import {createContext, memo, useContext, useLayoutEffect, useState} from "react";
import {streamWaitingLabel, watchStreamIdle} from "./streamPresence.js";

export const StreamPresenceContext = createContext({active: false, content: "", mode: "soft"});

export function useStreamWaiting(active, content, progressAt) {
    const [waiting, setWaiting] = useState(null);
    useLayoutEffect(() => {
        setWaiting(null);
        if (!active) return;
        return watchStreamIdle({schedule: (callback, delay) => window.setTimeout(callback, delay),
            cancel: id => window.clearTimeout(id), publish: setWaiting,
            elapsed: progressAt == null ? 0 : Math.max(0, Date.now() - progressAt)});
    }, [active, content, progressAt]);
    return active ? waiting : null;
}

export function StreamWaitIndicator({waiting, compact = false, thinking = false, mode = "soft"}) {
    const label = streamWaitingLabel(waiting, thinking);
    return <span className="stream-wait" data-compact={compact || undefined} data-slow={waiting.slow || undefined} data-motion={mode}
        role="status" aria-label={label} title={`${label} · ${waiting.seconds} 秒`}>
        <span className="stream-wait-track" aria-hidden="true"><i/></span>
        <span className="stream-wait-copy"><span className="stream-presence-label">{label}</span>{" "}
            <span className="stream-wait-seconds" aria-hidden="true">· {waiting.seconds} 秒</span>
            {waiting.slow && !compact && <span className="stream-wait-hint">可继续等待，或停止生成</span>}
        </span>
    </span>;
}

export const StreamPresence = memo(function StreamPresence({active, content, mode = "soft", block = false, progressAt}) {
    const waiting = useStreamWaiting(active, content, progressAt);
    if (!active || !waiting) return null;
    return <span className="stream-presence" data-block={block || undefined}>
        <StreamWaitIndicator waiting={waiting} mode={mode}/>
    </span>;
});

export function StreamPresenceNode({node}) {
    const state = useContext(StreamPresenceContext);
    return <StreamPresence {...state} block={node.properties["data-block"]}/>;
}
