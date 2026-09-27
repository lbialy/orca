package orca.backend.messagesjson

import orca.agents.{BackendTag, Model}
import orca.backend.{Settled, Step, TurnEvent, TurnEventConformance}
import orca.events.{TurnDebit, Usage}
import orca.testkit.Usages.usage

/** The dialect hooks, exercised on synthetic lines through a dialect that turns
  * each one on or off per test.
  */
class MessagesJsonDecoderTest extends munit.FunSuite:

  private type B = BackendTag.ClaudeCode.type

  private final case class TestDialect(
      unknownModel: Option[String],
      zeroUsage: ZeroUsage
  ) extends MessagesJsonDialect:
    def backendName: String = "testharness"
    def isAskUserCall(name: String, rawInput: String): Boolean = false
    def structuredOutputToolName: Option[String] = None
    def refusedTool(content: String): Option[String] = None
    def normaliseModel(reported: String): Option[String] =
      Option.unless(unknownModel.contains(reported))(reported)

  private val plain =
    TestDialect(unknownModel = None, zeroUsage = ZeroUsage.MeasuredZero)
  private val grokLike =
    TestDialect(
      unknownModel = Some("unknown"),
      zeroUsage = ZeroUsage.Unobserved
    )

  /** Feeds `lines` until one settles; the turn's events and its outcome. */
  private def run(
      dialect: MessagesJsonDialect,
      lines: String*
  ): (List[TurnEvent], Settled[B]) =
    val decoder = MessagesJsonDecoder[B](dialect, outputSchema = None)
    def loop(
        state: MessagesJsonDecoder.State,
        rest: List[String],
        events: Vector[TurnEvent]
    ): (List[TurnEvent], Settled[B]) = rest match
      case Nil => fail(s"no line settled the turn; events: $events")
      case line :: tail =>
        decoder.line(state, line) match
          case Step.Continue(next, emitted) =>
            loop(next, tail, events ++ emitted)
          case Step.Settle(_, emitted, outcome) =>
            ((events ++ emitted).toList, outcome)
    loop(decoder.init, lines.toList, Vector.empty)

  private def failed(outcome: Settled[B]): Settled.Failed[B] = outcome match
    case f: Settled.Failed[B] => f
    case other                => fail(s"expected a failed settle, got $other")

  private def succeeded(outcome: Settled[B]): orca.backend.AgentResult[B] =
    outcome match
      case Settled.Succeeded(result) => result
      case other => fail(s"expected a successful settle, got $other")

  private val init =
    """{"type":"system","subtype":"init","session_id":"sid-1","model":"nope-model","tools":[]}"""

  private val zeroUsage =
    """"usage":{"input_tokens":0,"output_tokens":0,"cache_creation_input_tokens":0,"cache_read_input_tokens":0,"server_tool_use":{"web_search_requests":0}},"total_cost_usd":0.0"""

  // The grok-badmodel.jsonl shape: an empty body, the reason only in `errors`.
  private val badModelResult =
    s"""{"type":"result","subtype":"error_during_execution","session_id":"sid-1","is_error":true,$zeroUsage,"errors":["Couldn't set model 'nope-model': not found"]}"""

  test("errors stand in for an empty is_error body, displayed and settled"):
    val (events, outcome) = run(grokLike, init, badModelResult)
    val reason =
      "testharness reported is_error (subtype error_during_execution): " +
        "Couldn't set model 'nope-model': not found"
    assertEquals(events, List(TurnEvent.Error(reason)))
    assertEquals(
      failed(outcome).message,
      s"testharness turn failed (subtype error_during_execution, session sid-1): $reason"
    )
    TurnEventConformance.assertGrammar(events, completedNormally = false)

  test("errors join a non-empty body on the settle only, several joined"):
    val (events, outcome) = run(
      plain,
      """{"type":"result","subtype":"error_during_execution","session_id":"sid-1","result":"boom","is_error":true,"errors":["cancelled","again"]}"""
    )
    assertEquals(events, List(TurnEvent.Error("boom")))
    assertEquals(
      failed(outcome).message,
      "testharness turn failed (subtype error_during_execution, session sid-1): " +
        "boom (errors: cancelled; again)"
    )

  test("all-zero usage settles an unobserved debit when the dialect says so"):
    val (_, outcome) = run(grokLike, init, badModelResult)
    assertEquals(failed(outcome).debit, TurnDebit.Unobserved)

  test("all-zero usage stays an observed zero when the dialect says not"):
    val (_, outcome) = run(plain, init, badModelResult)
    assertEquals(
      failed(outcome).debit,
      TurnDebit.Observed(
        usage(0L, 0L, Some(BigDecimal("0.0"))),
        Some(Model("nope-model"))
      )
    )

  test("all-zero usage on a success reports no usage"):
    val (events, outcome) = run(
      grokLike,
      init,
      """{"type":"assistant","message":{"id":"m1","content":[{"type":"text","text":"hi"}]}}""",
      s"""{"type":"result","subtype":"success","session_id":"sid-1","result":"hi",$zeroUsage}"""
    )
    // The response id still counts: it was observed, the tokens were not.
    assertEquals(
      succeeded(outcome).usage,
      Usage.empty.copy(apiCalls = Some(1L))
    )
    TurnEventConformance.assertGrammar(events, completedNormally = true)

  test("a usage with any non-zero axis stays observed under the policy"):
    val (_, costOnly) = run(
      grokLike,
      """{"type":"result","subtype":"success","session_id":"sid-1","usage":{"input_tokens":0,"output_tokens":0},"total_cost_usd":0.01}"""
    )
    assertEquals(
      succeeded(costOnly).usage,
      usage(0L, 0L, Some(BigDecimal("0.01")))
    )
    val (_, tokens) = run(
      grokLike,
      """{"type":"result","subtype":"success","session_id":"sid-1","usage":{"input_tokens":0,"output_tokens":0,"cache_read_input_tokens":5},"total_cost_usd":0.0}"""
    )
    assertEquals(
      succeeded(tokens).usage,
      usage(5L, 0L, Some(BigDecimal("0.0")), cacheRead = 5L)
    )

  test("the dialect's unknown-model placeholder is no model, in result"):
    val (_, outcome) = run(
      grokLike,
      """{"type":"result","subtype":"success","session_id":"sid-1","result":"ok","model":"unknown"}"""
    )
    assertEquals(succeeded(outcome).model, None)

  test("a placeholder result model falls back to init's model"):
    val (_, outcome) = run(
      grokLike,
      """{"type":"system","subtype":"init","session_id":"sid-1","model":"grok-4.7-build-fast"}""",
      """{"type":"result","subtype":"success","session_id":"sid-1","result":"ok","model":"unknown"}"""
    )
    assertEquals(succeeded(outcome).model, Some(Model("grok-4.7-build-fast")))

  test("the placeholder is no model in init either"):
    val (_, outcome) = run(
      grokLike,
      """{"type":"system","subtype":"init","session_id":"sid-1","model":"unknown"}""",
      """{"type":"result","subtype":"success","session_id":"sid-1","result":"ok"}"""
    )
    assertEquals(succeeded(outcome).model, None)

  test("an identity dialect keeps a model named unknown"):
    val (_, outcome) = run(
      plain,
      """{"type":"result","subtype":"success","session_id":"sid-1","result":"ok","model":"unknown"}"""
    )
    assertEquals(succeeded(outcome).model, Some(Model("unknown")))

  test("unknown content blocks in an assistant message render nothing"):
    val (events, outcome) = run(
      plain,
      """{"type":"assistant","message":{"id":"m1","content":[{"type":"server_tool_use","id":"s1","name":"web_search","input":{}},{"type":"web_search_tool_result","tool_use_id":"s1","content":[]},{"type":"text","text":"found it"}]}}""",
      """{"type":"result","subtype":"success","session_id":"sid-1","result":"found it"}"""
    )
    assertEquals(
      events,
      List(
        TurnEvent.AssistantTextDelta("found it"),
        TurnEvent.AssistantMessageEnd
      )
    )
    assertEquals(succeeded(outcome).output, "found it")
    TurnEventConformance.assertGrammar(events, completedNormally = true)
