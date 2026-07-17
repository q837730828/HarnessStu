# Agent Harness Working Notes

This repository is a small Java agent harness for learning Claude Code style
runtime architecture. Keep this file short and use it as an index; detailed
explanations belong in `docs/` or `HARNESS.md`.

## Project Shape

- `src/main/java/com/ahi/harness/Main.java`: wires settings, tools, model,
  permissions, hooks, sessions, MCP, and the main loop.
- `src/main/java/com/ahi/harness/core/`: conversation state and the agent loop.
- `src/main/java/com/ahi/harness/protocol/`: explicit Agent, Thread, Message/Part,
  Run, Step, Event, Artifact, Checkpoint, Interrupt, Trace, and Error resources.
- `src/main/java/com/ahi/harness/runtime/`: protocol lifecycle recording and
  `.harness/runtime/` persistence boundary.
- `src/main/java/com/ahi/harness/tools/`: built-in tools exposed to the model.
- `src/main/java/com/ahi/harness/tools/external/`: simple stdio and MCP clients.
- `src/main/java/com/ahi/harness/permission/`: mutation and command boundaries.
- `src/main/java/com/ahi/harness/session/`: resumable logs and compaction archives.
- `src/main/java/com/ahi/harness/subagent/`: read-only subagent definitions.
- `docs/ARCHITECTURE.md`: package map and runtime sequence.
- `docs/MCP_CALLING.md`: MCP request, transport, and tool flow notes.
- `docs/MCP_RUNTIME.md`: MCP manager and reload behavior.
- `docs/AGENT_PROTOCOL_RUNTIME.md`: explicit protocol objects, lifecycle, and
  persistence design.

## Validation

Use one command at a time in PowerShell with UTF-8 enabled:

```powershell
mvn -q test
mvn -q package
git diff
```

The tests are the executable acceptance baseline. Add or update tests whenever a
change touches permissions, path boundaries, compaction, tool registration, or
external tool behavior.

## Runtime Boundaries

- Read workspace facts with `list_files`, `read_file`, `grep`, `doc_read`, or
  `skill_load`.
- Use `edit_file` only after reading the target file.
- Use `subagent_write` for `.harness/agents/*.md`; do not edit agent metadata
  directly with generic file tools.
- Keep `.git/`, `.idea/`, `target/`, and private `.harness/` files out of normal
  read/write paths. `.harness/compactions`, `.harness/observations`, and
  `.harness/runtime` are readable because summaries, artifacts, and checkpoints
  point back to them.
- `bash` is for validation commands, not general shell scripting.

## Context Rules

- Keep stable instructions short and route to detailed docs on demand.
- Prefer `doc_read` for `docs/*.md`, `README.md`, `HARNESS.md`, and `AGENTS.md`.
- Prefer `skill_load` for task-specific Markdown under `skills/` or
  `.harness/skills/`.
- Large tool observations are archived and replaced with a short pointer; use
  `read_file` or `grep` on the archive path when details are needed.

## Engineering Style

- Java 8 compatible code only.
- No Spring Boot or LangChain-style framework layer.
- Preserve explicit interfaces for model, tools, permissions, sessions, and
  hooks.
- Make constraints executable where practical: tests beat documentation-only
  rules.
- Keep the main loop simple; add new capabilities as tools, stores, settings, or
  prompt/context structure around the loop.
