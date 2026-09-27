# Integrating Cursor Agent (`cursor-agent`)

Feasibility study and design for an `orca-cursor` backend, applying
[HARNESS_INTEGRATION_GUIDE.md](HARNESS_INTEGRATION_GUIDE.md) to the Cursor CLI.

**Verdict: feasible, and a subprocess-per-turn driver like gemini's fits
well.** Every "Must" item in the guide's checklist was verified against the real
CLI. The costs are:

- a Cursor-specific decoder (the wire is Claude-*like* but not compatible);
- a per-session config directory, which is how orca gets a hard read-only tier
  without editing the user's tree;
- a project-local `.cursor/mcp.json` merge for `ask_user` on interactive turns;
- no reported cost or model id on the result.

## Environment probed

| | |
|---|---|
| CLI | `cursor-agent` 2026.09.26-dd393fe (`~/.local/bin/cursor-agent` → `~/.local/share/cursor-agent/versions/…`; the CLI calls itself `agent` in `--help`) |
| OS | macOS (Darwin 24.6), 2026-09-27 |
| Account | logged in, **free plan**: only the `auto` model is allowed |
| Method | direct CLI probes in scratch git repos under `/tmp/orca-probe/`, a stub HTTP MCP server, and a throwaway prototype `LineDecoder` replayed through orca's real `DecodedTurn` with `TurnEventConformance` (both recorded transcripts passed the grammar oracle) |

Evidence markers: **[verified]** observed in a probe; **[source]** read from the
CLI's bundled JS; **[unverified]** inferred, must be probed before relying on it.

---

## 1. Capability matrix

| # | Guide requirement | Result |
|---|---|---|
| 1 | Non-interactive mode | `-p/--print` **[verified]** |
| 2 | NDJSON stream + terminal record | `--output-format stream-json` (+ `--stream-partial-output` for deltas) → `system/init`, `user`, `thinking`, `assistant`, `tool_call`, `result` **[verified]** |
| 3 | Session id | `session_id` on every line. **Client-claimable**: `--resume <new-uuid>` creates a chat under that uuid **[verified]** |
| 4 | Resume by id | `--resume <id>` keeps context **[verified]** |
| 5 | Existence check | `<configDir>/chats/<md5(realpath cwd)>/<id>/meta.json` with `"hasConversation": true` **[verified]** |
| 6 | Model per invocation | `--model <id>`. On a free plan anything but `auto` fails **[verified]** |
| 7 | Prompt without argv limit | prompt on **stdin**, read to EOF (multi-line OK) **[verified]** |
| 8 | Append system prompt | no flag → `SystemPromptComposer.foldIntoPrompt` |
| 9 | Hard read-only | `permissions.deny: ["Write(**)","Shell(*)"]` in `cli-config.json` blocks writes and shell, **even under `--force`** **[verified]**. `--mode plan/ask` only made the model decline (tools still listed) → not Hard |
| 10 | Approve everything | `--force` (`--yolo`) **[verified]** |
| 11 | No tools | deny rules can't remove web fetch/search → only PromptOnly **[verified]** |
| 12 | Read-only web | `WebFetch` works under the read-only deny set **[verified]** |
| 13 | Flags on resume | flags and config are read per invocation **[verified for resume itself; permissions on resume unverified]** |
| 14 | Usage | `result.usage {inputTokens, outputTokens, cacheReadTokens, cacheWriteTokens}`, input exclusive of cache. **No cost, no model id** **[verified]** |
| 15 | Structured output | no schema flag → `RawText` |
| 16 | MCP for `ask_user` | HTTP MCP servers from `<project>/.cursor/mcp.json` (or `~/.cursor/mcp.json`), with `--approve-mcps` plus an allow rule `Mcp(orca:*)` **[verified]** |
| 17 | Refusal vs failure | a `tool_call` result is `success`, `error`, `rejected`, `permissionDenied` or `writePermissionDenied` **[verified]** |
| 18 | SIGINT | exits 130, `Aborting operation...` on stderr, no result line **[verified]** |
| 19 | Errors | e.g. plan-restricted model: `ActionRequiredError: …` on stderr, exit 1, after `init` and `user` lines **[verified]** |
| 20 | Interactive resume | `cursor-agent --resume <id>` (listed in `--help`) **[unverified interactively]** |

---

## 2. Invocation

