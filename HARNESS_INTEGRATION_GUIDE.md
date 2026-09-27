# Harness integration guide

How Orca drives a coding-agent CLI ("harness"), what contract a new harness
must meet, and a checklist for deciding whether a given CLI can meet it.
Companion documents apply this guide to two concrete CLIs:
[INTEGRATE_CURSOR_AGENT.md](INTEGRATE_CURSOR_AGENT.md) and
[INTEGRATE_GROK_BUILD.md](INTEGRATE_GROK_BUILD.md).

Written against `master` at `49eec660` (orca 0.1.10). Paths are relative to the
repo root. Read [AGENTS.md](AGENTS.md) first — it owns the runtime invariants
(stage-bound capabilities, sessions, tool enforcement, the vocabulary) that
this guide only references. [ADR 0003](adr/0003-pluggable-llm-backends.md),
[0006](adr/0006-stream-json-conversation-driver.md),
[0007](adr/0007-codex-exec-jsonl-driver.md),
[0014](adr/0014-opencode-server-driver.md) and
[0015](adr/0015-gemini-stream-json-driver.md) record why the existing drivers
look the way they do.

---

## 1. Where a harness plugs in

```
flow script ── planningAgent / codingAgent / reviewAgent / claude / codex / …
   │
   ▼
Agent[B]            (tools/.../agents/Agent.scala)   final class; builders, run/chat/session
   │                AgentCall modes, TurnAccounting, retries, prompt assembly (Prompts)
   ▼
AgentBackend[B]     (tools/.../backend/AgentBackend.scala)   ◄── THE SPI YOU IMPLEMENT
   │  final runAutonomous / runInteractive: turn-entry gate, dispatch, enforcement
   │  notice, per-turn `supervised` scope, drain/drive, session commit, teardown
   │  abstract open(turn): LiveTurn[B]
   ▼
LiveTurn[B]         usually DecodedTurn.start(StreamSource, DecodedTurnSpec, LineDecoder)
   │  reader fork folds the decoder over stdout lines, drains stderr, enforces the
   │  TurnEvent message grammar, settles the outcome, SIGINT + tree-kill teardown
   ▼
LineDecoder[B, S]   (tools/.../backend/LineDecoder.scala)   ◄── AND THIS
   │  one CLI's wire protocol as a pure fold: line → Step.Continue | Step.Settle
   ▼
the CLI subprocess  (CliRunner.spawnPiped, via SubprocessSpawn.open)
```

The SPI is `private[orca]`, so **a new harness is a module inside this repo**
(`orca-<name>`, package `orca.tools.<name>`), not a plugin. Every existing
harness is a subprocess-per-turn driver except opencode, which runs HTTP+SSE
against one `opencode serve` per run (its `StreamSource` is an SSE connection,
not a process).

A module usually consists of:

| File | Role | Example |
|---|---|---|
| `<Name>Backend.scala` | `AgentBackend` impl: `open`, `sessions`, `tag`, `enforcementCell`, `structuredOutputMode`, `cheapModel` | `gemini/.../GeminiBackend.scala` |
| `<Name>Args.scala` | argv builder + the enforcement classification derived **from the same match** that builds the flags | `gemini/.../GeminiArgs.scala` |
| `<Name>Decoder.scala` | `LineDecoder` + a `<Name>Turn.apply` wrapping `DecodedTurn.start` | `gemini/.../GeminiDecoder.scala` |
| `jsonl/InboundEvent.scala` (or `streamjson/`) | wire DTOs, jsoniter codecs, `parse(line)` with an `Unknown` fallback | `gemini/.../jsonl/InboundEvent.scala` |
| `<Name>Models.scala` | the model ids orca pins | `gemini/.../GeminiModels.scala` |
| `<Name>Agents.scala` | public `default(wiring)` factory + tier extensions (`.flash`, `.haiku`) | `gemini/.../GeminiAgents.scala` |
| optional config/probe helpers | settings-file merge, session listing, on-disk store | `GeminiSettings`, `GeminiSessionList`, `pi/.../PiSessionStore.scala` |

