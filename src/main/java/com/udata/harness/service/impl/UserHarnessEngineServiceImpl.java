package com.udata.harness.service.impl;

import com.udata.harness.common.support.AntVChartTool;
import com.udata.harness.common.domain.CapabilityMode;
import com.udata.harness.common.support.ChartTool;
import com.udata.harness.common.context.ContextArtifactStore;
import com.udata.harness.common.context.CacheAlignedCompressionInterceptor;
import com.udata.harness.common.context.ContextManagementOptions;
import com.udata.harness.common.context.ContextCacheConfiguration;
import com.udata.harness.common.context.ContextTelemetryInterceptor;
import com.udata.harness.common.context.ContextUsage;
import com.udata.harness.common.context.ToolResultContextInterceptor;
import com.udata.harness.repository.SessionRepository;
import com.udata.harness.repository.UsageAuditRepository;
import org.noear.solon.ai.AiUsage;
import com.udata.harness.service.UserHarnessEngineService;
import com.udata.harness.service.UserWorkspaceService;
import com.udata.harness.service.ModelConfigurationService;
import com.udata.harness.common.support.ActiveRunRegistry;
import org.noear.solon.ai.agent.react.intercept.HITL;
import org.noear.solon.Solon;
import org.noear.solon.Utils;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.agent.ToolName;
import org.noear.solon.ai.harness.permission.PermissionRule;
import org.noear.solon.ai.mcp.client.McpServerParameters;
import org.noear.solon.ai.talents.lsp.LspServerParameters;
import org.noear.solon.ai.talents.cli.BoundedReadTool;
import org.noear.solon.ai.talents.cli.TerminalTalentProxy;
import org.noear.solon.ai.talents.cli.SafeMutationTalent;
import org.noear.solon.ai.talents.cli.SessionFileVersions;
import org.noear.solon.ai.talents.cli.BoundedLsTool;
import org.noear.solon.ai.talents.cli.BoundedGlobTool;
import org.noear.solon.ai.talents.cli.BoundedGrepTool;
import org.noear.solon.ai.talents.cli.BashCaptureTalent;
import org.noear.solon.ai.talents.mount.Mount;
import org.noear.solon.ai.talents.mount.MountType;
import org.noear.solon.ai.talents.mount.source.FileMountSource;
import org.noear.solon.annotation.Component;
import org.noear.solon.annotation.Inject;
import org.noear.solon.core.Props;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 用户级 {@link HarnessEngine} 构建器与缓存。
 *
 * <p>每个用户拥有独立工作目录、SessionProvider 和技能挂载；共享 Subagent、
 * MCP 配置会同步到所有已创建引擎。computeIfAbsent 保证同一用户只构建一次。</p>
 */
@Component
public class UserHarnessEngineServiceImpl implements UserHarnessEngineService {
    @Inject private ModelConfigurationService modelConfigurations;
    @Inject private ActiveRunRegistry activeRuns;
    private final Map<String, String> modelRevisions = new ConcurrentHashMap<>();

    @Override
    public boolean modelsPending(String userId) {
        if (modelConfigurations == null) return false;
        String prefix = UserWorkspaceService.requireUserId(userId) + ":";
        String revision = modelConfigurations.revision(userId);
        return engines.keySet().stream().anyMatch(key -> key.startsWith(prefix) && !revision.equals(modelRevisions.get(key))) && modelsBusy(userId);
    }

    private boolean modelsBusy(String userId) {
        return sessionRepository.list(userId).stream().anyMatch(session -> activeRuns.isActive(session.getSessionId())
                || sessionRepository.getSession(userId, session.getSessionId()).isPending()
                || !HITL.getPendingTasks(sessionRepository.getSession(userId, session.getSessionId())).isEmpty());
    }
    /**
     * 用户设置文件中记录沙箱开关的属性键。
     */
    private static final String SANDBOX_ENABLED_KEY = "sandboxEnabled";

    /**
     * 共享的图表渲染工具实例，供各用户引擎注册（无状态，可复用）。
     */
    private static final ChartTool CHART_TOOL = new ChartTool();

    /**
     * 共享的 AntV 图表渲染工具实例，供各用户引擎注册（无状态，可复用）。
     */
    private static final AntVChartTool ANTV_CHART_TOOL = new AntVChartTool();

    /**
     * 会话仓储，用于按用户装配 SessionProvider 与恢复历史消息。
     */
    @Inject
    private SessionRepository sessionRepository;

    @Inject
    private UsageAuditRepository usageAuditRepository;
    @Inject private com.udata.harness.repository.ProjectInstructionSettings projectInstructionSettings;

    /**
     * 用户工作区服务，用于解析用户当前激活的工作目录作为引擎工作区。
     */
    @Inject
    private UserWorkspaceService workspaces;

