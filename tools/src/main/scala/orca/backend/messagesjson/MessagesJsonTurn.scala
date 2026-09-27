package orca.backend.messagesjson

import orca.agents.BackendTag
import orca.backend.{DecodedTurn, DecodedTurnSpec, LiveTurn, StreamSource}
import orca.subprocess.PipedCliProcess

import ox.Ox

private[orca] object MessagesJsonTurn:

  /** Starts decoding `process`, a harness speaking `dialect`, into the caller's
    * turn scope.
    */
  def start[B <: BackendTag](
      process: PipedCliProcess,
      dialect: MessagesJsonDialect,
      spec: DecodedTurnSpec
  )(using Ox): LiveTurn[B] =
    DecodedTurn.start(
      StreamSource.fromProcess(process),
      spec,
      MessagesJsonDecoder[B](dialect, spec.outputSchema)
    )
