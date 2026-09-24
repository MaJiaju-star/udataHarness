package com.udata.harness.service.impl;

import com.udata.harness.common.domain.WorkspaceMetadata;
import com.udata.harness.service.UserWorkspaceService;
import org.noear.solon.Utils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 用户工作区实现，支持默认隔离目录和用户注册的本地目录。
 *
 * <p>生产装配默认枚举后端机器的全部文件系统根路径。所有注册目录都会解析为真实路径，
 * 并在注册和激活时重新确认仍处于可浏览范围内。</p>
 */
public class UserWorkspaceServiceImpl implements UserWorkspaceService {
    /**
     * 默认工作区标识，用户尚未注册任何本地目录时使用。
     */
    private static final String DEFAULT_WORKSPACE_ID = "default";

    /**
     * 注册表中记录当前激活工作区 Id 的属性键。
     */
    private static final String ACTIVE_KEY = "active";

    /**
     * 默认工作区根目录，隔离目录与激活的技能都置于其下。
     */
    private final Path workspaceRoot;

    /**
     * 用户工作区注册表目录，按用户持久化其注册与激活状态。
     */
    private final Path registryRoot;

    /**
     * 允许用户注册为工作区的根路径白名单，注册与激活时都会重新校验。
     */
    private final List<Path> allowedRoots;

    /**
     * 使用默认仓库根构造实例，仓库置于 workspaceRoot/.registry。
     *
     * @param workspaceRoot 用户工作区根目录
     */
    public UserWorkspaceServiceImpl(Path workspaceRoot) {
        this(workspaceRoot, workspaceRoot.resolve(".registry"), "");
    }

    /**
     * 创建工作区服务，并解析允许浏览的根目录配置。
     *
     * @param workspaceRoot 用户工作区根目录
     * @param dataRoot 应用数据根目录（工作区注册表存放处）
     * @param allowedRootConfig 允许浏览的根目录列表配置；为空时枚举全部文件系统根
     */
    public UserWorkspaceServiceImpl(Path workspaceRoot, Path dataRoot, String allowedRootConfig) {
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
        this.registryRoot = dataRoot.toAbsolutePath().normalize().resolve("workspaces");
        this.allowedRoots = resolveAllowedRoots(allowedRootConfig);
        try {
            Files.createDirectories(this.workspaceRoot);
            Files.createDirectories(this.registryRoot);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create workspace directories", e);
        }
    }

    /**
     * 返回当前激活目录；首次访问时创建并注册用户默认目录。
     *
     * @param userId 当前用户标识
     * @return 当前激活工作区的绝对路径
     */
    @Override
    public Path getOrCreate(String userId) {
        return Paths.get(getActive(userId).getPath());
    }

    /**
     * 读取当前用户的全部工作区，并补充默认工作区。
     *
     * @param userId 当前用户标识
     * @return 工作区列表，按最近打开时间倒序
     */
    @Override
    public List<WorkspaceMetadata> list(String userId) {
        String safeUserId = UserWorkspaceService.requireUserId(userId);
        Properties properties = load(safeUserId);
        ensureDefault(safeUserId, properties);
        String activeId = properties.getProperty(ACTIVE_KEY, DEFAULT_WORKSPACE_ID);
        List<WorkspaceMetadata> result = new ArrayList<>();
        for (String key : properties.stringPropertyNames()) {
            if (!key.endsWith(".path")) {
                continue;
            }
            String workspaceId = key.substring(0, key.length() - ".path".length());
            result.add(toMetadata(properties, workspaceId, activeId));
        }
        result.sort(Comparator.comparingLong(WorkspaceMetadata::getLastOpenedAt).reversed());
        return result;
    }

    /**
     * 返回当前激活工作区；失效记录会回退到默认工作区。
     *
     * @param userId 当前用户标识
     * @return 当前激活工作区元数据
     */
    @Override
    public WorkspaceMetadata getActive(String userId) {
        String safeUserId = UserWorkspaceService.requireUserId(userId);
        Properties properties = load(safeUserId);
        ensureDefault(safeUserId, properties);
        String activeId = properties.getProperty(ACTIVE_KEY, DEFAULT_WORKSPACE_ID);
        WorkspaceMetadata metadata = toMetadata(properties, activeId, activeId);
        Path path = Paths.get(metadata.getPath());
        if (!Files.isDirectory(path)) {
            properties.setProperty(ACTIVE_KEY, DEFAULT_WORKSPACE_ID);
            save(safeUserId, properties);
            metadata = toMetadata(properties, DEFAULT_WORKSPACE_ID, DEFAULT_WORKSPACE_ID);
        }
        return metadata;
    }

