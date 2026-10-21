import {useCallback, useEffect, useRef, useState} from "react";
import {createConversationTimer} from "./conversationTimer.js";

export default function useConversationTimer(userId, sessionId) {
    const clock = useRef(null);
    if (!clock.current) clock.current = createConversationTimer();
    const [, redraw] = useState(0);
    const invoke = useCallback((method, id, event) => {
        if (id && clock.current[method](`${userId}:${id}`, event)) redraw(value => value + 1);
    }, [userId]);
    const begin = useCallback(id => invoke("begin", id), [invoke]);
    const resume = useCallback(id => invoke("resume", id), [invoke]);
    const end = useCallback(id => invoke("end", id), [invoke]);
    const event = useCallback((id, value) => invoke("event", id, value), [invoke]);
    const view = clock.current.snapshot(`${userId}:${sessionId}`);
    useEffect(() => {
        if (view.status !== "running") return;
        const interval = setInterval(() => redraw(value => value + 1), 250);
        return () => clearInterval(interval);
    }, [view.status, userId, sessionId]);
    return {begin, resume, end, event, view};
}
