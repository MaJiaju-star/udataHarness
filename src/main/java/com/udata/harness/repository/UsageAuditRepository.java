package com.udata.harness.repository;

import com.udata.harness.service.UserWorkspaceService;
import org.noear.snack4.ONode;
import org.noear.solon.ai.AiUsage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Append-only, user-scoped token usage journal. Session deletion does not erase audit records. */
public class UsageAuditRepository {
    private static final String FILE_NAME = "token-usage.jsonl";
    private final Path usersRoot;
    private final Map<String, Set<String>> recordedIds = new LinkedHashMap<>();

    public UsageAuditRepository(Path dataRoot) {
        usersRoot = dataRoot.resolve("users").toAbsolutePath().normalize();
    }

    public synchronized void record(String userId, String sessionId, String workspaceId,
                                    String selectedModel, Map<String, Object> event) {
        String safeUserId = UserWorkspaceService.requireUserId(userId);
        String scope = String.valueOf(event.get("usageScope"));
        String type = String.valueOf(event.get("type"));
        // Only main-Agent calls count. Keep run completion/timing without its
        // framework aggregate, which includes child-Agent usage.
        if ("subagent_event".equals(type) || event.get("parentRunId") != null) return;
        if (!("model_call".equals(scope) || "run".equals(scope))) return;
        if ("model_call".equals(scope) && !(event.get("usage") instanceof Map)) return;
        if ("run".equals(scope) && !("run_end".equals(type) || "error".equals(type))) return;
        String runId = String.valueOf(event.get("runId"));
        String reasonId = String.valueOf(event.get("reasonId"));
        if ("null".equals(runId) || ("model_call".equals(scope) && "null".equals(reasonId))) return;
        String id = "model_call".equals(scope) ? runId + ":" + reasonId : runId + ":run";
        Path path = journal(safeUserId);
        Set<String> ids = recordedIds.computeIfAbsent(safeUserId, ignored -> loadIds(path));
        if (!ids.add(id)) return;

        Map<String, Object> record = new LinkedHashMap<>();
        record.put("id", id);
        record.put("timestamp", event.get("timestamp"));
        record.put("userId", safeUserId);
        record.put("workspaceId", workspaceId);
        record.put("sessionId", sessionId);
        record.put("selectedModel", selectedModel);
        record.put("runId", runId);
        record.put("parentRunId", event.get("parentRunId"));
        record.put("agentName", event.get("agentName"));
        record.put("reasonId", "model_call".equals(scope) ? reasonId : null);
        record.put("scope", scope);
        record.put("status", "run".equals(scope) ? type : null);
        if ("model_call".equals(scope)) record.put("usage", event.get("usage"));
        if ("run".equals(scope)) record.put("durationMs", event.get("durationMs"));
        try {
            Files.createDirectories(path.getParent());
            Files.write(path, (ONode.serialize(record) + "\n").getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            ids.remove(id);
            throw new IllegalStateException("Cannot write token audit record", e);
        }
    }

    /** Record the separate model call that creates a durable compaction checkpoint. */
    public synchronized void recordCompaction(String userId, String workspaceId, String sessionId,
                                              String model, long beforeTokens, long afterTokens,
                                              boolean cacheAligned, AiUsage usage) {
        recordCompaction(userId, workspaceId, sessionId, model, beforeTokens, afterTokens, cacheAligned, usage, null);
    }

    public synchronized void recordCompaction(String userId, String workspaceId, String sessionId,
                                              String model, long beforeTokens, long afterTokens,
                                              boolean cacheAligned, AiUsage usage, Map<String, Object> normalizedUsage) {
        String safeUserId = UserWorkspaceService.requireUserId(userId);
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("id", sessionId + ":compaction:" + UUID.randomUUID());
        record.put("timestamp", System.currentTimeMillis());
        record.put("userId", safeUserId);
        record.put("workspaceId", workspaceId);
        record.put("sessionId", sessionId);
        record.put("selectedModel", model);
        record.put("scope", "compaction");
        record.put("beforeTokens", beforeTokens);
        record.put("afterTokens", afterTokens);
        record.put("cacheAligned", cacheAligned);
        if (normalizedUsage != null) record.put("normalizedUsage", normalizedUsage);
        if (usage != null) {
            Map<String, Object> tokens = new LinkedHashMap<>();
            tokens.put("promptTokens", usage.promptTokens());
            tokens.put("completionTokens", usage.completionTokens());
            tokens.put("totalTokens", usage.totalTokens());
            tokens.put("cacheReadInputTokens", usage.cacheReadInputTokens());
            tokens.put("cacheCreationInputTokens", usage.cacheCreationInputTokens());
            record.put("usage", tokens);
        }
        Path path = journal(safeUserId);
        try {
            Files.createDirectories(path.getParent());
            Files.write(path, (ONode.serialize(record) + "\n").getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot write compaction audit record", e);
        }
    }

    public synchronized void recordCompactionFailure(String userId, String workspaceId,
                                                     String sessionId, String model, String code) {
        String safeUserId = UserWorkspaceService.requireUserId(userId);
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("id", sessionId + ":compaction:" + UUID.randomUUID());
        record.put("timestamp", System.currentTimeMillis());
        record.put("userId", safeUserId);
        record.put("workspaceId", workspaceId);
        record.put("sessionId", sessionId);
        record.put("selectedModel", model);
        record.put("scope", "compaction");
        record.put("status", "failed");
        record.put("reason", code);
        Path path = journal(safeUserId);
        try {
            Files.createDirectories(path.getParent());
            Files.write(path, (ONode.serialize(record) + "\n").getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot write compaction audit record", e);
        }
    }

    public synchronized List<Map<String, Object>> list(String userId, String sessionId) {
        Path path = journal(UserWorkspaceService.requireUserId(userId));
        if (!Files.exists(path)) return Collections.emptyList();
        List<Map<String, Object>> rows = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                if (line.trim().isEmpty()) continue;
                ONode node = ONode.ofJson(line);
                if (sessionId != null && !sessionId.equals(node.get("sessionId").getString())) continue;
                rows.add(node.toBean(Map.class));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read token audit records", e);
        }
        return rows;
    }

    private Set<String> loadIds(Path path) {
        Set<String> ids = new LinkedHashSet<>();
        if (!Files.exists(path)) return ids;
        try {
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                if (!line.trim().isEmpty()) ids.add(ONode.ofJson(line).get("id").getString());
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load token audit IDs", e);
        }
        return ids;
    }

    public synchronized List<Map<String, Object>> recentCompactions(String userId, String sessionId, int limit) {
        Path path = journal(UserWorkspaceService.requireUserId(userId));
        java.util.Deque<Map<String, Object>> tail = new java.util.ArrayDeque<>();
        if (!Files.exists(path)) return new ArrayList<>();
        try (java.io.BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                try {
                    ONode node = ONode.ofJson(line);
                    if (!"compaction".equals(node.get("scope").getString())
                            || !sessionId.equals(node.get("sessionId").getString())) continue;
                    tail.addLast(node.toBean(Map.class));
                    if (tail.size() > Math.max(1, Math.min(500, limit))) tail.removeFirst();
                } catch (RuntimeException ignored) { }
            }
        } catch (IOException e) { throw new IllegalStateException("Cannot read compaction audit", e); }
        return new ArrayList<>(tail);
    }

    private Path journal(String userId) {
        Path path = usersRoot.resolve(userId).resolve(FILE_NAME).normalize();
        if (!path.startsWith(usersRoot)) throw new IllegalArgumentException("Invalid userId");
        return path;
    }
}
