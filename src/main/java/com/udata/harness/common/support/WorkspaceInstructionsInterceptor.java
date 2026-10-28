package com.udata.harness.common.support;

import org.noear.solon.ai.agent.react.ReActInterceptor;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 每次推理读取最新项目指令；固定策略进入 system，正文由后置拦截器作为 user 消息追加。
 *
 * <p>实例不可变（只持有已规范化的绝对路径）；指令文件在每次 {@link #onReasonStart} 时重新读取，
 * 以保证用户对指令文件的修改即时生效，无需重建会话。</p>
 *
 * <p>查找顺序为 {@code CLAUDE.md} → {@code AGENTS.md}，命中一个即止，绝不拼接两个文件；
 * 内容超过 {@link #MAX_CHARS} 截断，并在提示词中注明。读取失败仅告警，不中断推理。</p>
 */
public final class WorkspaceInstructionsInterceptor implements ReActInterceptor {
    public static final String STATUS_KEY = "udata.workspace.instructions.status";
    private static final String SELECTION_KEY = "udata.workspace.instructions.selection";
    private static final String PREVIOUSLY_LOADED_KEY = "udata.workspace.instructions.previously-loaded";
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

    /** Solon 4.1.1 iterates lifecycle hooks in insertion order, not RankEntity order. */
    public void install(ReActAgent.Builder builder) {
        builder.modelOptions(options -> {
            java.util.List<org.noear.solon.core.util.RankEntity<ReActInterceptor>> existing =
                    new java.util.ArrayList<>(options.interceptors());
            options.interceptors().clear();
            options.interceptorAdd(Integer.MIN_VALUE + 1, this);
            options.interceptorAdd(existing);
            options.interceptorAdd(Integer.MAX_VALUE - 1, messageInterceptor());
        });
    }

    /** Runs before compression so its request uses the same stable system policy. */
    @Override
    public void onReasonStart(ReActTrace trace, StringBuilder systemPrompt) {
        systemPrompt.append(WorkspaceInstructionMessages.POLICY);
        if (trace == null || trace.getSession() == null) return;
        prepare(trace);
    }

    private void prepare(ReActTrace trace) {
        java.util.Map<?, ?> previous = trace.getSession().getContext().getAs(STATUS_KEY);
        trace.setExtra(PREVIOUSLY_LOADED_KEY, previous != null && Boolean.TRUE.equals(previous.get("loaded")));
        Selection selection = read();
        trace.setExtra(SELECTION_KEY, selection);
        publish(trace, selection.mode, selection.name, selection.path, selection.content.length(), selection.truncated);
    }

    /** Register after all memory rewriting and before telemetry, including on compression retries. */
    public ReActInterceptor messageInterceptor() {
        return new ReActInterceptor() {
            @Override public void onReasonStart(ReActTrace trace, StringBuilder systemPrompt) { reconcile(trace); }
            @Override public boolean onReasonRetry(ReActTrace trace, Throwable error, int attempt, String systemPrompt) {
                // Retry the same logical inference. Read fresh files on the next Reason, not on transport retries.
                return reconcile(trace);
            }
        };
    }

    private boolean reconcile(ReActTrace trace) {
        if (trace == null || trace.getSession() == null || trace.getWorkingMemory() == null) return false;
        Selection selection = trace.getExtraAs(SELECTION_KEY);
        if (selection == null) { prepare(trace); selection = trace.getExtraAs(SELECTION_KEY); }
        return WorkspaceInstructionMessages.reconcile(trace, workspace.toString(), selection,
                Boolean.TRUE.equals(trace.getExtra(PREVIOUSLY_LOADED_KEY)));
    }

    Selection read() {
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
                return new Selection(mode, name, file.toString(), content, truncated);
            } catch (IOException | SecurityException e) {
                LOG.warn("Cannot read workspace instruction file {}: {}", file, e.getClass().getSimpleName());
            }
        }
        return new Selection(mode, null, null, "", false);
    }

    static final class Selection {
        final String mode, name, path, content;
        final boolean truncated;
        Selection(String mode, String name, String path, String content, boolean truncated) {
            this.mode = mode; this.name = name; this.path = path; this.content = content; this.truncated = truncated;
        }
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
