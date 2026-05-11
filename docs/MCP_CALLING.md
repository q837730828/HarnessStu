# MCP 调用原理

这份文档用于理解 HarnessStu 里的 MCP 调用链。它不是完整协议翻译，而是站在“我想亲手写一个 Claude Code 风格 harness”的角度，解释 MCP 在 agent runtime 中到底怎么被发现、注册、调用和回灌。

官方入口：

- MCP specification: https://modelcontextprotocol.io/specification
- Transports: https://modelcontextprotocol.io/specification/2025-11-25/basic/transports
- Lifecycle: https://modelcontextprotocol.io/specification/2025-11-25/basic/lifecycle
- Tools: https://modelcontextprotocol.io/specification/2025-11-25/server/tools
- Resources: https://modelcontextprotocol.io/specification/2025-11-25/server/resources
- Prompts: https://modelcontextprotocol.io/specification/2025-11-25/server/prompts

## 1. MCP 是什么

MCP，全称 Model Context Protocol，可以先理解成：

```text
让 agent 以统一协议连接外部能力的一套 JSON-RPC 协议。
```

在 Claude Code / Codex / 你这个 HarnessStu 这种 runtime 里，模型本身不直接执行本地命令，也不直接访问外部服务。中间一定有一个 harness 负责：

```text
model 想调用工具
-> harness 判断权限
-> harness 调用本地工具或外部 MCP server
-> MCP server 返回结果
-> harness 把 observation 回灌给 model
```

MCP 解决的是“外部能力怎么统一暴露给 harness”的问题。

## 2. 核心角色

MCP 里常见三个角色：

```text
Host
  用户真正使用的应用，比如 Claude Desktop、Claude Code、HarnessStu。

Client
  Host 内部负责连接某个 MCP server 的协议客户端。
  在本项目里可以对应 McpStdioClient、McpStreamableHttpClient。

Server
  外部能力提供者，比如文件系统服务、GitHub 服务、数据库服务、自定义业务服务。
  在本项目里可以对应 external-tools/demo_tool_server.py 和 demo_streamable_http_server.py。
```

在 HarnessStu 里，关系大概是：

```text
Main
  -> ToolProvider
  -> ExternalStdioToolProvider
  -> McpStdioClient / McpStreamableHttpClient
  -> external MCP server
```

## 3. MCP 底层消息格式

MCP 使用 JSON-RPC 2.0。一次普通请求长这样：

```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "method": "tools/list",
  "params": {}
}
```

响应长这样：

```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "result": {
    "tools": []
  }
}
```

如果失败，响应里会有 `error`：

```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "error": {
    "code": -32601,
    "message": "Method not found"
  }
}
```

通知 notification 没有 `id`，表示不需要响应。例如初始化完成通知：

```json
{
  "jsonrpc": "2.0",
  "method": "notifications/initialized",
  "params": {}
}
```

## 4. 标准调用生命周期

一个 MCP client 连接 server 后，通常不是直接 `tools/call`，而是先走初始化。

最小流程：

```text
1. client -> server: initialize
2. server -> client: initialize result
3. client -> server: notifications/initialized
4. client -> server: tools/list / resources/list / prompts/list
5. client -> server: tools/call / resources/read / prompts/get
```

初始化请求：

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "initialize",
  "params": {
    "protocolVersion": "2025-11-25",
    "capabilities": {},
    "clientInfo": {
      "name": "HarnessStu",
      "version": "0.1.0"
    }
  }
}
```

初始化响应：

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "protocolVersion": "2025-11-25",
    "capabilities": {
      "tools": {},
      "resources": {},
      "prompts": {}
    },
    "serverInfo": {
      "name": "demo-server",
      "version": "0.1.0"
    }
  }
}
```

这里的核心是能力协商：

- `protocolVersion`: 双方确认协议版本。
- `clientInfo`: client 告诉 server 自己是谁。
- `serverInfo`: server 告诉 client 自己是谁。
- `capabilities`: 双方声明自己支持哪些能力。

