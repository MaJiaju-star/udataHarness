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
- 后台 Skill 包仓库、ZIP 整包导入和按用户生效/停用；
- Harness `task`/`multitask` 与自定义 Subagent；
- 定时 Loop 和带状态/轮次预算的 Goal。

架构与安全边界见 [docs/architecture.md](docs/architecture.md)。

## IntelliJ IDEA 插件

网页工作台继续独立使用；新增 IDEA 侧栏通过 HTTP/SSE 连接同一个本地 Solon 服务，支持项目绑定、
共享会话、审批、选区/文件上下文、文件定位和修改同步。构建与安装步骤见
[idea-plugin/README.md](idea-plugin/README.md)，双客户端协议见 [docs/idea-integration.md](docs/idea-integration.md)。

## Requirements

- JDK 17+
- Maven 3.9+
- Solon AI 支持的模型端点

项目跟随本仓库 `solon-ai-main` / `soloncode-main`，使用
Solon Framework 使用 `4.1.0`，Solon AI 使用 `4.1.0-SNAPSHOT`。首次构建
Solon AI 依赖时需访问 Sonatype Snapshot 仓库。

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

会话消息归档与模型上下文分开保存：`*.messages.ndjson` 保留原始记录，
`*.model-context.json` 原子保存当前摘要、保留消息、压缩代数和已消费的归档位置。
新请求沿用这个模型视图并追加新消息，重启后也不重新加载已总结的原始历史。
旧会话首次启用时从完整归档初始化；原来 200～400 条滑动窗口的参数已移除。
`agent.context.session-window-size` 保留框架兼容性：正数启用历史加载，0 禁用，
文件会话不再把该值当作消息条数上限。
`compression-max-context-ratio` 默认 0.75，按实际所选模型窗口估算整体上下文压力；
`compression-max-messages` 默认 1000，`compression-message-trigger-factor` 默认 2.0，
作为大量短消息的辅助守卫：超过约 2000 条后摘要旧消息并保留至多约 1000 条近期消息。
跨轮历史允许参与摘要，系统指令独立构建；工具调用组由框架保持完整。
如果裁剪没有生成新摘要，则恢复原上下文并报错，避免无摘要淘汰历史。
审批恢复继续原运行，临时等待审批消息从模型视图同步清理。
摘要生成会调用已配置模型；压缩节点仍可能重建前缀缓存，实际收益需通过 API 用量验证。

长周期任务管理默认启用：新工具结果先完整归档，再提供有界片段；重复文件读取在同版本全文
仍处于活跃上下文时只追加引用。主 Agent 可使用任务状态、阶段检查点、证据检索/恢复工具，
在阶段结束时批量收敛上下文。配置、HTTP 观测接口、验证方法与当前限制见
[docs/context-management.md](docs/context-management.md)。

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
