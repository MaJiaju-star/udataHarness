import test from "node:test";
import assert from "node:assert/strict";
import {prependHistory, mergeLatestHistory, virtualOffsets, virtualRange, indexAt, createHistoryCache} from "../src/historyPaging.js";

test("prepend is deduplicated and preserves active stream object identity", () => {
    const active = {id: "active", content: "stream"}, old = {id: "history-30"}, older = {id: "history-1"};
    assert.deepEqual(prependHistory([older, old], [old, active]), [older, old, active]);
    assert.equal(prependHistory([older], [old, active]).at(-1), active);
});
test("latest refresh keeps overlapping older pages without duplicating local live messages or creating gaps", () => {
    const old = {id: "history-1"}, overlap = {id: "history-10"}, latest = [{id: "history-10", content: "updated"}, {id: "history-20"}];
    const merged = mergeLatestHistory([old, overlap, {id: "local-live"}], latest);
    assert.deepEqual(merged.messages, [old, ...latest]); assert.equal(merged.messages[0], old); assert.equal(merged.keptOlder, true);
    assert.deepEqual(mergeLatestHistory([old], latest), {messages: latest, keptOlder: false});
});
test("variable height ranges stay bounded for 10,000 loaded messages and reach both ends", () => {
    const messages = Array.from({length: 10000}, (_, n) => ({id: String(n)})), heights = new Map([["0", 1000], ["1", 40]]);
    const offsets = virtualOffsets(messages, heights); assert.equal(offsets[2], 1040);
    const first = virtualRange(offsets, 0, 800), last = virtualRange(offsets, offsets.at(-1) - 800, 800);
    assert.equal(first.start, 0); assert.equal(last.end, 10000); assert.ok(last.end - last.start < 20);
    assert.equal(indexAt(offsets, 1001), 1); assert.deepEqual(virtualRange([0], 0, 800), {start: 0, end: 0});
});
test("stable row anchors survive prepends and measurements above the viewport", () => {
    const old = [{id: "a"}, {id: "b"}], heights = new Map([["a", 100], ["b", 300]]), anchor = {id: "b", offset: 25};
    const prepended = [{id: "older"}, ...old]; heights.set("older", 800); heights.set("a", 200);
    const offsets = virtualOffsets(prepended, heights), top = offsets[prepended.findIndex(row => row.id === anchor.id)] + anchor.offset;
    assert.equal(top, 1025); assert.equal(indexAt(offsets, top), 2);
});
test("pending details deduplicate, survive row unmount and notify remounted subscribers", async () => {
    const cache = createHistoryCache(); const initial = {content: "preview", nextOffset: 7, hasMore: true}; cache.get("key", initial);
    let resolve, calls = 0, notifications = 0;
    const first = cache.load("key", () => { calls++; return new Promise(done => { resolve = done; }); });
    await Promise.resolve(); assert.equal(cache.load("key", () => assert.fail()), first);
    const unsubscribe = cache.subscribe("key", () => notifications++);
    resolve({content: " remainder", nextOffset: 17, hasMore: false}); await first;
    assert.equal(calls, 1); assert.equal(cache.get("key").content, "preview remainder"); assert.equal(notifications, 1); unsubscribe();
});
test("failed reads preserve the preview and cursor, allowing exact retry", async () => {
    const cache = createHistoryCache(), initial = {content: "prefix", nextOffset: 6, hasMore: true}; cache.get("x", initial);
    await cache.load("x", () => Promise.reject(new Error("offline")));
    assert.equal(cache.get("x").nextOffset, 6); assert.equal(cache.get("x").error, "offline");
    await cache.load("x", offset => { assert.equal(offset, 6); return {content: "tail", nextOffset: 10, hasMore: false}; });
    assert.equal(cache.get("x").content, "prefixtail"); assert.equal(cache.get("x").hasMore, false);
});
