export const emptyTokenUsage = () => ({
    promptTokens: 0,
    thinkTokens: 0,
    completionTokens: 0,
    totalTokens: 0,
    cacheCreationInputTokens: 0,
    cacheReadInputTokens: 0,
    modelCallCount: 0,
    durationMs: 0,
    tokensPerSecond: 0,
    firstTokenLatencyMs: null,
    firstTokenStartedAt: null,
    firstTokenRunId: null,
    firstTokenReasonId: null,
    firstTokenPending: false,
    seenCalls: {}
});

const number = value => Number(value || 0);

// AiUsage.promptTokens is normalized to include cache reads/writes for all providers.
export function cacheHitRate(usage) {
    const input = number(usage.promptTokens);
    return input > 0 ? Math.min(100, Math.max(0, number(usage.cacheReadInputTokens) * 100 / input)) : null;
}

export function firstTokenLatencyText(value) {
    if (value == null || !Number.isFinite(value) || value < 0) return "—";
    return value < 1000 ? `${Math.round(value)} ms` : `${(value / 1000).toFixed(2)} s`;
}

// Server timestamps on both events avoid including browser clock skew/SSE delivery delay.
// Keep the first main request of this user turn; later tool-loop requests cannot replace it.
function trackFirstToken(current, event) {
    if (current.firstTokenLatencyMs != null) return current;
    if (event.type === "reason_start") {
        if (current.firstTokenStartedAt != null) {
            return event.runId === current.firstTokenRunId && event.reasonId === current.firstTokenReasonId
                ? current : {...current, firstTokenPending: false};
        }
        if (!Number.isFinite(event.timestamp) || !event.runId || !event.reasonId) return current;
        return {...current, firstTokenStartedAt: event.timestamp, firstTokenRunId: event.runId,
            firstTokenReasonId: event.reasonId, firstTokenPending: true};
    }
    if (!current.firstTokenPending || event.runId !== current.firstTokenRunId) return current;
    if (event.usageScope === "run" || (event.usageScope === "model_call"
            && event.reasonId === current.firstTokenReasonId)) {
        return {...current, firstTokenPending: false};
    }
    const tokenDelta = ["text", "thinking", "tool_args_delta"].includes(event.type)
        && event.finished !== true && typeof event.content === "string" && event.content.length > 0;
    if (!tokenDelta || (event.reasonId && event.reasonId !== current.firstTokenReasonId)
            || !Number.isFinite(event.timestamp) || event.timestamp < current.firstTokenStartedAt) return current;
    return {...current, firstTokenLatencyMs: event.timestamp - current.firstTokenStartedAt, firstTokenPending: false};
}

export function accumulateTokenUsage(current, event) {
    if (!event || event.type === "subagent_event" || event.parentRunId || event.usageScope === "compaction") return current;
    current = trackFirstToken(current, event);
    if (event.usageScope === "run") {
        const durationMs = number(event.durationMs);
        return {
            ...current,
            durationMs,
            tokensPerSecond: durationMs > 0 ? Math.round(current.completionTokens * 10000 / durationMs) / 10 : 0
        };
    }
    if (event.usageScope !== "model_call" || !event.usage) return current;
    const usage = event.usage;
    const callId = event.runId && event.reasonId ? `${event.runId}:${event.reasonId}` : null;
    if (callId && current.seenCalls[callId]) return current;
    return {
        ...current,
        promptTokens: current.promptTokens + number(usage.promptTokens),
        thinkTokens: current.thinkTokens + number(usage.thinkTokens),
        completionTokens: current.completionTokens + number(usage.completionTokens),
        totalTokens: current.totalTokens + number(usage.totalTokens),
        cacheCreationInputTokens: current.cacheCreationInputTokens + number(usage.cacheCreationInputTokens),
        cacheReadInputTokens: current.cacheReadInputTokens + number(usage.cacheReadInputTokens),
        modelCallCount: current.modelCallCount + 1,
        seenCalls: callId ? {...current.seenCalls, [callId]: true} : current.seenCalls
    };
}

export function summarizeAudit(records) {
    const runs = new Map();
    const seenCalls = new Set();
    for (const record of records || []) {
        if (record.parentRunId || record.scope === "compaction") continue;
        const runId = record.runId;
        if (!runId) continue;
        const row = runs.get(runId) || {
            runId, sessionId: record.sessionId, selectedModel: record.selectedModel,
            timestamp: 0, modelCallCount: 0, status: "partial",
            usage: {promptTokens: 0, thinkTokens: 0, completionTokens: 0,
                totalTokens: 0, cacheReadInputTokens: 0, cacheCreationInputTokens: 0}
        };
        row.timestamp = Math.max(row.timestamp, Number(record.timestamp || 0));
        if (record.scope === "model_call") {
            const id = record.id || (record.reasonId ? `${runId}:${record.reasonId}` : null);
            if (id && seenCalls.has(id)) continue;
            if (id) seenCalls.add(id);
            row.modelCallCount += 1;
            for (const key of Object.keys(row.usage)) {
                row.usage[key] += number(record.usage?.[key]);
            }
        } else if (record.scope === "run" && !record.parentRunId) {
            row.durationMs = number(record.durationMs);
            row.status = record.status || "run_end";
        }
        runs.set(runId, row);
    }
    return [...runs.values()].map(row => ({
        ...row,
        source: "model_call"
    })).sort((a, b) => b.timestamp - a.timestamp);
}
