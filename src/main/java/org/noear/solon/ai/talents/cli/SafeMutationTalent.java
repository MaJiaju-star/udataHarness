package org.noear.solon.ai.talents.cli;

import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.talent.AbsTalent;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.talents.mount.MountManager;
import org.noear.solon.ai.talents.mount.ResolvedResource;
import org.noear.solon.annotation.Param;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.AclFileAttributeView;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/** Local-file mutation guard; non-local MountSource writes retain Solon's semantics. */
public final class SafeMutationTalent extends AbsTalent {
    private static final long MAX_EDIT_BYTES = 16L * 1024 * 1024;
    private static final ReentrantLock[] LOCKS = new ReentrantLock[64];

    static {
        for (int i = 0; i < LOCKS.length; i++) LOCKS[i] = new ReentrantLock();
    }

    private final TerminalTalent terminal;
    private final TerminalSupport support;
    private final MountManager mounts;
    private final Path workspace;
    private final Field diagnosticsHook;
    private final boolean canWrite;
    private final boolean canEdit;

    public SafeMutationTalent(TerminalTalent terminal, String workspace, boolean canWrite, boolean canEdit) {
        this.terminal = terminal;
        this.workspace = Paths.get(workspace).toAbsolutePath().normalize();
        this.canWrite = canWrite;
        this.canEdit = canEdit;
        try {
            this.support = (TerminalSupport) field("support").get(terminal);
            this.mounts = (MountManager) field("mountManager").get(terminal);
            this.diagnosticsHook = field("fileDiagnosticsHook");
        } catch (ReflectiveOperationException | SecurityException e) {
            throw new IllegalStateException("Solon mutation path guard is unavailable", e);
        }
    }

