package orca.tools.grok

import orca.backend.{CliArgs, Dispatch, TurnMode}
import orca.agents.{
  AgentConfig,
  AutoApprove,
  BackendTag,
  Enforcement,
  EnforcementCell,
  ToolSet,
  TurnDispatch,
  WireSessionId
}

/** Maps `AgentConfig` fields to `grok` headless CLI flags
  * (`INTEGRATE_GROK_BUILD.md` §2, §5). The system prompt travels in `--rules`
  * and the prompt itself in a `--prompt-file`, both composed by the backend;
  * the `ask_user` MCP server arrives through an `--agent` definition file.
  *
  * grok's headless permission mode depends on the machine (`bypassPermissions`
  * on one probe, `default` on another), so every tier renders explicit flags:
  * an omitted flag is never a safe no-op.
  */
private[grok] object GrokArgs:
  import GrokTools.*

  private val ReadTools: Seq[String] = Seq(ReadFile, Grep, ListDir)
  private val WebTools: Seq[String] = Seq(WebSearch, WebFetch)
  private val MetaTools: Seq[String] = Seq(SearchTool, UseTool)

  /** The tier's flags and how strongly they hold, built together so the two
    * cannot drift.
    */
  final case class Wiring(args: Seq[String], cell: EnforcementCell)

  /** What the allowlist tiers' Hard rests on, and the gap it leaves: an
    * interactive turn keeps the meta-tools for orca's `ask_user`, and nothing
    * then stops `use_tool` reaching other MCP servers the user configured.
    */
  private def allowlistCell(allowlist: Seq[String]): EnforcementCell =
    EnforcementCell(
      Enforcement.Hard,
      s"`--tools ${allowlist.mkString(",")}` left init `tools` at the allowlist plus the MCP meta-tools, which autonomous turns strip with `--disallowed-tools search_tool,use_tool`; with the read tools alone (grok 1.0.41, probed 2026-09-27) the agent, asked to write, called `use_tool Write` / `use_tool Shell`, both were rejected as unknown and no file appeared. Interactive turns keep the meta-tools for orca's `ask_user`, so an MCP server from the user's own grok config stays reachable through `use_tool` — unprobed"
    )

  /** Maps `(tools, autoApprove)` to grok's flags for a `mode` turn. An
    * interactive turn attaches orca's `ask_user` MCP server, which the
    * meta-tools must keep reaching; an autonomous one attaches none, and no
    * human answers it, so the meta-tools go along with the tools that start
    * background work or ask a question.
    */
  def wiring(
      tools: ToolSet,
      autoApprove: AutoApprove,
      mode: TurnMode
  ): Wiring =
    val (meta, unattended) = mode match
      case TurnMode.Autonomous     => (MetaTools, AskUserQuestion +: Background)
      case TurnMode.Interactive(_) => (Nil, Nil)
    tools match
      case ToolSet.ReadOnly =>
        Wiring(
          allow(ReadTools) ++ disallow(meta ++ unattended),
          allowlistCell(ReadTools)
        )
      case ToolSet.NetworkOnly =>
        Wiring(
          allow(ReadTools ++ WebTools) ++ disallow(meta ++ unattended),
          allowlistCell(ReadTools ++ WebTools)
        )
      // An empty `--tools` disables filtering, so name a real tool and then
      // disallow it. An interactive turn keeps the meta-tools, its only route
      // to orca's `ask_user`, as the allowlist tiers do.
      case ToolSet.NoTools =>
        Wiring(
          allow(Seq(TodoWrite)) ++
            disallow(TodoWrite +: (meta ++ unattended)),
          EnforcementCell(
            Enforcement.Hard,
            "`--tools todo_write --disallowed-tools todo_write,search_tool,use_tool` (grok 1.0.41, probed 2026-09-27) left init `tools: []`; a bare `--tools \"\"` would instead have kept every tool. Interactive turns keep `search_tool,use_tool` for orca's `ask_user`, which also leaves other MCP servers from the user's grok config reachable — unprobed"
          )
        )
      // `--always-approve` is mandatory: without it, headless `default` mode
      // cancels the whole turn on the first edit. grok's own question tool
      // goes on every Full turn, interactive ones included — there it would
      // answer "no user is available" instead of reaching orca's `ask_user`.
      case ToolSet.Full =>
        val args =
          "--always-approve" +: disallow(AskUserQuestion +: unattended)
        autoApprove match
          case AutoApprove.All =>
            Wiring(
              args,
              EnforcementCell(
                Enforcement.Hard,
                "`--always-approve` honours \"approve everything\" verbatim: shell and edits ran without prompting (grok 1.0.41, probed 2026-09-27)"
              )
            )
          case AutoApprove.Only(_) =>
            Wiring(
              args,
              EnforcementCell(
                Enforcement.Ignored,
                "headless grok can't ask for an approval: under `--permission-mode dontAsk` an out-of-set `write` came back \"User cancelled the execution\" and ended the whole turn (grok 1.0.41, probed 2026-09-27), so any `Only` set is widened to `--always-approve` and reaches the CLI nowhere"
              )
            )

  /** `--tools <names>` plus `--no-subagents`. `names` must be real tool names:
    * grok treats an empty or unknown-only `--tools` as no filter at all.
    */
  private def allow(names: Seq[String]): Seq[String] =
    require(names.nonEmpty, "an empty --tools disables grok's tool filter")
    Seq("--tools", names.mkString(","), "--no-subagents")

  private def disallow(names: Seq[String]): Seq[String] =
    if names.isEmpty then Nil
    else Seq("--disallowed-tools", names.mkString(","))

  /** How strongly grok enforces each `(tools, autoApprove)` combination — see
    * [[wiring]], which derives this from the same match that builds the flags.
    * The cell doesn't depend on the mode, which only strips extra tools.
    */
  def enforcementCell(
      tools: ToolSet,
      autoApprove: AutoApprove,
      dispatch: TurnDispatch
  ): EnforcementCell = dispatch match
    // Same either way: a resumed turn re-applies every flag. A session created
    // under `--always-approve` and resumed with the ReadOnly flags came back
    // with init `tools` of the three read tools and `permissionMode:"default"`
    // (grok 1.0.41, probed 2026-09-27). Matched rather than ignored so a new
    // dispatch has to answer here.
    case TurnDispatch.Fresh | TurnDispatch.Resumed =>
      wiring(tools, autoApprove, TurnMode.Autonomous).cell

  /** One headless turn. cwd is set on the process spawn, so it isn't rendered;
    * `sessionFlags` are the turn's [[sessionArgs]].
    */
  def argv(
      config: AgentConfig,
      promptFile: os.Path,
      rules: String,
      sessionFlags: Seq[String],
      wiring: Wiring,
      outputSchema: Option[String],
      agentFile: Option[os.Path]
  ): Seq[String] =
    Seq(
      "grok",
      "--prompt-file",
      promptFile.toString,
      "--output-format",
      "streaming-messages-json",
      "--include-partial-messages",
      "--rules",
      rules
    ) ++
      CliArgs.modelArgs(config) ++
      sessionFlags ++
      wiring.args ++
      CliArgs.flag("--json-schema", outputSchema)(identity) ++
      CliArgs.flag("--agent", agentFile)(_.toString)

  /** Fresh dispatch → `--session-id <uuid>`, creating the session under orca's
    * id; resume → `--resume <uuid>` (grok refuses to re-claim an id it holds).
    */
  def sessionArgs(dispatch: Dispatch[BackendTag.Grok.type]): Seq[String] =
    dispatch match
      case Dispatch.Fresh(Some(id)) =>
        Seq("--session-id", WireSessionId.value(id))
      // Unreachable: grok is `IdScheme.ClientClaimed`, which always supplies
      // the claim id on a fresh dispatch. Fails loudly rather than letting grok
      // mint an id orca never learns.
      case Dispatch.Fresh(None) =>
        throw new IllegalStateException(
          "grok's ClientClaimed scheme must supply a fresh claim id"
        )
      case Dispatch.Resume(id, _) => Seq("--resume", WireSessionId.value(id))
