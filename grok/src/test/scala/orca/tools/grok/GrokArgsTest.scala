package orca.tools.grok

import orca.agents.{
  AgentConfig,
  AutoApprove,
  Enforcement,
  Model,
  ToolSet,
  TurnDispatch
}
import orca.backend.{Dispatch, TurnMode}

class GrokArgsTest extends munit.FunSuite:

  private val approveShapes: Seq[AutoApprove] =
    Seq(
      AutoApprove.All,
      AutoApprove.Only(Set("write")),
      AutoApprove.Only(Set())
    )

  private val interactive: TurnMode = TurnMode.Interactive("hi")
  private val modes: Seq[TurnMode] = Seq(TurnMode.Autonomous, interactive)

  /** Every `(tools, autoApprove, mode)` combination. */
  private val allCells =
    for
      tools <- ToolSet.values.toSeq
      approve <- approveShapes
      mode <- modes
    yield (tools, approve, mode)

  private def valuesOf(args: Seq[String], flag: String): Seq[String] =
    args.sliding(2).collect { case Seq(`flag`, v) => v }.toSeq

  private def listOf(args: Seq[String], flag: String): Seq[String] =
    valuesOf(args, flag).flatMap(_.split(',').toSeq)

  private def wiring(
      tools: ToolSet,
      approve: AutoApprove = AutoApprove.All,
      mode: TurnMode = TurnMode.Autonomous
  ): GrokArgs.Wiring =
    GrokArgs.wiring(tools, approve, mode)

  test("every allowlist tier renders one non-empty --tools of known tools"):
    for
      (tools, approve, mode) <- allCells
      if tools != ToolSet.Full
    do
      val args = GrokArgs.wiring(tools, approve, mode).args
      val clue = s"$tools $approve $mode: $args"
      val values = valuesOf(args, "--tools")
      assertEquals(values.size, 1, clue)
      assert(values.head.nonEmpty, clue)
      val names = values.head.split(',').toSeq
      assert(names.forall(_.nonEmpty), clue)
      assert(names.forall(GrokTools.Known), clue)
      assert(args.contains("--no-subagents"), clue)

  test("--disallowed-tools only ever names known tools, never empty"):
    for (tools, approve, mode) <- allCells do
      val args = GrokArgs.wiring(tools, approve, mode).args
      val clue = s"$tools $approve $mode: $args"
      val values = valuesOf(args, "--disallowed-tools")
      assert(values.size <= 1, clue)
      values.foreach { v =>
        val names = v.split(',').toSeq
        assert(v.nonEmpty && names.forall(GrokTools.Known), clue)
      }

  test("ReadOnly allows the read tools and strips the MCP meta-tools"):
    val args = wiring(ToolSet.ReadOnly).args
    assertEquals(
      args,
      Seq(
        "--tools",
        "read_file,grep,list_dir",
        "--no-subagents",
        "--disallowed-tools",
        "search_tool,use_tool,ask_user_question," +
          GrokTools.Background.mkString(",")
      )
    )

  test("NetworkOnly adds the web tools to the ReadOnly allowlist"):
    val args = wiring(ToolSet.NetworkOnly).args
    assertEquals(
      valuesOf(args, "--tools"),
      Seq("read_file,grep,list_dir,web_search,web_fetch")
    )
    assert(
      listOf(args, "--disallowed-tools").contains("use_tool"),
      args.toString
    )

  test("interactive turns keep search_tool and use_tool reachable"):
    for tools <- ToolSet.values do
      val stripped =
        listOf(wiring(tools, mode = interactive).args, "--disallowed-tools")
      assert(!stripped.contains("search_tool"), s"$tools: $stripped")
      assert(!stripped.contains("use_tool"), s"$tools: $stripped")

  test("NoTools names todo_write and then disallows it"):
    for mode <- modes do
      val args = wiring(ToolSet.NoTools, mode = mode).args
      assertEquals(valuesOf(args, "--tools"), Seq("todo_write"))
      assert(listOf(args, "--disallowed-tools").contains("todo_write"), args)
      assert(args.contains("--no-subagents"), args.toString)

  test("autonomous NoTools turns also disallow the meta-tools"):
    val stripped = listOf(wiring(ToolSet.NoTools).args, "--disallowed-tools")
    assert(Seq("search_tool", "use_tool").forall(stripped.contains), stripped)

  test("sessionArgs refuses a fresh dispatch with no claim id"):
    val _ = intercept[IllegalStateException](
      GrokArgs.sessionArgs(Dispatch.Fresh(None))
    )

  test("autonomous turns strip ask_user_question and background tools"):
    for tools <- ToolSet.values do
      val stripped = listOf(wiring(tools).args, "--disallowed-tools")
      val expected = "ask_user_question" +: GrokTools.Background
      assert(expected.forall(stripped.contains), s"$tools: $stripped")

  test("interactive Full turns still strip grok's own ask_user_question"):
    for approve <- approveShapes do
      val args = wiring(ToolSet.Full, approve, interactive)
      assertEquals(
        args.args,
        Seq("--always-approve", "--disallowed-tools", "ask_user_question")
      )

  test("every Full turn renders --always-approve and no --tools"):
    for
      (tools, approve, mode) <- allCells
      if tools == ToolSet.Full
    do
      val args = GrokArgs.wiring(tools, approve, mode).args
      assert(args.contains("--always-approve"), args.toString)
      assert(!args.contains("--tools"), args.toString)

  test("the enforcement row: Hard everywhere but Full with an Only set"):
    for
      (tools, approve, mode) <- allCells
      dispatch <- TurnDispatch.values.toSeq
    do
      val expected = (tools, approve) match
        case (ToolSet.Full, AutoApprove.Only(_)) => Enforcement.Ignored
        case _                                   => Enforcement.Hard
      val clue = s"$tools $approve $dispatch"
      assertEquals(
        GrokArgs.enforcementCell(tools, approve, dispatch).level,
        expected,
        clue
      )
      assertEquals(
        GrokArgs.wiring(tools, approve, mode).cell.level,
        expected,
        clue
      )

  test("every rationale cites the grok build and the probe date"):
    for (tools, approve, _) <- allCells do
      val rationale =
        GrokArgs.enforcementCell(tools, approve, TurnDispatch.Fresh).rationale
      assert(rationale.contains("grok 1.0.41"), rationale)
      assert(rationale.contains("2026-09-27"), rationale)

  private val promptFile = os.Path("/tmp/orca-grok-prompt.md")

  private def argv(
      config: AgentConfig = AgentConfig(),
      schema: Option[String] = None,
      agentFile: Option[os.Path] = None
  ): Seq[String] =
    GrokArgs.argv(
      config,
      promptFile,
      "the rules",
      Seq("--session-id", "sid-1"),
      wiring(config.tools),
      schema,
      agentFile
    )

  test("argv renders the headless streaming invocation"):
    val args = argv(AgentConfig(model = Some(GrokModels.BuildFast)))
    assertEquals(
      args,
      Seq(
        "grok",
        "--prompt-file",
        "/tmp/orca-grok-prompt.md",
        "--output-format",
        "streaming-messages-json",
        "--include-partial-messages",
        "--rules",
        "the rules",
        "--model",
        "grok-4.7-build-fast",
        "--session-id",
        "sid-1"
      ) ++ wiring(ToolSet.Full).args
    )

  test("argv omits --model, --json-schema and --agent when unset"):
    val args = argv()
    Seq("--model", "--json-schema", "--agent", "--no-auto-update").foreach {
      flag => assert(!args.contains(flag), s"$flag in $args")
    }

  test("argv passes the schema inline and the agent file path"):
    val schema = """{"type":"object"}"""
    val args = argv(
      AgentConfig(model = Some(Model("grok-4.7"))),
      schema = Some(schema),
      agentFile = Some(os.Path("/tmp/orca-grok-agent.md"))
    )
    assertEquals(valuesOf(args, "--json-schema"), Seq(schema))
    assertEquals(valuesOf(args, "--agent"), Seq("/tmp/orca-grok-agent.md"))

  test("each allowlist tier's rationale names the --tools value it renders"):
    for tools <- Seq(ToolSet.ReadOnly, ToolSet.NetworkOnly) do
      val w = wiring(tools)
      val rendered = valuesOf(w.args, "--tools").head
      assert(
        w.cell.rationale.contains(s"`--tools $rendered`"),
        w.cell.rationale
      )
