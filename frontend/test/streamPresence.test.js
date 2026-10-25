import test from "node:test";
import assert from "node:assert/strict";
import {watchStreamIdle, appendStreamPresence, streamProgressAt, streamPresenceTarget} from "../src/streamPresence.js";

test("remounting a stalled stream inherits its elapsed time instead of restarting the clock", () => {
    const seen = [], delays = [];
    const dispose = watchStreamIdle({elapsed: 7000, publish: value => seen.push(value), schedule: (_, delay) => { delays.push(delay); return 1; }, cancel: () => {}});
    assert.deepEqual(seen, ["等待继续…"]); assert.deepEqual(delays, [13000]); dispose();
    watchStreamIdle({elapsed: 23000, publish: value => seen.push(value), schedule: () => assert.fail("No new deadline needed"), cancel: () => {}});
    assert.equal(seen.at(-1), "响应较慢，仍在等待…");
});

test("only real turn progress resets idle time, not heartbeat, usage or repeated text", () => {
    const previous = {content: "first", thinking: "", streamProgressAt: 100};
    for (const event of [{type: "heartbeat"}, {type: "usage"}, {type: "text", content: ""},
        {type: "text_replay", content: "first"}, {type: "subagent_event", subtype: "heartbeat"}]) {
        assert.equal(streamProgressAt(previous, {...previous}, event, 9000), 100);
    }
    assert.equal(streamProgressAt(previous, {...previous, content: "first second"}, {type: "text"}, 9000), 9000);
    assert.equal(streamProgressAt(previous, previous, {type: "tool_end"}, 10000), 10000);
    assert.equal(streamProgressAt(previous, previous, {type: "thinking", finished: true}, 11000), 11000);
});

test("presence survives thinking-to-text and tool-to-model gaps without pretending execution is stalled", () => {
    const thought = {id: "reason", type: "thinking", content: "reasoning", active: true};
    assert.deepEqual(streamPresenceTarget({activities: [thought]}), {kind: "thinking", id: "reason"});
    assert.equal(streamPresenceTarget({activities: [{...thought, active: false}]}).kind, "fallback");
    const tools = [{callId: "read", running: true}];
    const activities = [{id: "text", type: "text", content: "before"}, {type: "tool", callId: "read"}];
    assert.equal(streamPresenceTarget({activities, tools}).kind, "busy");
    tools[0].running = false;
    assert.equal(streamPresenceTarget({activities, tools}).kind, "fallback");
    activities.push({type: "text", id: "resume", content: "after"});
    assert.deepEqual(streamPresenceTarget({activities, tools}), {kind: "text", id: "resume"});
    for (const terminal of [{finished: true}, {error: "disconnected"}, {hitl: {}}]) {
        assert.equal(streamPresenceTarget({...terminal, activities}).kind, "none");
    }
});

test("idle deadlines distinguish short/long waits and progress cancels stale deadlines", () => {
    let now = 0, serial = 0;
    const jobs = new Map(), seen = [];
    const options = {schedule: (callback, delay) => { const id = ++serial; jobs.set(id, {at: now + delay, callback}); return id; },
        cancel: id => jobs.delete(id), publish: label => seen.push(label)};
    const advance = time => { now = time; for (const [id, job] of jobs) if (job.at <= now) { jobs.delete(id); job.callback(); } };
    let dispose = watchStreamIdle(options);
    advance(4999); assert.deepEqual(seen, []);
    advance(5000); assert.deepEqual(seen, ["等待继续…"]);
    // Real content resumes; the previous 20-second deadline must not fire.
    dispose(); dispose = watchStreamIdle(options);
    advance(10000); assert.equal(seen.length, 2);
    advance(20000); assert.equal(seen.length, 2);
    advance(25000); assert.equal(seen.at(-1), "响应较慢，仍在等待…");
    dispose(); assert.equal(jobs.size, 0);
    dispose = watchStreamIdle(options); dispose(); advance(100000);
    assert.equal(seen.length, 3); // Completion/error/unmount leaves no live timers.
});

test("live marker sits inside final prose or list item, outside code and tables", () => {
    const paragraph = {type: "element", tagName: "p", children: [{type: "text", value: "last text"}]};
    const tree = {type: "root", children: [paragraph, {type: "text", value: "\n"}]};
    appendStreamPresence(tree);
    assert.equal(paragraph.children.at(-1).tagName, "stream-presence");
    assert.equal(paragraph.children.at(-1).properties["data-block"], false);
    const li = {type: "element", tagName: "li", children: [{type: "text", value: "item"}]};
    appendStreamPresence({children: [{type: "element", tagName: "ul", children: [li]}]});
    assert.equal(li.children.at(-1).tagName, "stream-presence");
    for (const tagName of ["pre", "table"]) {
        const block = {type: "element", tagName, children: [{type: "text", value: "unaltered"}]};
        const document = {children: [block]}; appendStreamPresence(document);
        assert.equal(block.children.length, 1);
        assert.equal(document.children.at(-1).properties["data-block"], true);
    }
});
