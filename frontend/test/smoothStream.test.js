import test from "node:test";
import assert from "node:assert/strict";
import {createSmoothStream, revealBoundary, structuredTail} from "../src/smoothStream.js";

function fixture() {
    let time = 0, seq = 0;
    const frames = new Map(), output = [];
    const buffer = createSmoothStream({publish: value => output.push({time, value}), now: () => time,
        requestFrame: fn => { const id = ++seq; frames.set(id, fn); return id; }, cancelFrame: id => frames.delete(id)});
    return {buffer, output, frames, advance(ms) {
        const end = time + ms;
        while (time < end) { time = Math.min(time + 16, end); const batch = [...frames.values()]; frames.clear(); batch.forEach(fn => fn()); }
    }};
}

test("a burst becomes multiple progressive paints, then the idle RAF loop stops", () => {
    const f = fixture(), text = "流水般连续输出".repeat(30);
    f.buffer.update(text); assert.equal(f.output.length, 0);
    f.advance(64);
    assert.ok(f.output.length >= 2);
    assert.ok(f.buffer.snapshot().visible.length > 0 && f.buffer.snapshot().visible.length < text.length);
    f.advance(128);
    assert.equal(f.buffer.snapshot().visible, text);
    assert.equal(f.frames.size, 0);
});

test("continuous chunks adapt without starving the old tail or accumulating delay", () => {
    const f = fixture(); let text = "";
    for (let n = 0; n < 80; n++) {
        text += `第${n}段中文；`;
        f.buffer.update(text); f.advance(40);
        assert.ok(f.buffer.snapshot().pending <= 6);
        if (n > 5) assert.ok(f.buffer.snapshot().visible.length >= text.length - 40);
    }
    f.advance(192);
    assert.equal(f.buffer.snapshot().visible, text);
    assert.equal(f.frames.size, 0);
    for (let n = 1; n < f.output.length; n++) assert.ok(f.output[n].value.startsWith(f.output[n - 1].value));
});

test("large bursts catch up in under 200ms instead of typing for seconds", () => {
    const f = fixture(), text = "中文内容".repeat(10000);
    f.buffer.update(text); f.advance(192);
    assert.equal(f.buffer.snapshot().visible, text);
    assert.ok(f.output.length <= 6);
});

test("Unicode graphemes are never cut into partial emoji or combining characters", () => {
    const f = fixture(), text = "A👨‍👩‍👦e\u0301🇨🇳B";
    f.buffer.update(text); f.advance(192);
    const valid = new Set(["A", "A👨‍👩‍👦", "A👨‍👩‍👦e\u0301", "A👨‍👩‍👦e\u0301🇨🇳", text]);
    for (const {value} of f.output) assert.ok(valid.has(value), JSON.stringify(value));
    assert.equal(revealBoundary("A😀B", 2), 1);
});

test("scroll pause, disabled motion, completion flush and source replacement cannot lose received text", () => {
    const f = fixture(); f.buffer.update("正在缓冲的完整内容"); f.advance(16);
    f.buffer.update("正在缓冲的完整内容", false);
    assert.equal(f.buffer.snapshot().visible, "正在缓冲的完整内容"); assert.equal(f.frames.size, 0);
    f.buffer.update("正在缓冲的完整内容和后续内容"); f.buffer.flush();
    assert.equal(f.buffer.snapshot().visible, "正在缓冲的完整内容和后续内容");
    f.buffer.update("服务端替换正文"); assert.equal(f.buffer.snapshot().visible, "服务端替换正文");
    assert.equal(f.frames.size, 0);
});

test("code and tables bypass typing, and prose after a closed fence can flow again", () => {
    const f = fixture();
    for (const text of ["说明\n```java\nString x", "说明\n~~~\n代码", "```java\n代码\n```\n", "A | B\n--- | ---\n一 | 二", "| 表头 | 值 |\n| --- | --- |\n| 一 | 二 |"] ) {
        assert.equal(structuredTail(text), true);
        f.buffer.update(text); assert.equal(f.buffer.snapshot().visible, text); assert.equal(f.frames.size, 0);
    }
    assert.equal(structuredTail("```java\n代码\n```\n\n后续正文"), false);
    assert.equal(structuredTail("~~~~\n```\n代码\n~~~~\n后续正文"), false);
});

test("unmount cancels callbacks and a resumed lifecycle can safely accept the remaining source", () => {
    const f = fixture(); f.buffer.update("测试组件生命周期恢复"); f.advance(16);
    const count = f.output.length; f.buffer.dispose(); f.advance(200);
    assert.equal(f.output.length, count); assert.equal(f.frames.size, 0);
    f.buffer.update("测试组件生命周期恢复"); f.advance(192);
    assert.equal(f.buffer.snapshot().visible, "测试组件生命周期恢复");
});

test("dense arrivals with a suspended frame clock keep a bounded queue", () => {
    const f = fixture(); let text = "";
    for (let n = 0; n < 1000; n++) { text += "字"; f.buffer.update(text); assert.ok(f.buffer.snapshot().pending <= 32); }
    f.advance(192); assert.equal(f.buffer.snapshot().visible, text);
});

test("remounting or re-enabling motion preserves existing prose without replaying it", () => {
    const existing = "已经展示的长回复".repeat(1000);
    let paints = 0, frames = 0;
    const buffer = createSmoothStream({initial: existing, publish: () => paints++, now: () => 0,
        requestFrame: () => ++frames, cancelFrame: () => {}});
    buffer.update(existing);
    assert.equal(buffer.snapshot().visible, existing);
    assert.equal(paints, 0); assert.equal(frames, 0);
});

test("plain thinking text buffers literal Markdown and HTML without applying Markdown bypass rules", () => {
    let frame, time = 0, visible = "";
    const buffer = createSmoothStream({structured: false, publish: text => { visible = text; }, now: () => time,
        requestFrame: fn => { frame = fn; return 1; }, cancelFrame: () => { frame = null; }});
    const text = "推理中的原文：\n```示例\nA | B\n<script>原样保留</script>";
    buffer.update(text); assert.equal(visible, "");
    time = 64; frame(); assert.ok(visible.length > 0 && visible.length < text.length);
    buffer.flush(); assert.equal(visible, text); assert.equal(frame, null);
});
