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
    private static final Logger LOG = LoggerFactory.getLogger(WorkspaceInstructionsInterceptor.class);
    /** 指令文件读取上限（字符数），超出部分截断 */
    static final int MAX_CHARS = 10000;

    /** 绑定的工作区根目录（绝对路径、已 normalize） */
    private final Path workspace;

    public WorkspaceInstructionsInterceptor(Path workspace) {
        this.workspace = workspace.toAbsolutePath().normalize();
    }

    /**
     * 推理开始时回调：若工作区存在指令文件，则将其内容作为「工作区指令」区块追加到系统提示词。
     * 用户当前的明确请求优先于这些项目指令。
     */
    @Override
    public void onReasonStart(ReActTrace trace, StringBuilder systemPrompt) {
        for (String name : new String[]{"CLAUDE.md", "AGENTS.md"}) {
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
                return; // 只选一个来源：CLAUDE.md 与 AGENTS.md 绝不拼接使用
            } catch (IOException | SecurityException e) {
                LOG.warn("Cannot read workspace instruction file {}: {}", file, e.getClass().getSimpleName());
            }
        }
    }
}
