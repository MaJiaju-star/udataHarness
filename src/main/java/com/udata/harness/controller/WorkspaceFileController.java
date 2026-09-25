package com.udata.harness.controller;

import com.udata.harness.common.domain.GlobalSearchResponse;
import com.udata.harness.service.WorkspaceFileService;
import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Delete;
import org.noear.solon.annotation.Get;
import org.noear.solon.annotation.Header;
import org.noear.solon.annotation.Inject;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.annotation.Param;
import org.noear.solon.annotation.Post;
import org.noear.solon.core.handle.Result;
import org.noear.solon.core.handle.DownloadedFile;
import org.noear.solon.core.handle.UploadedFile;

import java.util.List;
import java.util.Map;

/**
 * 用户工作区文件面板的 HTTP 接口。
 *
 * <p>所有 path 都是相对于当前用户专属工作区的逻辑路径。绝对路径、{@code ..} 穿越、
 * 符号链接逃逸以及对其他用户目录的访问必须由 {@link WorkspaceFileService} 拒绝。
 * Controller 不拼接物理路径，也不直接使用 {@code java.nio.file.Files}。</p>
 *
 * <p>该接口面向轻量代码编辑器，提供目录树、文本读取/保存、目录创建、删除和文件名搜索。
 * 大文件、二进制文件和搜索数量限制由服务层统一控制。</p>
 */
@Controller
@Mapping("/api/files")
public class WorkspaceFileController {
    /**
     * 封装用户目录解析、路径 containment 校验和文件系统操作。
     */
    @Inject
    private WorkspaceFileService files;

    /**
     * 获取指定目录下的文件树。
     *
     * @param userId 当前用户标识
     * @param path 可选相对目录；为空表示用户工作区根目录
     * @param depth 可选递归深度；未传时默认为 2
     * @return 适合文件面板渲染的树节点列表
     */
    @Get
    @Mapping("/tree")
    public Result<List<Map<String, Object>>> tree(@Header("X-User-Id") String userId,
                                                  @Param(value = "path", required = false) String path,
                                                  @Param(value = "depth", required = false) Integer depth) {
        return Result.succeed(files.tree(userId, path, depth == null ? 2 : depth));
    }

    /**
     * 读取工作区内的文本文件。
     *
     * @param userId 当前用户标识
     * @param path 文件相对路径
     * @return 文件路径、内容及服务层提供的附加元数据
     */
    @Get
    @Mapping("/read")
    public Result<Map<String, Object>> read(@Header("X-User-Id") String userId,
                                            @Param("path") String path) {
        return Result.succeed(files.read(userId, path));
    }

    /**
     * 新建或覆盖工作区内的文本文件。
     *
     * @param userId 当前用户标识
     * @param path 文件相对路径
     * @param content 完整文本内容，可为空
     * @return 保存后的文件元数据
     */
    @Post
    @Mapping("/save")
    public Result<Map<String, Object>> save(@Header("X-User-Id") String userId,
                                            @Param("path") String path,
                                            @Param(value = "content", required = false) String content) {
        return Result.succeed(files.save(userId, path, content));
    }

    /**
     * 在当前用户工作区内创建目录。
     *
     * @param userId 当前用户标识
     * @param path 待创建目录的相对路径
     * @return 无响应数据
     */
    @Post
    @Mapping("/directory")
    public Result<Void> directory(@Header("X-User-Id") String userId,
                                  @Param("path") String path) {
        files.createDirectory(userId, path);
        return Result.succeed();
    }

    /**
     * 删除工作区内的文件或目录。
     *
     * <p>是否允许递归删除以及根目录保护规则由服务层决定。</p>
     *
     * @param userId 当前用户标识
     * @param path 待删除对象的相对路径
     * @return 无响应数据
     */
    @Delete
    @Mapping
    public Result<Void> delete(@Header("X-User-Id") String userId,
                               @Param("path") String path) {
        files.delete(userId, path);
        return Result.succeed();
    }

    /**
     * 按文件名关键字搜索当前用户工作区。
     *
     * @param userId 当前用户标识
     * @param keyword 文件名匹配关键字
     * @return 命中的相对路径及节点元数据
     */
    @Get
    @Mapping("/search")
    public Result<List<Map<String, Object>>> search(@Header("X-User-Id") String userId,
                                                    @Param("keyword") String keyword) {
        return Result.succeed(files.search(userId, keyword));
    }

    /**
     * 按文件名称或文本内容检索当前工作区。
     *
     * @param userId 当前用户标识
     * @param keyword 检索关键字，必填
     * @param mode 检索模式：name 按文件名，content 按文件内容；缺省为 name
     * @param extensions 可选扩展名过滤列表，逗号分隔
     * @param maxResults 可选结果上限；为空时使用服务端默认值
     * @return 按文件分组且包含行列信息的检索结果
     */
    @Post
    @Mapping("/search")
    public Result<GlobalSearchResponse> globalSearch(
            @Header("X-User-Id") String userId,
            @Param("keyword") String keyword,
            @Param(value = "mode", required = false) String mode,
            @Param(value = "extensions", required = false) List<String> extensions,
            @Param(value = "maxResults", required = false) Integer maxResults) {
        return Result.succeed(files.globalSearch(userId, keyword, mode, extensions, maxResults));
    }

    /**
     * 上传一个文件到指定工作区目录。
     *
     * @param userId 当前用户标识
     * @param path 目标相对目录；为空表示工作区根目录
     * @param file multipart 上传文件
     * @return 保存后的节点元数据
     */
    @Post
    @Mapping("/upload")
    public Result<Map<String, Object>> upload(
            @Header("X-User-Id") String userId,
            @Param(value = "path", required = false) String path,
            @Param("file") UploadedFile file) {
        return Result.succeed(files.upload(userId, path, file));
    }

    /**
     * 下载工作区内的普通文件。
     *
     * @param userId 当前用户标识
     * @param path 文件相对路径
     * @return 流式下载对象
     */
    @Get
    @Mapping("/download")
    public DownloadedFile download(
            @Header("X-User-Id") String userId,
            @Param("path") String path) {
        return files.download(userId, path);
    }
}
