package com.udata.harness.service.impl;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.react.intercept.HITLTask;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatServiceImplTest {

    @Test
    void alwaysAllowAppliesToSameToolOnlyWithinSameSession() {
        ChatServiceImpl service = new ChatServiceImpl();
        InMemoryAgentSession firstSession = InMemoryAgentSession.of("session-1");
        HITLTask bash = task("call-1", "bash");
        HITLTask anotherBash = task("call-2", "bash");
        HITLTask chart = task("call-3", "render_chart");

        service.rememberAlwaysAllowedTools(firstSession, List.of(bash));

        assertTrue(service.areAllToolsAlwaysAllowed(firstSession, List.of(anotherBash)));
        assertFalse(service.areAllToolsAlwaysAllowed(firstSession, List.of(chart)));
        assertFalse(service.areAllToolsAlwaysAllowed(
                InMemoryAgentSession.of("session-2"), List.of(anotherBash)));
    }

    @Test
    void everyToolInBatchMustBeAllowedBeforeAutomaticResume() {
        ChatServiceImpl service = new ChatServiceImpl();
        InMemoryAgentSession session = InMemoryAgentSession.of("session-1");
        HITLTask bash = task("call-1", "bash");
        HITLTask chart = task("call-2", "render_chart");

        service.rememberAlwaysAllowedTools(session, List.of(bash));

        assertFalse(service.areAllToolsAlwaysAllowed(session, List.of(bash, chart)));
        service.rememberAlwaysAllowedTools(session, List.of(chart));
        assertTrue(service.areAllToolsAlwaysAllowed(session, List.of(bash, chart)));
    }

    @Test
    void normalizesSupportedThinkingDepths() {
        ChatServiceImpl service = new ChatServiceImpl();

        assertEquals("auto", service.normalizeThinkingDepth(null));
        assertEquals("none", service.normalizeThinkingDepth("NONE"));
        assertEquals("low", service.normalizeThinkingDepth("low"));
        assertEquals("medium", service.normalizeThinkingDepth("medium"));
        assertEquals("high", service.normalizeThinkingDepth("high"));
        assertEquals("max", service.normalizeThinkingDepth("max"));
        assertEquals("auto", service.normalizeThinkingDepth("unsupported"));
    }

    private static HITLTask task(String callUuid, String toolName) {
        return new HITLTask(callUuid, toolName, Map.of("command", "echo ok"), "需要确认");
    }
}
