# Agent Protocol 显式化实现方案

## 1. 背景与目标

当前 Harness 已经具备模型调用、工具执行、权限、会话日志、压缩归档、
MCP 和 Trace，但一次执行仍主要表现为 `AgentLoop.run(...)` 方法与循环变量。
本文方案把跨框架稳定出现的协议语义变成可阅读、可持久化的 Java 对象：

`Agent / Thread / Message / Part / Run / Step / Event / Artifact / Checkpoint /
Interrupt / Trace / Error`。

目标不是引入新的 Agent 框架，而是在现有小型 Harness 周围建立一层明确的
Runtime Protocol 领域模型，让学习者能够从代码和磁盘记录中看到任务生命周期。

## 2. 范围边界

### 2.1 本次实现

- 为 Agent、Thread、Message、Part、Run、Step、Event、Artifact、Checkpoint、Interrupt、Trace、Error
  建立显式模型和有限状态。
- 每次用户调用创建独立 Run；模型调用、权限检查和工具调用创建 Step。
- 把状态变化写成统一 Event，并为对象生成稳定 ID 和时间戳。
- 在每个稳定步骤后保存包含完整消息快照的 Checkpoint。
- 把需要审批表达为 `INPUT_REQUIRED` Run 状态和 Interrupt 对象；同步 CLI
  审批完成后再回到 `RUNNING`。
- 把最终回答和归档后的大型工具结果登记为 Artifact。
- 用 `traceId/spanId` 直接打通 Event、Step 和 Checkpoint，不要求消费者猜测关联。
- 提供 Run 查询、事件游标补读、协作式取消、超时和 Checkpoint 恢复入口。
- 使用 `.harness/runtime/` 持久化协议对象，保留现有 Session、Trace 和
  Compaction 各自职责。

### 2.2 本次不实现

- REST、SSE、WebSocket 或 gRPC 服务端。
- 多 Run 并发调度、队列、强制中断执行器和跨进程 Worker。
- 自动恢复外部工具副作用或正在执行的进程。
- OpenTelemetry SDK、指标平台和自动评测平台。
- 用图执行引擎替换当前代码式 Agent Loop。

这些能力需要更大的部署边界；本次先保证协议语义和持久化结构清晰。

## 3. 当前代码现状

- `Conversation` 是模型可见消息列表，不是完整 Thread 资源。
- 一次 `AgentLoop.run` 等价于隐式 Run，循环下标等价于隐式 Step。
- `HookBus` 和 `TraceStore` 有事件，但没有统一 Event ID、Run ID 和 Step ID。
- Session 能重放消息，Compaction 能找回旧上下文，但二者都不是执行 Checkpoint。
- 权限 ASK 是同步阻塞动作，没有显式 Interrupt 状态。
- 工作区文件和归档文件存在，但没有 Artifact 索引。

## 4. 设计决策

1. **协议对象与执行实现分离**：协议对象放在 `protocol` 包；执行循环仍在
   `core` 包；磁盘实现放在 `runtime` 包。
2. **保持现有 API 兼容**：保留 `AgentLoop.run(Conversation, String)`，增加带
   `AgentIdentity` 和 `AgentThread` 的显式入口。
3. **状态迁移集中在模型方法内**：Run、Step、Interrupt 不暴露任意 setter，
   使用 `start/complete/fail/requireInput/resume` 表达允许的变化。
4. **事件追加、资源快照覆盖**：Event、Step、Artifact、Interrupt 使用 JSONL
   追加；Thread 和 Run 使用 JSON 快照保存最新状态。
5. **Checkpoint 保存可恢复数据**：Checkpoint 元数据与完整 Message 快照一起
   保存，和仅用于裁剪上下文的 Compaction 明确区分。
6. **Error-as-Data 优先**：规范化工具失败继续作为 observation；未处理的系统
   Exception 会把 Step/Run 标记为 FAILED 后继续向调用者抛出。
7. **控制面与执行面分离**：`RuntimeStore` 定义查询、事件游标、取消请求和
   Checkpoint 加载；`AgentLoop` 只在安全边界执行这些控制语义。
8. **恢复创建新 Run**：加载 Checkpoint 不会篡改旧 Run；下一次提示创建新 Run，
   并写入 `parentRunId/resumedFromCheckpointId`。每个新 Checkpoint 还记录
   `parentCheckpointId`，形成可审计、可分叉的版本链。