    /**
     * 应用数据目录，用户级数据（设置、MCP 配置等）的根路径。
     */
    @Inject("${agent.data-dir:./data}")
    private String dataDir;

    /**
     * 用户工作区内 Solon 约定的配置目录名，技能与设置都置于其下。
     */
    @Inject("${agent.harness-home:.soloncode}")
    private String harnessHome;

    /**
     * 引擎系统提示词，可配置以约束智能体行为。
     */
    @Inject("${agent.system-prompt:You are a careful coding agent. Inspect the workspace, explain material changes, and verify your work.}")
    private String systemPrompt;

    /**
     * 单次运行的 ReAct 最大轮次上限，防止死循环。
     */
    @Inject("${agent.max-turns:100}")
    private int maxTurns;

    /**
     * 沙箱默认开关，用户未显式设置偏好时采用该值。
     */
    @Inject("${agent.sandbox.enabled:true}")
    private boolean sandboxEnabledByDefault = true;

    /**
     * Web 工具总开关，关闭后 websearch/codesearch/webfetch 一并禁用（适合内网）。
     */
    @Inject("${agent.tools.web.enabled:true}")
    private boolean webToolsEnabled = true;

    /**
     * Web 搜索工具开关。
     */
    @Inject("${agent.tools.web.websearch-enabled:true}")
    private boolean webSearchEnabled = true;

    /**
     * 代码检索工具开关。
     */
    @Inject("${agent.tools.web.codesearch-enabled:true}")
    private boolean codeSearchEnabled = true;

    /**
     * 网页抓取工具开关。
     */
    @Inject("${agent.tools.web.webfetch-enabled:true}")
    private boolean webFetchEnabled = true;

    /** Optional tools are off by default; each may be enabled in model.yml. */
    @Inject("${agent.tools.optional.render-echart-enabled:false}")
    private boolean renderEchartEnabled;

    @Inject("${agent.tools.optional.render-antv-chart-enabled:false}")
    private boolean renderAntvChartEnabled;

    @Inject("${agent.tools.optional.bash-start-enabled:false}")
    private boolean bashStartEnabled;

    @Inject("${agent.tools.optional.bash-wait-enabled:false}")
    private boolean bashWaitEnabled;

    @Inject("${agent.tools.optional.bash-stop-enabled:false}")
    private boolean bashStopEnabled;

    @Inject("${agent.tools.optional.lsp-enabled:false}")
    private boolean lspToolEnabled;

    @Inject("${agent.tools.optional.todowrite-enabled:false}")
    private boolean todoWriteEnabled;

    @Inject("${agent.tools.optional.todoread-enabled:false}")
    private boolean todoReadEnabled;

    @Inject("${agent.tools.optional.task-enabled:false}")
    private boolean taskToolEnabled;

    @Inject("${agent.tools.optional.now-enabled:false}")
    private boolean nowToolEnabled;

    /**
     * 每次新请求从持久化会话中恢复的最近消息数。
     *
     * <p>该值是消息条数而不是对话轮数；工具调用及其结果也会各占一条消息。</p>
     */
    @Inject("${agent.context.session-window-size:200}")
    private int sessionWindowSize;

    /**
     * 单次 ReAct 运行中，非初始消息超过该数量时触发上下文压缩。
     *
     * <p>设置得低于 Token 阈值可防止大量短工具消息无限累积。</p>
     */
    @Inject("${agent.context.compression-max-messages:160}")
    private int compressionMaxMessages;

    /**
     * 达到模型上下文窗口的该比例时触发压缩，取值范围为 0 到 1。
     */
    @Inject("${agent.context.compression-max-context-ratio:0.75}")
    private double compressionMaxContextRatio;

    @Inject("${agent.context.compaction.mode:cache-aligned}")
    private String compactionMode = "cache-aligned";

    @Inject("${agent.context.compaction.retain-ratio:0.16}")
    private double compactionRetainRatio = 0.16D;

    @Inject("${agent.context.compaction.threshold-ratio:0.8}")
    private double compactionThresholdRatio = 0.8D;

    @Inject("${agent.context.compaction.output-reserve:65536}")
    private int compactionOutputReserve = 65536;

    @Inject("${agent.context.tool-results.enabled:true}")
    private boolean contextToolResultsEnabled = true;

    @Inject
    private ContextCacheConfiguration contextCacheConfiguration;

    @Inject("${agent.context.tool-results.single-max-chars:64000}")
    private int contextSingleMaxChars = 64000;

    @Inject("${agent.context.tool-results.read-visible-chars:160000}")
    private int contextReadVisibleChars = 160000;

    @Inject("${agent.context.tool-results.bash-visible-chars:20000}")
    private int contextBashVisibleChars = 20000;

    @Inject("${agent.tools.bash.max-log-bytes:268435456}")
    private long bashMaxLogBytes = 268435456L;