    /**
     * 注册并激活一个本地目录。
     *
     * @param userId 当前用户标识
     * @param path 本地目录绝对路径
     * @return 注册后的工作区元数据
     * @throws IllegalArgumentException 目录不存在或不在允许浏览范围内时抛出
     */
    @Override
    public WorkspaceMetadata register(String userId, String path) {
        //1. 解析目录并确认它位于后端允许浏览的根路径内。
        String safeUserId = UserWorkspaceService.requireUserId(userId);
        Path directory = resolveAllowed(path);
        if (!Files.isDirectory(directory)) {
            throw new IllegalArgumentException("Directory not found");
        }

        //2. 复用同路径记录或创建新记录，并持久化为当前工作区。
        Properties properties = load(safeUserId);
        ensureDefault(safeUserId, properties);
        String workspaceId = findByPath(properties, directory);
        if (workspaceId == null) {
            workspaceId = "ws-" + UUID.randomUUID();
        }
        properties.setProperty(workspaceId + ".path", directory.toString());
        properties.setProperty(workspaceId + ".name", displayName(directory));
        properties.setProperty(workspaceId + ".lastOpenedAt", String.valueOf(System.currentTimeMillis()));
        properties.setProperty(ACTIVE_KEY, workspaceId);
        save(safeUserId, properties);
        return toMetadata(properties, workspaceId, workspaceId);
    }

    /**
     * 激活一个已经注册且仍然存在的工作区。
     *
     * @param userId 当前用户标识
     * @param workspaceId 已注册工作区标识
     * @return 激活后的工作区元数据
     * @throws IllegalArgumentException 工作区不存在或目录已失效时抛出
     */
    @Override
    public WorkspaceMetadata activate(String userId, String workspaceId) {
        String safeUserId = UserWorkspaceService.requireUserId(userId);
        Properties properties = load(safeUserId);
        ensureDefault(safeUserId, properties);
        if (workspaceId == null || properties.getProperty(workspaceId + ".path") == null) {
            throw new IllegalArgumentException("Workspace not found");
        }
        Path directory = Paths.get(properties.getProperty(workspaceId + ".path"));
        if (!Files.isDirectory(directory)) {
            throw new IllegalArgumentException("Workspace directory not found");
        }
        properties.setProperty(ACTIVE_KEY, workspaceId);
        properties.setProperty(workspaceId + ".lastOpenedAt", String.valueOf(System.currentTimeMillis()));
        save(safeUserId, properties);
        return toMetadata(properties, workspaceId, workspaceId);
    }

    /**
     * 列出可供用户开始浏览的文件系统根节点。
     *
     * @return 根目录节点列表
     */
    @Override
    public List<Map<String, Object>> roots() {
        return allowedRoots.stream().map(this::directoryNode).collect(Collectors.toList());
    }

    /**
     * 列出目录的直接子目录，不递归扫描磁盘。
     *
     * @param path 父目录绝对路径
     * @return 子目录节点列表，已过滤隐藏目录和符号链接
     * @throws IllegalArgumentException 目录不存在或不在允许范围内时抛出
     */
    @Override
    public List<Map<String, Object>> children(String path) {
        Path parent = resolveAllowed(path);
        if (!Files.isDirectory(parent)) {
            throw new IllegalArgumentException("Directory not found");
        }
        try (Stream<Path> paths = Files.list(parent)) {
            return paths.filter(Files::isDirectory)
                    .filter(item -> !Files.isSymbolicLink(item))
                    .filter(item -> !isHidden(item))
                    .sorted(Comparator.comparing(item -> item.getFileName().toString().toLowerCase()))
                    .map(this::directoryNode)
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new IllegalStateException("Cannot list local directories", e);
        }
    }

    @Override
    public Path getWorkspaceRoot() {
        return workspaceRoot;
    }

    /**
     * 解析允许浏览的根目录列表。
     *
     * @param configured 分号或换行分隔的根目录配置
     * @return 规范化后的允许根目录；配置为空时返回全部文件系统根
     */
    private List<Path> resolveAllowedRoots(String configured) {
        List<Path> roots = new ArrayList<>();
        if (Utils.isNotBlank(configured)) {
            for (String value : configured.split("[;\n]")) {
                if (Utils.isNotBlank(value)) {
                    roots.add(Paths.get(value.trim()).toAbsolutePath().normalize());
                }
            }
        } else {
            FileSystems.getDefault().getRootDirectories().forEach(roots::add);
        }
        return roots;
    }

    /**
     * 校验路径处于允许范围内，并返回解析真实路径后的规范化结果。
     *
     * @param value 原始路径
     * @return 真实路径（符号链接已解析）
     * @throws IllegalArgumentException 路径为空、越出或无法解析时抛出
     */
    private Path resolveAllowed(String value) {
        if (Utils.isBlank(value)) {
            throw new IllegalArgumentException("path is required");
        }
        Path path = Paths.get(value).toAbsolutePath().normalize();
        boolean allowed = allowedRoots.stream().anyMatch(path::startsWith);
        if (!allowed) {
            throw new IllegalArgumentException("Directory is outside allowed roots");
        }
        try {
            Path resolved = Files.exists(path) ? path.toRealPath() : path;
            boolean resolvedAllowed = allowedRoots.stream().anyMatch(root -> {
                try {
                    Path resolvedRoot = Files.exists(root) ? root.toRealPath() : root;
                    return resolved.startsWith(resolvedRoot);
                } catch (IOException e) {
                    return false;
                }
            });
            if (!resolvedAllowed) {
                throw new IllegalArgumentException("Directory is outside allowed roots");
            }
            return resolved;
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot resolve directory", e);
        }
    }

