# Recorded harness transcripts

Raw stdout captured on 2026-09-27 (macOS) while probing the CLIs for
`INTEGRATE_GROK_BUILD.md` and `INTEGRATE_CURSOR_AGENT.md`.

| File | CLI | Invocation shape |
|---|---|---|
| `grok-write.jsonl` | grok 1.0.41 | `-p … --output-format streaming-messages-json --include-partial-messages -m grok-4.7-build-fast` (read + write turn) |
| `grok-schema.jsonl` | grok 1.0.41 | same, plus `--json-schema` (no partial messages) |
| `grok-mcp.jsonl` | grok 1.0.41 | `--agent <file with mcpServers>`; the model calls `use_tool orca__ask_user` against a stub MCP server |
| `grok-dontask-cancel.jsonl` | grok 1.0.41 | `--permission-mode dontAsk --allow 'Bash(echo *)'`; an out-of-set `write` cancels the turn |
| `grok-badmodel.jsonl` | grok 1.0.41 | `-m nope-model` → `error_during_execution` |
| `cursor-partial.jsonl` | cursor-agent 2026.09.26-dd393fe | `-p --output-format stream-json --stream-partial-output --trust --model auto` (read + edit turn) |
| `cursor-shell-rejected.jsonl` | cursor-agent 2026.09.26-dd393fe | `-p --output-format stream-json --trust --model auto`, no `--force`: shell calls come back `rejected` |