    @Inject("${agent.context.tool-results.error-visible-chars:16000}")
    private int contextErrorVisibleChars = 16000;

    @Inject("${agent.context.tool-results.batch-max-chars:256000}")
    private int contextBatchMaxChars = 256000;

    @Inject("${agent.context.tool-results.preview-chars:4000}")
    private int contextPreviewChars = 4000;

    @Inject("${agent.context.tool-results.restore-max-chars:16000}")
    private int contextRestoreMaxChars = 16000;

    /**
     * 模型声明的物理上下文窗口，用于让压缩器按模型能力计算安全余量。
     */
    @Inject("${agent.model.context-length:1000000}")
    private long modelContextLength;

    /**
     * 模型请求总尝试次数，包含第一次请求。
     */
    @Inject("${agent.model.retry.max-attempts:3}")
    private int modelMaxAttempts;

    /**
     * 默认模型配置名（用于 UI 展示与选择）。
     */
    @Inject("${agent.model.default:deepseek-flash}")
    private String modelName;

    /**
     * 模型 API 基础地址。
     */
    @Inject("${agent.model.api-url:https://api.deepseek.com}")
    private String apiUrl;

    /**
     * 模型 API 密钥，优先由环境变量 {@code AGENT_API_KEY} 注入。
     */
    @Inject("${agent.model.api-key:}")
    private String apiKey;

    /**
     * 模型提供方协议，默认 OpenAI 兼容。
     */
    @Inject("${agent.model.provider:openai}")
    private String provider;

    /**
     * 实际调用的模型标识。
     */
    @Inject("${agent.model.model:deepseek-v4-flash}")
    private String model;

    /**
     * 已构建引擎缓存，按用户 Id 隔离，保证同一用户只初始化一次。
     */
    private final Map<String, HarnessEngine> engines = new ConcurrentHashMap<>();

    /**
     * 共享的 MCP 服务定义，变更时同步到所有已创建引擎。
     */
    private final Map<String, McpServerParameters> mcpServers = new ConcurrentHashMap<>();

    /**
     * 服务级 LSP 定义。各工作区引擎分别使用自己的工作目录启动语言服务器。
     */
    private final Map<String, LspServerParameters> lspServers = new ConcurrentHashMap<>();

    /**
     * 各用户沙箱开关偏好缓存，来源于用户设置文件。
     */
    private final Map<String, Boolean> sandboxPreferences = new ConcurrentHashMap<>();

    /**
     * 获取用户专属引擎；首次访问时原子创建并缓存。
     *
     * <p>用户标识先经过目录安全校验。缓存键使用规范化后的 userId，保证并发请求不会
     * 为同一用户创建多个 HarnessEngine，也防止跨用户复用会话与工作目录。</p>
     *
     * @param userId 原始用户标识
     * @return 该用户专属的 HarnessEngine（首次调用时创建）
     */
    public HarnessEngine get(String userId) {
        String safeUserId = UserWorkspaceService.requireUserId(userId);
        return get(safeUserId, workspaces.getActive(safeUserId).getWorkspaceId());
    }

    @Override
    public HarnessEngine get(String userId, String workspaceId) {
        return get(userId, workspaceId, CapabilityMode.STANDARD);
    }

    @Override
    public synchronized HarnessEngine get(String userId, String workspaceId, String capabilityMode) {
        if (modelConfigurations != null) synchronized (modelConfigurations) {
            return getConfigured(userId, workspaceId, capabilityMode);
        }
        return getConfigured(userId, workspaceId, capabilityMode);
    }

    private HarnessEngine getConfigured(String userId, String workspaceId, String capabilityMode) {
        String mode = CapabilityMode.require(capabilityMode);
        String safeUserId = UserWorkspaceService.requireUserId(userId);
        com.udata.harness.common.domain.WorkspaceMetadata workspace = workspaces.resolve(safeUserId, workspaceId);
        String key = safeUserId + ":" + workspace.getWorkspaceId() + ":" + mode;
        HarnessEngine engine = engines.get(key);
        String revision = modelConfigurations == null ? "legacy" : modelConfigurations.revision(safeUserId);
        if (engine == null) {
            engine = build(safeUserId, workspace.getWorkspaceId(), Paths.get(workspace.getPath()), mode);
            engines.put(key, engine); modelRevisions.put(key, revision);
        } else if (modelConfigurations != null && !revision.equals(modelRevisions.get(key)) && !modelsBusy(safeUserId)) {
            List<ChatConfig> configs = modelConfigurations.effective(safeUserId);
            // No active/pending trace can observe this replacement. Avoid restarting MCP/LSP.
            if (configs.isEmpty()) throw new IllegalStateException("请先在后台管理中配置并启用模型");
            if (!configs.isEmpty()) {
                for (ChatConfig config : configs) engine.addModel(config);
                engine.setDefaultModel(modelConfigurations.preferred(safeUserId, "default"));
                java.util.Set<String> names = configs.stream().map(ChatConfig::getNameOrModel).collect(java.util.stream.Collectors.toSet());
                for (ChatConfig old : new ArrayList<>(engine.getModels()))
                    if (!names.contains(old.getNameOrModel())) engine.removeModel(old.getNameOrModel());
            }
            modelRevisions.put(key, revision);
        }
        return engine;
    }

