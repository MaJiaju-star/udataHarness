import {useEffect, useMemo, useRef, useState} from "react";
import {FileCode2, Filter, FolderSearch2, RefreshCw, Search, X} from "lucide-react";

const typePresets = [
    {label: "Java", value: "java"},
    {label: "JS/TS", value: "js,jsx,ts,tsx"},
    {label: "JSON", value: "json"},
    {label: "HTML/CSS", value: "html,htm,css,scss,less"},
    {label: "Markdown", value: "md,markdown"},
    {label: "配置", value: "yml,yaml,xml,properties,toml"}
];

function extensionsOf(value) {
    return [...new Set(value.split(/[\s,;]+/)
        .map(item => item.trim().replace(/^\./, "").toLowerCase())
        .filter(Boolean))];
}

function Highlight({text = "", keyword = ""}) {
    const index = text.toLowerCase().indexOf(keyword.toLowerCase());
    if (!keyword || index < 0) return text;
    return <>{text.slice(0, index)}<mark>{text.slice(index, index + keyword.length)}</mark>{text.slice(index + keyword.length)}</>;
}

export default function GlobalSearchDialog({api, initialMode = "content", onClose, onOpenFile}) {
    const [mode, setMode] = useState(initialMode);
    const [keyword, setKeyword] = useState("");
    const [typeFilter, setTypeFilter] = useState("");
    const [response, setResponse] = useState(null);
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState("");
    const [activeIndex, setActiveIndex] = useState(0);
    const inputRef = useRef(null);
    const resultsRef = useRef(null);
    const extensions = useMemo(() => extensionsOf(typeFilter), [typeFilter]);
    const entries = useMemo(() => (response?.items || []).flatMap(item =>
        mode === "content"
            ? (item.matches || []).map(match => ({item, match}))
            : [{item, match: null}]), [response, mode]);

    useEffect(() => {
        inputRef.current?.focus();
    }, []);
    useEffect(() => {
        resultsRef.current?.querySelector(`[data-search-index="${activeIndex}"]`)
            ?.scrollIntoView({block: "nearest"});
    }, [activeIndex]);

    useEffect(() => {
        setActiveIndex(0);
        if (!keyword.trim()) {
            setResponse(null);
            setError("");
            return undefined;
        }
        const controller = new AbortController();
        const timer = window.setTimeout(() => {
            setLoading(true);
            setError("");
            api("/api/files/search", {
                method: "POST",
                signal: controller.signal,
                headers: {"Content-Type": "application/x-www-form-urlencoded"},
                body: new URLSearchParams({
                    keyword: keyword.trim(),
                    mode,
                    extensions: (extensions || []).join(","),
                    maxResults: 500
                }).toString()
            }).then(setResponse)
                .catch(reason => {
                    if (reason.name !== "AbortError") setError(reason.message);
                })
                .finally(() => {
                    if (!controller.signal.aborted) setLoading(false);
                });
        }, 250);
        return () => {
            window.clearTimeout(timer);
            controller.abort();
        };
    }, [api, keyword, mode, extensions]);

    async function openEntry(entry) {
        if (!entry) return;
        await onOpenFile(entry.item.path, entry.match);
        onClose();
    }

    function onKeyDown(event) {
        if (event.key === "Escape") {
            event.preventDefault();
            onClose();
            return;
        }
        if (entries.length === 0) return;
        if (event.key === "ArrowDown" || event.key === "ArrowUp") {
            event.preventDefault();
            const step = event.key === "ArrowDown" ? 1 : -1;
            setActiveIndex(index => (index + step + entries.length) % entries.length);
        }
        if (event.key === "Enter") {
            event.preventDefault();
            openEntry(entries[activeIndex]);
        }
    }

    return <div className="modal-backdrop global-search-backdrop" role="presentation"
                onMouseDown={event => event.target === event.currentTarget && onClose()}>
        <section className="global-search-dialog" role="dialog" aria-modal="true"
                 aria-labelledby="global-search-title" onKeyDown={onKeyDown}>
            <header>
                <div className="global-search-heading">
                    <span><FolderSearch2 size={18}/></span>
                    <div><h2 id="global-search-title">全局搜索</h2><small>在当前工作区中查找文件和代码</small></div>
                </div>
                <button className="icon-button" onClick={onClose} aria-label="关闭全局搜索"><X size={17}/></button>
            </header>
            <div className="global-search-controls">
                <div className="global-search-modes" role="tablist" aria-label="检索模式">
                    <button role="tab" aria-selected={mode === "name"}
                            className={mode === "name" ? "active" : ""} onClick={() => setMode("name")}>文件名称</button>
                    <button role="tab" aria-selected={mode === "content"}
                            className={mode === "content" ? "active" : ""} onClick={() => setMode("content")}>文件内容</button>
                </div>
                <label className="global-search-input">
                    <Search size={18}/>
                    <input ref={inputRef} value={keyword}
                           placeholder={mode === "content" ? "输入要查找的代码或文本" : "输入文件名或路径"}
                           onChange={event => setKeyword(event.target.value)}/>
                    {loading && <RefreshCw className="spin" size={15}/>}<kbd>Esc</kbd>
                </label>
                <div className="global-search-types">
                    <span><Filter size={13}/>文件类型</span>
                    <button className={!typeFilter ? "active" : ""} onClick={() => setTypeFilter("")}>全部</button>
                    {typePresets.map(item => <button key={item.label}
                        className={typeFilter === item.value ? "active" : ""}
                        onClick={() => setTypeFilter(typeFilter === item.value ? "" : item.value)}>{item.label}</button>)}
                    <input value={typeFilter} placeholder="java,xml,yml"
                           aria-label="自定义文件扩展名" onChange={event => setTypeFilter(event.target.value)}/>
                </div>
            </div>
            <div className="global-search-summary" aria-live="polite">
                {response ? <span>{response.totalFiles} 个文件 · {response.totalMatches} 处匹配
                    {response.truncated && " · 结果已截断"}</span> : <span>输入内容开始检索</span>}
                <small>↑↓ 选择 · Enter 打开 · Ctrl+Shift+F 内容检索 · Ctrl+P 文件检索</small>
            </div>
            <div ref={resultsRef} className="global-search-results" role="listbox" aria-busy={loading}>
                {error && <div className="global-search-state error"><X size={20}/><b>检索失败</b><span>{error}</span></div>}
                {!error && keyword.trim() && !loading && response?.items?.length === 0 &&
                    <div className="global-search-state"><Search size={22}/><b>没有找到匹配项</b><span>尝试更换关键词或清除文件类型筛选</span></div>}
                {!error && !keyword.trim() && <div className="global-search-state"><FolderSearch2 size={25}/><b>搜索整个工作区</b><span>隐藏文件和目录也会参与检索</span></div>}
                {!error && (response?.items || []).map(item => <div className="search-result-file" key={item.path}>
                    <div className="search-result-file-head"><FileCode2 size={14}/><b>{item.name}</b><span>{item.path}</span>
                        {mode === "content" && <i>{item.matches?.length || 0}</i>}</div>
                    {mode === "name" ? (() => {
                        const index = entries.findIndex(entry => entry.item.path === item.path);
                        return <button role="option" aria-selected={index === activeIndex}
                            data-search-index={index}
                            className={index === activeIndex ? "active" : ""}
                            onMouseEnter={() => setActiveIndex(index)} onClick={() => openEntry(entries[index])}>
                            <span className="search-line-number">文件</span><code><Highlight text={item.path} keyword={keyword}/></code>
                        </button>;
                    })() : (item.matches || []).map(match => {
                        const index = entries.findIndex(entry => entry.item.path === item.path
                            && entry.match.line === match.line && entry.match.column === match.column);
                        return <button key={`${match.line}-${match.column}`} role="option"
                            data-search-index={index}
                            aria-selected={index === activeIndex} className={index === activeIndex ? "active" : ""}
                            onMouseEnter={() => setActiveIndex(index)} onClick={() => openEntry(entries[index])}>
                            <span className="search-line-number">{match.line}:{match.column}</span>
                            <code><Highlight text={match.preview} keyword={keyword}/></code>
                        </button>;
                    })}
                </div>)}
            </div>
        </section>
    </div>;
}
