package orca.backend.messagesjson

/** What differs between harnesses that speak the messages-JSON stream (claude's
  * `--output-format stream-json` shape): the names and texts
  * [[MessagesJsonDecoder]] routes on.
  */
private[orca] trait MessagesJsonDialect:

  /** The harness's name as failure and control-request texts show it. */
  def backendName: String

  /** Whether the `tool_use` block named `name` with input `rawInput` is the
    * agent calling orca's `ask_user` tool. Takes the input too, for a harness
    * that reaches MCP tools through a generic dispatcher tool.
    */
  def isAskUserCall(name: String, rawInput: String): Boolean

  /** The tool the harness injects to carry a `--json-schema` result, whose call
    * the decoder hides in structured mode; `None` when the harness has none.
    */
  def structuredOutputToolName: Option[String]

  /** The refused tool's name, if the failed `tool_result` body `content` is the
    * harness refusing a tool call for lack of permission.
    */
  def refusedTool(content: String): Option[String]
