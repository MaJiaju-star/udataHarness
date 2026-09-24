package com.udata.harness.repository;

import com.udata.harness.common.domain.SessionMetadata;
import com.udata.harness.service.UserWorkspaceService;
import org.noear.solon.Utils;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.AgentSessionProvider;
import org.noear.solon.ai.agent.session.FileAgentSession;
import org.noear.solon.ai.chat.ChatRole;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.lang.NonNull;
import org.noear.solon.lang.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * 基于文件系统的会话仓储，同时实现 Solon AI {@link AgentSessionProvider}。
 *
 * <p>每个用户拥有独立 sessions 目录；会话 ID 和用户 ID 都经过白名单校验，
 * 标准化路径必须留在数据根目录内。内存缓存只保存活动 AgentSession，真实消息由
 * {@link FileAgentSession} 写入会话目录，应用重启后仍可恢复。</p>
 */
public class SessionRepository implements AgentSessionProvider {
    /**
     * 会话元数据文件名，存放标题、工作区、模型选项等。
     */
    private static final String META_FILE = "meta.properties";

    /**
     * Web 会话 ID 前缀，用于区分不同来源的会话。
     */
    private static final String SESSION_PREFIX = "web-";

    /**
     * 数据根下的用户目录，每个用户一个子目录存放其会话。
     */
    private final Path usersRoot;

    /**
     * 活动 AgentSession 内存缓存，键为会话 ID；真实消息由 FileAgentSession 落盘。
     */
    private final Map<String, AgentSession> sessions = new ConcurrentHashMap<>();

    /**
     * 会话 ID 到所属用户 ID 的映射，用于跨用户隔离校验。
     */
    private final Map<String, String> owners = new ConcurrentHashMap<>();

    /**
     * 构造会话仓储，确保数据根下的 users 目录存在。
     *
     * @param dataRoot 应用数据根目录
     */
    public SessionRepository(Path dataRoot) {
        this.usersRoot = dataRoot.resolve("users").toAbsolutePath().normalize();
        try {
            Files.createDirectories(usersRoot);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create user data directory: " + usersRoot, e);
        }
    }

    /**
     * 创建会话目录、元数据和框架 Session。
     *
     * <p>目录先落盘，再把实例加入缓存；后续查找可通过 owners 索引快速校验用户归属。</p>
     *
     * @param userId 当前用户标识
     * @param title 会话标题，可为空（回退占位标题）
     * @param model 模型名，可为空
     * @return 新建的会话元数据，绑定到 default 工作区
     */
    public SessionMetadata create(String userId, String title, String model) {
        return create(userId, title, model, "default");
    }

    /**
     * 创建绑定到指定工作区的会话。
     *
     * @param userId 当前用户标识
     * @param title 会话标题，可为空
     * @param model 模型名，可为空
     * @param workspaceId 工作区标识，为空时回退 default
     * @return 新建的会话元数据
     */
    public SessionMetadata create(String userId, String title, String model, String workspaceId) {
        userId = UserWorkspaceService.requireUserId(userId);
        String sessionId = SESSION_PREFIX + UUID.randomUUID();
        long now = System.currentTimeMillis();
        SessionMetadata metadata = new SessionMetadata();
        metadata.setSessionId(sessionId);
        metadata.setUserId(userId);
        metadata.setWorkspaceId(Utils.isBlank(workspaceId) ? "default" : workspaceId);
        metadata.setTitle(normalizeTitle(title));
        metadata.setModel(model == null ? "" : model.trim());
        metadata.setPermissionMode("standard");
        metadata.setCreatedAt(now);
        metadata.setUpdatedAt(now);
        save(userId, metadata);
        owners.put(sessionId, userId);
        getSession(userId, sessionId);
        return metadata;
    }

