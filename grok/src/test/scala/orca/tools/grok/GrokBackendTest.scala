package orca.tools.grok

import orca.agents.{
  AgentConfig,
  BackendTag,
  Model,
  SessionId,
  StructuredOutputMode,
  ToolSet,
  TurnDispatch,
  onWire
}
import orca.backend.{SupervisedBackend, SystemPromptComposer}
import orca.backend.mcp.AskUserMcpServer
import orca.subprocess.{FakePipedCliProcess, SpawnStubCliRunner}
import orca.testkit.{OpenTurn, TempDirs}

class GrokBackendTest extends munit.FunSuite:

  private val sid: SessionId[BackendTag.Grok.type] =
    SessionId[BackendTag.Grok.type]("01a0e2b4-43af-70a2-9279-9bbff25a5505")

  /** A clean turn: `init`, then a successful `result`. */
  private def successfulProcess(): FakePipedCliProcess =
    val p = new FakePipedCliProcess()
    p.enqueueStdout(
      s"""{"type":"system","subtype":"init","session_id":"${sid.value}","model":"grok-4.7-build-fast","tools":["read_file"]}"""
    )
    p.enqueueStdout(
      s"""{"type":"result","subtype":"success","is_error":false,"result":"done","session_id":"${sid.value}","usage":{"input_tokens":10,"output_tokens":5,"cache_read_input_tokens":0,"cache_creation_input_tokens":0},"total_cost_usd":0.001}"""
    )
    p.closeStdout()
    p.closeStderr()
    p.sendSigInt()
    p

  private def withBackend[T](
      runner: SpawnStubCliRunner,
      grokHome: os.Path = TempDirs.dir(),
      workDir: os.Path = TempDirs.dir()
  )(body: ox.Ox ?=> GrokBackend => T): T =
    SupervisedBackend.using(new GrokBackend(runner, grokHome, workDir))(body)

  /** The value following `flag` in `args`, failing when the flag is absent. */
  private def valueOf(args: Seq[String], flag: String): String =
    val idx = args.indexOf(flag)
    assert(idx >= 0, s"no $flag in $args")
    args(idx + 1)

  /** A turn's argv plus the contents of the files it pointed at, read while
    * they still exist — the turn deletes them when it ends.
    */
  private final case class Spawned(
      args: List[String],
      files: Map[String, String]
  )

  private def recordingRunner(
      processes: FakePipedCliProcess*
  ): (SpawnStubCliRunner, () => List[Spawned]) =
    val spawned = new java.util.concurrent.ConcurrentLinkedQueue[Spawned]()
    val runner = new SpawnStubCliRunner(
      processes.toList,
      onSpawn = args =>
        val files = Seq("--prompt-file", "--agent")
          .filter(args.contains)
          .map(flag => flag -> os.read(os.Path(valueOf(args, flag))))
          .toMap
        val _ = spawned.add(Spawned(args, files))
    )
    import scala.jdk.CollectionConverters.*
    (runner, () => spawned.asScala.toList)

  test("a fresh turn claims the id with --session-id, a second one resumes"):
    val runner =
      new SpawnStubCliRunner(List(successfulProcess(), successfulProcess()))
    withBackend(runner): backend =>
      val _ = backend.runAutonomous("first", sid, AgentConfig())
      val _ = backend.runAutonomous("again", sid, AgentConfig())
      val first = runner.calls(0)
      val second = runner.calls(1)
      assertEquals(valueOf(first, "--session-id"), sid.value)
      assert(!first.contains("--resume"), first)
      assertEquals(valueOf(second, "--resume"), sid.value)
      assert(!second.contains("--session-id"), second)

  test("the prompt goes in a --prompt-file outside the workDir"):
    val workDir = TempDirs.dir()
    val (runner, spawned) = recordingRunner(successfulProcess())
    withBackend(runner, workDir = workDir): backend =>
      val _ = backend.runAutonomous("summarize a.txt", sid, AgentConfig())
    val turn = spawned().head
    val promptFile = os.Path(valueOf(turn.args, "--prompt-file"))
    assert(!promptFile.startsWith(workDir), promptFile)
    assertEquals(turn.files("--prompt-file"), "summarize a.txt")
    assert(!os.exists(promptFile), "the prompt file goes when the turn ends")
    assertEquals(runner.spawnCalls.head.cwd, workDir)

  test("stdin closes straight away since the prompt travels in --prompt-file"):
    val process = successfulProcess()
    withBackend(new SpawnStubCliRunner(List(process))): backend =>
      val _ = backend.runAutonomous("q", sid, AgentConfig())
    assert(process.isStdinClosed, "stdin must close straight away")

  test("an interactive NoTools turn keeps use_tool for its ask_user server"):
    val (runner, spawned) = recordingRunner(successfulProcess())
    withBackend(runner): backend =>
      val _ = OpenTurn.interactive(backend)(
        "q",
        sid,
        displayPrompt = "q",
        AgentConfig(tools = ToolSet.NoTools),
        outputSchema = None
      )
    val turn = spawned().head
    assert(turn.files.contains("--agent"), turn.args)
    assert(valueOf(turn.args, "--rules").contains(AskUserMcpServer.Hint))
    val stripped = valueOf(turn.args, "--disallowed-tools").split(',').toSeq
    assert(!stripped.contains("use_tool"), stripped)
    assert(!stripped.contains("search_tool"), stripped)

  test("the composed system prompt travels in --rules"):
    val runner = new SpawnStubCliRunner(List(successfulProcess()))
    withBackend(runner): backend =>
      val _ = backend.runAutonomous(
        "q",
        sid,
        AgentConfig(systemPrompt = Some("be terse"), tools = ToolSet.ReadOnly)
      )
    val rules = valueOf(runner.calls.head, "--rules")
    assert(rules.contains("be terse"), rules)
    assert(rules.contains(SystemPromptComposer.ReadOnlyTurn), rules)
    assert(!rules.contains(AskUserMcpServer.Hint), rules)

  test("an autonomous turn attaches no --agent file and strips the meta-tools"):
    val runner = new SpawnStubCliRunner(List(successfulProcess()))
    withBackend(runner): backend =>
      val _ = backend.runAutonomous(
        "q",
        sid,
        AgentConfig(tools = ToolSet.ReadOnly)
      )
    val args = runner.calls.head
    assert(!args.contains("--agent"), args)
    assert(
      valueOf(args, "--disallowed-tools").split(',').contains("use_tool"),
      args
    )

  test(
    "an interactive turn attaches ask_user through an --agent file with list-form mcpServers"
  ):
    val workDir = TempDirs.dir()
    val (runner, spawned) = recordingRunner(successfulProcess())
    withBackend(runner, workDir = workDir): backend =>
      val _ = OpenTurn.interactive(backend)(
        "q",
        sid,
        displayPrompt = "q",
        AgentConfig(tools = ToolSet.ReadOnly),
        outputSchema = None
      )
    val turn = spawned().head
    val agentFile = os.Path(valueOf(turn.args, "--agent"))
    assert(!agentFile.startsWith(workDir), agentFile)
    assert(agentFile.last.startsWith("orca-grok-agent-"), agentFile)
    val definition = turn.files("--agent")
    assert(
      """(?s)mcpServers:\n  - name: orca\n    type: http\n    url: http://127\.0\.0\.1:\d+/mcp\n    headers: \[\]\n""".r
        .findFirstIn(definition)
        .isDefined,
      definition
    )
    assert(valueOf(turn.args, "--rules").contains(AskUserMcpServer.Hint))
    // The meta-tools stay: `use_tool` is how grok reaches `ask_user`.
    assert(!turn.args.contains("--disallowed-tools"), turn.args)

  test("the --model pin and --json-schema reach the argv"):
    val schema = """{"type":"object"}"""
    val runner = new SpawnStubCliRunner(List(successfulProcess()))
    withBackend(runner): backend =>
      val _ = backend.runAutonomous(
        "q",
        sid,
        AgentConfig(model = Some(GrokModels.BuildFast)),
        outputSchema = Some(schema)
      )
    val args = runner.calls.head
    assertEquals(valueOf(args, "--model"), "grok-4.7-build-fast")
    assertEquals(valueOf(args, "--json-schema"), schema)

  test(
    "grok reports structured output as raw text and cheaps out on build-fast"
  ):
    withBackend(new SpawnStubCliRunner(Nil)): backend =>
      assertEquals(backend.structuredOutputMode, StructuredOutputMode.RawText)
      assertEquals(
        backend.cheapModel(Some(Model("grok-4.7"))),
        Some(GrokModels.BuildFast)
      )

  test("the session directory is the whole real path, percent-encoded"):
    val parent = TempDirs.dir()
    val real = parent / "real dir"
    os.makeDir(real)
    val link = parent / "link"
    os.symlink(link, real)
    val expected =
      real.toNIO.toRealPath().toString.replace("/", "%2F").replace(" ", "%20")
    assertEquals(GrokBackend.sessionDirName(link), expected)
    assert(expected.startsWith("%2F"), expected)

  test("the probe finds a session's chat_history.jsonl, even an empty one"):
    // A turn that failed before the model ran still holds the id; the probe
    // must report it present so the next turn resumes instead of re-claiming.
    val grokHome = TempDirs.dir()
    val workDir = TempDirs.dir()
    val dir = grokHome / "sessions" / GrokBackend.sessionDirName(workDir) /
      sid.value
    os.makeDir.all(dir)
    os.write(dir / "chat_history.jsonl", "")
    withBackend(new SpawnStubCliRunner(Nil), grokHome, workDir): backend =>
      backend.sessions.rehydrate(sid, sid.onWire)
      assertEquals(
        backend.sessions.dispatchFor(sid).asTurnDispatch,
        TurnDispatch.Resumed
      )

  test("the probe reports a session with no chat_history.jsonl gone"):
    val grokHome = TempDirs.dir()
    val workDir = TempDirs.dir()
    os.makeDir.all(
      grokHome / "sessions" / GrokBackend.sessionDirName(workDir) / sid.value
    )
    withBackend(new SpawnStubCliRunner(Nil), grokHome, workDir): backend =>
      backend.sessions.rehydrate(sid, sid.onWire)
      assertEquals(
        backend.sessions.dispatchFor(sid).asTurnDispatch,
        TurnDispatch.Fresh
      )
