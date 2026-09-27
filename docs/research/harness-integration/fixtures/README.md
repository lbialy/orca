# Recorded harness transcripts

Raw stdout captured on 2026-09-27 while probing the CLIs for
`INTEGRATE_GROK_BUILD.md` and `INTEGRATE_CURSOR_AGENT.md`. The first batch was
recorded on macOS; the rows marked *(Linux)* come from the §11 follow-up probes.

| File | CLI | Invocation shape |
|---|---|---|
| `grok-write.jsonl` | grok 1.0.41 | `-p … --output-format streaming-messages-json --include-partial-messages -m grok-4.7-build-fast` (read + write turn) |
| `grok-schema.jsonl` | grok 1.0.41 | same, plus `--json-schema` (no partial messages) |
| `grok-mcp.jsonl` | grok 1.0.41 | `--agent <file with mcpServers>`; the model calls `use_tool orca__ask_user` against a stub MCP server |
| `grok-dontask-cancel.jsonl` | grok 1.0.41 | `--permission-mode dontAsk --allow 'Bash(echo *)'`; an out-of-set `write` cancels the turn |
| `grok-badmodel.jsonl` | grok 1.0.41 | `-m nope-model` → `error_during_execution` |
| `grok-default-mode-cancel.jsonl` | grok 1.0.41 *(Linux)* | no permission flags at all: init `permissionMode:"default"`, the first `search_replace` comes back "User cancelled the execution…" and the turn ends `error_during_execution` / `stop_reason:"cancelled"` |
| `grok-ask-user-question.jsonl` | grok 1.0.41 *(Linux)* | `--always-approve`; the model calls the built-in `ask_user_question`, which returns a synthetic "No user is available…" answer at once; the turn succeeds |
| `grok-resume-readonly.jsonl` | grok 1.0.41 *(Linux)* | `--resume` of an `--always-approve` session with `--tools read_file,grep,list_dir --disallowed-tools search_tool,use_tool --no-subagents`; init `tools:[read_file,list_dir,grep]`, write request declined, no file written |
| `grok-agent-mcp-failed.jsonl` | grok 1.0.41 *(Linux)* | `--agent <file>` whose `mcpServers` entry points at an unreachable port: init `mcp_servers:[{"name":"orca","status":"failed"}]`, turn still succeeds |
| `grok-websearch.jsonl` | grok 1.0.41 *(Linux)* | NetworkOnly flags (`--tools read_file,grep,list_dir,web_search,web_fetch --no-subagents --disallowed-tools search_tool,use_tool,ask_user_question`, no partial messages): one `assistant` frame carrying three inline `server_tool_use` / `web_search_tool_result` pairs next to `thinking` and the reply `text`; `usage.server_tool_use.web_search_requests:3` |
| `cursor-partial.jsonl` | cursor-agent 2026.09.26-dd393fe | `-p --output-format stream-json --stream-partial-output --trust --model auto` (read + edit turn) |
| `cursor-shell-rejected.jsonl` | cursor-agent 2026.09.26-dd393fe | `-p --output-format stream-json --trust --model auto`, no `--force`: shell calls come back `rejected` |
