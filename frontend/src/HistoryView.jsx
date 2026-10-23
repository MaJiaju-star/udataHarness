import {createContext, useCallback, useContext, useEffect, useLayoutEffect, useMemo, useRef, useState, useSyncExternalStore} from "react";
import {createHistoryCache} from "./historyPaging.js";

const HistoryContext = createContext(null), Scope = createContext("");
export function HistoryProvider({api, sessionId, children}) {
    const value = useMemo(() => ({api, sessionId, chunks: createHistoryCache(), disclosures: new Map()}), [api, sessionId]);
    return <HistoryContext.Provider value={value}>{children}</HistoryContext.Provider>;
}
export function MessageScope({id, children}) {
    const parent = useContext(Scope);
    return <Scope.Provider value={parent ? `${parent}/${id}` : id}>{children}</Scope.Provider>;
}
export function useDisclosure(id, initial, phase) {
    const history = useContext(HistoryContext), scope = useContext(Scope), key = `${scope}:${id}`;
    const localCache = useRef(new Map()), disclosures = history?.disclosures || localCache.current;
    const cached = disclosures.get(key);
    const [open, local] = useState(() => cached && (phase === undefined || cached.phase === phase) ? cached.open : initial);
    const setOpen = useCallback(value => local(old => {
        const next = typeof value === "function" ? value(old) : value;
        disclosures.set(key, {open: next, phase}); return next;
    }), [disclosures, key, phase]);
    useLayoutEffect(() => {
        const previous = disclosures.get(key);
        if (phase !== undefined && previous && previous.phase !== phase) setOpen(initial);
        else if (!previous) disclosures.set(key, {open, phase});
    }, [disclosures, key, phase, initial, setOpen, open]);
    return [open, setOpen];
}
export function useHistoryChunks(reference, initial = "", automatic = false) {
    const history = useContext(HistoryContext), key = JSON.stringify(reference || {});
    const fallback = useMemo(() => ({content: initial, nextOffset: reference?.offset || 0, hasMore: Boolean(reference), loading: false, error: ""}), [initial, reference]);
    const subscribe = useCallback(listener => history && reference ? history.chunks.subscribe(key, listener) : () => {}, [history, reference, key]);
    const snapshot = useCallback(() => history && reference ? history.chunks.get(key, fallback) : fallback, [history, reference, key, fallback]);
    const value = useSyncExternalStore(subscribe, snapshot, snapshot);
    const load = useCallback(() => {
        if (!history || !reference) return;
        return history.chunks.load(key, offset => {
            const params = new URLSearchParams({sessionId: history.sessionId, messageIndex: reference.messageIndex, offset, limit: 8000});
            if (reference.toolIndex != null) params.set("toolIndex", reference.toolIndex);
            if (reference.part) params.set("part", reference.part);
            return history.api(`/api/sessions/history-content?${params}`);
        });
    }, [history, reference, key]);
    useEffect(() => { if (automatic && reference && value.hasMore && !value.content && !value.loading && !value.error) load(); }, [automatic, reference, value.hasMore, value.content, value.loading, value.error, load]);
    return {...value, load};
}
export function HistoryContent({reference, initial, render}) {
    const value = useHistoryChunks(reference, initial);
    return <>{render(value.content)}<ChunkActions value={value}/></>;
}
export function ChunkActions({value}) {
    return <div className="history-chunk-actions">{value.error && <span role="alert">{value.error}</span>}
        {(value.hasMore || value.error) && <button type="button" disabled={value.loading} onClick={value.load}>{value.loading ? "正在读取…" : value.error ? "重试读取" : "继续读取正文"}</button>}</div>;
}
export function DeferredToolDetails({reference}) {
    const value = useHistoryChunks(reference, "", true);
    return <div>{value.loading && !value.content && <small>正在读取工具详情…</small>}<pre>{value.content}</pre><ChunkActions value={value}/></div>;
}
