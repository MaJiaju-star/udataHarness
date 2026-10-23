const segmenter = typeof Intl.Segmenter === "function" ? new Intl.Segmenter(undefined, {granularity: "grapheme"}) : null;
const excluded = new Set(["pre", "code", "table", "svg", "math"]);

export function streamStyle(value) {
    return ["off", "soft", "glow"].includes(value) ? value : "soft";
}

/** Decorate a bounded trailing set of graphemes without changing their text. */
export function decorateStreamTail(tree, clock, now, limit = 24) {
    let leaves = [], offset = 0;
    function collect(node, blocked = false) {
        blocked ||= excluded.has(node.tagName);
        if (node.type === "text") {
            const start = offset; offset += node.value.length;
            // Markdown adds whitespace between blocks; it must not revive prose before a code block.
            if (!node.value.trim()) return;
            if (blocked) leaves = [];
            else leaves.push({node, start});
        }
        for (const child of node.children || []) collect(child, blocked);
    }
    collect(tree);
    const selected = [];
    for (let n = leaves.length - 1; n >= 0 && selected.length < limit; n--) {
        const {node, start} = leaves[n];
        // Bound Unicode segmentation too, even for a very long paragraph.
        let suffixStart = Math.max(0, node.value.length - 256);
        if (suffixStart && /[\uDC00-\uDFFF]/.test(node.value[suffixStart])) suffixStart--;
        const suffix = node.value.slice(suffixStart);
        const parts = segmenter ? [...segmenter.segment(suffix)].map(part => ({text: part.segment, index: part.index}))
            : Array.from(suffix).reduce((items, text) => [...items, {text, index: items.length ? items.at(-1).index + items.at(-1).text.length : 0}], []);
        for (let i = parts.length - 1; i >= 0 && selected.length < limit; i--) {
            const index = suffixStart + parts[i].index;
            selected.unshift({node, index, text: parts[i].text, id: String(start + index)});
        }
    }
    const nextClock = new Map();
    selected.forEach((part, index) => nextClock.set(part.id, clock.get(part.id) ?? now + index * 3));
    clock.clear(); nextClock.forEach((value, key) => clock.set(key, value));
    const replacements = new Map();
    selected.forEach((part, index) => {
        if (!replacements.has(part.node)) replacements.set(part.node, [{type: "text", value: part.node.value.slice(0, part.index)}]);
        replacements.get(part.node).push({type: "element", tagName: "span", properties: {
            "data-stream-id": part.id, "data-stream-born": clock.get(part.id),
            ...(index === selected.length - 1 ? {"data-stream-tip": "true"} : {})
        }, children: [{type: "text", value: part.text}]});
    });
    function replace(node) {
        if (!node.children) return;
        node.children = node.children.flatMap(child => replacements.get(child) || [child]);
        for (const child of node.children) if (!child.properties?.["data-stream-id"]) replace(child);
    }
    replace(tree);
}
