import {test} from "node:test";
import assert from "node:assert/strict";
import {formatIdeaReference, readManualIdeaReference, withAutoReference, insertReferenceText, insertReferenceAtSelection, createReferenceReceiver} from "../src/ideaReferences.js";

test("current file, selected block, single line, and closed editor references", () => {
    assert.equal(formatIdeaReference({path: "src/Main.java"}), "@src/Main.java");
    assert.equal(formatIdeaReference({path: "src/Main.java", startLine: 3, endLine: 8}), "@src/Main.java#L3-L8");
    assert.equal(formatIdeaReference({path: "src/Main.java", startLine: 3, endLine: 3}), "@src/Main.java#L3-L3");
    assert.equal(formatIdeaReference(null), "");
    assert.equal(formatIdeaReference({}), "");
});

test("auto reference includes latest range and leaves explicit references intact", () => {
    const reference = {path: "src/Main.java", startLine: 5, endLine: 12};
    assert.equal(withAutoReference("解释这段代码", reference), "@src/Main.java#L5-L12\n\n解释这段代码");
    const explicit = "@src/Main.java#L1-L2 解释这两行";
    assert.equal(withAutoReference(explicit, reference), explicit);
    assert.equal(withAutoReference("@src/Main.java", reference), "@src/Main.java");
    assert.equal(withAutoReference("解释代码", null), "解释代码");
});

test("similarly named paths do not suppress a distinct automatic reference", () => {
    assert.equal(withAutoReference("@src/Main.java.bak 查看备份", {path: "src/Main.java"}),
        "@src/Main.java\n\n@src/Main.java.bak 查看备份");
    assert.equal(withAutoReference("@src/Test[1].java#L1-L2 查看", {path: "src/Test[1].java"}),
        "@src/Test[1].java#L1-L2 查看");
    assert.equal(withAutoReference("@src/Test1.java 查看", {path: "src/Test[1].java"}),
        "@src/Test[1].java\n\n@src/Test1.java 查看");
});

test("manual IDEA references preserve their leading and trailing newlines", () => {
    const reference = "\n@src/Main.java#L2-L5\n";
    assert.equal(insertReferenceText("解释这个函数", reference), `解释这个函数${reference}`);
    assert.equal(insertReferenceText("", reference), reference);
    assert.equal(insertReferenceText("任务\n", reference), `任务\n${reference}`);
    assert.equal(insertReferenceText("网页任务", "@src/Main.java"), "网页任务 @src/Main.java ");
});

test("references can be inserted step by step at different instruction positions", () => {
    let prompt = "请你根据 文件A 改造 文件B的代码";
    const firstStart = prompt.indexOf("文件A");
    let inserted = insertReferenceAtSelection(prompt, "@docs/spec.md", {start: firstStart, end: firstStart + 3});
    prompt = inserted.value;
    const secondStart = prompt.indexOf("文件B");
    inserted = insertReferenceAtSelection(prompt, "@src/Main.java#L1-L25", {start: secondStart, end: secondStart + 3});
    assert.equal(inserted.value, "请你根据 @docs/spec.md 改造 @src/Main.java#L1-L25 的代码");
    assert.equal(inserted.value.slice(inserted.cursor), "的代码");
});

test("separate ranges in the same file are retained and subsequent insertion follows the caret", () => {
    const first = insertReferenceAtSelection("比较", "@src/Main.java#L1-L25");
    const second = insertReferenceAtSelection(first.value, "@src/Main.java#L40-L60", {start: first.cursor, end: first.cursor});
    assert.equal(second.value, "比较 @src/Main.java#L1-L25 @src/Main.java#L40-L60 ");
    assert.equal(withAutoReference(second.value, {path: "src/Main.java", startLine: 70, endLine: 90}), second.value);
});

test("multiline payloads and a stale caret are handled without erasing surrounding text", () => {
    assert.deepEqual(insertReferenceAtSelection("前后", "\n@a.java#L1-L2\n", {start: 1, end: 1}),
        {value: "前\n@a.java#L1-L2\n后", cursor: 16});
    const stale = insertReferenceAtSelection("任务", "@a.java", {start: 100, end: 200});
    assert.equal(stale.value, "任务 @a.java ");
});

test("references queued before the page mounts are retrieved and acknowledged", async () => {
    let pending = [{id: "one", text: "\n@src/Main.java#L1-L10\n"}], inserted = "";
    const receive = createReferenceReceiver(async (method, params) => {
        if (method === "getPendingReferences") return pending;
        assert.deepEqual(params.ids, ["one"]);
        pending = [];
    }, text => {inserted += text;});
    await receive();
    await receive();
    assert.equal(inserted, "\n@src/Main.java#L1-L10\n");
    assert.equal(pending.length, 0);
});

test("a failed acknowledgement retries without duplicating inserted references", async () => {
    let attempts = 0, insertions = 0;
    const receive = createReferenceReceiver(async method => {
        if (method === "getPendingReferences") return [{id: "one", text: "\n@a.java#L1-L2\n"}];
        if (++attempts === 1) throw new Error("Lost ack");
    }, () => {insertions++;});
    await assert.rejects(receive(), /Lost ack/);
    await receive();
    assert.equal(insertions, 1);
    assert.equal(attempts, 2);
});

test("a failed input initialization leaves references pending for retry", async () => {
    let insertions = 0, acknowledgements = 0;
    const receive = createReferenceReceiver(async method => {
        if (method === "getPendingReferences") return [{id: "one", text: "\n@a.java#L1-L2\n"}];
        acknowledgements++;
    }, () => {if (++insertions === 1) throw new Error("Composer not ready");});
    await assert.rejects(receive(), /Composer not ready/);
    assert.equal(acknowledgements, 0);
    await receive();
    assert.equal(acknowledgements, 1);
});

test("toolbar file reference excludes the active selection range", async () => {
    const read = async method => {
        assert.equal(method, "getReference");
        return {path: "src/Main.java", startLine: 2, endLine: 5};
    };
    assert.equal(await readManualIdeaReference(read, "file"), "@src/Main.java");
    assert.equal(await readManualIdeaReference(read, "selection"), "@src/Main.java#L2-L5");
});

test("toolbar selection requires an actual selection and file reference requires an open file", async () => {
    await assert.rejects(readManualIdeaReference(async () => ({path: "src/Main.java"}), "selection"), /选中/);
    await assert.rejects(readManualIdeaReference(async () => ({}), "file"), /打开/);
    await assert.rejects(readManualIdeaReference(async () => ({path: "src/Main.java", startLine: 3, endLine: 2}), "selection"), /选中/);
});