## 5. 数据与目录结构

```text
.harness/runtime/
  agents/<agent-id>.json
  threads/<thread-id>.json
  runs/<run-id>/run.json
  runs/<run-id>/steps.jsonl
  runs/<run-id>/events.jsonl
  runs/<run-id>/messages.jsonl
  runs/<run-id>/artifacts.jsonl
  runs/<run-id>/interrupts.jsonl
  runs/<run-id>/spans.jsonl
  runs/<run-id>/errors.jsonl
  runs/<run-id>/cancel-request.json
  runs/<run-id>/checkpoints/<checkpoint-id>.json
```

每个事件都包含 `event_id / thread_id / run_id / step_id / trace_id / span_id /
sequence / channel / type / ts`，
消费者即使暂时只是本地文件，也能按同一语义恢复顺序和因果关系。
当前模型客户端一次返回完整文本，因此会产生一个完整内容的 `TEXT_DELTA`；未来改成
流式客户端时，同一事件类型可按 chunk 多次发出。消息、工具调用、工具结果、状态、
Artifact 和错误仍位于同一有序流中。

Run 状态名与原文示意图是语义映射而非字符串照抄：`CREATED ≈ SUBMITTED`、
`RUNNING ≈ WORKING`；`INPUT_REQUIRED`、`AUTH_REQUIRED`、`COMPLETED`、`FAILED`、
`CANCELLED` 保持直接含义，并额外定义 `TIMED_OUT`。

## 6. 核心流程

```text
创建 Run
  -> RUN_CREATED / RUN_STARTED
  -> 创建 MODEL_CALL Step
  -> 模型返回 ToolCall
  -> 创建 GUARDRAIL Step
  -> 若需审批：Run=INPUT_REQUIRED，保存 Interrupt + Checkpoint
  -> 审批完成：Interrupt resolved，Run=RUNNING
  -> 创建 TOOL_CALL Step
  -> observation 回灌 Conversation
  -> 保存 Checkpoint
  -> 无更多 ToolCall：保存 FINAL_RESPONSE Artifact，Run=COMPLETED
```

控制面命令：

```text
/runs
/run <run-id>
/run/events <run-id> [after-sequence]
/run/cancel <run-id> [reason]
/checkpoint/load <run-id> <checkpoint-id>
```

`/run/cancel` 写入持久化请求。执行中的 Loop 会在模型调用前后以及每个工具调用前
检查请求；已经进入的第三方调用不会被线程级强杀。`run_timeout_seconds` 使用同一组
安全边界，`0` 表示不设置墙钟超时。

## 7. 兼容性与风险

- 旧 Session JSONL 格式保持不变；Runtime Store 是新增旁路，不替换 Session。
- 子代理使用同一协议模型，但使用独立 Thread 和 Run。
- Checkpoint 能恢复消息状态，但尚不能保证外部副作用幂等，因此文档与模型中
  明确记录恢复边界。
- JSON 文件是教学型存储，没有进程间写锁；跨进程控制适合单 Writer + 多 Reader/Controller，
  不承诺多个执行 Worker 同时追加同一个 Run。

## 8. 验收标准

- 原有测试通过，Java 8 编译和打包通过。
- 一次无工具调用产生 Agent、Thread、Message/Part、Run、Step、Event、Artifact、
  Checkpoint、TraceSpan。
- 一次工具调用能够关联 guardrail/tool Step 和 tool call ID。
- 需要审批时能够观察到 INPUT_REQUIRED 和已解决/拒绝的 Interrupt。
- 系统异常会持久化 FAILED Step、FAILED Run、错误 Event 和 ProtocolError。
- Event/Checkpoint 可直接关联 TraceSpan；事件支持 `afterSequence` 补读。
- Run 可查询、协作式取消、超时，并能从 Checkpoint 创建带血缘的新 Run。
- 协议对象状态迁移与 Runtime Store 有单元测试。

## 9. 实施顺序

1. 新增协议模型、枚举、ID 工具和 Runtime Store 接口。
2. 新增 JSON 文件存储与 Noop 实现。
3. 扩展 ObservationArchiveStore 返回结构化归档信息。
4. 接入 AgentLoop，并在 Main 与 Subagent 中显式创建 Agent/Thread。
5. 添加测试，更新架构说明和项目学习索引。
6. 执行 `mvn -q test`、`mvn -q package`、`git diff`。
