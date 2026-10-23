// Monotonic elapsed time for locally initiated turns. Timer ticks never increment the duration.
export function createConversationTimer(now = () => performance.now()) {
    const sessions = new Map();
    const freeze = (key, status) => {
        const state = sessions.get(key);
        if (!state || state.status !== "running") return false;
        state.elapsedMs += Math.max(0, now() - state.startedAt);
        state.startedAt = null; state.status = status; return true;
    };
    return {
        begin(key) { sessions.set(key, {elapsedMs: 0, startedAt: now(), status: "running"}); return true; },
        resume(key) {
            const state = sessions.get(key);
            // After reload, the previous active segments are unknown; never invent a partial total.
            if (!state || state.status !== "paused") return false;
            state.startedAt = now(); state.status = "running"; return true;
        },
        end(key) { return freeze(key, "finished"); },
        event(key, event) {
            if (!event || event.type === "subagent_event" || event.parentRunId) return false;
            if (event.type === "hitl" || event.type === "run_pending") return freeze(key, "paused");
            if (["run_end", "done", "error"].includes(event.type)) return freeze(key, "finished");
            return false;
        },
        snapshot(key) {
            const state = sessions.get(key);
            if (!state) return {elapsedMs: null, status: "unknown"};
            return {status: state.status, elapsedMs: state.elapsedMs + (state.status === "running" ? Math.max(0, now() - state.startedAt) : 0)};
        }
    };
}

export function conversationElapsedText(ms) {
    if (ms == null || !Number.isFinite(ms) || ms < 0) return "—";
    if (ms < 60000) return `${(ms / 1000).toFixed(1)} s`;
    const seconds = Math.floor(ms / 1000);
    if (seconds < 3600) return `${Math.floor(seconds / 60)} 分 ${seconds % 60} 秒`;
    return `${Math.floor(seconds / 3600)} 时 ${Math.floor(seconds % 3600 / 60)} 分 ${seconds % 60} 秒`;
}

// Transition notifications only. The display owns its repaint timer, so elapsed
// ticks never rerender App or the conversation's historical messages.
export function createConversationTimerStore(now) {
    const clock = createConversationTimer(now), listeners = new Set();
    const invoke = (method, key, event) => {
        if (!clock[method](key, event)) return;
        for (const listener of listeners) listener(key);
    };
    return {
        begin: key => invoke("begin", key), resume: key => invoke("resume", key),
        end: key => invoke("end", key), event: (key, event) => invoke("event", key, event),
        snapshot: key => clock.snapshot(key),
        subscribe(listener) { listeners.add(listener); return () => listeners.delete(listener); }
    };
}
