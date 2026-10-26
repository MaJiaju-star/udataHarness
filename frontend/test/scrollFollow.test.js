import test from "node:test";
import assert from "node:assert/strict";
import {anchorShift, consumesNestedScroll, createScrollFollow} from "../src/scrollFollow.js";

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

test("manual downward scrolling resumes only when it reaches the actual bottom", () => {
    const follow = createScrollFollow(), element = {scrollTop: 400, scrollHeight: 2000, clientHeight: 500};
    follow.input(-1, element);
    element.scrollTop = 200; follow.scrolled(element);
    assert.equal(follow.isFollowing(), false);
    follow.input(1, element);
    element.scrollTop = 1000; follow.scrolled(element);
    assert.equal(follow.isFollowing(), false);
    element.scrollTop = 1499.5; follow.scrolled(element);
    assert.equal(follow.isFollowing(), true);
    let calls = 0; follow.follow(() => calls++); assert.equal(calls, 1);
});

test("content shrinking to the reader and queued scroll events cannot resume an upward pause", () => {
    const follow = createScrollFollow(), element = {scrollTop: 500, scrollHeight: 2000, clientHeight: 500};
    follow.input(-1, element);
    element.scrollHeight = 1000; follow.scrolled(element);
    assert.equal(follow.isFollowing(), false);
    follow.input(1, element); assert.equal(follow.isFollowing(), true);
    follow.pause(); follow.scrolled(element); assert.equal(follow.isFollowing(), false);
});

test("scrollbar dragging can return to bottom but an upward gesture cancels the return", () => {
    const follow = createScrollFollow(), element = {scrollTop: 500, scrollHeight: 2000, clientHeight: 500};
    follow.input(0, element);
    element.scrollTop = 1500; follow.scrolled(element); assert.equal(follow.isFollowing(), true);
    follow.input(1, element);
    follow.input(-1, element);
    element.scrollTop = 1501; follow.scrolled(element); assert.equal(follow.isFollowing(), false);
});

test("inner thinking scroll stays local until its directional boundary is reached", () => {
    const original = globalThis.getComputedStyle;
    globalThis.getComputedStyle = node => ({overflowY: node.overflowY || "visible"});
    try {
        const container = {}, pane = {parentElement: container, scrollHeight: 800, clientHeight: 180, scrollTop: 100, overflowY: "auto"};
        const target = {parentElement: pane, scrollHeight: 0, clientHeight: 0};
        assert.equal(consumesNestedScroll(target, container, -1), true);
        assert.equal(consumesNestedScroll(target, container, 1), true);
        pane.scrollTop = 0;
        assert.equal(consumesNestedScroll(target, container, -1), false);
        pane.scrollTop = 620;
        assert.equal(consumesNestedScroll(target, container, 1), false);
        pane.overflowY = "hidden"; pane.scrollTop = 100;
        assert.equal(consumesNestedScroll(target, container, -1), false);
    } finally {
        if (original) globalThis.getComputedStyle = original;
        else delete globalThis.getComputedStyle;
    }
});
