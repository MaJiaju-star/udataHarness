import assert from "node:assert/strict";
import test from "node:test";
import {toolSummary, toolStatus, toolDuration} from "../src/toolSummary.js";

test("read summarizes requested ranges, focus points, and observed default ranges", () => {
    const read = args => toolSummary({name: "read", args});
    assert.equal(read({file_path: "src/Demo.java", offset: 10, limit: 20}).detail, "第 10–29 行（请求范围）");
    assert.equal(read({focus_lines: [12, 80], context_lines: 5}).detail, "定位行 12、80 · 前后各 5 行");
    assert.equal(read({char_offset: 0, char_limit: 500}).detail, "快照字符偏移 0 · 最多 500 字符");
    assert.equal(toolSummary({name: "read", args: {file_path: "a.java"},
        output: "--- File: a.java (Lines: 1 - 13, Size: 0.34 KB) ---\nbody"}).detail, "第 1–13 行");
});

test("edit summaries use diff metadata without displaying replacement source", () => {
    const result = toolSummary({name: "edit", args: {file_path: "D:\\src\\Demo.java", new_string: "PRIVATE_BODY"},
        editDiff: {addedLines: 4, deletedLines: 2, firstChangedLine: 9}});
    assert.equal(result.target, "D:\\src\\Demo.java");
    assert.equal(result.detail, "修改文件 · 首处第 9 行");
    assert.equal(result.added, 4);
    assert.equal(result.removed, 2);
    assert.ok(!JSON.stringify(result).includes("PRIVATE_BODY"));
});

test("search and multiline shell summaries retain relevant targets", () => {
    const grep = toolSummary({name: "grep", args: {pattern: "@Controller", path: "src", include: "*.java"}});
    assert.equal(grep.target, "@Controller");
    assert.equal(grep.detail, "范围 src · *.java");
    assert.equal(toolSummary({name: "bash", args: {command: "mvn test\n  git status", description: "验证修改"}}).target,
        "mvn test git status");
});

test("streamed, historical, and unknown tools degrade without leaking arbitrary args", () => {
    assert.equal(toolSummary({name: "read", argsText: '{"file_path":"src/a.java"}'}).target, "src/a.java");
    assert.equal(toolSummary({name: "read", generating: true, argsText: '{"file_path":'}).target, "正在准备参数…");
    assert.equal(toolSummary({name: "custom", args: {api_key: "SECRET"}}).target, "查看调用详情");
    assert.equal(toolStatus({error: "failed", durationMs: 10}).label, "执行失败");
    assert.equal(toolStatus({running: true}).label, "执行中");
    assert.equal(toolStatus({argsText: "{}"}).label, "等待执行");
    assert.equal(toolDuration(undefined), "");
    assert.equal(toolDuration(0), "0 ms");
    assert.equal(toolDuration(1250), "1.3 s");
});