## 5. Transport：消息怎么传

MCP 的消息体是 JSON-RPC，但 JSON-RPC 可以通过不同 transport 传输。你当前项目重点支持两种：

```text
stdio
streamable_http
```

### 5.1 stdio

stdio 是最适合本地工具的方式。

Harness 启动一个子进程：

```text
python external-tools/demo_tool_server.py
```

然后通过进程标准输入输出通信：

```text
harness 写入一行 JSON-RPC 到 stdin
server 从 stdin 读取
server 执行
server 写回一行 JSON-RPC 到 stdout
harness 从 stdout 读取
```

在本项目中：

- 客户端类：`McpStdioClient`
- 示例 server：`external-tools/demo_tool_server.py`
- 配置项：`type: "stdio"`，`protocol: "mcp"`

配置示例：

```json
{
  "name": "demo",
  "type": "stdio",
  "protocol": "mcp",
  "command": "python",
  "args": ["external-tools/demo_tool_server.py"]
}
```

优点：

- 简单，适合本机命令行工具。
- 不需要 HTTP 服务端口。
- 很适合学习 agent harness 的最小闭环。

当前 HarnessStu 为了简单，每次发现或调用都会新开一次 stdio session。真实生产版本通常会维持长连接，避免每次都重新 initialize。

### 5.2 Streamable HTTP

Streamable HTTP 适合远程服务或常驻本地服务。

Harness 向 MCP server URL 发 POST 请求：

```http
POST /mcp
Content-Type: application/json
Accept: application/json, text/event-stream
```

请求体还是 JSON-RPC：

```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "method": "tools/list",
  "params": {}
}
```

server 可以返回普通 JSON：

```http
Content-Type: application/json
```

也可以返回 SSE：

```http
Content-Type: text/event-stream

data: {"jsonrpc":"2.0","id":2,"result":{}}
```

在本项目中：

- 客户端类：`McpStreamableHttpClient`
- 示例 server：`external-tools/demo_streamable_http_server.py`
- 配置项：`type: "streamable_http"`

配置示例：

```json
{
  "name": "demo_http",
  "type": "streamable_http",
  "url": "http://127.0.0.1:8765/mcp"
}
```

启动 demo server：

```powershell
python external-tools/demo_streamable_http_server.py 8765
```

HTTP transport 还会用到 `Mcp-Session-Id`。server 初始化后可以返回一个 session id，后续请求带上它：

```http
Mcp-Session-Id: xxxx
```

HarnessStu 的日志里会脱敏显示：

```text
"Mcp-Session-Id": "ca5f...ba12"
```

## 6. Tools：模型可调用的动作

Tools 是 MCP 里最接近“函数调用”的能力。

发现工具：

```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "method": "tools/list",
  "params": {}
}
```

返回：

```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "result": {
    "tools": [
      {
        "name": "time_now",
        "description": "Return the current local timestamp.",
        "inputSchema": {
          "type": "object",
          "properties": {},
          "required": []
        }
      }
    ]
  }
}
```

调用工具：

```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "method": "tools/call",
  "params": {
    "name": "time_now",
    "arguments": {}
  }
}
```

返回：

```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "result": {
    "content": [
      {
        "type": "text",
        "text": "2026-05-11T11:05:17"
      }
    ]
  }
}
```

在 HarnessStu 中，外部工具会被包装成内部工具名：

```text
external__<server_name>__<tool_name>
```

例如：

```text
external__demo_http__time_now
```

这个名字是给模型看的。模型发起 tool call 时，调用的是 `external__demo_http__time_now`。Harness 内部再把它拆回：

```text
server = demo_http
tool = time_now
```

然后走 MCP 的 `tools/call`。

## 7. Resources：可读取的上下文材料

Resources 可以理解成“server 暴露出来的可读资料”。它不一定是工具动作，更像是文件、文档、数据库记录、页面内容等。

列出资源：

```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "method": "resources/list",
  "params": {}
}
```

读取资源：

```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "method": "resources/read",
  "params": {
    "uri": "demo-http://about"
  }
}
```

