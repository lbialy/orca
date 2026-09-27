package orca.tools.grok

import orca.agents.Model

/** The grok model tiers orca pins by name (`grok models`, grok 1.0.41). */
private[orca] object GrokModels:

  /** The model bare `grok` runs. */
  val Default: Model = Model("grok-4.7")

  /** The cheap-and-fast tier. */
  val BuildFast: Model = Model("grok-4.7-build-fast")