---

## 2. The `AgentBackend` contract

`tools/src/main/scala/orca/backend/AgentBackend.scala`. Abstract members, all
mandatory (deliberately undefaulted so a new backend cannot silently skip one):

### 2.1 `open(turn: TurnRequest[B])(using Ox): LiveTurn[B]`

Start **one turn** and return it live. The framework owns everything else
(close gate, dispatch, enforcement notice, drain, commit, cancel). `open` must:

1. **Allocate per-turn resources through `TurnResources`** (temp files, MCP
   hosts, config edits) so they are released when the turn scope ends, also
   when `open` itself throws. Temp files go under `$TMPDIR` (`orca-*`), **never
   inside `workDir`** — a hard kill that skips cleanup must leave nothing a stage
   commit can sweep up.
2. **Build the argv** from `turn.config`, `turn.dispatch`, `turn.outputSchema`,
   `turn.mode` (see §3).
3. **Spawn through `SubprocessSpawn.open(label, turn.events)(spawn)(build)`**,
   which registers the environment-cookie sweep and kills the process tree if
   `build` throws. Spawn with `cli.spawnPiped(args, env, cwd = workDir)`.
4. **Deliver the prompt and close stdin.** Claude writes one stream-json user
   message then closes stdin; gemini closes stdin immediately (prompt is on
   argv). A CLI left waiting on stdin never exits.
5. **Wrap the process**: `DecodedTurn.start(StreamSource.fromProcess(p),
   DecodedTurnSpec(mode.openingPrompt, outputSchema, askUser), decoder)`.

Failures before the spawn throw plain `OrcaFlowException` (retryable); failures
after the turn ran surface as `AgentTurnFailed` from `awaitResult` (not retried:
the wire session may already exist).

### 2.2 `workDir: os.Path`

Fixed for the backend's lifetime; every spawn and every session probe uses it.
Most CLIs key their session store by cwd, so the probe and the spawn must agree
on the exact path (watch for symlinks: macOS `/tmp` → `/private/tmp`; CLIs that
hash or encode the cwd usually use the *real* path).

### 2.3 `sessions: SessionSupport[B]`

`tools/src/main/scala/orca/backend/SessionSupport.scala`. Two choices:

- **`IdScheme`**
  - `ClientClaimed` — orca mints a UUID (`SessionId.fresh`) and the CLI creates
    the conversation *under that id* (claude `--session-id`, pi per-id
    `--session-dir`). `Dispatch.Fresh(Some(id))` puts it on the wire; resume
    uses the same id. Preferred: the id is known before the turn, so an
    interrupted first turn is still recoverable (see "held claim" in
    `SessionSupport.heldClaim`).
  - `ServerMinted` — the CLI mints the id; the decoder learns it from the stream
    (codex `thread.started`, gemini `init`) and returns it as
    `AgentResult.wireId`. `Dispatch.Fresh(None)` puts nothing on the wire.
- **Durability**: `SessionSupport.durable(scheme, probe)` (every backend today)
  or `ephemeral(scheme)`. The probe answers "does the CLI still hold
  conversation `id` for `workDir`?" and **must not create, mutate or resume**
  anything. It is called with ids that passed `SessionId.isSafe`
  (`[A-Za-z0-9_-]{1,200}`), so it may build paths from them. A throwing probe
  reads as "gone" (re-seed, always safe).

Requirements on the CLI:

| Requirement | Why |
|---|---|
| Continue a conversation by id, non-interactively, in the same cwd | `Dispatch.Resume` → next turn's argv |
| Report the session id on the stream (at least for `ServerMinted`) | commit after drain |
| Ids match `[A-Za-z0-9_-]{1,200}` | `SessionId.isSafe` guards every write door; an unsafe id fails the turn (autonomous) or is dropped (interactive) |
| A durable, side-effect-free existence check | resume across process restarts (`.orca/cache/runs/<key>.sessions.json`) |
| Per-invocation flags (tools, permissions, model) re-applied on resume | the enforcement cell is classified per dispatch; a CLI that keeps the *creation-time* sandbox on resume gets a separate `Resumed` classification (codex) |

