package org.noear.solon.ai.talents.cli;

import org.noear.solon.ai.agent.react.ReActInterceptor;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.react.task.ToolExchanger;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 后端维护的会话文件版本。读取与修改工具共享一个工作区实例。
 *
 * <p>每批工具开始时冻结已知版本；同批 read 或其他修改不能替已生成的编辑更新前提。
 * 服务重启或引擎重建后没有基线的文件必须重新 read。</p>
 */
public final class SessionFileVersions implements ReActInterceptor {
    private final Map<String, State> sessions = new ConcurrentHashMap<>();

    /**
     * 冻结模型发起本批工具调用时已知的版本，并通过隐藏上下文注入批次 ID。
     *
     * @param trace 当前推理轨迹
     * @param toolCalls 当前批工具调用
     */
    @Override
    public void onActionStart(ReActTrace trace, Collection<ToolExchanger> toolCalls) {
        String sessionId = trace.getSession().getSessionId();
        String batchId = trace.getCurrentTurnId();
        beginBatch(sessionId, batchId);
        trace.getOptions().getToolContext().put("__fileVersionBatch", batchId);
    }

    /**
     * 开始工具批次；审批恢复同一批次时保留原基线。
     *
     * @param sessionId 会话标识
     * @param batchId 模型轮次标识
     */
    void beginBatch(String sessionId, String batchId) {
        State state = sessions.computeIfAbsent(sessionId, ignored -> new State());
        if (state.batch == null || !batchId.equals(state.batch.id)) {
            state.batch = new Batch(batchId, new HashMap<>(state.observed));
        }
    }

    /**
     * 保存成功读取或本工具成功写入的版本；没有可靠版本时撤销该文件基线。
     *
     * @param sessionId 框架注入的会话标识
     * @param file 已通过路径守卫校验的文件
     * @param version 文件内容摘要，为 null 时撤销基线
     * @throws IOException 文件真实路径不可解析
     */
    void remember(String sessionId, Path file, String version) throws IOException {
        if (sessionId == null || sessionId.isEmpty()) return;
        State state = sessions.computeIfAbsent(sessionId, ignored -> new State());
        Path path = identity(file);
        if (version == null) state.observed.remove(path);
        else state.observed.put(path, version);
    }

    /**
     * 返回当前会话在模型生成修改前已确认的版本。
     *
     * @param sessionId 框架注入的会话标识
     * @param file 已校验且存在的文件
     * @param batchId 隐藏的工具批次标识；直接调用时可为 null
     * @return 已读版本；缺少会话、基线或对应批次时返回 null
     * @throws IOException 文件真实路径不可解析
     */
    String expected(String sessionId, Path file, String batchId) throws IOException {
        if (sessionId == null) return null;
        State state = sessions.get(sessionId);
        if (state == null) return null;
        Path path = identity(file);
        if (batchId == null) return state.observed.get(path);
        Batch batch = state.batch;
        return batch != null && batchId.equals(batch.id) ? batch.versions.get(path) : null;
    }

    /**
     * 从最近仍存在的父目录解析真实路径，保持文件删除前后的版本键一致。
     *
     * @param file 已通过路径守卫的目标路径
     * @return 文件的规范路径身份
     * @throws IOException 父目录真实路径不可解析
     */
    private static Path identity(Path file) throws IOException {
        Path absolute = file.toAbsolutePath().normalize();
        Path parent = absolute;
        while (!Files.exists(parent)) parent = parent.getParent();
        return parent.toRealPath().resolve(parent.relativize(absolute)).normalize();
    }

    private static final class State {
        final Map<Path, String> observed = new ConcurrentHashMap<>();
        volatile Batch batch;
    }

    private static final class Batch {
        final String id;
        final Map<Path, String> versions;

        Batch(String id, Map<Path, String> versions) {
            this.id = id;
            this.versions = versions;
        }
    }
}
