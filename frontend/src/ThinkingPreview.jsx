import {memo, useEffect, useMemo, useState} from "react";
import {thinkingPreview} from "./thinkingPreview.js";

export default memo(function ThinkingPreview({content, active, mode = "soft", waiting = ""}) {
    // Historical cards start at the head. Only a card that was streaming holds its tail.
    const [holdTail, setHoldTail] = useState(active);
    useEffect(() => {
        if (active) { setHoldTail(true); return; }
        if (!holdTail) return;
        const timer = window.setTimeout(() => setHoldTail(false), 300);
        return () => window.clearTimeout(timer);
    }, [active, holdTail]);
    const tail = active || holdTail;
    const preview = useMemo(() => thinkingPreview(content, tail), [content, tail]);
    const suffix = thinkingPreview(preview.text, true, 6).text;
    if (active && waiting) return <small className="thinking-preview thinking-preview-waiting" role="status">{waiting}</small>;
    return <small className={`thinking-preview ${tail ? "is-tail" : ""}`} title={preview.text}>
        <span className="thinking-preview-text">{tail ? <>
            {preview.clipped ? "…" : ""}{preview.text.slice(0, preview.text.length - suffix.length)}
            <span key={suffix} className={active && mode !== "off" ? "thinking-preview-tip" : undefined}>{suffix}</span>
        </> : <>{preview.text}{preview.clipped ? "…" : ""}</>}</span>
    </small>;
});