    @Override
    /**
     * 按全局 sessionId 获取会话。
     *
     * <p>该重载供框架 SessionProvider 使用；业务 HTTP 层应优先调用带 userId 的重载。</p>
     *
     * @param sessionId 全局会话标识
     * @return 对应 AgentSession
     * @throws IllegalArgumentException 会话不存在时抛出
     */
    public @NonNull AgentSession getSession(String sessionId) {
        requireValidId(sessionId);
        String userId = owners.computeIfAbsent(sessionId, this::findOwner);
        if (userId == null) {
            throw new IllegalArgumentException("Session not found: " + sessionId);
        }
        return getSession(userId, sessionId);
    }

    /**
     * 校验用户归属后读取会话，防止仅凭 sessionId 跨用户访问。
     *
     * @param userId 当前用户标识
     * @param sessionId 目标会话标识
     * @return 对应 AgentSession
     * @throws IllegalArgumentException 会话不属于该用户或不存在时抛出
     */
    public @NonNull AgentSession getSession(String userId, String sessionId) {
        userId = UserWorkspaceService.requireUserId(userId);
        requireValidId(sessionId);
        Path sessionDir = sessionPath(userId, sessionId);
        if (!Files.isDirectory(sessionDir)) {
            throw new IllegalArgumentException("Session not found: " + sessionId);
        }
        owners.put(sessionId, userId);
        return sessions.computeIfAbsent(sessionId,
                key -> new FileAgentSession(key, sessionDir.toString()));
    }

    /**
     * 从内存缓存移除会话，不删除磁盘数据。
     *
     * @param sessionId 目标会话标识
     * @return 被移除的活动 AgentSession；不存在时返回 null
     */
    public @Nullable AgentSession removeSession(String sessionId) {
        requireValidId(sessionId);
        return sessions.remove(sessionId);
    }

    /**
     * 扫描用户会话元数据并按更新时间倒序返回。
     *
     * <p>列表不依赖内存缓存，因此服务重启后仍能完整发现历史会话。</p>
     *
     * @param userId 当前用户标识
     * @return 会话元数据列表，按更新时间倒序
     */
    public List<SessionMetadata> list(String userId) {
        final String safeUserId = UserWorkspaceService.requireUserId(userId);
        Path sessionsRoot = sessionsRoot(safeUserId);
        List<SessionMetadata> result = new ArrayList<>();
        if (!Files.isDirectory(sessionsRoot)) {
            return result;
        }
        try (Stream<Path> paths = Files.list(sessionsRoot)) {
            paths.filter(Files::isDirectory)
                    .map(Path::getFileName)
                    .map(Path::toString)
                    .filter(SessionRepository::isValidId)
                    .map(id -> read(safeUserId, id))
                    .forEach(result::add);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot list sessions", e);
        }
        result.sort(Comparator.comparingLong(SessionMetadata::getUpdatedAt).reversed());
        return result;
    }

    /**
     * 读取并校验单个会话元数据；不存在或所有者不匹配时统一失败。
     *
     * @param userId 当前用户标识
     * @param sessionId 目标会话标识
     * @return 会话元数据
     * @throws IllegalArgumentException 会话标识非法或元数据文件不存在时抛出
     */
    public SessionMetadata read(String userId, String sessionId) {
        userId = UserWorkspaceService.requireUserId(userId);
        requireValidId(sessionId);
        Path metaPath = sessionPath(userId, sessionId).resolve(META_FILE);
        if (!Files.isRegularFile(metaPath)) {
            throw new IllegalArgumentException("Session not found: " + sessionId);
        }
        Properties props = new Properties();
        try (InputStream input = Files.newInputStream(metaPath)) {
            props.load(input);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read session: " + sessionId, e);
        }

        SessionMetadata metadata = new SessionMetadata();
        metadata.setSessionId(sessionId);
        metadata.setUserId(userId);
        metadata.setWorkspaceId(props.getProperty("workspaceId", "default"));
        metadata.setTitle(props.getProperty("title", "New session"));
        metadata.setModel(props.getProperty("model", ""));
        metadata.setPermissionMode(normalizePermissionMode(props.getProperty("permissionMode")));
        metadata.setCreatedAt(parseLong(props.getProperty("createdAt")));
        metadata.setUpdatedAt(parseLong(props.getProperty("updatedAt")));
        return metadata;
    }

