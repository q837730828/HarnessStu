# Agent Protocol 原文对照审计

## 1. 审计基线

审计对象是文章《相比层出不穷的 Agent 框架，不变的 Agent Protocol 是什么》
第 10、11 章总结出的协议对象、九条设计原则和 Runtime 能力。判断标准不是
“是否存在同名 Java 类”，而是对象是否具备稳定 ID、状态、持久化和可操作入口。

状态标记：

- **对齐**：当前 Harness 已具备文章要求的核心语义。
- **部分对齐**：对象存在，但缺少文章强调的操作或边界。
- **部署绑定**：语义已抽象，具体网络/分布式实现不属于本地 CLI Runtime。

## 2. 九条原则审计

| 原文原则 | 审计结果 | 证据与偏差 | 本轮处理 |
|---|---|---|---|
| Task/Run 一等化 | 对齐 | Run 有稳定 ID、状态机、预算、超时、查询、取消与恢复血缘 | RuntimeStore + CLI 控制面；取消采用明确的协作式语义 |
| Thread/Context 一等化 | 对齐 | Thread 独立于消息窗口，并持久化参与者、能力和元数据 | participants/capabilities 已显式化 |
| Step 一等化 | 对齐 | Model、Tool、Guardrail、Subagent、Compaction 都是 Step | 保持 |
| Event Stream 标准化 | 对齐（本地绑定） | 文本、消息、状态、工具、Artifact、错误共用 sequence/channel/trace/span 事件流，支持游标补读 | 非流式模型把完整响应记作单个 TEXT_DELTA；SSE 可由未来 Adapter 映射 |
| Artifact 一等化 | 对齐 | 最终回答、大 observation、压缩归档均有 Artifact | 保持 |
| Interrupt 是状态 | 对齐（同步绑定） | INPUT_REQUIRED、AUTH_REQUIRED、Interrupt、Checkpoint、resolution 均持久化 | CLI 仍同步等待；网络异步属于部署绑定 |
| 发现与能力声明分离 | 对齐 | AgentIdentity 独立表达 metadata/capabilities/tools | Thread 同步能力声明 |
| 协议绑定可替换 | 对齐 | AgentLoop 依赖 RuntimeStore 接口；JSONL 只是当前 Adapter | REST/SSE/数据库可在不修改 Loop/领域对象时替换 |
| 观测语义内建 | 对齐 | TraceSpan、ProtocolError、Event、Checkpoint 共享 trace/span/run/step 关联 | 事件可定位 Span，Checkpoint 直接记录 Span |

## 3. 协议对象审计

| 原文对象 | 当前状态 | 结论 |
|---|---|---|
| Agent Card / Metadata | `AgentIdentity` | 对齐 |
| Thread / Context | `AgentThread` + `Conversation` | 补参与者和能力后对齐 |
| Message / Part | `AgentMessage` + `MessagePart` | 对齐；与 provider-facing `core.Message` 分层 |
| Task / Run | `AgentRun` | 对齐本地 Runtime；支持查询、取消、超时、预算和恢复血缘 |
| Step / Run Step | `RunStep` + `TraceSpan` | 对齐 |
| Event Stream | `RuntimeEvent` JSONL | 对齐本地可恢复流；SSE 为部署绑定 |
| Interrupt / Input Required | `Interrupt` + Run 状态 | 对齐同步 CLI 语义 |
| Artifact | `Artifact` | 对齐 |
| Checkpoint / State | per-step 完整消息快照 + parentCheckpointId | 对齐消息级恢复、历史读取和分叉版本链；外部副作用回滚不作虚假承诺 |
| Todo / Plan | 结构化 Todo 工具和独立状态 | 对齐当前本地 Runtime |
| Workspace / Backend | 路径边界、工具权限和归档 | 对齐当前本地 Runtime |
| Skill | 动态 `skill_load` 与子代理复用 | 对齐当前本地 Runtime |
| Trace / Span | `TraceSpan` + 直接 correlation IDs | 对齐；不强依赖 OpenTelemetry SDK |
| Error | `ToolResult` + `ProtocolError` | 对齐 Error-as-Data 和结构化审计两层语义 |

