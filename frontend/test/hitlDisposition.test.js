import test from "node:test";
import assert from "node:assert/strict";
import {applyHitlDisposition} from "../src/hitlDisposition.js";
import {toolStatus} from "../src/toolSummary.js";
import {summarizeToolGroup} from "../src/toolGroups.js";

test("approval outcomes close unexecuted cards without relabelling completed operations", () => {
    for (const action of ["skip", "reject"]) {
        const tools = [{callId: "pending", generating: true}, {callId: "done", executionStarted: true, output: "ok"}];
        const result = applyHitlDisposition(tools, action);
        assert.equal(result[0].generating, false);
        assert.equal(result[0].running, false);
        assert.equal(result[0].disposition, action);
        assert.equal(result[1], tools[1]);
        assert.match(toolStatus(result[0]).label, /未执行/);
        assert.equal(summarizeToolGroup(result).current, "");
        assert.equal(summarizeToolGroup(result).counts.waiting, 0);
        assert.equal(summarizeToolGroup(result).counts.success, 1);
    }
});

test("later decisions cannot relabel earlier rejected/skipped calls", () => {
    const tools = [{disposition: "reject", executionStarted: false}, {argsText: "{}"}];
    assert.equal(applyHitlDisposition(tools, "skip")[0], tools[0]);
    assert.equal(applyHitlDisposition(tools, "approve"), tools);
});
