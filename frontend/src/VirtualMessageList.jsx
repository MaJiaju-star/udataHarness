import {memo, useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState} from "react";
import {anchoredVirtualTop, indexAt, virtualOffsets, virtualRange} from "./historyPaging.js";
import {MessageScope} from "./HistoryView.jsx";

/** Variable-height window. Measurements and row identities survive prepend and stream updates. */
export default memo(function VirtualMessageList({messages, scrollRef, captureRef, isFollowing, followOutput, renderMessage}) {
    const root = useRef(null), heights = useRef(new Map()), anchor = useRef(null), geometry = useRef(null);
    const initialized = useRef(false);
    const [revision, setRevision] = useState(0), [viewport, setViewport] = useState({top: 0, height: 800});
    const viewportRef = useRef(viewport);
    viewportRef.current = viewport;
    // Measurement cache is mutated in place; revision intentionally invalidates its derived offsets.
    // eslint-disable-next-line react-hooks/exhaustive-deps
    const offsets = useMemo(() => virtualOffsets(messages, heights.current), [messages, revision]);
    const refresh = useCallback(() => {
        const element = scrollRef.current, list = root.current, current = geometry.current;
        if (!element || !list || !current?.messages.length) return;
        const origin = list.getBoundingClientRect().top - element.getBoundingClientRect().top + element.scrollTop;
        const top = Math.max(0, element.scrollTop - origin), index = indexAt(current.offsets, top);
        anchor.current = {id: current.messages[index]?.id, start: current.offsets[index]};
        setViewport(old => Math.abs(old.top - top) < 1 && old.height === element.clientHeight ? old : {top, height: element.clientHeight});
    }, [scrollRef]);
    useLayoutEffect(() => {
        if (captureRef) captureRef.current = refresh;
        return () => { if (captureRef) captureRef.current = null; };
    }, [captureRef, refresh]);
    // The scroll element belongs to an ancestor. Its ref is attached after child
    // layout effects on mount; subscribing there can silently skip both listeners.
    useEffect(() => {
        const element = scrollRef.current; if (!element) return;
        // Capture native scroll immediately: a stream commit can run before the next animation frame.
        const scroll = refresh;
        element.addEventListener("scroll", scroll, {passive: true});
        const resize = new ResizeObserver(() => {
            refresh();
            // Batch reflow measurements only on container resize, not on every scroll/commit.
            let changed = false;
            for (const row of root.current?.children || []) {
                const id = row.dataset.messageId, height = row.getBoundingClientRect().height;
                if (id && height > 0 && Math.abs((heights.current.get(id) || 220) - height) >= 1) {
                    heights.current.set(id, height); changed = true;
                }
            }
            if (changed) setRevision(value => value + 1);
        });
        resize.observe(element); refresh();
        return () => { element.removeEventListener("scroll", scroll); resize.disconnect(); };
    }, [scrollRef, refresh]);
    useLayoutEffect(() => {
        geometry.current = {messages, offsets};
        const element = scrollRef.current, list = root.current;
        if (!element || !list) return;
        const previousTop = viewportRef.current.top;
        const top = anchoredVirtualTop(anchor.current, messages, offsets, previousTop);
        const shift = top - previousTop;
        if (isFollowing()) followOutput(!initialized.current);
        else if (Math.abs(shift) >= 1) {
            const origin = list.getBoundingClientRect().top - element.getBoundingClientRect().top + element.scrollTop;
            element.scrollTop = origin + top;
        }
        if (messages.length) initialized.current = true;
        refresh();
    }, [messages, offsets, isFollowing, followOutput, refresh, scrollRef]);
    // Keep the reader's row mounted during a height correction. Using the old top here
    // can replace it with a spacer, clamp native scrollTop and strand the viewport blank.
    const rangeTop = anchoredVirtualTop(anchor.current, messages, offsets, viewport.top);
    const range = virtualRange(offsets, rangeTop, viewport.height);
    const measure = useCallback((id, height) => {
        if (height <= 0 || Math.abs((heights.current.get(id) || 220) - height) < 1) return;
        heights.current.set(id, height); setRevision(value => value + 1);
    }, []);
    return <div ref={root} className="virtual-messages">
        <div aria-hidden="true" style={{height: offsets[range.start]}}/>
        {messages.slice(range.start, range.end).map(message => <MeasuredRow key={message.id} id={message.id} measure={measure}>
            <MessageScope id={message.id}>{renderMessage(message)}</MessageScope>
        </MeasuredRow>)}
        <div aria-hidden="true" style={{height: offsets.at(-1) - offsets[range.end]}}/>
    </div>;
});
function MeasuredRow({id, measure, children}) {
    const ref = useRef(null);
    useLayoutEffect(() => {
        const element = ref.current;
        const report = () => measure(id, element.getBoundingClientRect().height);
        const observer = new ResizeObserver(report); observer.observe(element); report();
        return () => observer.disconnect();
    }, [id, measure]);
    return <div ref={ref} className="virtual-message-row" data-message-id={id}>{children}</div>;
}
