package orca.tools.claude

import orca.agents.BackendTag
import orca.backend.{AskUserChannel, DecodedTurnSpec, LiveTurn}
import orca.backend.messagesjson.{
  MessagesJsonDialect,
  MessagesJsonTurn,
  ZeroUsage
}
import orca.subprocess.PipedCliProcess

import ox.Ox

/** How claude's stream-json differs from the other messages-JSON harnesses. */
private[claude] object ClaudeDialect extends MessagesJsonDialect:
  def backendName: String = "claude"

  def isAskUserCall(name: String, rawInput: String): Boolean =
    name == ClaudeBackend.AskUserToolName

  def structuredOutputToolName: Option[String] =
    Some(ClaudeBackend.StructuredOutputToolName)

  def refusedTool(content: String): Option[String] =
    PermissionRefusal.toolName(content)

  def normaliseModel(reported: String): Option[String] = Some(reported)

  // A claude turn that sent no request carries no `usage` object at all.
  def zeroUsage: ZeroUsage = ZeroUsage.MeasuredZero

private[claude] object ClaudeTurn:

  /** Starts decoding `process` into the caller's turn scope. */
  def apply(
      process: PipedCliProcess,
      openingPrompt: Option[String] = None,
      outputSchema: Option[String] = None,
      askUser: AskUserChannel = AskUserChannel.Unavailable
  )(using Ox): LiveTurn[BackendTag.ClaudeCode.type] =
    MessagesJsonTurn.start(
      process,
      ClaudeDialect,
      DecodedTurnSpec(
        openingPrompt = openingPrompt,
        outputSchema = outputSchema,
        askUser = askUser
      )
    )
