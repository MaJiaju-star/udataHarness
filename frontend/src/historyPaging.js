export function prependHistory(older, current) {
    const existing = new Set(current.map(message => message.id));
    return [...older.filter(message => !existing.has(message.id)), ...current];
}
export function mergeLatestHistory(current, latest) {
    const ids = new Set(latest.map(message => message.id));
    const historical = current.filter(message => message.id.startsWith("history-"));
    if (!historical.some(message => ids.has(message.id))) return {messages: latest, keptOlder: false};
    const first = Number(latest[0]?.id.replace("history-", ""));
    const older = historical.filter(message => Number(message.id.replace("history-", "")) < first);
    return {messages: [...older, ...latest], keptOlder: older.length > 0};
}

export function virtualOffsets(messages, heights, estimate = 220) {
    const offsets = [0];
    for (const message of messages) offsets.push(offsets.at(-1) + (heights.get(message.id) || estimate));
    return offsets;
}
export function indexAt(offsets, position) {
    let low = 0, high = Math.max(0, offsets.length - 2);
    while (low < high) { const mid = Math.ceil((low + high) / 2); if (offsets[mid] <= position) low = mid; else high = mid - 1; }
    return low;
}
export function virtualRange(offsets, top, height, overscan = 900) {
    const count = offsets.length - 1;
    if (!count) return {start: 0, end: 0};
    return {start: indexAt(offsets, Math.max(0, top - overscan)), end: Math.min(count, indexAt(offsets, top + height + overscan) + 1)};
}

/** Keep the newest exchange mounted while older rows are windowed. Never bridge the history gap. */
export function virtualRanges(offsets, top, height, overscan = 900, tailCount = 3) {
    const count = offsets.length - 1;
    if (!count) return [];
    const visible = virtualRange(offsets, top, height, overscan);
    const tail = {start: Math.max(0, count - tailCount), end: count};
    return visible.end >= tail.start ? [{start: Math.min(visible.start, tail.start), end: count}] : [visible, tail];
}

/** Select rows using the corrected anchor before React removes the old window. */
export function anchoredVirtualTop(anchor, messages, offsets, top) {
    if (!anchor) return top;
    const index = messages.findIndex(message => message.id === anchor.id);
    if (index < 0) return top;
    if (top < anchor.start || (anchor.height != null && top >= anchor.start + anchor.height)) return top;
    const inset = Math.max(0, top - anchor.start);
    const height = offsets[index + 1] - offsets[index];
    // Clamp only an actual shrink of the reader's row. A pending wheel/scrollbar
    // movement may already have left that row; clamping it would jump backward.
    const shrank = anchor.height == null || height < anchor.height;
    const wasInside = anchor.height == null || inset < anchor.height;
    return offsets[index] + (shrank && wasInside ? Math.min(inset, Math.max(0, height - 1)) : inset);
}

/** Session-owned async cache. Pending requests survive row unmounts; subscriptions never do. */
export function createHistoryCache(maxEntries = 100) {
    const values = new Map(), listeners = new Map(), pending = new Map();
    const emit = key => listeners.get(key)?.forEach(listener => listener());
    return {
        get(key, initial) { if (!values.has(key)) values.set(key, initial); return values.get(key); },
        subscribe(key, listener) {
            if (!listeners.has(key)) listeners.set(key, new Set()); listeners.get(key).add(listener);
            return () => { listeners.get(key)?.delete(listener); if (!listeners.get(key)?.size) listeners.delete(key); };
        },
        load(key, fetchPage) {
            if (pending.has(key)) return pending.get(key);
            const old = values.get(key); values.set(key, {...old, loading: true, error: ""}); emit(key);
            const request = Promise.resolve().then(() => fetchPage(old.nextOffset)).then(page => {
                values.set(key, {content: old.content + page.content, nextOffset: page.nextOffset, hasMore: page.hasMore, loading: false, error: ""});
            }, error => { values.set(key, {...old, loading: false, error: error.message}); }).finally(() => {
                pending.delete(key); emit(key);
                for (const candidate of values.keys()) {
                    if (values.size <= maxEntries) break;
                    if (!listeners.has(candidate) && !pending.has(candidate)) values.delete(candidate);
                }
            });
            pending.set(key, request); return request;
        }
    };
}
