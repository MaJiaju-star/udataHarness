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
- 运行时 LSP 语言服务器配置，用于定义、引用和诊断查询；
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
