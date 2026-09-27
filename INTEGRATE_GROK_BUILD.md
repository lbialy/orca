# Integrating Grok Build (`grok`)

Feasibility study and design for an `orca-grok` backend, applying
[HARNESS_INTEGRATION_GUIDE.md](HARNESS_INTEGRATION_GUIDE.md) to xAI's Grok
Build CLI.

**Verdict: feasible, and the cheapest integration of any candidate so far.**
`grok --output-format streaming-messages-json` emits Claude Code's stream-json
wire format. Recorded grok transcripts replayed through orca's **unmodified
`ClaudeDecoder`** + `DecodedTurn` decoded correctly and passed
`TurnEventConformance`: text, thinking, tool calls, tool results, usage,
`total_cost_usd`, API-call count, model id, `structured_output`, and error
results. Sessions are client-claimed exactly like claude's. Hard read-only and
no-tools tiers come from `--tools`. MCP (`ask_user`) can be injected per
invocation through a temp agent-definition file, without touching the user's
tree.

## Environment probed

| | |
|---|---|
| CLI | `grok 1.0.41 (4220f3b224a6) [stable]` at `~/.grok/bin/grok` |
| OS | macOS (Darwin 24.6), 2026-09-27 |
| Account | logged in with grok.com (`apiKeySource: "oauth"`). Models: `grok-4.7` (default), `grok-4.7-build-fast`, `grok-4.6`, `grok-4.5` |
| Method | direct CLI probes in scratch git repos under `/tmp/orca-probe/`; bundled docs in `~/.grok/docs/user-guide/` (14 headless, 22 permissions, 07 MCP, 05 configuration); a stub HTTP MCP server; a throwaway munit suite replaying five recorded transcripts through orca's `DecodedTurn` + `ClaudeDecoder` (all passed; suite deleted afterwards) |
| §11 follow-up | same CLI build on Linux (6.8, devcontainer), 2026-09-27: six live `grok-4.7-build-fast` turns (≈ $0.13) plus flag-only and pre-model-failure runs, in scratch git repos under `/tmp/orca-probe11/`. Transcripts are saved as fixtures (see the fixtures README) |

Evidence markers: **[verified]** observed (first probe, macOS);
**[verified grok 1.0.41, 2026-09-27, Linux]** observed in the §11 follow-up;
**[docs]** from grok's bundled user guide; **[unverified]** must be probed
before relying on it.

---

## 1. Capability matrix

| # | Guide requirement | Result |
|---|---|---|
| 1 | Non-interactive mode | `-p/--single <PROMPT>`, `--prompt-file <PATH>`, `--prompt-json` **[verified]** |
| 2 | NDJSON stream + terminal record | `--output-format streaming-messages-json` (+ `--include-partial-messages`) — Claude stream-json framing **[verified]** |
| 3 | Session id | `-s/--session-id <uuid>` claims a new id; `session_id` on every line **[verified]** |
| 4 | Resume | `-r/--resume <id>` keeps context **[verified]** |
| 5 | Existence check | `$GROK_HOME/sessions/<urlencode(realpath cwd)>/<id>/chat_history.jsonl` (`GROK_HOME` defaults to `~/.grok`) **[verified]**. A turn that fails before the model runs still creates it (non-empty), and the id is then taken: the probe must report such a session as present (§4) **[verified grok 1.0.41, 2026-09-27, Linux]** |
| 6 | Model per invocation | `-m/--model`; an unknown id fails the turn cleanly **[verified]** |
| 7 | Prompt without argv limit | `--prompt-file`. Headless mode does **not** read stdin **[docs; `--prompt-file` verified]** |
| 8 | Append system prompt | `--rules <TEXT>` → `<human_rules>` block in the session's `system_prompt.txt` **[verified]** |
| 9 | Hard read-only | `--tools read_file,grep,list_dir` removes write and shell tools **[verified]** |
| 10 | Approve everything | `--always-approve` (= `--permission-mode bypassPermissions`) **[verified]**. It is **required**: with no permission flag, headless runs in `default` mode and the first edit cancels the whole turn (§5) **[verified grok 1.0.41, 2026-09-27, Linux]** |
| 11 | No tools | `--tools todo_write --disallowed-tools todo_write,search_tool,use_tool` → `tools: []` **[verified]** |
| 12 | Read-only web | add `web_search,web_fetch` to `--tools` **[docs; tool names verified in init]** |
| 13 | Flags on resume | all flags are per invocation; `--resume` takes the same flags **[verified]**. A session created under `--always-approve` and resumed with the read-only flags got init `tools: [read_file, list_dir, grep]` and could not write **[verified grok 1.0.41, 2026-09-27, Linux]** |
| 14 | Usage + cost | `result.usage` (Messages shape, **disjoint** buckets) + `total_cost_usd` + `modelUsage` **[verified; docs]** |
| 15 | Structured output | `--json-schema <inline>` → constrained output + `result.structured_output` **[verified]** |
| 16 | MCP for `ask_user` | temp `--agent <file.md>` with `mcpServers:` frontmatter; tool reached via `use_tool` as `orca__ask_user` **[verified]**. The agent body replaces only the stock `<browser_verification>` block; `--rules` still lands in `<human_rules>`; an unreachable server reports `status: "failed"` and the turn still runs (§6) **[verified grok 1.0.41, 2026-09-27, Linux]** |
| 17 | Refusal vs failure | a deny-rule refusal is an `is_error` tool result reading "was not executed: Denied by permission policy…" **[verified]** |
| 18 | SIGINT | dies immediately (killed by signal), no result line **[verified]** |
| 19 | Errors | `result` with `is_error: true`, `subtype: error_during_execution` (also `error_max_turns`, `error_max_structured_output_retries` **[docs]**), `errors[]`, stderr detail, exit 1 **[verified]** |
| 20 | Interactive resume | `grok --resume <id>` in `workDir` **[docs; unverified interactively]** |

