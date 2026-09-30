# 长周期任务上下文管理（第一版）


这版在持久化模型视图之上增加工具结果治理、结构化任务状态、显式阶段检查点和证据恢复。
范围限定为主 Agent 的文件会话。子 Agent、Thinking、图表、多模态和未知工具结果不经过新的投影策略。
没有实现自动语义相关性评分、跨会话记忆、向量库或供应商计费模型。

## 数据与请求生命周期

每个会话的原始消息仍写入 `*.messages.ndjson`；`*.model-context.json` 保存模型实际使用的视图。
新增 `<sessionId>.context/`：

- `artifacts/<sha256>.json`：工具名称、规范化参数、原始文本、内容哈希、创建时间。
- `task-state.json`：结构化任务状态及 revision，原子更新。
- `metrics.ndjson`：工具投影、检查点、预算压缩、恢复、请求前上下文及供应商用量事件。

完整工具调用组先写入原始归档，再生成工具结果投影，最后提交模型视图。
文件和范围相同、文本内容相同才属于同一证据版本；工具参数按键排序后计算哈希。
首次归档时间是证据创建时间，不表示后来调用工具的时间。
原始工具执行不会因去重而被跳过：去重减少的是模型收到的重复正文，文件更新仍能被发现。

新请求通常沿用稳定前缀并追加消息。任务状态更新不会每轮重写已有状态快照。
首次有状态时插入一次快照；成功检查点或预算压缩时刷新快照。
快照放在模型消息前部，显式保护，包含任务目标、约束及验收条件；它不是系统指令，也不是完成证明。

## 工具结果策略

`read`、`grep`、`glob`、`ls`、`list`、`webfetch`、`websearch`、`codesearch`、`bash`、`bash_wait`
的非直返、单文本结果参与治理。其他工具、媒体内容和直返结果保持框架行为。

- 短结果保留全文，标记证据 ID 和 `full` 投影。
- 同版本、同范围的 `read` 全文仍在活跃上下文时，新结果仅返回引用。
- 如果引用文字比短正文还长，则直接保留正文，避免去重反而增加请求载荷。
- 如果旧全文已退出上下文，或者只有旧片段/引用，则再次提供正文。
- 长结果保留头部、诊断行及尾部，并明确标注为**不完整输出**；不推断成功状态。
- 诊断行提供字符 offset。遗漏的报错、代码或日志可以通过恢复工具取回。
- 治理不重新修改已投影消息，不任意拆散 assistant/tool 调用组。

规则投影可能遗漏未命中诊断关键词的信息，因此 Agent 必须将片段视为部分证据，必要时恢复或重新执行工具。
写盘失败不会生成缩减投影；未完成投影时下一次推理会再次尝试治理，失败则中止该请求。

## Agent 工具

主 Agent 注册六个稳定定义的工具：

| 工具 | 行为 |
| --- | --- |
| `task_state_get()` | 读取最新任务状态 |
| `task_state_update(patch)` | 原子更新结构化状态 |
| `context_checkpoint(reason)` | 请求在下一次推理前批量摘要旧历史 |
| `context_search(query, tool?, limit?)` | 精确关键词检索当前会话证据，所有词必须匹配 |
| `context_restore(id, offset?, maxChars?)` | 恢复历史证据片段，返回 nextOffset |
| `context_metrics()` | 查询上下文决策及用量报告 |

状态支持 `objective`、`acceptance`、`constraints`、`phase`、`module`、`decisions`、`completed`、
`pending`、`questions`、`evidence`、`nextStep`。目标首次写入后保护；约束与验收条件累积，
其他列表按 patch 替换。证据 ID 必须已存在。状态总长限制为 16000 字符。
目标发生根本变化时应新建任务；当前版本没有覆盖既有目标或移除约束的工具。
阶段和完成状态由 Agent 显式维护，系统不自动认定测试通过或 Bug 已关闭。

建议流程：

1. 从用户请求建立目标、验收条件和约束。
2. 工具探索与修改过程中记录决定、未解决问题及证据 ID。
3. 模块 A 完成并验证后，更新完成项、模块 B 待办与下一步。
4. 调用 `context_checkpoint`，旧历史变成摘要，保留最新用户请求和近期完整工具组。
5. 再次需要模块 A 时按路径、符号或错误码搜索，恢复相应片段；验证当前文件版本及测试结果。

检查点不是每轮整理：少于两条旧消息或旧正文不足 2000 字符时跳过，避免为很小收益重建前缀。
这是容量和批量程度的启发式，不是货币成本的最优决策器。Token 预算压缩仍作为兜底。
摘要为空、摘要异常或模型视图写盘失败时保留原上下文，不静默裁剪。

