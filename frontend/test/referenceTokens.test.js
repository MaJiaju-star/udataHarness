import {test} from "node:test";
import assert from "node:assert/strict";
import {splitReferenceTokens, readReferenceInput, referenceSelectionOffset, referenceSelectionPoint} from "../src/referenceTokens.js";

test("file and line-range references become independent tags without changing the instruction", () => {
    const prompt = "请根据 @docs/spec.md 改造 @src/Main.java#L1-L25 的代码";
    const tokens = splitReferenceTokens(prompt, ["@docs/spec.md", "@src/Main.java", "@src/Main.java#L1-L25"]);
    assert.deepEqual(tokens.filter(item => item.reference).map(item => item.text), ["@docs/spec.md", "@src/Main.java#L1-L25"]);
    assert.equal(tokens.map(item => item.text).join(""), prompt);
});

test("same-file ranges, spaces in file names, and newlines are preserved", () => {
    const references = ["@docs/design notes.md", "@a.java#L1-L2", "@a.java#L4-L6"];
    const prompt = references.join("\n");
    const tokens = splitReferenceTokens(prompt, references);
    assert.equal(tokens.filter(item => item.reference).length, 3);
    assert.equal(tokens.map(item => item.text).join(""), prompt);
});

test("ordinary @ text and filename prefixes are left editable", () => {
    const prompt = "联系 a@example.com @src/Other.java.bak";
    assert.deepEqual(splitReferenceTokens(prompt, ["@src/Other.java"]), [{text: prompt}]);
});

test("tag display labels do not leak into copied or submitted reference values", () => {
    const root = {childNodes: [
        {nodeType: 3, nodeValue: "修改 "},
        {dataset: {reference: "@src/Main.java#L1-L25"}, childNodes: [{nodeType: 3, nodeValue: "Main.java"}]},
        {nodeType: 3, nodeValue: "\n继续"},
    ]};
    assert.equal(readReferenceInput(root), "修改 @src/Main.java#L1-L25\n继续");
});

test("caret offsets use complete reference values rather than a tag's display label", () => {
    const text = value => ({nodeType: 3, nodeValue: value, childNodes: []});
    const element = (childNodes, dataset = {}) => ({nodeType: 1, childNodes, dataset,
        contains(target) {return this === target || this.childNodes.some(child => child === target || child.contains?.(target));}});
    const before = text("hello "), label = text("a.java"), after = text(" world");
    const tag = element([label], {reference: "@a.java#L1-L2"});
    const root = element([before, tag, after]);
    assert.equal(referenceSelectionOffset(root, root, 1), 6);
    assert.equal(referenceSelectionOffset(root, root, 2), 19);
    assert.equal(referenceSelectionOffset(root, after, 3), 22);
    assert.equal(referenceSelectionOffset(root, label, 3), 19);
    assert.deepEqual(referenceSelectionPoint(root, 19), {node: root, offset: 2});
    assert.deepEqual(referenceSelectionPoint(root, 22), {node: after, offset: 3});
});

test("the trailing caret sentinel never appears in clipboard or submitted text", () => {
    const root = {childNodes: [{nodeType: 3, nodeValue: "下一行\n"},
        {nodeName: "BR", dataset: {caretSentinel: "true"}, childNodes: []}]};
    assert.equal(readReferenceInput(root), "下一行\n");
});
