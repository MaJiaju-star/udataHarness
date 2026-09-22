package com.udata.harness.service;

import com.udata.harness.common.request.CapabilityRequest;
import com.udata.harness.common.request.SkillArchiveRequest;

import java.util.List;
import java.util.Map;

/**
 * 共享 SKILL/Subagent 库及按用户激活能力的服务契约。
 *
 * <p>“保存/导入/删除”修改共享定义，“激活/停用”修改用户工作区副本。任何会影响
 * Harness 可见能力的操作都应在实现层刷新相应的用户引擎缓存。</p>
 */
public interface CapabilityService {
    /** 返回共享技能，并合并指定用户的激活状态。 */
    List<Map<String, Object>> listSkills(String userId);

    /** 返回共享 Subagent 定义及运行时状态。 */
    List<Map<String, Object>> listAgents(String userId);

    /** 保存只有 SKILL.md 的单文件技能。 */
    void saveSkill(CapabilityRequest request);

    /** 安全解压并保存包含脚本、引用资料和资源的完整技能包。 */
    void importSkill(SkillArchiveRequest request);

    /** 将技能复制到用户的 .soloncode/skills 目录并刷新引擎。 */
    void activateSkill(String userId, String name);

    /** 删除用户工作区中的技能副本并刷新引擎。 */
    void deactivateSkill(String userId, String name);

    /** 保存或覆盖共享 Subagent Markdown 定义。 */
    void saveAgent(CapabilityRequest request);

    /** 删除共享技能定义。 */
    void deleteSkill(String name);

    /** 删除共享 Subagent 定义并刷新相关引擎。 */
    void deleteAgent(String name);

    /** 重新同步指定用户的技能副本和 Harness 能力缓存。 */
    void refresh(String userId);
}
