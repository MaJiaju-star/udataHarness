package com.udata.harness.service;

import com.udata.harness.common.request.ChatRequest;
import com.udata.harness.common.request.HitlDecisionRequest;
import com.udata.harness.common.support.*;
import com.udata.harness.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.AgentSession;
import reactor.core.publisher.Flux;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;

class ScheduledTaskServiceTest {
    @TempDir Path root;
    private final ActiveRunRegistry active = new ActiveRunRegistry();
    private final ScheduledRunEvents events = new ScheduledRunEvents();
    private final AtomicInteger calls = new AtomicInteger();
    private volatile ChatRequest request;
    private SessionRepository sessions;
    private String sessionId;
    private ScheduledTaskService service(Function<ChatRequest, Flux<String>> response) {
        if (sessions == null) { sessions = new SessionRepository(root); sessionId = sessions.create("alice", "Test", "", "default").getSessionId(); }
        ChatService chat = new ChatService() {
            public Flux<String> chat(String user, ChatRequest input) { throw new AssertionError("Scheduler must use owned execution"); }
            public Flux<String> chatScheduled(String user, ChatRequest input, String executionId) {
                calls.incrementAndGet(); request = input;
                if (!active.begin(input.getSessionId(), executionId)) return Flux.just("{\"type\":\"error\",\"message\":\"This session is already running\"}");
                return Flux.concat(Flux.just("{\"type\":\"session\"}"), response.apply(input))
                        .doOnNext(json -> events.event(executionId, json))
                        .doOnSubscribe(subscription -> active.bind(input.getSessionId(), subscription))
                        .transform(source -> active.cancellable(input.getSessionId(), source))
                        .doFinally(signal -> active.end(input.getSessionId()));
            }
            public Flux<String> decide(String user, HitlDecisionRequest request) { return Flux.empty(); }
            public boolean cancel(String id) { return active.cancel(id); }
        };
        return new ScheduledTaskService(new AutomationRepository(root), sessions, chat, active, events);
    }
    private Map<String, Object> form() {
        Map<String, Object> form = new LinkedHashMap<>(); form.put("name", "检查项目"); form.put("prompt", "检查并汇报");
        form.put("sessionId", sessionId); form.put("kind", "interval"); form.put("intervalMinutes", 1);
        form.put("timezone", "Asia/Shanghai"); form.put("enabled", true); return form;
    }
    @SuppressWarnings("unchecked") private List<Map<String, Object>> rows(ScheduledTaskService service, String key) { return (List<Map<String, Object>>) service.list("alice", sessionId).get(key); }
    private static void await(BooleanSupplier condition) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (!condition.getAsBoolean() && System.nanoTime() < until) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), "Background execution did not reach expected state");
    }
    @Test void runsWithoutBrowserAndPersistsHistoryAndPromptOrigin() throws Exception {
        try (CloseableScheduler wrapper = new CloseableScheduler(service(input -> Flux.just("{\"type\":\"run_end\",\"runId\":\"agent-run\"}")))) {
            ScheduledTaskService service = wrapper.value; String id = service.save("alice", form());
            service.runNow("alice", id); service.tick(System.currentTimeMillis());
            await(() -> !rows(service, "runs").isEmpty() && "succeeded".equals(rows(service, "runs").get(0).get("status")));
            assertTrue(request.getPrompt().startsWith("【定时任务：检查项目")); assertTrue(request.getPrompt().endsWith("检查并汇报"));
            assertEquals(sessionId, request.getSessionId()); assertEquals(1, calls.get());
            assertFalse(rows(service, "runs").get(0).containsKey("prompt"));
            service.tick(System.currentTimeMillis()); assertEquals(1, calls.get());
            assertEquals("succeeded", ((Map<?, ?>) ((List<?>) new AutomationRepository(root).read("alice").get("runs")).get(0)).get("status"));
        }
    }
    @Test void busySessionCoalescesMissedIntervalsIntoOneQueuedRun() throws Exception {
        try (CloseableScheduler wrapper = new CloseableScheduler(service(input -> Flux.empty()))) {
            ScheduledTaskService service = wrapper.value; service.save("alice", form());
            active.begin(sessionId); long due = ((Number) rows(service, "tasks").get(0).get("nextRunAt")).longValue();
            service.tick(due); service.tick(due + 600000); assertEquals(0, calls.get());
            assertEquals(due, ((Number) rows(service, "tasks").get(0).get("pendingAt")).longValue());
            active.end(sessionId); service.tick(due + 600001);
            await(() -> calls.get() == 1 && "succeeded".equals(rows(service, "tasks").get(0).get("lastStatus")));
            service.tick(due + 600002); assertEquals(1, calls.get());
        }
    }
    @Test void pauseClearsQueueAndManualRunDoesNotEnableARecurringPlan() throws Exception {
        try (CloseableScheduler wrapper = new CloseableScheduler(service(input -> Flux.empty()))) {
            ScheduledTaskService service = wrapper.value; String id = service.save("alice", form());
            active.begin(sessionId); service.runNow("alice", id); service.enabled("alice", id, false); active.end(sessionId);
            service.tick(System.currentTimeMillis() + 600000); assertEquals(0, calls.get());
            service.runNow("alice", id); service.tick(System.currentTimeMillis());
            await(() -> "succeeded".equals(rows(service, "tasks").get(0).get("lastStatus")));
            assertEquals(false, rows(service, "tasks").get(0).get("enabled"));
        }
    }
    @Test void hitlRecordTracksLaterApprovalContinuation() throws Exception {
        try (CloseableScheduler wrapper = new CloseableScheduler(service(input -> {
            sessions.getSession("alice", sessionId).pending(true, null);
            return Flux.just("{\"type\":\"hitl\"}");
        }))) {
            ScheduledTaskService service = wrapper.value; String id = service.save("alice", form()); service.runNow("alice", id); service.tick(System.currentTimeMillis());
            await(() -> !rows(service, "runs").isEmpty() && "waiting_approval".equals(rows(service, "runs").get(0).get("status")));
            String runId = (String) rows(service, "runs").get(0).get("id");
            service.tick(System.currentTimeMillis() + 600000); assertEquals(1, calls.get());
            assertThrows(IllegalArgumentException.class, () -> service.remove("alice", id));
            sessions.getSession("alice", sessionId).pending(false, null);
            events.event(runId, "{\"type\":\"session\"}"); events.event(runId, "{\"type\":\"run_end\",\"runId\":\"continued\"}"); events.ended(runId, "ON_COMPLETE", false);
            assertEquals("succeeded", rows(service, "runs").get(0).get("status")); assertEquals("continued", rows(service, "runs").get(0).get("agentRunId"));
        }
    }
    @Test void ownedStopCannotCancelAnotherRunAndRecordsCancellation() throws Exception {
        try (CloseableScheduler wrapper = new CloseableScheduler(service(input -> Flux.never()))) {
            ScheduledTaskService service = wrapper.value; String id = service.save("alice", form()); service.runNow("alice", id); service.tick(System.currentTimeMillis());
            await(() -> active.isActive(sessionId));
            assertFalse(active.cancelOwned(sessionId, "wrong-owner")); assertTrue(active.isActive(sessionId));
            service.stop("alice", (String) rows(service, "runs").get(0).get("id"));
            await(() -> "cancelled".equals(rows(service, "runs").get(0).get("status")));
        }
    }
    @Test void ownershipValidationAndFailureAreDurable() throws Exception {
        try (CloseableScheduler wrapper = new CloseableScheduler(service(input -> Flux.just("{\"type\":\"error\",\"message\":\"Model unavailable\"}")))) {
            ScheduledTaskService service = wrapper.value; String id = service.save("alice", form());
            assertThrows(RuntimeException.class, () -> service.list("bob", sessionId));
            assertThrows(RuntimeException.class, () -> service.runNow("bob", id));
            Map<String, Object> bad = form(); bad.put("id", id); bad.put("kind", "bad");
            assertThrows(IllegalArgumentException.class, () -> service.save("alice", bad));
            service.runNow("alice", id); service.tick(System.currentTimeMillis());
            await(() -> "failed".equals(rows(service, "tasks").get(0).get("lastStatus")));
            assertEquals("Model unavailable", rows(service, "runs").get(0).get("error"));
            service.remove("alice", id); assertEquals(1, rows(service, "runs").size()); assertTrue(rows(service, "tasks").isEmpty());
        }
    }
    @Test void restartInterruptsClaimsAndSkipsMissedRecurringTimes() throws Exception {
        ScheduledTaskService first = service(input -> Flux.empty()); String id = first.save("alice", form()); first.close();
        AutomationRepository repository = new AutomationRepository(root); Map<String, Object> stored = repository.read("alice");
        @SuppressWarnings("unchecked") Map<String, Object> task = ((List<Map<String, Object>>) stored.get("tasks")).get(0);
        task.put("activeRunId", "old-run"); task.put("nextRunAt", System.currentTimeMillis() - 600000);
        Map<String, Object> run = new LinkedHashMap<>(); run.put("id", "old-run"); run.put("sessionId", sessionId); run.put("taskId", id); run.put("status", "running");
        @SuppressWarnings("unchecked") List<Map<String, Object>> runs = (List<Map<String, Object>>) stored.get("runs"); runs.add(run); repository.write("alice", stored);
        try (CloseableScheduler wrapper = new CloseableScheduler(service(input -> Flux.empty()))) {
            assertEquals("interrupted", rows(wrapper.value, "runs").get(0).get("status"));
            assertTrue(((Number) rows(wrapper.value, "tasks").get(0).get("nextRunAt")).longValue() > System.currentTimeMillis());
            wrapper.value.tick(System.currentTimeMillis()); assertEquals(0, calls.get());
        }
    }
    @Test void lostDispatchRaceIsQueuedInsteadOfSilentlyConsumingTheOccurrence() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        try (CloseableScheduler wrapper = new CloseableScheduler(service(input -> attempts.getAndIncrement() == 0
                ? Flux.just("{\"type\":\"error\",\"message\":\"This session is already running\"}") : Flux.empty()))) {
            ScheduledTaskService service = wrapper.value; String id = service.save("alice", form()); service.runNow("alice", id);
            service.tick(System.currentTimeMillis());
            await(() -> "queued".equals(rows(service, "tasks").get(0).get("lastStatus")) && calls.get() == 1);
            assertEquals("deferred", rows(service, "runs").get(0).get("status"));
            service.tick(System.currentTimeMillis());
            await(() -> "succeeded".equals(rows(service, "tasks").get(0).get("lastStatus")));
            assertEquals(2, calls.get());
        }
    }
    @Test void serviceShutdownMarksAnActiveExecutionInterruptedWithoutReplayingIt() throws Exception {
        ScheduledTaskService service = service(input -> Flux.never());
        String id = service.save("alice", form()); service.runNow("alice", id); service.tick(System.currentTimeMillis());
        await(() -> active.isActive(sessionId)); service.close();
        await(() -> "interrupted".equals(rows(service, "runs").get(0).get("status")));
        service.tick(System.currentTimeMillis() + 600000); assertEquals(1, calls.get());
    }
    private static class CloseableScheduler implements AutoCloseable {
        final ScheduledTaskService value; CloseableScheduler(ScheduledTaskService value) { this.value = value; }
        public void close() { value.close(); }
    }
}
