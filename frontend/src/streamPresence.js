export const SHORT_WAIT_MS = 5000;
export const LONG_WAIT_MS = 20000;

/** One pair of deadlines per content revision; heartbeats never reset these clocks. */
export function watchStreamIdle({schedule, cancel, publish, elapsed = 0}) {
    const jobs = [];
    if (elapsed >= LONG_WAIT_MS) publish("响应较慢，仍在等待…");
    else {
        if (elapsed >= SHORT_WAIT_MS) publish("等待继续…");
        else jobs.push(schedule(() => publish("等待继续…"), SHORT_WAIT_MS - elapsed));
        jobs.push(schedule(() => publish("响应较慢，仍在等待…"), LONG_WAIT_MS - elapsed));
    }
    return () => jobs.forEach(cancel);
}

/** A turn-owned timestamp survives Markdown block replacement and virtual row remounts. */
export function streamProgressAt(previous, next, event, now = Date.now()) {
    if (event.type === "run_start" || /^tool_(start|end|args_start|args_delta|args_end)$/.test(event.type)
        || (event.type === "subagent_event" && !["heartbeat", "usage", "metrics"].includes(event.subtype)) || (event.type === "chunk" && event.content)
        || ((event.type === "thinking" || event.type === "text" || event.type === "text_replay")
            && (next.content !== previous.content || next.thinking !== previous.thinking || event.finished))) return now;
    return previous.streamProgressAt;
}

/** Do not let a completed activity imply that the whole response has finished. */
export function streamPresenceTarget(message) {
    if (message.finished || message.error || message.hitl) return {kind: "none"};
    if (message.tools?.some(tool => tool.running || tool.generating) || message.todo?.updating
        || Object.values(message.subagents || {}).some(child => ["running", "pending"].includes(child.status))) return {kind: "busy"};
    const last = message.activities?.findLast(item => item.type !== "text" || item.content);
    if (last?.type === "thinking" && last.active) return {kind: "thinking", id: last.id};
    if (last?.type === "text") return {kind: "text", id: last.id};
    if (!last && message.content) return {kind: "text"};
    if (!last && message.thinking) return {kind: "thinking"};
    return {kind: "fallback"};
}

/** Place the live marker in the final prose block, without changing Markdown source. */
export function appendStreamPresence(tree) {
    let parent = tree;
    for (;;) {
        const last = parent.children?.findLast(node => node.type !== "text" || node.value.trim());
        if (last?.type !== "element") break;
        if (/^(p|h[1-6])$/.test(last.tagName)) { parent = last; break; }
        if (!["ul", "ol", "li", "blockquote"].includes(last.tagName)) break;
        parent = last;
    }
    const inline = parent !== tree && !["ul", "ol", "blockquote"].includes(parent.tagName);
    // A code block/table stays intact, including copyable code and table structure.
    if (!inline) parent = tree;
    parent.children.push({type: "element", tagName: "stream-presence", properties: {"data-block": !inline}, children: []});
}
