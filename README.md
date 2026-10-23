# UData Harness

基于 Solon Web、Solon AI `HarnessEngine` 的轻量 Web 编码智能体，参考
SolonCode、Solon Harness 和 `D:\code\dev-agent` 实现。

当前能力：

- 按 `userId` 隔离配置，并支持注册、切换后端所在机器的本地工作区；
- 创建、持久化并继续多轮会话；
- HarnessAgent 文件、搜索、终端、Skill、Web 与子智能体工具；
- SSE 流式思考、正文、工具调用、HITL 审批和最终状态；
- 原生 HTML/CSS/JavaScript 文件树、搜索与编辑器；
- 运行时 MCP 配置；
- 运行时 LSP 语言服务器配置；Agent 的 `lsp` 工具可单独启用；
- 后台 Skill 包仓库、ZIP 整包导入和按用户生效/停用；
- 可选的 Harness `task`/`multitask` 与自定义 Subagent；
- 定时 Loop 和带状态/轮次预算的 Goal。

架构图与安全边界见 [docs/architecture.md](docs/architecture.md)，模块职责、数据模型、执行流程与演进方向见 [系统设计文档](docs/design.md)。

## 可选工具开关

`model.yml` 的 `agent.tools.optional` 默认关闭 `render_echart`、`render_antv_chart`、
`bash_start`、`bash_wait`、`bash_stop`、`lsp`、`todowrite`、`todoread` 和 `task`。
`now` 也默认关闭；需要当前时间时可使用同步 `bash`，或单独开启 `now-enabled`。
每项均有独立的 `*-enabled` 开关，可按需开启；异步 Bash 三项建议一起开启。
关闭 `task` 时同一 Talent 的 `multitask` 也不可用。基础文件、同步 `bash`
和 Web 工具仍按各自原有配置工作。工具配置在引擎创建时生效，更改后需重启后端。

创建会话时选择能力模式；旧会话默认采用标准模式。经济模式在 Harness 的 `pi`
工具组（`read/write/edit/bash`）上增加 `ls`，只提供
`read/write/edit/bash/ls`；标准模式再增加 `glob/grep`；超级模式提供已启用的
全部工具（包含按配置开放的 Web、LSP、Skill、异步 Bash 和子智能体等）。
会话空闲且没有待审批调用时可切换，审批权限与能力模式独立。
`agent.modes.{economy,standard,super}.model` 可指定每档默认模型别名，须与
`agent.model.models[].name` 一致；未配置或找不到时使用应用默认模型。创建会话时
仍可单独选模型，聊天时也可按轮覆盖。模式和模型都保存在会话元数据中。
经济模式用 `bash` 做目录与代码搜索，Windows 上请按实际终端方言编写命令。
比较模式成本时应同时看总 Token、工具调用次数、任务完成率和误改率。

聊天框“本轮 Token”和审计台账仅累计主 Agent 已返回 Usage 的模型调用，按运行与推理 ID 去重。
子代理自身的模型调用、压缩摘要调用不计入该汇总；运行结束只更新耗时，不使用含子代理的框架总量覆盖明细。
主 Agent 后续请求中携带的工具或子代理返回内容仍属于该次主调用的输入。
SDK 的输入 Token 已包含缓存读写，缓存命中率为缓存读取量除以全部输入，不重复加计缓存或思考 Token。

输入框下方“首 Token 延迟”使用服务端事件时间戳，统计本轮主 Agent 首次模型推理开始到首个非空文本、思考或工具参数流片段的耗时；后续模型调用不覆盖首次结果。
等待首个片段时显示“等待中…”，无流式证据、提前失败或停止时显示 `—`；下一轮清零。不含此前压缩、子代理执行及浏览器传输时间。缓存命中率继续显示，窄屏允许指标栏换行。

输入框下方“耗时”由前端使用单调时钟计时：实际发送请求时开始，审批等待时暂停，续跑后累计，
完成、失败或停止后保留最终值；下一轮发送时清零。包含浏览器请求与传输时间，不含待发送队列和人工审批等待。
同一页面内各会话分别计时，刷新页面后无法恢复准确耗时，显示 `—`；后台定时任务耗时查看执行记录。

工具卡片默认采用分组视图：连续工具调用合并为可折叠列表，正文、思考、计划和子代理事件保持原有顺序。
在输入框“对话设置 → 工具展示”中可切换为原有逐条视图；选择保存在当前浏览器，刷新后保留。
工具组在生成参数、执行或等待执行时自动展开，组内工具全部结束后自动收起；期间仍可手动切换展开状态。
分组仅影响展示，审批卡片、图表和单个工具详情保持独立。

## 上下文工具结果治理

