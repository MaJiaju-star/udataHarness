// Preserve every event and its order. Only React commits are batched; timers and
// first-token accounting can still observe the original events on arrival.
const urgent = new Set(["hitl", "hitl_decision", "run_pending", "run_end", "done", "error"]);
const visible = new Set(["memory_recall", "project_instructions", "hitl_decision", "text", "text_replay", "thinking", "tool_args_start", "tool_args_delta",
    "tool_args_end", "tool_start", "tool_end", "subagent_event", "chunk", "hitl", "done", "run_end", "error"]);
export function applyMessageBatch(messages, id, events, reduce) {
    const batch = (Array.isArray(events) ? events : [events]).filter(event => visible.has(event.type));
    if (!batch.length) return messages;
    const index = messages.findIndex(message => message.id === id);
    if (index < 0) return messages;
    const updated = batch.reduce(reduce, messages[index]);
    if (updated === messages[index]) return messages;
    const result = messages.slice(); result[index] = updated; return result;
}
export function createStreamBatcher(commit, {delay = 40, maxEvents = 256, schedule = setTimeout, cancel = clearTimeout} = {}) {
    let pending = [], timer = null, closed = false;
    const flush = () => {
        if (timer !== null) { cancel(timer); timer = null; }
        if (!pending.length) return;
        const batch = pending; pending = []; commit(batch);
    };
    return {
        push(event) {
            if (closed) return;
            pending.push(event);
            if (urgent.has(event.type) || pending.length >= maxEvents) flush();
            else if (timer === null) timer = schedule(flush, delay);
        },
        flush,
        close() { if (closed) return; closed = true; flush(); }
    };
}
