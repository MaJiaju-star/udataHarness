const graphemes = typeof Intl.Segmenter === "function" ? new Intl.Segmenter(undefined, {granularity: "grapheme"}) : null;
const MAX_LAG = 160;

// Leave structured Markdown to its existing renderer, rather than typing incomplete table/fence syntax.
export function structuredTail(text) {
    let fence = null;
    for (const match of text.matchAll(/^ {0,3}(`{3,}|~{3,})([^\n]*)/gm)) {
        const marker = match[1];
        if (!fence) fence = marker;
        else if (marker[0] === fence[0] && marker.length >= fence.length && !match[2].trim()) fence = null;
    }
    const lastLine = text.trimEnd().split("\n").at(-1) || "";
    return Boolean(fence) || /^ {0,3}(`{3,}|~{3,})/.test(lastLine) || lastLine.includes("|");
}

export function revealBoundary(text, desired) {
    if (desired >= text.length) return text.length;
    if (!graphemes) return desired > 0 && /[\uD800-\uDBFF]/.test(text[desired - 1]) ? desired - 1 : desired;
    let start = Math.max(0, desired - 256);
    if (start && /[\uDC00-\uDFFF]/.test(text[start])) start--;
    let boundary = start;
    for (const part of graphemes.segment(text.slice(start, desired + 256))) {
        const end = start + part.index + part.segment.length;
        if (end > desired) break;
        boundary = end;
    }
    return boundary;
}

/** Presentation-only buffer. Original SSE state and audit/accounting never wait for this clock. */
export function createSmoothStream({publish, now, requestFrame, cancelFrame, initial = "", structured = true}) {
    let target = initial, visible = initial, segments = [], frame = null, lastPaint = -Infinity;
    let lastArrival = null, rate = .09;
    const emit = end => {
        const next = target.slice(0, end);
        if (next !== visible) { visible = next; publish(next); }
    };
    const cancel = () => { if (frame !== null) cancelFrame(frame); frame = null; };
    const flush = () => { cancel(); segments = []; emit(target.length); };
    const schedule = () => { if (frame === null) frame = requestFrame(tick); };
    function tick() {
        frame = null;
        const time = now();
        // Keep Markdown parsing near the existing SSE batching cadence, while CSS handles the in-between fade.
        if (time - lastPaint < 32) { schedule(); return; }
        lastPaint = time;
        let desired = visible.length;
        while (segments.length) {
            const item = segments[0];
            if (time >= item.endAt) { desired = item.to; segments.shift(); continue; }
            if (time >= item.startAt) desired = Math.max(desired, Math.floor(item.from + (item.to - item.from) * (time - item.startAt) / (item.endAt - item.startAt)));
            break;
        }
        emit(segments.length ? Math.max(visible.length, revealBoundary(target, desired)) : target.length);
        if (segments.length) schedule();
    }
    return {
        update(text, enabled = true) {
            const old = target; target = text;
            if (!enabled || !text.startsWith(old) || (structured && structuredTail(text))) { flush(); return; }
            if (text === old) return;
            const time = now(), added = text.length - old.length;
            if (lastArrival !== null) rate = Math.max(.025, Math.min(4, rate * .65 + added / Math.max(16, time - lastArrival) * .35));
            lastArrival = time;
            const startAt = Math.max(time, segments.at(-1)?.endAt || time);
            segments.push({from: old.length, to: text.length, startAt,
                endAt: Math.min(time + MAX_LAG, startAt + Math.max(36, Math.min(MAX_LAG, added / rate)))});
            // Background or unusually dense updates must not create an unbounded animation queue.
            if (segments.length > 32) segments = [{from: visible.length, to: text.length, startAt: time, endAt: Math.min(time + MAX_LAG, segments[0].endAt)}];
            schedule();
        },
        flush,
        dispose() { cancel(); segments = []; target = visible; lastArrival = null; },
        snapshot: () => ({visible, target, pending: segments.length})
    };
}