设计与分期计划见 [docs/context-management-plan.md](docs/context-management-plan.md)。首批已在
应用层接入大工具结果外置、读取结果去重和本批总预算；历史结果通过现有 `read`/`grep` 按需查看；
原有框架压缩器继续承担全局容量兜底。该实现不跳过工具执行，不依赖额外模型调用。

`model.yml` 的 `agent.context.tool-results` 控制开关和字符预算：默认单条 64,000、
本批 256,000、预览 4,000 字符；`read`/`bash`/长错误的模型可见预算分别为
160,000/64,000/16,000 字符。字符数不是 Token 数。
`read` 对不超过 32 KiB 的文件默认尝试读取 4000 行；较大文件默认预览前 400 行并显示总行数（超过 16 MiB 时不统计）。通读大文件可显式设置 `limit`（例如 2000）；局部查看可用 `focus_line`，同一文件多个位置可用 `focus_lines=[120, 180]` 一次读取。多焦点最多 8 个，默认各取前后 40 行，重叠窗口合并，合并后最多 1600 行；`context_lines` 可调整每个窗口。`offset`/`limit` 仍用于连续范围；单次最多 8000 行、256 KiB 编号正文。未变化的同会话同范围重复读取只返回带可恢复快照路径的短引用。历史快照按需读取的
单页正文最多 16 KiB，字符分页默认 12,000、最多 16,000 字符。
`grep` 默认返回按文件聚合的搜索概览（命中计数、少量带行号片段及完整性提示）；`output_mode=content` 保留逐条结果与续页，单页正文最多 32,000 字符。`ls` 默认每页 200 项；`glob` 默认每页 80 项，共享目录前缀压缩成 `├──` / `└──` 树形结果，页尾显示当前匹配进度与 `after` 续页游标。
`ls` 未开启递归时默认给 `├──` / `└──` 树形浅层概览：直属目录优先，小子树展开至第三级，
超过 12 项的子树只标注有界概览；`mode=flat` 可恢复仅列当前层。
`recursive=true` 以树形递归列出全部层级并分页；单独设置 `max_depth`（1～64）即启用指定深度的递归。续页可能重复显示上级目录作为路径定位；达到深度边界时标注未展开，
不会误报完整递归结束。递归续页保持 path/recursive/max_depth 一致，详见 [目录概览说明](docs/ls-adaptive-overview.md)。
预算为软限制：受保护、错误、媒体、直接返回、未知工具及引用本身无法再缩短的结果保留原样。
当前治理的工具为 read/bash/grep/glob/ls/webfetch/websearch/codesearch/lsp；图表、Todo、
自定义工具保持原行为。原文在保存失败时也保持原样。

原文与稳定预览保存在 `<agent.data-dir>/users/<userId>/context-artifacts/<workspaceId>/` 下，
按会话隔离。模型可用 `read(file_path="@tool-results/<id>.txt", offset, limit)` 按行分页；
超长单行可用 `char_offset/char_limit` 按 UTF-16 字符分页，
或用 `grep(pattern, path="@tool-results")` 搜索当前会话已归档的结果；路径只读，不能由 `write/edit` 修改。
读取内容是历史快照，需要当前文件时仍应重新 read。对不超过 16 MiB 的本地文件，重复读取先核对当前 SHA-256；即使旧 Read 正文已退出 WorkingMemory，也可用会话快照恢复。保存失败或版本变化时照常返回新读取结果。
同步 `bash` 输出通过流式执行器落盘；展示截断不会终止命令，超时、取消、归档故障或独立日志存储上限会标明捕获不完整。构建和测试成功时压缩常规日志；失败时优先展示诊断行，完整已捕获日志仍可回读。
`verify(task="test", build_system="auto")` 从当前目录识别 Maven、Gradle 或 Node 项目，生成单条跨平台命令并返回验证状态、失败诊断及会话日志路径；`task` 可选 `test`、`build`、`compile`（Node 项目不支持 `compile`）。`verify` 与 `bash` 使用相同的人工审批规则。`edit` 在先 `read` 后比较两种互斥操作的参数成本：整行或连续多行且已知行号时优先 `line_edits=[{"start_line":120,"end_line":140,"new_str":"..."}]`，无需重发旧代码；仅修改长行内的短片段时可用 `edits[].old_str/old_StrStartLine/new_str`，避免重发整行。非本地逻辑挂载仅支持 `edits`。多处行区间按原文件行号从下往上应用，重叠或越界会整体拒绝；空 `new_str` 删除整行。后端仍校验文件版本并原子写入。

`bash` 的短且完整结果只显示退出码和正文；日志仍归档，路径保留在工具元数据中。模型可见正文会去掉 ANSI 颜色控制码，归档保存原始解码文本。长输出、失败摘要或捕获不完整时继续在结果中给出 `@tool-results` 回查路径。

