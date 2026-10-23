import {useCallback, useEffect, useState} from "react";
import {Brain, Plus, RefreshCw, Save} from "lucide-react";
import {usePageDialog} from "./usePageDialog.jsx";
import "./modelSettings.css";
import "./memorySettings.css";

const empty = scope => ({id: "", key: "", content: "", scope, pinned: false});
const labels = {active: "生效", disabled: "已停用", deleted: "回收站"};
const actions = {create: "新增", update: "修改", active: "启用 / 恢复", disabled: "停用", deleted: "移入回收站", settings: "开关与预算"};
const time = value => new Date(value).toLocaleString("zh-CN");

export default function MemorySettings({api, notify, currentWorkspace}) {
    const [projects, setProjects] = useState([]), [workspace, setWorkspace] = useState("");
    const [data, setData] = useState(null), [tab, setTab] = useState("project"), [query, setQuery] = useState("");
    const [status, setStatus] = useState("active"), [form, setForm] = useState(empty("project"));
    const [dirty, setDirty] = useState(false), [busy, setBusy] = useState(false), [error, setError] = useState("");
    const [budget, setBudget] = useState(1000);
    const {confirm, dialog} = usePageDialog();
    useEffect(() => {
        let alive = true;
        api("/api/workspaces").then(rows => {
            if (!alive) return;
            setProjects(rows);
            setWorkspace((rows.find(row => row.path === currentWorkspace) || rows.find(row => row.active) || rows[0])?.workspaceId || "");
        }).catch(err => { if (alive) setError(err.message); });
        return () => { alive = false; };
    }, [api, currentWorkspace]);
    const load = useCallback(async () => {
        const result = await api(`/api/memory?workspaceId=${encodeURIComponent(workspace)}`);
        setData(result); setBudget(result.budget); return result;
    }, [api, workspace]);
    useEffect(() => {
        if (!workspace) return;
        let alive = true;
        api(`/api/memory?workspaceId=${encodeURIComponent(workspace)}`).then(result => {
            if (alive) { setData(result); setBudget(result.budget); setError(""); }
        }).catch(err => { if (alive) setError(err.message); });
        return () => { alive = false; };
    }, [api, workspace]);
    const run = async work => { setBusy(true); setError(""); try { await work(); await load(); } catch (err) { setError(err.message); } finally { setBusy(false); } };
    const leave = async () => !dirty || await confirm("记忆尚未保存，放弃这些修改吗？");
    const select = async row => { if (await leave()) { setForm({...row}); setDirty(false); } };
    const field = (key, value) => { setForm(old => ({...old, [key]: value})); setDirty(true); };
    const updateState = row => next => run(async () => {
        if (next === "deleted" && !await confirm(`将“${row.key}”移入回收站？之后可以恢复。`)) return;
        await api(`/api/memory/state?workspaceId=${encodeURIComponent(workspace)}`, {method: "POST", body: JSON.stringify({id: row.id, revision: row.revision, status: next})});
        setForm(empty(tab === "global" ? "global" : "project")); setDirty(false);
    });
    const entries = (data?.entries || []).filter(row => row.scope === tab && row.status === status && `${row.key} ${row.content}`.toLowerCase().includes(query.toLowerCase()));
    return <div className="panel-page model-settings memory-settings">
        <header className="panel-header"><div><span className="eyebrow">LONG-TERM MEMORY</span><h2>心智记忆</h2><p>全局记忆属于当前用户；项目记忆仅在选定工作区生效，跨会话保留。</p></div>
            <div className="panel-actions"><button disabled={busy || !workspace} onClick={() => run(load)}><RefreshCw size={15}/>刷新</button></div></header>
        {error && <div className="model-banner error" role="alert">{error}</div>}
        <section className="memory-controls">
            <label className="model-checkbox"><input type="checkbox" checked={Boolean(data?.enabled)} disabled={busy || !data} onChange={event => {
                const enabled = event.target.checked;
                run(async () => { await api("/api/memory/settings", {method: "POST", body: JSON.stringify({enabled, budget: data.budget})}); notify(enabled ? "心智记忆已开启，下一轮生效" : "心智记忆已关闭，已有条目保留"); });
            }}/><b>开启心智记忆</b><span className="memory-state">{data?.enabled ? "已开启" : "已关闭（默认）"}</span></label>
            <p>{data?.enabled ? "下一轮自动召回相关记忆。明确说“记住……”可写入项目记忆；全局写入请说明“所有项目”或“全局”。" : "停止自动召回和对话写入，已有记忆仍可手动管理。"} 正在执行的任务保持本轮设置；关闭不会撤回已进入旧会话上下文的内容。</p>
            <div className="memory-filter"><label>项目<select value={workspace} disabled={busy} onChange={async event => {
                const value = event.target.value; if (await leave()) { setWorkspace(value); setData(null); setForm(empty(tab === "global" ? "global" : "project")); setDirty(false); }
            }}>{projects.map(row => <option key={row.workspaceId} value={row.workspaceId}>{row.name} · {row.path}</option>)}</select></label>
                <label>召回预算（估算 Token）<input type="number" min={256} max={4000} value={budget} onChange={event => setBudget(Number(event.target.value))}/></label>
                <button disabled={busy || !data || budget === data.budget || budget < 256 || budget > 4000} onClick={() => run(async () => {
                    await api("/api/memory/settings", {method: "POST", body: JSON.stringify({enabled: data.enabled, budget})}); notify("召回预算已保存");
                })}>保存预算</button></div>
        </section>
        <nav className="memory-tabs" aria-label="记忆分类">{[["global", "全局记忆"], ["project", "项目记忆"], ["changes", "变更记录"]].map(([id, label]) => <button key={id} className={tab === id ? "primary" : ""} disabled={busy} onClick={async () => {
            if (await leave()) { setTab(id); setForm(empty(id === "global" ? "global" : "project")); setDirty(false); }
        }}>{label}</button>)}</nav>
        {tab === "changes" ? <section className="memory-log">{(data?.changes || []).map((row, index) => <article key={index}><b>{actions[row.action] || row.action} · {row.key || "心智记忆设置"}</b><small>{time(row.updatedAt)} · {row.scope === "global" ? "全局" : "项目"} · {row.source === "manual" ? "后台录入" : row.source || "后台设置"}</small>
            <p>{row.content}</p>{row.before && <details><summary>修改前内容</summary><p>{row.before.content}</p></details>}</article>)}{!data?.changes?.length && <p>暂无变更记录，最多保留最近 300 条。</p>}</section> : <>
            <div className="memory-filter"><input aria-label="搜索记忆" placeholder="搜索主题或内容" value={query} onChange={event => setQuery(event.target.value)}/><select aria-label="记忆状态" value={status} onChange={event => setStatus(event.target.value)}>{Object.entries(labels).map(([key, label]) => <option key={key} value={key}>{label}</option>)}</select><button disabled={busy || !data} onClick={() => select(empty(tab))}><Plus size={14}/>新增记忆</button></div>
            <div className="model-layout"><aside className="model-catalog">{entries.map(row => <div className={`model-entry ${row.id === form.id ? "selected" : ""}`} key={row.id}><button className="model-select" disabled={busy} onClick={() => select(row)}><Brain size={16}/><span><b>{row.pinned ? "置顶 · " : ""}{row.key}</b><small>{row.content.slice(0, 95)}</small><em>{labels[row.status]} · {time(row.updatedAt)}</em></span></button></div>)}{!entries.length && <p>{data ? "暂无匹配记忆。可以手动新增，或开启后在对话中说“记住……”。" : "正在加载记忆……"}</p>}</aside>
                <form className="model-editor" onSubmit={event => { event.preventDefault(); run(async () => {
                    const saved = await api(`/api/memory?workspaceId=${encodeURIComponent(workspace)}`, {method: "POST", body: JSON.stringify(form)}); setForm(saved); setDirty(false); notify("记忆已保存，下一轮对话生效");
                }); }}><div className="model-editor-heading"><h3>{form.id ? "编辑记忆" : "新增记忆"}</h3>{dirty && <small>尚未保存</small>}</div>
                    <fieldset disabled={busy || !data || form.status === "deleted"}><label>作用域<select value={form.scope} onChange={event => field("scope", event.target.value)}><option value="global">全局：当前用户的所有项目</option><option value="project">项目：当前选定工作区</option></select><small>修改作用域会移动条目；同一主题 key 的项目记忆优先于全局记忆。</small></label>
                        <label>主题 key<input required maxLength={120} value={form.key} placeholder="例如 token-accounting 或 response-language" onChange={event => field("key", event.target.value)}/></label>
                        <label>记忆内容<textarea required rows={7} maxLength={2000} value={form.content} placeholder="保存明确、稳定、可独立理解的约定。每条最多 2000 字符。" onChange={event => field("content", event.target.value)}/></label>
                        <label className="model-checkbox"><input type="checkbox" checked={Boolean(form.pinned)} onChange={event => field("pinned", event.target.checked)}/>置顶：优先纳入召回预算</label>
                        {form.id && <small className="model-hint">来源：{form.source === "manual" ? "后台录入" : form.source} · 版本 {form.revision} · {labels[form.status]}</small>}
                    </fieldset><footer className="model-editor-actions">{form.id && <>
                        {form.status !== "deleted" && <button type="button" className="danger" disabled={busy || dirty} onClick={() => updateState(form)("deleted")}>移入回收站</button>}
                        <button type="button" disabled={busy || dirty} onClick={() => updateState(form)(form.status === "active" ? "disabled" : "active")}>{form.status === "active" ? "停用" : "恢复并启用"}</button></>}
                        <button className="primary" disabled={busy || !data || !form.key.trim() || !form.content.trim() || form.status === "deleted"}><Save size={14}/>保存记忆</button></footer>
                </form></div></>}
        {dialog}
    </div>;
}