    private static Field field(String name) throws ReflectiveOperationException {
        Field field = TerminalTalent.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    @Override
    public String getInstruction(Prompt prompt) {
        return "本地 write 覆盖已有文件、edit 修改已有文件时，必须先 read 并传入输出中的 expected_sha256。版本冲突时重新 read，不要盲目重试。";
    }

    @Override
    public Collection<FunctionTool> getTools(Prompt prompt) {
        if (canWrite && canEdit) return getToolAry("write", "edit");
        if (canWrite) return getToolAry("write");
        if (canEdit) return getToolAry("edit");
        return getToolAry();
    }

    @ToolMapping(name = "write", description = "创建文件或覆盖已有文件。本地已有文件必须提供最近 read 返回的 expected_sha256；使用同目录临时文件原子替换。")
    public String write(
            @Param(value = "file_path", description = "工作区相对路径或逻辑路径") String filePath,
            @Param(value = "content", description = "完整文件内容") String content,
            @Param(value = "expected_sha256", required = false, description = "覆盖已有本地文件时必填：read 返回的文件版本 SHA-256") String expectedSha256,
            String __cwd) throws IOException {
        if (filePath == null || content == null) return "错误：file_path 和 content 均不能为空。";
        if (ToolResultPaths.isVirtual(filePath)) return "错误：@tool-results 是只读历史快照，不能写入。";
        if (isNonLocalVirtual(filePath)) return terminal.write(filePath, content, __cwd);
        Path target = resolve(filePath, __cwd, true);
        ReentrantLock lock = lockFor(target);
        lock.lock();
        try {
            boolean exists = Files.exists(target);
            if (exists && !Files.isRegularFile(target)) return "错误：目标不是普通文件。";
            if (Files.isSymbolicLink(target)) return "错误：不直接覆盖符号链接，请使用其真实路径。";
            String conflict = checkVersion(target, expectedSha256, exists);
            if (conflict != null) return conflict;
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            atomicReplace(target, bytes, expectedSha256, exists, filePath, __cwd);
            return withDiagnostics("文件成功写入: " + filePath + "\n新文件版本 SHA-256: "
                    + FileVersion.sha256(target), target);
        } finally {
            lock.unlock();
        }
    }

    @ToolMapping(name = "edit", description = "按文本块编辑文件，自动保留原有 BOM 与换行风格。本地文件必须提供最近 read 返回的 expected_sha256；全部替换成功后才原子写入。")
    public String edit(
            @Param(value = "file_path", description = "工作区相对路径或逻辑路径") String filePath,
            @Param(value = "edits", description = "编辑操作列表") List<TerminalTalent.EditOp> edits,
            @Param(value = "expected_sha256", required = false, description = "必填：read 返回的文件版本 SHA-256") String expectedSha256,
            String __cwd) throws IOException {
        if (filePath == null || edits == null || edits.isEmpty()) return "错误：file_path 和 edits 均不能为空。";
        if (ToolResultPaths.isVirtual(filePath)) return "错误：@tool-results 是只读历史快照，不能编辑。";
        if (isNonLocalVirtual(filePath)) return terminal.edit(filePath, edits, __cwd);
        Path target = resolve(filePath, __cwd, true);
        ReentrantLock lock = lockFor(target);
        lock.lock();
        try {
            if (!Files.isRegularFile(target)) return "错误：文件不存在或不是普通文件。";
            if (Files.isSymbolicLink(target)) return "错误：不直接编辑符号链接，请使用其真实路径。";
            String conflict = checkVersion(target, expectedSha256, true);
            if (conflict != null) return conflict;
            if (Files.size(target) > MAX_EDIT_BYTES) {
                return "错误：文件超过 16 MiB，不能用 edit 整文件修改；请使用受控脚本或专用工具。";
            }
            String raw = new String(Files.readAllBytes(target), StandardCharsets.UTF_8);
            boolean hadBom = TerminalSupport.hasUtf8Bom(raw);
            String working = TerminalSupport.stripUtf8Bom(raw);
            for (int index : executionOrder(edits)) {
                TerminalTalent.EditOp operation = edits.get(index);
                if (operation == null || operation.oldStr == null || operation.oldStr.isEmpty()) {
                    return "预检查失败（操作 #" + (index + 1) + "）：old_str 不能为空。";
                }
                try {
                    working = support.applyEditLogic(working, operation.oldStr,
                            operation.newStr == null ? "" : operation.newStr,
                            Boolean.TRUE.equals(operation.replaceAll), operation.oldStrStartLine);
                } catch (IllegalArgumentException e) {
                    return "编辑失败（操作 #" + (index + 1) + "）：" + e.getMessage();
                }
            }
            String result = hadBom ? TerminalSupport.UTF8_BOM + working : working;
            if (raw.equals(result)) return "文件未变化: " + filePath;
            atomicReplace(target, result.getBytes(StandardCharsets.UTF_8), expectedSha256,
                    true, filePath, __cwd);
            return withDiagnostics("文件 " + filePath + " 成功完成 " + edits.size()
                    + " 处修改。\n新文件版本 SHA-256: " + FileVersion.sha256(target), target);
        } finally {
            lock.unlock();
        }
    }

    private Path resolve(String filePath, String cwd, boolean write) throws IOException {
        Path workPath = cwd == null ? workspace : Paths.get(cwd).toAbsolutePath().normalize();
        return support.resolveSafePath(workPath, filePath, write,
                terminal.isSandboxEnabled(), false, null);
    }

    private boolean isNonLocalVirtual(String filePath) {
        if (!filePath.startsWith("@")) return false;
        ResolvedResource resource = mounts.resolveResource(filePath);
        return !resource.getSource().capabilities().isLocalPathAccessible()
                || !resource.getLocalPath().isPresent();
    }

    private static ReentrantLock lockFor(Path target) {
        return LOCKS[(target.toAbsolutePath().normalize().hashCode() & Integer.MAX_VALUE) % LOCKS.length];
    }

    private static String checkVersion(Path target, String expected, boolean exists) throws IOException {
        if (exists && (expected == null || !expected.matches("(?i)[0-9a-f]{64}"))) {
            return "版本冲突：已有文件必须提供 read 返回的 expected_sha256。";
        }
        if (!exists && expected != null) return "版本冲突：目标文件不存在，请重新确认路径。";
        if (exists && !FileVersion.sha256(target).equalsIgnoreCase(expected)) {
            return "版本冲突：文件自上次读取后已变化，请重新 read，再决定是否修改。";
        }
        return null;
    }

    private void atomicReplace(Path target, byte[] bytes, String expected, boolean existed,
                               String filePath, String cwd) throws IOException {
        Path parent = target.getParent();
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, ".solon-write-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            if (existed) copyPermissions(target, temporary);
            // Recheck after potentially expensive rendering and disk I/O. The
            // striped lock serializes this app's writes to the same path.
            if (Files.exists(target) != existed || Files.isSymbolicLink(target)
                    || checkVersion(target, expected, existed) != null
                    || !resolve(filePath, cwd, true).equals(target)) {
                throw new IOException("版本冲突：写入期间目标或路径发生变化，请重新 read。");
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                throw new IOException("文件系统不支持原子替换，已拒绝非原子写入", e);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void copyPermissions(Path source, Path destination) throws IOException {
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(source);
            Files.setPosixFilePermissions(destination, permissions);
        } catch (UnsupportedOperationException ignored) {
            // Windows uses ACLs instead of POSIX permissions.
        }
        AclFileAttributeView sourceAcl = Files.getFileAttributeView(source, AclFileAttributeView.class);
        AclFileAttributeView destinationAcl = Files.getFileAttributeView(destination, AclFileAttributeView.class);
        if (sourceAcl != null && destinationAcl != null) {
            destinationAcl.setAcl(sourceAcl.getAcl());
        }
    }

    private static List<Integer> executionOrder(List<TerminalTalent.EditOp> edits) {
        List<Integer> order = new ArrayList<>();
        boolean lineScoped = true;
        for (int i = 0; i < edits.size(); i++) {
            order.add(i);
            TerminalTalent.EditOp operation = edits.get(i);
            if (operation == null || Boolean.TRUE.equals(operation.replaceAll)
                    || operation.oldStrStartLine == null || operation.oldStrStartLine <= 0) {
                lineScoped = false;
            }
        }
        if (lineScoped) {
            order.sort((a, b) -> Integer.compare(edits.get(b).oldStrStartLine,
                    edits.get(a).oldStrStartLine));
        }
        return order;
    }

    @SuppressWarnings("unchecked")
    private String withDiagnostics(String message, Path target) {
        try {
            Function<Path, String> hook = (Function<Path, String>) diagnosticsHook.get(terminal);
            if (hook != null) {
                String diagnostics = hook.apply(target);
                if (diagnostics != null && !diagnostics.isEmpty()) return message + "\n\n" + diagnostics;
            }
        } catch (Throwable ignored) {
            // Match TerminalTalent: diagnostics must not fail a successful write.
        }
        return message;
    }
}