批量治理在 Native Tool 模式的 ActionEnd 安全边界执行，只改变下一次请求将使用的本批结果。
单条外置也支持文本 Observation 模式；文本模式批量治理暂不启用。
同一引擎的子代理使用自身会话范围，未提供跨会话访问。快照目前无自动过期删除，
应纳入现有数据目录权限与备份策略。关闭开关也会关闭结果外置与虚拟路径。

`context_restore`、`context_search`、`context_task_update`、`context_task_snapshot` 不再注册给 Agent；
也不再要求模型为维护任务状态额外调用工具。旧任务档案和归档文件保留在磁盘上，
但任务切换驱动的历史改写已停用。已有归档可通过 `@tool-results` 读取。

此前任务档案方案（现为历史设计，默认 Agent 不再使用）：

- `context_task_update(task_id, state, ...)` 记录模块、用户约束、结论、验证和下一步。
  激活新任务会暂停前一任务；关闭任务必须填写结论和验证说明。验证是 Agent 报告的证据，工具不会代替执行测试。
- `context_task_snapshot(task_id)` 恢复档案；省略 ID 获取当前任务。已关闭任务重新激活后需要重新填写验证。
- Native Tool 模式下，已明确归属暂停任务的纯文本工具结果超过 4,000 字符、关闭任务的结果超过
  1,000 字符时，尝试外置原文并缩短活跃视图，阶段归档预览最多 400 字符。
  只有替换文本更短才生效；错误、受保护结果及归属不明的历史保留。
  同批并发切换任务与读取时不猜测读取归属。阶段内历史表示保持稳定，显式任务切换可能改变缓存前缀。
- `context_search(query, task_id?, source_path?, offset?, limit?, body_offset?)` 按大小写敏感的字面关键词检索归档。
  每次最多扫描 20 个归档、每个正文 100,000 个 UTF-16 字符、返回 10 个命中。用返回的
  `next_offset` 和 `next_body_offset` 续查，支持跨正文分页边界命中；每个归档返回一个命中。
  搜索期间新增归档可能改变文件列表顺序，必要时从 offset=0 重新检索。
- Read 结果记录工作区内的源路径、读取范围及归档时观察到的文件哈希；不读取工作区外的文件。
  恢复时提示源文件是否变化或不可用；超过 2 MiB 的源文件不计算版本哈希。
  该哈希在工具执行后观察，不能证明工具执行期间文件没有变化。
- 会话目录新增 `tasks.properties` 和 `context-actions.jsonl`，记录任务档案及归档、恢复、检索、状态更新动作。
  动作审计不包含正文，审计写入失败不阻止恢复或导致原文清理。旧归档仍可恢复和检索，缺失来源信息显示 unknown。

任务状态由 Agent 显式调用工具维护，不做自动语义识别；任务档案最多 100 项、每个字段最多 4,000 字符。
任务档案与阶段归档不额外调用摘要模型。阶段归档现在受 Cache 成本门槛控制；结构化依赖图仍属后续阶段。

会话压缩可通过 `agent.context.compaction.mode` 选择 `cache-aligned`（默认）或 `legacy`。
前者在上下文压力下使用一次模型摘要请求：重放与前次正式请求匹配的 system、工具定义和较早消息前缀，
最后追加压缩指令；摘要成功且更短后保存检查点。下一轮模型读取检查点与后续原文，完整会话历史仍保留。
`agent.context.compaction.threshold-ratio`、`retain-ratio` 与 `output-reserve` 控制触发、近期原文和输出余量；
`/api/usage/audit` 中 `scope=compaction` 记录摘要调用、前缀匹配及失败。前缀匹配不保证提供方缓存命中，
实际效果应以模型返回的缓存 Usage 为准。摘要请求当前为同步调用，取消需等待该请求返回。

### Cache 成本决策与观察台

聊天输入区下方点击 **上下文观察**，查看本会话的上下文组成与趋势、逐请求 Usage、管理动作及决策原因、
任务状态和独立压缩开销。运行时面板每 3 秒刷新，停止后补取最后一批记录。
接口为 `GET /api/context/overview?sessionId=...&limit=200`；校验会话归属，最多返回每类最近 500 项。

`agent.context.cache.enabled` 默认开启，控制的是任务暂停/关闭后的历史工具正文重组，不影响新工具结果入场预算，
也不取代 `compaction.mode` 的容量兜底。策略比较未来输入节省与缓存重建、按需恢复开销，只有预计收益大于
开销乘安全系数才允许重组；估算上下文达到模型窗口的 `pressure-ratio` 时容量优先。
价格或最近一次缓存 Usage 未知时保留旧前缀，关闭此开关可恢复原阶段归档规则。