    /**
     * 返回用户持久化的沙箱设置；首次读取时回退到应用默认值。
     *
     * @param userId 目标用户标识
     * @return 该用户当前生效的沙箱开关
     */
    @Override
    public boolean isSandboxEnabled(String userId) {
        String safeUserId = UserWorkspaceService.requireUserId(userId);
        return sandboxPreferences.computeIfAbsent(safeUserId, this::loadSandboxPreference);
    }

    /**
     * 保存用户沙箱设置，并同步到已经创建的 HarnessEngine。
     *
     * @param userId 目标用户标识
     * @param enabled 是否启用沙箱
     */
    @Override
    public void setSandboxEnabled(String userId, boolean enabled) {
        String safeUserId = UserWorkspaceService.requireUserId(userId);
        saveSandboxPreference(safeUserId, enabled);
        sandboxPreferences.put(safeUserId, enabled);
        engines.forEach((key, engine) -> {
            if (key.startsWith(safeUserId + ":")) engine.setSandboxEnabled(enabled);
        });
    }

    /**
     * 清除用户引擎缓存；工作区切换后的下一次访问会重新构建。
     *
     * @param userId 目标用户标识
     */
    @Override
    public void resetUser(String userId) {
        String prefix = UserWorkspaceService.requireUserId(userId) + ":";
        engines.keySet().removeIf(key -> key.startsWith(prefix));
    }

    /**
     * 返回当前进程已经实例化的引擎视图，不会主动为未访问用户创建实例。
     *
     * @return 已缓存引擎集合（实时视图）
     */
    public Collection<HarnessEngine> all() {
        return engines.values();
    }

    /**
     * 刷新指定用户的 Skill、共享 Agent 挂载和主 Agent。
     *
     * <p>刷新只影响能力定义，不替换用户的 SessionProvider 或工作区。</p>
     *
     * @param userId 目标用户标识
     */
    public void refreshUserCapabilities(String userId) {
        String prefix = UserWorkspaceService.requireUserId(userId) + ":";
        engines.forEach((key, engine) -> {
            if (key.startsWith(prefix)) {
                engine.refreshMount("@workspace-skills");
                engine.refreshMount("@shared-agents");
                engine.refreshMainAgent();
            }
        });
    }

    /**
     * 判断某个 Skill 是否已经进入该用户当前引擎的有效能力集合。
     *
     * @param userId 目标用户标识
     * @param skillName 技能名称
     * @return 已加载时为 true
     */
    public boolean isSkillLoaded(String userId, String skillName) {
        return get(userId).getSkills().stream().anyMatch(skill -> skillName.equals(skill.getName()));
    }

    /**
     * 共享 Subagent 定义变化后，刷新所有已实例化引擎的 Agent 挂载。
     */
    public void refreshAgentsForAll() {
        for (HarnessEngine engine : engines.values()) {
            engine.refreshMount("@shared-agents");
            engine.refreshMainAgent();
        }
    }

    /**
     * 保存运行时 MCP 定义并热更新所有现存引擎。
     *
     * <p>尚未创建的用户引擎会在 {@link #build(String, String, Path)} 时读取缓存中的同一份配置。</p>
     *
     * @param name MCP Server 名称
     * @param parameters MCP 连接参数
     */
    public void putMcp(String name, McpServerParameters parameters) {
        mcpServers.put(name, parameters);
        for (HarnessEngine engine : engines.values()) {
            engine.removeMcpServer(name);
            engine.addMcpServer(name, parameters);
        }
    }

    /**
     * 从全局缓存和所有已创建引擎中移除指定 MCP 服务。
     *
     * @param name MCP Server 名称
     */
    public void removeMcp(String name) {
        mcpServers.remove(name);
        engines.values().forEach(engine -> engine.removeMcpServer(name));
    }

    /**
     * 注册或替换 LSP 服务，并更新已经创建的工作区引擎。
     *
     * @param name LSP 服务名称
     * @param parameters 语言服务器启动参数
     */
    @Override
    public void putLsp(String name, LspServerParameters parameters) {
        lspServers.put(name, parameters);
        engines.values().forEach(engine -> {
            engine.removeLspServer(name);
            engine.addLspServer(name, parameters);
        });
    }

