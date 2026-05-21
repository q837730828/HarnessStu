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
- `SubagentRunTool`: runs focused read-only subagents in isolated conversations.
- `SubagentWriteTool`: creates or updates validated project subagent definitions.
- `SubagentLoader`: loads project-level subagents from `.harness/agents/*.md`.
- `HookBus`: emits lifecycle events.
- `HarnessSettings`: loads `.harness/settings.json`.
- `ProjectMemoryLoader`: loads this file, `CLAUDE.md`, and `AGENTS.md`.
- `SlashCommandHandler`: handles local slash commands without sending them to the model.

## Tool Rules

Use tools deliberately:

- Use `list_files`, `read_file`, and `grep` before answering codebase questions.
- Use `todo_write` for multi-step tasks, keep at most one item `in_progress`, and update the list as steps complete.
- Use `subagent_run` for focused read-only exploration or review tasks that benefit from an isolated subagent context.
- Use `subagent_write`, not `edit_file`, to create or update `.harness/agents/*.md` project subagents.
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
- estimated context size warnings near and above 256K tokens
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
- MVP8: read-only subagent runtime with `explorer` and `reviewer` roles
- MVP9: project-configurable read-only subagents loaded from `.harness/agents/*.md`
- MVP10: permission modes with interactive approval and persisted allow rules
- MVP11: richer subagent frontmatter for memory, permission mode, model, MCP server metadata, and disallowed tools
- MVP12: executable runtime hooks with optional blocking behavior
- MVP13: traceable compaction archives written to `.harness/compactions/*.jsonl`

## Project Subagents

Project subagents live in `.harness/agents/*.md`. They use simple frontmatter plus a Markdown body:

```markdown
---
name: security-reviewer
description: Read-only reviewer for security-sensitive code paths.
tools: list_files, read_file, grep
disallowed_tools:
max_steps: 10
memory: true
permission_mode: strict
model:
mcp_servers:
---

You are a security reviewer subagent.

Focus on unsafe command execution, path traversal, secret leakage, and missing permission checks.
Return concrete findings with file references.
```

For safety, project subagents may only request `list_files`, `read_file`, and `grep`. `max_steps` is clamped to `1..16`, and project definitions can override built-in names.
Agents may create or update these definitions through `subagent_write`, which validates the same read-only tool boundary before writing under `.harness/agents/`.

## Permissions

`permission_mode` controls tools that can mutate local state or leave the read-only path:

- `strict`: use the configured allowlists and denylists without prompting.
- `ask`: prompt the user for `edit_file`, `bash`, `subagent_write`, and non-allowlisted external tools. Choosing `always` writes a rule to `.harness/permissions.json`.
- `danger-full-access`: allow bash commands after blocked-token and shell-control checks.

`edit_file` still blocks generated, private, and metadata paths such as `.git/`, `.harness/`, `.idea/`, and `target/`. Use dedicated tools such as `subagent_write` for structured harness state.
`bash_allowed_prefixes` and `external_tool_allowlist` support `"*"` as a wildcard. Bash still applies blocked-token and shell-control checks before wildcard allow.

## Hooks

Hooks are configured in `.harness/settings.json`:

```json
{
  "hooks": [
    {
      "event": "PreToolUse",
      "command": "Write-Output \"checking $env:HARNESS_HOOK_DETAIL\"",
      "blocking": true,
      "timeout_seconds": 10
    }
  ]
}
```

Hook commands run from the workspace with `HARNESS_HOOK_EVENT` and `HARNESS_HOOK_DETAIL` environment variables. A blocking hook with a nonzero exit code blocks the tool call.
Hooks may also execute workspace-relative scripts:

```json
{
  "hooks": [
    {
      "event": "PreToolUse",
      "tools": ["bash", "edit_file"],
      "script": "hooks/pre_tool_use.ps1",
      "args": ["example-arg"],
      "blocking": true,
      "timeout_seconds": 10
    }
  ]
}
```

Supported script extensions are `.ps1`, `.cmd`, `.bat`, and `.py`. Script paths must stay inside the workspace.
For `PreToolUse` and `PostToolUse`, hooks can include `tool: "bash"` or `tools: ["bash", "edit_file"]` to run only for selected tools.

## Traceable Compaction

When compaction triggers, the harness writes the full compacted message history to `.harness/compactions/*.jsonl` before removing it from active context. The active summary stores only the archive path, message count, and a short index.

If the model later needs details that were compressed away, it should use `read_file` or `grep` against the archive path. This makes compaction lossy in active context but recoverable from local history.

Possible next direction:

- A fuller MCP implementation with server-initiated notifications, progress, cancellation, roots, sampling, and richer Streamable HTTP resumability
- Richer todo integration with session resume, compaction summaries, and slash-command rendering
- Subagent slash commands and richer per-subagent permission profiles
