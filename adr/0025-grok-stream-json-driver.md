# 0025. Drive Grok Build via `grok --output-format streaming-messages-json`, on a shared messages-JSON decoder

Status: Accepted · Date: 2026-09-27
Amends: [ADR 0003](0003-pluggable-llm-backends.md) (backend surface), [ADR 0006](0006-stream-json-conversation-driver.md) (the stream-json decoding moves out of `claude`)
Related: [ADR 0012](0012-mcp-host-bridge.md) (ask_user MCP bridge), [ADR 0015](0015-gemini-stream-json-driver.md) (Gemini driver), [ADR 0016](0016-toolset-capability-axis-and-planner-network.md) (tool tiers), [ADR 0021](0021-orca-shell.md) (shell resume)

## Context

xAI's Grok Build CLI (`grok`) is a coding agent with a headless mode. The
feasibility study, `INTEGRATE_GROK_BUILD.md`, found that
`grok --output-format streaming-messages-json` emits Claude Code's
stream-json wire format. Recorded grok 1.0.41 transcripts, replayed through
the unmodified `ClaudeDecoder`, decoded correctly and passed
`TurnEventConformance`. The question was whether to copy claude's decoder or
share it, and how to map orca's tool tiers, sessions and `ask_user` onto
grok's flags.

## Findings

Where grok differs from claude on the wire (`INTEGRATE_GROK_BUILD.md` §3):

1. **MCP tools come through meta-tools.** A call to orca's `ask_user` is
   `tool_use{name:"use_tool", input:{tool_name:"orca__ask_user",…}}`, so
   the echo matcher needs the raw input as well as the tool name. The model
   may call `search_tool` first; that call renders.
2. **No injected structured-output tool.** Under `--json-schema`, every
   assistant text block is schema-constrained JSON, and the validated value
   is in `result.structured_output`.
3. **No top-level `model` on `result`.** `init.model` is the real id, or
   `"unknown"` when grok has none.
4. **An all-zero `usage` is not a measurement.** A turn rejected before the
   model ran (an unknown `-m`) still carries a zeroed `usage` and
   `total_cost_usd: 0.0`.
5. **Different refusal texts.** They read "Denied by permission policy…" or
   "User cancelled the execution for tool `write`" instead of claude's.

Behaviour of the flags (§5, §6):

- Headless grok defaults to `bypassPermissions`, so every tier needs explicit
  flags.
- An empty `--tools ""`, or a `--tools` naming only unknown tools, turns
  filtering *off*.
- Under `--permission-mode dontAsk`, an out-of-set edit cancels the whole
  turn: headless grok can't ask for an approval.
- MCP servers can be attached per invocation through a `--agent` definition
  file. Its `mcpServers` must be a YAML list. The body replaces only the
  stock profile's browser-verification block.

## Decision

**Share the decoder.** The stream-json decoding (`InboundMessage`,
`ContentBlock`, `StreamEventPayload` and the fold) moved from `claude` to
`tools/src/main/scala/orca/backend/messagesjson/` as `MessagesJsonDecoder`.
It is parameterised by a `MessagesJsonDialect`: the backend name, the
ask-user matcher (name + raw input), the structured-output tool name (if
any), the refusal detector, a model normaliser and how to read an all-zero
`usage` (`ZeroUsage`). `ClaudeDialect` and `GrokDialect` are the two
dialects; `ClaudeTurn` and `GrokTurn` both start a `MessagesJsonTurn` with
theirs. A copy would have worked, but two copies of the same wire
format would drift.

**Drive grok one process per turn** from the new `grok` module
(`orca-grok`):

- `grok --prompt-file <tmp> --output-format streaming-messages-json
  --include-partial-messages --rules <composed system prompt>`, plus
  `--model`, `--json-schema`, the tier flags, the session flags and, on
  interactive turns, `--agent <tmp>`. cwd is the run's `workDir`. The
  prompt and agent files are `TurnResources.tempFile`s under `$TMPDIR`, so
  grok writes nothing into the user's tree.
- **Sessions are `IdScheme.ClientClaimed`**, as for claude. A fresh turn
  passes `--session-id <orca's id>`, a later one `--resume <id>`. The
  durability probe checks that `chat_history.jsonl` exists under
  `$GROK_HOME|~/.grok/sessions/<URL-encoded real workDir>/<id>/`. It
  deliberately accepts a history left by a turn that failed before the
  model ran: such a session holds the id, re-claiming it errors with
  "already in use", and `--resume` of it works.
- **Tiers are hard `--tools` allowlists.** `ReadOnly` → `read_file,grep,list_dir`;
  `NetworkOnly` adds `web_search,web_fetch`; `NoTools` names `todo_write`
  and then disallows it (an empty `--tools` would allow everything). Each
  allowlist tier adds `--no-subagents`. `Full` renders `--always-approve`
  and disallows grok's own `ask_user_question`, which answers "no user is
  available" when headless. Autonomous turns also drop the MCP meta-tools
  and the background scheduler/monitor tools.
- **`Full, Only(set)` widens to `--always-approve`**, recorded as
  `Enforcement.Ignored`, like gemini's `yolo` widening (ADR 0015). Every
  other cell is `Hard`. Resumed turns get the same flags as fresh ones:
  grok re-applies them on `--resume` (probed).
- **`ask_user` on interactive turns** uses a temp `--agent` file with
  `model: inherit`, a neutral one-line body and a one-element
  `mcpServers` list pointing at the per-turn `AskUserSession` URL
  (ADR 0012). The system prompt stays in `--rules` on every turn.
- **Structured output is `StructuredOutputMode.RawText`.** The decoder
  already prefers `result.structured_output`.
- **Models**: bare `grok` pins `grok-4.7`; `grok.fast` and `grok.cheap`
  pin `grok-4.7-build-fast`. Cost comes from the CLI's `total_cost_usd`.
  orca has no xAI `Pricing` rows yet.
- **Shell**: `orca continue` execs `grok --resume <wireId>` in `workDir`
  (ADR 0021 §8). The harness name `grok` is also the binary name.

Rejected: `grok agent stdio` (ACP). It offers native per-tool approvals,
which would make `Full, Only(set)` hard on interactive turns, but it needs a
JSON-RPC driver closer to opencode's (ADR 0014). Revisit it if interactive
approvals matter.

## Consequences

- Claude and grok share every wire-format fix. A claude-only change must go
  through the dialect, not a branch on the backend.
- SIGINT kills grok at once, so an interactive cancel surfaces as
  `OrcaInteractiveCancelled` with no result line.
- Open (see `INTEGRATE_GROK_BUILD.md` §11):
  - A held session with no user turn (left by a pre-model failure) resumes
    as `EarlierAttempt`, so the retry doesn't receive the session seed or
    the progress preamble.
  - How the chimp `McpHost` handles `server/discover` from grok is unprobed.
  - Web-search content blocks are unprobed.
  - There are no xAI pricing rows.
  - There is no gated `GrokIntegrationTest` yet; the decoder is pinned by the
    fixture replays in `docs/research/harness-integration/fixtures/grok-*.jsonl`.