```
cursor-agent -p
  --output-format stream-json --stream-partial-output
  --trust                      # without it: "⚠ Workspace Trust Required", exit 1   [verified]
  --model <id>                 # always pass one — see §7
  --resume <client-uuid>       # BOTH fresh and resumed turns (ClientClaimed, §4)
  [--force]                    # Full + AutoApprove.All
  [--approve-mcps]             # interactive turns with the ask_user server
  env CURSOR_CONFIG_DIR=<workDir>/.orca/cache/cursor/<client-id>   # §5
  stdin: <prompt>  (then close)
  cwd:   workDir
```

`--workspace <path>` exists, but orca already fixes the cwd, so it isn't needed.
Don't use `--worktree`: orca owns worktrees.

---

## 3. Wire format (stream-json) — decoder spec

Every line carries `type` and `session_id`. Shapes observed:

```jsonc
{"type":"system","subtype":"init","apiKeySource":"login","cwd":"/private/tmp/…","session_id":"<uuid>","model":"Auto","permissionMode":"default"}
{"type":"user","message":{"role":"user","content":[{"type":"text","text":"<prompt echo>"}]},"session_id":"…"}
{"type":"thinking","subtype":"delta","text":"…","session_id":"…","timestamp_ms":…}
{"type":"thinking","subtype":"completed","session_id":"…","timestamp_ms":…}
// assistant, partial mode — a DELTA: has timestamp_ms, no model_call_id
{"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":" read"}]},"session_id":"…","timestamp_ms":…}
// assistant — a COMPLETED SEGMENT (text before a tool batch): has model_call_id
{"type":"assistant","message":{…full segment text…},"session_id":"…","model_call_id":"…","timestamp_ms":…}
// assistant — the FINAL segment: neither timestamp_ms nor model_call_id
{"type":"assistant","message":{…full final text…},"session_id":"…"}
{"type":"tool_call","subtype":"started","call_id":"…","tool_call":{"readToolCall":{"args":{"path":"…"}},"toolCallId":"…"},"model_call_id":"…",…}
{"type":"tool_call","subtype":"completed","call_id":"…","tool_call":{"readToolCall":{"args":{…},"result":{"success":{…}}}},…}
{"type":"result","subtype":"success","duration_ms":4546,"duration_api_ms":4546,"is_error":false,
 "result":"<ALL segments concatenated, no separator>","session_id":"…","request_id":"…",
 "usage":{"inputTokens":13652,"outputTokens":141,"cacheReadTokens":5376,"cacheWriteTokens":0}}
```

Decoding rules:

- **Delta vs segment** (holds in both modes) **[verified]**: an `assistant` line
  is a delta iff it has `timestamp_ms` and no `model_call_id`. Anything else is
  a completed segment: emit `AssistantMessageEnd`, and re-emit its text as one
  delta only if no deltas streamed since the last segment. Without
  `--stream-partial-output`, every assistant line is a segment. Use
  `ClaudeDecoder`'s `deltasSinceLastFullMessage` pattern.
- **Thinking**: `delta` with non-empty `text` → `AssistantThinkingDelta`;
  `completed` → nothing.
- **Tool calls**: the payload is keyed `<kind>ToolCall`. Kinds seen: `read`,
  `edit`, `shell`, `glob`, `grep`, `webFetch`, `webSearch`, `mcp`,
  `getMcpTools`, `createPlan`. Tool name = `kind`. `started` →
  `AssistantToolCall(kind, args.noSpaces)`. `completed` → look at the single
  key under `result`:
  - `success` → `ToolResult(Some(kind), ok = true, …)`
  - `rejected`, `permissionDenied`, `writePermissionDenied` → `ToolDenied(kind)`
  - `error` (and anything else) → `ToolResult(Some(kind), ok = false, …)`

  Parallel calls arrive as several `started` lines, then several `completed`
  lines. Pair them by `call_id`. It contains a newline, which is JSON-escaped,
  so it is safe inside a line.
- **MCP calls**: `mcpToolCall.args = {"name":"orca-ask_user","providerIdentifier":"orca","toolName":"ask_user",…}`
  **[verified]**. Suppress the `ask_user` echo on
  `providerIdentifier == "orca" && toolName == "ask_user"` (the
  `<server>-<tool>` display name is ambiguous if a name contains `-`).
  `getMcpToolsToolCall` is the model browsing the MCP catalog; render it
  normally.
