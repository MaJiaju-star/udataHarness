package com.udata.harness.common.support;

import org.noear.solon.ai.agent.react.ReActInterceptor;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 工作区指令拦截器：在每次模型推理前，将工作区的项目指令文件追加到系统提示词中。
 *
 * <p>实例不可变（只持有已规范化的绝对路径）；指令文件在每次 {@link #onReasonStart} 时重新读取，
 * 以保证用户对指令文件的修改即时生效，无需重建会话。</p>
 *
 * <p>查找顺序为 {@code CLAUDE.md} → {@code AGENTS.md}，命中一个即止，绝不拼接两个文件；
 * 内容超过 {@link #MAX_CHARS} 截断，并在提示词中注明。读取失败仅告警，不中断推理。</p>
 */
public final class WorkspaceInstructionsInterceptor implements ReActInterceptor {
    public static final String STATUS_KEY = "udata.workspace.instructions.status";
    private static final Logger LOG = LoggerFactory.getLogger(WorkspaceInstructionsInterceptor.class);
    /** 指令文件读取上限（字符数），超出部分截断 */
    static final int MAX_CHARS = 10000;

    /** 绑定的工作区根目录（绝对路径、已 normalize） */
    private final Path workspace;
    private final java.util.function.Supplier<String> selectedMode;

    public WorkspaceInstructionsInterceptor(Path workspace) {
        this(workspace, () -> "auto");
    }

    public WorkspaceInstructionsInterceptor(Path workspace, java.util.function.Supplier<String> selectedMode) {
        this.workspace = workspace.toAbsolutePath().normalize();
        this.selectedMode = selectedMode;
    }

    /**
     * 推理开始时回调：若工作区存在指令文件，则将其内容作为「工作区指令」区块追加到系统提示词。
     * 用户当前的明确请求优先于这些项目指令。
     */
    @Override
    public void onReasonStart(ReActTrace trace, StringBuilder systemPrompt) {
        String mode = com.udata.harness.repository.ProjectInstructionSettings.requireMode(selectedMode.get());
        String[] files = "auto".equals(mode) ? new String[]{"CLAUDE.md", "AGENTS.md"}
                : "claude".equals(mode) ? new String[]{"CLAUDE.md"}
                : "agents".equals(mode) ? new String[]{"AGENTS.md"} : new String[0];
        for (String name : files) {
            Path file = workspace.resolve(name);
            if (!Files.isRegularFile(file)) continue;
            try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                StringBuilder text = new StringBuilder();
                char[] buffer = new char[2048];
                while (text.length() <= MAX_CHARS) {
                    int count = reader.read(buffer, 0, Math.min(buffer.length, MAX_CHARS + 1 - text.length()));
                    if (count < 0) break;
                    text.append(buffer, 0, count);
                }
                boolean truncated = text.length() > MAX_CHARS;
                String content = text.substring(0, Math.min(text.length(), MAX_CHARS));
                if (content.startsWith("\uFEFF")) content = content.substring(1);
                content = content.trim();
                if (content.isEmpty()) continue;
                systemPrompt.append("\n\n## Workspace instructions\nSource: ").append(file)
                        .append("\nApply these project instructions within this workspace. Current explicit user requests take precedence.\n")
                        .append("<workspace-instructions>\n").append(content).append("\n</workspace-instructions>\n");
                if (truncated) systemPrompt.append("[Project instruction file truncated to ").append(MAX_CHARS).append(" characters.]\n");
                publish(trace, mode, name, file.toString(), content.length(), truncated);
                return; // 只选一个来源：CLAUDE.md 与 AGENTS.md 绝不拼接使用
            } catch (IOException | SecurityException e) {
                LOG.warn("Cannot read workspace instruction file {}: {}", file, e.getClass().getSimpleName());
            }
        }
        publish(trace, mode, null, null, 0, false);
    }

    private void publish(ReActTrace trace, String mode, String name, String path, int characters, boolean truncated) {
        if (trace == null || trace.getSession() == null) return;
        java.util.Map<String, Object> status = new java.util.LinkedHashMap<>();
        status.put("mode", mode);
        status.put("loaded", name != null); status.put("source", name); status.put("path", path);
        status.put("characters", characters); status.put("truncated", truncated);
        status.put("checkedAt", System.currentTimeMillis());
        trace.getSession().getContext().put(STATUS_KEY, status);
        if (trace.hasStreamSink()) trace.pushAgentEvent(new WorkspaceInstructionsEvent(trace, status));
    }
}
