import test from "node:test";
import assert from "node:assert/strict";
import {createSmoothScroll} from "../src/smoothScroll.js";

function fixture() {
    let time = 0, seq = 0, allowed = true, reduced = false;
    const frames = new Map(), element = {scrollTop: 0, scrollHeight: 1000, clientHeight: 400};
    const scroller = createSmoothScroll({getElement: () => element, canFollow: () => allowed, instant: () => reduced,
        now: () => time, requestFrame: fn => { frames.set(++seq, fn); return seq; }, cancelFrame: id => frames.delete(id)});
    return {element, frames, scroller, pause: () => { allowed = false; }, reduce: () => { reduced = true; },
        step(ms = 16) { time += ms; const batch = [...frames.values()]; frames.clear(); batch.forEach(fn => fn()); }};
}

test("outer conversation and inner thinking containers approach the moving bottom instead of jumping", () => {
    for (const height of [400, 250]) {
        const f = fixture(); f.element.clientHeight = height;
        f.scroller.follow(); f.step();
        assert.ok(f.element.scrollTop > 0 && f.element.scrollTop < 1000 - height);
        f.element.scrollHeight += 200;
        const before = f.element.scrollTop;
        f.scroller.follow(); f.scroller.follow(); assert.equal(f.frames.size, 1);
        f.step(); assert.ok(f.element.scrollTop > before && f.element.scrollTop < 1200 - height);
        for (let i = 0; i < 40; i++) f.step();
        assert.equal(f.element.scrollTop, 1200 - height); assert.equal(f.frames.size, 0);
    }
});

test("a user pause wins over an already queued frame and stop cancels it immediately", () => {
    const f = fixture(); f.scroller.follow(); f.step(); const top = f.element.scrollTop;
    f.pause(); f.step(); assert.equal(f.element.scrollTop, top); assert.equal(f.frames.size, 0);
    f.scroller.follow(); assert.equal(f.frames.size, 0);
    const g = fixture(); g.scroller.follow(); g.scroller.stop(); assert.equal(g.frames.size, 0);
});

test("initial loading, reduced motion and hidden pages can synchronize without animation", () => {
    const f = fixture(); f.scroller.snap(); assert.equal(f.element.scrollTop, 600); assert.equal(f.frames.size, 0);
    f.element.scrollHeight = 1200; f.reduce(); f.scroller.follow();
    assert.equal(f.element.scrollTop, 800); assert.equal(f.frames.size, 0);
    f.pause(); f.element.scrollHeight = 1500; f.scroller.snap(); assert.equal(f.element.scrollTop, 800);
});

test("time-based easing matches 60Hz and 120Hz displays, and handles shrinking content", () => {
    const a = fixture(), b = fixture(); a.scroller.follow(); b.scroller.follow();
    for (let n = 0; n < 10; n++) a.step(16);
    for (let n = 0; n < 20; n++) b.step(8);
    assert.ok(Math.abs(a.element.scrollTop - b.element.scrollTop) < .01);
    a.element.scrollHeight = 500;
    for (let n = 0; n < 40; n++) a.step();
    assert.equal(a.element.scrollTop, 100); assert.equal(a.frames.size, 0);
});