- **Result**: `subtype: "success"`, `is_error: false` → settle `Succeeded`.
  For `output`, prefer **the last completed segment's text** over
  `result.result`, which joins segments with no separator (e.g. `"I'll read
  a.txt…**hello**…"`). `ResponseParser` would still find the rightmost JSON
  object, but prose would run together. `is_error: true` → settle `Failed`
  with an `Observed` debit. (No `is_error` result was observed; failures seen
  so far exit non-zero without a result line.)
- **Usage**: `inputTokens` excludes cache. In one turn `inputTokens` 13153
  came with `cacheReadTokens` 35456, so the input counter can't include the
  reads. Map it straight onto `Usage(freshInputTokens = inputTokens,
  cacheReadInputTokens = cacheReadTokens, cacheWriteInputTokens =
  cacheWriteTokens, outputTokens, reasoningOutputTokens = 0, cost = None,
  apiCalls = <distinct model_call_id count, or None>)`.
- **Model**: `init.model` is a display name (`"Auto"`, `"Composer 2.5"`), not an
  id. Return `model = None` and let spend attribute to the pinned model.
- `failedTurnDebit` → `TurnDebit.Unobserved` (usage only on `result`).
- `terminalMessageNoun` = `"a result message"`; `backendName` = `"cursor-agent"`.

The prototype decoder behind these rules replayed two recorded turns (an
edit turn with partial output; a turn whose shell calls were rejected three
times) through `DecodedTurn` and passed `TurnEventConformance.assertGrammar`.

---

## 4. Sessions — `IdScheme.ClientClaimed`

- `--resume <uuid>` with an id Cursor has never seen **creates** a chat under
  that id (init echoes it back), and later `--resume <uuid>` turns keep
  context **[verified]**. The argv is the same for `Fresh(Some(id))` and
  `Resume(id, _)`. `cursor-agent create-chat` (prints a fresh id) is not
  needed.
- Unlike claude and grok, Cursor **never refuses** a claimed id. So the
  held-claim logic in `SessionSupport` never has to save a run from a
  refusal; the probe alone tells fresh from resumed.
- **Store**: `<configDir>/chats/<md5(realpath(cwd))>/<id>/{meta.json,store.db}`,
  where `<configDir>` resolves as `CURSOR_CONFIG_DIR` → `$XDG_CONFIG_HOME/cursor`
  → `~/.cursor` **[source: `cursor-config/dist/paths.js`; verified]**. `md5`
  of `/private/tmp/orca-probe/cur` matched the observed directory; the path
  hashed is the real path, so canonicalize `workDir` in the probe.
- **Probe**: `meta.json` exists **and** `hasConversation == true`. A turn that
  failed before the model ran (plan-restricted model) left `meta.json` with
  `hasConversation: false` **[verified]**. That should read as "not held", so
  the retry re-seeds.
- A per-session `CURSOR_CONFIG_DIR` (§5) puts the chats in orca's cache, so the
  probe is a plain file check under `.orca/cache/cursor/<client-id>/`, the
  same shape as pi's `PiSessionStore`.

---

## 5. Tool enforcement

### 5.1 Mechanism: a per-session `CURSOR_CONFIG_DIR`

Cursor reads permissions from `<configDir>/cli-config.json` and from every
`<dir>/.cursor/cli.json` between the repo root and the cwd. `deny` wins over
`allow` and over `--force` **[verified with both locations]**. Relocating the
config dir with `CURSOR_CONFIG_DIR`:

- **keeps auth on macOS** (`cursor-agent status` stayed logged in; auth appears
  to be in the keychain) **[verified]**. **Linux is unverified**: if the CLI
  keeps its token under the config dir there, a relocated dir logs the user
  out. Mitigations: copy or symlink the token file into the session dir, or
  require `CURSOR_API_KEY`. Probe this on Linux before shipping.
- **also relocates `chats/`** **[verified]**, so the directory must be stable
  across all turns of a session. Recommended: `.orca/cache/cursor/<client-id>/`
  created through a new `OrcaDir` accessor. Rewrite `cli-config.json` at the
  start of every turn, because a session can change tier between turns.
  Concurrent turns on one session are already forbidden, so there is no race.
  Parallel reviewers each own a session, so each gets its own directory.
- makes the turn hermetic: the user's own `cli-config.json` (default model, a
  personal allowlist) is not inherited. That's intended, but document it. MCP
  config is **not** affected (see §6).
