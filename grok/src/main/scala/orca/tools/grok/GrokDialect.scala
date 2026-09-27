package orca.tools.grok

import com.github.plokhotnyuk.jsoniter_scala.core.{
  JsonValueCodec,
  readFromString
}
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import orca.backend.messagesjson.{MessagesJsonDialect, ZeroUsage}

import scala.util.control.NonFatal

/** How grok's `streaming-messages-json` differs from claude's stream-json. */
private[grok] object GrokDialect extends MessagesJsonDialect:
  def backendName: String = "grok"

  private final case class UseToolInput(tool_name: Option[String] = None)
  private given JsonValueCodec[UseToolInput] = JsonCodecMaker.make

  // MCP tools are reached through the `use_tool` meta-tool, which names the
  // target in its input. The `search_tool` lookup before it stays visible.
  def isAskUserCall(name: String, rawInput: String): Boolean =
    name == GrokTools.UseTool && {
      try
        readFromString[UseToolInput](rawInput).tool_name
          .contains(GrokTools.OrcaAskUser)
      catch case NonFatal(_) => false
    }

  // Under `--json-schema` grok constrains the reply itself and reports the
  // value in `result.structured_output`; no tool carries it.
  def structuredOutputToolName: Option[String] = None

  def refusedTool(content: String): Option[String] =
    GrokRefusal.toolName(content)

  def normaliseModel(reported: String): Option[String] =
    Option.unless(reported == "unknown")(reported)

  // A turn rejected before the model ran (an unknown `-m`) still carries a
  // `usage` object, all zeros.
  def zeroUsage: ZeroUsage = ZeroUsage.Unobserved
