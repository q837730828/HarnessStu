# Agent Harness MVP

> 🎉 欢迎来到 Agent Harness MVP！这是一个探索 AI agent 工具调用机制的学习项目。

一个用 Java 写的极小 Claude Code 风格闭环，用来理解 agent harness 的核心结构：

```text
用户输入 -> 模型决定 tool call -> harness 执行工具 -> observation 回灌给模型 -> 继续循环 -> final answer
```

## 运行

Windows PowerShell 先启用 UTF-8：

```powershell
chcp 65001 > $null
[Console]::InputEncoding  = [System.Text.UTF8Encoding]::new($false)
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$OutputEncoding           = [System.Text.UTF8Encoding]::new($false)
```

配置 DeepSeek API Key：

```powershell
$env:DEEPSEEK_API_KEY="你的 key"
```

可选配置：

```powershell
$env:DEEPSEEK_MODEL="deepseek-v4-flash"
$env:DEEPSEEK_BASE_URL="https://api.deepseek.com"
```

也可以创建 `.harness/settings.json` 配置运行时。参考 `settings.example.json`。

构建并运行：

```powershell
mvn package
java -jar target/agent-harness-0.1.0.jar
```

也可以直接传入一次性任务：

```powershell
java -jar target/agent-harness-0.1.0.jar "看看这个项目里有哪些文件"
```

## 已实现工具

- `list_files`: 列出工作区文件
- `read_file`: 读取工作区内文件
- `grep`: 在工作区内搜索文本
- `edit_file`: 用精确 `old_text -> new_text` 替换修改文件，写入前打印 diff，并在 `.harness/backups/` 保存备份
- `bash`: 执行允许列表中的验证命令，例如 `git diff`、`mvn test`、`mvn package`

`edit_file` 是 MVP2 的核心工具。它刻意不支持模型随意覆盖整文件，而是要求模型先 `read_file`，再提交一个当前文件中能唯一匹配的 `old_text` 块。这样更容易观察和理解 coding agent 的安全编辑流程。

`bash` 是 MVP3 的核心工具。它用于验证编辑结果，会把 `command`、`exit_code`、`elapsed_ms` 和命令输出回灌给模型。权限层只允许单条 allowlist 命令，并阻止 `;`、管道、重定向、`&&`、`||` 等 shell 组合写法。

## MVP4 runtime

MVP4 增加了工程化运行时能力：

- `settings.json`: 从 `.harness/settings.json` 加载模型、步数、压缩阈值、bash allowlist/blocklist 等配置
- hooks: 打印 `UserPromptSubmit`、`PreModelCall`、`PostModelCall`、`PreToolUse`、`PostToolUse`、`PreCompact`、`PostCompact`、`Stop`、`SessionEnd`
- project memory: 启动时自动读取 `HARNESS.md`、`CLAUDE.md`、`AGENTS.md` 并注入 system prompt
- slash commands: 交互模式支持 `/help`、`/tools`、`/status`、`/diff`、`/sessions`、`/resume latest`、`/resume <session-file>`、`/compact`
- session resume: 新会话日志会保存结构化 message，可用 `/resume` 恢复
- compaction: 消息超过阈值后，把旧上下文压缩成本地摘要，并保留最近消息

## MVP5 external tools

MVP5 增加了外部工具生态的最小实现：

- `ToolProvider`: 工具来源抽象，内置工具和外部工具都通过 provider 注册
- `ExternalStdioToolProvider`: 从外部 stdio 服务发现工具
- `ExternalStdioTool`: 把外部工具包装成 harness 内部 `Tool`
- `protocol: "simple"`: 兼容本项目早期的一行 JSON 协议
- `protocol: "mcp"`: 支持 MCP-style JSON-RPC 的 `initialize`、`notifications/initialized`、`tools/list`、`tools/call`
- `type: "streamable_http"`: 支持 MCP Streamable HTTP 的 POST JSON-RPC 请求，兼容 `application/json` 和基础 `text/event-stream`
- 外部工具命名规则：`external__<provider>__<tool>`
- 外部工具权限：必须出现在 `external_tool_allowlist`
- 示例服务：`external-tools/demo_tool_server.py`

外部 stdio 服务支持两种协议。

Simple 协议使用一行 JSON 请求/响应。

发现工具：

```json
{"type":"list_tools"}
```

调用工具：

```json
{"type":"call_tool","name":"time_now","arguments":{}}
```

示例 `.harness/settings.json`：

```json
{
  "external_tools": [
    {
      "name": "demo",
      "type": "stdio",
      "protocol": "mcp",
      "command": "python",
      "args": ["external-tools/demo_tool_server.py"]
    },
    {
      "name": "demo_http",
      "type": "streamable_http",
      "url": "http://127.0.0.1:8765/mcp"
    }
  ],
  "external_tool_allowlist": [
    "external__demo__time_now",
    "external__demo__echo",
    "external__demo_http__time_now",
    "external__demo_http__echo"
  ]
}
```

MCP-style 协议使用 JSON-RPC 2.0。harness 会依次发送：

```text
initialize
notifications/initialized
tools/list 或 tools/call
```

Streamable HTTP demo server:

```powershell
python external-tools/demo_streamable_http_server.py 8765
```

交互模式还可以直接查看 MCP resources 和 prompts：

```text
/mcp/resources [server]
/mcp/read <server> <uri>
/mcp/prompts [server]
/mcp/get-prompt <server> <name> [json-args]
```

## 日志怎么看

程序会打印这些阶段：

- `[HARNESS]`: harness 自身状态
- `[MODEL REQUEST JSON]`: 脱敏后的 HTTP 请求体 JSON 结构
- `[MODEL HTTP]`: HTTP 请求状态
- `[MODEL RESPONSE JSON]`: 脱敏后的 HTTP 响应体 JSON 结构
- `[HOOK]`: hooks 生命周期事件
- `[ASSISTANT]`: 模型自然语言输出
- `[TOOL]`: 工具调用和结果
- `[PERMISSION]`: 权限判断
- `[DIFF]`: `edit_file` 写入前的 diff
- `[VALIDATION]`: `bash` 验证命令的执行状态
- `[EXTERNAL]`: 外部工具发现和调用状态
- `[OBSERVATION]`: 回灌给模型的工具结果

所有 stage 会以中英双语显示，例如 `[MODEL REQUEST JSON/模型请求体]`、`[PERMISSION/权限]`。

注意：DeepSeek thinking mode 返回的 `reasoning_content` 只打印字符数或占位符，不打印具体内容；harness 会把它保存在会话内存里并在后续请求中回传，这是 provider 协议要求。

会话 JSONL 会写入：

```text
.harness/sessions/
```

## MVP6 MCP runtime manager

MVP6 promotes MCP from one-shot client calls to a small runtime layer:

- `McpServerManager`: owns MCP client cache, tool cache, status, reload, and shutdown.
- stdio MCP clients reuse the same child process across multiple MCP requests.
- Streamable HTTP MCP clients reuse the same client instance and `Mcp-Session-Id`.
- `/mcp/status`: show configured MCP servers, connection state, tool/resource/prompt counts, and last error.
- `/mcp/reload <server|all>`: close cached clients, rediscover tools, and re-register external tools.

See `docs/MCP_RUNTIME.md` for the MVP6 walkthrough.