    /**
     * 删除 LSP 服务，并更新已经创建的工作区引擎。
     *
     * @param name LSP 服务名称
     */
    @Override
    public void removeLsp(String name) {
        lspServers.remove(name);
        engines.forEach((key, engine) -> {
            engine.removeLspServer(name);
            if ("java".equals(name)) {
                int separator = key.indexOf(':');
                int modeSeparator = key.lastIndexOf(':');
                LspServerParameters bundled = BundledJavaLsp.forWorkspace(
                        dataDir, key.substring(0, separator), key.substring(separator + 1, modeSeparator));
                if (bundled != null) {
                    engine.addLspServer("java", bundled);
                }
            } else if ("typescript".equals(name)) {
                LspServerParameters bundled = BundledTypeScriptLsp.forWorkspace();
                if (bundled != null) {
                    engine.addLspServer("typescript", bundled);
                }
            }
        });
    }

    /**
     * 构建完整的用户级 HarnessEngine。
     *
     * <p>该方法集中建立以下不变量：</p>
     * <ul>
     *   <li>工作区、Skill 目录和共享 Agent 目录在使用前存在；</li>
     *   <li>会话持久化委托给带用户隔离的 {@link SessionRepository}；</li>
     *   <li>文件、终端、HITL、Subagent 与图表 Tool 在主 Agent 创建时一次注册；</li>
     *   <li>沙箱禁止访问用户主目录，写操作仍受权限规则约束；</li>
     *   <li>已保存的 MCP 与 LSP 定义在引擎对外可见前全部注册。</li>
     * </ul>
     *
     * @param userId 已校验的目标用户标识
     * @param workspaceId 已注册工作区标识
     * @return 完整初始化的用户级 HarnessEngine
     */
    private HarnessEngine build(String userId, String workspaceId, Path workspace, String capabilityMode) {
        Path skills = workspace.resolve(".soloncode").resolve("skills");
        Path agents = Paths.get(dataDir).toAbsolutePath().normalize().resolve("capabilities").resolve("agents");
        try {
            Files.createDirectories(skills);
            Files.createDirectories(agents);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create Harness capability directories", e);
        }

        ContextManagementOptions contextOptions = contextToolResultsEnabled
                ? new ContextManagementOptions(contextSingleMaxChars, contextBatchMaxChars,
                    contextPreviewChars, contextRestoreMaxChars, contextReadVisibleChars,
                    contextBashVisibleChars, contextErrorVisibleChars) : null;
        ContextArtifactStore contextStore = new ContextArtifactStore(Paths.get(dataDir).resolve("users").resolve(userId)
                    .resolve("context-artifacts").resolve(workspaceId));
        SessionFileVersions fileVersions = new SessionFileVersions();

        //1. 工具、沙箱、HITL 和 Subagent 在引擎创建时一次性启用；会话级 cwd
        // 仍由 ChatService 每次运行时写入 toolContext。
        HarnessEngine.Builder builder = HarnessEngine.of(workspace.toString(), harnessHome)
                .systemPrompt(systemPrompt + (contextToolResultsEnabled
                        ? "\nLarge tool results may be saved as read-only historical snapshots at @tool-results/<id>.txt. "
                        + (CapabilityMode.ECONOMY.equals(capabilityMode)
                        ? "Use read(file_path=\"@tool-results/<id>.txt\") when the artifact ID is known; aggregate artifact search is unavailable in this profile. "
                        : "Use grep(path=\"@tool-results\") to locate archived evidence and read(file_path=\"@tool-results/<id>.txt\") to inspect it only when needed. ")
                        + "Snapshots are not current workspace files; revalidate changed sources."
                        : "")
                        + (CapabilityMode.ECONOMY.equals(capabilityMode)
                        ? "\nFile search profile: use bash for directory listing and file/content search. Prefer rg --files and rg -n when available; otherwise use commands appropriate to the actual shell. Bound command output. Use read for file contents and edit/write for mutations."
                        : ""))
                .maxTurns(maxTurns)
                .sessionWindowSize(sessionWindowSize)
                .modelRetries(modelMaxAttempts)
                .compressionThreshold(compressionMaxMessages, compressionMaxContextRatio)
                .sessionProvider(sessionRepository)
                .toolsAdd(CapabilityMode.SUPER.equals(capabilityMode)
                        ? Arrays.asList(ToolName.TOOL_ALL_FULL.getName(), ToolName.TOOL_HITL.getName())
                        : CapabilityMode.ECONOMY.equals(capabilityMode)
                        ? Arrays.asList(ToolName.TOOL_PI.getName(), "ls", ToolName.TOOL_HITL.getName())
                        : Arrays.asList(ToolName.TOOL_PI.getName(), "ls", "glob", "grep", ToolName.TOOL_HITL.getName()))
                .disallowedToolsAdd(disabledWebTools())
                .disallowedToolsAdd(disabledOptionalTools())
                .disallowedToolsAdd(CapabilityMode.SUPER.equals(capabilityMode)
                        ? Arrays.asList("memory") // Request-scoped UdataMemory Talent enforces user/project isolation and the per-user switch.
                        : Arrays.asList("verify", "now", "skillread", "skillrefresh",
                                "task", "multitask", "subagent", "lsp", "todoread", "todowrite",
                                "bash_start", "bash_wait", "bash_stop", "websearch", "codesearch", "webfetch"))
                .disallowedToolsAdd(disabledFileTools(capabilityMode))
                .extensionAdd((engine, agentName, agentBuilder) -> {
                    agentBuilder.defaultInterceptorAdd(Integer.MIN_VALUE,
                            new com.udata.harness.common.support.GatewaySessionHeaderInterceptor());
                    com.udata.harness.common.support.WorkspaceInstructionsInterceptor instructions =
                            new com.udata.harness.common.support.WorkspaceInstructionsInterceptor(workspace,
                                    () -> projectInstructionSettings == null ? "auto" : projectInstructionSettings.mode(userId, workspaceId));
                    instructions.install(agentBuilder);
                    // Only replace read when AgentFactory granted the built-in read to this agent.
                    agentBuilder.modelOptions(options -> {
                        if (!nowToolEnabled || !CapabilityMode.SUPER.equals(capabilityMode)) {
                            // AgentFactory attaches ClockTalent unconditionally, outside the
                            // disallowed-tools check; remove it before talents activate.
                            options.talents().removeIf(item ->
                                    item.target instanceof org.noear.solon.ai.talents.cli.ClockTalent);
                        }
                        java.util.Set<String> terminalTools = options.talents().stream()
                                .filter(item -> item.target instanceof TerminalTalentProxy)
                                .flatMap(item -> item.target.getTools(null).stream())
                                .map(tool -> tool.name())
                                .collect(java.util.stream.Collectors.toSet());
                        if (todoReadEnabled != todoWriteEnabled) {
                            boolean todoGranted = options.talents().removeIf(
                                    item -> item.target instanceof org.noear.solon.ai.talents.cli.TodoTalent);
                            if (todoGranted) {
                                options.toolAdd(engine.getTodoTalent().getToolAry(
                                        todoReadEnabled ? "todoread" : "todowrite"));
                            }
                        }
                        if (terminalTools.contains("read")) {
                            // Talents activate in insertion order; the later read replaces Solon's built-in one.
                            options.talentAdd(new BoundedReadTool(engine.getTerminalTalent(),
                                    engine.getWorkspace(), contextStore, fileVersions));
                        }
                        if (terminalTools.contains("ls")) {
                            options.talentAdd(new BoundedLsTool(engine.getTerminalTalent(), engine.getWorkspace()));
                        }
                        if (terminalTools.contains("glob")) {
                            options.talentAdd(new BoundedGlobTool(engine.getTerminalTalent(), engine.getWorkspace()));
                        }
                        if (terminalTools.contains("grep")) {
                            options.talentAdd(new BoundedGrepTool(engine.getTerminalTalent(), engine.getWorkspace(), contextStore));
                        }
                        if (terminalTools.contains("bash")) {
                            options.talentAdd(new BashCaptureTalent(engine.getTerminalTalent(), contextStore,
                                    contextBashVisibleChars, bashMaxLogBytes, engine.getWorkspace(),
                                    CapabilityMode.SUPER.equals(capabilityMode)));
                        }
                        if (terminalTools.contains("write") || terminalTools.contains("edit")) {
                            options.talentAdd(new SafeMutationTalent(engine.getTerminalTalent(),
                                    engine.getWorkspace(), terminalTools.contains("write"),
                                    terminalTools.contains("edit"), fileVersions, contextStore));
                        }
                    });
                    agentBuilder.defaultInterceptorAdd(-1000, fileVersions);
                    if (CapabilityMode.SUPER.equals(capabilityMode) && renderEchartEnabled) agentBuilder.defaultToolAdd(CHART_TOOL);
                    if (CapabilityMode.SUPER.equals(capabilityMode) && renderAntvChartEnabled) agentBuilder.defaultToolAdd(ANTV_CHART_TOOL);
                    if (contextOptions != null) {
                        agentBuilder.defaultInterceptorAdd(1000,
                                new ToolResultContextInterceptor(contextStore, contextOptions, null, workspace, contextCacheConfiguration));
                        if (contextCacheConfiguration != null) {
                            agentBuilder.defaultInterceptorAdd(Integer.MAX_VALUE,
                                    new ContextTelemetryInterceptor(contextStore, contextCacheConfiguration));
                        }
                    }
                    if (contextCacheConfiguration != null && contextCacheConfiguration.prefixDiagnosticsEnabled)
                        agentBuilder.defaultInterceptorAdd(Integer.MAX_VALUE,
                                new com.udata.harness.common.context.FinalRequestPrefixInterceptor(contextStore, contextCacheConfiguration));
                })
                .sandboxEnabled(isSandboxEnabled(userId))
                .sandboxAllowUserHome(false)
                .sandboxSystemRestrict(true)
                // BashToolStrategy 会优先放行只读命令、拒绝系统级危险命令；
                // 对其余可能修改工作区的命令使用此兜底规则进入人工审批。
                .permissionRuleAdd(PermissionRule.ask("bash"))
                .permissionRuleAdd(PermissionRule.ask("verify"))
                .hitlEnabled(true)
                .subagentEnabled(CapabilityMode.SUPER.equals(capabilityMode) && taskToolEnabled)
                .bashAsyncEnabled(CapabilityMode.SUPER.equals(capabilityMode)
                        && (bashStartEnabled || bashWaitEnabled || bashStopEnabled));

        if ("cache-aligned".equals(compactionMode)) {
            builder.compressionInterceptor(new CacheAlignedCompressionInterceptor(
                    compressionMaxMessages, compactionThresholdRatio,
                    compactionRetainRatio, compactionOutputReserve,
                    new CacheAlignedCompressionInterceptor.CompactionObserver() {
                        @Override
                        public void record(String sessionId, String model, long beforeTokens,
                                           long afterTokens, boolean cacheAligned, AiUsage usage) {
                            if (usageAuditRepository != null) {
                                usageAuditRepository.recordCompaction(userId, workspaceId, sessionId,
                                        model, beforeTokens, afterTokens, cacheAligned, usage,
                                        contextCacheConfiguration == null ? null : ContextUsage.normalize(usage,
                                                contextCacheConfiguration.policy(model).prices));
                            }
                        }

                        @Override
                        public void failed(String sessionId, String model, String code) {
                            if (usageAuditRepository != null) {
                                usageAuditRepository.recordCompactionFailure(userId, workspaceId,
                                        sessionId, model, code);
                            }
                        }
                    }));
        }

        //2. 从 app.yml 注册模型列表；未配置列表时保留旧单模型配置兼容性。
        List<ChatConfig> modelConfigs = modelConfigurations == null ? loadModelConfigs() : modelConfigurations.effective(userId);
        if (modelConfigs.isEmpty()) throw new IllegalStateException("请先在后台管理中配置并启用模型");
        for (ChatConfig chatConfig : modelConfigs) {
            builder.modelAdd(chatConfig);
        }
        String defaultModel = modelConfigurations != null ? modelConfigurations.preferred(userId, "default") : modelConfigs.stream()
                .anyMatch(item -> modelName.equals(item.getNameOrModel()))
                ? modelName : modelConfigs.get(0).getNameOrModel();
        HarnessEngine engine = builder.defaultModel(defaultModel).build();

        //3. 用户技能使用独立可写挂载，共享 Agent 使用服务级只读语义目录。
        engine.addMount(Mount.builder()
                .alias("@workspace-skills")
                .description("Activated Skills for " + userId)
                .type(MountType.SKILLS)
                .source(FileMountSource.of(skills))
                .primary(true)
                .writeable(true)
                .build());
        engine.addMount(Mount.builder()
                .alias("@shared-agents")
                .description("Service-wide Subagent library")
                .type(MountType.AGENTS)
                .source(FileMountSource.of(agents))
                .primary(true)
                .writeable(true)
                .build());
        mcpServers.forEach(engine::addMcpServer);
        lspServers.forEach(engine::addLspServer);
        if (!lspServers.containsKey("java")) {
            LspServerParameters bundled = BundledJavaLsp.forWorkspace(dataDir, userId, workspaceId);
            if (bundled != null) {
                engine.addLspServer("java", bundled);
            }
        }
        if (!lspServers.containsKey("typescript")) {
            LspServerParameters bundled = BundledTypeScriptLsp.forWorkspace();
            if (bundled != null) {
                engine.addLspServer("typescript", bundled);
            }
        }
        return engine;
    }

