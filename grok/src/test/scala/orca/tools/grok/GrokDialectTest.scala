package orca.tools.grok

import orca.agents.BackendTag
import orca.backend.{Step, TurnEvent}
import orca.backend.messagesjson.MessagesJsonDecoder

class GrokDialectTest extends munit.FunSuite:

  // The two calls grok-mcp.jsonl records, in order.
  private val searchInput = """{"query":"orca ask_user","limit":5}"""
  private val useInput =
    """{"tool_name":"orca__ask_user","tool_input":{"question":"what is the secret word?"}}"""

  /** The events the shared decoder emits for a recorded grok fixture. */
  private def decode(fixtureName: String): Vector[TurnEvent] =
    val fixture = orca.testkit.RepoRoot.dir / os.RelPath(
      s"docs/research/harness-integration/fixtures/$fixtureName"
    )
    // BackendTag.Grok doesn't exist yet; the tag only types the session id.
    // Switch it when Grok is registered.
    val decoder = MessagesJsonDecoder[BackendTag.ClaudeCode.type](
      GrokDialect,
      outputSchema = None
    )
    os.read
      .lines(fixture)
      .filter(_.nonEmpty)
      .foldLeft((decoder.init, Vector.empty[TurnEvent])) {
        case ((state, acc), line) =>
          decoder.line(state, line) match
            case Step.Continue(next, emitted)  => (next, acc ++ emitted)
            case Step.Settle(next, emitted, _) => (next, acc ++ emitted)
      }
      ._2

  test("use_tool naming orca__ask_user is the ask_user call"):
    assert(GrokDialect.isAskUserCall("use_tool", useInput))

  test("the search_tool lookup before it stays visible"):
    assert(!GrokDialect.isAskUserCall("search_tool", searchInput))

  test("use_tool reaching another MCP tool is not ask_user"):
    assert(
      !GrokDialect.isAskUserCall(
        "use_tool",
        """{"tool_name":"other__tool","tool_input":{}}"""
      )
    )

  test("only use_tool dispatches: another tool with the same input is not"):
    assert(!GrokDialect.isAskUserCall("orca__ask_user", useInput))

  test("unparseable or nameless input reads as not ask_user"):
    assert(!GrokDialect.isAskUserCall("use_tool", "not json"))
    assert(!GrokDialect.isAskUserCall("use_tool", "{}"))
    assert(!GrokDialect.isAskUserCall("use_tool", """{"tool_name":7}"""))

  test("decoding grok-mcp.jsonl hides the ask_user call, keeps search_tool"):
    val events = decode("grok-mcp.jsonl")
    val calls = events.collect { case c: TurnEvent.AssistantToolCall => c }
    assert(calls.exists(_.toolName == "search_tool"), events.toString)
    assert(!calls.exists(_.toolName == "use_tool"), events.toString)
    // Only search_tool's result renders: the ask_user answer echo is dropped.
    val results = events.collect { case r: TurnEvent.ToolResult => r }
    assertEquals(results.size, 1, events.toString)

  test("grok's 'unknown' model placeholder maps to no model"):
    assertEquals(GrokDialect.normaliseModel("unknown"), None)
    assertEquals(
      GrokDialect.normaliseModel("grok-4.7-build-fast"),
      Some("grok-4.7-build-fast")
    )

  test("the dontAsk fixture's cancelled write decodes as a denial"):
    val events = decode("grok-dontask-cancel.jsonl")
    assert(events.contains(TurnEvent.ToolDenied("write")), events.toString)