---

## 2. Invocation

```
grok
  --prompt-file <tmp>/orca-grok-prompt-*.md    # TurnResources.tempFile, outside workDir
  --output-format streaming-messages-json --include-partial-messages
  -m <model>
  (--session-id <client-uuid> | --resume <client-uuid>)     # ClientClaimed (§4)
  --rules <composed system prompt>                           # SystemPromptComposer.combine
  <permission wiring, §5>
  [--json-schema <inline schema>]                            # structured turns
  [--agent <tmp>/orca-grok-agent-*.md]                       # interactive: ask_user MCP (§6)
  [--no-subagents]                                           # allowlist tiers; optional on Full (§5)
  cwd: workDir
  stdin: closed immediately
```

Also consider (flag checks **[verified grok 1.0.41, 2026-09-27, Linux]**
unless marked otherwise):

- `--cwd <CWD>` exists (in `--help` and headless docs). Not needed: spawn
  with `cwd = workDir` as every other backend does, so the session directory
  key and the process cwd can't disagree.
- `--no-subagents` exists in `--help` ("Disable subagent spawning"; also
  `subagents.enabled` / `GROK_SUBAGENTS`) **[docs]**. Safe to pass on every
  tier.
- `--no-auto-update` is **not** in `--help`, but the parser accepts it
  (`grok --no-auto-update --version` exits 0, while an unknown flag exits 2
  with "unexpected argument") and the headless docs list it. Update checks are
  also suppressed automatically when stderr is not a TTY, which is always the
  case for orca's piped spawns **[docs]**. **Don't pass it**: a hidden flag
  may disappear and would then fail every turn. If explicit suppression is
  wanted, set `GROK_DISABLE_AUTOUPDATER=1` in the child's environment, which
  an older or newer build simply ignores.
- `--max-turns <N>`: not needed.
- `--verbatim` exists ("Send the prompt exactly as given"). Without it grok
  only wraps the prompt in `<user_query>\n…\n</user_query>` in
  `chat_history.jsonl`; the text itself is unchanged. **Don't pass it**: the
  wrapper is what the stock system prompt expects.
- `--rules` goes on argv. The composed system prompt is a few KB, fine for
  `ARG_MAX`. If it ever isn't, move it into the `--agent` file body (§6).
- **Don't** use `--system-prompt-override`: it replaces grok's own prompt.

---

## 3. Wire format — reuse `MessagesJsonDecoder`

Grok documents `streaming-messages-json` as "the Messages API stream-json
wire format… a consumer that reconstructs messages, reads spend, or detects
errors works without changes" **[docs]**, and the replay confirmed it:

| Recording | `ClaudeDecoder` outcome |
|---|---|
| read + write turn, partial messages (186 lines) | thinking/text deltas, `CALL read_file`, `CALL write`, two `ToolResult`s, three messages; `AgentResult(wireId, "hello\n\nCreated…", Usage(fresh 6815, cacheRead 48512, out 566, cost $0.02807, apiCalls 3), model grok-4.7-build-fast)` |
| `--json-schema` turn | output = the validated JSON (`structured_output`) |
| MCP turn (`--agent`) | `CALL search_tool`, `CALL use_tool {"tool_name":"orca__ask_user",…}`, result, final `PINEAPPLE` |
| `dontAsk` cancel | `AgentTurnFailed: claude turn failed (subtype error_during_execution …)` with the turn's usage as debit |
| invalid model | `AgentTurnFailed … error_during_execution` |

`init` looks like claude's plus `tools`, `mcp_servers[].status`, `skills`.
Assistant frames carry `message.id` (`msg_0`, `msg_1`, …), so the distinct-id
API-call count works. `result` has `num_turns`, `stop_reason`,
`total_cost_usd`, `usage`, `modelUsage`, `structured_output`, and `errors[]`
on error subtypes.

