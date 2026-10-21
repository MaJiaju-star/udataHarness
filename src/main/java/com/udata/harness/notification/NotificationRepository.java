package com.udata.harness.notification;

import com.udata.harness.repository.ModelConfigurationFiles;
import com.udata.harness.service.UserWorkspaceService;
import org.noear.snack4.ONode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 通知数据的文件存储：每位用户一个 {@code notifications.json}，
 * 位于 {@code <root>/users/<userId>/} 下，与用户工作区目录同级。
 *
 * <p>写入采用「临时文件 + 原子替换」，并通过 {@link ModelConfigurationFiles}
 * 标记为受保护文件；文件损坏或结构非法时以 {@link IllegalStateException} 报错，
 * 不做静默重置，避免覆盖用户已有配置。</p>
 */
public final class NotificationRepository {

    /** 用户目录根路径（{@code <root>/users}）。 */
    private final Path users;

    public NotificationRepository(Path root) {
        users = root.toAbsolutePath().normalize().resolve("users");
    }

    /**
     * 定位并保护指定用户的通知数据文件。
     *
     * @param user 用户 id
     * @return {@code <root>/users/<user>/notifications.json}
     */
    public Path file(String user) {
        Path path = users.resolve(UserWorkspaceService.requireUserId(user)).resolve("notifications.json");
        ModelConfigurationFiles.protect(path);
        return path;
    }

    /**
     * 列出所有拥有通知数据文件的用户；遍历时同步刷新各文件的保护标记。
     *
     * @return 用户 id 列表（目录不存在时为空）
     */
    public List<String> users() {
        List<String> result = new ArrayList<>();
        // 1. 目录不存在时直接返回空列表
        if (!Files.isDirectory(users)) return result;
        // 2. 遍历用户目录，仅收集存在数据文件的用户并刷新保护标记
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(users)) {
            for (Path dir : dirs) {
                if (Files.isRegularFile(dir.resolve("notifications.json"))) {
                    String user = dir.getFileName().toString();
                    file(user);
                    result.add(user);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("通知配置目录读取失败", e);
        }
        return result;
    }

    /**
     * 读取用户通知状态；文件不存在时返回仅含空 {@code configs}/{@code records} 的初始状态。
     *
     * @param user 用户 id
     * @return 通知状态（含 {@code configs} 与 {@code records} 两个列表）
     * @throws IllegalStateException 文件读取失败或结构非法
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> read(String user) {
        // 1. 文件不存在时返回仅含空列表的初始状态
        Path target = file(user);
        if (!Files.exists(target)) {
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("configs", new ArrayList<>());
            state.put("records", new ArrayList<>());
            return state;
        }
        // 2. 解析 JSON 并校验结构，损坏时直接报错不静默重置
        try {
            Map<String, Object> state = ONode.ofJson(new String(Files.readAllBytes(target), StandardCharsets.UTF_8)).toBean(Map.class);
            if (!(state.get("configs") instanceof List) || !(state.get("records") instanceof List)) {
                throw new IOException("Invalid notification state");
            }
            return state;
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("通知配置读取失败", e);
        }
    }

    /**
     * 原子写回用户通知状态：先写临时文件，再替换目标文件。
     *
     * @param user 用户 id
     * @param state 通知状态
     * @throws IllegalStateException 写入失败
     */
    public void write(String user, Map<String, Object> state) {
        // 1. 定位目标文件，确保用户目录存在并创建受保护的临时文件
        Path target = file(user);
        try {
            Files.createDirectories(target.getParent());
            Path temp = Files.createTempFile(target.getParent(), "notifications-", ".tmp");
            ModelConfigurationFiles.protect(temp);
            try {
                // 2. 序列化写入临时文件，再原子替换到目标文件
                Files.write(temp, ONode.serialize(state).getBytes(StandardCharsets.UTF_8));
                try {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    // 3. 文件系统不支持原子移动时退化为普通替换
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            // 4. 无论成败都清理临时文件并解除保护
            } finally {
                try {
                    Files.deleteIfExists(temp);
                } finally {
                    ModelConfigurationFiles.unprotect(temp);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("通知配置保存失败", e);
        }
    }

    /**
     * 取状态中的行列表（{@code configs} 或 {@code records}）。
     *
     * @param state 通知状态
     * @param key 列表键名
     * @return 行列表；结构非法时抛出类型转换异常
     */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> rows(Map<String, Object> state, String key) {
        return (List<Map<String, Object>>) state.get(key);
    }
}