A `ClientClaimed` CLI that *refuses* a second claim of an existing id (claude,
pi, grok) is handled: the framework resumes a held claim as `EarlierAttempt`.
A CLI that *silently creates* a conversation under an unknown resume id
(cursor) makes Fresh and Resume argv identical — also fine, but the probe is
then the only thing distinguishing them.

### 2.4 `tag: B`

A new case on `enum BackendTag` (`tools/.../agents/BackendTag.scala`). Persisted
by case name in session records and attempt manifests — pick the name once.

### 2.5 `enforcementCell(tools, autoApprove, dispatch): EnforcementCell`

How strongly the flags this backend builds hold the restriction a
`(ToolSet, AutoApprove)` pair requests, on a `Fresh` or `Resumed` turn. Levels
(`tools/.../agents/Enforcement.scala`): `Hard` (mechanically blocked; may be a
named superset), `SandboxApprox`, `PromptOnly` (only the standing prompt
forbids it), `Ignored`. The `rationale` string is the only home of the
reasoning and should cite the flag, CLI version and probe date.

Rules that reviewers enforce:

- Derive the cell **from the same match that builds the argv** (see
  `GeminiArgs.approvalWiring`, `ClaudeArgs.permissionWiring`): one function
  returns `(args, cell)` so they cannot drift.
