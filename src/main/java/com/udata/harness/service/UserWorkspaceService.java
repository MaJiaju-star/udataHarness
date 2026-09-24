package com.udata.harness.service;

import com.udata.harness.common.domain.WorkspaceMetadata;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 用户专属工作区边界，负责创建目录并校验用户标识。
 *
 * <p>所有需要访问用户文件的服务都应通过本接口获取根目录，禁止在业务代码中自行用
 * userId 拼接磁盘路径。</p>
 */
public interface UserWorkspaceService {
    /**
     * 校验 userId，并返回已存在或新创建的用户工作区绝对路径。
     *
     * @param userId 当前用户标识
     * @return 用户工作区绝对路径
     */
    Path getOrCreate(String userId);

    /**
     * 返回当前用户已注册的工作区，按最近打开时间倒序排列。
     *
     * @param userId 当前用户标识
     * @return 工作区列表
     */
    List<WorkspaceMetadata> list(String userId);

    /**
     * 返回当前用户正在使用的工作区。
     *
     * @param userId 当前用户标识
     * @return 当前激活工作区元数据
     */
    WorkspaceMetadata getActive(String userId);

    /**
     * 注册本地目录并立即将其设为当前工作区。
     *
     * @param userId 当前用户标识
     * @param path 本地目录绝对路径
     * @return 注册后的工作区元数据
     */
    WorkspaceMetadata register(String userId, String path);

    /**
     * 激活已注册的工作区。
     *
     * @param userId 当前用户标识
     * @param workspaceId 已注册工作区标识
     * @return 激活后的工作区元数据
     */
    WorkspaceMetadata activate(String userId, String workspaceId);

    /**
     * 返回后端允许浏览的本地目录根节点。
     *
     * @return 根目录节点列表
     */
    List<Map<String, Object>> roots();

    /**
     * 返回指定本地目录的直接子目录。
     *
     * @param path 父目录绝对路径
     * @return 子目录节点列表
     */
    List<Map<String, Object>> children(String path);

    /**
     * 返回容纳全部用户目录的应用工作区根路径。
     *
     * @return 工作区根路径
     */
    Path getWorkspaceRoot();

    /**
     * 校验可安全用于目录名的用户 ID，阻止路径穿越和超长名称。
     *
     * <p>允许 1 到 64 个字符，首字符必须为字母或数字，其余字符可包含点、下划线
     * 和连字符。</p>
     *
     * @param userId 原始请求头值
     * @return 去除首尾空白后的安全用户标识
     * @throws IllegalArgumentException 用户标识为空或不符合安全格式时
     */
    static String requireUserId(String userId) {
        String value = userId == null ? "" : userId.trim();
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
            throw new IllegalArgumentException("Invalid X-User-Id");
        }
        return value;
    }
}
