import assert from "node:assert/strict";
import test from "node:test";
import {accumulateTokenUsage, emptyTokenUsage, summarizeAudit} from "../src/tokenUsage.js";

test("final run metrics replace incomplete model-call totals", () => {
    const first = accumulateTokenUsage(emptyTokenUsage(), {
        type: "text_replay", usageScope: "model_call", runId: "run-1", reasonId: "reason-1",
        usage: {promptTokens: 100, thinkTokens: 10, completionTokens: 20,
            totalTokens: 120, cacheReadInputTokens: 50}
    });
    const final = accumulateTokenUsage(first, {
        type: "run_end", usageScope: "run", runId: "run-1",
        usage: {promptTokens: 250, thinkTokens: 25, completionTokens: 40, totalTokens: 290,
            cacheReadInputTokens: 150}, durationMs: 2000, tokensPerSecond: 20
    });
    assert.equal(final.totalTokens, 290);
    assert.equal(final.cacheReadInputTokens, 150);
    assert.equal(final.thinkTokens, 25);
    assert.equal(final.tokensPerSecond, 20);
});

test("replayed model calls are deduplicated and child calls count once", () => {
    const call = {type: "text_replay", usageScope: "model_call", runId: "run-1",
        reasonId: "reason-1", usage: {promptTokens: 10, totalTokens: 12}};
    const once = accumulateTokenUsage(emptyTokenUsage(), call);
    assert.equal(accumulateTokenUsage(once, call).totalTokens, 12);
    const child = {...call, type: "subagent_event", runId: "child"};
    const withChild = accumulateTokenUsage(once, child);
    assert.equal(withChild.totalTokens, 24);
    assert.equal(accumulateTokenUsage(withChild, child).totalTokens, 24);
    assert.equal(accumulateTokenUsage(withChild,
        {...child, usageScope: "run", usage: {totalTokens: 12}}).totalTokens, 24);
});

test("audit summaries prefer final metrics and keep interrupted calls", () => {
    const runs = summarizeAudit([
        {runId: "a", sessionId: "s", scope: "model_call", timestamp: 1,
            usage: {promptTokens: 10, totalTokens: 12}},
        {runId: "a", sessionId: "s", scope: "run", status: "run_end", timestamp: 2,
            usage: {promptTokens: 20, totalTokens: 25}},
        {runId: "b", sessionId: "s", scope: "model_call", timestamp: 3,
            usage: {promptTokens: 7, totalTokens: 8}}
    ]);
    assert.equal(runs[0].source, "model_call");
    assert.equal(runs[0].usage.totalTokens, 8);
    assert.equal(runs[1].source, "run");
    assert.equal(runs[1].usage.totalTokens, 25);
});
