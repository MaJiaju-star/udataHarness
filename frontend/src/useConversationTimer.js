import {useEffect, useMemo, useRef, useState} from "react";
import {createConversationTimerStore} from "./conversationTimer.js";

export default function useConversationTimer(userId) {
    const clock = useRef(null);
    if (!clock.current) clock.current = createConversationTimerStore();
    return useMemo(() => {
        const store = clock.current, key = id => `${userId}:${id}`;
        return {
            begin: id => id && store.begin(key(id)), resume: id => id && store.resume(key(id)),
            end: id => id && store.end(key(id)), event: (id, event) => id && store.event(key(id), event),
            snapshot: id => store.snapshot(key(id)),
            subscribe: (id, listener) => store.subscribe(changed => { if (changed === key(id)) listener(); })
        };
    }, [userId]);
}

export function useConversationElapsed(timer, sessionId) {
    const [, redraw] = useState(0);
    useEffect(() => timer.subscribe(sessionId, () => redraw(value => value + 1)), [timer, sessionId]);
    const view = timer.snapshot(sessionId);
    useEffect(() => {
        if (view.status !== "running") return;
        const interval = setInterval(() => redraw(value => value + 1), 250);
        return () => clearInterval(interval);
    }, [view.status, timer, sessionId]);
    return view;
}
