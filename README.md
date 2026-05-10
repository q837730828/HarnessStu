# Agent Harness MVP

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
- `bash`: 执行允许列表中的安全命令

`edit_file` 是 MVP2 的核心工具。它刻意不支持模型随意覆盖整文件，而是要求模型先 `read_file`，再提交一个当前文件中能唯一匹配的 `old_text` 块。这样更容易观察和理解 coding agent 的安全编辑流程。

## 日志怎么看

程序会打印这些阶段：

- `[HARNESS]`: harness 自身状态
- `[MODEL INPUT]`: 本轮真正发给模型的 messages 和 tools
- `[MODEL REQUEST JSON]`: 脱敏后的 HTTP 请求体 JSON 结构
- `[MODEL HTTP]`: HTTP 请求状态
- `[MODEL RESPONSE JSON]`: 脱敏后的 HTTP 响应体 JSON 结构
- `[MODEL OUTPUT]`: 模型返回内容、token usage、tool calls
- `[ASSISTANT]`: 模型自然语言输出
- `[TOOL]`: 工具调用和结果
- `[PERMISSION]`: 权限判断
- `[DIFF]`: `edit_file` 写入前的 diff
- `[OBSERVATION]`: 回灌给模型的工具结果

所有 stage 会以中英双语显示，例如 `[MODEL INPUT/模型输入]`、`[PERMISSION/权限]`。

注意：DeepSeek thinking mode 返回的 `reasoning_content` 只打印字符数或占位符，不打印具体内容；harness 会把它保存在会话内存里并在后续请求中回传，这是 provider 协议要求。

会话 JSONL 会写入：

```text
.harness/sessions/
```

这个 MVP 的重点不是功能完整，而是把 Claude Code 类工具的"心跳"露出来。

你好，欢迎阅读这份 README！祝编码愉快 🎉“心跳”露出来。

你好，AI Coding Agent！祝你编码愉快！🚀
