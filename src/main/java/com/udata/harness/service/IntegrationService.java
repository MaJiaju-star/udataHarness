package com.udata.harness.service;

import com.udata.harness.common.request.McpConfigRequest;

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
     * @param request MCP 配置请求
     */
    void saveMcp(McpConfigRequest request);

    /**
     * 删除持久化配置并从所有用户引擎注销 MCP Server。
     *
     * @param name MCP Server 名称
     */
    void removeMcp(String name);
}