价格按模型配置中的 **name 别名** 设置在 `agent.context.cache.models.<name>`，不自动获取报价，也不预填真实价格：

| 配置项 | 含义 |
|---|---|
| `accounting` | 建议配置 `inclusive`；当前 SDK Usage 已包含缓存读写，`exclusive` 仅用于未归一化的原始计数入口 |
| `currency` | 三位币种代码；不同币种分别汇总 |
| `input-per-million` | 每百万普通输入 Token 单价 |
| `cache-read-per-million` | 每百万缓存读取 Token 单价 |
| `cache-write-per-million` | 每百万缓存写入 Token 的完整单价，包含该类别的输入费用 |
| `output-per-million` | 每百万输出 Token 单价；不重复加计 thinking Token |

单价必须为非负有限数；缺失或错误配置显示未知，不作为零。可以先只配置 accounting 以观察归一后的输入与命中率。
缓存字段未报告时保持未知；inclusive 口径下未报告单独缓存写入类别时按 0 处理。
`expected-remaining-calls` 默认 4、`restore-budget-tokens` 默认 2000、`safety-margin` 默认 1.2、
`pressure-ratio` 默认 0.8，都是可调的工程假设，不是实际收益保证。

观察记录写入会话目录 `context-observations.jsonl`，包含 runId/reasonId、模型、各类上下文估算、实际 Usage 与价格快照，
不保存正文。`context-actions.jsonl` 增加成本决策；reasonId 表示产生决策的调用，历史重组影响后续请求。
上下文和公共前缀是应用层工作记忆估算，未覆盖所有路由及供应商协议变化，不能据此前缀长度宣称实际 Cache 命中。
费用是已返回 Usage 按配置单价计算的估算，窗口内的主/子 Agent 调用与压缩调用分别计入，
未返回 Usage 的重试、未记录的工具费用及账单调整不在覆盖范围内。旧压缩记录缺少价格快照时明确按当前配置估价。
JSONL 读取限制返回条数和内存占用，但仍顺序扫描文件；长期高频使用需配合日志轮转或数据库索引。

### 稳定消息与上下文版本

文件会话为新消息保存内部 `udata_entry_id`；旧消息按会话、位置和语义指纹生成稳定 ID。
工具正文投影沿用消息 ID，并保存 `udata_source_entry_id`。这些字段属于内部元数据，不注入供应商消息正文。
运行时替换和重启装载共用检查点/投影状态；压缩覆盖范围按原始消息 ID 校验，不依赖 Java 对象身份。
原始聊天正文仍保留；旧格式检查点继续按原哈希校验读取，新的检查点写入 v2 格式。
损坏的检查点或来源不匹配的投影回退原文，投影状态保存失败时不替换活跃视图。

`ContextEpoch` 表示上下文代次。正常追加消息保持版本；历史重组、压缩、规则/工具定义、模型或端点变化会变代。
文件会话持久保存 epoch 与可见前缀指纹，跨运行重建历史时也能识别前缀变化。
观测、成本决策及实际重组事件包含 `contextEpoch`；旧代次 Usage 不再用于新视图的成本判断或前缀复用。
请求进行期间发生变代时，其 Usage 仍按原请求版本记录费用，但不能成为新视图的基线。

默认 `cache-aligned` 压缩路径识别当前用户轮次是否被切开：摘要必须包含非空的 `## Current Turn Intent`，
保留原始要求、验收条件、用户修正、早期进展和理解近期工具结果所需的信息。
不超过 1,024 字符的当前用户要求会另外以 JSON 引用形式逐字保留；较长要求通过该章节提炼，不截取成伪完整原文。
缺少该章节、章节为空、摘要截断或未缩小时，不提交检查点。
章节校验不等于语义验收；长要求的完整性仍需模型效果实验验证。`legacy` 模式保持框架原压缩策略，
同时保留稳定 ID 与工具投影装载。

## IntelliJ IDEA 插件

网页工作台继续独立使用；新增 IDEA 侧栏通过 HTTP/SSE 连接同一个本地 Solon 服务，支持项目绑定、
共享会话、审批、选区/文件上下文、文件定位和修改同步。构建与安装步骤见
[idea-plugin/README.md](idea-plugin/README.md)，双客户端协议见 [docs/idea-integration.md](docs/idea-integration.md)。

## Requirements

- JDK 17+
- Maven 3.9+
- Solon AI 支持的模型端点

项目使用 Solon Framework `4.1.0` 和 Solon AI `4.1.1`。

## Configure