- the directory holds transcripts, so prune it like `PiSessionStore` (30 days).

Rejected alternative: writing `<workDir>/.cursor/cli.json`. It works
**[verified]** but edits the user's tree for the whole turn (a mid-turn agent
commit would include it), and it applies to every Cursor session in that
directory. Keep it only as a fallback if the Linux auth question goes badly.

Default `-p` without `--force` (for reference): edits auto-approved, shell
calls come back `rejected` **[verified]**. The CLI's help says `-p` "has
access to all tools". Don't rely on the default; always write explicit
permissions.

### 5.2 Tier mapping

Permission tokens: `Read(glob)`, `Write(glob)`, `Shell(cmd)`, `WebFetch(…)`,
`Mcp(server:tool)`.

| `(tools, approve)` | `cli-config.json` permissions + flags | Level | Evidence |
|---|---|---|---|
| `ReadOnly, *` | `deny: ["Write(**)","Shell(*)"]`, `--force` | **Hard** | write → `writePermissionDenied`, shell → `permissionDenied`; files absent **[verified]** |
| `NetworkOnly, *` | same + `allow: ["WebFetch(*)"]` | **Hard** (writes) | web fetch succeeded, write denied **[verified]** |
| `NoTools, *` | `deny: ["Read(**)","Write(**)","Shell(*)","WebFetch(*)","Mcp(*:*)"]` | **PromptOnly** | read and shell denied, but `webFetch` and `webSearch` still succeeded **[verified]** |
| `Full, All` | `--force`, no deny | **Hard** | shell and edits ran **[verified]** |
| `Full, Only(set)` | no `--force`, `allow` = set rendered as Cursor tokens | **Ignored** (recommended) | stream-json is read-only, so orca can't answer an approval. Out-of-set shell comes back `rejected`, while edits are auto-approved by Cursor's own default, so the boundary is Cursor's, not the set **[verified for the defaults]** |

Notes:

- `--force` on the read-only tiers keeps reads and MCP from ever waiting on an
  approval that nobody can give; the deny list still wins.
- `Write(**)` also blocks the `Delete` tool (`deleteToolCall` →
  `permissionDenied`, file kept) **[verified]**. Whether a `Task` subagent
  inherits the deny list is **[unverified]**. Add a subagent write attempt to
  the integration test before claiming `Hard`.
- Resumed turns get the same argv and config file, so classify `Resumed` like
  `Fresh`. Confirm with a resume under a stricter tier before shipping.
- `--sandbox enabled` exists (OS sandbox; `cli-config.json` has
  `sandbox.mode` and `networkAccess`). It is not probed and could back a
  `SandboxApprox` cell.
- `--mode plan|ask`: the model declines to write, but `Write` and `Shell` are
  still in its tool list **[verified]**. At best `PromptOnly`, so don't use it.
- The hand-kept `NetworkOnly` grant list in AGENTS.md gains "cursor:
  `WebFetch(*)`, plus built-in `WebSearch`, which has no permission token".

---

## 6. `ask_user` over MCP (interactive turns)

**[verified]** with a stub streamable-HTTP server:

1. Merge `{"mcpServers":{"orca":{"url":"http://127.0.0.1:<port>/mcp"}}}` into
   `<workDir>/.cursor/mcp.json`. Cursor reads MCP config only from
   `<project>/.cursor/mcp.json` and `~/.cursor/mcp.json`. The latter comes from
   `homedir()`, not the config dir; `CURSOR_CONFIG_DIR` and `CURSOR_DATA_DIR`
   were both ignored ("No MCP servers configured (expected in .cursor/mcp.json
   or ~/.cursor/mcp.json)"). This is the `GeminiSettings` pattern: merge,
   restore on turn end, refuse symlinks, drop stale entries left by a crash,
   and remove a `.cursor/` orca created.
2. Pass `--approve-mcps`. Without it the server is "not loaded (needs approval)".
3. Add `allow: ["Mcp(orca:*)"]` to the session's `cli-config.json`. Without it
   (and without `--force`) the call came back `rejected: "User rejected MCP:
   orca-ask_user"`. With it, the stub's answer reached the model
   (`PINEAPPLE`).
4. Add `AskUserMcpServer.Hint` to the folded system guidance.