HarnessStu 提供了 slash command：

```text
/mcp/resources demo_http
/mcp/read demo_http demo-http://about
```

这类命令不会发给模型，而是 harness 直接调用 MCP server，方便你学习协议交互。

## 8. Prompts：server 提供的提示模板

Prompts 可以理解成“server 提供的 prompt 模板”。例如某个 GitHub MCP server 可以提供“总结 issue”的 prompt，数据库 MCP server 可以提供“解释 schema”的 prompt。

列出 prompts：

```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "method": "prompts/list",
  "params": {}
}
```

获取某个 prompt：

```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "method": "prompts/get",
  "params": {
    "name": "explain_demo",
    "arguments": {
      "topic": "mcp"
    }
  }
}
```

HarnessStu 提供：

```text
/mcp/prompts demo_http
/mcp/get-prompt demo_http explain_demo {"topic":"mcp"}
```

## 9. HarnessStu 里的完整调用链

以 `external__demo_http__time_now` 为例。

### 9.1 启动时发现工具

启动 harness：

```text
Main
  -> load settings.example.json or .harness/settings.json
  -> build BuiltInToolProvider
  -> build ExternalStdioToolProvider
```

然后外部 provider 会扫描配置：

```json
{
  "name": "demo_http",
  "type": "streamable_http",
  "url": "http://127.0.0.1:8765/mcp"
}
```

发现它是 streamable HTTP，于是：

```text
ExternalStdioToolProvider
  -> new McpStreamableHttpClient(...)
  -> initialize
  -> notifications/initialized
  -> tools/list
  -> 把 time_now 包装成 external__demo_http__time_now
  -> 注册到 ToolRegistry
```

### 9.2 模型看到工具

Harness 给模型的 tool schema 里会出现：

```text
external__demo_http__time_now
```

模型不需要知道背后是 HTTP、stdio、Python 还是数据库。模型只需要按 schema 发起 tool call。

### 9.3 模型发起 tool call

模型响应里出现：

```json
{
  "tool_calls": [
    {
      "function": {
        "name": "external__demo_http__time_now",
        "arguments": "{}"
      }
    }
  ]
}
```

AgentLoop 收到后：

```text
AgentLoop
  -> ToolRegistry 找到工具
  -> PermissionPolicy 检查 external_tool_allowlist
  -> ExternalStdioTool.execute(...)
  -> McpStreamableHttpClient.callTool("time_now", {})
```

### 9.4 Harness 调 MCP server

实际 MCP 请求：

```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "method": "tools/call",
  "params": {
    "name": "time_now",
    "arguments": {}
  }
}
```

MCP server 返回：

```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "result": {
    "content": [
      {
        "type": "text",
        "text": "..."
      }
    ]
  }
}
```

### 9.5 结果回灌给模型

Harness 把 MCP 结果转换成 observation，再回灌给模型：

```text
tool_call_id -> observation content -> next model request
```

模型看到 observation 后，继续生成自然语言答案。

这就是 agent 闭环：

```text
model asks for tool
-> harness executes MCP call
-> server returns result
-> harness gives result back to model
-> model answers
```

## 10. 日志怎么看

现在 HarnessStu 的 MCP 日志已经改成“请求体 / 响应体”格式。

HTTP 请求日志：

```text
[MCP HTTP REQUEST/HTTP请求体] tools/list 请求体 / Request Body
```

内部结构：

```json
{
  "transport": "streamable_http",
  "server": "demo_http",
  "http": {
    "method": "POST",
    "url": "http://127.0.0.1:8765/mcp"
  },
  "headers": {
    "Content-Type": "application/json",
    "Accept": "application/json, text/event-stream",
    "Mcp-Session-Id": "ca5f...ba12"
  },
  "body": {
    "jsonrpc": "2.0",
    "id": 2,
    "method": "tools/list",
    "params": {}
  }
}
```

HTTP 响应日志：

```text
[MCP HTTP RESPONSE/HTTP响应体] tools/list 响应体 / Response Body
```

