# Web 与 IDEA 双客户端集成

## 请求与执行边界

Solon Web 保持 REST / SSE 接口。普通浏览器使用完整 React 工作台，IDEA 在 JCEF 中加载
`/?host=idea&userId=...&workspaceId=...`，复用聊天、工具展示、会话、审批与模型组件。
`frontend/src/host.js` 封装固定原生操作；Kotlin 通过 JBCefJSQuery 接收请求并在 EDT 调度编辑器操作。

IDEA 用后台 HTTP 请求测试连接和注册工作区，React 用同源 fetch 调用普通 API 与 POST SSE。
请求头 `X-Workspace-Id` 在 UserWorkspaceService 的 HTTP 入口选择上下文内解析，属于当前请求，
不会改写其他客户端的选择。不带该头的旧客户端仍使用持久化的默认激活目录。

工作区解析必须检查用户注册表的归属。引擎缓存键为 `userId:workspaceId`；切换目录不再丢弃已有引擎。
后台流式执行及自动审批续跑从会话元数据解析 workspaceId，不依赖请求线程或后续的用户目录切换。
沙箱仍是用户设置，对该用户所有缓存引擎生效；MCP 和自定义 Agent 维持原有服务级语义。

## 兼容性增量

| 接口 | 新增行为 |
|---|---|
| `GET /api/health` | 无模型调用，返回名称、protocolVersion=1、工作区头名称 |
| `POST /api/workspaces` | 可选表单 `activate=false`，注册目录而不改默认激活目录；旧请求默认激活 |
| 用户工作区相关 API | 可选 `X-Workspace-Id` 请求头；旧请求无头保持原有行为 |
| `POST /api/chat/stream` | 可选 `context` 附件列表：path、content、language、startLine、endLine |
| `GET /api/sessions/status?sessionId=...` | 校验会话用户归属，返回 active 和待审批 hitl JSON 字符串或 null |

上下文附加为明确标注的编辑器快照，后端工具继续读磁盘；路径必须相对项目，附件数量/长度有上限。
两端会话和审批属于同一用户与工作区。ActiveRunRegistry 对聊天和审批恢复都先占位，避免重复启动和并发审批。
HTTP 轮询只更新会话状态；空闲时按更新时间刷新历史并恢复审批。首版没有跨端实时事件订阅或事件回放。

## 文件同步和生命周期

IDEA 的自动引用监听当前标签页、选区和文档变更，向 React 发送 `udata:reference` 路径与行范围。
输入区的“引用”开关沿用网页设置：开启时展示当前文件引用，选区非空时展示
`@项目相对路径#L起始行-L结束行`；发送时通过 `getReference` 再取最新位置。选区的结束偏移是开区间，
换行处结束的选区不会多计下一行。关闭开关不会删除手动引用或既有代码快照附件。

文件树、编辑器标签页及代码编辑区的右键动作从菜单目标获取文件，经真实项目根目录校验后发送
`udata:references-available`，提示输入框拉取引用队列。页面通过 `getPendingReferences` 读取、插入后
通过 `ackReferences` 确认；握手前排队的引用不会丢失，确认失败时重试且不重复插入。
手动引用按输入框失焦前保存的光标/选区插入，支持替换占位文字与分次引用多处文件或代码段；
文件使用 `@路径`，选区使用 `@路径#L起始行-L结束行`。0.1.4 起右键发送不强制换行，
旧版的 `\n@路径#L起始行-L结束行\n` 载荷仍原样保留。右键“发送到 UData Harness”也统一到文本引用。
文件树支持多选，标签页使用右击的文件；没有会话时自动创建会话并展示输入框。
项目关闭时释放监听器、定时器及排队引用。

0.1.5 起，IDEA 输入框将原生引用的完整值登记为标签元数据，在可编辑文本中使用不可编辑的独立 span 展示。
光标位置按原始引用文本的长度计算，兼容原有光标插入和补全；复制、剪切、粘贴及发送均使用纯文本格式。
包含空格的路径及同一文件的不同范围可分别标记，标签不会作为 HTML 传给后端。

发送前插件询问是否保存项目中的未保存文档。任务结束或手动同步时，有未保存文档则保留内容并提示处理，
必要时显示原生 Diff；没有未保存文档才递归刷新项目 VFS。新旧磁盘版本的逐项补丁审阅尚未实现。

插件只连接既有本机 HTTP 服务，不拥有服务进程。UI 只接受指定插件页面的桥接请求，主页面导航受到限制，
路径按项目真实根目录检查，拒绝越界路径和符号链接逃逸。关闭项目释放 JCEF 与查询对象。

## 验证

- `mvn test`：旧测试与新增的用户/工作区隔离、引擎缓存隔离、编辑器上下文兼容及长度/路径边界。
- `npm.cmd --prefix frontend run check`、`npm.cmd --prefix frontend run build`。
- `idea-plugin/gradlew.bat buildPlugin verifyPluginStructure`：Kotlin 编译、插桩、ZIP 和插件结构。
- `node --test frontend/test/ideaReferences.test.js idea-plugin/test/bridge.test.cjs`：引用格式、明确引用去重、桥接回复与超时。
- 独立临时数据目录的 HTTP 联调：同用户注册两个工作区、各创建会话，分别查询只得到各自会话，查询 idle 状态。
- 浏览器检查 Web 与 IDEA 模式；不等价于实际 IDEA 中的 JCEF/编辑器端到端验收。

安装步骤见 [插件 README](../idea-plugin/README.md)。
# IDEA 内嵌页面的确认与输入

会话重命名、删除、完整权限确认、关闭沙箱确认，以及切换用户和工作区的提示使用页面内对话框，避免依赖 JCEF 可能抑制的 `window.confirm` / `window.prompt`。确认后才发送原有 HTTP 请求；取消或按 Escape 不修改状态。重命名会预填并选中原名称，支持 Enter 提交。网页端也使用同一套交互。

在禁用原生弹窗的本地模拟 IDEA 页面中，已验证沙箱双向切换、权限双向切换、重命名及取消保持状态。此验证不代表在实际 IDEA 中完成了操作测试。

IDEA 工具栏的“历史会话”和网页会话侧栏的“管理 / 清理”打开同一管理列表。支持逐条删除、勾选后批量删除、全选以及清理所有会话；清理范围为当前用户当前工作区，删除前使用页面内确认框提示数量和不可恢复性。运行中的会话禁用删除，当前打开的会话被删除后回到新任务页面。批量操作复用已有单会话删除接口，逐条处理；部分失败时保留失败的会话并显示错误和成功数量。
