import {useCallback, useEffect, useRef, useState} from "react";
import {createPortal} from "react-dom";
import {Clock3, Plus, Pause, Play, Pencil, Trash2, X, CircleStop, ArrowUpRight, RefreshCw} from "lucide-react";
import {usePageDialog} from "./usePageDialog.jsx";
import "./scheduledTasks.css";

const statuses = {idle: "尚未执行", queued: "等待会话空闲", deferred: "会话繁忙，已延后", running: "运行中", waiting_approval: "等待审批", succeeded: "已完成", failed: "执行失败", cancelled: "已停止", interrupted: "执行中断"};
const kinds = {once: "单次", daily: "每天", weekly: "每周", interval: "固定间隔"};
const stamp = (value, zone) => value ? new Date(value).toLocaleString("zh-CN", {timeZone: zone || "Asia/Shanghai", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit"}) : "—";
const rule = task => task.kind === "once" ? `${task.at?.replace("T", " ")}`
    : task.kind === "interval" ? `每 ${task.intervalMinutes} 分钟`
        : `${task.kind === "weekly" ? `每周${"一二三四五六日"[Number(task.weekday) - 1]}` : "每天"} ${task.time}`;
function initial(prompt) {
    const date = new Date(Date.now() + 5 * 60000);
    const parts = Object.fromEntries(new Intl.DateTimeFormat("en-GB", {timeZone: "Asia/Shanghai", year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", hourCycle: "h23"})
        .formatToParts(date).map(part => [part.type, part.value]));
    return {name: "", prompt, kind: "once", timezone: "Asia/Shanghai", at: `${parts.year}-${parts.month}-${parts.day}T${parts.hour}:${parts.minute}`,
        time: "09:00", weekday: 1, intervalMinutes: 60, enabled: true, model: ""};
}

export default function ScheduledTasks({api, sessionId, prompt = "", models = [], onViewConversation}) {
    const [data, setData] = useState({tasks: [], runs: []});
    const [open, setOpen] = useState(false);
    const [form, setForm] = useState(null);
    const [error, setError] = useState("");
    const [busy, setBusy] = useState(false);
    const [loading, setLoading] = useState(true);
    const {confirm, dialog} = usePageDialog();
    const load = useCallback(async () => {
        const value = await api(`/api/automations?sessionId=${encodeURIComponent(sessionId)}`);
        setData(value); setLoading(false);
    }, [api, sessionId]);
    useEffect(() => {
        let alive = true, pending = false;
        const refresh = async () => {
            if (pending) return; pending = true;
            try { const value = await api(`/api/automations?sessionId=${encodeURIComponent(sessionId)}`); if (alive) { setData(value); setLoading(false); } }
            catch (err) { if (alive) { setError(err.message); setLoading(false); } }
            finally { pending = false; }
        };
        refresh(); const timer = setInterval(() => { if (!document.hidden) refresh(); }, 3000);
        return () => { alive = false; clearInterval(timer); };
    }, [api, sessionId]);
    const act = async action => {
        setBusy(true); setError("");
        try { await action(); await load(); } catch (err) { setError(err.message); } finally { setBusy(false); }
    };
    const view = () => { setOpen(false); onViewConversation?.(); };
    const field = (key, value) => setForm(old => ({...old, [key]: value}));
    return <div className="scheduled-tasks">
        <div className="schedule-toolbar">
            <button type="button" className="schedule-launch" onClick={() => { setOpen(true); setForm(null); }}>
                <Clock3 size={14}/>定时任务{data.tasks.length > 0 && <span>{data.tasks.length}</span>}
            </button>
            <small>绑定本对话 · 由后台执行</small>
        </div>
        {data.tasks.length > 0 && <div className="schedule-strip">{data.tasks.slice(0, 3).map(task => <button type="button" className="schedule-mini" key={task.id}
            onClick={() => { setOpen(true); setForm(null); }}>
            <Clock3 size={14}/><span><b>{task.name}</b><small>{task.activeRunId ? statuses[task.lastStatus] : task.pendingAt ? "等待会话空闲" : task.enabled ? `下次 ${stamp(task.nextRunAt, task.timezone)}` : task.kind === "once" && task.lastStatus === "succeeded" ? "已完成" : "已暂停"}</small></span>
        </button>)}</div>}
        {open && createPortal(<ScheduleDialog onClose={() => setOpen(false)} busy={busy} contentKey={form ? form.id || "new" : "list"}>
            <header><div><span className="eyebrow">CONVERSATION AUTOMATIONS</span><h2 id="schedule-dialog-title">对话定时任务</h2><p>到点发送执行指令，沿用本对话的上下文、工作区和工具权限。</p></div>
                <button type="button" aria-label="关闭定时任务" onClick={() => setOpen(false)}><X size={19}/></button></header>
            <div className="schedule-note">关闭网页仍会执行；电脑和 Harness 须保持运行。会话繁忙或等待审批时延后执行，最多保留一次待执行任务。</div>
            {error && <div role="alert" className="schedule-error">{error}</div>}
            {form ? <form className="schedule-form" onSubmit={event => { event.preventDefault(); act(async () => {
                await api("/api/automations", {method: "POST", body: JSON.stringify({...form, sessionId})}); setForm(null);
            }); }}>
                <h3>{form.id ? "编辑任务" : "新建任务"}</h3>
                <fieldset disabled={busy}>
                    <label>任务名称<input required maxLength={80} value={form.name} onChange={event => field("name", event.target.value)} placeholder="例如：每日检查测试结果"/></label>
                    <label>执行指令<textarea required maxLength={50000} rows={4} value={form.prompt} onChange={event => field("prompt", event.target.value)} placeholder="明确描述到点后需要 Agent 完成的工作"/></label>
                    <div className="schedule-form-grid">
                        <label>执行频率<select value={form.kind} onChange={event => field("kind", event.target.value)}>{Object.entries(kinds).map(([key, name]) => <option key={key} value={key}>{name}</option>)}</select></label>
                        <label>时区<input required value={form.timezone} onChange={event => field("timezone", event.target.value)} list="schedule-timezones"/><datalist id="schedule-timezones"><option value="Asia/Shanghai"/><option value="UTC"/><option value="America/New_York"/></datalist></label>
                        {form.kind === "once" && <label>执行日期和时间<input required type="datetime-local" value={form.at} onChange={event => field("at", event.target.value)}/></label>}
                        {(form.kind === "daily" || form.kind === "weekly") && <label>执行时间<input required type="time" value={form.time} onChange={event => field("time", event.target.value)}/></label>}
                        {form.kind === "weekly" && <label>星期<select value={form.weekday} onChange={event => field("weekday", event.target.value)}>{[1, 2, 3, 4, 5, 6, 7].map(day => <option key={day} value={day}>星期{"一二三四五六日"[day - 1]}</option>)}</select></label>}
                        {form.kind === "interval" && <label>间隔（分钟）<input required type="number" min={1} max={525600} step={1} value={form.intervalMinutes} onChange={event => field("intervalMinutes", event.target.value)}/></label>}
                        <label>执行模型<select value={form.model} onChange={event => field("model", event.target.value)}><option value="">跟随会话模型</option>{form.model && !models.some(model => model.name === form.model) && <option value={form.model}>原模型不可用：{form.model}</option>}{models.map(model => <option key={model.name} value={model.name}>{model.label || model.name}</option>)}</select></label>
                    </div>
                    <label className="schedule-checkbox"><input type="checkbox" checked={form.enabled} onChange={event => field("enabled", event.target.checked)}/>启用任务</label>
                </fieldset>
                <footer><button type="button" disabled={busy} onClick={() => setForm(null)}>取消</button><button className="schedule-primary" type="submit" disabled={busy}>{busy ? "保存中…" : "保存任务"}</button></footer>
            </form> : <>
                <div className="schedule-section-heading"><h3>任务计划</h3><div><button type="button" disabled={busy} onClick={() => act(load)} aria-label="刷新定时任务"><RefreshCw size={14}/></button><button className="schedule-primary" type="button" onClick={() => { setForm(initial(prompt)); setError(""); }}><Plus size={14}/>{prompt.trim() ? "将输入框内容设为任务" : "新建任务"}</button></div></div>
                {loading && <p role="status">正在加载…</p>}
                {!loading && !data.tasks.length && <div className="schedule-empty"><Clock3 size={28}/><b>让这段对话按时继续</b><p>安排一次执行，或每天、每周自动检查项目。执行结果会保留在原对话中。</p></div>}
                <div className="schedule-task-list">{data.tasks.map(task => <article className="schedule-card" key={task.id}>
                    <div className="schedule-card-title"><b>{task.name}</b><span className={`schedule-status ${task.lastStatus}`}>{task.pendingAt ? "等待会话空闲" : task.activeRunId ? statuses[task.lastStatus] : task.enabled ? "已启用" : task.kind === "once" && task.lastStatus === "succeeded" ? "已完成" : "已暂停"}</span></div>
                    <p>{rule(task)} <span>· {task.timezone}</span></p><small>下次执行：{task.enabled ? stamp(task.nextRunAt, task.timezone) : "—"} · 最近：{statuses[task.lastStatus] || "尚未执行"}</small>
                    <details><summary>执行指令</summary><pre>{task.prompt}</pre></details>
                    {task.error && <p className="schedule-error">{task.error}</p>}
                    <div className="schedule-card-actions">
                        <button type="button" disabled={busy || !!task.activeRunId || !!task.pendingAt} onClick={() => act(() => api(`/api/automations/run?id=${encodeURIComponent(task.id)}`, {method: "POST"}))}><Play size={13}/>立即执行</button>
                        <button type="button" disabled={busy} onClick={() => act(() => api(`/api/automations/state?id=${encodeURIComponent(task.id)}&enabled=${!task.enabled}`, {method: "POST"}))}>{task.enabled ? <Pause size={13}/> : <Play size={13}/>} {task.enabled ? "暂停计划" : "启用计划"}</button>
                        <button type="button" disabled={busy || !!task.activeRunId} onClick={() => { setForm({...initial(""), ...task}); setError(""); }}><Pencil size={13}/>编辑</button>
                        <button type="button" disabled={busy || !!task.activeRunId} onClick={() => act(async () => { if (await confirm(`删除“${task.name}”？执行记录仍会保留。`)) await api(`/api/automations?id=${encodeURIComponent(task.id)}`, {method: "DELETE"}); })}><Trash2 size={13}/>删除</button>
                    </div>
                </article>)}</div>
                <div className="schedule-section-heading"><h3>执行记录 <small>最近 {Math.min(data.runs.length, 20)} 次</small></h3><button type="button" onClick={view}>查看对话 <ArrowUpRight size={14}/></button></div>
                {!data.runs.length && <p className="schedule-muted">尚无执行记录。</p>}
                <div className="schedule-run-list">{data.runs.slice(0, 20).map(run => <article key={run.id} className="schedule-run">
                    <div><b>{run.name}</b><small>{stamp(run.startedAt, run.timezone)} · {run.trigger === "manual" ? "手动触发" : "定时触发"}{run.endedAt ? ` · ${Math.max(0, Math.round((run.endedAt - run.startedAt) / 1000))} 秒` : ""}</small>{run.error && <p className="schedule-error">{run.error}</p>}</div>
                    <span className={`schedule-status ${run.status}`}>{statuses[run.status]}</span>
                    {run.status === "running" && <button type="button" disabled={busy || run.stopRequested} aria-label={`停止 ${run.name}`} onClick={() => act(() => api(`/api/automations/stop?runId=${encodeURIComponent(run.id)}`, {method: "POST"}))}><CircleStop size={15}/>{run.stopRequested ? "停止中" : "停止"}</button>}
                    {run.status === "waiting_approval" && <button type="button" onClick={view}>返回对话审批</button>}
                </article>)}</div>
            </>}
        </ScheduleDialog>, document.body)}
        {dialog}
    </div>;
}

function ScheduleDialog({children, onClose, busy, contentKey}) {
    const panel = useRef(null);
    useEffect(() => { const previous = document.activeElement; panel.current?.focus(); return () => { if (previous?.isConnected) previous.focus(); }; }, []);
    useEffect(() => { if (panel.current) panel.current.scrollTop = 0; }, [contentKey]);
    return <div className="modal-backdrop schedule-backdrop" onMouseDown={event => { if (event.target === event.currentTarget && !busy) onClose(); }}>
        <section ref={panel} className="schedule-dialog" role="dialog" aria-modal="true" aria-labelledby="schedule-dialog-title" tabIndex={-1} onKeyDown={event => {
            if (event.key === "Escape" && !busy) { event.preventDefault(); onClose(); }
            if (event.key === "Tab") {
                const elements = [...panel.current.querySelectorAll("button:not(:disabled), input:not(:disabled), textarea:not(:disabled), select:not(:disabled), summary")];
                const first = elements[0], last = elements.at(-1);
                if (event.shiftKey && (document.activeElement === first || document.activeElement === panel.current)) { event.preventDefault(); last?.focus(); }
                else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus(); }
            }
            event.stopPropagation();
        }}>{children}</section>
    </div>;
}
