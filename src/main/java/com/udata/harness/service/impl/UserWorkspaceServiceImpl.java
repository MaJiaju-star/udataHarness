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
    private static final String DEFAULT_WORKSPACE_ID = "default";
    private static final String ACTIVE_KEY = "active";

    private final Path workspaceRoot;
    private final Path registryRoot;
    private final List<Path> allowedRoots;

    public UserWorkspaceServiceImpl(Path workspaceRoot) {
        this(workspaceRoot, workspaceRoot.resolve(".registry"), "");
    }

    /** 创建工作区服务，并解析允许浏览的根目录配置。 */
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

    /** 返回当前激活目录；首次访问时创建并注册用户默认目录。 */
    @Override
    public Path getOrCreate(String userId) {
        return Paths.get(getActive(userId).getPath());
    }

    /** 读取当前用户的全部工作区，并补充默认工作区。 */
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

    /** 返回当前激活工作区；失效记录会回退到默认工作区。 */
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

    /** 注册并激活一个本地目录。 */
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

    /** 激活一个已经注册且仍然存在的工作区。 */
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

    /** 列出可供用户开始浏览的文件系统根节点。 */
    @Override
    public List<Map<String, Object>> roots() {
        return allowedRoots.stream().map(this::directoryNode).collect(Collectors.toList());
    }

    /** 列出目录的直接子目录，不递归扫描磁盘。 */
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> directoryNode(Path path) {
        return Utils.asMap("name", displayName(path), "path", path.toString(),
                "writable", Files.isWritable(path));
    }

    private boolean isHidden(Path path) {
        try {
            return Files.isHidden(path);
        } catch (IOException e) {
            return true;
        }
    }

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

    private String findByPath(Properties properties, Path directory) {
        for (String key : properties.stringPropertyNames()) {
            if (key.endsWith(".path") && directory.toString().equals(properties.getProperty(key))) {
                return key.substring(0, key.length() - ".path".length());
            }
        }
        return null;
    }

    private WorkspaceMetadata toMetadata(Properties properties, String workspaceId, String activeId) {
        WorkspaceMetadata metadata = new WorkspaceMetadata();
        metadata.setWorkspaceId(workspaceId);
        metadata.setName(properties.getProperty(workspaceId + ".name", workspaceId));
        metadata.setPath(properties.getProperty(workspaceId + ".path"));
        metadata.setLastOpenedAt(parseLong(properties.getProperty(workspaceId + ".lastOpenedAt")));
        metadata.setActive(workspaceId.equals(activeId));
        return metadata;
    }

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

    private Path registryFile(String userId) {
        return registryRoot.resolve(userId).resolve("workspaces.properties");
    }

    private String displayName(Path path) {
        Path name = path.getFileName();
        return name == null ? path.toString() : name.toString();
    }

    private long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (Exception ignored) {
            return 0L;
        }
    }
}
