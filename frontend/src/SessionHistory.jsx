import {useState} from "react";

export default function SessionHistory({sessions, current, running, onSelect, onDelete, onClose, title = "历史会话"}) {
    const [selected, setSelected] = useState([]);
    const [busy, setBusy] = useState(false);
    const available = sessions.filter(item => !item.active);
    const selectedIds = selected.filter(id => available.some(item => item.sessionId === id));
    const allSelected = available.length > 0 && selectedIds.length === available.length;
    async function remove(ids, all = false) {
        if (busy || running) return;
        setBusy(true);
        try { await onDelete(ids, all); } finally { setBusy(false); }
    }
    return <div className="modal-backdrop session-history-backdrop">
        <section className="session-history" role="dialog" aria-modal="true" aria-labelledby="session-history-title">
            <header>
                <div><h2 id="session-history-title">{title}</h2><small>当前工作区 · {sessions.length} 个会话 · 点击名称切换</small></div>
                <button disabled={busy} onClick={onClose} aria-label={`关闭${title}`}>关闭</button>
            </header>
            <div className="session-history-tools">
                <label><input type="checkbox" checked={allSelected} disabled={busy || running || !available.length}
                    onChange={() => setSelected(allSelected ? [] : available.map(item => item.sessionId))}/>全选</label>
                <button disabled={busy || running || !selectedIds.length} onClick={() => remove(selectedIds)}>
                    删除所选（{selectedIds.length}）</button>
                <button className="session-history-danger" disabled={busy || running || !sessions.length || sessions.some(item => item.active)}
                    onClick={() => remove(sessions.map(item => item.sessionId), true)}>清理所有会话</button>
            </div>
            {(running || sessions.some(item => item.active)) && <p className="session-history-tip">运行中的会话不可删除，清理所有会话前请先停止任务。</p>}
            <div className="session-history-list">
                {!sessions.length && <p className="sidebar-empty">这个工作区还没有会话</p>}
                {sessions.map(item => <div key={item.sessionId} className={`session-history-row ${current?.sessionId === item.sessionId ? "active" : ""}`}>
                    <input type="checkbox" aria-label={`选择会话 ${item.title}`} checked={selectedIds.includes(item.sessionId)}
                        disabled={busy || running || item.active} onChange={event => setSelected(ids => event.target.checked
                            ? [...ids, item.sessionId] : ids.filter(id => id !== item.sessionId))}/>
                    <button className="session-history-title" disabled={busy || running} onClick={() => {onSelect(item); onClose();}}>
                        {item.title}{current?.sessionId === item.sessionId && <small>当前会话</small>}{item.active && <small>运行中</small>}</button>
                    <button disabled={busy || running || item.active} aria-label={`删除会话 ${item.title}`}
                        onClick={() => remove([item.sessionId])}>删除</button>
                </div>)}
            </div>
        </section>
    </div>;
}
