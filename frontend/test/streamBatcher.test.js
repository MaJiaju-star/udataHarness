import test from "node:test";
import assert from "node:assert/strict";
import {createStreamBatcher, applyMessageBatch} from "../src/streamBatcher.js";
import {accumulateTokenUsage, emptyTokenUsage} from "../src/tokenUsage.js";
import {createConversationTimerStore} from "../src/conversationTimer.js";

function fixture() {
    const timers = new Map(), commits = []; let serial = 0;
    const batcher = createStreamBatcher(events => commits.push(events), {
        schedule(fn) { timers.set(++serial, fn); return serial; }, cancel(id) { timers.delete(id); }
    });
    return {batcher, commits, timers, tick() { const callbacks = [...timers.values()]; timers.clear(); callbacks.forEach(fn => fn()); }};
}
test("a burst is committed once with every original event in source order", () => {
    const f = fixture(), events = Array.from({length: 100}, (_, n) => ({type: "text", content: String(n)}));
    events.forEach(f.batcher.push); assert.equal(f.commits.length, 0); assert.equal(f.timers.size, 1);
    f.tick(); assert.equal(f.commits.length, 1); assert.deepEqual(f.commits[0], events);
    assert.equal(f.commits[0][0], events[0]);
});
test("approval, errors and terminal events flush preceding tool/text events immediately", () => {
    for (const type of ["hitl", "hitl_decision", "run_pending", "error", "run_end", "done"]) {
        const f = fixture(); const events = [{type: "tool_args_start"}, {type: "tool_args_delta", content: "{"}, {type}];
        events.forEach(f.batcher.push); assert.deepEqual(f.commits, [events]); assert.equal(f.timers.size, 0);
    }
});

test("actual batched message path delivers instruction status including the unloaded transition", () => {
    let messages = [{id: "active", role: "assistant"}];
    const batcher = createStreamBatcher(events => {
        messages = applyMessageBatch(messages, "active", events, (message, event) =>
            ({...message, projectInstructions: event}));
    });
    for (const event of [
        {type: "project_instructions", loaded: true, source: "CLAUDE.md"},
        {type: "project_instructions", loaded: true, source: "AGENTS.md"},
        {type: "project_instructions", loaded: false, source: null}
    ]) {
        batcher.push(event); batcher.flush();
        assert.equal(messages[0].projectInstructions, event);
    }
    batcher.close();
});

test("approval outcomes reach message reducer instead of being dropped as invisible metadata", () => {
    for (const action of ["skip", "reject"]) {
        const events = [{type: "hitl_decision", action}];
        const messages = applyMessageBatch([{id: "pending"}], "pending", events,
            (message, event) => ({...message, action: event.action}));
        assert.equal(messages[0].action, action);
    }
});
test("closing on EOF, disconnect or abort flushes exactly once and cancels pending callbacks", () => {
    const f = fixture(); f.batcher.push({type: "thinking", content: "remaining"});
    const staleCallback = [...f.timers.values()][0]; f.batcher.close(); f.batcher.close(); staleCallback();
    assert.equal(f.commits.length, 1); assert.equal(f.timers.size, 0);
    f.batcher.push({type: "text", content: "late"}); assert.equal(f.commits.length, 1);
});
test("background timer throttling cannot grow the queue indefinitely or drop the last batch", () => {
    const f = fixture(); for (let n = 0; n < 600; n++) f.batcher.push({type: "text", content: String(n)});
    assert.equal(f.commits.length, 2); f.batcher.close(); assert.equal(f.commits.flat().length, 600);
});
test("one message batch preserves historical object identities and ignores metadata-only events", () => {
    const old = Array.from({length: 1000}, (_, n) => ({id: String(n), content: "history"})), active = {id: "active", content: ""};
    const messages = [...old, active];
    const reduced = applyMessageBatch(messages, "active", [{type: "text", content: "A"}, {type: "tool_start"}, {type: "text", content: "B"}],
        (message, event) => ({...message, content: message.content + (event.content || ""), last: event.type}));
    assert.equal(reduced.at(-1).content, "AB"); old.forEach((message, n) => assert.equal(reduced[n], message));
    assert.equal(active.content, "");
    assert.equal(applyMessageBatch(messages, "active", [{type: "reason_start"}], () => assert.fail()), messages);
    assert.equal(applyMessageBatch(messages, "missing", {type: "done"}, () => assert.fail()), messages);
});
test("batching retains first-token timestamps, main-only accounting and tool-call deduplication", () => {
    const events = [
        {type: "reason_start", runId: "r", reasonId: "a", timestamp: 1000},
        {type: "text", runId: "r", reasonId: "a", timestamp: 1123, content: "A"},
        {type: "subagent_event", usageScope: "model_call", usage: {promptTokens: 10000}},
        {type: "thinking", runId: "r", reasonId: "a", usageScope: "model_call", usage: {promptTokens: 50, completionTokens: 5, totalTokens: 55, cacheReadInputTokens: 20}},
        {type: "run_end", usageScope: "run", durationMs: 3000}
    ];
    const expected = events.reduce(accumulateTokenUsage, emptyTokenUsage()), f = fixture(); events.forEach(f.batcher.push);
    const result = f.commits.reduce((state, batch) => batch.reduce(accumulateTokenUsage, state), emptyTokenUsage());
    assert.deepEqual(result, expected); assert.equal(result.firstTokenLatencyMs, 123); assert.equal(result.promptTokens, 50);
});
test("elapsed snapshots do not publish ticks; transition subscribers pause, resume and unsubscribe", () => {
    let now = 0; const clock = createConversationTimerStore(() => now), changes = [];
    const unsubscribe = clock.subscribe(key => changes.push(key));
    clock.begin("alice:a"); now = 500; assert.equal(clock.snapshot("alice:a").elapsedMs, 500);
    clock.snapshot("alice:a"); assert.deepEqual(changes, ["alice:a"]);
    clock.event("alice:a", {type: "hitl"}); now = 10000; assert.equal(clock.snapshot("alice:a").elapsedMs, 500);
    clock.resume("alice:a"); now += 250; clock.end("alice:a"); assert.equal(clock.snapshot("alice:a").elapsedMs, 750);
    assert.equal(changes.length, 4); unsubscribe(); clock.begin("bob:b"); assert.equal(changes.length, 4);
});
