package orca.tools.grok

import orca.agents.{AgentConfig, DefaultPrompts}
import orca.backend.{AgentWiring, SupervisedBackend}
import orca.events.OrcaListener
import orca.subprocess.{FakePipedCliProcess, SpawnStubCliRunner}
import orca.testkit.{TempDirs, TestAgent}
import orca.tools.grok.GrokAgents.fast

class GrokAgentsTest extends munit.FunSuite:

  // LLM `run` is gated on `InStage`; mint the token for the suite.
  private given orca.InStage = orca.InStage.unsafe

  private def successfulProcess(): FakePipedCliProcess =
    val p = new FakePipedCliProcess()
    p.enqueueStdout(
      """{"type":"system","subtype":"init","session_id":"s","model":"grok-4.7"}"""
    )
    p.enqueueStdout(
      """{"type":"result","subtype":"success","is_error":false,"result":"ok","session_id":"s","usage":{"input_tokens":1,"output_tokens":1,"cache_read_input_tokens":0,"cache_creation_input_tokens":0}}"""
    )
    p.closeStdout()
    p.closeStderr()
    p.sendSigInt()
    p

  test("fast opts the model down to grok-4.7-build-fast"):
    val runner = new SpawnStubCliRunner(List(successfulProcess()))
    SupervisedBackend.using(new GrokBackend(runner)): backend =>
      val agent = TestAgent(
        backend,
        config = AgentConfig(model = Some(GrokModels.Default))
      )
      val _ = agent.fast.run("q")
    assert(
      runner.calls.head.containsSlice(Seq("--model", "grok-4.7-build-fast")),
      runner.calls.head
    )

  test("the default agent pins grok-4.7 and spawns in the run's workDir"):
    val workDir = TempDirs.dir()
    val wiring = AgentWiring(
      events = OrcaListener.noop,
      interaction = TestAgent.UnusedInteraction,
      workDir = workDir,
      prompts = DefaultPrompts
    )
    val runner = new SpawnStubCliRunner(List(successfulProcess()))
    val agent = GrokAgents.default(wiring, runner)
    assertEquals(agent.config.model, Some(GrokModels.Default))
    val _ = agent.run("q")
    assertEquals(runner.spawnCalls.head.cwd, workDir)
    assert(
      runner.calls.head.containsSlice(Seq("--model", "grok-4.7")),
      runner.calls.head
    )
