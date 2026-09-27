package orca.tools.grok

import orca.agents.{Agent, AgentConfig, GrokAgent}
import orca.backend.AgentWiring
import orca.subprocess.{CliRunner, OsProcCliRunner}

/** The default grok agent and the grok model tiers. */
object GrokAgents:

  /** The default grok agent for a run: `grok-4.7` pinned; `.fast` opts down for
    * cheap one-shots. The backend's `workDir` is the run's `workDir`, so the
    * session probe looks where the spawns write (see [[GrokBackend.workDir]]).
    */
  def default(wiring: AgentWiring): GrokAgent = default(wiring, OsProcCliRunner)

  private[grok] def default(wiring: AgentWiring, cli: CliRunner): GrokAgent =
    Agent(
      backend = new GrokBackend(cli, workDir = wiring.workDir),
      config = AgentConfig(model = Some(GrokModels.Default)),
      prompts = wiring.prompts,
      events = wiring.events,
      interaction = wiring.interaction,
      defaultName = "main"
    )

  extension (agent: GrokAgent)
    def fast: GrokAgent = agent.withModel(GrokModels.BuildFast)
