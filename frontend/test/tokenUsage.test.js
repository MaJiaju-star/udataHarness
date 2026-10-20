import assert from "node:assert/strict";
import test from "node:test";
import {accumulateTokenUsage, emptyTokenUsage, summarizeAudit, cacheHitRate} from "../src/tokenUsage.js";

const call = {
    type: "text_replay", usageScope: "model_call", runId: "main", reasonId: "reason-1",
    usage: {promptTokens: 1000, thinkTokens: 20, completionTokens: 200, totalTokens: 1200,
        cacheReadInputTokens: 800, cacheCreationInputTokens: 100}
};

test("main calls accumulate once; child and compaction calls never count", () => {
    const first = accumulateTokenUsage(emptyTokenUsage(), call);
    assert.equal(accumulateTokenUsage(first, call).totalTokens, 1200);
    for (const excluded of [
        {...call, type: "subagent_event", runId: "child"},
        {...call, parentRunId: "main", runId: "child"},
        {...call, usageScope: "compaction", runId: "summary"},
        {...call, type: "subagent_event", usageScope: "run", runId: "child"}
    ]) assert.deepEqual(accumulateTokenUsage(first, excluded), first);
    const next = accumulateTokenUsage(first, {...call, reasonId: "reason-2"});
    assert.equal(next.totalTokens, 2400);
    assert.equal(next.modelCallCount, 2);
    assert.equal(next.thinkTokens, 40); // Reasoning is not added to total again.
});

test("run completion only updates duration and main-output throughput, including legacy events", () => {
    const first = accumulateTokenUsage(emptyTokenUsage(), call);
    for (const usage of [undefined, {promptTokens: 9000, totalTokens: 9999, completionTokens: 999}]) {
        const final = accumulateTokenUsage(first, {
            type: "run_end", usageScope: "run", runId: "main", usage,
            durationMs: 2000, tokensPerSecond: 999
        });
        assert.equal(final.totalTokens, 1200);
        assert.equal(final.cacheReadInputTokens, 800);
        assert.equal(final.thinkTokens, 20);
        assert.equal(final.tokensPerSecond, 100);
        assert.equal(final.durationMs, 2000);
    }
    const pending = accumulateTokenUsage(first, {usageScope: "run", type: "run_pending", durationMs: 500});
    assert.equal(accumulateTokenUsage(pending, call).totalTokens, 1200);
});

test("cache rate uses SDK inclusive input; caches and thinking are not added again", () => {
    assert.equal(cacheHitRate(call.usage), 80);
    assert.equal(cacheHitRate({promptTokens: 1000, cacheReadInputTokens: 0}), 0);
    assert.equal(cacheHitRate({promptTokens: 0, cacheReadInputTokens: 0}), null);
});

test("audit sums main call details, ignoring mixed legacy totals, children, and compaction", () => {
    const main = {id: "a:r1", runId: "a", reasonId: "r1", sessionId: "s",
        scope: "model_call", timestamp: 1, usage: {promptTokens: 1000, totalTokens: 1200}};
    const runs = summarizeAudit([
        main, main,
        {runId: "child", parentRunId: "a", scope: "model_call", timestamp: 2,
            usage: {totalTokens: 900}},
        {scope: "compaction", runId: "a", timestamp: 3, usage: {totalTokens: 999}},
        {runId: "a", sessionId: "s", scope: "run", status: "run_end", timestamp: 4,
            durationMs: 2000, usage: {promptTokens: 2000, totalTokens: 3000}},
        {runId: "b", sessionId: "s", scope: "model_call", timestamp: 5,
            usage: {promptTokens: 7, totalTokens: 8}},
        {runId: "b", sessionId: "s", scope: "run", status: "error", timestamp: 6}
    ]);
    assert.equal(runs.length, 2);
    assert.equal(runs[0].usage.totalTokens, 8);
    assert.equal(runs[0].status, "error");
    assert.equal(runs[1].usage.totalTokens, 1200);
    assert.equal(runs[1].modelCallCount, 1);
    assert.equal(runs[1].durationMs, 2000);
    assert.equal(runs[1].status, "run_end");
    assert.equal(runs[1].source, "model_call");
});
