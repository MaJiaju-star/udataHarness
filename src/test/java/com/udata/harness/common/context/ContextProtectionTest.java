package com.udata.harness.common.context;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.AgentTrace;
import org.noear.solon.ai.chat.message.*;
import org.noear.solon.ai.chat.tool.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ContextProtectionTest {
    @Test void toolResultCannotMasqueradeAsSummary() {
        assertThrows(IllegalStateException.class, () -> ContextProtection.validateSummary(new ToolMessage(ToolResult.success("summary"), "read", "id", false)));
        assertThrows(IllegalStateException.class, () -> ContextProtection.validateSummary(ChatMessage.ofUser("  ")));
    }
    @Test void temporaryPrefixDoesNotChangeOrdinaryRequestOrder() {
        ChatMessage old = ChatMessage.ofUser("old goal"), user = ChatMessage.ofUser("new exact request"), answer = ChatMessage.ofAssistant("work");
        List<ChatMessage> before = Arrays.asList(old, answer, user, ChatMessage.ofAssistant("current work"));
        ContextProtection protection = new ContextProtection(before);
        assertEquals(user, protection.prepare().get(0));
        assertEquals(before, protection.finish(protection.prepare(), false));
        assertFalse(user.hasMetadata(AgentTrace.META_FIRST));
    }
    @Test void compressedHistoryKeepsCurrentRequestBeforeSurvivingWork() {
        ChatMessage old = ChatMessage.ofUser("old goal"), user = ChatMessage.ofUser("Do not change signatures\nAlso fix B"), work = ChatMessage.ofAssistant("work");
        ContextProtection protection = new ContextProtection(Arrays.asList(old, user, work));
        protection.prepare();
        ChatMessage summary = ChatMessage.ofUser("prior progress");
        assertEquals(Arrays.asList(summary, user, work), protection.finish(Arrays.asList(user, summary, work), true));
        assertThrows(IllegalStateException.class, () -> protection.finish(Collections.singletonList(summary), true));
    }
    @Test void orphanOrSplitToolExchangeIsRejected() {
        AssistantMessage call = new AssistantMessage("", null, false, null, null,
                Collections.singletonList(new ToolCall("id", "id", "read", "{}", Collections.emptyMap())), null);
        ToolMessage result = new ToolMessage(ToolResult.success("text"), "read", "id", false);
        List<ChatMessage> before = Arrays.asList(call, result);
        assertThrows(IllegalStateException.class, () -> ContextProtection.validateToolGroups(before, Collections.singletonList(result)));
        assertThrows(IllegalStateException.class, () -> ContextProtection.validateToolGroups(before, Collections.singletonList(call)));
        assertDoesNotThrow(() -> ContextProtection.validateToolGroups(before, before));
        assertDoesNotThrow(() -> ContextProtection.validateToolGroups(before, Collections.singletonList(ChatMessage.ofUser("summary"))));
    }
}
