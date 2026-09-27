package orca.tools.grok

class GrokDialectTest extends munit.FunSuite:

  // The two calls grok-mcp.jsonl records, in order.
  private val searchInput = """{"query":"orca ask_user","limit":5}"""
  private val useInput =
    """{"tool_name":"orca__ask_user","tool_input":{"question":"what is the secret word?"}}"""

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

  test("grok's 'unknown' model placeholder maps to no model"):
    assertEquals(GrokDialect.normaliseModel("unknown"), None)
    assertEquals(
      GrokDialect.normaliseModel("grok-4.7-build-fast"),
      Some("grok-4.7-build-fast")
    )