`context_restore` 的 offset/maxChars 使用 UTF-16 字符单位，单次最大 16000 字符。
响应明确标注历史证据及版本哈希，不声称与当前工作区一致。
检索当前使用文件扫描，没有向量库和索引；证据很多时检索延迟需要进一步优化。
归档暂不自动过期，磁盘占用会持续增长；清空/删除会话时一并清理。

## 配置与观测

```yaml
agent:
  context:
    management-enabled: true
    tool-result-max-chars: 6000
    checkpoint-retain-messages: 12
    compression-max-context-ratio: 0.75
    compression-max-messages: 1000
    compression-message-trigger-factor: 2.0
```

关闭 `management-enabled` 可作为对照：保留之前的持久化模型视图及预算压缩，关闭新投影和上下文工具。
新开会话做对照，关闭开关不会把已有摘要/片段重新扩展为原始历史。
检查点保留数量为目标，工具组边界及最新用户请求可能使实际数量增加。

只读 HTTP 接口均通过 `X-User-Id` 和 `sessionId` 检查会话归属：

- `GET /api/sessions/context?sessionId=...`：当前代次、归档/活跃消息数、任务状态、指标。
- `GET /api/sessions/context/search?sessionId=...&query=...&tool=...`：最多十条命中。
- `GET /api/sessions/context/restore?sessionId=...&id=...&offset=0&maxChars=4000`：有界恢复。

报告保留真实响应的 prompt/completion/cache read/cache creation 及 TTL 拆分。
供应商 prompt 字段是否包含缓存 Token 的口径不同，不直接累加成统一成本，也不推断缓存命中率。
`commonMessagePrefix` 是本进程中相邻推理前模型消息的结构稳定程度，**不是 API 缓存命中**，
没有计入 system/tools 变化、缓存 TTL、分词或供应商内部请求转换。重启后首次比较为未知。
主 Agent 推理与压缩策略内的模型调用现在由请求级拦截器记录，包含阶段检查点、预算摘要、
可观察到的失败重试、流式完成及取消。摘要通过代理模型接入，不修改共享模型配置。
`providerUsageByModelAndPurpose` 按供应商/模型/reason 或 summary 分桶；每次调用有独立 `callId`，
摘要与推理通过 `runId` 关联。`callsWithoutUsage` 保留未知用量，不能按零费用处理。
旧 `providerReportedReasonUsage` 只表示旧版 usage 记录；新增记录应读取新的分桶字段。
尚未接入供应商计价、子 Agent 推理和供应商内部不可见重试，因此 `costMeasurementComplete` 仍为 false。

## 第二批改造：压缩保护与工具分类

预算压缩与阶段检查点均保留最新用户请求原文，并在成功压缩后刷新任务状态快照。
已写入状态的 pending、questions、constraints、acceptance 和 evidence 不依赖模型摘要来保留。
预算压缩期间暂时把用户请求移到保护前缀；未压缩时恢复原顺序，实际请求不会因此每轮重排。
压缩成功后恢复用户请求与存活后续消息的相对顺序。
摘要须非空且不能是工具结果或工具调用；保留结果必须有对应调用，已完整的工具组不能只留下调用。
校验或写盘失败时回退。这些是结构校验，不证明自然语言摘要没有遗漏或误述。

- 文件正文：保留按原顺序排列的头尾片段，不把代码中的 error 字样当日志诊断。
- bash/bash_wait：优先提取失败行，再补充状态行和头尾，标注原始字符偏移。
- grep/glob/目录及搜索结果：优先保留完整的前部匹配行及路径/行号；未知单行格式退回有界片段。
- webfetch 等文本：使用通用诊断片段规则。所有片段明确标注省略，完整原文仍可恢复。

## 可复现验证

```powershell
.\mvn8.ps1 test
.\mvn8.ps1 '-Dtest=ContextManagementIntegrationTest' test
```

新测试覆盖重复读取、版本变化、退出后的再次读取、日志关键错误、恢复范围、任务约束保护、
模块 A → B → A、完整工具调用组、摘要/写盘失败、会话隔离、真实 ReAct 请求组装和重启。
均使用模拟模型，不访问外部模型 API。本轮全量 **87 项通过**，增加连续预算压缩的原文/待办保护、
普通请求顺序稳定性、工具组拆分拒绝、摘要调用用量、失败重试、流式取消与日志诊断优先级测试。

对照测试输出 `target/context-management-comparison.json`，比较同一脚本任务开启/关闭治理时的
实际请求 JSON 字符数，包括新增工具定义的开销，且断言完整原始日志仍存在。
这证明请求载荷降低，**不证明实际 Token、缓存、货币成本或真实任务完成率提高**。
赛题评估应进一步使用同模型同参数、多次真实运行，对照独立验收测试及所有模型调用账单。
