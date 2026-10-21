import test from "node:test";
import assert from "node:assert/strict";
import {createConversationTimer, conversationElapsedText} from "../src/conversationTimer.js";

test("approval pauses exclude human wait and repeated continuations accumulate", () => {
    let now = 1000; const timer = createConversationTimer(() => now);
    timer.begin("a"); now = 3500; timer.event("a", {type: "run_pending"}); timer.event("a", {type: "hitl"}); timer.end("a");
    now = 50000; assert.deepEqual(timer.snapshot("a"), {elapsedMs: 2500, status: "paused"});
    timer.resume("a"); now = 51500; timer.event("a", {type: "hitl"}); now = 90000; timer.resume("a");
    now = 91000; timer.event("a", {type: "run_end"}); now = 100000; timer.end("a");
    assert.deepEqual(timer.snapshot("a"), {elapsedMs: 5000, status: "finished"});
});
test("delayed display ticks do not lose elapsed time; child events cannot stop the clock", () => {
    let now = 0; const timer = createConversationTimer(() => now);
    timer.begin("a"); now = 90000;
    timer.event("a", {type: "subagent_event", subtype: "run_end"}); timer.event("a", {type: "hitl", parentRunId: "main"});
    assert.deepEqual(timer.snapshot("a"), {elapsedMs: 90000, status: "running"});
    now = 93000; timer.end("a"); now = 200000;
    assert.equal(timer.snapshot("a").elapsedMs, 93000);
});
test("turns reset at dispatch and sessions keep isolated clocks", () => {
    let now = 0; const timer = createConversationTimer(() => now);
    assert.equal(timer.snapshot("a").elapsedMs, null); now = 10000; timer.begin("a"); now = 11000; timer.event("a", {type: "hitl"});
    timer.begin("b"); now = 13000; timer.event("b", {type: "error"});
    assert.equal(timer.snapshot("a").elapsedMs, 1000); assert.equal(timer.snapshot("b").elapsedMs, 2000);
    timer.begin("b"); now = 13500; assert.equal(timer.snapshot("b").elapsedMs, 500);
    assert.equal(timer.resume("unknown"), false); assert.equal(timer.snapshot("unknown").elapsedMs, null);
});
test("elapsed text handles unknown and longer turns", () => {
    assert.equal(conversationElapsedText(null), "—"); assert.equal(conversationElapsedText(NaN), "—");
    assert.equal(conversationElapsedText(12600), "12.6 s"); assert.equal(conversationElapsedText(83000), "1 分 23 秒");
    assert.equal(conversationElapsedText(3723000), "1 时 2 分 3 秒");
});
