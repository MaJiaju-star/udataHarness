package com.udata.harness.controller;

import com.udata.harness.common.domain.WorkspaceMetadata;
import com.udata.harness.common.request.WorkspaceActivateRequest;
import com.udata.harness.common.request.WorkspaceRegisterRequest;
import com.udata.harness.service.UserHarnessEngineService;
import com.udata.harness.service.UserWorkspaceService;
import org.noear.solon.annotation.Body;
import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Get;
import org.noear.solon.annotation.Header;
import org.noear.solon.annotation.Inject;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.annotation.Param;
import org.noear.solon.annotation.Post;
import org.noear.solon.core.handle.Result;

import java.util.List;
import java.util.Map;

/** 提供本地目录浏览、工作区注册和切换接口。 */
@Controller
@Mapping("/api/workspaces")
public class WorkspaceController {
    @Inject
    private UserWorkspaceService workspaces;

    @Inject
    private UserHarnessEngineService engines;

    /** 返回当前用户已注册的工作区。 */
    @Get
    @Mapping
    public Result<List<WorkspaceMetadata>> list(@Header("X-User-Id") String userId) {
        return Result.succeed(workspaces.list(userId));
    }

    /** 返回后端允许浏览的根目录。 */
    @Get
    @Mapping("/roots")
    public Result<List<Map<String, Object>>> roots() {
        return Result.succeed(workspaces.roots());
    }

    /** 按需返回一个目录的直接子目录。 */
    @Get
    @Mapping("/children")
    public Result<List<Map<String, Object>>> children(@Param("path") String path) {
        return Result.succeed(workspaces.children(path));
    }

    /** 注册本地目录，激活后重建用户引擎以应用新的工作目录。 */
    @Post
    @Mapping
    public Result<WorkspaceMetadata> register(
            @Header("X-User-Id") String userId,
            @Body WorkspaceRegisterRequest request) {
        WorkspaceMetadata workspace = workspaces.register(userId, request.getPath());
        engines.resetUser(userId);
        return Result.succeed(workspace);
    }

    /** 切换当前工作区，随后重建绑定旧目录的用户引擎。 */
    @Post
    @Mapping("/activate")
    public Result<WorkspaceMetadata> activate(
            @Header("X-User-Id") String userId,
            @Body WorkspaceActivateRequest request) {
        WorkspaceMetadata workspace = workspaces.activate(userId, request.getWorkspaceId());
        engines.resetUser(userId);
        return Result.succeed(workspace);
    }
}
