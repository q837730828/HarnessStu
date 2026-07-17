# Agent Harness 项目说明

这份文档面向第一次阅读本仓库的人，目标是帮你快速建立整体地图：这个项目是什么、从哪里启动、每个包负责什么，以及建议按什么顺序读代码。

## 1. 项目目标

本项目是一个用 Java 实现的 Claude Code 风格 Agent Harness 学习项目。它关注的不是做一个完整产品，而是把 Agent runtime 的关键边界写清楚：

```text
用户输入
-> 模型生成自然语言或 tool call
-> Harness 做权限判断
-> Harness 执行本地工具或外部 MCP 工具
-> 工具 observation 回灌给模型
-> 循环直到模型给出最终回答
```

核心思想是：模型负责决策，Harness 负责执行边界、权限、日志、上下文、会话和可恢复性。

## 2. 启动入口

入口文件：

```text
src/main/java/com/ahi/harness/Main.java
```

`Main` 做几件事：

1. 读取 `.harness/settings.json` 或默认配置。
2. 创建 `ConsoleLog`、`HookBus`、`ToolRegistry`。
3. 创建 `DeepSeekClient` 作为模型客户端。
4. 注册内置工具、子 Agent 工具、外部 MCP 工具。
5. 创建 session、trace、compaction、observation archive 等 runtime store。
6. 创建 `AgentLoop` 并进入 one-shot 或 interactive 模式。

## 3. 主循环

主循环在：

```text
src/main/java/com/ahi/harness/core/AgentLoop.java
```

阅读时重点看 `run(...)`：

```text
UserPromptSubmit hook
-> conversation.addUser
-> compact if needed
-> model.generate
-> append assistant message
-> if no tool call, stop
-> for each tool call:
     PreToolUse hook
     PermissionPolicy.check
     Tool.execute
     observation archive if too large
     append tool result
-> next model call
```

这条链路是整个项目最重要的骨架。新增能力一般应该放在工具、store、hook、permission 或 settings 里，而不是把主循环变成复杂状态机。

## 4. 关键包职责

```text
com.ahi.harness.core
```

对话状态和主循环。`Conversation` 保存当前发给模型的消息；`Message`、`ToolCall`、`ModelResponse` 是模型协议附近的数据对象。

```text
com.ahi.harness.model
```

模型调用边界。`ModelClient` 是接口，`DeepSeekClient` 把内部 conversation 和 tool schema 转成 OpenAI-compatible chat completions 请求。

```text
com.ahi.harness.tools
```

内置工具。包括文件读取、搜索、编辑、验证命令、todo、子 Agent、文档按需读取、skill 按需加载等。

```text
com.ahi.harness.permission
```

权限层。`PermissionPolicy` 决定工具调用是否允许、拒绝或需要询问用户。

```text
com.ahi.harness.tools.external
```

外部工具和 MCP runtime。`McpServerManager` 统一管理 MCP client 缓存、工具发现、reload、resources、prompts 和状态。

```text
com.ahi.harness.session
```

运行时持久化。包括 JSONL session、上下文压缩归档、超长 observation 归档、结构化 trace。

```text
com.ahi.harness.hooks
```

生命周期 hooks。可以在模型调用、工具调用、压缩等阶段运行外部命令，并可选择阻断工具调用。

```text
com.ahi.harness.subagent
```

子 Agent 定义加载和注册。项目级子 Agent 存在 `.harness/agents/*.md`。

## 5. 工具系统

所有工具都实现：

```text
src/main/java/com/ahi/harness/tools/Tool.java
```

每个工具提供：

- `name()`：模型看到的工具名。
- `description()`：模型选择工具时看到的描述。
- `parameters()`：JSON schema。
- `execute()`：实际执行逻辑。

工具注册发生在 `BuiltInToolProvider` 和 `ExternalStdioToolProvider`。权限判断不在工具自己内部完成，而是在 `AgentLoop -> PermissionPolicy` 这一层完成。

## 6. 上下文和记忆

当前项目有几层上下文来源：

- system prompt：`Main.systemPrompt(...)` 生成的短规则。
- project memory：`HARNESS.md`、`CLAUDE.md`、`AGENTS.md` 由 `ProjectMemoryLoader` 注入。
- on-demand docs：`doc_read` 按需读取 `docs/*.md` 等文档。
- on-demand skills：`skill_load` 按需读取本地 skill Markdown。
- session history：当前 `Conversation` 中的消息。
- compaction archive：旧消息被压缩时写入 `.harness/compactions`。
- observation archive：超长工具结果写入 `.harness/observations`。

阅读重点：上下文不是越多越好。这个项目刻意把长文档和大工具输出放到按需读取或归档路径里，避免每次模型请求都携带大量低密度文本。

## 7. 安全边界

主要安全边界有三类：

1. 路径边界：`WorkspacePaths` 用 canonical path 防止读写逃逸工作区。
2. 权限边界：`PermissionPolicy` 控制编辑、bash、外部工具和 `.harness` 元数据。
3. 执行边界：`BashTool` 只运行 allowlisted 单条验证命令；`HookBus` 只运行工作区内脚本。

如果你要新增会产生副作用的能力，建议先改权限测试，再实现工具。

## 8. MCP 调用链

MCP 相关入口：

```text
src/main/java/com/ahi/harness/tools/external/McpServerManager.java
src/main/java/com/ahi/harness/tools/external/McpStdioClient.java
src/main/java/com/ahi/harness/tools/external/McpStreamableHttpClient.java
```

调用链大致是：

```text
settings.external_tools
-> ExternalStdioToolProvider
-> McpServerManager.loadTools
-> register external__server__tool
-> model requests external tool
-> PermissionPolicy checks external_tool_allowlist
-> ExternalStdioTool.execute
-> McpServerManager.callTool
-> MCP server tools/call
-> observation back to model
```

更详细的协议说明见：

```text
docs/MCP_CALLING.md
docs/MCP_RUNTIME.md
```

## 9. 推荐阅读顺序

如果你是第一次读，建议按这个顺序：

1. `Main.java`：看运行时怎么组装。
2. `AgentLoop.java`：看 Agent 闭环。
3. `Tool.java`、`ToolRegistry.java`、`ToolResult.java`：看工具抽象。
4. `ReadFileTool.java`、`EditFileTool.java`、`BashTool.java`：看内置工具边界。
5. `PermissionPolicy.java`：看副作用如何被拦住。
6. `Conversation.java`、`JsonlSessionStore.java`、`CompactionArchiveStore.java`：看上下文和会话。
7. `McpServerManager.java`：看外部 MCP 工具如何进入同一套工具系统。
8. `SubagentRunTool.java`、`SubagentLoader.java`：看子 Agent 如何隔离上下文。
9. `src/test/java/...`：看哪些规则已经变成可执行约束。

## 10. 验证命令

常用验证命令：

```powershell
mvn -q test
mvn -q package
git diff
```

Windows PowerShell 下如果涉及中文输出，先启用 UTF-8：

```powershell
chcp 65001 > $null
[Console]::InputEncoding  = [System.Text.UTF8Encoding]::new($false)
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$OutputEncoding           = [System.Text.UTF8Encoding]::new($false)
```

## 11. 扩展建议

新增功能时优先考虑放在哪里：

- 新的模型提供商：实现 `ModelClient`。
- 新的本地能力：实现 `Tool`，在 `BuiltInToolProvider` 注册，并补 `PermissionPolicy`。
- 新的外部能力：走 MCP server 或 simple stdio server。
- 新的运行时约束：优先写测试或 Hook，而不是只写文档。
- 新的长期上下文：优先写成文档、skill 或 archive，再通过工具按需读取。