内部结构：

```json
{
  "transport": "streamable_http",
  "server": "demo_http",
  "sse": false,
  "http": {
    "status": 200
  },
  "headers": {
    "Content-Type": "application/json",
    "Mcp-Session-Id": "ca5f...ba12"
  },
  "body": {
    "jsonrpc": "2.0",
    "id": 2,
    "result": {}
  }
}
```

stdio 请求日志：

```text
[MCP STDIO REQUEST/STDIO请求体] tools/list 请求体 / Request Body
```

stdio 响应日志：

```text
[MCP STDIO RESPONSE/STDIO响应体] tools/list 响应体 / Response Body
```

看日志时，优先看三件事：

```text
method: 当前在调 MCP 的哪一步
params: 本次调用的输入
result/error: server 返回了什么
```

## 11. 为什么要有 allowlist

外部 MCP server 可能暴露很多工具，有些工具可能会读文件、写文件、访问网络、删数据。模型不应该自动拥有全部权限。

HarnessStu 通过 `external_tool_allowlist` 做最小权限控制：

```json
{
  "external_tool_allowlist": [
    "external__demo__time_now",
    "external__demo__echo",
    "external__demo_http__time_now",
    "external__demo_http__echo"
  ]
}
```

流程是：

```text
MCP server 暴露 tool
-> harness 发现 tool
-> tool 被注册成 external__server__tool
-> 模型请求调用
-> PermissionPolicy 检查 allowlist
-> 允许后才真正 tools/call
```

这也是 Claude Code / Codex 这类工具必须有权限层的原因：模型负责提出动作，harness 负责判断动作能不能执行。

## 12. 你可以如何手动观察 MCP

### 12.1 观察 HTTP resources

启动 HTTP demo server：

```powershell
python external-tools/demo_streamable_http_server.py 8765
```

另一个窗口运行：

```powershell
java -jar target/agent-harness-0.1.0.jar /mcp/resources demo_http
```

你会看到：

```text
initialize
notifications/initialized
resources/list
```

### 12.2 观察 HTTP tool call

进入交互模式：

```powershell
java -jar target/agent-harness-0.1.0.jar
```

问：

```text
调用 external__demo_http__time_now 看看当前时间
```

你应该能看到：

```text
initialize
notifications/initialized
tools/call
observation
assistant final answer
```

### 12.3 观察 stdio tools

运行：

```powershell
java -jar target/agent-harness-0.1.0.jar /tools
```

启动时会发现 stdio MCP server，并打印：

```text
initialize
notifications/initialized
tools/list
```

## 13. 当前实现和生产级 MCP 的差距

HarnessStu 当前是学习型 MVP，实现重点是让调用链可见，而不是覆盖全部协议细节。

已支持：

- MCP-style stdio JSON-RPC
- Streamable HTTP POST JSON-RPC
- `initialize`
- `notifications/initialized`
- `tools/list`
- `tools/call`
- `resources/list`
- `resources/read`
- `prompts/list`
- `prompts/get`
- HTTP `application/json`
- 基础 HTTP `text/event-stream`
- MCP 请求/响应体日志
- 外部工具 allowlist

还可以继续增强：

- stdio 长连接复用，而不是每次启动新进程。
- Streamable HTTP session 复用和恢复。
- server-initiated notifications。
- progress notification。
- cancellation。
- roots。
- sampling。
- elicitation。
- 更完整的错误码处理。
- 更严格的 capability 校验。

## 14. 一句话总结

MCP 调用不是模型直接访问外部世界，而是：

```text
模型提出 tool call
-> harness 通过权限层接住
-> harness 用 MCP client 调外部 MCP server
-> MCP server 返回 JSON-RPC result
-> harness 把 result 作为 observation 回灌给模型
```

真正值得你学习的不是某个单独请求，而是这条边界：

```text
model decision boundary
  vs
harness execution boundary
```

MCP 正好站在这条边界上，把“外部能力”用统一协议交给 harness 管理。