Open: the per-server tool timeout. Cursor's `mcp.json` may accept a timeout
key; set it to `AskUserMcpServer.ToolTimeout` (1 h) if it does
**[unverified]**. Without it, a slow human may see duplicate questions. Cursor
advertises the MCP `elicitation` capability in `initialize`, which could be an
alternative question channel later.

`RepoMcpServer` and `GitHubMcpServer` are claude-only today and not needed here:
the read-only tiers keep Cursor's own `Read`, `Grep` and `Glob`.

---

## 7. Models, cheap tier, cost

- `cursor-agent models` / `--list-models` lists ids such as `auto`,
  `composer-2.5`, `gpt-5.3-codex`, `claude-sonnet-5-thinking-high`,
  `grok-4.7-medium`. Parameterized form:
  `'claude-opus-4-8[context=1m,effort=high,fast=false]'`.
- **Always pass `--model`.** The user's `cli-config.json` on the probe machine
  defaulted to `composer-2.5`, which the free plan rejects, so a bare `-p`
  failed with `ActionRequiredError: Named models unavailable Free plans can
  only use Auto` **[verified]**. The per-session config dir drops that default
  anyway.
- Recommended default: `Model("auto")`. `cheapModel` returns `None`: there is
  no universally allowed cheaper tier, and `auto` is Cursor's own router.
  Users pin models through `codingAgent = cursor:<id>`.
- No cost is reported. `auto` can't be priced (unknown underlying model), so
  turns show tokens without dollars; `DefaultModelsPricedTest` must exempt it
  or the backend must pin nothing. Named models could get `Pricing` rows,
  but Cursor bills its own rates, which orca shouldn't guess.

---

## 8. Other behaviour

- **Cancellation**: SIGINT → exit 130, stderr `Aborting operation...`, no result
  line → `OrcaInteractiveCancelled` **[verified]**. Add that stderr line to
  `isStderrNoise`.
- **Trust**: `--trust` is mandatory in headless mode **[verified]**.
- **Background work**: the `Shell` tool has `isBackground` and a
  `TIMEOUT_BEHAVIOR_BACKGROUND` default. `AwaitShell` exists. The standing
  `BackgroundWorkAbandonedAtTurnEnd` rule covers this; the cookie sweep reports
  leftovers.
- **Binary vs harness name**: the binary is `cursor-agent`, and it also answers
  to `agent`. On the probe machine, `agent` on `PATH` resolved to grok's shim
  (`~/.grok/bin/agent`), so **never invoke `agent`**. `AgentSpec.harnessNameFor`
  doubles as the binary name (wizard PATH probe, `ResumeCommand`, `slugArgv`).
  Either use `cursor-agent` as the settings harness name, or add a separate
  binary mapping and use `cursor`. I recommend the settings name `cursor` plus a
  `binaryFor(tag)` helper, since this will recur.
- **Shell `orca continue`**: `cursor-agent --resume <wireId>` in `workDir`,
  **with `CURSOR_CONFIG_DIR` set to the session's dir**. Otherwise Cursor
  won't find the chat. `ResumeCommand.build` returns argv only, so extend it
  (or `SessionAction.resume`) with env. The interactive session inherits
  orca's generated `cli-config.json`; for the TUI, rewrite it to allow-all
  first.
- **Flow authoring slug** (`FlowAuthoring.slugArgv`):
  `Seq("cursor-agent", "-p", "--trust", "--model", pin.getOrElse("auto"), prompt)`
  (text output is the default).

---

## 9. Proposed enforcement row

| tools, approve | Cursor |
|---|---|
| ReadOnly, * | Hard |
| NetworkOnly, * | Hard |
| Full, All | Hard |
| Full, Only(_) / Only() | Ignored |
| NoTools, * | PromptOnly |

Resumed turns: same as fresh (flags and config file re-applied every turn).

---

## 10. Implementation sketch

Module `cursor/` (`orca-cursor`, package `orca.tools.cursor`), plus the
registration touchpoints in guide §7 (`BackendTag.Cursor`, harness name
`cursor`, binary `cursor-agent`).

