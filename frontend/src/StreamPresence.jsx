import {createContext, memo, useContext, useLayoutEffect, useState} from "react";
import {watchStreamIdle} from "./streamPresence.js";

export const StreamPresenceContext = createContext({active: false, content: "", mode: "soft"});

export function useStreamWaiting(active, content, progressAt) {
    const [waiting, setWaiting] = useState("");
    useLayoutEffect(() => {
        setWaiting("");
        if (!active) return;
        return watchStreamIdle({schedule: (callback, delay) => window.setTimeout(callback, delay),
            cancel: id => window.clearTimeout(id), publish: setWaiting,
            elapsed: progressAt == null ? 0 : Math.max(0, Date.now() - progressAt)});
    }, [active, content, progressAt]);
    return active ? waiting : "";
}

export const StreamPresence = memo(function StreamPresence({active, content, mode = "soft", block = false, progressAt}) {
    const waiting = useStreamWaiting(active, content, progressAt);
    if (!active) return null;
    return <span className="stream-presence" data-block={block || undefined} data-motion={mode} aria-label="回复仍在进行">
        <span className="stream-presence-dot" aria-hidden="true"/>
        {waiting && <small className="stream-presence-label" role="status">{waiting}</small>}
    </span>;
});

export function StreamPresenceNode({node}) {
    const state = useContext(StreamPresenceContext);
    return <StreamPresence {...state} block={node.properties["data-block"]}/>;
}
