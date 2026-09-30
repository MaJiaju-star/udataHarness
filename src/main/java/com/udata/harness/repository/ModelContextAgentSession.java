package com.udata.harness.repository;

import com.udata.harness.common.context.ContextArtifactStore;
import com.udata.harness.common.context.ContextMetrics;
import com.udata.harness.common.context.TaskStateStore;
import org.noear.snack4.ONode;
import org.noear.solon.ai.agent.AgentTrace;
import org.noear.solon.ai.agent.session.FileAgentSession;
import org.noear.solon.ai.chat.message.ChatMessage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 文件归档与模型上下文分开保存。模型视图保存摘要、保留消息及已消费的归档位置，
 * 新请求只追加尚未消费的归档，不再按固定消息数滑动窗口。
 */
public class ModelContextAgentSession extends FileAgentSession {
    private static final String MESSAGE_ID = "_udata_message_id";
    private final Path contextFile;
    private final Path contextRoot;
    private final ContextArtifactStore artifacts;
    private final TaskStateStore taskState;
    private ContextMetrics metrics;
    private List<String> modelMessages;
    private int archiveCursor;
    private long generation;

    public ModelContextAgentSession(String sessionId, String dir) {
        super(sessionId, dir);
        contextFile = Paths.get(dir).resolve(sessionId + ".model-context.json");
        Path directory = Paths.get(dir).toAbsolutePath().normalize();
        contextRoot = directory.resolve(sessionId + ".context").normalize();
        if (!directory.equals(contextRoot.getParent())) throw new IllegalArgumentException("Invalid context session path");
        artifacts = new ContextArtifactStore(contextRoot);
        taskState = new TaskStateStore(contextRoot, artifacts);
        metrics = new ContextMetrics(contextRoot);
        loadModelContext();
    }

    public ContextArtifactStore contextArtifacts() { return artifacts; }
    public TaskStateStore taskState() { return taskState; }
    public ContextMetrics contextMetrics() { return metrics; }

    public synchronized Map<String, Object> contextReport() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("generation", generation);
        result.put("archiveMessages", getMessages().size());
        result.put("activeMessages", getLatestMessages(1).size());
        result.put("taskState", taskState.get());
        result.put("metrics", metrics.report());
        return result;
    }

    private void loadModelContext() {
        if (!Files.exists(contextFile)) return; // 旧会话首次使用完整归档，随后按预算生成摘要。
        try {
            ONode state = ONode.ofJson(new String(Files.readAllBytes(contextFile), StandardCharsets.UTF_8));
            if (state.get("version").getInt() != 1 || !state.get("messages").isArray()) {
                throw new IllegalArgumentException("Unsupported model context format");
            }
            archiveCursor = state.get("archiveCursor").getInt();
            generation = state.get("generation").getLong();
            if (archiveCursor < 0 || archiveCursor > getMessages().size()) {
                throw new IllegalArgumentException("Model context archive cursor is invalid");
            }
            modelMessages = new ArrayList<>();
            for (ONode message : state.get("messages").getArray()) {
                String json = message.getString();
                ChatMessage.fromJson(json); // 验证消息，不静默回退到压缩前的原始历史。
                modelMessages.add(json);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Cannot load model context: " + contextFile, e);
        }
    }

    @Override
    public synchronized List<ChatMessage> getLatestMessages(int windowSize) {
        // windowSize 在此仅由框架用作历史启用开关，模型视图不按消息数截断。
        List<ChatMessage> view = new ArrayList<>();
        if (modelMessages != null) {
            for (String message : modelMessages) view.add(ChatMessage.fromJson(message));
        }
        List<ChatMessage> archive = getMessages();
        for (int i = modelMessages == null ? 0 : archiveCursor; i < archive.size(); i++) {
            view.add(ChatMessage.fromJson(cleanJson(archive.get(i))));
        }
        return view;
    }

    @Override
    public synchronized void addMessage(Collection<? extends ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) return;
        for (ChatMessage message : messages) {
            if (!message.hasMetadata(MESSAGE_ID)) message.addMetadata(MESSAGE_ID, UUID.randomUUID().toString());
        }
        super.addMessage(messages);
    }

    /** 从实际工作记忆提交视图。先原子写盘再更新内存，失败时保留最后成功的检查点。 */
    public synchronized void saveModelContext(List<ChatMessage> memory, boolean compressed) {
        List<String> next = new ArrayList<>();
        for (ChatMessage message : memory) next.add(cleanJson(message));
        int nextCursor = getMessages().size();
        if (!compressed && next.equals(modelMessages) && archiveCursor == nextCursor) return;
        writeModelContext(next, nextCursor, generation + (compressed ? 1 : 0));
    }

    private void writeModelContext(List<String> next, int cursor, long nextGeneration) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("version", 1);
        state.put("archiveCursor", cursor);
        state.put("generation", nextGeneration);
        state.put("messages", next);
        Path temporary = contextFile.resolveSibling(contextFile.getFileName() + ".tmp");
        try {
            Files.write(temporary, ONode.serialize(state).getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(temporary, contextFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, contextFile, StandardCopyOption.REPLACE_EXISTING);
            }
            modelMessages = next;
            archiveCursor = cursor;
            generation = nextGeneration;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot persist model context: " + contextFile, e);
        }
    }

    @Override
    public synchronized void removeLatestMessage(int windowSize) {
        List<ChatMessage> before = new ArrayList<>(getMessages());
        super.removeLatestMessage(windowSize);
        int size = getMessages().size();
        if (modelMessages == null || size >= archiveCursor) return;
        List<String> next = new ArrayList<>(modelMessages);
        for (int i = size; i < archiveCursor; i++) next.remove(cleanJson(before.get(i)));
        writeModelContext(next, size, generation);
    }

    @Override
    public synchronized void clear() {
        super.clear();
        writeModelContext(new ArrayList<>(), 0, 0);
        if (Files.exists(contextRoot)) {
            try (java.util.stream.Stream<Path> files = Files.walk(contextRoot)) {
                for (Path path : files.sorted(java.util.Comparator.reverseOrder()).collect(java.util.stream.Collectors.toList())) {
                    Files.delete(path);
                }
            } catch (IOException e) { throw new IllegalStateException("Cannot clear context evidence", e); }
        }
        metrics = new ContextMetrics(contextRoot);
    }

    private String cleanJson(ChatMessage message) {
        ChatMessage copy = ChatMessage.fromJson(ChatMessage.toJson(message));
        copy.getMetadata().remove(AgentTrace.META_FIRST);
        copy.getMetadata().remove("token_size");
        return ChatMessage.toJson(copy);
    }
}