    /**
     * 在一次运行完成后更新时间戳，同时保留模型和权限等稳定字段。
     *
     * @param userId 当前用户标识
     * @param sessionId 目标会话标识
     */
    public void touch(String userId, String sessionId) {
        SessionMetadata metadata = read(userId, sessionId);
        metadata.setUpdatedAt(System.currentTimeMillis());
        save(userId, metadata);
    }

    /**
     * 串行更新权限模式，避免并发请求互相覆盖元数据文件；同时兼容大小写或未知输入。
     *
     * @param userId 当前用户标识
     * @param sessionId 目标会话标识
     * @param permissionMode 目标权限模式
     * @return 更新后的会话元数据
     */
    public synchronized SessionMetadata updatePermissionMode(
            String userId, String sessionId, String permissionMode) {
        SessionMetadata metadata = read(userId, sessionId);
        metadata.setPermissionMode(normalizePermissionMode(permissionMode));
        metadata.setUpdatedAt(System.currentTimeMillis());
        save(userId, metadata);
        return metadata;
    }

    /**
     * 显式重命名会话，并保留模型、权限与创建时间等其他元数据。
     *
     * @param userId 当前用户标识
     * @param sessionId 目标会话标识
     * @param title 新标题
     * @return 更新后的会话元数据
     * @throws IllegalArgumentException 标题为空时抛出
     */
    public synchronized SessionMetadata updateTitle(
            String userId, String sessionId, String title) {
        if (title == null || title.trim().isEmpty()) {
            throw new IllegalArgumentException("title is required");
        }
        SessionMetadata metadata = read(userId, sessionId);
        metadata.setTitle(normalizeTitle(title));
        metadata.setUpdatedAt(System.currentTimeMillis());
        save(userId, metadata);
        return metadata;
    }

    /**
     * 用会话第一条用户问题生成标题。
     *
     * <p>仅替换创建时的占位标题，用户显式重命名后的标题不会被后续提问覆盖。对旧会话
     * 会优先读取已经持久化的第一条用户消息；新会话则使用当前即将提交的问题。</p>
     *
     * @param userId 当前用户标识
     * @param sessionId 目标会话标识
     * @param prompt 当前即将提交的问题（回退值）
     * @return 更新后的会话元数据
     */
    public synchronized SessionMetadata applyFirstPromptTitle(
            String userId, String sessionId, String prompt) {
        SessionMetadata metadata = read(userId, sessionId);
        if (!isPlaceholderTitle(metadata.getTitle())) {
            return metadata;
        }
        String firstPrompt = "";
        for (ChatMessage message : getSession(userId, sessionId).getMessages()) {
            if (message.getRole() == ChatRole.USER
                    && Utils.isNotBlank(message.getContent())) {
                firstPrompt = message.getContent();
                break;
            }
        }
        metadata.setTitle(normalizeTitle(Utils.isBlank(firstPrompt) ? prompt : firstPrompt));
        save(userId, metadata);
        return metadata;
    }

