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

架构与安全边界见 [docs/architecture.md](docs/architecture.md)。

## 可选工具开关

`model.yml` 的 `agent.tools.optional` 默认关闭 `render_echart`、`render_antv_chart`、
`bash_start`、`bash_wait`、`bash_stop`、`lsp`、`todowrite`、`todoread` 和 `task`。
`now` 也默认关闭；需要当前时间时可使用同步 `bash`，或单独开启 `now-enabled`。
每项均有独立的 `*-enabled` 开关，可按需开启；异步 Bash 三项建议一起开启。
关闭 `task` 时同一 Talent 的 `multitask` 也不可用。基础文件、同步 `bash`
和 Web 工具仍按各自原有配置工作。工具配置在引擎创建时生效，更改后需重启后端。

`agent.tools.file-profile` 默认为 `standard`，提供专用 `ls/glob/grep/read/edit/write`。
试用 `shell-search` 时，模型用同步 `bash` 做目录列举与代码搜索，
仍使用专用 `read`、`edit`、`write` 处理文件正文及修改。
试验配置将 Bash 可见结果提高到 64,000 字符，减少搜索结果落盘后的恢复调用。
这是可回退的工具集实验，不代表已经证实更省 Token；在 Windows 上 Bash 工具实际运行的
shell 可能是 PowerShell，搜索命令须按实际环境编写。`shell-search` 没有跨所有历史归档的
`grep(path="@tool-results")`；已知 ID 的归档仍可用 `read` 查看。比较两种模式时应同时看
总 Token、工具调用次数、任务完成率和误改率。

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
| `accounting` | `inclusive` 表示 promptTokens 包含缓存读写；`exclusive` 表示普通输入与缓存读写分别报告 |
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

Windows 启动时默认要求 PowerShell 7（`pwsh.exe` 在服务进程的 `PATH` 中）；
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
| `/api/automations/*` | Loop/Goal 创建、触发和状态控制 |
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
