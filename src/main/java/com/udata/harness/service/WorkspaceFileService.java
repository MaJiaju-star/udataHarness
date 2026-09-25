package com.udata.harness.service;

import com.udata.harness.common.domain.GlobalSearchResponse;
import org.noear.solon.core.handle.DownloadedFile;
import org.noear.solon.core.handle.UploadedFile;

import java.util.List;
import java.util.Map;

/**
 * 文件面板用例；所有路径都必须限制在当前用户工作区内。
 *
 * <p>实现层负责相对路径归一化、真实路径 containment 校验、符号链接规避，以及文件
 * 大小和搜索数量限制。调用方不得将返回路径当作服务器绝对路径。</p>
 */
public interface WorkspaceFileService {
    /**
     * 构建目录树；实现可对 depth 做安全范围裁剪。
     *
     * @param userId 当前用户标识
     * @param path 相对目录路径
     * @param depth 递归深度
     * @return 目录树节点列表
     */
    List<Map<String, Object>> tree(String userId, String path, int depth);

    /**
     * 读取受大小限制的 UTF-8 文本文件及元数据。
     *
     * @param userId 当前用户标识
     * @param path 文件相对路径
     * @return 文件元数据与文本内容
     */
    Map<String, Object> read(String userId, String path);

    /**
     * 以 UTF-8 新建或覆盖文本文件，必要时创建父目录。
     *
     * @param userId 当前用户标识
     * @param path 目标文件相对路径
     * @param content 完整文本内容
     * @return 写入后的文件元数据
     */
    Map<String, Object> save(String userId, String path, String content);

    /**
     * 递归创建工作区内的相对目录。
     *
     * @param userId 当前用户标识
     * @param path 待创建目录相对路径
     */
    void createDirectory(String userId, String path);

    /**
     * 递归删除文件或目录，但绝不允许删除用户工作区根目录。
     *
     * @param userId 当前用户标识
     * @param path 待删除对象相对路径
     */
    void delete(String userId, String path);

    /**
     * 在安全深度与结果上限内按相对路径关键字搜索。
     *
     * @param userId 当前用户标识
     * @param keyword 匹配关键字
     * @return 命中的节点元数据列表
     */
    List<Map<String, Object>> search(String userId, String keyword);

    /**
     * 按文件名称或文本内容执行带扩展名过滤的全局检索。
     *
     * @param userId 当前用户标识
     * @param keyword 检索词
     * @param mode 检索模式：name 或 content
     * @param extensions 可选扩展名过滤列表
     * @param maxResults 可选结果上限；为空时使用默认值
     * @return 按文件分组的检索结果
     */
    GlobalSearchResponse globalSearch(String userId, String keyword, String mode, List<String> extensions, Integer maxResults);

    /**
     * 将上传文件保存到工作区指定目录，重名文件会被覆盖。
     *
     * @param userId 当前用户标识
     * @param directory 目标相对目录，可为空
     * @param file 上传文件
     * @return 保存后的节点元数据
     */
    Map<String, Object> upload(String userId, String directory, UploadedFile file);

    /**
     * 返回工作区内普通文件的流式下载对象。
     *
     * @param userId 当前用户标识
     * @param path 文件相对路径
     * @return 下载对象
     */
    DownloadedFile download(String userId, String path);
}
