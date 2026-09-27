package orca.tools.grok

import orca.AgentTurnFailed
import orca.agents.{BackendTag, Model, WireSessionId}
import orca.backend.{
  AgentResult,
  AskUserChannel,
  LiveTurn,
  TurnEvent,
  TurnEventConformance
}
import orca.backend.mcp.AskUserSession
import orca.events.{TurnDebit, Usage}
import orca.subprocess.FakePipedCliProcess
import orca.testkit.RepoRoot
import ox.channels.BufferCapacity
import ox.{Ox, supervised}

/** Replays the grok 1.0.41 recordings in
  * `docs/research/harness-integration/fixtures` through [[GrokTurn]] and pins
  * the outcomes `INTEGRATE_GROK_BUILD.md` §3 lists for each.
  */
class GrokTurnTest extends munit.FunSuite:

  private def liveTest(name: String)(body: Ox ?=> Unit): Unit =
    test(name)(supervised(body))

  /** A process whose stdout is the fixture `name`, already at EOF. */
  private def replay(name: String): FakePipedCliProcess =
    val process = new FakePipedCliProcess()
    os.read
      .lines(RepoRoot.dir / "docs/research/harness-integration/fixtures" / name)
      .filter(_.nonEmpty)
      .foreach(process.enqueueStdout)
    process.closeStdout()
    process.closeStderr()
    process

  /** Drains the turn's events, checks their grammar, and returns them with the
    * result.
    */
  private def succeed(
      live: LiveTurn[BackendTag.Grok.type]
  ): (List[TurnEvent], AgentResult[BackendTag.Grok.type]) =
    val events = live.events.toList
    TurnEventConformance.assertGrammar(events, completedNormally = true)
    live.awaitResult() match
      case Right(result)   => (events, result)
      case Left(cancelled) => fail(s"turn was cancelled: $cancelled")

  private def failure(
      live: LiveTurn[BackendTag.Grok.type]
  ): (List[TurnEvent], AgentTurnFailed) =
    val events = live.events.toList
    TurnEventConformance.assertGrammar(events, completedNormally = true)
    (events, intercept[AgentTurnFailed](live.awaitResult()))

  private def toolCalls(
      events: List[TurnEvent]
  ): List[TurnEvent.AssistantToolCall] =
    events.collect { case call: TurnEvent.AssistantToolCall => call }

  liveTest("a read + write turn settles with the result text, usage and model"):
    val (events, result) = succeed(GrokTurn(replay("grok-write.jsonl")))
    assertEquals(
      WireSessionId.value(result.wireId),
      "01a0e2b4-43af-70a2-9279-9bbff25a5505"
    )
    assertEquals(result.output, "hello\n\nCreated `b.txt` containing `bye`.")
    assertEquals(
      result.usage,
      Usage(
        freshInputTokens = 6815L,
        cacheReadInputTokens = 48512L,
        cacheWriteInputTokens = 0L,
        outputTokens = 566L,
        reasoningOutputTokens = 0L,
        cost = Some(BigDecimal("0.02807176")),
        apiCalls = Some(3L)
      )
    )
    // `result` carries no top-level model; it comes from `init`.
    assertEquals(result.model, Some(Model("grok-4.7-build-fast")))
    assertEquals(
      toolCalls(events).map(_.toolName),
      List("read_file", "write")
    )
    assertEquals(
      events.collect { case r: TurnEvent.ToolResult => r.ok },
      List(true, true)
    )

  liveTest("a --json-schema turn's output is the structured_output value"):
    val schema =
      """{"type":"object","properties":{"content":{"type":"string"},"lines":{"type":"integer"}}}"""
    val (_, result) =
      succeed(
        GrokTurn(replay("grok-schema.jsonl"), outputSchema = Some(schema))
      )
    assertEquals(
      result.output,
      """{"content":"a.txt contains one line:\n\nhello\n\nLine count: 1.","lines":5}"""
    )

  test("an MCP turn hides the ask_user call and result but shows search_tool"):
    supervised:
      given BufferCapacity = BufferCapacity(8)
      val live = GrokTurn(
        replay("grok-mcp.jsonl"),
        askUser = AskUserChannel.Mcp(AskUserSession.allocate())
      )
      val (events, result) = succeed(live)
      assertEquals(toolCalls(events).map(_.toolName), List("search_tool"))
      assert(
        !events.exists {
          case TurnEvent.ToolResult(_, _, content) =>
            content.contains("PINEAPPLE")
          case _ => false
        },
        s"the ask_user result must not render: $events"
      )
      assertEquals(result.output, "PINEAPPLE")

  liveTest("a dontAsk cancel fails the turn, reports the denial, debits usage"):
    val (events, failed) =
      failure(GrokTurn(replay("grok-dontask-cancel.jsonl")))
    assert(failed.getMessage.contains("grok"), failed.getMessage)
    assert(failed.getMessage.contains("cancelled"), failed.getMessage)
    assert(events.contains(TurnEvent.ToolDenied("write")), events)
    assertEquals(
      failed.debit,
      TurnDebit.Observed(
        Usage(
          freshInputTokens = 6261L,
          cacheReadInputTokens = 12032L,
          cacheWriteInputTokens = 0L,
          outputTokens = 168L,
          reasoningOutputTokens = 0L,
          cost = Some(BigDecimal("0.01329128")),
          apiCalls = Some(1L)
        ),
        Some(Model("grok-4.7-build-fast"))
      )
    )

  liveTest("an unknown model fails with grok's errors text and no debit"):
    val (_, failed) = failure(GrokTurn(replay("grok-badmodel.jsonl")))
    assert(
      failed.getMessage.contains("Couldn't set model 'nope-model'"),
      failed.getMessage
    )
    assertEquals(failed.debit, TurnDebit.Unobserved)
