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

Evidence markers: **[verified]** observed; **[docs]** from grok's bundled user
guide; **[unverified]** must be probed before relying on it.

---

## 1. Capability matrix

| # | Guide requirement | Result |
|---|---|---|
| 1 | Non-interactive mode | `-p/--single <PROMPT>`, `--prompt-file <PATH>`, `--prompt-json` **[verified]** |
| 2 | NDJSON stream + terminal record | `--output-format streaming-messages-json` (+ `--include-partial-messages`) — Claude stream-json framing **[verified]** |
| 3 | Session id | `-s/--session-id <uuid>` claims a new id; `session_id` on every line **[verified]** |
| 4 | Resume | `-r/--resume <id>` keeps context **[verified]** |
| 5 | Existence check | `$GROK_HOME/sessions/<urlencode(realpath cwd)>/<id>/chat_history.jsonl` (`GROK_HOME` defaults to `~/.grok`) **[verified]** |
| 6 | Model per invocation | `-m/--model`; an unknown id fails the turn cleanly **[verified]** |
| 7 | Prompt without argv limit | `--prompt-file`. Headless mode does **not** read stdin **[docs; `--prompt-file` verified]** |
| 8 | Append system prompt | `--rules <TEXT>` → `<human_rules>` block in the session's `system_prompt.txt` **[verified]** |
| 9 | Hard read-only | `--tools read_file,grep,list_dir` removes write and shell tools **[verified]** |
| 10 | Approve everything | `--always-approve` (= `--permission-mode bypassPermissions`). Headless already **defaults** to `bypassPermissions` **[verified]** |
| 11 | No tools | `--tools todo_write --disallowed-tools todo_write,search_tool,use_tool` → `tools: []` **[verified]** |
| 12 | Read-only web | add `web_search,web_fetch` to `--tools` **[docs; tool names verified in init]** |
| 13 | Flags on resume | all flags are per invocation; `--resume` takes the same flags **[verified for resume; stricter-tier-on-resume unverified]** |
| 14 | Usage + cost | `result.usage` (Messages shape, **disjoint** buckets) + `total_cost_usd` + `modelUsage` **[verified; docs]** |
| 15 | Structured output | `--json-schema <inline>` → constrained output + `result.structured_output` **[verified]** |
| 16 | MCP for `ask_user` | temp `--agent <file.md>` with `mcpServers:` frontmatter; tool reached via `use_tool` as `orca__ask_user` **[verified]** |
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
  cwd: workDir     (or --cwd workDir)
  stdin: closed immediately
```

Also consider:

- `--no-auto-update`: headless flag in the docs, not tested. It stops a
  mid-run self-update from changing the wire.
- `--max-turns <N>`: not needed.
- `--verbatim`: sends the prompt exactly as given. Test whether grok rewrites
  prompts without it before deciding **[unverified]**.
- `--rules` goes on argv. The composed system prompt is a few KB, fine for
  `ARG_MAX`. If it ever isn't, move it into the `--agent` file body (§6).
- **Don't** use `--system-prompt-override`: it replaces grok's own prompt.

---

## 3. Wire format — reuse `ClaudeDecoder`

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
   which `ClaudeDecoder.resultBody` already prefers. Declare
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
   `citations_delta` never appears **[docs]**. Harmless: `ClaudeDecoder` takes
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
  Whether a session whose first turn failed leaves `chat_history.jsonl` behind
  is **[unverified]**. If it does, the probe should also require a non-empty
  history, or rely on the refusal-plus-held-claim path.
- `--fork-session` (resume into a new id) and `--restore-code` are not needed.

---

## 5. Tool enforcement

Headless default is **`bypassPermissions`**: without flags, grok edits and runs
shell freely **[verified: `permissionMode":"bypassPermissions"` in init, file
written]**. Every tier therefore needs explicit flags.

Tool names (init `tools`, default profile): `run_terminal_command`,
`read_file`, `search_replace`, `write`, `list_dir`, `grep`,
`kill_command_or_subagent`, `todo_write`, `get_command_or_subagent_output`,
`spawn_subagent`, `scheduler_create`, `scheduler_delete`, `scheduler_list`,
`monitor`, `search_tool`, `use_tool`, `workflow`, `enter_plan_mode`,
`exit_plan_mode`, `ask_user_question`, `send_feedback`, `web_search`,
`web_fetch`, `image_gen`, `image_edit`, `image_to_video`,
`reference_to_video`.

