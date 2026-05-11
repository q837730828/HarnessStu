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
- `[OBSERVATION]`: 回灌给模型的工具结果

所有 stage 会以中英双语显示，例如 `[MODEL REQUEST JSON/模型请求体]`、`[PERMISSION/权限]`。

注意：DeepSeek thinking mode 返回的 `reasoning_content` 只打印字符数或占位符，不打印具体内容；harness 会把它保存在会话内存里并在后续请求中回传，这是 provider 协议要求。

会话 JSONL 会写入：

```text
.harness/sessions/
```

