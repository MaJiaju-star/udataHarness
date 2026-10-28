package com.udata.harness.service;

import com.udata.harness.repository.SessionRepository;
import com.udata.harness.memory.MemoryService;
import com.udata.harness.common.context.ContextMessageIds;
import org.noear.solon.annotation.*;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.chat.message.*;
import org.noear.solon.ai.chat.tool.ToolCall;
import org.noear.snack4.ONode;
import java.util.*;

/** UI-only paging. Audit history and model context are never truncated or modified. */
@Component
public class SessionHistoryService {
    @Inject private SessionRepository sessions;
    public SessionHistoryService() { }
    public SessionHistoryService(SessionRepository sessions) { this.sessions = sessions; }
    public Map<String, Object> page(String user, String sessionId, Integer before, Integer size) {
        AgentSession session = sessions.getSession(user, sessionId);
        List<ChatMessage> raw = session.getMessages();
        int end = before == null ? raw.size() : before;
        if (end < 0 || end > raw.size()) throw new IllegalArgumentException("历史游标已失效，请重新打开会话");
        if (end < raw.size() && raw.get(end) instanceof ToolMessage) throw new IllegalArgumentException("历史游标必须位于消息边界");
        int count = Math.max(1, Math.min(60, size == null ? 30 : size)), start = end, found = 0;
        // Stop only at a user/assistant boundary, keeping each tool-call batch with its observations.
        while (start > 0 && found < count) { start--; if (!(raw.get(start) instanceof ToolMessage)
                && !ContextMessageIds.internal(raw.get(start))) found++; }
        // Whole user turns give each UI bubble a stable identity even as later replies are appended.
        while (start > 0 && (raw.get(start).getRole() != org.noear.solon.ai.chat.ChatRole.USER
                || ContextMessageIds.internal(raw.get(start)))) start--;
        List<Map<String, Object>> items = new ArrayList<>();
        Map<String, Map<String, Object>> tools = new HashMap<>(); int remaining = 32000;
        Object stored = session.getContext().get(MemoryService.HISTORY_KEY);
        Map<?, ?> recalls = stored instanceof Map ? (Map<?, ?>) stored : Collections.emptyMap();
        Set<String> shown = new HashSet<>();
        for (int i = start; i < end; i++) {
            ChatMessage message = raw.get(i);
            if (ContextMessageIds.internal(message)) continue;
            if (message instanceof ToolMessage) {
                ToolMessage result = (ToolMessage) message; Map<String, Object> tool = tools.get(result.getToolCallId());
                if (tool != null) {
                    String output = result.getContent() == null ? "" : result.getContent();
                    int length = Math.min(Math.min(160, remaining), output.length());
                    tool.put("output", output.substring(0, length)); remaining -= length;
                    Object diff = result.getMetadata().get("editDiff");
                    if (diff instanceof Map) {
                        Map<String, Object> summary = new LinkedHashMap<>();
                        for (String key : Arrays.asList("filePath", "firstChangedLine", "addedLines", "deletedLines", "coarse"))
                            if (((Map<?, ?>) diff).containsKey(key)) summary.put(key, ((Map<?, ?>) diff).get(key));
                        tool.put("editDiff", summary);
                    }
                }
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>(); item.put("id", "history-" + i);
            item.put("role", message.getRole().name().toLowerCase());
            String content = message.getContent() == null ? "" : message.getContent();
            int length = Math.min(Math.min(2000, remaining), content.length()); remaining -= length;
            item.put("content", content.substring(0, length));
            item.put("thinking", message instanceof AssistantMessage && ((AssistantMessage) message).isThinkingOnly());
            if (length < content.length()) item.put("contentRef", ref(i, -1, length));
            if (message instanceof AssistantMessage) {
                AssistantMessage assistant = (AssistantMessage) message;
                List<Map<String, Object>> calls = new ArrayList<>(); int index = 0;
                if (assistant.getToolCalls() != null) for (ToolCall call : assistant.getToolCalls()) {
                    Map<String, Object> tool = new LinkedHashMap<>();
                    tool.put("callId", "history-" + i + "-tool-" + index); tool.put("name", call.getName());
                    Map<String, Object> args = new LinkedHashMap<>();
                    // Keep small routing/summary fields; source code, arrays and nested payloads are deferred.
                    if (call.getArguments() != null) for (String key : Arrays.asList("file_path", "filePath", "path", "uri", "pattern", "command", "offset", "limit", "focus_line", "context_lines", "recursive")) {
                        Object value = call.getArguments().get(key);
                        if (value instanceof String) args.put(key, ((String) value).substring(0, Math.min(256, ((String) value).length())));
                        else if (value instanceof Number || value instanceof Boolean) args.put(key, value);
                    }
                    tool.put("args", args); tool.put("running", false); tool.put("detailRef", ref(i, index++, 0));
                    calls.add(tool); tools.put(call.getId(), tool);
                }
                if (!calls.isEmpty()) item.put("tools", calls);
                Object run = message.getMetadata().get("_runId");
                Object activities = session.getContext().get("_udata_file_activities");
                if (run != null && activities instanceof Map) item.put("fileActivities", ((Map<?, ?>) activities).get(String.valueOf(run)));
                if (run != null && shown.add(String.valueOf(run)) && recalls.containsKey(String.valueOf(run))) item.put("memoryRecall", recalls.get(String.valueOf(run)));
            }
            items.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<>(); result.put("messages", items);
        result.put("nextBefore", start); result.put("hasMore", start > 0); return result;
    }
    private static Map<String, Object> ref(int message, int tool, int offset) {
        Map<String, Object> value = new LinkedHashMap<>(); value.put("messageIndex", message);
        if (tool >= 0) value.put("toolIndex", tool); value.put("offset", offset); return value;
    }
    public Map<String, Object> content(String user, String sessionId, int messageIndex, Integer toolIndex, int offset, int limit) {
        return content(user, sessionId, messageIndex, toolIndex, offset, limit, null);
    }
    public Map<String, Object> content(String user, String sessionId, int messageIndex, Integer toolIndex, int offset, int limit, String part) {
        List<ChatMessage> raw = sessions.getSession(user, sessionId).getMessages();
        if (messageIndex < 0 || messageIndex >= raw.size() || offset < 0 || limit < 1 || limit > 16000) throw new IllegalArgumentException("无效的正文范围");
        ChatMessage source = raw.get(messageIndex); String text;
        if (toolIndex == null) text = source.getContent() == null ? "" : source.getContent();
        else {
            if (!(source instanceof AssistantMessage)) throw new IllegalArgumentException("工具消息不存在");
            List<ToolCall> calls = ((AssistantMessage) source).getToolCalls();
            if (calls == null || toolIndex < 0 || toolIndex >= calls.size()) throw new IllegalArgumentException("工具消息不存在");
            ToolCall call = calls.get(toolIndex); text = ONode.serialize(call.getArguments());
            if ("args".equals(part)) return chunk(text, offset, limit);
            text = "参数：\n" + text;
            for (int i = messageIndex + 1; i < raw.size() && raw.get(i) instanceof ToolMessage; i++) {
                ToolMessage result = (ToolMessage) raw.get(i);
                if (Objects.equals(result.getToolCallId(), call.getId())) {
                    if ("output".equals(part)) return chunk(result.getContent() == null ? "" : result.getContent(), offset, limit);
                    text += "\n\n结果：\n" + (result.getContent() == null ? "" : result.getContent());
                    Object diff = result.getMetadata().get("editDiff");
                    if (diff instanceof Map && ((Map<?, ?>) diff).get("patch") != null) text += "\n\n差异：\n" + ((Map<?, ?>) diff).get("patch");
                    break;
                }
            }
            if ("output".equals(part)) text = "";
        }
        return chunk(text, offset, limit);
    }
    private static Map<String, Object> chunk(String text, int offset, int limit) {
        if (offset > text.length()) throw new IllegalArgumentException("正文已变化，请重新打开详情");
        int end = (int) Math.min(text.length(), (long) offset + limit);
        Map<String, Object> result = new LinkedHashMap<>(); result.put("content", text.substring(offset, end));
        result.put("nextOffset", end); result.put("hasMore", end < text.length()); result.put("totalChars", text.length()); return result;
    }
}
