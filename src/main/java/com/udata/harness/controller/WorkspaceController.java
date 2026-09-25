package com.udata.harness.controller;

import com.udata.harness.common.domain.WorkspaceMetadata;
import com.udata.harness.service.UserHarnessEngineService;
import com.udata.harness.service.UserWorkspaceService;
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

/**
 * 提供本地目录浏览、工作区注册和切换接口。
 *
 * <p>目录浏览只返回目录节点而不递归扫描磁盘；注册与激活后都会重建用户引擎，
 * 使新的工作目录对后续对话生效。</p>
 */
@Controller
@Mapping("/api/workspaces")
public class WorkspaceController {
    /**
     * 工作区注册、激活与本地目录浏览。
     */
    @Inject
    private UserWorkspaceService workspaces;

    /**
     * 工作区切换后重建用户引擎。
     */
    @Inject
    private UserHarnessEngineService engines;

    /**
     * 返回当前用户已注册的工作区。
     *
     * @param userId 当前用户标识
     * @return 工作区列表，按最近打开时间倒序
     */
    @Get
    @Mapping
    public Result<List<WorkspaceMetadata>> list(@Header("X-User-Id") String userId) {
        return Result.succeed(workspaces.list(userId));
    }

    /**
     * 返回后端允许浏览的根目录。
     *
     * @return 根目录节点列表
     */
    @Get
    @Mapping("/roots")
    public Result<List<Map<String, Object>>> roots() {
        return Result.succeed(workspaces.roots());
    }

    /**
     * 按需返回一个目录的直接子目录。
     *
     * @param path 父目录绝对路径
     * @return 子目录节点列表
     */
    @Get
    @Mapping("/children")
    public Result<List<Map<String, Object>>> children(@Param("path") String path) {
        return Result.succeed(workspaces.children(path));
    }

    /**
     * 注册本地目录，激活后重建用户引擎以应用新的工作目录。
     *
     * @param userId 当前用户标识
     * @param path 本地目录绝对路径
     * @return 注册后的工作区元数据
     */
    @Post
    @Mapping
    public Result<WorkspaceMetadata> register(
            @Header("X-User-Id") String userId,
            @Param("path") String path) {
        WorkspaceMetadata workspace = workspaces.register(userId, path);
        engines.resetUser(userId);
        return Result.succeed(workspace);
    }

    /**
     * 切换当前工作区，随后重建绑定旧目录的用户引擎。
     *
     * @param userId 当前用户标识
     * @param workspaceId 目标工作区标识
     * @return 激活后的工作区元数据
     */
    @Post
    @Mapping("/activate")
    public Result<WorkspaceMetadata> activate(
            @Header("X-User-Id") String userId,
            @Param("workspaceId") String workspaceId) {
        WorkspaceMetadata workspace = workspaces.activate(userId, workspaceId);
        engines.resetUser(userId);
        return Result.succeed(workspace);
    }
}
