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
    seenCalls: {}
});

const number = value => Number(value || 0);

export function accumulateTokenUsage(current, event) {
    if (!event?.usage || (event.type === "subagent_event" && event.usageScope === "run")) return current;
    const usage = event.usage;
    if (event.usageScope === "run") {
        return {
            ...current,
            promptTokens: number(usage.promptTokens),
            thinkTokens: number(usage.thinkTokens),
            completionTokens: number(usage.completionTokens),
            totalTokens: number(usage.totalTokens),
            cacheCreationInputTokens: number(usage.cacheCreationInputTokens),
            cacheReadInputTokens: number(usage.cacheReadInputTokens),
            durationMs: number(event.durationMs),
            tokensPerSecond: number(event.tokensPerSecond)
        };
    }
    if (event.usageScope !== "model_call") return current;
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
    for (const record of records || []) {
        const runId = record.parentRunId || record.runId;
        if (!runId) continue;
        const row = runs.get(runId) || {
            runId, sessionId: record.sessionId, selectedModel: record.selectedModel,
            timestamp: 0, modelCallCount: 0, status: "partial",
            usage: {promptTokens: 0, thinkTokens: 0, completionTokens: 0,
                totalTokens: 0, cacheReadInputTokens: 0, cacheCreationInputTokens: 0}
        };
        row.timestamp = Math.max(row.timestamp, Number(record.timestamp || 0));
        if (record.scope === "model_call") {
            row.modelCallCount += 1;
            for (const key of Object.keys(row.usage)) {
                row.usage[key] += number(record.usage?.[key]);
            }
        } else if (record.scope === "run" && !record.parentRunId) {
            row.finalUsage = record.usage;
            row.durationMs = number(record.durationMs);
            row.status = record.status || "run_end";
        }
        runs.set(runId, row);
    }
    return [...runs.values()].map(row => ({
        ...row,
        usage: row.finalUsage ? {...row.usage, ...row.finalUsage} : row.usage,
        source: row.finalUsage ? "run" : "model_call"
    })).sort((a, b) => b.timestamp - a.timestamp);
}
