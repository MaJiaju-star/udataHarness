package com.udata.harness.memory;

import com.udata.harness.repository.ModelConfigurationFiles;
import com.udata.harness.service.UserWorkspaceService;
import org.noear.snack4.ONode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** One atomic document per user keeps settings, records and their audit changes consistent. */
public final class MemoryRepository {

    private final Path users;

    public MemoryRepository(Path root) {
        users = root.toAbsolutePath().normalize().resolve("users");
    }

    public Path file(String user) {
        Path file = users.resolve(UserWorkspaceService.requireUserId(user)).resolve("memory.json");
        ModelConfigurationFiles.protect(file);
        return file;
    }

    public void protectExisting() {
        if (!Files.isDirectory(users)) {
            return;
        }

        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(users)) {
            for (Path dir : dirs) {
                if (Files.exists(dir.resolve("memory.json"))) {
                    file(dir.getFileName().toString());
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("无法读取记忆目录", e);
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> read(String user) {
        Path file = file(user);
        if (!Files.exists(file)) {
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("enabled", false);
            state.put("budget", 1000);
            state.put("entries", new ArrayList<>());
            state.put("changes", new ArrayList<>());
            return state;
        }

        try {
            Map<String, Object> state = ONode.ofJson(new String(Files.readAllBytes(file), StandardCharsets.UTF_8)).toBean(Map.class);

            if (!(state.get("enabled") instanceof Boolean) || !(state.get("budget") instanceof Number)
                    || !(state.get("entries") instanceof List) || !(state.get("changes") instanceof List)) {
                throw new IOException("Invalid memory state");
            }

            for (Object item : (List<?>) state.get("entries")) {
                if (!(item instanceof Map)) {
                    throw new IOException("Invalid memory entry");
                }

                Map<?, ?> row = (Map<?, ?>) item;
                if (!(row.get("id") instanceof String) || !(row.get("key") instanceof String)
                        || !(row.get("content") instanceof String) || !(row.get("revision") instanceof Number)
                        || !Arrays.asList("global", "project").contains(row.get("scope"))) {
                    throw new IOException("Invalid memory entry");
                }
            }

            return state;
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("记忆文件损坏或无法读取，未覆盖原文件", e);
        }
    }

    public void write(String user, Map<String, Object> state) {
        Path file = file(user);

        try {
            Files.createDirectories(file.getParent());
            Path temp = Files.createTempFile(file.getParent(), "memory-", ".tmp");
            ModelConfigurationFiles.protect(temp);

            try {
                Files.write(temp, ONode.serialize(state).getBytes(StandardCharsets.UTF_8));

                try {
                    Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                try {
                    Files.deleteIfExists(temp);
                } finally {
                    ModelConfigurationFiles.unprotect(temp);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("记忆保存失败", e);
        }
    }
}
