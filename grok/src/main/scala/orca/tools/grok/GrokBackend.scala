package orca.tools.grok

import orca.agents.{BackendTag, Model, StructuredOutputMode}
import orca.backend.{
  AgentBackend,
  AskUserChannel,
  IdScheme,
  LiveTurn,
  SessionSupport,
  SubprocessSpawn,
  SystemPromptComposer,
  TurnRequest,
  TurnResources
}
import orca.backend.mcp.{AskUserMcpServer, AskUserSession}
import orca.subprocess.CliRunner
import ox.{Ox, ResourceScope}

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Grok Build backend (`INTEGRATE_GROK_BUILD.md`). Every turn is one headless
  * `grok --prompt-file … --output-format streaming-messages-json` process,
  * decoded by [[GrokTurn]]; the flags come from [[GrokArgs]].
  *
  * An interactive turn also stands up orca's `ask_user` MCP server and hands it
  * to grok in a per-turn `--agent` definition file — the only route that
  * attaches an MCP server without editing the user's config or trusting the
  * project folder (§6).
  */
private[orca] class GrokBackend(
    cli: CliRunner,
    /** Where grok keeps its sessions: `GROK_HOME`, else `~/.grok`. */
    private[grok] val grokHome: os.Path = GrokBackend.defaultGrokHome,
    /** Shared between the spawn path ([[open]]) and the existence probe (see
      * [[sessions]]): grok keys sessions by the cwd it ran in. The `os.pwd`
      * default serves only bare/test construction; the runtime passes the
      * flow's real `workDir`.
      */
    override val workDir: os.Path = os.pwd
) extends AgentBackend[BackendTag.Grok.type]:

  val tag: BackendTag.Grok.type = BackendTag.Grok

  export GrokArgs.enforcementCell

  /** grok constrains the reply itself under `--json-schema` and injects no tool
    * to carry it, so the reply text is the JSON value.
    */
  override def structuredOutputMode: StructuredOutputMode =
    StructuredOutputMode.RawText

  def cheapModel(leading: Option[Model]): Option[Model] =
    Some(GrokModels.BuildFast)

  /** [[IdScheme.ClientClaimed]]: a fresh turn claims orca's id with
    * `--session-id`, later ones `--resume` it. Durable, since grok keeps the
    * transcript on disk under `grokHome`.
    *
    * The probe only asks whether `chat_history.jsonl` exists. A turn that
    * failed before the model ran (an unknown `-m`) still leaves a non-empty
    * history and holds the id (grok 1.0.41, probed 2026-09-27): a stricter
    * probe would report it gone, orca would re-claim it, and grok would refuse
    * with "already in use" every time.
    */
  val sessions: SessionSupport[BackendTag.Grok.type] =
    SessionSupport.durable(
      IdScheme.ClientClaimed,
      id =>
        os.exists(
          grokHome / "sessions" / GrokBackend.sessionDirName(workDir) / id /
            "chat_history.jsonl"
        )
    )

  /** Writes the prompt to a temp file, spawns `grok` in [[workDir]] and wraps
    * it in a [[GrokTurn]]. grok reads the prompt from `--prompt-file`, so stdin
    * closes straight away.
    *
    * The composed system prompt travels in `--rules` on every turn, so
    * interactive and autonomous turns carry the same rules. An interactive turn
    * adds the `ask_user` hint there and attaches the MCP server through
    * [[writeAgentFile]]; an autonomous one has no one to answer, so it gets
    * neither.
    */
  override protected[orca] def open(
      turn: TurnRequest[BackendTag.Grok.type]
  )(using Ox): LiveTurn[BackendTag.Grok.type] =
    import turn.*
    val askUser: Option[AskUserSession] =
      Option.when(mode.isInteractive)(AskUserSession.allocate())
    val promptFile =
      TurnResources.tempFile(
        prompt,
        prefix = "orca-grok-prompt-",
        suffix = ".md"
      )
    val agentFile = askUser.map(session => writeAgentFile(session.server.url))
    val rules = SystemPromptComposer.combine(
      config,
      askUser.map(_ => AskUserMcpServer.Hint)
    )
    SubprocessSpawn.open("grok", events) {
      val args = GrokArgs.argv(
        config,
        promptFile,
        rules,
        GrokArgs.sessionArgs(dispatch),
        GrokArgs.wiring(config.tools, config.autoApprove, mode),
        outputSchema,
        agentFile
      )
      cli.spawnPiped(args, cwd = workDir)
    } { process =>
      process.closeStdin()
      GrokTurn(
        process,
        openingPrompt = mode.openingPrompt,
        outputSchema = outputSchema,
        askUser =
          askUser.fold(AskUserChannel.Unavailable)(AskUserChannel.Mcp(_))
      )
    }

  /** A temp `--agent` definition attaching orca's `ask_user` server at `url`,
    * outside [[workDir]] like the prompt file.
    *
    * `mcpServers` must be a YAML list — the map form fails with "expected a
    * sequence". The body replaces only the stock profile's browser-verification
    * block (grok 1.0.41, probed 2026-09-27), so it stays a neutral one-liner;
    * orca's rules go in `--rules`. `model: inherit` keeps `--model` in charge.
    */
  private def writeAgentFile(url: String)(using ResourceScope): os.Path =
    TurnResources.tempFile(
      GrokBackend.agentDefinition(url),
      prefix = "orca-grok-agent-",
      suffix = ".md"
    )

private[grok] object GrokBackend:

  private def defaultGrokHome: os.Path =
    sys.env
      .get("GROK_HOME")
      .map(os.Path(_, os.pwd))
      .getOrElse(os.home / ".grok")

  /** The directory grok files `cwd`'s sessions under: the percent-encoded REAL
    * path, `/` included (`/tmp/x` → `%2Fprivate%2Ftmp%2Fx` on macOS, where
    * `/tmp` links to `/private/tmp`). Only `/` was seen on the wire; the rest
    * follows RFC 3986's unreserved set. Throws if `cwd` doesn't exist, which
    * the probe reads as "gone".
    */
  def sessionDirName(cwd: os.Path): String =
    URLEncoder
      .encode(cwd.toNIO.toRealPath().toString, StandardCharsets.UTF_8)
      .replace("+", "%20")
      .replace("*", "%2A")
      .replace("%7E", "~")

  def agentDefinition(url: String): String =
    s"""---
       |name: orca-turn
       |description: Orca-driven coding turn.
       |prompt_mode: full
       |model: inherit
       |agents_md: true
       |mcpServers:
       |  - name: ${AskUserMcpServer.ServerName}
       |    type: http
       |    url: $url
       |    headers: []
       |---
       |
       |You are driven by the Orca orchestrator.
       |""".stripMargin
