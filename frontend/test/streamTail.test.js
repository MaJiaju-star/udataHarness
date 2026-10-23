import test from "node:test";
import assert from "node:assert/strict";
import React from "react";
import {renderToStaticMarkup} from "react-dom/server";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import {decorateStreamTail, streamStyle} from "../src/streamTail.js";

const text = value => ({type: "text", value});
const element = (tagName, ...children) => ({type: "element", tagName, children});
const glyphs = node => [ ...(node.properties?.["data-stream-id"] !== undefined ? [node] : []), ...(node.children || []).flatMap(glyphs) ];
const plain = node => node.type === "text" ? node.value : (node.children || []).map(plain).join("");

test("only the latest 24 graphemes animate, preserving Unicode and complete received content", () => {
    const content = "已有正文".repeat(10000) + "👨‍👩‍👦e\u0301中文追光";
    const tree = element("root", element("p", text(content))), clock = new Map();
    decorateStreamTail(tree, clock, 1000);
    assert.equal(plain(tree), content);
    assert.equal(glyphs(tree).length, 24);
    assert.equal(clock.size, 24);
    assert.ok(glyphs(tree).some(node => plain(node) === "👨‍👩‍👦"));
    assert.ok(glyphs(tree).some(node => plain(node) === "e\u0301"));
});

test("continued SSE chunks retain birth times rather than restarting the existing text animation", () => {
    const clock = new Map(), first = element("root", text("正在输出"));
    decorateStreamTail(first, clock, 1000);
    const born = clock.get("0"), next = element("root", text("正在输出新的文字"));
    decorateStreamTail(next, clock, 1100);
    assert.equal(clock.get("0"), born);
    assert.equal(clock.get("4"), 1112);
    assert.equal(glyphs(next).filter(node => node.properties["data-stream-tip"]).length, 1);
});

test("code and tables stay stable, and a trailing code block does not animate earlier prose", () => {
    for (const tag of ["pre", "code", "table"]) {
        const tree = element("root", element("p", text("上面的正文")), element(tag, text("const a = 1")), text("\n"));
        decorateStreamTail(tree, new Map(), 1000);
        assert.equal(glyphs(tree).length, 0);
        tree.children.push(element("p", text("代码后的解释")));
        decorateStreamTail(tree, new Map(), 1100);
        assert.equal(glyphs(tree).map(plain).join(""), "代码后的解释");
    }
});

test("actual Markdown retains formatting and escaped HTML without decorating fenced code or tables", () => {
    const content = "**粗体** 和 [链接](https://example.com)\n\n```js\nconst x = 1\n```\n\n| A | B |\n| - | - |\n| 1 | 2 |\n\n最新的 *中文* 👋 输出 <script>alert(1)</script>";
    const html = renderToStaticMarkup(React.createElement(ReactMarkdown, {
        remarkPlugins: [remarkGfm], rehypePlugins: [() => tree => decorateStreamTail(tree, new Map(), 1000)]
    }, content));
    assert.match(html, /<strong>粗体<\/strong>/);
    assert.match(html, /<a href="https:\/\/example.com">链接<\/a>/);
    assert.match(html, /<pre><code class="language-js">const x = 1\n<\/code><\/pre>/);
    assert.doesNotMatch(html.match(/<table>[\s\S]*?<\/table>/)?.[0] || "", /data-stream/);
    assert.doesNotMatch(html, /<script>/);
    assert.match(html, /data-stream-tip="true"/);
});

test("invalid saved preferences safely default to the gentle effect", () => {
    assert.equal(streamStyle(null), "soft"); assert.equal(streamStyle("unknown"), "soft");
    for (const mode of ["off", "soft", "glow"]) assert.equal(streamStyle(mode), mode);
});
