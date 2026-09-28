export function formatIdeaReference(reference) {
    if (!reference?.path) return "";
    const {path, startLine, endLine} = reference;
    return Number.isInteger(startLine) && Number.isInteger(endLine) && startLine > 0 && endLine >= startLine
        ? `@${path}#L${startLine}-L${endLine}` : `@${path}`;
}

export function withAutoReference(prompt, reference) {
    const text = formatIdeaReference(reference);
    if (!text) return prompt;
    // An explicit reference to this file takes precedence over the automatic one.
    const escaped = reference.path.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
    return new RegExp(`@${escaped}(?=#L\\d|\\s|$)`).test(prompt)
        ? prompt : `${text}\n\n${prompt.trim()}`;
}

export function insertReferenceText(prompt, text) {
    if (text.startsWith("\n")) return `${prompt}${text}`;
    const separator = prompt && !/\s$/.test(prompt) ? " " : "";
    return `${prompt}${separator}${text} `;
}

export function insertReferenceAtSelection(prompt, text, selection) {
    const start = Math.max(0, Math.min(selection?.start ?? prompt.length, prompt.length));
    const end = Math.max(start, Math.min(selection?.end ?? start, prompt.length));
    const before = prompt.slice(0, start), after = prompt.slice(end);
    // Preserve explicit multiline payloads from older plugins. New references can
    // be composed inline with the surrounding instruction at the remembered caret.
    const insertion = text.startsWith("\n") ? text :
        `${before && !/\s$/.test(before) ? " " : ""}${text}${after && /^\s/.test(after) ? "" : " "}`;
    return {value: before + insertion + after, cursor: before.length + insertion.length};
}

// Keep native requests until the React input has accepted them. Retry acknowledgements
// without inserting the same reference twice if a reply is lost or the page loads late.
export function createReferenceReceiver(callNative, insert) {
    const received = new Set();
    let busy = false;
    return async function receive() {
        if (busy) return;
        busy = true;
        try {
            const pending = await callNative("getPendingReferences");
            if (!Array.isArray(pending)) return;
            const valid = pending.filter(item => typeof item?.id === "string" && typeof item.text === "string");
            const fresh = valid.filter(item => !received.has(item.id));
            if (fresh.length) {
                await insert(fresh.map(item => item.text).join(" "), fresh.map(item => item.text));
                fresh.forEach(item => received.add(item.id));
            }
            if (valid.length) await callNative("ackReferences", {ids: valid.map(item => item.id)});
            const active = new Set(valid.map(item => item.id));
            for (const id of received) if (!active.has(id)) received.delete(id);
        } finally {
            busy = false;
        }
    };
}
