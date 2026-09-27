package orca.tools.grok

/** The grok tool names orca's flags and decoder refer to — the only place they
  * are spelled. Each is a name grok 1.0.41 lists in the `system.init` `tools`
  * array (`INTEGRATE_GROK_BUILD.md` §5); a name grok doesn't know would make
  * `--tools` stop filtering at all.
  */
private[grok] object GrokTools:
  val ReadFile = "read_file"
  val Grep = "grep"
  val ListDir = "list_dir"
  val WebSearch = "web_search"
  val WebFetch = "web_fetch"
  val TodoWrite = "todo_write"

  /** The MCP meta-tools: MCP tools are found with `search_tool` and called
    * through `use_tool`.
    */
  val SearchTool = "search_tool"
  val UseTool = "use_tool"

  /** grok's own question tool. Headless, it answers "no user is available" at
    * once, so it would swallow a question orca's `ask_user` should carry.
    */
  val AskUserQuestion = "ask_user_question"

  /** Tools that start background work the turn boundary abandons. */
  val Background: Seq[String] =
    Seq("scheduler_create", "scheduler_delete", "scheduler_list", "monitor")

  /** orca's `ask_user` as grok names it inside `use_tool`'s `tool_name`: the
    * MCP server name, a double underscore, then the tool name.
    */
  val OrcaAskUser = "orca__ask_user"

  /** Every built-in name above, for tests pinning that the flags only name
    * tools grok knows.
    */
  val Known: Set[String] =
    Set(
      ReadFile,
      Grep,
      ListDir,
      WebSearch,
      WebFetch,
      TodoWrite,
      SearchTool,
      UseTool,
      AskUserQuestion
    ) ++ Background
