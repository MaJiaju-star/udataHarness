package com.udata.harness.service.impl;

import com.udata.harness.common.support.AntVChartTool;
import com.udata.harness.common.support.ChartTool;
import com.udata.harness.repository.SessionRepository;
import com.udata.harness.service.UserHarnessEngineService;
import com.udata.harness.service.UserWorkspaceService;
import org.noear.solon.Solon;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.agent.ToolName;
import org.noear.solon.ai.harness.permission.PermissionRule;
import org.noear.solon.ai.mcp.client.McpServerParameters;
import org.noear.solon.ai.talents.mount.MountDir;
import org.noear.solon.ai.talents.mount.MountType;
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
    private static final String SANDBOX_ENABLED_KEY = "sandboxEnabled";
    private static final ChartTool CHART_TOOL = new ChartTool();
    private static final AntVChartTool ANTV_CHART_TOOL = new AntVChartTool();

    @Inject
    private SessionRepository sessionRepository;

    @Inject
    private UserWorkspaceService workspaces;

    @Inject("${agent.data-dir:./data}")
    private String dataDir;

    @Inject("${agent.harness-home:.soloncode}")
    private String harnessHome;

    @Inject("${agent.system-prompt:You are a careful coding agent. Inspect the workspace, explain material changes, and verify your work.}")
    private String systemPrompt;

    @Inject("${agent.max-turns:100}")
    private int maxTurns;

    @Inject("${agent.sandbox.enabled:true}")
    private boolean sandboxEnabledByDefault = true;

    @Inject("${agent.tools.web.enabled:true}")
    private boolean webToolsEnabled = true;

    @Inject("${agent.tools.web.websearch-enabled:true}")
    private boolean webSearchEnabled = true;

    @Inject("${agent.tools.web.codesearch-enabled:true}")
    private boolean codeSearchEnabled = true;

    @Inject("${agent.tools.web.webfetch-enabled:true}")
    private boolean webFetchEnabled = true;

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

    /** 达到模型上下文窗口的该比例时触发压缩，取值范围为 0 到 1。 */
    @Inject("${agent.context.compression-max-context-ratio:0.75}")
    private double compressionMaxContextRatio;

    /** 模型声明的物理上下文窗口，用于让压缩器按模型能力计算安全余量。 */
    @Inject("${agent.model.context-length:1000000}")
    private long modelContextLength;

    /** 模型请求总尝试次数，包含第一次请求。 */
    @Inject("${agent.model.retry.max-attempts:3}")
    private int modelMaxAttempts;

    @Inject("${agent.model.default:deepseek-flash}")
    private String modelName;

    @Inject("${agent.model.api-url:https://api.deepseek.com}")
    private String apiUrl;

    @Inject("${agent.model.api-key:}")
    private String apiKey;

    @Inject("${agent.model.provider:openai}")
    private String provider;

    @Inject("${agent.model.model:deepseek-v4-flash}")
    private String model;

    private final Map<String, HarnessEngine> engines = new ConcurrentHashMap<>();
    private final Map<String, McpServerParameters> mcpServers = new ConcurrentHashMap<>();
    private final Map<String, Boolean> sandboxPreferences = new ConcurrentHashMap<>();

    /**
     * 获取用户专属引擎；首次访问时原子创建并缓存。
     *
     * <p>用户标识先经过目录安全校验。缓存键使用规范化后的 userId，保证并发请求不会
     * 为同一用户创建多个 HarnessEngine，也防止跨用户复用会话与工作目录。</p>
     */
    public HarnessEngine get(String userId) {
        String safeUserId = UserWorkspaceService.requireUserId(userId);
        return engines.computeIfAbsent(safeUserId, this::build);
    }

    /** 返回用户持久化的沙箱设置；首次读取时回退到应用默认值。 */
    @Override
    public boolean isSandboxEnabled(String userId) {
        String safeUserId = UserWorkspaceService.requireUserId(userId);
        return sandboxPreferences.computeIfAbsent(safeUserId, this::loadSandboxPreference);
    }

    /** 保存用户沙箱设置，并同步到已经创建的 HarnessEngine。 */
    @Override
    public void setSandboxEnabled(String userId, boolean enabled) {
        String safeUserId = UserWorkspaceService.requireUserId(userId);
        saveSandboxPreference(safeUserId, enabled);
        sandboxPreferences.put(safeUserId, enabled);
        HarnessEngine engine = engines.get(safeUserId);
        if (engine != null) {
            engine.setSandboxEnabled(enabled);
        }
    }

    /** 清除用户引擎缓存；工作区切换后的下一次访问会重新构建。 */
    @Override
    public void resetUser(String userId) {
        engines.remove(UserWorkspaceService.requireUserId(userId));
    }

    /** 返回当前进程已经实例化的引擎视图，不会主动为未访问用户创建实例。 */
    public Collection<HarnessEngine> all() {
        return engines.values();
    }

    /**
     * 刷新指定用户的 Skill、共享 Agent 挂载和主 Agent。
     *
     * <p>刷新只影响能力定义，不替换用户的 SessionProvider 或工作区。</p>
     */
    public void refreshUserCapabilities(String userId) {
        HarnessEngine engine = get(userId);
        engine.refreshMount("@workspace-skills");
        engine.refreshMount("@shared-agents");
        engine.refreshMainAgent();
    }

    /** 判断某个 Skill 是否已经进入该用户当前引擎的有效能力集合。 */
    public boolean isSkillLoaded(String userId, String skillName) {
        return get(userId).getSkills().stream().anyMatch(skill -> skillName.equals(skill.getName()));
    }

    /** 共享 Subagent 定义变化后，刷新所有已实例化引擎的 Agent 挂载。 */
    public void refreshAgentsForAll() {
        for (HarnessEngine engine : engines.values()) {
            engine.refreshMount("@shared-agents");
            engine.refreshMainAgent();
        }
    }

    /**
     * 保存运行时 MCP 定义并热更新所有现存引擎。
     *
     * <p>尚未创建的用户引擎会在 {@link #build(String)} 时读取缓存中的同一份配置。</p>
     */
    public void putMcp(String name, McpServerParameters parameters) {
        mcpServers.put(name, parameters);
        for (HarnessEngine engine : engines.values()) {
            engine.removeMcpServer(name);
            engine.addMcpServer(name, parameters);
        }
    }

    /** 从全局缓存和所有已创建引擎中移除指定 MCP 服务。 */
    public void removeMcp(String name) {
        mcpServers.remove(name);
        engines.values().forEach(engine -> engine.removeMcpServer(name));
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
     *   <li>已保存的 MCP 定义在引擎对外可见前全部挂载。</li>
     * </ul>
     */
    private HarnessEngine build(String userId) {
        Path workspace = workspaces.getOrCreate(userId);
        Path skills = workspace.resolve(".soloncode").resolve("skills");
        Path agents = Paths.get(dataDir).toAbsolutePath().normalize().resolve("capabilities").resolve("agents");
        try {
            Files.createDirectories(skills);
            Files.createDirectories(agents);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create Harness capability directories", e);
        }

        //1. 工具、沙箱、HITL 和 Subagent 在引擎创建时一次性启用；会话级 cwd
        // 仍由 ChatService 每次运行时写入 toolContext。
        HarnessEngine.Builder builder = HarnessEngine.of(workspace.toString(), harnessHome)
                .systemPrompt(systemPrompt)
                .maxTurns(maxTurns)
                .sessionWindowSize(sessionWindowSize)
                .modelRetries(modelMaxAttempts)
                .compressionThreshold(compressionMaxMessages, compressionMaxContextRatio)
                .sessionProvider(sessionRepository)
                .toolsAdd(Arrays.asList(ToolName.TOOL_ALL_PUBLIC.getName(), ToolName.TOOL_HITL.getName()))
                .disallowedToolsAdd(disabledWebTools())
                .extensionAdd((engine, agentName, agentBuilder) -> {
                    agentBuilder.defaultToolAdd(CHART_TOOL);
                    agentBuilder.defaultToolAdd(ANTV_CHART_TOOL);
                })
                .sandboxEnabled(isSandboxEnabled(userId))
                .sandboxAllowUserHome(false)
                .sandboxSystemRestrict(true)
                // BashToolStrategy 会优先放行只读命令、拒绝系统级危险命令；
                // 对其余可能修改工作区的命令使用此兜底规则进入人工审批。
                .permissionRuleAdd(PermissionRule.ask("bash"))
                .hitlEnabled(true)
                .subagentEnabled(true)
                .bashAsyncEnabled(true);

        //2. 从 app.yml 注册模型列表；未配置列表时保留旧单模型配置兼容性。
        List<ChatConfig> modelConfigs = loadModelConfigs();
        for (ChatConfig chatConfig : modelConfigs) {
            builder.modelAdd(chatConfig);
        }
        String defaultModel = modelConfigs.stream()
                .anyMatch(item -> modelName.equals(item.getNameOrModel()))
                ? modelName : modelConfigs.get(0).getNameOrModel();
        HarnessEngine engine = builder.defaultModel(defaultModel).build();

        //3. 用户技能使用独立可写挂载，共享 Agent 使用服务级只读语义目录。
        engine.addMount(MountDir.builder()
                .alias("@workspace-skills")
                .description("Activated Skills for " + userId)
                .type(MountType.SKILLS)
                .path(skills.toString())
                .primary(true)
                .writeable(true)
                .build());
        engine.addMount(MountDir.builder()
                .alias("@shared-agents")
                .description("Service-wide Subagent library")
                .type(MountType.AGENTS)
                .path(agents.toString())
                .primary(true)
                .writeable(true)
                .build());
        mcpServers.forEach(engine::addMcpServer);
        return engine;
    }

    /** 根据总开关和细粒度开关生成不向模型暴露的网络工具列表。 */
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

    /** 从用户设置文件读取沙箱开关。 */
    private boolean loadSandboxPreference(String userId) {
        Properties properties = loadUserSettings(userId);
        return Boolean.parseBoolean(properties.getProperty(
                SANDBOX_ENABLED_KEY, String.valueOf(sandboxEnabledByDefault)));
    }

    /** 将沙箱开关写入用户设置文件。 */
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

    /** 读取用户设置；文件尚不存在时返回空配置。 */
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

    /** 返回位于应用数据目录内的用户设置文件。 */
    private Path userSettingsFile(String userId) {
        return Paths.get(dataDir).toAbsolutePath().normalize()
                .resolve("users").resolve(userId).resolve("settings.properties");
    }

    /**
     * 从 {@code agent.model.models} 读取模型列表，并让每项继承公共连接配置。
     *
     * <p>列表项支持 name、model、api-url、api-key、provider 和 context-length；其中只有
     * name/model 通常需要逐项配置。</p>
     */
    private List<ChatConfig> loadModelConfigs() {
        return loadModelConfigs(Solon.cfg());
    }

    /** 使用给定属性集构建模型配置，供启动配置加载和单元测试复用。 */
    List<ChatConfig> loadModelConfigs(Props rootProps) {
        List<ChatConfig> result = new ArrayList<>();
        if (rootProps != null) {
            for (Props props : rootProps.getListedProp("agent.model.models")) {
                String configuredModel = props.get("model");
                if (configuredModel == null || configuredModel.isBlank()) {
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
