package com.udata.harness.service;

import java.util.List;
import java.util.Map;

/**
 * MCP 应用级配置管理服务。
 *
 * <p>实现负责请求校验、配置文件持久化、列表脱敏，以及向所有已创建的用户
 * HarnessEngine 热注册或注销 Server。</p>
 */
public interface IntegrationService {
    /**
     * 返回脱敏后的 MCP 配置摘要。
     *
     * @return MCP 配置摘要列表
     */
    List<Map<String, Object>> listMcp();

    /**
     * 保存 MCP 配置并更新运行时 Server 注册。
     *
     * @param name MCP Server 名称
     * @param transport 传输方式
     * @param url 远程服务 URL
     * @param command 本地启动命令
     * @param args 命令参数
     * @param headers 请求 Header（JSON 文本，可能含敏感凭据）
     * @param env 进程环境变量（JSON 文本，可能含敏感凭据）
     * @param allowedTools 工具白名单
     * @param disallowedTools 工具黑名单
     * @param enabled 是否启用
     */
    void saveMcp(String name, String transport, String url, String command, List<String> args,
                 String headers, String env, List<String> allowedTools,
                 List<String> disallowedTools, boolean enabled);

    /**
     * 删除持久化配置并从所有用户引擎注销 MCP Server。
     *
     * @param name MCP Server 名称
     */
    void removeMcp(String name);
}
