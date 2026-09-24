package com.udata.harness.service.impl;

import org.noear.snack4.ONode;
import com.udata.harness.common.request.McpConfigRequest;
import com.udata.harness.service.IntegrationService;
import com.udata.harness.service.UserHarnessEngineService;
import org.noear.solon.ai.mcp.client.McpServerParameters;
import org.noear.solon.annotation.Component;
import org.noear.solon.annotation.Init;
import org.noear.solon.annotation.Inject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * MCP 配置持久化实现。
 *
 * <p>配置写入 data/integrations，启动时恢复到内存并同步进 HarnessEngine。
 * 列表只暴露摘要和 hasSecrets 标记，不返回 Header/环境变量的具体秘密值。</p>
 */
@Component
public class IntegrationServiceImpl implements IntegrationService {
    /**
     * 用户引擎服务，MCP 配置变更后同步到所有已创建引擎。
     */
    @Inject
    private UserHarnessEngineService engines;

    /**
     * 应用数据目录，MCP 配置持久化的根路径。
     */
    @Inject("${agent.data-dir:./data}")
    private String dataDir;

    /**
     * MCP 配置目录（data/integrations），启动时解析并缓存。
     */
    private Path configRoot;

    /**
     * 内存中的 MCP 服务定义，以服务名为键，保证与磁盘文件一致。
     */
    private final Map<String, McpServerParameters> mcpServers = new LinkedHashMap<>();

    /**
     * 启动时从独立的 MCP 配置目录恢复定义并同步到引擎管理器。
     */
    @Init
    public void init() {
        //1. 准备配置根目录，确保 mcp 子目录存在。
        configRoot = Paths.get(dataDir).toAbsolutePath().normalize().resolve("integrations");
        try {
            Files.createDirectories(configRoot.resolve("mcp"));
            //2. 恢复历史配置并同步到引擎管理器。
            loadAll();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot initialize integrations", e);
        }
    }

    /**
     * 返回 MCP 配置摘要。
     *
     * <p>Header 与环境变量只暴露 {@code hasSecrets}，不会把具体密钥回传给浏览器。</p>
     *
     * @return MCP 配置摘要列表
     */
    public List<Map<String, Object>> listMcp() {
        List<Map<String, Object>> result = new ArrayList<>();
        mcpServers.forEach((name, value) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", name);
            item.put("transport", value.getTypeOrTransport());
            item.put("url", value.getUrl());
            item.put("command", value.getCommand());
            item.put("enabled", value.isEnabled());
            item.put("hasSecrets", !value.getHeaders().isEmpty() || !value.getEnv().isEmpty());
            result.add(item);
        });
        return result;
    }

    /**
     * 校验、持久化并热更新 MCP 服务；同名配置采用替换语义。
     *
     * @param request MCP 配置请求
     * @throws IllegalArgumentException 名称为空或格式非法时抛出
     */
    public void saveMcp(McpConfigRequest request) {
        String name = requireName(request == null ? null : request.getName());
        McpServerParameters params = toMcp(request);
        mcpServers.put(name, params);
        engines.putMcp(name, params);
        write("mcp", name, request);
    }

    /**
     * 同时删除磁盘配置和所有已实例化引擎中的 MCP 客户端。
     *
     * @param name MCP Server 名称
     */
    public void removeMcp(String name) {
        name = requireName(name);
        mcpServers.remove(name);
        engines.removeMcp(name);
        delete("mcp", name);
    }

    /**
     * 恢复全部配置文件。
     *
     * <p>先解析为请求 DTO，再转换成框架参数，确保启动恢复与 HTTP 保存走同一套规则。</p>
     *
     * @throws IOException 读取配置目录失败时抛出
     */
    private void loadAll() throws IOException {
        try (Stream<Path> paths = Files.list(configRoot.resolve("mcp"))) {
            paths.filter(path -> path.toString().endsWith(".json")).forEach(path -> {
                try {
                    String content = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
                    McpConfigRequest request = ONode.ofJson(content).toBean(McpConfigRequest.class);
                    String name = requireName(request.getName());
                    McpServerParameters parameters = toMcp(request);
                    mcpServers.put(name, parameters);
                    engines.putMcp(name, parameters);
                } catch (Exception e) {
                    throw new IllegalStateException("Cannot load " + path, e);
                }
            });
        }
    }

    /**
     * 将请求 DTO 转换为框架 MCP 参数。
     *
     * @param request MCP 配置请求
     * @return 框架可用的 MCP 连接参数
     */
    private McpServerParameters toMcp(McpConfigRequest request) {
        McpServerParameters params = new McpServerParameters();
        params.setTransport(request.getTransport());
        params.setUrl(request.getUrl());
        params.setCommand(request.getCommand());
        params.setArgs(request.getArgs());
        params.setHeaders(request.getHeaders());
        params.setEnv(request.getEnv());
        params.setAllowedTools(request.getAllowedTools());
        params.setDisallowedTools(request.getDisallowedTools());
        params.setEnabled(request.isEnabled());
        return params;
    }

    /**
     * 将配置序列化为 JSON 写入磁盘。
     *
     * @param type 配置类型目录（如 mcp）
     * @param name 配置名称
     * @param value 待序列化对象
     */
    private void write(String type, String name, Object value) {
        try {
            Files.write(configRoot.resolve(type).resolve(name + ".json"),
                    ONode.ofBean(value).toJson().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot save integration", e);
        }
    }

    /**
     * 删除磁盘上的配置文件。
     *
     * @param type 配置类型目录
     * @param name 配置名称
     */
    private void delete(String type, String name) {
        try {
            Files.deleteIfExists(configRoot.resolve(type).resolve(name + ".json"));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot delete integration", e);
        }
    }

    /**
     * 校验集成名称并返回原值。
     *
     * @param name 原始名称
     * @return 去除首尾空白后的合法名称
     * @throws IllegalArgumentException 名称不符合白名单时抛出
     */
    private String requireName(String name) {
        String value = name == null ? "" : name.trim();
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
            throw new IllegalArgumentException("Invalid integration name");
        }
        return value;
    }
}
