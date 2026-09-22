package com.udata.harness.service;

import java.util.List;
import java.util.Map;

/**
 * 文件面板用例；所有路径都必须限制在当前用户工作区内。
 *
 * <p>实现层负责相对路径归一化、真实路径 containment 校验、符号链接规避、隐藏目录过滤
 * 以及文件大小/搜索数量限制。调用方不得将返回路径当作服务器绝对路径。</p>
 */
public interface WorkspaceFileService {
    /** 构建目录树；实现可对 depth 做安全范围裁剪。 */
    List<Map<String, Object>> tree(String userId, String path, int depth);

    /** 读取受大小限制的 UTF-8 文本文件及元数据。 */
    Map<String, Object> read(String userId, String path);

    /** 以 UTF-8 新建或覆盖文本文件，必要时创建父目录。 */
    Map<String, Object> save(String userId, String path, String content);

    /** 递归创建工作区内的相对目录。 */
    void createDirectory(String userId, String path);

    /** 递归删除文件或目录，但绝不允许删除用户工作区根目录。 */
    void delete(String userId, String path);

    /** 在安全深度与结果上限内按相对路径关键字搜索。 */
    List<Map<String, Object>> search(String userId, String keyword);
}
