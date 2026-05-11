# MVP6 MCP Runtime 管理层

MVP5 解决的是“能不能通过 MCP 调外部工具”。MVP6 解决的是“这些 MCP server 能不能像 runtime 资源一样被管理”。

当前 MVP6 的目标：

```text
MCP client 不再散落在 provider、tool、slash command 里临时 new。
统一交给 McpServerManager 管理连接、缓存、状态和 reload。
```

## 1. 新增核心组件

### McpServerManager

位置：

```text
src/main/java/com/ahi/harness/tools/external/McpServerManager.java
```

职责：

- 识别哪些 external server 是 MCP server。
- 缓存 `McpStdioClient` 和 `McpStreamableHttpClient`。
- 缓存已发现的 MCP tools。
- 统一执行 `tools/call`、`resources/list`、`resources/read`、`prompts/list`、`prompts/get`。
- 记录 server 状态，包括 connected、tools/resources/prompts 数量、last error。
- 支持 reload 单个 server 或所有 MCP server。
- 退出时关闭 stdio 进程。

## 2. 调用链变化

### MVP5

```text
ExternalStdioToolProvider
  -> new McpStreamableHttpClient(...)
  -> tools/list

ExternalStdioTool
  -> new McpStreamableHttpClient(...)
  -> tools/call

SlashCommandHandler
  -> new McpStreamableHttpClient(...)
  -> resources/list
```

每个地方都自己 new client，所以 HTTP session id 和 stdio process 都不容易复用。

### MVP6

```text
Main
  -> new McpServerManager(...)
  -> provider / tool / slash command share the same manager

ExternalStdioToolProvider
  -> mcpManager.loadTools(server)

ExternalStdioTool
  -> mcpManager.callTool(server, tool, args)

SlashCommandHandler
  -> mcpManager.listResources(...)
  -> mcpManager.listPrompts(...)
```

现在 MCP client 被集中管理。

## 3. stdio 长连接

`McpStdioClient` 从“一次请求启动一个进程”改成了“一个 client 持有一个 stdio 进程”。

首次调用：

```text
start process
-> initialize
-> notifications/initialized
-> tools/list
```

后续调用：

```text
reuse same process
-> tools/call / resources/list / prompts/list
```

退出 harness 或 reload 时：

```text
mcpManager.closeAll()
```

会关闭 stdio client 持有的进程。

## 4. HTTP session 复用

`McpStreamableHttpClient` 本来就会保存 `Mcp-Session-Id`。MVP6 通过 manager 复用同一个 client 实例，让后续请求继续带上同一个 session id。

日志里能看到：

```json
{
  "headers": {
    "Mcp-Session-Id": "ca5f...ba12"
  }
}
```

如果 reload 某个 HTTP server，manager 会丢弃旧 client，下次调用重新 initialize，获得新 session id。

## 5. 新增 slash commands

### 查看 MCP 状态

```text
/mcp/status
```

输出示例：

```text
- demo type=stdio protocol=mcp connected=true tools=2 resources=unknown prompts=unknown reloads=1
- demo_http type=streamable_http protocol=mcp connected=true tools=2 resources=1 prompts=unknown reloads=1
```

字段含义：

- `connected`: 最近一次 MCP 调用是否成功。
- `tools`: 已发现的工具数量。
- `resources`: 最近一次 `resources/list` 看到的资源数量。
- `prompts`: 最近一次 `prompts/list` 看到的 prompt 数量。
- `reloads`: 重新发现 tools 的次数。
- `last_error`: 最近一次失败原因。

### reload 单个 MCP server

```text
/mcp/reload demo_http
```

流程：

```text
remove external__demo_http__* tools from registry
-> close cached MCP client
-> initialize
-> tools/list
-> register tools again
```

### reload 全部 MCP server

```text
/mcp/reload all
```

### 全部外部工具 reload

原来的命令仍然保留：

```text
/reload
```

它会 reload MCP server，也会 reload simple stdio server。

## 6. 现在的结构图

```text
Main
  -> HarnessSettings
  -> ToolRegistry
  -> McpServerManager
       -> McpStdioClient
       -> McpStreamableHttpClient
  -> ExternalStdioToolProvider
       -> mcpManager.loadTools(...)
  -> ExternalStdioTool
       -> mcpManager.callTool(...)
  -> SlashCommandHandler
       -> /mcp/status
       -> /mcp/reload
       -> /mcp/resources
       -> /mcp/prompts
```

## 7. 验证方式

启动 HTTP demo server：

```powershell
python external-tools/demo_streamable_http_server.py 8765
```

查看 MCP 状态：

```powershell
java -jar target/agent-harness-0.1.0.jar /mcp/status
```

查看 resources：

```powershell
java -jar target/agent-harness-0.1.0.jar /mcp/resources demo_http
```

reload：

```powershell
java -jar target/agent-harness-0.1.0.jar /mcp/reload demo_http
```

## 8. 还不是生产级的部分

MVP6 已经把 MCP runtime 的骨架立起来了，但仍然保留学习项目的简化：

- stdio 请求读取仍是同步阻塞模型。
- 没有实现 cancellation。
- 没有实现 progress notification。
- 没有实现 server-initiated notifications 的分发。
- 没有实现 roots、sampling、elicitation。
- HTTP session 还没有跨进程持久化。

下一步如果继续做 MVP7，可以开始补这些更完整的协议能力。
