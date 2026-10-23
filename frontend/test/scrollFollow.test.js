import test from "node:test";
import assert from "node:assert/strict";
import {anchorShift, createScrollFollow} from "../src/scrollFollow.js";

test("queued stream follow respects a subsequent manual scroll, even near the bottom", () => {
    const follow = createScrollFollow();
    let scrolls = 0;
    const queuedFrame = () => follow.follow(() => scrolls++);
    follow.pause();
    queuedFrame(); queuedFrame();
    assert.equal(scrolls, 0);
    assert.equal(follow.isFollowing(), false);
    follow.resume(); queuedFrame();
    assert.equal(scrolls, 1);
});

test("streaming below the reader never restores a stale absolute scroll position", () => {
    const anchor = {id: "reading", start: 300};
    const messages = [{id: "old"}, {id: "reading"}, {id: "stream", content: "growing"}];
    assert.equal(anchorShift(anchor, messages, [0, 300, 600, 900]), 0);
    assert.equal(anchorShift(anchor, messages, [0, 300, 600, 3000]), 0);
    // A user scroll that happened before this commit must survive unchanged.
    assert.equal(425 + anchorShift(anchor, messages, [0, 300, 600, 3000]), 425);
});

test("prepend and height corrections compensate only the change above the reader", () => {
    const anchor = {id: "reading", start: 300};
    assert.equal(anchorShift(anchor, [{id: "prepend"}, {id: "old"}, {id: "reading"}], [0, 800, 1100, 1400]), 800);
    assert.equal(anchorShift(anchor, [{id: "old"}, {id: "reading"}], [0, 200, 500]), -100);
    assert.equal(anchorShift(anchor, [{id: "other"}], [0, 220]), 0);
    assert.equal(anchorShift(null, [], [0]), 0);
});
