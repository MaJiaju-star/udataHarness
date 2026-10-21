package com.udata.harness.notification;

import org.junit.jupiter.api.Test;
import java.util.*;
import org.noear.snack4.ONode;
import static org.junit.jupiter.api.Assertions.*;

class NotificationExecutionTest {
    private Map<String, Object> event(String type) { Map<String, Object> event = new LinkedHashMap<>(); event.put("type", type); return event; }
    @Test void approvalSegmentsReuseIdentityExcludeWaitAndOnlyFinishOnce() {
        Map<String, Object> state = NotificationExecution.begin("turn-1", "chat", "ws", "项目", "s", "修复接口", 1000);
        NotificationExecution.observe(state, event("run_pending"));
        assertNull(NotificationExecution.end(state, "ON_COMPLETE", false, true, 2000));
        NotificationExecution.resume(state, 12000);
        Map<String, Object> text = event("text"); text.put("content", "已完成修复"); NotificationExecution.observe(state, text);
        NotificationExecution.observe(state, event("run_end"));
        Map<String, Object> result = NotificationExecution.end(state, "ON_COMPLETE", false, false, 14000);
        assertEquals("turn-1", result.get("execution.id")); assertEquals("succeeded", result.get("execution.status"));
        assertEquals(3000L, result.get("execution.durationMs")); assertEquals("已完成修复", result.get("result.summary"));
        state.put("finished", true); assertNull(NotificationExecution.end(state, "ON_COMPLETE", false, false, 15000));
    }
    @Test void childThinkingAndOldToolLoopTextDoNotLeakIntoFinalSummary() {
        Map<String, Object> state = NotificationExecution.begin("x", "scheduled", "w", "w", "s", "t", 1000);
        Map<String, Object> text = event("text"); text.put("content", "before tools"); NotificationExecution.observe(state, text);
        NotificationExecution.observe(state, event("reason_start"));
        Map<String, Object> child = event("subagent_event"); child.put("content", "child private"); NotificationExecution.observe(state, child);
        Map<String, Object> thought = event("thinking"); thought.put("content", "private reasoning"); NotificationExecution.observe(state, thought);
        Map<String, Object> result = event("text_replay"); result.put("content", "<think>hidden</think>最终结果"); NotificationExecution.observe(state, result);
        NotificationExecution.observe(state, event("run_end"));
        assertEquals("最终结果", NotificationExecution.end(state, "ON_COMPLETE", false, false, 2000).get("result.summary"));
    }
    @Test void failuresAndStopsAreNotReportedAsSuccessOrApproval() {
        for (String signal : Arrays.asList("ON_ERROR", "CANCEL", "ON_COMPLETE")) {
            Map<String, Object> state = NotificationExecution.begin("x", "chat", "w", "w", "s", "t", 0);
            assertEquals("CANCEL".equals(signal) ? "cancelled" : "failed", NotificationExecution.end(state, signal, false, false, 10).get("execution.status"));
        }
        Map<String, Object> state = NotificationExecution.begin("x", "chat", "w", "w", "s", "t", 0);
        Map<String, Object> error = event("error"); error.put("message", "审批上限"); NotificationExecution.observe(state, error);
        assertEquals("failed", NotificationExecution.end(state, "ON_COMPLETE", false, true, 10).get("execution.status"));
    }
    @SuppressWarnings("unchecked") @Test void jsonInterpolationEscapesValuesAndPreservesTypedNumbers() {
        Map<String, Object> variables = NotificationTemplate.sample(); variables.put("result.summary", "quote\"\nline\\path${session.id}");
        String body = NotificationTemplate.body("{\"content\":\"${result.summary}\",\"nested\":[\"${execution.durationMs}\"]}", "json", variables);
        Map<String, Object> parsed = ONode.ofJson(body).toBean(Map.class);
        assertEquals(variables.get("result.summary"), parsed.get("content")); assertTrue(((List<?>) parsed.get("nested")).get(0) instanceof Number);
        assertEquals("prefix " + variables.get("result.summary"), NotificationTemplate.render("prefix ${result.summary}", variables, false));
        assertThrows(IllegalArgumentException.class, () -> NotificationTemplate.body("{\"x\":\"${unknown}\"}", "json", variables));
        assertThrows(IllegalArgumentException.class, () -> NotificationTemplate.url("https://${workspace.name}/send", variables));
        assertEquals("/send", NotificationTemplate.url("http://localhost/send?x=${result.summary}", variables).getPath());
        variables.put("result.summary", "evil\r\nHeader: x"); assertThrows(IllegalArgumentException.class, () -> NotificationTemplate.header("${result.summary}", variables));
    }
    @Test void jsonBusinessTypeFieldsStayDataRatherThanJavaTypes() {
        String body = NotificationTemplate.body("{\"@type\":\"company.message.NotAJavaClass\",\"nested\":{\"@type\":\"message\",\"value\":\"${execution.durationMs}\"}}", "json", NotificationTemplate.sample());
        ONode parsed = ONode.ofJson(body);
        assertEquals("company.message.NotAJavaClass", parsed.get("@type").getString());
        assertEquals("message", parsed.get("nested").get("@type").getString());
        assertTrue(parsed.get("nested").get("value").isNumber());
    }

}
