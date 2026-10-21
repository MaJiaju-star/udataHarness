package com.udata.harness.service;

import com.udata.harness.common.request.ChatRequest;
import com.udata.harness.common.support.*;
import com.udata.harness.repository.AutomationRepository;
import com.udata.harness.repository.SessionRepository;
import org.noear.snack4.ONode;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.intercept.HITL;
import org.noear.solon.annotation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.file.Paths;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static com.udata.harness.common.support.ScheduleTiming.*;

/** Single-process persistent scheduler. At most four runs execute and one occurrence queues per task. */
@Component
public class ScheduledTaskService {
    private static final Logger LOG = LoggerFactory.getLogger(ScheduledTaskService.class);
    @Inject("${agent.data-dir:./data}") private String dataDir;
    @Inject private SessionRepository sessions;
    @Inject private ChatService chat;
    @Inject private ActiveRunRegistry active;
    @Inject private ScheduledRunEvents events;
    private AutomationRepository repository;
    private final Map<String, Map<String, Object>> states = new LinkedHashMap<>();
    private final Set<String> inFlight = new HashSet<>();
    private final Set<String> claimedSessions = new HashSet<>();
    private final ExecutorService workers = Executors.newFixedThreadPool(4, runnable -> daemon(runnable, "scheduled-agent"));
    private ScheduledExecutorService timer;
    private volatile boolean closed;
    public ScheduledTaskService() { }
    public ScheduledTaskService(AutomationRepository repository, SessionRepository sessions, ChatService chat,
                                ActiveRunRegistry active, ScheduledRunEvents events) {
        this.repository = repository; this.sessions = sessions; this.chat = chat; this.active = active; this.events = events;
        recover(); events.listen(this::onEvent);
    }
    private static Thread daemon(Runnable runnable, String name) { Thread thread = new Thread(runnable, name); thread.setDaemon(true); return thread; }
    @Init public void start() {
        if (repository == null) { repository = new AutomationRepository(Paths.get(dataDir)); recover(); events.listen(this::onEvent); }
        timer = Executors.newSingleThreadScheduledExecutor(runnable -> daemon(runnable, "schedule-timer"));
        timer.scheduleWithFixedDelay(() -> { try { tick(System.currentTimeMillis()); } catch (RuntimeException e) { LOG.error("Scheduled task scan failed", e); } }, 2, 1, TimeUnit.SECONDS);
    }
    private synchronized void recover() {
        for (String user : repository.users()) {
            try {
                Map<String, Object> state = state(user); boolean changed = false;
                for (Map<String, Object> run : rows(state, "runs")) if (unfinished(run)) {
                    run.put("status", "interrupted"); run.put("error", "服务重启，原执行已中断，请查看对话后决定是否重新执行");
                    run.put("endedAt", System.currentTimeMillis()); changed = true;
                }
                for (Map<String, Object> task : rows(state, "tasks")) {
                    if (!text(task, "activeRunId").isEmpty()) { task.put("activeRunId", ""); task.put("lastStatus", "interrupted"); changed = true; }
                    long next = number(task, "nextRunAt"), now = System.currentTimeMillis();
                    if (Boolean.TRUE.equals(task.get("enabled")) && next > 0 && next < now && !"once".equals(text(task, "kind"))) {
                        task.put("nextRunAt", next(task, now, next)); changed = true;
                    }
                }
                if (changed) repository.write(user, state);
            } catch (RuntimeException e) { states.remove(user); LOG.error("Cannot recover scheduled tasks for {}", user, e); }
        }
    }
    private Map<String, Object> state(String user) {
        UserWorkspaceService.requireUserId(user); return states.computeIfAbsent(user, repository::read);
    }
    @SuppressWarnings("unchecked") private static List<Map<String, Object>> rows(Map<String, Object> state, String key) { return (List<Map<String, Object>>) state.get(key); }
    private static boolean unfinished(Map<String, Object> run) { return Arrays.asList("running", "waiting_approval").contains(text(run, "status")); }
    private Map<String, Object> task(String user, String id) {
        Map<String, Object> task = rows(state(user), "tasks").stream().filter(row -> id.equals(row.get("id"))).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("定时任务不存在"));
        sessions.read(user, text(task, "sessionId")); return task;
    }
    public synchronized Map<String, Object> list(String user, String sessionId) {
        sessions.read(user, sessionId); Map<String, Object> state = state(user), result = new LinkedHashMap<>();
        for (String key : Arrays.asList("tasks", "runs")) {
            List<Map<String, Object>> selected = new ArrayList<>();
            for (Map<String, Object> row : rows(state, key)) if (sessionId.equals(row.get("sessionId"))) {
                Map<String, Object> view = new LinkedHashMap<>(row); if ("runs".equals(key)) view.remove("prompt"); selected.add(view);
            }
            if ("runs".equals(key)) { Collections.reverse(selected); if (selected.size() > 50) selected = new ArrayList<>(selected.subList(0, 50)); }
            result.put(key, selected);
        }
        return result;
    }
    public synchronized String save(String user, Map<String, Object> input) {
        String sessionId = text(input, "sessionId");
        com.udata.harness.common.domain.SessionMetadata session = sessions.read(user, sessionId);
        Map<String, Object> state = state(user); String id = text(input, "id");
        Map<String, Object> old = id.isEmpty() ? null : task(user, id);
        if (old != null && !sessionId.equals(old.get("sessionId"))) throw new IllegalArgumentException("不能移动任务所属对话");
        if (old != null && !text(old, "activeRunId").isEmpty()) throw new IllegalArgumentException("执行中或等待审批的任务暂不能编辑");
        if (old == null && rows(state, "tasks").size() >= 100) throw new IllegalArgumentException("最多保存 100 个定时任务");
        String name = text(input, "name"), prompt = text(input, "prompt");
        if (name.isEmpty() || name.length() > 80 || prompt.isEmpty() || prompt.length() > 50000) throw new IllegalArgumentException("名称须为 1～80 字符，执行指令须为 1～50000 字符");
        Map<String, Object> task = new LinkedHashMap<>();
        for (String key : Arrays.asList("name", "prompt", "kind", "timezone", "at", "time", "model")) task.put(key, text(input, key));
        if (text(task, "timezone").isEmpty()) task.put("timezone", "Asia/Shanghai");
        task.put("weekday", input.get("weekday")); task.put("intervalMinutes", input.get("intervalMinutes"));
        task.put("enabled", !Boolean.FALSE.equals(input.get("enabled")));
        long now = System.currentTimeMillis(); validate(task, now);
        if (id.isEmpty()) id = "task-" + UUID.randomUUID();
        task.put("id", id); task.put("sessionId", sessionId); task.put("workspaceId", session.getWorkspaceId());
        task.put("nextRunAt", Boolean.TRUE.equals(task.get("enabled")) ? next(task, now, 0) : 0);
        task.put("pendingAt", 0); task.put("activeRunId", ""); task.put("lastStatus", old == null ? "idle" : old.get("lastStatus"));
        task.put("lastRunAt", old == null ? 0 : old.get("lastRunAt"));
        if (old != null) rows(state, "tasks").remove(old);
        rows(state, "tasks").add(task); repository.write(user, state); return id;
    }
    public synchronized void enabled(String user, String id, boolean enabled) {
        Map<String, Object> task = task(user, id); task.put("enabled", enabled);
        task.put("pendingAt", 0); task.put("pendingManual", false);
        task.put("nextRunAt", enabled ? next(task, System.currentTimeMillis(), 0) : 0);
        repository.write(user, state(user));
    }
    public synchronized void remove(String user, String id) {
        Map<String, Object> task = task(user, id);
        if (!text(task, "activeRunId").isEmpty()) throw new IllegalArgumentException("请先停止执行或处理完待审批任务");
        rows(state(user), "tasks").remove(task); repository.write(user, state(user));
    }
    public synchronized void runNow(String user, String id) {
        Map<String, Object> task = task(user, id);
        if (!text(task, "activeRunId").isEmpty() || number(task, "pendingAt") > 0) throw new IllegalArgumentException("该任务正在执行或已经排队");
        task.put("pendingAt", System.currentTimeMillis()); task.put("pendingManual", true); task.put("lastStatus", "queued");
        repository.write(user, state(user));
    }
    public synchronized void stop(String user, String runId) {
        Map<String, Object> run = rows(state(user), "runs").stream().filter(row -> runId.equals(row.get("id"))).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("执行记录不存在"));
        sessions.read(user, text(run, "sessionId"));
        if (!"running".equals(text(run, "status"))) throw new IllegalArgumentException("该执行不在运行中，待审批请返回对话处理");
        run.put("stopRequested", true); repository.write(user, state(user));
        active.cancelOwned(text(run, "sessionId"), runId);
    }
    /** Tick only claims jobs; model work is held by service-owned worker subscriptions. */
    public synchronized void tick(long now) {
        if (closed) return;
        for (Map.Entry<String, Map<String, Object>> entry : states.entrySet()) {
            String user = entry.getKey(); Map<String, Object> state = entry.getValue(); boolean changed = false;
            for (Map<String, Object> task : rows(state, "tasks")) {
                long due = number(task, "nextRunAt");
                if (Boolean.TRUE.equals(task.get("enabled")) && due > 0 && due <= now) {
                    if (number(task, "pendingAt") == 0) {
                        task.put("pendingAt", due); if (text(task, "activeRunId").isEmpty()) task.put("lastStatus", "queued");
                    }
                    task.put("nextRunAt", next(task, now, due)); changed = true;
                }
                if (number(task, "pendingAt") == 0 || !text(task, "activeRunId").isEmpty() || inFlight.size() >= 4) continue;
                String sessionId = text(task, "sessionId");
                if (active.isActive(sessionId) || claimedSessions.contains(sessionId)) continue;
                try {
                    AgentSession session = sessions.getSession(user, sessionId);
                    if (session.isPending() || !HITL.getPendingTasks(session).isEmpty()) continue;
                } catch (RuntimeException e) {
                    task.put("enabled", false); task.put("nextRunAt", 0); task.put("pendingAt", 0);
                    task.put("lastStatus", "failed"); task.put("error", "对话已删除或不可用"); changed = true; continue;
                }
                Map<String, Object> run = new LinkedHashMap<>(); String runId = "scheduled-" + UUID.randomUUID();
                run.put("id", runId); run.put("taskId", task.get("id")); run.put("name", task.get("name"));
                run.put("sessionId", sessionId); run.put("scheduledAt", task.get("pendingAt"));
                run.put("trigger", Boolean.TRUE.equals(task.get("pendingManual")) ? "manual" : "schedule");
                run.put("startedAt", now); run.put("status", "running"); run.put("endedAt", 0);
                run.put("prompt", task.get("prompt")); run.put("model", task.get("model")); run.put("timezone", task.get("timezone"));
                rows(state, "runs").add(run);
                while (rows(state, "runs").size() > 200) {
                    Map<String, Object> oldest = rows(state, "runs").stream().filter(row -> !unfinished(row)).findFirst().orElse(null);
                    if (oldest == null) break; rows(state, "runs").remove(oldest);
                }
                task.put("activeRunId", runId); task.put("lastRunAt", now); task.put("lastStatus", "running");
                task.put("pendingAt", 0); task.put("pendingManual", false); task.remove("error");
                if ("once".equals(text(task, "kind")) && "schedule".equals(run.get("trigger"))) task.put("enabled", false);
                try { repository.write(user, state); } // Dispatch only after the durable claim succeeds.
                catch (RuntimeException e) {
                    rows(state, "runs").remove(run); task.put("activeRunId", ""); task.put("pendingAt", run.get("scheduledAt"));
                    task.put("pendingManual", "manual".equals(run.get("trigger"))); task.put("lastStatus", "queued"); throw e;
                }
                changed = false; inFlight.add(runId); claimedSessions.add(sessionId);
                workers.submit(() -> execute(user, run));
            }
            if (changed) repository.write(user, state);
        }
    }
    private void execute(String user, Map<String, Object> run) {
        String runId = text(run, "id"), sessionId = text(run, "sessionId"); AtomicReference<String> error = new AtomicReference<>();
        try {
            synchronized (this) { if (Boolean.TRUE.equals(run.get("stopRequested"))) return; }
            ChatRequest request = new ChatRequest(); request.setSessionId(sessionId); request.setModel(text(run, "model"));
            String time = Instant.ofEpochMilli(number(run, "scheduledAt")).atZone(ZoneId.of(text(run, "timezone"))).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z"));
            request.setPrompt("【定时任务：" + text(run, "name") + " · " + time + "】\n" + text(run, "prompt"));
            chat.chatScheduled(user, request, runId).doOnNext(json -> {
                ONode event = ONode.ofJson(json);
                if ("error".equals(event.get("type").getString())) error.set(event.get("message").getString());
            }).blockLast();
        } catch (Throwable e) { error.set(e.getMessage() == null ? "执行异常" : e.getMessage()); }
        finally {
            synchronized (this) {
                if (unfinished(run)) {
                    if ("This session is already running".equals(error.get()) || "Resolve the pending approval before sending another prompt".equals(error.get())) {
                        run.put("status", "deferred"); run.put("endedAt", System.currentTimeMillis());
                        for (Map<String, Object> task : rows(state(user), "tasks")) if (run.get("taskId").equals(task.get("id"))) {
                            task.put("activeRunId", ""); task.put("lastStatus", "queued");
                            if (number(task, "pendingAt") == 0) task.put("pendingAt", run.get("scheduledAt"));
                            task.put("pendingManual", "manual".equals(run.get("trigger")));
                        }
                        repository.write(user, state(user)); inFlight.remove(runId); claimedSessions.remove(sessionId); return;
                    }
                    if (error.get() != null) run.put("error", error.get());
                    boolean pending = false;
                    try { AgentSession session = sessions.getSession(user, sessionId); pending = session.isPending() || !HITL.getPendingTasks(session).isEmpty(); }
                    catch (RuntimeException ignored) { }
                    finish(user, run, pending, false);
                }
                inFlight.remove(runId); claimedSessions.remove(sessionId);
            }
        }
    }
    private synchronized void onEvent(String id, ONode event) {
        for (Map.Entry<String, Map<String, Object>> entry : states.entrySet()) {
            Map<String, Object> run = rows(entry.getValue(), "runs").stream().filter(row -> id.equals(row.get("id"))).findFirst().orElse(null);
            if (run == null || !unfinished(run)) continue;
            String type = event.get("type").getString();
            if ("error".equals(type)) run.put("error", event.get("message").getString());
            if ("session".equals(type)) {
                run.put("status", "running");
                for (Map<String, Object> task : rows(entry.getValue(), "tasks"))
                    if (id.equals(task.get("activeRunId"))) task.put("lastStatus", "running");
            }
            if ("hitl".equals(type)) run.put("status", "waiting_approval");
            if ("run_end".equals(type)) run.put("agentRunId", event.get("runId").getString());
            if ("segment_end".equals(type)) {
                finish(entry.getKey(), run, event.get("pending").getBoolean(), "CANCEL".equals(event.get("signal").getString())); return;
            }
            repository.write(entry.getKey(), entry.getValue()); return;
        }
    }
    private void finish(String user, Map<String, Object> run, boolean pending, boolean cancelled) {
        if (!unfinished(run)) return;
        if (closed) run.put("error", "服务停止，原执行已中断，请查看对话后决定是否重新执行");
        String status = closed ? "interrupted" : cancelled || Boolean.TRUE.equals(run.get("stopRequested")) ? "cancelled"
                : !text(run, "error").isEmpty() ? "failed" : pending ? "waiting_approval" : "succeeded";
        run.put("status", status);
        if (!"waiting_approval".equals(status)) run.put("endedAt", System.currentTimeMillis());
        for (Map<String, Object> task : rows(state(user), "tasks")) if (run.get("taskId").equals(task.get("id"))) {
            task.put("lastStatus", status);
            if (!"waiting_approval".equals(status)) task.put("activeRunId", "");
        }
        repository.write(user, state(user));
    }
    @Destroy public synchronized void close() {
        closed = true; if (timer != null) timer.shutdownNow(); workers.shutdownNow();
    }
}
