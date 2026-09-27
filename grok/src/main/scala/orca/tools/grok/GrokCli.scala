package orca.tools.grok

import orca.agents.{AutoApprove, ToolSet}
import orca.backend.TurnMode

/** grok invocation pieces for callers outside the grok module. */
private[orca] object GrokCli:

  /** The flags for a tool-less headless one-shot (the shell's slug suggestion):
    * an autonomous NoTools turn's, so the empty-`--tools` workaround keeps one
    * home in [[GrokArgs.wiring]].
    */
  val noTools: Seq[String] =
    GrokArgs.wiring(ToolSet.NoTools, AutoApprove.All, TurnMode.Autonomous).args