```powershell
$env:AGENT_WORKSPACE='D:\agent-workspaces'
$env:AGENT_DATA_DIR='D:\agent-data'
$env:AGENT_API_URL='https://api.deepseek.com'
$env:AGENT_API_KEY='<your-deepseek-key>'
$env:AGENT_PROVIDER='openai'
```

默认通过 OpenAI 兼容协议连接 DeepSeek。前端模型列表由 `app.yml` 中的
`agent.model.models` 配置，`agent.model.default` 指定默认项；每个列表项可覆盖
`api-url`、`api-key`、`provider` 和 `context-length`。
模型请求重试通过 `agent.model.retry.max-attempts` 和
`agent.model.retry.initial-delay-ms` 调整；最大尝试次数包含第一次请求。
`AGENT_API_KEY` 没有默认值；必须通过进程环境或密钥管理服务注入。不要将密钥
提交到仓库或写入配置文件。

### 后台模型配置

启动后打开“后台管理 → 模型配置”，可以新增、编辑、复制、测试连接、启停或删除个人模型，
并设置个人及经济、标准、超级模式的默认模型。系统预置来自 `model.yml`，只读且不会回传 API Key；
复制系统预置需要填写个人密钥，复制个人模型可复用其已保存密钥。
没有可用模型时也能启动并进入后台；配置好后再创建会话。

个人配置保存到 `<data-dir>/users/<userId>/models.json`，稳定 ID 不随显示名称改变。
保存后无需重启：空闲引擎在下一次新请求时更新，有运行或待审批任务时延后应用，不重启工作区 MCP/LSP。
已指定模型的会话保留选择；删除或停用后须重新选择，不自动改用其他服务。
模型测试会发送一次简短真实请求，可能产生用量；测试失败不会保存配置，错误信息不回显服务端原始敏感正文。

所有 Agent 模型 HTTP 请求自动携带 `session-id: <根对话 ID>`，用于网关缓存路由及审计。
同一对话的多轮提问、工具循环、重试、压缩以及 `task` / `multitask` 子代理保持相同值；
新建对话使用新 ID，重启后继续已有对话仍使用原 ID。后台连接测试每次使用独立的 `test-<UUID>`。
请求头由后端执行上下文动态设置，不写入提示词，不改变主 Agent 的 Token 统计口径，也不保证一定命中缓存。
子代理根 ID 的传递使用本项目对 Solon AI 4.1.1 `TaskTalent` 的局部适配；升级 SDK 时需保留该传递逻辑。

API Key 直接以明文保存在个人 `models.json` 中，接口不回传已保存的密钥；留空保留，勾选清除可移除。
无需主密钥文件或额外环境变量，迁移时复制数据目录即可。旧版加密配置保留模型信息，
后台会提示重新填写 API Key；保存前该模型不参与调用，原密文文件不会被删除。
读取、修改和搜索文件工具会拒绝访问包含密钥的模型配置；终端及宿主机权限仍由部署的沙箱策略约束。

接口位于 `/api/models`、`/api/models/defaults`、`/api/models/test`，沿用应用现有的 `X-User-Id`
隔离机制；这不是新增登录系统。多人网络部署需要可信认证网关绑定用户身份，禁止客户端冒用该 Header。

### 对话定时任务

进入已有对话后，在输入框上方点击“定时任务”。支持单次、每天、每周和固定间隔（最短 1 分钟），
默认时区为 `Asia/Shanghai`，可指定其他 IANA 时区。输入框里已有的文字可直接作为任务指令。
任务绑定原对话和工作区，默认跟随会话模型，也可指定模型；模型不可用时记录失败，不自动换用其他服务。

任务由后端执行，关闭网页不会取消；电脑、Java 进程和网络须保持可用，休眠或退出程序时不会执行。
执行结果写入原对话并标注任务名称与计划时间，通过页面状态轮询刷新，可在任务面板查看执行状态及记录。
定时任务沿用会话的工具权限；需要人工审批时进入“等待审批”，返回原对话提交决策后继续跟踪结果。
主 Agent 调用正常进入 Token 台账，子代理和压缩仍不计入主 Agent 汇总，网关 `session-id` 保持原对话 ID。

同一会话串行执行，繁忙或待审批时最多合并保留每个任务的一次待执行机会；全服务最多并发 4 个后台任务。
暂停计划会清除待执行机会，但不会停止已经开始的运行；停止运行使用执行记录中的“停止”按钮。
“立即执行”不改变周期计划。删除任务保留历史记录，运行中及待审批的任务需先结束或处理完审批。
重启后补跑错过的单次计划，周期计划跳到下个时间点；已开始但未完成的运行记为中断，不自动重放。

