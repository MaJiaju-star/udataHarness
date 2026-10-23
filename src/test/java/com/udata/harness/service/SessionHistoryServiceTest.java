package com.udata.harness.service;

import com.udata.harness.repository.SessionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.chat.message.*;
import org.noear.solon.ai.chat.tool.ToolCall;
import org.noear.snack4.ONode;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SessionHistoryServiceTest {
    @TempDir Path root;
    @SuppressWarnings("unchecked") private List<Map<String, Object>> rows(Map<String, Object> page) { return (List<Map<String, Object>>) page.get("messages"); }
    @Test void pageCursorKeepsBatchesAndDoesNotShiftWhenNewMessagesArrive() {
        SessionRepository repo = new SessionRepository(root); String id = repo.create("alice", "history", "").getSessionId();
        AgentSession session = repo.getSession("alice", id);
        for (int n = 0; n < 40; n++) { session.addMessage(ChatMessage.ofUser("user-" + n)); session.addMessage(ChatMessage.ofAssistant("assistant-" + n)); }
        SessionHistoryService history = new SessionHistoryService(repo);
        Map<String, Object> latest = history.page("alice", id, null, 30);
        assertEquals(30, rows(latest).size()); assertEquals("history-50", rows(latest).get(0).get("id"));
        session.addMessage(ChatMessage.ofUser("new")); session.addMessage(ChatMessage.ofAssistant("new answer"));
        Map<String, Object> older = history.page("alice", id, ((Number) latest.get("nextBefore")).intValue(), 30);
        assertEquals("history-20", rows(older).get(0).get("id")); assertEquals("history-49", rows(older).get(29).get("id"));
        assertThrows(IllegalArgumentException.class, () -> history.page("bob", id, null, 30));
        assertThrows(IllegalArgumentException.class, () -> history.page("alice", id, 999, 30));
    }
    @Test void defersLargePayloadsAndRestoresExactCharacterRangesWithoutChangingHistory() {
        SessionRepository repo = new SessionRepository(root); String id = repo.create("alice", "history", "").getSessionId();
        AgentSession session = repo.getSession("alice", id);
        char[] chars = new char[40000]; Arrays.fill(chars, '中'); String content = new String(chars) + "TAIL_EVIDENCE";
        session.addMessage(ChatMessage.ofUser("inspect"));
        AssistantMessage assistant = new AssistantMessage(content, null, Collections.singletonList(new ToolCall("0", "call", "write", ONode.serialize(Collections.singletonMap("content", content)), Collections.singletonMap("content", content))), null);
        session.addMessage(assistant); session.addMessage(ChatMessage.ofTool(content, "write", "call"));
        SessionHistoryService history = new SessionHistoryService(repo); Map<String, Object> page = history.page("alice", id, null, 1);
        assertEquals(2, rows(page).size()); String json = ONode.serialize(page); assertFalse(json.contains("TAIL_EVIDENCE")); assertTrue(json.length() < 6000);
        assertEquals(assistant.getContent(), content);
        assertEquals(content.substring(2000, 2100), history.content("alice", id, 1, null, 2000, 100).get("content"));
        StringBuilder recovered = new StringBuilder(); int offset = 0; boolean more = true;
        while (more) { Map<String, Object> chunk = history.content("alice", id, 1, 0, offset, 8000); recovered.append(chunk.get("content")); offset = ((Number) chunk.get("nextOffset")).intValue(); more = (Boolean) chunk.get("hasMore"); }
        assertTrue(recovered.toString().contains("TAIL_EVIDENCE"));
        assertEquals("{\"content\":", ((String) history.content("alice", id, 1, 0, 0, 11, "args").get("content")));
        assertEquals(content.substring(0, 80), history.content("alice", id, 1, 0, 0, 80, "output").get("content"));
        assertThrows(IllegalArgumentException.class, () -> history.page("alice", id, 2, 30));
        assertThrows(IllegalArgumentException.class, () -> history.content("bob", id, 1, 0, 0, 100));
        assertThrows(IllegalArgumentException.class, () -> history.content("alice", id, 1, 5, 0, 100));
        assertThrows(IllegalArgumentException.class, () -> history.content("alice", id, 1, 0, -1, 100));
        assertThrows(IllegalArgumentException.class, () -> history.content("alice", id, 1, 0, 0, 20000));
    }
}
