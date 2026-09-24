package com.udata.harness.service;

import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.mcp.client.McpServerParameters;

import java.util.Collection;

/**
 * 按用户构建并缓存 Solon AI {@link HarnessEngine}。
 *
 * <p>每个用户引擎绑定其专属工作区与已激活技能；MCP 配置则是应用级共享资源，
 * 注册变更需要同步到所有已缓存引擎。</p>
 */
public interface UserHarnessEngineService {
    /**
     * 获取或惰性创建指定用户的引擎。
     *
     * @param userId 目标用户标识
     * @return 该用户的 HarnessEngine
     */
    HarnessEngine get(String userId);

    /**
     * 移除用户当前缓存的引擎，使下一次请求按最新工作区重新创建。
     *
     * @param userId 目标用户标识
     */
    default void resetUser(String userId) {
        // Lightweight test doubles and alternate implementations may not cache engines.
    }

    /**
     * 返回当前进程中已创建的全部用户引擎。
     *
     * @return 已缓存引擎集合
     */
    Collection<HarnessEngine> all();

    /**
     * 依据用户工作区中的技能目录重建其能力加载状态。
     *
     * @param userId 目标用户标识
     */
    void refreshUserCapabilities(String userId);

    /**
     * 判断技能是否已经加载到指定用户的引擎。
     *
     * @param userId 目标用户标识
     * @param skillName 技能名称
     * @return 已加载时为 true
     */
    boolean isSkillLoaded(String userId, String skillName);

    /**
     * 返回当前用户实际采用的沙箱开关状态。
     *
     * @param userId 目标用户标识
     * @return 沙箱开关
     */
    default boolean isSandboxEnabled(String userId) {
        return true;
    }

    /**
     * 持久化并立即应用当前用户的沙箱开关。
     *
     * @param userId 目标用户标识
     * @param enabled 是否启用沙箱
     */
    default void setSandboxEnabled(String userId, boolean enabled) {
        get(userId).setSandboxEnabled(enabled);
    }

    /**
     * 将共享 Subagent 定义刷新到所有已创建引擎。
     */
    void refreshAgentsForAll();

    /**
     * 保存运行时 MCP 参数，并注册或替换所有引擎中的同名 Server。
     *
     * @param name MCP Server 名称
     * @param parameters MCP 连接参数
     */
    void putMcp(String name, McpServerParameters parameters);

    /**
     * 从运行时配置及所有引擎中移除 MCP Server。
     *
     * @param name MCP Server 名称
     */
    void removeMcp(String name);

}