```scala
// CursorArgs.scala — argv + cell from ONE match (guide §2.5)
private[cursor] object CursorArgs:
  final case class Wiring(force: Boolean, allow: Seq[String], deny: Seq[String], cell: EnforcementCell)

  def wiring(tools: ToolSet, autoApprove: AutoApprove): Wiring = tools match
    case ToolSet.ReadOnly    => Wiring(true, Nil, ReadOnlyDeny, ReadOnlyCell)
    case ToolSet.NetworkOnly => Wiring(true, Seq("WebFetch(*)"), ReadOnlyDeny, ReadOnlyCell)
    case ToolSet.NoTools     => Wiring(true, Nil, NoToolsDeny, NoToolsCell)
    case ToolSet.Full => autoApprove match
      case AutoApprove.All     => Wiring(true, Nil, Nil, AllCell)
      case AutoApprove.Only(_) => Wiring(false, Nil, Nil, OnlyIgnoredCell)

  def argv(config: AgentConfig, wireId: String, w: Wiring, mcp: Boolean): Seq[String] =
    Seq("cursor-agent", "-p", "--output-format", "stream-json",
        "--stream-partial-output", "--trust", "--resume", wireId) ++
      CliArgs.flag("--model", config.model.orElse(Some(CursorModels.Auto)))(_.name) ++
      Option.when(w.force)("--force") ++ Option.when(mcp)("--approve-mcps")

// CursorBackend.open — per turn:
//   dir  = OrcaDir.ensureCursorSession(workDir, session)   // .orca/cache/cursor/<client-id>/
//   write dir/cli-config.json  {version:1, permissions:{allow: w.allow ++ mcpAllow, deny: w.deny}}
//   Interactive → AskUserSession.allocate() + TurnResources.useCloseable(CursorMcpSettings.register(workDir, url))
//   prompt = SystemPromptComposer.foldIntoPrompt(config, prompt, askUserHint)
//   SubprocessSpawn.open("cursor-agent", events)(
//     cli.spawnPiped(argv, env = Map("CURSOR_CONFIG_DIR" -> dir.toString), cwd = workDir)
//   ){ p => p.writeLine(prompt); p.closeStdin(); CursorTurn(p, mode.openingPrompt, outputSchema, askUser) }
//
// sessions = SessionSupport.durable(IdScheme.ClientClaimed, id =>
//   metaJson(dir(id), md5(workDir.toNIO.toRealPath())).exists(_.hasConversation))
// structuredOutputMode = RawText;  cheapModel(_) = None
```

`-p` reads stdin to EOF as the prompt. A multi-line prompt with a blank line
and a `|`-prefixed line arrived whole, and its last-line instruction was
followed **[verified]**. So `writeLine(prompt)` then `closeStdin()` is enough;
the trailing newline doesn't matter.

Tests: `CursorArgsTest` (argv per tier and dispatch), `CursorTurnTest` over
recorded fixtures (partial and non-partial, denied tools, MCP echo, failed
turn), `CursorBackendTest` (config file contents, env, MCP merge and restore),
`CursorIntegrationTest` (gated: fresh, resume, read-only write attempt,
`Delete` attempt, subagent attempt).

---

## 11. Open questions / to probe before shipping

1. Linux: auth location vs `CURSOR_CONFIG_DIR` (§5.1). **Blocking** for the
   recommended design.
2. `Write(**)` coverage of notebook edits and `Task` subagents (`Delete` is
   covered, verified).
3. ~~Multi-line stdin prompt~~ — verified: read to EOF.
4. The `mcp.json` per-server timeout key.
5. Does an `is_error: true` result exist? Record one (rate limit, auth expiry).
6. Resume under a stricter tier than creation (enforcement on `Resumed`).
7. `hasConversation` on a chat whose first turn was cancelled mid-stream (not
   just one that failed before the model ran).
8. Paid-plan behaviour: named models, whether `result` then carries a model id.

## Appendix: probe commands

```bash
# fresh turn, partial output
echo "Read a.txt …" | cursor-agent -p --output-format stream-json --stream-partial-output --trust --model auto
# client-claimed chat
U=$(uuidgen | tr A-Z a-z); echo "say hi" | cursor-agent -p --output-format stream-json --trust --model auto --resume $U
# hard read-only via relocated config dir
mkdir -p /tmp/cfg && echo '{"version":1,"permissions":{"allow":[],"deny":["Write(**)","Shell(*)"]}}' > /tmp/cfg/cli-config.json
echo "create WROTE.txt; run touch SHELL.txt" | CURSOR_CONFIG_DIR=/tmp/cfg cursor-agent -p --output-format stream-json --trust --model auto --force
# MCP: .cursor/mcp.json in the project + --approve-mcps + allow Mcp(orca:*)
```