定义与执行记录保存在 `<data-dir>/users/<userId>/automations.json`，执行前先持久化派发记录。
每个用户最多保存 100 个任务，保留最近 200 条执行记录；面板展示最近 20 条。
调度器面向单个 Harness 进程，不支持多个进程共享同一数据目录进行调度。
接口为 `/api/automations`、`/state`、`/run`、`/stop`，按现有用户身份及会话归属校验访问。

## Build and Run

```bash
cd frontend
npm install
npm run build
cd ..
mvn test
mvn package -DskipTests
java -jar target/udata-harness.jar
```

Windows 可双击项目根目录的 `启动.bat`，自动查找同目录或 `target` 下的 `udata-harness.jar`。
分发时将 `启动.bat` 与 `udata-harness.jar` 放在同一目录即可。
脚本依次使用同目录 `runtime/bin/java.exe`、`JAVA_HOME/bin/java.exe`、`PATH` 中的 Java，
在脚本所在目录运行，启动失败时保留窗口显示错误。脚本最多等待 120 秒，检测到页面可访问后自动用默认浏览器打开
`http://127.0.0.1:8080/`；探测直连本机、不使用系统代理，诊断记录保存在脚本旁的 `browser-start.log`。超时可手动打开。若使用其他端口，可设置 `UDATA_HARNESS_URL` 为对应网址
（该变量仅影响浏览器打开地址，不修改服务端口）。
运行期间保持窗口开启，按 `Ctrl+C` 停止。脚本默认设置 `SOLON_AI_CLI_SHELL=powershell`，
使用 Windows PowerShell；如果启动前已设置该环境变量，则保留原值。

直接使用 `java -jar` 在 Windows 启动时默认要求 PowerShell 7（`pwsh.exe` 在服务进程的 `PATH` 中）；
Harness 会在创建终端前探测版本并选用 `pwsh`。找不到可用的 PowerShell 7 时启动会给出明确错误，
不会静默使用 PowerShell 5.1。需要改用其他终端时，可在启动前设置
`SOLON_AI_CLI_SHELL=powershell` 或 `SOLON_AI_CLI_SHELL=cmd`；
JVM 属性 `-Dsolon.ai.cli.shell=...` 优先于环境变量。切换后须重启服务。

React/Vite 源码位于 `frontend/src`；生产构建会输出到
`src/main/resources/static`，由 Solon Web 直接提供。

打开 <http://localhost:8080/>。浏览器首次访问时输入 `userId`，随后可以从后端返回的
全部磁盘根目录中打开本地项目，无需额外配置目录白名单。未选择项目时使用
`<agent.workspace>/<userId>` 默认目录；
工作区注册表和会话保存在 `<agent.data-dir>`。每个会话绑定创建时的工作区，切换目录后
只展示该工作区的会话。

## API

除静态资源外，用户接口必须携带 `X-User-Id`。

| Path | Purpose |
|---|---|
| `/api/sessions`, `/api/chat/*` | 会话、持续对话、流式输出与停止 |
| `/api/hitl/decide` | 审批、跳过或拒绝工具调用 |
| `/api/files/*` | 用户工作区文件树、读写与搜索 |
| `/api/workspaces/*` | 本地目录浏览、工作区注册与切换 |
| `/api/automations`, `/api/automations/*` | 对话定时任务、暂停启用、立即执行、停止与执行记录 |
| `/api/integrations/mcp` | 服务级 MCP 配置 |
| `/api/integrations/lsp` | 服务级 LSP 配置，新增、查询和删除语言服务器 |
| `/api/capabilities/{skills,agents}` | Skill 包、激活状态与 Subagent 管理 |

## Security

`X-User-Id` 当前只是目录路由键，不是身份认证。不要直接暴露到不可信网络。
生产部署需由可信网关从已登录身份注入该请求头，并仅允许管理员修改全局
MCP。集成配置可能包含密钥，数据目录必须设置严格文件权限。
Skill 原始包保存在 `<agent.data-dir>/skill-library/<name>`。用户点击生效后，
服务将完整目录复制到
`<agent.workspace>/<userId>/.soloncode/skills/<name>`，并只刷新该用户的
HarnessEngine。Subagent 定义保存在 `<agent.data-dir>/capabilities/agents`，
目前为服务级共享资源。生产环境应限制这些后台写入接口。
LSP 配置也由全服务共享，启动命令会在后端机器执行，管理接口只应向管理员开放。

## LSP

在管理后台的 **LSP 管理** 中保存语言服务器配置。例如，已在后端机器安装
`jdtls` 后可使用：

```json
{"name":"java","command":["jdtls"],"extensions":[".java"],"enabled":true}
```

