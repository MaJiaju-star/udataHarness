package com.udata.harness.service.impl;

import com.udata.harness.common.context.*;
import com.udata.harness.common.domain.SessionMetadata;
import com.udata.harness.repository.SessionRepository;
import com.udata.harness.repository.UsageAuditRepository;
import com.udata.harness.service.UserWorkspaceService;
import org.noear.solon.annotation.Component;
import org.noear.solon.annotation.Inject;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-only session dashboard; ownership is checked before touching any artifact directory. */
@Component
public class ContextDashboardService {
    @Inject("${agent.data-dir:./data}") private String dataDir;
    @Inject private SessionRepository sessions;
    @Inject private UsageAuditRepository usageAudit;
    @Inject private ContextCacheConfiguration config;

    public Map<String, Object> overview(String userId, String sessionId, int requestedLimit) throws IOException {
        userId = UserWorkspaceService.requireUserId(userId);
        SessionMetadata session = sessions.read(userId, sessionId);
        Path root = Paths.get(dataDir).toAbsolutePath().normalize().resolve("users").resolve(userId).resolve("context-artifacts");
        Path workspace = root.resolve(session.getWorkspaceId()).normalize();
        if (!workspace.startsWith(root) || workspace.equals(root)) throw new IllegalArgumentException("Invalid workspace scope");
        ContextArtifactStore store = new ContextArtifactStore(workspace);
        int limit = Math.max(1, Math.min(requestedLimit, 500));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", sessionId); result.put("workspaceId", session.getWorkspaceId());
        result.put("limit", limit); result.put("observations", store.observations(sessionId, false, limit));
        result.put("actions", store.observations(sessionId, true, limit));
        result.put("tasks", new ContextTaskStore(store).summaries(sessionId));
        result.put("policyEnabled", config.enabled);
        result.put("prices", config.policy(session.getModel()).prices.view());
        List<Map<String, Object>> compactions = usageAudit.recentCompactions(userId, sessionId, limit);
        for (Map<String, Object> record : compactions) {
            if (record.get("normalizedUsage") instanceof Map) {
                record.put("pricingBasis", "recorded_configuration");
                continue;
            }
            Object raw = record.get("usage");
            if (!(raw instanceof Map)) continue;
            Map<String, Object> usage = (Map<String, Object>) raw;
            record.put("pricingBasis", "current_configuration");
            record.put("normalizedUsage", ContextUsage.normalize(number(usage.get("promptTokens")), number(usage.get("completionTokens")),
                    number(usage.get("cacheReadInputTokens")), number(usage.get("cacheCreationInputTokens")), null,
                    config.policy(String.valueOf(record.get("selectedModel"))).prices));
        }
        result.put("compactions", compactions);
        return result;
    }

    private static long number(Object value) { return value instanceof Number ? ((Number) value).longValue() : 0; }
}