    /**
     * 根据总开关和细粒度开关生成不向模型暴露的网络工具列表。
     *
     * @return 需要屏蔽的 Web 工具名列表
     */
    List<String> disabledWebTools() {
        List<String> tools = new ArrayList<>();
        if (!webToolsEnabled || !webSearchEnabled) {
            tools.add(ToolName.TOOL_WEBSEARCH.getName());
        }
        if (!webToolsEnabled || !codeSearchEnabled) {
            tools.add(ToolName.TOOL_CODESEARCH.getName());
        }
        if (!webToolsEnabled || !webFetchEnabled) {
            tools.add(ToolName.TOOL_WEBFETCH.getName());
        }
        return tools;
    }

    /** Names checked by AgentFactory before it registers built-in tool groups. */
    List<String> disabledOptionalTools() {
        List<String> tools = new ArrayList<>();
        if (!bashStartEnabled) tools.add("bash_start");
        if (!bashWaitEnabled) tools.add("bash_wait");
        if (!bashStopEnabled) tools.add("bash_stop");
        if (!lspToolEnabled) tools.add(ToolName.TOOL_LSP.getName());
        if (!todoReadEnabled) tools.add("todoread");
        if (!todoWriteEnabled) tools.add("todowrite");
        if (!todoReadEnabled && !todoWriteEnabled) tools.add(ToolName.TOOL_TODO.getName());
        if (!taskToolEnabled) {
            tools.add(ToolName.TOOL_TASK.getName());
            tools.add("subagent");
        }
        return tools;
    }