## 4. Runtime 八维审计

1. **执行模型**：代码式 Runtime，Loop/Step 已显式化；符合文章允许内部实现自由的边界。
2. **状态管理**：自动 per-step Checkpoint；Session、Compaction、Checkpoint 职责已分开。
3. **中断恢复**：审批中断状态完整；当前 CLI 同步恢复，不冒充分布式暂停 Worker。
4. **错误恢复**：工具错误默认作为数据；系统错误失败 Run；尚无自动副作用回滚。
5. **工具协议**：统一 Tool API + JSON Schema + MCP Adapter，对齐。
6. **流式输出**：本地持久化事件流支持游标补读。SSE/Last-Event-ID 是未来传输 Adapter。
7. **多 Agent**：父级 `SUBAGENT_TASK` + 子 Agent 独立 Thread/Run，符合层级委派模式。
8. **可观测/可评测**：TraceSpan、ProtocolError、Event、Checkpoint 已打通；成本与质量评测仍需要模型 usage 数据和独立评测系统。

## 5. 不应强行实现的内容

- Redis Stream、SSE、WebSocket 属于部署绑定，不应让本地 CLI 依赖 Redis 或 Web 框架。
- 图执行引擎不是 Protocol 要求；文章明确 Protocol 不规定 Graph、Actor 或 Code Loop。
- Checkpoint 不能自动撤销已经发生的外部副作用；没有幂等键/补偿事务时宣称可回滚会造成更大偏差。
- 多 Agent 群聊、投票、发布订阅不是统一标准；当前层级 Subagent 是有意选择的协作模式。

## 6. 本轮追加验收

- 每条 user/assistant/tool 消息都生成 provider-neutral `AgentMessage` 与 `MessagePart`。
- Thread JSON 包含 participant 和 capability。
- 每个 Step 生成带 `trace_id/span_id/parent_span_id` 的 TraceSpan。
- Step/Run 失败生成结构化 ProtocolError。
- RuntimeStore 支持列出/读取 Run、按 sequence 补读 Event、请求取消、加载 Checkpoint。
- CLI 提供 `/runs`、`/run`、`/run/events`、`/run/cancel`、`/checkpoint/load`。
- AgentLoop 在模型调用和工具调用边界检查协作式取消。
- `run_timeout_seconds` 形成持久化 deadline 和 `TIMED_OUT` 终态。
- Checkpoint 恢复后创建新 Run，并记录 `parentRunId/resumedFromCheckpointId`。
- Checkpoint 记录 `parentCheckpointId`，同一 Run 和跨 Run 恢复都形成显式版本链。
- Event 和 Checkpoint 直接携带 `traceId/spanId`，不再依赖隐式推断。
- 非流式模型输出作为单个 `TEXT_DELTA`，工具请求/结果与消息也进入同一事件流。

## 7. 二次审计发现与修正

第一次实现后再次按原文逐项检查，发现两处仍有语义偏差：

1. **观测链路只是间接关联**：Event 和 Checkpoint 原本只有 `stepId`，消费者需要
   自己联表猜 Span。已增加直接 `traceId/spanId`，满足原文“事件定位 Span、Span
   定位 Checkpoint”的要求。
2. **取消只在循环开头检查**：模型执行期间到达的取消可能被最终回答抢先完成。
   现已在模型调用前后、每个工具调用前检查；测试覆盖“模型调用期间收到取消”。

修正后没有再发现应由本地 Harness Protocol 承担、却仍被隐式省略的核心语义。
剩余项均属于第 5 节列明的部署绑定，或需要外部系统才能诚实实现的能力。

## 8. 验证结果

- `mvn -q test`：21 tests，0 failures，0 errors。
- 覆盖 Run 状态机、恢复血缘、超时终态、事件游标、持久取消、Message/Part、
  TraceSpan、ProtocolError、Checkpoint 恢复及 Event/Span/Checkpoint 关联。
- `mvn -q package`：通过（包含同一组 21 项测试）。
