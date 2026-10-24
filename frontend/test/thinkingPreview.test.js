import test from "node:test";
import assert from "node:assert/strict";
import {thinkingPreview} from "../src/thinkingPreview.js";

test("stream preview follows the tail and completed preview returns to the head", () => {
    const content = "开始。" + "中间内容".repeat(400) + " 最新步骤：验证结果。";
    const live = thinkingPreview(content, true), done = thinkingPreview(content);
    assert.ok(live.text.endsWith("最新步骤：验证结果。"));
    assert.ok(done.text.startsWith("开始。"));
    assert.ok(live.clipped && done.clipped);
});
test("preview folds whitespace and preserves grapheme clusters", () => {
    assert.deepEqual(thinkingPreview("  A\n B\tC  ", true), {text: "A B C", clipped: false});
    assert.equal(thinkingPreview("开头👨‍👩‍👧‍👦é结束", true, 4).text, "👨‍👩‍👧‍👦é结束");
    assert.deepEqual(thinkingPreview("", true), {text: "", clipped: false});
});