`command` 是程序与参数组成的数组，`extensions` 是该服务器处理的文件扩展名。
可选 `env` 对象用于为语言服务器设置环境变量，例如单独指定其 `JAVA_HOME`。
当前较新的 Eclipse JDT LS 运行时需要 Java 21；本项目后端仍可用 JDK 8 编译和运行。
语言服务器程序需要单独安装，保存配置不会自动下载它。各用户工作区使用独立的
HarnessEngine 与 LSP 管理器，LSP 配置保存在 `<agent.data-dir>/integrations/lsp`，
服务重启后恢复。IDEA 中尚未保存的内容需先保存到磁盘，后端 LSP 才能读取最新版本。

### 离线交付（内置 Java 与 TypeScript LSP）

在有网络的构建机上先构建前端和 JAR，再生成包含 Eclipse JDT LS、
TypeScript Language Server 和 TypeScript 的交付包：

```powershell
npm.cmd --prefix frontend run build
mvn package -DskipTests
.\scripts\package-offline.ps1
```

生成的 `target/offline/udata-harness-lsp-jdtls-1.62.0-tsls-6.0.2.zip` 适合 Windows，
同目录的 `.tar.gz` 适合 Linux。交付包内有应用 JAR、`jdtls/`、`typescript-lsp/`、
启动脚本和上游许可文本；将整个包复制到内网机器并解压，不需要安装语言服务器或运行全局 npm 安装命令。
Windows 运行 `run.bat`，Linux 运行 `sh run.sh`。启动脚本使用机器已有的 Java；
**JDT LS 1.62.0 要求 Java 21 或更高版本**；TypeScript Language Server 6.0.2
要求 **Node.js 22.22.2 或更高版本**。交付包不包含 Java 或 Node.js 运行时。

从交付包启动且没有显式同名 LSP 配置时，后端自动注册包内 `java` 和
`typescript` 服务。后者处理 `.ts`、`.tsx`、`.js`、`.jsx`、`.mjs`、`.cjs`、
`.mts`、`.cts`，通过部署机的 `node` 启动；若 Node 不在服务进程的 PATH，
可设置 `NODE_HOME` 或 JVM 属性 `udata.node.command` 指向 Node 可执行文件。
每个用户工作区拥有独立的语言服务器实例，Java LSP 还使用独立数据目录。
已有的 `<agent.data-dir>/integrations/lsp/<name>.json` 会覆盖同名内置配置；
如果它保存的是旧机器上的路径，请在管理后台删除该条配置以恢复内置版本。
普通 `java -jar` 启动不启用自动发现，仍可按上文手动配置 LSP。


## HTTP 通知

后台管理 → 通知配置可以按用户保存多条 HTTP 回调，覆盖该用户的全部工作区，手动对话和定时任务共用配置，保存后无需重启。配置包含 URL、请求方式（POST / GET / PUT / DELETE）、请求头、JSON / 纯文本请求体、连接与读取超时，以及成功 / 失败 / 手动停止触发条件。接收人或群 ID 可以直接作为请求体的固定字段。

动态变量：`${workspace.id}`、`${workspace.name}`、`${session.id}`、`${session.title}`、`${execution.id}`、`${execution.status}`、`${execution.source}`、`${execution.duration}`、`${execution.durationMs}`、`${result.summary}`、`${result.error}`、`${startedAt}`、`${finishedAt}`。状态为 `succeeded / failed / cancelled`，来源为 `chat / scheduled / test`，时间使用 UTC ISO 格式；执行耗时不包含审批等待。JSON 变量写在字符串值内，框架先解析再替换和序列化，自动处理换行、引号，并保留完整数值变量的类型。URL 路径和查询参数中的变量会编码；不支持动态域名、端口或执行模板脚本。

审批挂起、工具结束和子代理结束都不会单独发送完成通知；整轮最终结束后才由后台线程投递。最终回答摘要限制约 1200 字符，不包含结构化思考和工具输出。测试发送使用示例数据，手动重发使用原记录变量和当前保存的配置。

默认 HTTP 2xx 为成功，可选增加 JSON 字段判断，例如 `errcode` 等于 `0`。不跟随 HTTP 重定向，不自动重试；网络超时时接收方可能已经处理，请核对后手动重发。请求自动附带唯一 `X-Udata-Notification-Id`；同一轮同一配置的自动通知会去重，手动重发会生成新的发送 ID。

配置与最近 200 条发送记录保存在 `data/users/<user>/notifications.json`。沿用当前项目的可信 `X-User-Id` 用户边界，文件在本机明文保存，无需主密钥文件；URL 和请求头保存后不通过配置查询接口回传。接口地址留空、同名请求头值留空会保留原值，移除请求头行会删除该项。配置文件不对 Agent 文件工具开放，日志不保存请求头、完整 URL 或原始响应正文。服务重启后未确认的发送记录标记为中断，不自动重放。
### 心智记忆