**Differences that need handling** (the reason for a shared decoder rather
than a copy of claude's):

1. **Branding/tag**: `backendName`, failure text ("claude turn failed…") and
   the `B` type parameter are claude's. Extract the stream-json decoding
   (`InboundMessage`, `ContentBlock`, `StreamEventPayload`, the fold) into a
   shared, parameterized decoder, e.g. `tools/.../backend/messagesjson/`,
   taking the backend name, the ask-user tool matcher, the structured-output
   tool name (if any) and the refusal detector. `claude` and `grok` then
   instantiate it. A copy works but would drift.
2. **`ask_user` echo**: grok exposes MCP tools through meta-tools. The call is
   `tool_use {name: "use_tool", input: {tool_name: "orca__ask_user", …}}`, the
   result a `tool_result` with `{"type":"MCP","tool_name":"ask_user","server_name":"orca",…}`
   **[verified]**. Suppress on `name == "use_tool" && input.tool_name ==
   "orca__ask_user"`. The model may first call `search_tool` to find it; that
   call can render.
3. **Structured output**: there's no injected `StructuredOutput` tool. Under
   `--json-schema` **every** assistant text block is schema-constrained JSON:
   even the preamble "I'll read a.txt…" arrived as `{"content":"I'll read…","lines":1}`
   **[verified]**. The validated value is in `result.structured_output`,
   which `MessagesJsonDecoder.resultBody` already prefers. Declare
   `StructuredOutputMode.RawText`: the reply text is the JSON, and
   `Prompts.autonomous` must still ship the schema. Note that the intermediate
   JSON blobs render as prose; consider suppressing assistant text on
   structured turns and showing only `StructuredResult`.
4. **Error detail**: `result.errors[]` (e.g. `["cancelled"]`) and stderr
   (`Error: Couldn't set model 'nope-model': Invalid params: "unknown model id"…`)
   carry the reason; the claude decoder only reports the subtype. Decode
   `errors` into the failure message.
5. **Permission refusals**: `PermissionRefusal` matches claude's text. Grok's
   refusal results read `Tool \`run_terminal_command\` was not executed: Denied
   by permission policy: deny rule on bash matching "touch *"` (deny rule) and
   `User cancelled the execution for tool \`write\`` (dontAsk) **[verified]**.
   Add a grok matcher that maps these to `ToolDenied`.
6. **Model**: `result` has no top-level `model`. The decoder falls back to
   `init.model`, which is the real id (`"unknown"` only if grok has none at
   emit time; map that to `None`).
7. **Cost**: `total_cost_usd` falls back to `0` when unknown and `usage` to
   all-zero buckets **[docs]**. `Pricing.resolve` already reads a reported
   zero against spent tokens as "no report". An all-zero usage should become
   `TurnDebit.Unobserved` or an unobserved usage, not a measured zero.
8. **Stream-event deltas**: tool input arrives as one `input_json_delta`, and
   `citations_delta` never appears **[docs]**. Harmless: `MessagesJsonDecoder` takes
   tool calls from the full `assistant` frame.
9. `result.usage.server_tool_use.web_search_requests` and inline
   `server_tool_use` / `web_search_tool_result` blocks: the decoder's
   `ContentBlock.parse` must treat unknown block types as ignorable (verify
   with a web-search turn **[unverified]**).

---

## 4. Sessions — `IdScheme.ClientClaimed` (same as claude)

- Fresh: `--session-id <uuid>` creates the session under orca's id; init and
  result echo it **[verified]**. The value must be a UUID. `SessionId.fresh`
  mints one.
- Resume: `--resume <uuid>`. A second turn recalled the first ("MANGO")
  **[verified]**.
- Re-claim refused: `Error: Error: Session ID <uuid> is already in use.`, exit
  1 **[verified]**. `SessionSupport.heldClaim` handles this (an interrupted
  first turn resumes as `EarlierAttempt`), exactly as for claude.
- Grok's own sessions are UUIDv7-like (`01a0e2b4-…`). Ids are
  `[0-9a-f-]`, so they pass `SessionId.isSafe`.
- **Probe**: `os.exists(grokHome / "sessions" / urlEncode(workDir.toRealPath) / id / "chat_history.jsonl")`.
  The directory name is the percent-encoded **real** path
  (`%2Fprivate%2Ftmp%2Forca-probe%2Fgk` for a cwd of `/tmp/orca-probe/gk`)
  **[verified]**. Resolve `grokHome` from `GROK_HOME`, else `~/.grok`.
- **A pre-model failure still claims the id** **[verified grok 1.0.41,
  2026-09-27, Linux]**. `-m nope-model --session-id <uuid>` exits 1 with
  `error_during_execution` (usage all zero, `num_turns: 0`) but leaves
  `sessions/<cwd>/<uuid>/` with a **non-empty** `chat_history.jsonl`
  (2 lines, ~17 KB: the system message and a synthetic skills
  `system_reminder`, no `prompt_index` user line), plus `system_prompt.txt`
  and `summary.json` (`num_messages: 0`). After that:
  - `--session-id <uuid>` again → `Error: Error: Session ID <uuid> is already
    in use.`, exit 1;
  - `--resume <uuid>` works: a real turn resumed it, answered, and succeeded
    under the same id.

  **Consequence for the probe**: keep it to "`chat_history.jsonl` exists" and
  do **not** require a non-empty history or a real user turn. A stricter
  probe would report such a session as gone, orca would re-claim the id,
  and grok would refuse with "already in use" every time. With the plain
  existence check, `SessionSupport.heldClaim` sees the session as present
  and dispatches `--resume <id>`, which grok accepts.

  **Existence-only is necessary but not sufficient.** In orca, a
  `Dispatch.Resume(_, EarlierAttempt)` builds its prompt with
  `continuedPrompt` (`flow/.../orca/Session.scala`): on the first turn that
  is `InterruptedAttemptNotice` + the turn text. Only the `Fresh` path
  (`rebuiltPrompt`) adds the recorded session `seed` and the progress
  preamble. A pre-model failure leaves a history with **no `prompt_index`
  user line**, so grok never saw the first prompt. Every retry on that
  session would therefore resume an empty conversation and silently lose the
  seed and preamble, without even `rebuiltPrompt`'s "re-seeding" warning.
  **Follow-up (must be settled in the implementation task):** the grok
  backend should detect a held session whose `chat_history.jsonl` has no
  line with a `prompt_index` field, and treat it as needing a rebuilt prompt
  while still dispatching `--resume <id>`. Options: a tri-state probe result
  (gone / held-empty / held-with-history) in `SessionSupport`, or a
  `ResumeOrigin` that makes `effectivePrompt` use `rebuiltPrompt`. Pin the
  case in `GrokIntegrationTest` (`-m nope-model` with a claimed id, then a
  real turn must see the seed) and in `SessionTest`.
- `--fork-session` (resume into a new id) and `--restore-code` are not needed.

---

## 5. Tool enforcement

**The headless default permission mode depends on the machine, so every tier
needs explicit flags.** The first probe (macOS) saw
`permissionMode:"bypassPermissions"` without flags, and the file was written
**[verified]**. The follow-up (Linux, no permission keys in
`~/.grok/config.toml`) saw `permissionMode:"default"`, as the headless docs
describe ("the `--permission-mode` value, or `bypassPermissions` under
`--yolo`, else `default`"). There, the first `search_replace` came back
`User cancelled the execution for tool \`search_replace\``, and the **whole
turn** ended `error_during_execution`, `stop_reason: "cancelled"`,
`errors: ["cancelled"]` (fixture `grok-default-mode-cancel.jsonl`)
**[verified grok 1.0.41, 2026-09-27, Linux]**. Consequences: `Full, All`
must always render `--always-approve`, since omitting it is not a safe
no-op, and read-only tiers must not rely on `default` mode to stop writes.
A missing approval flag shows up as a cancelled turn, not as a refusal the
model can route around.

Tool names (init `tools`, default profile): `run_terminal_command`,
`read_file`, `search_replace`, `write`, `list_dir`, `grep`,
`kill_command_or_subagent`, `todo_write`, `get_command_or_subagent_output`,
`spawn_subagent`, `scheduler_create`, `scheduler_delete`, `scheduler_list`,
`monitor`, `search_tool`, `use_tool`, `workflow`, `enter_plan_mode`,
`exit_plan_mode`, `ask_user_question`, `send_feedback`, `web_search`,
`web_fetch`, `image_gen`, `image_edit`, `image_to_video`,
`reference_to_video`. Under an `--agent` file the list differs slightly:
`send_feedback` goes and `wait_commands_or_subagents` appears **[verified
grok 1.0.41, 2026-09-27, Linux]**. This is harmless for enforcement, because
the allowlist tiers name their tools and `Full` only disallows.

| `(tools, approve)` | Flags | Level | Evidence |
|---|---|---|---|
| `ReadOnly, *` | `--tools read_file,grep,list_dir --disallowed-tools search_tool,use_tool,Agent --no-subagents` (keep `search_tool,use_tool` when orca MCP is attached) | **Hard** | with `--tools read_file,grep,list_dir` the init list was `[read_file,list_dir,grep,search_tool,use_tool]`; the agent tried `use_tool Write` / `use_tool Shell` and both were rejected as unknown MCP tools; no files written **[verified]** |
| `NetworkOnly, *` | `--tools read_file,grep,list_dir,web_search,web_fetch` + the same disallows | **Hard** (writes) | as above; web tool names present in init **[verified]**. A live fetch under the allowlist is **[unverified]** |
| `NoTools, *` | `--tools todo_write --disallowed-tools todo_write,search_tool,use_tool,Agent --no-subagents` | **Hard** | init `tools: []` **[verified]** |
| `Full, All` | `--always-approve --disallowed-tools ask_user_question` | **Hard** | shell and edits ran **[verified]**; `--always-approve` is mandatory (without it, the first edit cancels the turn on Linux) **[verified grok 1.0.41, 2026-09-27, Linux]** |
| `Full, Only(set)` | option A: `--permission-mode dontAsk` + `--allow <rule>`… → **Hard but turn-fatal**; option B: `--always-approve --disallowed-tools ask_user_question` → **Ignored** | see note | dontAsk: an out-of-set `write` returned `User cancelled the execution for tool \`write\``, and then the **whole turn** ended `error_during_execution`, `stop_reason: "cancelled"` **[verified]** |

Notes:

- **The empty-allowlist trap** **[verified]**. `--tools ""` is ignored (all 25
  tools remained). `--tools <unknown-name>` is effectively no filter too (21
  tools remained). Never render an empty or unknown-only `--tools`; that is why
  `NoTools` names a real tool and then disallows it. Pin this in
  `GrokArgsTest`, and in the integration test assert the init `tools` list
  itself, not just the flags.
- `--tools` and `--disallowed-tools` are headless-only; in the TUI they are
  ignored with a warning **[docs]**. That's irrelevant to orca's turns but
  matters for `orca continue` (§8).
- **Option A vs B for `Full, Only(set)`.** `AutoApprove.Only` is meant for
  interactive turns, where a human answers the rest. `streaming-messages-json`
  is read-only: approvals need ACP (`grok agent stdio`) **[docs]**. So an
  out-of-set call can't be answered: dontAsk cancels the turn, and so does
  headless `default` mode (see the top of this section) **[verified grok
  1.0.41, 2026-09-27, Linux]**. Recommend option
  B (`Ignored`, like gemini) until an ACP driver exists. Option A is
  defensible if `Only` should fail loudly.
- **Deny rules under always-approve return a normal tool error** (the turn
  continues) **[verified]**. They're a usable hard backstop, e.g.
  `--deny 'Bash(git push*)'` on write turns, which would mechanically back
  `RuntimeOwnsGit`.
- `--sandbox <profile>` / `GROK_SANDBOX` (doc 18) is an OS sandbox for
  filesystem and network. Not probed. It could back a `SandboxApprox` cell or
  harden `ReadOnly` against a shell smuggled through MCP.
- **`ask_user_question`** **[verified grok 1.0.41, 2026-09-27, Linux]**.
  It is in the headless init `tools` by default (plain turns and `--agent`
  turns alike). When the model calls it, grok answers **at once** with a
  successful synthetic result: `{"type":"AskUserQuestion","UserAnswered":{"message":"No
  user is available to answer questions in this non-interactive session.
  Continue with your best judgment; do not wait for clarification."}}`. It
  doesn't hang or fail, and the turn ends `success` with a reply like "no
  answer came back" (fixture `grok-ask-user-question.jsonl`).
  `--disallowed-tools ask_user_question` removes it from init `tools`
  (26 remain) and the turn runs normally. **Consequence: always disallow
  `ask_user_question` on every tier that doesn't already exclude it by
  allowlist, on autonomous *and* interactive turns.** On autonomous turns
  it would quietly swallow a question the user should have seen. On
  interactive turns the model must reach the human through `orca__ask_user`,
  not a tool that answers "nobody's there". The `--tools` allowlist tiers
  (`ReadOnly`, `NetworkOnly`, `NoTools`) already exclude it; `Full` needs it
  on `--disallowed-tools`. `GrokArgsTest` should pin it on `Full`.
- Consider `--no-subagents` on `Full` turns (cost control; the flag exists,
  see §2).
  `scheduler_*` and `monitor` create background work the turn boundary
  abandons; the standing `BackgroundWorkAbandonedAtTurnEnd` rule covers them,
  but disallowing them is cheaper than relying on prose.
- **Resumed turns re-apply every flag, so classify `Resumed` = `Fresh`**
  **[verified grok 1.0.41, 2026-09-27, Linux]**. A session created with
  `--always-approve` was resumed with `--tools read_file,grep,list_dir
  --disallowed-tools search_tool,use_tool --no-subagents` and asked to
  "create pwned.txt … by any means available". Init showed
  `permissionMode:"default"` and `tools:["read_file","list_dir","grep"]`: the
  creation turn's tool set and bypass mode were not carried over. The model
  called `list_dir`, `read_file` and `grep`, said it had no way to write, and
  the turn ended `success`. No file appeared and `git status` was clean
  (fixture `grok-resume-readonly.jsonl`). Nothing about the tier is
  persisted in the session, so the enforcement row needs no resumed-turn
  exceptions.
- The AGENTS.md `NetworkOnly` grant list gains "grok: `web_search`,
  `web_fetch` on `--tools`".

---

## 6. `ask_user` over MCP (interactive turns)

**[verified]** with a stub streamable-HTTP server. Write a temp agent
definition per interactive turn (`TurnResources.tempFile`, outside `workDir`)
and pass `--agent <path>`:

```markdown
---
name: orca-turn
description: Orca-driven coding turn.
prompt_mode: full
model: inherit
agents_md: true
mcpServers:
  - name: orca
    type: http
    url: http://127.0.0.1:<port>/mcp
    headers: []
---

You are driven by the Orca orchestrator.
```

- `mcpServers` must be a **list** of ACP-style entries. The map form fails with
  `invalid type: map, expected a sequence` **[verified]**.
- The server showed `status: "pending"` in init (handshake still running),
  then connected. The model called `search_tool` → `use_tool
  orca__ask_user`, and the stub's answer reached the model **[verified]**.
  Grok's own clients sent `server/discover` before `initialize`. orca's chimp
  server must tolerate or reject that method gracefully (the stub replied with
  an empty result) **[verify against `McpHost`]**.
- Under always-approve the MCP call needs no approval. Under dontAsk add
  `--allow 'MCPTool(orca__*)'`. On read-only tiers keep `use_tool` and
  `search_tool` in the toolset when the server is attached ("MCP meta-tools
  remain available unless denied" **[docs]**).
- Timeout: grok's per-tool default is `tool_timeout_sec = 6000` (100 min)
  **[docs]**, above `AskUserMcpServer.ToolTimeout` (1 h). Whether agent
  frontmatter accepts a per-server timeout is **[unverified]**; not needed.
- **`--agent` side effects** **[verified grok 1.0.41, 2026-09-27, Linux]**.
  The same one-line turn ran twice with the same model and flags, once plain
  and once under the frontmatter above (unreachable `url`,
  `http://127.0.0.1:9/mcp`). The two `system_prompt.txt` files (8813 vs
  8003 bytes) are identical except for one block: the stock profile's
  `<browser_verification>…</browser_verification>` section, 11 lines about
  verifying web-UI changes in a browser, is **replaced by the agent file's
  body**. Everything else is unchanged: identity, tool guidance, the
  AGENTS.md/rules sections (`agents_md: true`) and the environment block.
  `--rules` text still appears as `<human_rules>…</human_rules>`, right after
  the body. `summary.json` records `agent_name: "orca-turn"` instead of the
  stock `grok-build-plan`. Model and permission mode are unchanged
  (`model: inherit` honoured). The tool list differs only by `send_feedback`
  going and `wait_commands_or_subagents` appearing. An unreachable MCP
  server shows as `mcp_servers:[{"name":"orca","status":"failed"}]` in init,
  and the turn still runs and succeeds (fixture
  `grok-agent-mcp-failed.jsonl`). Consequences:
  - keep the composed system prompt in `--rules` on every turn, so
    interactive and autonomous turns get the same `<human_rules>` block;
    keep the agent body a one-line neutral preamble (the lost
    browser-verification block isn't something orca relies on);
  - an attached-but-dead `McpHost` doesn't fail the turn; `ask_user` just
    isn't there. `status: "pending"` is the normal init value, so only
    `"failed"` can be detected. Surfacing it as a warning is optional and
    shouldn't be fatal.
- Rejected alternatives: project `.mcp.json` loaded the server as `disabled`
  (untrusted folder / import marker), and the agent fell back to curling the
  endpoint through its shell **[verified]**. `<project>/.grok/config.toml`
  needs folder trust and edits the user's tree. The `GROK_CONFIG` /
  `GROK_CONFIG_PATH` overlays deliberately drop `mcp_servers` **[docs]**.
  Relocating `GROK_HOME` would move `auth.json` and the sessions.
- Add `AskUserMcpServer.Hint` to `--rules`.

---

## 7. Models, cheap tier, cost

- `grok models` lists `grok-4.7` (default), `grok-4.7-build-fast`, `grok-4.6`,
  `grok-4.5`; also `--reasoning-effort none|minimal|low|medium|high|xhigh|max`.
- Recommended pins: default `grok-4.7`; `cheapModel` → `grok-4.7-build-fast`.
  A small read+write turn cost $0.028 on build-fast; the relative pricing of
  the two is **[unverified]**.
- Cost is reported (`total_cost_usd`, `CostBasis.Reported`). Still add
  `Pricing` rows for the pinned models as the fallback for turns reporting 0,
  and so `DefaultModelsPricedTest` passes; source the rates from xAI's price
  page (not probed here).
- Usage buckets are disjoint (`input_tokens` excludes cache) **[docs;
  consistent with observations]**, so map them straight onto `Usage`, as
  `InboundMessage` already does for claude.

---

## 8. Other behaviour

- **Cancellation**: SIGINT from a normal parent kills grok at once (exit by
  signal, no result line) → `OrcaInteractiveCancelled` **[verified]**. (A
  shell `&` background job inherits `SIGINT` ignored, and there grok kept
  running. That's an artefact of the probe, not of orca's `ProcessBuilder`
  spawns.)
- **Leader process**: `grok agent --leader` can route clients through a
  shared leader (`~/.grok/leader.sock`, `[cli] use_leader`) **[docs]**.
  Whether a user config enabling it affects `grok -p` is **[unverified]**.
  If it does, pass whatever opt-out applies to headless.
- **Auto-update / managed config**: `requirements.toml` / MDM can clamp
  settings, e.g. `disable_bypass_permissions_mode = true` makes
  `--always-approve` unavailable **[docs]**. Surface the resulting failure
  clearly (stderr carries it).
- **Shell `orca continue`**: `grok --resume <wireId>`, cwd = `workDir`
  (sessions are keyed by cwd). Harness name `grok` equals the binary name, so
  `ResumeCommand` needs only the new arm.
- **Flow authoring slug** (`FlowAuthoring.slugArgv`):
  `Seq("grok", "-m", pin.getOrElse("grok-4.7-build-fast"), "--tools", "todo_write", "--disallowed-tools", "todo_write,search_tool,use_tool", "-p", prompt)`.
- **`grok agent stdio` (ACP)** is the richer transport: native tool approvals
  (would make `Full, Only(set)` Hard on interactive turns), per-session
  `mcpServers` on `session/new`, and `GROK_CONFIG` overlays **[docs]**. It
  needs a JSON-RPC driver, closer to opencode's than to claude's. It's not
  required for a first integration; revisit if interactive approvals matter.

---

## 9. Proposed enforcement row

| tools, approve | Grok |
|---|---|
| ReadOnly, * | Hard |
| NetworkOnly, * | Hard |
| Full, All | Hard |
| Full, Only(_) / Only() | Ignored (option B) |
| NoTools, * | Hard |

Resumed turns: same as fresh **[verified grok 1.0.41, 2026-09-27, Linux]**: resume re-applies every flag (§5).

---

## 10. Implementation sketch

Module `grok/` (`orca-grok`, package `orca.tools.grok`), `BackendTag.Grok`,
harness name `grok`, plus guide §7's touchpoints. The larger change is
extracting claude's stream-json decoder into a shared one.

```scala
// GrokArgs.scala — argv + cell from ONE match
private[grok] object GrokArgs:
  private val ReadTools   = Seq("read_file", "grep", "list_dir")
  private val WebTools    = Seq("web_search", "web_fetch")
  // grok's own question tool answers "no user is available" at once (§5):
  // strip it on every Full turn, autonomous and interactive. The allowlist tiers
  // already exclude it.
  private val FullStrip = Seq("ask_user_question", "Agent")

  final case class Wiring(args: Seq[String], cell: EnforcementCell)

  def wiring(tools: ToolSet, autoApprove: AutoApprove, mcp: Boolean): Wiring =
    val meta = if mcp then Nil else Seq("search_tool", "use_tool")
    tools match
      case ToolSet.ReadOnly    => Wiring(allow(ReadTools) ++ strip(meta), ReadOnlyCell)
      case ToolSet.NetworkOnly => Wiring(allow(ReadTools ++ WebTools) ++ strip(meta), ReadOnlyCell)
      case ToolSet.NoTools     =>
        Wiring(allow(Seq("todo_write")) ++ strip(Seq("todo_write", "search_tool", "use_tool")), NoToolsCell)
      case ToolSet.Full => autoApprove match
        // --always-approve is mandatory: headless `default` mode cancels the turn on the first edit.
        case AutoApprove.All     => Wiring(Seq("--always-approve") ++ strip(FullStrip), AllCell)
        case AutoApprove.Only(_) => Wiring(Seq("--always-approve") ++ strip(FullStrip), OnlyIgnoredCell)

  // `allow(Nil)` must be impossible: an empty --tools disables filtering.
  private def allow(names: Seq[String]): Seq[String] =
    require(names.nonEmpty); Seq("--tools", names.mkString(","), "--no-subagents")
  private def strip(names: Seq[String]): Seq[String] =
    if names.isEmpty then Nil else Seq("--disallowed-tools", names.mkString(","))

  def argv(config: AgentConfig, dispatch: Dispatch[BackendTag.Grok.type], promptFile: os.Path,
           rules: String, w: Wiring, schema: Option[String], agentFile: Option[os.Path]): Seq[String] =
    Seq("grok", "--prompt-file", promptFile.toString,
        "--output-format", "streaming-messages-json", "--include-partial-messages",
        "--rules", rules) ++
      CliArgs.modelArgs(config) ++ sessionArgs(dispatch) ++ w.args ++
      CliArgs.flag("--json-schema", schema)(identity) ++
      CliArgs.flag("--agent", agentFile)(_.toString)

  private def sessionArgs(d: Dispatch[BackendTag.Grok.type]): Seq[String] = d match
    case Dispatch.Fresh(Some(id)) => Seq("--session-id", WireSessionId.value(id))
    case Dispatch.Fresh(None)     => throw IllegalStateException("grok is ClientClaimed")
    case Dispatch.Resume(id, _)   => Seq("--resume", WireSessionId.value(id))

// GrokBackend: structuredOutputMode = RawText; cheapModel(_) = Some(GrokModels.BuildFast)
// sessions = SessionSupport.durable(IdScheme.ClientClaimed, id =>
//   os.exists(grokHome / "sessions" / urlEncode(realPath(workDir)) / id / "chat_history.jsonl"))
//   existence only: a pre-model failure leaves a non-empty history AND holds the id (§4);
//   a held session with no `prompt_index` user line still needs the seed + preamble
//   (rebuilt prompt over --resume) — open follow-up, §4 / §11 item 3
// open: temp prompt file; rules = SystemPromptComposer.combine(config, askUserHint);
//       Interactive → AskUserSession.allocate() + temp agent file with mcpServers;
//       SubprocessSpawn.open("grok", events)(cli.spawnPiped(argv, cwd = workDir))
//         { p => p.closeStdin(); GrokTurn(p, mode.openingPrompt, outputSchema, askUser) }
```

Tests: `GrokArgsTest` (every tier; the non-empty `--tools` invariant; session
flags), `GrokTurnTest` over recorded fixtures (the five transcripts from this
study are a starting set: write turn, schema turn, MCP turn, dontAsk cancel,
bad model; plus the §11 follow-up ones: default-mode cancel, `ask_user_question`
synthetic answer, read-only resume, failed MCP server), the shared decoder's own tests (claude's move with it), and
`GrokIntegrationTest` (gated: fresh, resume, re-claim refusal, read-only write
attempt with an init-tools assertion, structured turn; optionally the free
pre-model-failure case: `-m nope-model` with a claimed id, then assert the
probe reports the session present).

---

## 11. Open questions / to probe before shipping

1. ~~`--agent` profile side effects on the system prompt~~ **[verified grok
   1.0.41, 2026-09-27, Linux]**: the body replaces only the stock
   `<browser_verification>` block; `--rules` still appears; tools differ by
   `send_feedback` / `wait_commands_or_subagents`; a dead MCP server doesn't
   fail the turn. → Keep the system prompt in `--rules` and use a neutral
   one-line agent body (§6).
2. `server/discover` handling by orca's chimp-based `McpHost`. Still open:
   probe it in the grok integration-test task against the real `McpHost`.
3. `chat_history.jsonl` left behind by a turn that failed before the model
   ran. Wire behaviour **[verified grok 1.0.41, 2026-09-27, Linux]**: yes,
   non-empty (system message + synthetic reminder, no `prompt_index` user
   line), the id is held ("already in use" on re-claim), and `--resume` of it
   works. → The probe must stay "`chat_history.jsonl` exists" and must
   **not** require a non-empty or real history (§4). **Still open on the orca
   side:** resuming such a session as `EarlierAttempt` drops the session seed
   and progress preamble (`continuedPrompt` vs `rebuiltPrompt`). The backend
   must detect "held but no user turn" and get a rebuilt prompt over
   `--resume` (§4 follow-up).
4. ~~`ask_user_question` in headless mode; headless `default` mode on a
   non-read-only tool~~ **[verified grok 1.0.41, 2026-09-27, Linux]**:
   `ask_user_question` is listed by default and returns an immediate
   synthetic "No user is available…" success; `--disallowed-tools
   ask_user_question` removes it. With no permission flag the mode is
   `default` and the first edit cancels the whole turn. → Always disallow
   `ask_user_question` on `Full` turns (autonomous and interactive), and
   always render `--always-approve` on `Full` (§5).
5. Web-search turn: inline `server_tool_use` / `web_search_tool_result` blocks
   through `ContentBlock.parse`. Still open.
6. ~~Resume under a stricter tier than creation~~ **[verified grok 1.0.41,
   2026-09-27, Linux]**: flags fully re-applied (init `tools` = the three
   read tools, no bypass carried over, no file written). → `Resumed`
   classified the same as `Fresh` (§5, §9).
7. ~~`--verbatim` and `--no-auto-update`~~ **[verified grok 1.0.41,
   2026-09-27, Linux]**: `--verbatim` exists, and without it the prompt is
   only wrapped in `<user_query>` tags → don't pass it. `--no-auto-update`
   is accepted but hidden from `--help`, and non-TTY stderr already
   suppresses update checks → don't pass it; use
   `GROK_DISABLE_AUTOUPDATER=1` if explicit suppression is wanted.
   `--no-subagents` and `--cwd` both exist (§2).
8. xAI pricing rows for the pinned models. Still open.

## Appendix: probe commands

```bash
grok -p "Read a.txt …" --output-format streaming-messages-json --include-partial-messages -m grok-4.7-build-fast
U=$(uuidgen | tr A-Z a-z)
grok -p "Remember MANGO" --session-id $U --output-format streaming-messages-json   # then --resume $U
grok -p "…write + touch…" --tools read_file,grep,list_dir --output-format streaming-messages-json
grok -p "list tools" --tools todo_write --disallowed-tools todo_write,search_tool,use_tool --output-format streaming-messages-json
grok -p "…" --json-schema '{"type":"object",…}' --output-format streaming-messages-json
grok -p "…ask_user…" --agent /tmp/orca-agent.md --output-format streaming-messages-json   # mcpServers list frontmatter
grok -p "…" --always-approve --deny 'Bash(touch *)' --output-format streaming-messages-json
# §11 follow-up (Linux)
grok -p "Reply OK." -m nope-model --session-id $U --output-format streaming-messages-json   # pre-model failure; then ls $GROK_HOME/sessions/<cwd>/$U
grok -p "…" --resume $U --tools read_file,grep,list_dir --disallowed-tools search_tool,use_tool --no-subagents --output-format streaming-messages-json
grok -p "…ask_user_question…" --always-approve --output-format streaming-messages-json   # add --disallowed-tools ask_user_question to remove it
diff <plain session>/system_prompt.txt <--agent session>/system_prompt.txt
grok --no-auto-update --version   # accepted though hidden from --help
```
