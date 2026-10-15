package com.udata.harness.common.context;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** A bounded, atomic task ledger. Switching focus does not erase constraints or evidence. */
public final class ContextTaskStore {
    private final ContextArtifactStore artifacts;

    public ContextTaskStore(ContextArtifactStore artifacts) {
        this.artifacts = artifacts;
    }

    public synchronized String update(String sessionId, String taskId, String module, String state,
                                      String requirements, String conclusion, String verification,
                                      String nextStep) throws IOException {
        validateId(taskId);
        if (!("active".equals(state) || "paused".equals(state) || "closed".equals(state))) {
            throw new IOException("State must be active, paused or closed");
        }
        Properties ledger = read(sessionId);
        String prefix = taskId + ".";
        if (!ledger.containsKey(prefix + "state") && taskCount(ledger) >= 100) {
            throw new IOException("Task ledger limit reached");
        }
        if ("closed".equals(ledger.getProperty(prefix + "state")) && "active".equals(state)) {
            ledger.remove(prefix + "verification"); // Reopened work requires fresh verification before closure.
        }
        put(ledger, prefix + "module", module);
        put(ledger, prefix + "requirements", requirements);
        put(ledger, prefix + "conclusion", conclusion);
        put(ledger, prefix + "verification", verification);
        put(ledger, prefix + "nextStep", nextStep);
        if ("closed".equals(state) && (ledger.getProperty(prefix + "conclusion", "").trim().isEmpty()
                || ledger.getProperty(prefix + "verification", "").trim().isEmpty())) {
            throw new IOException("Closing requires a conclusion and reported verification evidence");
        }
        String previous = ledger.getProperty("activeTaskId", "");
        if ("active".equals(state)) {
            if (!previous.isEmpty() && !previous.equals(taskId)) ledger.setProperty(previous + ".state", "paused");
            ledger.setProperty("activeTaskId", taskId);
        } else if (previous.equals(taskId)) {
            ledger.remove("activeTaskId");
        }
        ledger.setProperty(prefix + "state", state);
        ledger.setProperty(prefix + "updatedAt", Long.toString(System.currentTimeMillis()));
        ContextArtifactStore.writeProperties(path(sessionId), ledger);
        artifacts.recordAction(sessionId, "task_update", taskId, state, 0);
        return snapshot(sessionId, taskId);
    }

    public synchronized String currentTaskId(String sessionId) throws IOException {
        return read(sessionId).getProperty("activeTaskId", "");
    }

    public synchronized String state(String sessionId, String taskId) throws IOException {
        validateId(taskId);
        return read(sessionId).getProperty(taskId + ".state", "");
    }

    public synchronized java.util.List<java.util.Map<String, Object>> summaries(String sessionId) throws IOException {
        Properties ledger = read(sessionId);
        java.util.List<java.util.Map<String, Object>> result = new java.util.ArrayList<>();
        for (String key : new java.util.TreeSet<>(ledger.stringPropertyNames())) {
            if (!key.endsWith(".state")) continue;
            String id = key.substring(0, key.length() - 6);
            java.util.Map<String, Object> task = new java.util.LinkedHashMap<>();
            task.put("taskId", id); task.put("state", ledger.getProperty(key));
            task.put("module", ledger.getProperty(id + ".module", ""));
            task.put("updatedAt", ledger.getProperty(id + ".updatedAt", ""));
            result.add(task);
        }
        return result;
    }

    public synchronized String snapshot(String sessionId, String taskId) throws IOException {
        Properties ledger = read(sessionId);
        String id = taskId == null || taskId.trim().isEmpty() ? ledger.getProperty("activeTaskId", "") : taskId;
        if (id.isEmpty()) return "No active task. Use context_task_update to record task focus and constraints.";
        validateId(id);
        if (!ledger.containsKey(id + ".state")) return "Task not found in this session.";
        StringBuilder view = new StringBuilder("Task snapshot; task_id=").append(id);
        for (String field : new String[]{"module", "state", "requirements", "conclusion", "verification", "nextStep"}) {
            view.append('\n').append(field).append(": ").append(ledger.getProperty(id + "." + field, ""));
        }
        return view.append("\nVerification is reported evidence; revalidate after source changes.").toString();
    }

    private Path path(String sessionId) throws IOException {
        return artifacts.sessionDirectory(sessionId).resolve("tasks.properties");
    }

    private Properties read(String sessionId) throws IOException {
        Properties ledger = new Properties();
        Path file = path(sessionId);
        if (Files.exists(file)) {
            return ContextArtifactStore.readProperties(file);
        }
        return ledger;
    }

    private static void put(Properties ledger, String key, String value) throws IOException {
        if (value == null) return; // Omitted fields preserve existing task facts.
        if (value.length() > 4000) throw new IOException("Task field exceeds 4000 characters");
        ledger.setProperty(key, value);
    }

    private static int taskCount(Properties ledger) {
        int count = 0;
        for (String key : ledger.stringPropertyNames()) if (key.endsWith(".state")) count++;
        return count;
    }

    private static void validateId(String id) throws IOException {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,64}")) throw new IOException("Invalid task ID");
    }
}