    /**
     * 将目录路径封装为前端可浏览的节点。
     *
     * @param path 目标目录
     * @return 包含 name/path/writable 的节点
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> directoryNode(Path path) {
        return Utils.asMap("name", displayName(path), "path", path.toString(),
                "writable", Files.isWritable(path));
    }

    /**
     * 判断路径是否为隐藏目录，异常时保守地视为隐藏。
     *
     * @param path 目标路径
     * @return 隐藏或无法判定时为 true
     */
    private boolean isHidden(Path path) {
        try {
            return Files.isHidden(path);
        } catch (IOException e) {
            return true;
        }
    }

    /**
     * 确保注册表中存在默认工作区记录，不存在时创建并写入。
     *
     * @param userId 当前用户标识
     * @param properties 已加载的注册表属性
     */
    private void ensureDefault(String userId, Properties properties) {
        if (properties.getProperty(DEFAULT_WORKSPACE_ID + ".path") != null) {
            return;
        }
        Path directory = workspaceRoot.resolve(userId).normalize();
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create user workspace", e);
        }
        properties.setProperty(DEFAULT_WORKSPACE_ID + ".path", directory.toString());
        properties.setProperty(DEFAULT_WORKSPACE_ID + ".name", userId);
        properties.setProperty(DEFAULT_WORKSPACE_ID + ".lastOpenedAt", "0");
        properties.setProperty(ACTIVE_KEY, DEFAULT_WORKSPACE_ID);
        save(userId, properties);
    }

    /**
     * 在注册表中按路径反查已有的工作区标识。
     *
     * @param properties 注册表属性
     * @param directory 目录路径
     * @return 已注册的 workspaceId；未找到时返回 null
     */
    private String findByPath(Properties properties, Path directory) {
        for (String key : properties.stringPropertyNames()) {
            if (key.endsWith(".path") && directory.toString().equals(properties.getProperty(key))) {
                return key.substring(0, key.length() - ".path".length());
            }
        }
        return null;
    }

    /**
     * 将注册表条目转换为工作区元数据，并标记是否激活。
     *
     * @param properties 注册表属性
     * @param workspaceId 目标工作区标识
     * @param activeId 当前激活工作区标识
     * @return 工作区元数据
     */
    private WorkspaceMetadata toMetadata(Properties properties, String workspaceId, String activeId) {
        WorkspaceMetadata metadata = new WorkspaceMetadata();
        metadata.setWorkspaceId(workspaceId);
        metadata.setName(properties.getProperty(workspaceId + ".name", workspaceId));
        metadata.setPath(properties.getProperty(workspaceId + ".path"));
        metadata.setLastOpenedAt(parseLong(properties.getProperty(workspaceId + ".lastOpenedAt")));
        metadata.setActive(workspaceId.equals(activeId));
        return metadata;
    }

    /**
     * 读取用户工作区注册表；文件不存在时返回空属性。
     *
     * @param userId 当前用户标识
     * @return 注册表属性
     */
    private Properties load(String userId) {
        Properties properties = new Properties();
        Path file = registryFile(userId);
        if (!Files.isRegularFile(file)) {
            return properties;
        }
        try (InputStream input = Files.newInputStream(file)) {
            properties.load(input);
            return properties;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read workspace registry", e);
        }
    }

    /**
     * 持久化用户工作区注册表。
     *
     * @param userId 当前用户标识
     * @param properties 待写入属性
     */
    private void save(String userId, Properties properties) {
        Path file = registryFile(userId);
        try {
            Files.createDirectories(file.getParent());
            try (OutputStream output = Files.newOutputStream(file)) {
                properties.store(output, "udata-harness workspaces");
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot save workspace registry", e);
        }
    }

    /**
     * 计算用户工作区注册表文件路径。
     *
     * @param userId 当前用户标识
     * @return 注册表文件绝对路径
     */
    private Path registryFile(String userId) {
        return registryRoot.resolve(userId).resolve("workspaces.properties");
    }

    /**
     * 生成目录的展示名称，无文件名时回退到完整路径。
     *
     * @param path 目标路径
     * @return 用于 UI 展示的名称
     */
    private String displayName(Path path) {
        Path name = path.getFileName();
        return name == null ? path.toString() : name.toString();
    }

    /**
     * 安全解析 long 字符串，解析失败时返回 0。
     *
     * @param value 原始文本
     * @return 解析结果或 0
     */
    private long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (Exception ignored) {
            return 0L;
        }
    }
}
