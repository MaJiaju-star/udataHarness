import {useEffect, useRef, useState} from "react";
import {ChevronDown, Folder, MoreHorizontal, Plus} from "lucide-react";
import {callIdea} from "./host.js";
import {useWorkspaceStore} from "./workspaceStore.js";

export default function IdeaToolbar({current, running, onCreate, onRename, onDelete, onOpenHistory, onReference, onAttach, onStop, notify, workspace}) {
    const [open, setOpen] = useState(false);
    const root = useRef(null);
    const trigger = useRef(null);
    const referenceTrigger = useRef(null);
    const menu = useRef(null);
    const themeMode = useWorkspaceStore(state => state.themeMode);
    const setThemeMode = useWorkspaceStore(state => state.setThemeMode);
    useEffect(() => {
        if (!open) return;
        menu.current?.querySelector("button:not(:disabled)")?.focus();
        const close = event => { if (!root.current?.contains(event.target)) setOpen(false); };
        window.addEventListener("mousedown", close);
        return () => window.removeEventListener("mousedown", close);
    }, [open]);
    function action(handler) {
        setOpen(false);
        (open === "reference" ? referenceTrigger : trigger).current?.focus();
        Promise.resolve().then(handler).catch(error => notify(error.message));
    }
    async function attach(method) { onAttach(await callIdea(method)); }
    function keys(event) {
        if (event.key === "Escape") { event.preventDefault(); setOpen(false); (open === "reference" ? referenceTrigger : trigger).current?.focus(); }
        if (["ArrowDown", "ArrowUp", "Home", "End"].includes(event.key)) {
            event.preventDefault();
            const items = [...menu.current.querySelectorAll("button:not(:disabled)")];
            const index = items.indexOf(document.activeElement);
            const next = event.key === "Home" ? 0 : event.key === "End" ? items.length - 1
                : (index + (event.key === "ArrowDown" ? 1 : -1) + items.length) % items.length;
            items[next]?.focus();
        }
        event.stopPropagation();
    }
    return <div className="idea-toolbar" ref={root}>
        <div className="idea-toolbar-row">
            <span className="idea-project-icon" title={workspace || "正在连接项目"} aria-label={`项目：${workspace || "正在连接"}`}><Folder size={16}/></span>
            <button className="idea-switch-session" onClick={onOpenHistory} aria-haspopup="dialog"
                title={current ? `当前会话：${current.title}` : "选择或管理会话"}>切换会话</button>
            <button className="idea-reference-trigger" ref={referenceTrigger} disabled={running}
                onClick={() => setOpen(value => value === "reference" ? false : "reference")}
                aria-label="引用" aria-haspopup="menu" aria-expanded={open === "reference"}
                aria-controls={open === "reference" ? "idea-reference-menu" : undefined}>引用<ChevronDown size={12}/></button>
            <button className="idea-toolbar-icon" disabled={running} onClick={onCreate} aria-label="新建会话" title="新建会话"><Plus size={17}/></button>
            <button className="idea-toolbar-icon" ref={trigger} onClick={() => setOpen(value => value === "more" ? false : "more")} aria-label="更多操作" title="更多操作"
                aria-haspopup="menu" aria-expanded={open === "more"} aria-controls={open === "more" ? "idea-more-menu" : undefined}><MoreHorizontal size={18}/></button>
        </div>
        {open && <div ref={menu} id={open === "reference" ? "idea-reference-menu" : "idea-more-menu"} className="idea-more-menu" role="menu" aria-label={open === "reference" ? "引用" : "更多操作"} onKeyDown={keys}
            onBlur={event => { if (!root.current?.contains(event.relatedTarget)) setOpen(false); }}>
            {open === "reference" ? <>
                <button role="menuitem" disabled={running} onClick={() => action(() => onReference("selection"))}>引用选区</button>
                <button role="menuitem" disabled={running} onClick={() => action(() => onReference("file"))}>引用当前文件</button>
                <div className="idea-reference-hint">编辑器中按 Alt+K 快速引用</div>
            </> : <>
            <div className="idea-menu-label">会话</div>
            <button role="menuitem" onClick={() => action(onOpenHistory)}>历史会话 / 清理</button>
            <button role="menuitem" disabled={running || !current} onClick={event => {event.stopPropagation(); action(() => onRename(event, current));}}>重命名会话</button>
            <button role="menuitem" disabled={running || !current} onClick={event => {event.stopPropagation(); action(() => onDelete(event, current));}}>删除当前会话</button>
            {running && <button role="menuitem" onClick={() => action(onStop)}>停止当前任务</button>}
            <div className="idea-menu-label">内容附件</div>
            <button role="menuitem" disabled={running} onClick={() => action(() => attach("getSelection"))}>附加选区内容</button>
            <button role="menuitem" disabled={running} onClick={() => action(() => attach("getCurrentFile"))}>附加文件内容</button>
            <div className="idea-menu-label">外观</div>
            {[['light', '浅色模式'], ['dark', '黑夜模式'], ['system', '跟随系统']].map(([id, label]) =>
                <button key={id} role="menuitemradio" aria-checked={themeMode === id} onClick={() => action(() => setThemeMode(id))}>
                    {label}<span aria-hidden="true">{themeMode === id ? "✓" : ""}</span></button>)}
            <div className="idea-menu-label">连接</div>
            <button role="menuitem" disabled={running} onClick={() => action(() => window.location.reload())}>重新连接</button>
            <button role="menuitem" disabled={running} onClick={() => action(() => callIdea("settings"))}>连接设置</button>
            <button role="menuitem" disabled={running} onClick={() => action(() => callIdea("refreshFiles").then(result => result.warning && notify(result.warning)))}>同步文件</button>
            <button role="menuitem" onClick={() => action(() => callIdea("openWeb"))}>打开网页工作台</button>
            </>}
        </div>}
    </div>;
}
