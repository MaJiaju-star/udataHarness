import test from "node:test";
import assert from "node:assert/strict";
import {anchoredVirtualTop, prependHistory, mergeLatestHistory, virtualOffsets, virtualRange, virtualRanges, indexAt, createHistoryCache} from "../src/historyPaging.js";

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

test("the latest exchange stays mounted without rendering the gap through older history", () => {
    const offsets = virtualOffsets(Array.from({length: 10000}, (_, i) => ({id: String(i)})), new Map());
    const ranges = virtualRanges(offsets, 0, 800);
    assert.equal(ranges.length, 2);
    assert.deepEqual(ranges[1], {start: 9997, end: 10000});
    assert.ok(ranges.reduce((sum, range) => sum + range.end - range.start, 0) < 20);
    const bottom = virtualRanges(offsets, offsets.at(-1) - 800, 800);
    assert.equal(bottom.length, 1); assert.equal(bottom[0].end, 10000);
    assert.deepEqual(virtualRanges([0], 0, 800), []);
    assert.deepEqual(virtualRanges([0, 500], 0, 800), [{start: 0, end: 1}]);
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

test("width reflow keeps the reading row in the rendered window before DOM replacement", () => {
    const messages = [{id: "long"}, {id: "reading"}, {id: "stream"}];
    const anchor = {id: "reading", start: 4000}, top = 4100;
    const offsets = [0, 12000, 16000, 18000];
    // The old viewport selects only the preceding long row: the reader would unmount.
    assert.equal(virtualRange(offsets, top, 400).end, 1);
    const next = anchoredVirtualTop(anchor, messages, offsets, top);
    assert.equal(next, 12100);
    const range = virtualRange(offsets, next, 400);
    assert.ok(range.start <= 1 && range.end > 1);
    // Later output below the reader does not move the reading position.
    assert.equal(anchoredVirtualTop({id: "reading", start: 12000}, messages, [0, 12000, 16000, 22000], next), next);
});

test("shrinking or collapsing the anchored row cannot leave the viewport inside its old empty extent", () => {
    const messages = [{id: "before"}, {id: "reading"}, {id: "after"}];
    const next = anchoredVirtualTop({id: "reading", start: 6000}, messages, [0, 2000, 2100, 2400], 9500);
    assert.equal(next, 2099);
    assert.equal(indexAt([0, 2000, 2100, 2400], next), 1);
    assert.equal(anchoredVirtualTop(null, messages, [0, 2000, 2100, 2400], 50), 50);
    assert.equal(anchoredVirtualTop({id: "removed", start: 0}, messages, [0, 2000, 2100, 2400], 50), 50);
});

test("pending native scrolling past a stale anchor is never clamped back to that message", () => {
    const messages = [{id: "completed"}, {id: "latest"}];
    const offsets = [0, 1000, 8000], anchor = {id: "completed", start: 0, height: 1000};
    assert.equal(anchoredVirtualTop(anchor, messages, offsets, 7400), 7400);
    assert.equal(anchoredVirtualTop({id: "latest", start: 1000, height: 7000}, messages, offsets, 0), 0);
    // Reflow must not apply the old row's offset correction after the reader has left it.
    assert.equal(anchoredVirtualTop(anchor, messages, [0, 500, 7500], 2000), 2000);
});

test("actual collapse still clamps an inset while the reader remains in the anchored row", () => {
    const messages = [{id: "before"}, {id: "reading"}, {id: "after"}];
    const anchor = {id: "reading", start: 6000, height: 5000};
    assert.equal(anchoredVirtualTop(anchor, messages, [0, 2000, 2100, 2400], 9500), 2099);
    assert.equal(anchoredVirtualTop(anchor, messages, [0, 2000, 8000, 9000], 9500), 5500);
});
test("failed reads preserve the preview and cursor, allowing exact retry", async () => {
    const cache = createHistoryCache(), initial = {content: "prefix", nextOffset: 6, hasMore: true}; cache.get("x", initial);
    await cache.load("x", () => Promise.reject(new Error("offline")));
    assert.equal(cache.get("x").nextOffset, 6); assert.equal(cache.get("x").error, "offline");
    await cache.load("x", offset => { assert.equal(offset, 6); return {content: "tail", nextOffset: 10, hasMore: false}; });
    assert.equal(cache.get("x").content, "prefixtail"); assert.equal(cache.get("x").hasMore, false);
});
