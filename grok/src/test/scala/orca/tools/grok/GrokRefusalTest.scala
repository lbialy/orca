package orca.tools.grok

class GrokRefusalTest extends munit.FunSuite:

  test("extracts the tool name from a deny-rule refusal"):
    assertEquals(
      GrokRefusal.toolName(
        "Tool `run_terminal_command` was not executed: Denied by permission policy (Bash(touch *))"
      ),
      Some("run_terminal_command")
    )

  test("extracts the tool name from a cancelled execution"):
    assertEquals(
      GrokRefusal.toolName("User cancelled the execution for tool `write`"),
      Some("write")
    )

  // The shape grok-dontask-cancel.jsonl carries, after the decoder has
  // unwrapped the outer JSON string.
  test("unwraps grok's single-entry content array"):
    assertEquals(
      GrokRefusal.toolName(
        """[{"type":"content","content":{"type":"text","text":"User cancelled the execution for tool `search_replace`"}}]"""
      ),
      Some("search_replace")
    )

  test("returns None when the phrase is quoted inside other output"):
    assertEquals(
      GrokRefusal.toolName(
        "app.log:12: User cancelled the execution for tool `write`\napp.log:13: done"
      ),
      None
    )
    assertEquals(
      GrokRefusal.toolName(
        "grep hit: Tool `write` was not executed: Denied by permission policy"
      ),
      None
    )

  test("returns None when a content array holds more than the refusal"):
    assertEquals(
      GrokRefusal.toolName(
        """[{"type":"content","content":{"type":"text","text":"User cancelled the execution for tool `write`"}},{"type":"content","content":{"type":"text","text":"more"}}]"""
      ),
      None
    )

  test("returns None for an unrelated tool error"):
    assertEquals(GrokRefusal.toolName("No such file or directory"), None)
    assertEquals(GrokRefusal.toolName("[not json"), None)
