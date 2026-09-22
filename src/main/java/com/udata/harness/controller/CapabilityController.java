package com.udata.harness.controller;

import com.udata.harness.common.request.CapabilityRequest;
import com.udata.harness.common.request.SkillArchiveRequest;
import com.udata.harness.service.CapabilityService;
import com.udata.harness.service.UserWorkspaceService;
import org.noear.solon.annotation.Body;
import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Delete;
import org.noear.solon.annotation.Get;
import org.noear.solon.annotation.Header;
import org.noear.solon.annotation.Inject;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.annotation.Param;
import org.noear.solon.annotation.Post;
import org.noear.solon.core.handle.Result;

import java.util.List;
import java.util.Map;

/**
 * SKILL 与 Subagent 的后台管理接口。
 *
 * <p>SKILL/Subagent 定义保存在共享能力库中，保存、导入和删除属于后台管理操作；
 * SKILL 的激活状态按用户隔离。激活后，服务层会将完整技能目录复制到当前用户工作区的
 * {@code .soloncode/skills/<skillName>}，并刷新该用户的 HarnessEngine，使后续对话加载
 * 新能力。停用时执行对应的移除与刷新流程。</p>
 *
 * <p>Controller 仅负责用户 ID 校验、动作路由和统一响应封装。名称安全、ZIP 解压安全、
 * 文件写入及引擎刷新等规则集中在 {@link CapabilityService} 中。</p>
 */
@Controller
@Mapping("/api/capabilities")
public class CapabilityController {
    /** 能力库、用户激活状态及 Harness 刷新的统一应用服务。 */
    @Inject
    private CapabilityService capabilities;

    /**
     * 列出共享技能及当前用户的激活状态。
     *
     * @param userId 当前用户标识
     * @return 技能摘要列表；每项包含名称、内容/元数据及是否对当前用户生效
     */
    @Get
    @Mapping("/skills")
    public Result<List<Map<String, Object>>> skills(@Header("X-User-Id") String userId) {
        UserWorkspaceService.requireUserId(userId);
        return Result.succeed(capabilities.listSkills(userId));
    }

    /**
     * 从 Base64 编码的 ZIP 压缩包导入一个完整技能目录。
     *
     * <p>该接口用于包含 {@code SKILL.md}、脚本和资源文件的多文件技能。压缩包格式、
     * 条目路径与大小限制由服务层检查，避免 ZIP Slip 和越界写入。</p>
     *
     * @param userId 发起管理操作的用户标识
     * @param request 技能名称和 ZIP 内容的 Base64 文本
     * @return 无响应数据
     */
    @Post
    @Mapping("/skills/import")
    public Result<Void> importSkill(@Header("X-User-Id") String userId,
                                    @Body SkillArchiveRequest request) {
        UserWorkspaceService.requireUserId(userId);
        capabilities.importSkill(request);
        return Result.succeed();
    }

    /**
     * 激活或停用当前用户的某个技能。
     *
     * @param userId 当前用户标识
     * @param name 共享能力库中的技能名称
     * @param action 仅支持 {@code activate} 或 {@code deactivate}
     * @return 无响应数据；完成时对应用户的 Harness 能力已刷新
     */
    @Post
    @Mapping("/skills/action")
    public Result<Void> skillAction(@Header("X-User-Id") String userId,
                                    @Param("name") String name,
                                    @Param("action") String action) {
        userId = UserWorkspaceService.requireUserId(userId);
        if ("activate".equalsIgnoreCase(action)) {
            capabilities.activateSkill(userId, name);
        } else if ("deactivate".equalsIgnoreCase(action)) {
            capabilities.deactivateSkill(userId, name);
        } else {
            throw new IllegalArgumentException("Unsupported Skill action");
        }
        return Result.succeed();
    }

    /**
     * 新建或覆盖单文件技能定义。
     *
     * <p>适用于只包含 Markdown 指令的简单技能；多文件技能应使用
     * {@code /skills/import}。</p>
     *
     * @param userId 发起管理操作的用户标识
     * @param request 技能名称及 SKILL.md 内容
     * @return 无响应数据
     */
    @Post
    @Mapping("/skills")
    public Result<Void> saveSkill(@Header("X-User-Id") String userId,
                                  @Body CapabilityRequest request) {
        UserWorkspaceService.requireUserId(userId);
        capabilities.saveSkill(request);
        return Result.succeed();
    }

    /**
     * 从共享能力库删除技能。
     *
     * @param userId 发起管理操作的用户标识
     * @param name 待删除技能名称
     * @return 无响应数据
     */
    @Delete
    @Mapping("/skills")
    public Result<Void> deleteSkill(@Header("X-User-Id") String userId,
                                    @Param("name") String name) {
        UserWorkspaceService.requireUserId(userId);
        capabilities.deleteSkill(name);
        return Result.succeed();
    }

    /**
     * 列出可用 Subagent 定义及其加载信息。
     *
     * @param userId 当前用户标识
     * @return Subagent 名称、定义内容和状态组成的列表
     */
    @Get
    @Mapping("/agents")
    public Result<List<Map<String, Object>>> agents(@Header("X-User-Id") String userId) {
        UserWorkspaceService.requireUserId(userId);
        return Result.succeed(capabilities.listAgents(userId));
    }

    /**
     * 新建或覆盖 Subagent Markdown 定义。
     *
     * @param userId 发起管理操作的用户标识
     * @param request Subagent 名称及定义内容
     * @return 无响应数据
     */
    @Post
    @Mapping("/agents")
    public Result<Void> saveAgent(@Header("X-User-Id") String userId,
                                  @Body CapabilityRequest request) {
        UserWorkspaceService.requireUserId(userId);
        capabilities.saveAgent(request);
        return Result.succeed();
    }

    /**
     * 删除共享 Subagent 定义。
     *
     * @param userId 发起管理操作的用户标识
     * @param name 待删除 Subagent 名称
     * @return 无响应数据
     */
    @Delete
    @Mapping("/agents")
    public Result<Void> deleteAgent(@Header("X-User-Id") String userId,
                                    @Param("name") String name) {
        UserWorkspaceService.requireUserId(userId);
        capabilities.deleteAgent(name);
        return Result.succeed();
    }

    /**
     * 主动重建当前用户的能力加载状态。
     *
     * <p>用于管理端修改能力文件后立即生效，无需重启 Web 服务。</p>
     *
     * @param userId 需要刷新 HarnessEngine 的用户标识
     * @return 无响应数据
     */
    @Post
    @Mapping("/refresh")
    public Result<Void> refresh(@Header("X-User-Id") String userId) {
        UserWorkspaceService.requireUserId(userId);
        capabilities.refresh(userId);
        return Result.succeed();
    }
}
