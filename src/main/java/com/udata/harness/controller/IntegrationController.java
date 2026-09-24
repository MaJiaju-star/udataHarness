package com.udata.harness.controller;

import com.udata.harness.common.request.McpConfigRequest;
import com.udata.harness.service.IntegrationService;
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
import com.udata.harness.service.UserWorkspaceService;

import java.util.List;
import java.util.Map;

/**
 * MCP Server 配置管理接口。
 *
 * <p>配置由 {@link IntegrationService} 持久化并同步到 HarnessEngine。保存或删除配置后，
 * 后续智能体运行会使用更新后的工具。列表接口会对 Header、环境变量等敏感字段
 * 做脱敏处理，不能依赖列表响应还原原始密钥。</p>
 *
 * <p>{@code X-User-Id} 当前用于识别和审计调用者；集成配置是应用级共享配置，
 * 并非每个用户各存一份。</p>
 */
@Controller
@Mapping("/api/integrations")
public class IntegrationController {
    /**
     * 负责配置校验、持久化、脱敏展示和引擎热更新。
     */
    @Inject
    private IntegrationService configs;

    /**
     * 查询 MCP Server 配置摘要。
     *
     * @param userId 当前调用用户，用于身份格式校验
     * @return 已脱敏的 MCP 配置列表
     */
    @Get
    @Mapping("/mcp")
    public Result<List<Map<String, Object>>> mcp(@Header("X-User-Id") String userId) {
        UserWorkspaceService.requireUserId(userId);
        return Result.succeed(configs.listMcp());
    }

    /**
     * 新建或覆盖 MCP Server 配置。
     *
     * @param userId 当前调用用户
     * @param request 传输协议、URL/命令、参数、凭据及工具过滤规则
     * @return 无响应数据
     */
    @Post
    @Mapping("/mcp")
    public Result<Void> saveMcp(@Header("X-User-Id") String userId,
                                @Body McpConfigRequest request) {
        UserWorkspaceService.requireUserId(userId);
        configs.saveMcp(request);
        return Result.succeed();
    }

    /**
     * 删除指定 MCP Server 配置并从运行时注销。
     *
     * @param userId 当前调用用户
     * @param name MCP Server 唯一名称
     * @return 无响应数据
     */
    @Delete
    @Mapping("/mcp")
    public Result<Void> deleteMcp(@Header("X-User-Id") String userId,
                                  @Param("name") String name) {
        UserWorkspaceService.requireUserId(userId);
        configs.removeMcp(name);
        return Result.succeed();
    }

}