- **Never claim `Hard` without a probe** against a write attempt. An
  unmeasured mode is `PromptOnly` with a rationale saying "UNMEASURED"
  (gemini's plan mode is the model).
- Match `dispatch` exhaustively.
- `EnforcementTableTest` renders the full product into AGENTS.md; add your
  backend there and paste the regenerated table.

What the tiers mean for the CLI:

| `ToolSet` | Must achieve | Typical mechanism |
|---|---|---|
| `ReadOnly` | reads only: no edits, no shell (planners, reviewers) | tool allowlist, deny rules, read-only permission mode |
| `NetworkOnly` | ReadOnly + read-only web (planner reading an issue) | allowlist + web tools; hand-maintained grant list in AGENTS.md |
| `Full` + `AutoApprove.All` | everything, no prompts | bypass / yolo / force |
| `Full` + `AutoApprove.Only(set)` | the set auto-approves, the rest would prompt (interactive only) | per-tool allow rules; often unavailable → `Ignored` |
| `NoTools` | no tools, no MCP servers (`cheapOneShot`) | empty allowlist; strict MCP |

The standing prompt rules (`SystemPromptComposer`: `ReadOnlyTurn`,
`NoToolsTurn`, `RuntimeOwnsGit`, `BackgroundWorkAbandonedAtTurnEnd`) are
appended on every backend regardless, which is what makes a `PromptOnly` cell
true by construction.

### 2.6 `structuredOutputMode: StructuredOutputMode`

How a `resultAs[O]` payload arrives (`tools/.../agents/StructuredOutputMode.scala`):

- `Tool` — the CLI injects a structured-output tool and the payload never
  arrives as reply text (claude `--json-schema`, opencode `format:
  json_schema`). The prompt names the tool, does not repeat the schema.
- `RawText` — the final reply text IS the JSON. The prompt ships the schema and
  raw-JSON rules (codex, gemini, pi). Use this when the CLI has no schema flag,
  **and also when it constrains decoding but still returns the JSON as text**.

`AgentResult.output` must be the payload: `ResponseParser.parse` tolerates
fences and prose but tries JSON objects from the right, so return the final
answer (or the CLI's validated structured value), not a concatenation of every
message if you can avoid it.

### 2.7 `cheapModel(leading: Option[Model]): Option[Model]`

The model `agent.cheap` pins (branch names, commit summaries, reviewer
selection). `None` when there is no cheaper tier; `cheap` is then the agent
itself. Whatever model a backend pins by default must have a row in
`flow/.../events/Pricing.scala` (`DefaultModelsPricedTest`) unless the CLI
reports cost.

---

## 3. What a turn asks for: `TurnRequest`

`tools/src/main/scala/orca/backend/TurnRequest.scala`.

| Field | What the backend must do |
|---|---|
| `prompt` | Full wire message (templates, schema rules already applied). Deliver it without a size cliff: stdin or a temp `--prompt-file` beats argv (`ARG_MAX`, and variadic flags such as claude's `--tools` must not swallow a positional prompt). |
| `session`, `dispatch` | `Fresh(Some(id))` → claim flag; `Fresh(None)` → nothing; `Resume(wireId, _)` → resume flag. Never put a client id on the wire under `ServerMinted` (the `Option` exists to stop that). |
| `mode` | `Autonomous`: no human — never expose `ask_user`; the drain auto-denies any `ApproveTool` and auto-answers any `Question`. `Interactive(display)`: stand up the `ask_user` MCP bridge (`AskUserSession.allocate()`) and pass `askUser = AskUserChannel.Mcp(session)`; `openingPrompt` renders the user's text. |
| `config.model` | `--model <id>` (`CliArgs.modelArgs`). |
| `config.systemPrompt` + standing rules | `SystemPromptComposer.combine(config, extraHint)` → an append-system-prompt flag/file if the CLI has one; otherwise `SystemPromptComposer.foldIntoPrompt` (codex, gemini) which prefixes `System guidance:` to every turn. Never *replace* the CLI's own system prompt. |
| `config.tools`, `config.autoApprove` | the permission wiring of §2.5. |
| `config.selfManagedGit` | already folded into the composed prompt (`RuntimeOwnsGit` omitted); nothing extra. |
| `config.networkTools` | claude-only today (`NetworkTools`); other backends document their fixed `NetworkOnly` grant in AGENTS.md. |
| `outputSchema` | pass to the CLI's schema flag if it has one, and suppress any CLI-injected structured-output tool echo in the decoder. |
| `events` | only for what the backend reports outside the turn's own events (the cookie sweep). |

`config.retrySchedule` is the framework's (`AgentCall`), not the backend's.

---

## 4. Decoding the wire: `LineDecoder`

`tools/src/main/scala/orca/backend/LineDecoder.scala`, `DecodedTurn.scala`,
`TurnEvent.scala`.

```scala
private[orca] trait LineDecoder[B <: BackendTag, S]:
  def backendName: String            // prefixes stderr lines and failures
  def terminalMessageNoun: String    // "a result message" — used when the stream ends unsettled
  def init: S
  def line(state: S, line: String): Step[B, S]   // pure fold; may write replies to the wire
  def failedTurnDebit(state: S): TurnDebit       // spend known so far, for failed/cancelled turns
  def protocolContext(state: S): Option[String] = None
  def onUnsettledEnd(): Unit = ()
  def isStderrNoise(line: String): Boolean = false
```

- `Step.Continue(state, events)` for ordinary lines; `Step.Settle(state,
  events, Settled.Succeeded(AgentResult(...)) | Settled.Failed(msg, debit))` on
  the terminal line. After a settle, `DecodedTurn` SIGINTs the source and
  ignores further lines.
- A throw from `line` becomes a parse-error `Error` event and leaves state
  unchanged — so parse with an `Unknown` fallback for forward compatibility and
  drop unknown types silently.
- A stream that ends without a settle is diagnosed from the exit code and the
  collected stderr (`"<name> exited with code N"`, or `"exited cleanly but never
  sent <terminalMessageNoun>"`). A cancelled turn (SIGINT/kill) yields
  `OrcaInteractiveCancelled` regardless of exit code.

### 4.1 Events and the message grammar

Emit `TurnEvent`s: `AssistantTextDelta`, `AssistantThinkingDelta`,
`AssistantToolCall(name, rawInput)`, `ToolResult(name: Option, ok, content)`,
`ToolDenied(name)`, `AssistantMessageEnd`, `Error`, `Approval`, `Question`.

- A *message* is opened by any activity event and closed by exactly one
  `AssistantMessageEnd`. `DecodedTurn` drops an `AssistantMessageEnd` with no
  open message and closes an open message on settle, so a decoder may emit an
  end at every "segment complete" signal the wire gives.
- `ToolResult.toolName` is `Some(name)` if the wire names the tool, else
  `None`, never `Some("")`.
- Use `ToolDenied` when the wire lets you tell a permission refusal from a
  failed tool (claude's `PermissionRefusal`; cursor's `rejected` /
  `permissionDenied` results; grok's "Denied by permission policy" text).
- If the CLI streams deltas **and** later repeats the full message, emit the
  deltas and use the full message only as the boundary (re-emit its text only
  if no deltas preceded it) — see `MessagesJsonDecoder.assistantMessage`.
- **Echo suppression**: the agent's own `ask_user` call and its result must not
  render (the bridge already surfaced the question). Track ids in
  `AskUserEchoes` (`suppress` on the call, `consume` on the result). Match the
  exact qualified name the CLI uses for MCP tools (claude `mcp__orca__ask_user`,
  gemini `orca__ask_user`, codex bare slug + server field). Likewise suppress a
  CLI-injected structured-output tool.

Every backend pins the grammar with `TurnEventConformance.assertGrammar` over
recorded sequences (`tools/src/test/.../backend/TurnEventConformance.scala`).

### 4.2 `AgentResult` and usage

`AgentResult(wireId, output, usage, model)`:

- `wireId` — the claimed id (`ClientClaimed`) or the id the stream announced.
- `output` — the final answer / structured payload (§2.6).
- `usage: orca.events.Usage` — construct **by name**. Map a wire whose input
  counter *excludes* cache reads/writes straight onto the constructor (claude,
  pi, opencode); one that *includes* them goes through `Usage.inclusiveInput`.
  Put a CLI-reported dollar figure in `cost` (a reported `0` against spent
  tokens is read as "no report" and falls back to the pricing table). Set
  `apiCalls` only if you can count model requests (claude counts distinct
  response ids).
- `model` — the model the turn names, else `None` (spend falls back to the
  pinned model).

A failed turn that reports usage should settle `Failed(msg,
TurnDebit.Observed(usage, model))` so spend reaches the cost log.

---

## 5. Host-served MCP tools

orca stands up MCP servers on `127.0.0.1:<ephemeral>/mcp` (streamable HTTP,
JSON responses) per turn via `McpHost` (`tools/.../backend/mcp/`):

| Server | Tools | Who gets it |
|---|---|---|
| `AskUserMcpServer` (`orca`) | `ask_user` — blocks until the human answers | **interactive turns only**, on claude/codex/gemini (opencode and pi use native question events: `AskUserChannel.Native`) |
| `RepoMcpServer` | git reads for read-only tiers that lost `Bash` | claude only (`ReadOnly`/`NetworkOnly`) |
| `GitHubMcpServer` | issue/PR reads | claude only (`NetworkOnly`) |

To wire `ask_user` a CLI needs: an HTTP MCP client; a way to register a server
per invocation (argv flag, a temp config file, a per-invocation agent/profile
file, or — last resort — a merge into a project config inside `workDir` that is
restored when the turn ends and refuses symlinks, like `GeminiSettings`); a way
to pre-approve that server's tools without approving everything; and a
per-tool timeout of at least `AskUserMcpServer.ToolTimeout` (1 h), rendered in
the CLI's unit. Add `AskUserMcpServer.Hint` to the system prompt. A backend
that cannot do this is still viable: interactive turns simply run with
`AskUserChannel.Unavailable` (`canAskUser = false`).

---

## 6. Process hygiene

- `CliRunner.spawnPiped` always pipes stderr; `DecodedTurn` drains it. Filter
  benign chatter with `isStderrNoise` (it sees ANSI-stripped, trimmed lines).
- Teardown is SIGINT then process-tree kill. The CLI should die (or finish) on
  SIGINT; background work it detached is only *reported* by the cookie sweep
  (`ORCA_TURN_COOKIE`, Linux only).
- Never write under `.orca/` except through `OrcaDir` (symlink-refusing). A
  per-session on-disk store belongs under `.orca/cache/<name>/` with an
  `OrcaDir` accessor (pi: `.orca/cache/pi-sessions/<id>/`, with 30-day pruning).
- Env for the child goes through `spawnPiped(args, env, cwd)` (applied on top
  of the inherited environment). Remember the flow's environment may carry
  `XDG_CONFIG_HOME` (the runner tests and the CONTRIBUTING dev recipe set it),
  which some CLIs read for their own config.

---

## 7. Registration checklist (outside the module)

Adding a `BackendTag` case makes the compiler flag the exhaustive matches; the
rest must be found by hand.

**Core**
- `tools/.../agents/BackendTag.scala` — new case.
- `tools/.../agents/Agent.scala:419-423` — `type <Name>Agent = Agent[BackendTag.<Name>.type]`.
- `flow/.../settings/AgentSpec.scala:16-21` — `harnessNameFor` (settings
  spelling). **This string is also used as the binary name** by the wizard's
  PATH probe, `ResumeCommand` and `FlowAuthoring.slugArgv`; if the CLI's binary
  differs from the settings name, add an explicit binary mapping.
- `flow/.../AgentSet.scala` (`def <name>`, `agentFor` arm), `flow/.../accessors.scala`,
  `flow/.../FlowContext.scala` (docs).

**Runner**
- `runner/.../FlowWiring.scala` (override field), `runner/.../WiredAgents.scala`
  (import, field, `build` arm with `<Name>Agents.default`),
  `runner/.../DefaultFlowContext.scala` (`export wired.{…}`),
  `runner/.../flowEntry.scala` (the `flow(...)` override parameter and
  pass-through), `runner/.../exports.scala` (alias, `<Name>Agents`, tier
  extensions).
- `runner/.../terminal/ReadOnlyTools.scala` — read-only tool names (display).
- `flow/.../events/Pricing.scala` — default model row(s).

**Build**
- `build.sbt` — `lazy val <name>` (`orca-<name>`, `dependsOn(tools, tools % "test->test")`),
  runner `dependsOn`, root `aggregate`; shell `dependsOn` only if the shell
  needs a module helper for resume.

**Shell**
- `shell/.../sessions/ResumeCommand.scala` — interactive resume argv. It
  returns argv only; a CLI that needs env (e.g. a relocated config dir) needs
  `ResumeCommand`/`SessionAction.resume` extended.
- `shell/.../create/FlowAuthoring.scala` — `slugArgv` (one-shot, cheap).
- `shell/.../wizard/ModelCatalog.scala` — curated models / free-text hint;
  `Wizard.scala` role defaults (optional).
- `shell/.../actions/SettingsEditAction.scala:30` — template comment.

**Tests**
- `runner/.../EnforcementTableTest.scala` (row helper, expected rows, backend
  list) → paste the rendered table into AGENTS.md.
- `flow/src/test/.../TestFlowContext.scala`, `runner/.../RoleAgentsTest.scala`,
  `LeadAgentIdentityTest.scala`, `DefaultModelsPricedTest.scala`,
  `flowtests/FlowCompilesTest.scala`, `AgentSpecTest.scala`,
  `ResumeCommandTest.scala`, `FlowAuthoringTest.scala`, `WizardTest.scala`.

**Docs**
- README (prerequisites, harness list, accessor table, per-backend notes,
  settings `harness` list, `agent.cheap` mapping), AGENTS.md (layout,
  dependency graph, `IdScheme` examples, enforcement table, `NetworkOnly`
  grant list, "what a run writes to disk" table), CONTRIBUTING.md (integration
  suites, published artifacts), `skills/orca/README.md`, ADR 0021's resume
  table. A new driver shape deserves its own ADR (see 0015 for the template).

---

## 8. Testing a backend

- **Unit, no CLI:** `StubCliRunner` / `SpawnStubCliRunner` and
  `FakePipedCliProcess` feed recorded lines; assert argv (`<Name>ArgsTest`),
  decoding (`<Name>TurnTest` + `TurnEventConformance`), and the backend's
  `open` wiring (`<Name>BackendTest`). Record fixtures from the real CLI and
  keep the version/date beside them.
- **Enforcement:** `EnforcementTableTest` walks backend × ToolSet ×
  AutoApprove × dispatch.
- **Integration (gated):** `<Name>IntegrationTest` under `ORCA_INTEGRATION=1`,
  authenticated CLI: a fresh turn, a resume, a structured turn, a read-only
  turn attempting a write.
- **Breakage triage:** CLIs auto-update and change wire behaviour. Reproduce
  with a direct CLI probe before touching orca code; fix at the seam (decoder,
  args) with a test pinning the observed shape (AGENTS.md "Debugging backend
  breakage").

Conventions: load the `direct-style-scala` skill before writing Scala here
(CLAUDE.md); no class-level `var`s; any new mutable state needs the
research-and-critique step CLAUDE.md prescribes; `Either[E <: OrcaFlowException, T]`
for recoverable failures; no defaults on persisted/domain fields.

---

## 9. Feasibility checklist for a candidate CLI

Run these probes (in a scratch git repo) before writing code. "Must" items
block an integration; "Should" items degrade to a weaker enforcement cell or a
missing feature.

| # | Capability | Level | Probe |
|---|---|---|---|
| 1 | Non-interactive single-turn mode that exits when done | Must | `cli -p "…"`; exit code 0 |
| 2 | Line-delimited machine-readable event stream (NDJSON) with a terminal "result" record | Must | `--output-format stream-json`-like flag; look for init / message / tool / result records |
| 3 | Session id on the stream, or a client-chosen id | Must | init/result `session_id`; `--session-id <uuid>` |
| 4 | Resume by id, non-interactively, keeping context | Must | second turn "what did I say?" |
| 5 | Side-effect-free session existence check (file on disk / list command) | Should (else ephemeral) | locate the store; confirm path keying (cwd hash/encoding) |
| 6 | Model selection per invocation | Must | `--model` |
| 7 | Prompt delivery without argv size limits | Should | stdin or `--prompt-file` |
| 8 | Append (not replace) system prompt | Should (else fold into prompt) | flag, then confirm in the CLI's recorded system prompt |
| 9 | Hard read-only tier: writes and shell physically blocked | Should (else PromptOnly) | allowlist / deny rules; ask the agent to write and to `touch`; check the filesystem |
| 10 | Approve-everything flag | Must for `Full` | `--yolo` / `--force` / bypass |
| 11 | No-tools tier | Should | empty allowlist; confirm the init tool list is empty |
| 12 | Read-only web tools grantable without write | Should | web fetch under the read-only config |
| 13 | Flags re-applied on resume | Should | resume under a stricter tier and retry the write |
| 14 | Usage (tokens, ideally cost) on the result | Should | inspect result; decide inclusive vs exclusive input |
| 15 | Structured output (schema flag) | Nice | else `RawText` |
| 16 | HTTP MCP client + per-invocation registration + per-tool approval | Nice (enables `ask_user`) | point it at a stub MCP server, call the tool |
| 17 | Permission refusals distinguishable from tool failures | Nice | inspect a denied call's result record |
| 18 | Dies (or settles) promptly on SIGINT | Must | send SIGINT from a parent that does not ignore it (a `&` job in a non-interactive shell inherits `SIGINT` ignored — use a real subprocess API) |
| 19 | Errors surface as a failed result or non-zero exit with stderr | Must | invalid model id |
| 20 | Interactive resume command for the shell's `orca continue` | Should | `cli --resume <id>` |

A throwaway MCP stub for probe 16 (streamable HTTP, JSON responses, one
`ask_user` tool that answers with a fixed string) is ~50 lines of Python
`http.server`: handle `initialize`, `tools/list`, `tools/call`, answer
notifications with 202. Both companion documents used one.