| `(tools, approve)` | Flags | Level | Evidence |
|---|---|---|---|
| `ReadOnly, *` | `--tools read_file,grep,list_dir --disallowed-tools search_tool,use_tool,Agent --no-subagents` (keep `search_tool,use_tool` when orca MCP is attached) | **Hard** | with `--tools read_file,grep,list_dir` the init list was `[read_file,list_dir,grep,search_tool,use_tool]`; the agent tried `use_tool Write` / `use_tool Shell` and both were rejected as unknown MCP tools; no files written **[verified]** |
| `NetworkOnly, *` | `--tools read_file,grep,list_dir,web_search,web_fetch` + the same disallows | **Hard** (writes) | as above; web tool names present in init **[verified]**. A live fetch under the allowlist is **[unverified]** |
| `NoTools, *` | `--tools todo_write --disallowed-tools todo_write,search_tool,use_tool,Agent --no-subagents` | **Hard** | init `tools: []` **[verified]** |
| `Full, All` | `--always-approve` | **Hard** | shell and edits ran **[verified]** |
| `Full, Only(set)` | option A: `--permission-mode dontAsk` + `--allow <rule>`… → **Hard but turn-fatal**; option B: `--always-approve` → **Ignored** | see note | dontAsk: an out-of-set `write` returned `User cancelled the execution for tool \`write\``, and then the **whole turn** ended `error_during_execution`, `stop_reason: "cancelled"` **[verified]** |

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
  out-of-set call can't be answered: dontAsk cancels the turn, and headless
  `default` mode presumably does the same **[unverified]**. Recommend option
  B (`Ignored`, like gemini) until an ACP driver exists. Option A is
  defensible if `Only` should fail loudly.
- **Deny rules under always-approve return a normal tool error** (the turn
  continues) **[verified]**. They're a usable hard backstop, e.g.
  `--deny 'Bash(git push*)'` on write turns, which would mechanically back
  `RuntimeOwnsGit`.
- `--sandbox <profile>` / `GROK_SANDBOX` (doc 18) is an OS sandbox for
  filesystem and network. Not probed. It could back a `SandboxApprox` cell or
  harden `ReadOnly` against a shell smuggled through MCP.
- On autonomous turns, also disallow `ask_user_question` (grok's own
  question tool; its headless behaviour is **[unverified]**: it could hang
  or fail) and consider `--no-subagents` on `Full` turns (cost control).
  `scheduler_*` and `monitor` create background work the turn boundary
  abandons; the standing `BackgroundWorkAbandonedAtTurnEnd` rule covers them,
  but disallowing them is cheaper than relying on prose.
- Resumed turns re-apply every flag, so classify `Resumed` = `Fresh`.
  Confirm by resuming a `Full` session under `ReadOnly` and attempting a write.
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
- Caveat: `--agent` switches the primary agent profile from the stock
  `grok-build` one. With `prompt_mode: full` behaviour looked normal (tools and
  model unchanged), but check the exact effect on the system prompt against a
  plain turn's `system_prompt.txt` before shipping **[unverified]**. The body
  could carry the composed system prompt instead of `--rules`.
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
  `ClaudeDecoder` already does.

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

Resumed turns: same as fresh.

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
  private val AlwaysStrip = Seq("ask_user_question", "Agent")   // autonomous turns

  final case class Wiring(args: Seq[String], cell: EnforcementCell)

  def wiring(tools: ToolSet, autoApprove: AutoApprove, mcp: Boolean): Wiring =
    val meta = if mcp then Nil else Seq("search_tool", "use_tool")
    tools match
      case ToolSet.ReadOnly    => Wiring(allow(ReadTools) ++ strip(meta), ReadOnlyCell)
      case ToolSet.NetworkOnly => Wiring(allow(ReadTools ++ WebTools) ++ strip(meta), ReadOnlyCell)
      case ToolSet.NoTools     =>
        Wiring(allow(Seq("todo_write")) ++ strip(Seq("todo_write", "search_tool", "use_tool")), NoToolsCell)
      case ToolSet.Full => autoApprove match
        case AutoApprove.All     => Wiring(Seq("--always-approve"), AllCell)
        case AutoApprove.Only(_) => Wiring(Seq("--always-approve"), OnlyIgnoredCell)

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
// open: temp prompt file; rules = SystemPromptComposer.combine(config, askUserHint);
//       Interactive → AskUserSession.allocate() + temp agent file with mcpServers;
//       SubprocessSpawn.open("grok", events)(cli.spawnPiped(argv, cwd = workDir))
//         { p => p.closeStdin(); GrokTurn(p, mode.openingPrompt, outputSchema, askUser) }
```

Tests: `GrokArgsTest` (every tier; the non-empty `--tools` invariant; session
flags), `GrokTurnTest` over recorded fixtures (the five transcripts from this
study are a starting set: write turn, schema turn, MCP turn, dontAsk cancel,
bad model), the shared decoder's own tests (claude's move with it), and
`GrokIntegrationTest` (gated: fresh, resume, re-claim refusal, read-only write
attempt with an init-tools assertion, structured turn).

---

## 11. Open questions / to probe before shipping

1. `--agent` profile side effects on the system prompt (§6).
2. `server/discover` handling by orca's chimp-based `McpHost`.
3. `chat_history.jsonl` left behind by a turn that failed before the model ran
   (probe accuracy).
4. `ask_user_question` behaviour in headless mode; headless `default`
   permission mode on a non-read-only tool.
5. Web-search turn: inline `server_tool_use` / `web_search_tool_result` blocks
   through `ContentBlock.parse`.
6. Resume under a stricter tier than creation.
7. `--verbatim` (does grok rewrite prompts by default?) and `--no-auto-update`.
8. xAI pricing rows for the pinned models.

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
```
