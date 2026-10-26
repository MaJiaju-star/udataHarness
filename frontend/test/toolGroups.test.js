import assert from "node:assert/strict";
import test from "node:test";
import {groupToolActivities, summarizeToolGroup} from "../src/toolGroups.js";

const tool = (callId, name = "read", rest = {}) => ({callId, name, args: {file_path: `${callId}.js`}, output: "done", ...rest});
const event = callId => ({type: "tool", callId});

test("groups adjacent calls without crossing text, thinking, todo, subagent, or runtime events", () => {
    for (const type of ["text", "thinking", "todo", "subagent", "runtime_chunk"]) {
        const divider = {type, id: type, content: "narrative"};
        const result = groupToolActivities([event("a"), event("b"), divider, event("c")], [tool("a"), tool("b"), tool("c")]);
        assert.deepEqual(result.map(item => item.type), ["tool_group", type, "tool_group"]);
        assert.deepEqual(result[0].tools.map(item => item.callId), ["a", "b"]);
        assert.equal(result[1], divider);
    }
});

test("streaming growth and result updates preserve group identity and source order", () => {
    const before = groupToolActivities([event("a")], [tool("a", "read", {generating: true})]);
    const calls = [tool("b"), tool("a")];
    const after = groupToolActivities([event("a"), event("b")], calls);
    assert.equal(before[0].id, after[0].id);
    assert.deepEqual(after[0].tools.map(item => item.callId), ["a", "b"]);
    assert.equal(calls[0].callId, "b");
});

test("charts and todo tools remain independent, including messages with no timeline", () => {
    const tools = [tool("a"), tool("chart", "render_chart"), tool("b"), tool("todo", "todowrite"), tool("c")];
    const result = groupToolActivities(tools.map(item => event(item.callId)), tools);
    assert.deepEqual(result.map(item => item.type), ["tool_group", "tool", "tool_group", "tool", "tool_group"]);
    assert.deepEqual(groupToolActivities([]), []);
});

test("repeated call events do not duplicate tools and missing calls do not reorder later events", () => {
    const result = groupToolActivities([event("a"), event("a"), event("missing"), event("b")], [tool("a"), tool("b")]);
    assert.equal(result[0].tools.length, 1);
    assert.equal(result[1].callId, "missing");
    assert.equal(result[2].tools[0].callId, "b");
});

test("summary counts actual states and exposes failures independently of active calls", () => {
    const tools = [tool("ok"), tool("fail", "bash", {error: true, args: {command: "npm test"}}),
        tool("run", "grep", {running: true}), tool("gen", "read", {generating: true}),
        tool("wait", "read", {output: "", argsText: "{}"})];
    const summary = summarizeToolGroup(tools);
    assert.deepEqual(summary.counts, {success: 1, error: 1, running: 1, generating: 1, waiting: 1, rejected: 0, skipped: 0});
    assert.match(summary.failures, /bash npm test/);
    assert.match(summary.current, /另有 1 项/);
    assert.match(summary.categories, /读取 3/);
    assert.equal(summarizeToolGroup([tool("a"), tool("b")]).current, "");
});


test("transport-to-execution call ID changes retain the activity's group identity", () => {
    const before = groupToolActivities([{id: "stable-activity", type: "tool", callId: "stream-1"}], [tool("stream-1")]);
    const after = groupToolActivities([{id: "stable-activity", type: "tool", callId: "execution-uuid"}], [tool("execution-uuid")]);
    assert.equal(before[0].id, after[0].id);
});
