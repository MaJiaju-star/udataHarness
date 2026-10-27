package com.udata.harness.repository;

import com.udata.harness.service.UserWorkspaceService;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.*;
import java.util.Arrays;
import java.util.Properties;

/** Per-user, per-workspace preferences; model requests reread the persisted selection. */
public final class ProjectInstructionSettings {
    private final Path users;
    public ProjectInstructionSettings(Path data) { users = data.toAbsolutePath().normalize().resolve("users"); }
    public static String requireMode(String mode) {
        if (!Arrays.asList("auto", "claude", "agents", "off").contains(mode))
            throw new IllegalArgumentException("项目指令模式必须为 auto/claude/agents/off");
        return mode;
    }
    private Path path(String user) { return users.resolve(UserWorkspaceService.requireUserId(user)).resolve("project-instructions.properties"); }
    private Properties load(String user) throws IOException {
        Properties p = new Properties(); Path file = path(user);
        if (Files.exists(file)) try (InputStream in = Files.newInputStream(file)) { p.load(in); }
        return p;
    }
    public synchronized String mode(String user, String workspace) {
        try { return requireMode(load(user).getProperty(workspace, "auto")); }
        catch (IOException e) { throw new IllegalStateException("无法读取项目指令设置", e); }
    }
    public synchronized void save(String user, String workspace, String mode) {
        requireMode(mode);
        if (workspace == null || workspace.trim().isEmpty()) throw new IllegalArgumentException("workspaceId is required");
        Path temporary = null;
        try {
            Properties p = load(user); p.setProperty(workspace, mode);
            Path file = path(user); Files.createDirectories(file.getParent());
            temporary = Files.createTempFile(file.getParent(), "instructions-", ".tmp");
            try (OutputStream out = Files.newOutputStream(temporary)) { p.store(out, "Project instruction modes"); }
            try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException e) { throw new IllegalStateException("无法保存项目指令设置", e); }
        finally { if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) { } }
    }
}
