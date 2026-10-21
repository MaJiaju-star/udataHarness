import assert from "node:assert/strict";
import test from "node:test";
import {findToolStreamIndex} from "../src/toolStream.js";

test("Claude nameless argument deltas reuse the named placeholder even if stream IDs differ", () => {
    const tools = [{callId: "stream-msg:0:idx:2", providerCallId: "toolu-a", name: "read", streamId: "msg:0:idx:2"}];
    assert.equal(findToolStreamIndex(tools, {callId: "toolu-a", streamId: "msg:0:2"}), 0);
    assert.equal(findToolStreamIndex(tools, {callId: "toolu-a", streamId: "msg:0:idx:2", toolName: "read"}), 0);
    assert.equal(tools.length, 1);
});

test("parallel same-name calls are matched by provider identity rather than name or index collision", () => {
    const tools = [
        {callId: "a", providerCallId: "toolu-a", streamId: "msg:0:0", name: "read"},
        {callId: "b", providerCallId: "toolu-b", streamId: "msg:0:1", name: "read"}
    ];
    assert.equal(findToolStreamIndex(tools, {callId: "toolu-b", streamId: "msg:0:0"}), 1);
    assert.equal(findToolStreamIndex(tools, {callId: "a"}), 0);
    assert.equal(findToolStreamIndex(tools, {streamId: "msg:0:1"}), 1);
    assert.equal(findToolStreamIndex(tools, {}), -1);
    assert.equal(findToolStreamIndex(tools, {streamId: "unknown", callId: "unknown"}), -1);
    assert.equal(findToolStreamIndex(tools, {streamId: "msg:0:0", callId: "toolu-new"}), -1);
});
