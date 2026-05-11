# HarnessStu Project Memory

## Project Goal

This project is a small Java agent harness for learning Claude Code style architecture.

The main learning loop is:

```text
user prompt -> model response -> tool call -> local execution -> observation -> model continues -> final answer
```

Prefer clarity over framework complexity. Keep each runtime concept visible in code and logs.

## Runtime Architecture

Important modules:

- `Main`: wires the runtime together.
- `AgentLoop`: owns the model/tool/observation loop.
- `DeepSeekClient`: sends OpenAI-compatible chat completion requests to DeepSeek.
- `ToolRegistry`: exposes tools to the model.
- `PermissionPolicy`: decides whether a tool call is allowed.
- `JsonlSessionStore`: records resumable conversation messages.
- `TodoReadTool` and `TodoWriteTool`: maintain visible agent planning state.
- `HookBus`: emits lifecycle events.
- `HarnessSettings`: loads `.harness/settings.json`.
- `ProjectMemoryLoader`: loads this file, `CLAUDE.md`, and `AGENTS.md`.
- `SlashCommandHandler`: handles local slash commands without sending them to the model.

## Tool Rules

Use tools deliberately:

- Use `list_files`, `read_file`, and `grep` before answering codebase questions.
- Use `todo_write` for multi-step tasks, keep at most one item `in_progress`, and update the list as steps complete.
- Before editing any file, read it first.
- Use `edit_file` with exact `old_text` and `new_text`; do not guess large replacements.
- After code edits, verify with `bash` using safe commands such as `git diff`, `mvn test`, or `mvn package`.
- Treat nonzero command exits as observations to reason from, not as final failure by themselves.

## Safety Rules

Never edit generated, private, or metadata paths:

- `.git/`
- `.harness/`
- `.idea/`
- `target/`

Do not use shell control operators in `bash` commands:

- `;`
- `&&`
- `||`
- pipes
- redirects

Run one validation command at a time.

## Coding Style

Keep the implementation Java 8 compatible because the local Maven runtime may use JDK 8.

Prefer:

- Small classes with direct responsibilities
- Jackson for JSON
- Explicit interfaces for model, tools, permissions, and session storage
- Plain logs that reveal the runtime flow

Avoid:

- Spring Boot
- LangChain-style abstractions
- Hidden magic
- Large refactors unrelated to the current MVP

## Logging Intent

Logs should help a learner understand the harness.

Keep these visible:

- model HTTP request and response JSON structure
- tool arguments
- permission decisions
- diff before writes
- validation command result
- hook lifecycle events
- observations sent back to the model

Do not print raw `reasoning_content`; only print a placeholder and character count.

## MVP Roadmap

Current completed MVPs:

- MVP1: read-only exploration loop
- MVP2: safe edit tool with diff and backup
- MVP3: validation commands through restricted `bash`
- MVP4: runtime engineering features, including settings, hooks, memory, slash commands, resume, and compaction
- MVP5: external tool ecosystem with provider abstraction, simple stdio adapters, MCP-style JSON-RPC stdio adapters, and Streamable HTTP transport
- MVP6: MCP runtime manager with cached MCP clients, stdio process reuse, HTTP session reuse, MCP status, and MCP reload commands
- MVP7: todo planning tools with structured `todo_read` and `todo_write` runtime state

Possible next direction:

- A fuller MCP implementation with server-initiated notifications, progress, cancellation, roots, sampling, and richer Streamable HTTP resumability
- Richer todo integration with session resume, compaction summaries, and slash-command rendering