    /** Economy omits structured content search; standard and super retain it. */
    List<String> disabledFileTools(String capabilityMode) {
        if (CapabilityMode.ECONOMY.equals(capabilityMode)) return Arrays.asList("glob", "grep");
        return java.util.Collections.emptyList();
    }

    /**
     * 从用户设置文件读取沙箱开关。
     *
     * @param userId 目标用户标识
     * @return 已保存的开关；未设置时回退应用默认值
     */
    private boolean loadSandboxPreference(String userId) {
        Properties properties = loadUserSettings(userId);
        return Boolean.parseBoolean(properties.getProperty(
                SANDBOX_ENABLED_KEY, String.valueOf(sandboxEnabledByDefault)));
    }

    /**
     * 将沙箱开关写入用户设置文件。
     *
     * @param userId 目标用户标识
     * @param enabled 是否启用沙箱
     */
    private void saveSandboxPreference(String userId, boolean enabled) {
        Properties properties = loadUserSettings(userId);
        properties.setProperty(SANDBOX_ENABLED_KEY, String.valueOf(enabled));
        Path file = userSettingsFile(userId);
        try {
            Files.createDirectories(file.getParent());
            try (OutputStream output = Files.newOutputStream(file)) {
                properties.store(output, "udata-harness user settings");
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot save user settings", e);
        }
    }

    /**
     * 读取用户设置；文件尚不存在时返回空配置。
     *
     * @param userId 目标用户标识
     * @return 用户设置属性集
     */
    private Properties loadUserSettings(String userId) {
        Properties properties = new Properties();
        Path file = userSettingsFile(userId);
        if (!Files.isRegularFile(file)) {
            return properties;
        }
        try (InputStream input = Files.newInputStream(file)) {
            properties.load(input);
            return properties;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read user settings", e);
        }
    }

    /**
     * 返回位于应用数据目录内的用户设置文件。
     *
     * @param userId 目标用户标识
     * @return {@code <data-dir>/users/<userId>/settings.properties} 绝对路径
     */
    private Path userSettingsFile(String userId) {
        return Paths.get(dataDir).toAbsolutePath().normalize()
                .resolve("users").resolve(userId).resolve("settings.properties");
    }

    /**
     * 从 {@code agent.model.models} 读取模型列表，并让每项继承公共连接配置。
     *
     * <p>列表项支持 name、model、api-url、api-key、provider 和 context-length；其中只有
     * name/model 通常需要逐项配置。</p>
     *
     * @return 从应用配置读取的模型列表
     */
    private List<ChatConfig> loadModelConfigs() {
        return loadModelConfigs(Solon.cfg());
    }

    /**
     * 使用给定属性集构建模型配置，供启动配置加载和单元测试复用。
     *
     * @param rootProps 模型配置属性源
     * @return 模型配置列表；未配置列表时回退到单模型配置
     */
    List<ChatConfig> loadModelConfigs(Props rootProps) {
        List<ChatConfig> result = new ArrayList<>();
        if (rootProps != null) {
            //1. 逐项读取 agent.model.models 列表，每项继承公共连接配置。
            for (Props props : rootProps.getListedProp("agent.model.models")) {
                String configuredModel = props.get("model");
                if (Utils.isBlank(configuredModel)) {
                    continue;
                }
                ChatConfig config = new ChatConfig();
                config.setName(props.get("name", configuredModel));
                config.setApiUrl(props.get("api-url", apiUrl));
                config.setApiKey(props.get("api-key", apiKey));
                config.setProvider(props.get("provider", provider));
                config.setModel(configuredModel);
                config.setContextLength(props.getLong("context-length", modelContextLength));
                result.add(config);
            }
        }
        //2. 列表为空时回退到旧版单模型配置，保证向后兼容。
        if (result.isEmpty()) {
            ChatConfig config = new ChatConfig();
            config.setName(modelName);
            config.setApiUrl(apiUrl);
            config.setApiKey(apiKey);
            config.setProvider(provider);
            config.setModel(model);
            config.setContextLength(modelContextLength);
            result.add(config);
        }
        return result;
    }
}
