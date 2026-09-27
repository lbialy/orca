package orca.tools.grok

import orca.agents.BackendTag
import orca.backend.{AskUserChannel, DecodedTurnSpec, LiveTurn}
import orca.backend.messagesjson.MessagesJsonTurn
import orca.subprocess.PipedCliProcess

import ox.Ox

private[grok] object GrokTurn:

  /** Starts decoding `process`, grok's `streaming-messages-json` stdout, into
    * the caller's turn scope.
    */
  def apply(
      process: PipedCliProcess,
      openingPrompt: Option[String] = None,
      outputSchema: Option[String] = None,
      askUser: AskUserChannel = AskUserChannel.Unavailable
  )(using Ox): LiveTurn[BackendTag.Grok.type] =
    MessagesJsonTurn.start(
      process,
      GrokDialect,
      DecodedTurnSpec(
        openingPrompt = openingPrompt,
        outputSchema = outputSchema,
        askUser = askUser
      )
    )
