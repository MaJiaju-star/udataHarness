import assert from "node:assert/strict";
import test from "node:test";
import {accumulateTokenUsage, emptyTokenUsage, summarizeAudit, cacheHitRate, firstTokenLatencyText} from "../src/tokenUsage.js";

const call = {
    type: "text_replay", usageScope: "model_call", runId: "main", reasonId: "reason-1",
    usage: {promptTokens: 1000, thinkTokens: 20, completionTokens: 200, totalTokens: 1200,
        cacheReadInputTokens: 800, cacheCreationInputTokens: 100}
};

const start = {type: "reason_start", runId: "main", reasonId: "r1", timestamp: 1000};

test("first-token latency uses first main streaming content, ignoring child/empty events", () => {
    let state = accumulateTokenUsage(emptyTokenUsage(), start);
    for (const event of [
        {type: "thinking", content: "", timestamp: 1010},
        {type: "subagent_event", subtype: "text", content: "child", timestamp: 1020},
        {type: "text", parentRunId: "main", content: "child", timestamp: 1030},
        {type: "text", usageScope: "compaction", content: "summary", timestamp: 1040},
        {type: "text_replay", content: "replayed", timestamp: 1050}
    ]) state = accumulateTokenUsage(state, {runId: "main", reasonId: "r1", ...event});
    assert.equal(state.firstTokenLatencyMs, null);
    state = accumulateTokenUsage(state, {type: "thinking", content: "Let me check", runId: "main", reasonId: "r1", timestamp: 1250});
    assert.equal(state.firstTokenLatencyMs, 250);
    state = accumulateTokenUsage(state, {...start, reasonId: "r2", timestamp: 5000});
    state = accumulateTokenUsage(state, {type: "text", content: "answer", runId: "main", reasonId: "r2", timestamp: 7000});
    assert.equal(state.firstTokenLatencyMs, 250);
});

test("tool-argument-only first responses count, but names/start markers do not", () => {
    let state = accumulateTokenUsage(emptyTokenUsage(), start);
    state = accumulateTokenUsage(state, {type: "tool_args_start", runId: "main", content: "read", timestamp: 1200});
    assert.equal(state.firstTokenLatencyMs, null);
    state = accumulateTokenUsage(state, {type: "tool_args_delta", runId: "main", content: '{"file_path":', timestamp: 1550});
    assert.equal(state.firstTokenLatencyMs, 550);
});

test("missing streaming evidence remains unknown across finish and later model calls", () => {
    let state = accumulateTokenUsage(emptyTokenUsage(), start);
    state = accumulateTokenUsage(state, {...call, reasonId: "r1", type: "thinking", finished: true, content: "whole reply", timestamp: 2000});
    assert.equal(state.firstTokenLatencyMs, null);
    state = accumulateTokenUsage(state, {...start, reasonId: "r2", timestamp: 3000});
    state = accumulateTokenUsage(state, {type: "tool_args_delta", content: "{}", runId: "main", timestamp: 3500});
    assert.equal(state.firstTokenLatencyMs, null);
    assert.equal(accumulateTokenUsage(emptyTokenUsage(), {type: "text", content: "x", timestamp: 10}).firstTokenLatencyMs, null);
});

test("latency display handles zero, seconds, resets, and invalid timestamps", () => {
    const state = accumulateTokenUsage(emptyTokenUsage(), start);
    assert.equal(accumulateTokenUsage(state, {type: "text", runId: "main", content: "x", timestamp: 999}).firstTokenLatencyMs, null);
    const zero = accumulateTokenUsage(state, {type: "text", runId: "main", content: "x", timestamp: 1000});
    assert.equal(firstTokenLatencyText(zero.firstTokenLatencyMs), "0 ms");
    assert.equal(firstTokenLatencyText(1250), "1.25 s");
    assert.equal(firstTokenLatencyText(emptyTokenUsage().firstTokenLatencyMs), "—");
});

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

test("first-token waiting display follows main request evidence and terminal events", () => {
    assert.equal(firstTokenLatencyText(null, true), "等待中…");
    assert.equal(firstTokenLatencyText(0, true), "0 ms");
    assert.equal(firstTokenLatencyText(null, false), "—");
    for (const type of ["error", "done", "run_end", "run_pending"]) {
        const pending = accumulateTokenUsage(emptyTokenUsage(), start);
        const ended = accumulateTokenUsage(pending, {type, runId: "main"});
        assert.equal(ended.firstTokenPending, false);
        assert.equal(ended.firstTokenLatencyMs, null);
        const later = accumulateTokenUsage(ended, {type: "text", runId: "main", content: "late", timestamp: 2000});
        assert.equal(later.firstTokenLatencyMs, null);
    }
});

test("plain text and thinking and tool streams without usage capture latency once and preserve cache accounting", () => {
    for (const type of ["text", "thinking", "tool_args_delta"]) {
        let state = accumulateTokenUsage(emptyTokenUsage(), start);
        assert.equal(state.firstTokenPending, true);
        state = accumulateTokenUsage(state, {type, content: "first", runId: "main", reasonId: "r1", timestamp: 1850});
        assert.equal(state.firstTokenLatencyMs, 850);
        assert.equal(state.firstTokenPending, false);
        state = accumulateTokenUsage(state, {...call, reasonId: "r1"});
        state = accumulateTokenUsage(state, {...start, reasonId: "r2", timestamp: 4000});
        state = accumulateTokenUsage(state, {type: "text", content: "later", runId: "main", reasonId: "r2", timestamp: 5000});
        assert.equal(state.firstTokenLatencyMs, 850);
        assert.equal(cacheHitRate(state), 80);
        assert.equal(state.totalTokens, 1200);
        assert.equal(emptyTokenUsage().firstTokenLatencyMs, null);
    }
});
