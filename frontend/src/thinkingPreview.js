const segmenter = typeof Intl.Segmenter === "function" ? new Intl.Segmenter(undefined, {granularity: "grapheme"}) : null;

/** Bound work per SSE update, even when the full reasoning contains megabytes. */
export function thinkingPreview(content = "", tail = false, limit = 88) {
    const sample = tail ? content.slice(-1024) : content.slice(0, 1024);
    const text = sample.replace(/\s+/g, " ").trim();
    const characters = segmenter ? Array.from(segmenter.segment(text), item => item.segment) : Array.from(text);
    const clipped = content.length > sample.length || characters.length > limit;
    const visible = (tail ? characters.slice(-limit) : characters.slice(0, limit)).join("");
    return {text: visible, clipped};
}
