package orca.tools.grok

import com.github.plokhotnyuk.jsoniter_scala.core.readFromString
import com.github.plokhotnyuk.jsoniter_scala.macros.ConfiguredJsonValueCodec
import orca.agents.{
  AgentConfig,
  AutoApprove,
  BackendTag,
  Model,
  SessionId,
  ToolSet,
  WireSessionId,
  onWire
}
import orca.backend.{
  ChannelEvent,
  Dispatch,
  ResumeOrigin,
  SupervisedBackend,
  SystemPromptComposer,
  TurnEvent,
  TurnMode
}
import orca.subprocess.{OsProcCliRunner, QuietProc}
import orca.testkit.{GitRepo, OpenTurn, TempDirs}

/** End-to-end tests against the real `grok` CLI. Gated on the
  * `ORCA_INTEGRATION` environment variable so `sbt test` without the flag
  * behaves like a pure unit suite. Requires `grok` to be installed and logged
  * in. Every call is billed, so the prompts stay one line and the model is the
  * cheap build one unless `ORCA_GROK_MODEL` says otherwise.
  */
class GrokIntegrationTest extends munit.FunSuite:

  override def munitTests(): Seq[Test] =
    if sys.env.contains("ORCA_INTEGRATION") then super.munitTests()
    else Nil

  override def munitTimeout: scala.concurrent.duration.Duration =
    import scala.concurrent.duration.DurationInt
    3.minutes

  private def withBackend(workDir: os.Path = GitRepo.seeded())(
      body: ox.Ox ?=> GrokBackend => Unit
  ): Unit =
    SupervisedBackend.using(
      new GrokBackend(OsProcCliRunner, workDir = workDir)
    )(body)

  private val model: Model =
    Model(sys.env.getOrElse("ORCA_GROK_MODEL", "grok-4.7-build-fast"))

  private val unsandboxed: AgentConfig =
    AgentConfig().copy(autoApprove = AutoApprove.All, model = Some(model))

  private def fresh = SessionId.fresh[BackendTag.Grok.type]

  test("a fresh turn replies and reports the id it claimed"):
    withBackend(): backend =>
      val session = fresh
      val result = backend.runAutonomous(
        prompt = "Reply with the single word: READY",
        session = session,
        config = unsandboxed
      )
      assert(
        result.output.toUpperCase.contains("READY"),
        s"expected output to contain READY, got: ${result.output}"
      )
      assert(WireSessionId.value(result.wireId).nonEmpty)
      assertEquals(WireSessionId.value(result.wireId), session.value)

  test("a resumed call carries conversational context across turns"):
    withBackend(): backend =>
      val session = fresh
      val _ = backend.runAutonomous(
        prompt = "Remember the number 42. Reply with the single word: stored.",
        session = session,
        config = unsandboxed
      )
      val second = backend.runAutonomous(
        prompt =
          "What number did I ask you to remember? Reply with just the number.",
        session = session,
        config = unsandboxed
      )
      assert(
        second.output.contains("42"),
        s"expected the resumed turn to recall 42, got: ${second.output}"
      )

  test("a turn that failed before the model ran resumes as unprompted"):
    // `-m nope-model` claims the id and writes a history with no prompt in
    // it: the retry must `--resume` (a re-claim is refused) and read as
    // unprompted, so the flow layer re-sends the seed. Once a real turn lands,
    // the session continues normally, and a backend with nothing recorded
    // probes grok's own transcript as live.
    val repo = GitRepo.seeded()
    withBackend(repo): backend =>
      val session = fresh
      val _ = intercept[orca.AgentTurnFailed]:
        backend.runAutonomous(
          prompt = "Reply with the single word: READY",
          session = session,
          config = unsandboxed.copy(model = Some(Model("nope-model")))
        )
      assertEquals(
        backend.sessions.dispatchFor(session),
        Dispatch.Resume(session.onWire, ResumeOrigin.Unprompted)
      )
      val result = backend.runAutonomous(
        prompt = "Reply with the single word: READY",
        session = session,
        config = unsandboxed
      )
      assert(
        result.output.toUpperCase.contains("READY"),
        s"expected the resumed turn to answer, got: ${result.output}"
      )
      assertEquals(
        backend.sessions.dispatchFor(session),
        Dispatch.Resume(session.onWire, ResumeOrigin.ThisAttempt)
      )
      assertEquals(
        new GrokBackend(OsProcCliRunner, workDir = repo).sessions
          .dispatchFor(session),
        Dispatch.Resume(session.onWire, ResumeOrigin.EarlierAttempt)
      )

  test("a read-only turn leaves the filesystem unchanged"):
    val repo = GitRepo.seeded()
    val before = os.walk(repo).filterNot(_.startsWith(repo / ".git")).toSet
    withBackend(repo): backend =>
      val _ = backend.runAutonomous(
        prompt = "Create a file named made.txt containing hi, then run the " +
          "shell command `touch touched.txt`.",
        session = fresh,
        config = unsandboxed.copy(tools = ToolSet.ReadOnly)
      )
    val after = os.walk(repo).filterNot(_.startsWith(repo / ".git")).toSet
    assertEquals(after, before)
    assertEquals(os.read(repo / "seed.txt"), "seed")

  test("the ReadOnly allowlist reaches grok with every name intact"):
    // grok drops unknown `--tools` names silently, so a renamed read tool
    // would vanish from ReadOnly turns; an exact set also catches any extra
    // tool (a new mutating one) slipping past the allowlist.
    val config = unsandboxed.copy(tools = ToolSet.ReadOnly)
    val tools = initTools(
      config,
      GrokArgs.wiring(config.tools, config.autoApprove, TurnMode.Autonomous)
    )
    assertEquals(tools, GrokArgs.ReadTools.toSet)

  test("a structured turn returns JSON matching the schema"):
    withBackend(): backend =>
      val result = backend.runAutonomous(
        prompt = "What is 2 + 3? Put the number in `answer`.",
        session = fresh,
        config = unsandboxed,
        outputSchema = Some(
          """{"type":"object","properties":{"answer":{"type":"integer"}},"required":["answer"],"additionalProperties":false}"""
        )
      )
      assertEquals(readFromString[Answer](result.output), Answer(5))

  test("an interactive turn's ask_user question reaches orca and back"):
    // Settles whether grok's `server/discover` before `initialize` upsets the
    // chimp-based McpHost: a fatal rejection would leave grok without the tool.
    withBackend(): backend =>
      val live = OpenTurn.interactive(backend)(
        prompt = "Call the orca ask_user tool to ask me for a codeword, then " +
          "reply with only the codeword I give.",
        session = fresh,
        displayPrompt = "ask for a codeword",
        config = unsandboxed,
        outputSchema = None
      )
      try
        val questions = live.events.collect {
          case TurnEvent.Question(ChannelEvent.UserQuestion(q, respond)) =>
            respond("PERIWINKLE")
            q
        }.toList
        val Right(result) = live.awaitResult(): @unchecked
        assert(questions.nonEmpty, "grok never called orca's ask_user")
        assert(
          result.output.toUpperCase.contains("PERIWINKLE"),
          s"expected the answer to reach the model, got: ${result.output}"
        )
      finally live.cancel()

  /** Run grok with the argv [[GrokArgs]] renders for `config` and `wiring`, and
    * return the `tools` its `system.init` line announces.
    */
  private def initTools(
      config: AgentConfig,
      wiring: GrokArgs.Wiring
  ): Set[String] =
    val workDir = TempDirs.dir()
    val promptFile = TempDirs.dir() / "prompt.md"
    os.write(promptFile, "Reply: ok")
    val args = GrokArgs.argv(
      config,
      promptFile,
      SystemPromptComposer.combine(config, None),
      GrokArgs.sessionArgs(
        orca.backend.Dispatch.Fresh(
          Some(WireSessionId[BackendTag.Grok.type](fresh.value))
        )
      ),
      wiring,
      outputSchema = None,
      agentFile = None
    )
    val run = QuietProc.call(args, cwd = workDir, stdin = "")
    assertEquals(
      run.exitCode,
      0,
      s"grok exited ${run.exitCode}; stderr: ${run.err.text()}"
    )
    val stdout = run.out.lines()
    val init = stdout
      .find(_.contains("\"subtype\":\"init\""))
      .getOrElse(fail(s"no init line in grok's output: $stdout"))
    readFromString[InitTools](init).tools.toSet

private case class InitTools(tools: List[String])
    derives ConfiguredJsonValueCodec

private case class Answer(answer: Int) derives ConfiguredJsonValueCodec
