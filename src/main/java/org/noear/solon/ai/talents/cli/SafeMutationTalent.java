package org.noear.solon.ai.talents.cli;

import com.udata.harness.common.context.ContextArtifactStore;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.talent.AbsTalent;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.ToolResult;
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
import java.util.Map;
import java.util.LinkedHashMap;
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
    private final SessionFileVersions fileVersions;
    private final ContextArtifactStore artifactStore;

    public SafeMutationTalent(TerminalTalent terminal, String workspace, boolean canWrite, boolean canEdit) {
        this(terminal, workspace, canWrite, canEdit, new SessionFileVersions());
    }

    /**
     * 创建自动核对当前会话已读版本的修改工具。
     *
     * @param terminal 宿主终端工具
     * @param workspace 默认工作目录
     * @param canWrite 是否已授予 write 权限
     * @param canEdit 是否已授予 edit 权限
     * @param fileVersions 与 read 共享的会话版本记录
     */
    public SafeMutationTalent(TerminalTalent terminal, String workspace, boolean canWrite, boolean canEdit,
                              SessionFileVersions fileVersions) {
        this(terminal, workspace, canWrite, canEdit, fileVersions, null);
    }

    /** 创建共享版本台账与大补丁归档仓储的修改工具。 */
    public SafeMutationTalent(TerminalTalent terminal, String workspace, boolean canWrite, boolean canEdit,
                              SessionFileVersions fileVersions, ContextArtifactStore artifactStore) {
        this.terminal = terminal;
        this.workspace = Paths.get(workspace).toAbsolutePath().normalize();
        this.canWrite = canWrite;
        this.canEdit = canEdit;
        this.fileVersions = fileVersions;
        this.artifactStore = artifactStore;
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
        return "本地 write 覆盖已有文件、edit 修改已有文件时，必须先在本会话 read，后端自动维护和校验版本。读取结果返回后再发起修改；版本冲突时重新 read 并重新决定修改，不要盲目重试。";
    }

    @Override
    public Collection<FunctionTool> getTools(Prompt prompt) {
        if (canWrite && canEdit) return getToolAry("write", "edit");
        if (canWrite) return getToolAry("write");
        if (canEdit) return getToolAry("edit");
        return getToolAry();
    }

    /**
     * 创建或覆盖文件，后端自动使用当前会话已读版本校验覆盖前提。
     *
     * @param filePath 目标路径
     * @param content 完整文件内容
     * @param __cwd 框架注入的工作目录
     * @param __sessionId 框架注入的会话标识
     * @param __fileVersionBatch 框架注入的工具批次标识
     * @return 写入结果或版本冲突说明
     * @throws IOException 文件操作失败
     */
    @ToolMapping(name = "write", description = "创建文件或覆盖已有文件。覆盖前须在本会话 read；后端自动核对已读版本，冲突时拒绝覆盖。使用同目录临时文件原子替换。")
    public String write(
            @Param(value = "file_path", description = "工作区相对路径或逻辑路径") String filePath,
            @Param(value = "content", description = "完整文件内容") String content,
            String __cwd, String __sessionId, String __fileVersionBatch) throws IOException {
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
            String expectedSha256 = fileVersions.expected(__sessionId, target, __fileVersionBatch);
            String conflict = checkVersion(target, expectedSha256, exists);
            if (conflict != null) return conflict;
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            atomicReplace(target, bytes, expectedSha256, exists, filePath, __cwd);
            String writtenVersion = FileVersion.sha256(bytes);
            fileVersions.remember(__sessionId, target, writtenVersion);
            return withDiagnostics("文件成功写入: " + filePath, target);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 按文本块编辑文件，全部替换成功且会话已读版本仍匹配才落盘。
     *
     * @param filePath 目标路径
     * @param edits 编辑操作
     * @param __cwd 框架注入的工作目录
     * @param __sessionId 框架注入的会话标识
     * @param __fileVersionBatch 框架注入的工具批次标识
     * @return 编辑结果或版本冲突说明
     * @throws IOException 文件操作失败
     */
    @ToolMapping(name = "edit", description = "按文本块编辑文件，保留 BOM 与换行风格。须先在本会话 read；后端自动校验已读版本，全部替换成功后才原子写入。")
    public ToolResult editResult(
            @Param(value = "file_path", description = "工作区相对路径或逻辑路径") String filePath,
            @Param(value = "edits", description = "编辑操作列表") List<TerminalTalent.EditOp> edits,
            String __cwd, String __sessionId, String __fileVersionBatch) throws IOException {
        Map<String, Object> metadata = new LinkedHashMap<>();
        String content = editBody(filePath, edits, __cwd, __sessionId, __fileVersionBatch, metadata);
        ToolResult result = ToolResult.success(content);
        result.metas().putAll(metadata);
        return result;
    }

    /** 保留直接调用入口，结构化 diff 由工具结果元数据传给 UI。 */
    public String edit(String filePath, List<TerminalTalent.EditOp> edits, String cwd,
                       String sessionId, String batchId) throws IOException {
        return editResult(filePath, edits, cwd, sessionId, batchId).getContent();
    }

    /** 在原有版本守卫内执行全部替换，仅为成功提交的内容生成 diff。 */
    private String editBody(String filePath, List<TerminalTalent.EditOp> edits, String __cwd,
                            String __sessionId, String __fileVersionBatch, Map<String, Object> metadata) throws IOException {
        if (filePath == null || edits == null || edits.isEmpty()) return "错误：file_path 和 edits 均不能为空。";
        if (ToolResultPaths.isVirtual(filePath)) return "错误：@tool-results 是只读历史快照，不能编辑。";
        if (isNonLocalVirtual(filePath)) return terminal.edit(filePath, edits, __cwd);
        Path target = resolve(filePath, __cwd, true);
        ReentrantLock lock = lockFor(target);
        lock.lock();
        try {
            if (!Files.isRegularFile(target)) return "错误：文件不存在或不是普通文件。";
            if (Files.isSymbolicLink(target)) return "错误：不直接编辑符号链接，请使用其真实路径。";
            String expectedSha256 = fileVersions.expected(__sessionId, target, __fileVersionBatch);
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
            byte[] bytes = result.getBytes(StandardCharsets.UTF_8);
            EditDiff preparedDiff = EditDiff.create(filePath, raw, result);
            atomicReplace(target, bytes, expectedSha256,
                    true, filePath, __cwd);
            String writtenVersion = FileVersion.sha256(bytes);
            fileVersions.remember(__sessionId, target, writtenVersion);
            preparedDiff.archive(artifactStore, __sessionId);
            Map<String, Object> diff = preparedDiff.details;
            metadata.put("editDiff", diff);
            return withDiagnostics("文件 " + filePath + " 成功完成 " + edits.size()
                    + " 处修改。（+" + diff.get("addedLines") + " / -" + diff.get("deletedLines")
                    + " 行，首个修改行 " + diff.get("firstChangedLine") + "）"
                    + (Boolean.TRUE.equals(diff.get("coarse")) ? "（合并差异块统计，可能包含未变行）" : "")
                    + (diff.containsKey("patchPath") ? "\n完整补丁：read(file_path=\"" + diff.get("patchPath") + "\")" : ""), target);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 供不经过 Agent 批次的直接调用方创建或覆盖文件。
     *
     * @param filePath 目标路径
     * @param content 完整内容
     * @param cwd 工作目录
     * @param sessionId 会话标识
     * @return 写入结果
     * @throws IOException 文件操作失败
     */
    public String write(String filePath, String content, String cwd, String sessionId) throws IOException {
        return write(filePath, content, cwd, sessionId, null);
    }

    /**
     * 供不经过 Agent 批次的直接调用方编辑文件。
     *
     * @param filePath 目标路径
     * @param edits 编辑操作
     * @param cwd 工作目录
     * @param sessionId 会话标识
     * @return 编辑结果
     * @throws IOException 文件操作失败
     */
    public String edit(String filePath, List<TerminalTalent.EditOp> edits, String cwd, String sessionId)
            throws IOException {
        return edit(filePath, edits, cwd, sessionId, null);
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
        if (exists && expected == null) {
            return "版本冲突：本会话尚未读取该文件，请先 read，收到结果后再修改。";
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
