package com.udata.harness.repository;

import com.udata.harness.service.UserWorkspaceService;
import org.noear.snack4.ONode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Definitions and dispatch claims commit together before any Agent is started. */
public final class AutomationRepository {
    private final Path users;
    public AutomationRepository(Path data) { users = data.toAbsolutePath().normalize().resolve("users"); }
    private Path file(String user) { return users.resolve(UserWorkspaceService.requireUserId(user)).resolve("automations.json"); }
    public List<String> users() {
        List<String> result = new ArrayList<>();
        if (!Files.isDirectory(users)) return result;
        try (DirectoryStream<Path> paths = Files.newDirectoryStream(users)) {
            for (Path path : paths) if (Files.isRegularFile(path.resolve("automations.json"))) result.add(path.getFileName().toString());
        } catch (IOException e) { throw new IllegalStateException("定时任务目录读取失败", e); }
        return result;
    }
    @SuppressWarnings("unchecked") public Map<String, Object> read(String user) {
        if (!Files.exists(file(user))) {
            Map<String, Object> state = new LinkedHashMap<>(); state.put("tasks", new ArrayList<>()); state.put("runs", new ArrayList<>()); return state;
        }
        try {
            Map<String, Object> state = ONode.ofJson(new String(Files.readAllBytes(file(user)), StandardCharsets.UTF_8)).toBean(Map.class);
            if (!(state.get("tasks") instanceof List) || !(state.get("runs") instanceof List)) throw new IOException("Invalid automation state");
            return state;
        } catch (IOException | RuntimeException e) { throw new IllegalStateException("定时任务配置读取失败", e); }
    }
    public void write(String user, Map<String, Object> state) {
        Path target = file(user);
        try {
            Files.createDirectories(target.getParent());
            Path temp = Files.createTempFile(target.getParent(), "automations-", ".tmp");
            try {
                Files.write(temp, ONode.serialize(state).getBytes(StandardCharsets.UTF_8));
                try { Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                catch (AtomicMoveNotSupportedException e) { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING); }
            } finally { Files.deleteIfExists(temp); }
        } catch (IOException e) { throw new IllegalStateException("定时任务保存失败", e); }
    }
}