    /**
     * 删除会话缓存、所有者索引和磁盘目录。
     *
     * <p>调用方必须先取消活动运行，仓储层只负责数据一致性而不管理 Reactor 订阅。</p>
     *
     * @param userId 当前用户标识
     * @param sessionId 目标会话标识
     */
    public void delete(String userId, String sessionId) {
        userId = UserWorkspaceService.requireUserId(userId);
        requireValidId(sessionId);
        removeSession(sessionId);
        owners.remove(sessionId);
        Path target = sessionPath(userId, sessionId);
        if (!Files.exists(target)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(target)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException e) {
                    throw new IllegalStateException("Cannot delete " + path, e);
                }
            });
        } catch (IOException e) {
            throw new IllegalStateException("Cannot delete session: " + sessionId, e);
        }
    }

    /**
     * 返回容纳全部用户会话目录的根路径。
     *
     * @return users 根目录绝对路径
     */
    public Path getSessionsRoot() {
        return usersRoot;
    }

    /**
     * 将会话元数据写入磁盘 meta.properties。
     *
     * @param userId 当前用户标识
     * @param metadata 待保存的元数据
     */
    private void save(String userId, SessionMetadata metadata) {
        userId = UserWorkspaceService.requireUserId(userId);
        requireValidId(metadata.getSessionId());
        Path sessionDir = sessionPath(userId, metadata.getSessionId());
        try {
            Files.createDirectories(sessionDir);
            Properties props = new Properties();
            props.setProperty("title", normalizeTitle(metadata.getTitle()));
            props.setProperty("model", metadata.getModel() == null ? "" : metadata.getModel());
            props.setProperty("workspaceId", metadata.getWorkspaceId() == null
                    ? "default" : metadata.getWorkspaceId());
            props.setProperty(
                    "permissionMode", normalizePermissionMode(metadata.getPermissionMode()));
            props.setProperty("createdAt", String.valueOf(metadata.getCreatedAt()));
            props.setProperty("updatedAt", String.valueOf(metadata.getUpdatedAt()));
            try (OutputStream output = Files.newOutputStream(sessionDir.resolve(META_FILE))) {
                props.store(output, "udata-harness session");
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot save session: " + metadata.getSessionId(), e);
        }
    }

    /**
     * 计算并校验用户会话根目录。
     *
     * @param userId 当前用户标识
     * @return 用户会话根目录
     * @throws IllegalArgumentException userId 越出 users 根目录时抛出
     */
    private Path sessionsRoot(String userId) {
        Path root = usersRoot.resolve(userId).resolve("sessions").normalize();
        if (!root.startsWith(usersRoot)) {
            throw new IllegalArgumentException("Invalid userId");
        }
        return root;
    }

    /**
     * 计算并校验单个会话目录。
     *
     * @param userId 当前用户标识
     * @param sessionId 目标会话标识
     * @return 会话目录绝对路径
     * @throws IllegalArgumentException sessionId 越出会话根目录时抛出
     */
    private Path sessionPath(String userId, String sessionId) {
        Path sessionsRoot = sessionsRoot(userId);
        Path path = sessionsRoot.resolve(sessionId).normalize();
        if (!path.startsWith(sessionsRoot)) {
            throw new IllegalArgumentException("Invalid sessionId");
        }
        return path;
    }

    /**
     * 查找 sessionId 的所有者并回填内存索引。
     *
     * <p>冷启动时 owners 为空，因此需要在 users 根目录下扫描；命中后缓存以避免重复扫描。</p>
     *
     * @param sessionId 目标会话标识
     * @return 所有者 userId；未找到时返回 null
     */
    private String findOwner(String sessionId) {
        try (Stream<Path> users = Files.list(usersRoot)) {
            return users.filter(Files::isDirectory)
                    .map(Path::getFileName)
                    .map(Path::toString)
                    .filter(user -> Files.isDirectory(sessionPath(user, sessionId)))
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot locate session owner", e);
        }
    }

    private static boolean isValidId(String sessionId) {
        return sessionId != null && sessionId.matches("web-[0-9a-fA-F-]{36}");
    }

    private static void requireValidId(String sessionId) {
        if (!isValidId(sessionId)) {
            throw new IllegalArgumentException("Invalid sessionId");
        }
    }

    private static String normalizeTitle(String title) {
        String value = title == null ? "" : title.trim().replaceAll("[\\r\\n]+", " ");
        if (value.isEmpty()) {
            return "New session";
        }
        return value.length() > 80 ? value.substring(0, 80) : value;
    }

    private static boolean isPlaceholderTitle(String title) {
        String value = title == null ? "" : title.trim();
        return value.isEmpty()
                || "New session".equalsIgnoreCase(value)
                || "新的编码任务".equals(value);
    }

    private static String normalizePermissionMode(String permissionMode) {
        return "full".equalsIgnoreCase(permissionMode) ? "full" : "standard";
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (Exception ignored) {
            return 0L;
        }
    }
}
