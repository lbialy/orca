package orca.tools.grok

import com.github.plokhotnyuk.jsoniter_scala.core.{
  JsonValueCodec,
  readFromString
}
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker

import scala.util.control.NonFatal

/** Recognises the `tool_result` grok sends back when it refused a tool call — a
  * deny rule, or a permission mode that can't ask — as opposed to a tool that
  * ran and failed.
  */
private[grok] object GrokRefusal:
  private val Denied =
    """Tool `([^`]+)` was not executed: Denied by permission policy.*""".r
  private val Cancelled =
    """User cancelled the execution for tool `([^`]+)`\.?""".r

  // grok's tool_result `content` is a string holding a JSON array of
  // `{"type":"content","content":{"type":"text","text":…}}` entries.
  private final case class Text(text: Option[String] = None)
  private final case class Entry(content: Option[Text] = None)
  private given JsonValueCodec[List[Entry]] = JsonCodecMaker.make

  /** The refused tool's name, if the whole of `content` is a refusal — bare, or
    * as the single text entry of grok's content array. Output that merely
    * quotes the phrase (a read of this file, a grep of a log) does not match.
    */
  def toolName(content: String): Option[String] =
    text(content).trim match
      case Denied(tool)    => Some(tool)
      case Cancelled(tool) => Some(tool)
      case _               => None

  private def text(content: String): String =
    val wrapped =
      try readFromString[List[Entry]](content)
      catch case NonFatal(_) => Nil
    wrapped match
      case List(Entry(Some(Text(Some(text))))) => text
      case _                                   => content