后台管理 → 心智记忆提供用户级总开关，默认关闭。开启后，全局记忆在当前用户的全部项目间共享，项目记忆绑定已有工作区 ID、跨会话保留；不同用户互相隔离。设置修改从下一轮对话生效，正在运行及等待审批的任务保留本轮记忆快照。关闭停止新一轮召回与记忆工具挂载，保留已有条目；已经进入旧会话的内容不会被撤回。

页面支持新增、修改、置顶、停用、作用域移动、回收站恢复、关键词筛选，以及最近 300 条变更记录。即使关闭，也可以手动维护记忆。并发修改通过 revision 检查拒绝过期覆盖。

开启后，在对话中明确说“记住……”可使用 `memory_save` 保存项目约定；要写全局记忆，请明确说明“全局”“所有项目”或“跨项目”。更新前通过 `memory_search` 获取条目 ID 和版本；明确要求“忘记……”时可使用 `memory_forget` 软删除。第一版不进行后台模型自动提取或整理。工具写入意图仅从用户消息判断，不从编辑器附件提取；仍需模型遵循工具说明，不保存密钥、日志和临时任务进度。

召回采用本地关键词匹配（包含中文双字匹配），项目同 key 条目覆盖全局条目，置顶条目优先，最多注入 12 条完整记忆；超过预算的条目跳过。预算默认 1000、可设 256～4000，使用 SDK 的 Token **估算**，不是供应商计费值。主 Agent 的实际请求用量按原有口径统计；未增加独立整理模型调用。子代理只继承本轮召回内容，不提供记忆写入工具。

运行中可在回答上方展开“本轮使用记忆”查看内容与来源；会话快照最多保留最近 200 次运行的召回详情。修改或删除记忆不会抹去已发送模型请求的历史记录。

存储位置为 `<agent.data-dir>/users/<userId>/memory.json`，全局与项目条目、开关及变更记录在一个文档中原子更新，最多 1000 条（含回收站）。该文件由应用管理，并接入文件工具保护机制。通过请求级 Solon Talent 接入，不启用框架默认共享 Memory Provider。

接口：`GET/POST /api/memory?workspaceId=...`，`POST /api/memory/settings`，`POST /api/memory/state?workspaceId=...`。用户归属沿用现有 `X-User-Id` 机制。

### 长会话前端渲染

对话设置提供“输出动效”：关闭、柔和（默认）、追光，偏好保存在当前浏览器。流式正文只对尾部最多 24 个 Unicode 字素做短暂显影，追光档增加主题色微光和柔和档共用的呼吸光标；完整 SSE 内容立即呈现，不增加逐字排队或额外模型请求。代码、表格、历史回复和工具输出保持稳定。上滚阅读时暂停动画，回复完成、停止或失败后恢复普通正文；系统启用“减少动态效果”时禁用显影和光标动画。

历史消息、Markdown 和工具卡片采用记忆化渲染，输入框修改不触发历史消息重新渲染；更新当前回答时保留旧消息对象。耗时的 250ms 显示刷新仅作用于指标栏。流式事件按约 40ms 合并提交界面，保留原始事件顺序和时间戳；审批、错误及结束立即提交，断线或取消前刷新尾批次，后台标签页队列达到 256 条也会提交。Token 统计仍按原始事件逐条累计。

收缩的工具组不挂载内部工具卡片，收缩的思考卡片不挂载完整正文；工具参数仅在展开详情时格式化。展开后仍可查看完整结果。

历史首屏通过 `GET /api/sessions/history` 以最近 30 条用户/助手消息为目标分页，并向前扩展到完整用户轮次边界；工具结果随其调用一起返回，向上通过“加载更早消息”继续读取。游标使用不可压缩原始历史的位置，新消息追加不移动旧页。每页正文预览共享 32000 字符预算，单条最多预览 2000 字符；工具只带少量摘要字段，完整参数、结果和差异通过 `/api/sessions/history-content` 分段读取，默认每段 8000 字符。旧 `/api/sessions/messages` 接口保留兼容，前端不再使用。

已加载消息采用可变高度虚拟列表，只挂载视口及附近消息；向上补页保持阅读锚点，回到底部可继续跟随流式输出。卡片展开状态及最近 100 项按需正文缓存在当前会话视图内保留，切换会话后重新初始化。历史图表和任务卡片在进入挂载窗口时按需恢复结构化数据。实时长工具结果先显示 16000 字符，用户可继续展开。页面展示分段不会修改审计历史、模型上下文或工具执行结果。原生浏览器查找仅能覆盖当前挂载的内容。
