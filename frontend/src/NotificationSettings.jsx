import {useCallback, useEffect, useState} from "react";
import {Bell, Plus, RefreshCw, Save, Send, Trash2, X} from "lucide-react";
import {usePageDialog} from "./usePageDialog.jsx";
import "./modelSettings.css";
import "./notificationSettings.css";

const empty = () => ({id: "", name: "", enabled: true, url: "", headers: [], method: "POST", timeoutMs: 10000,
    bodyFormat: "json", body: '{\n  "title": "${workspace.name}：${session.title}",\n  "content": "${result.summary}",\n  "status": "${execution.status}",\n  "requestId": "${execution.id}"\n}',
    statuses: ["succeeded", "failed"], successPath: "", successValue: "0"});
const states = {queued: "待发送", sending: "发送中", succeeded: "发送成功", failed: "发送失败", interrupted: "发送中断", skipped: "已跳过"};
const outcomes = {succeeded: "对话成功", failed: "对话失败", cancelled: "手动停止"};
const descriptions = {"workspace.id": "工作区 ID", "workspace.name": "工作区名称", "session.id": "会话 ID", "session.title": "会话标题",
    "execution.id": "本轮执行 ID", "execution.status": "succeeded / failed / cancelled", "execution.source": "chat / scheduled / test",
    "execution.duration": "执行耗时（不含审批等待）", "execution.durationMs": "执行耗时毫秒数", "result.summary": "最终回答摘要，最多约 1200 字符",
    "result.error": "错误摘要", startedAt: "开始时间（UTC）", finishedAt: "完成时间（UTC）"};
const time = value => value ? new Date(value).toLocaleString("zh-CN") : "—";

export default function NotificationSettings({api, notify}) {
    const [data, setData] = useState(null), [form, setForm] = useState(empty), [error, setError] = useState("");
    const [busy, setBusy] = useState(false), [dirty, setDirty] = useState(false);
    const {confirm, dialog} = usePageDialog();
    const load = useCallback(async () => { const result = await api("/api/notifications"); setData(result); return result; }, [api]);
    useEffect(() => {
        let alive = true, pending = false;
        const refresh = async () => { if (pending) return; pending = true; try { const result = await api("/api/notifications"); if (alive) setData(result); } catch (err) { if (alive) setError(err.message); } finally { pending = false; } };
        refresh(); const timer = setInterval(() => { if (!document.hidden) refresh(); }, 3000);
        return () => { alive = false; clearInterval(timer); };
    }, [api]);
    const field = (key, value) => { setForm(old => ({...old, [key]: value})); setDirty(true); };
    const run = async action => { setBusy(true); setError(""); try { await action(); await load(); } catch (err) { setError(err.message); } finally { setBusy(false); } };
    const leave = async () => !dirty || await confirm("尚有未保存的通知配置，放弃修改并切换吗？");
    const select = async row => { if (await leave()) { setForm({...empty(), ...row}); setDirty(false); setError(""); } };
    const header = (index, key, value) => field("headers", form.headers.map((item, position) => position === index ? {...item, [key]: value} : item));
    const sendTest = () => run(async () => {
        const accepted = await confirm("将使用当前表单调用配置的 HTTP 接口，发送一条包含示例数据的测试通知。继续吗？");
        if (!accepted) return;
        await api("/api/notifications/test", {method: "POST", body: JSON.stringify(form)}); notify("测试通知已排队，请在发送记录中查看结果");
    });
    return <div className="panel-page model-settings notification-settings">
        <header className="panel-header"><div><span className="eyebrow">HTTP NOTIFICATIONS</span><h2>通知配置</h2>
            <p>对话真正结束后，由后台异步调用你的 HTTP 接口。配置属于当前用户，适用于其全部工作区及定时任务。</p></div>
            <div className="panel-actions"><button type="button" disabled={busy} onClick={() => run(load)}><RefreshCw size={15}/>刷新</button>
                <button type="button" disabled={busy} onClick={() => select(empty())}><Plus size={15}/>新增通知</button></div></header>
        {error && <div className="model-banner error" role="alert">{error}</div>}
        <div className="model-layout">
            <aside className="model-catalog" aria-label="通知配置列表">
                {!data && <p>正在加载…</p>}{data && !data.configs.length && <p>还没有通知配置。填写接口并保存即可启用。</p>}
                {data?.configs.map(row => <div key={row.id} className={`model-entry ${row.id === form.id ? "selected" : ""}`}>
                    <button type="button" className="model-select" disabled={busy} onClick={() => select(row)}><Bell size={17}/><span><b>{row.name}</b><small>{row.method} · {row.endpoint}</small><em>{row.enabled ? "已启用" : "已停用"}</em></span></button>
                    <button type="button" className="model-copy" disabled={busy} aria-label={`${row.enabled ? "停用" : "启用"}通知 ${row.name}`} onClick={() => run(async () => {
                        await api(`/api/notifications/state?id=${encodeURIComponent(row.id)}&enabled=${!row.enabled}`, {method: "POST"});
                        if (row.id === form.id) setForm(old => ({...old, enabled: !row.enabled}));
                    })}>{row.enabled ? "停用" : "启用"}</button>
                </div>)}
            </aside>
            <form className="model-editor" onSubmit={event => { event.preventDefault(); run(async () => {
                const id = await api("/api/notifications", {method: "POST", body: JSON.stringify(form)});
                const next = await load(); setForm({...empty(), ...next.configs.find(row => row.id === id)}); setDirty(false); notify("通知配置已保存，无需重启");
            }); }}>
                <div className="model-editor-heading"><h3>{form.id ? "编辑通知" : "新增通知"}</h3>{dirty && <small>有未保存的修改</small>}</div>
                <fieldset disabled={busy}>
                    <div className="notification-form-grid"><label>通知名称<input required maxLength={80} value={form.name} onChange={event => field("name", event.target.value)} placeholder="例如：研发群任务通知"/></label>
                        <label>请求方式<select value={form.method} onChange={event => field("method", event.target.value)}>{["POST", "GET", "PUT", "DELETE"].map(method => <option key={method}>{method}</option>)}</select></label></div>
                    <label>接口 URL<input type="text" required={!form.hasUrl} maxLength={4000} value={form.url} autoComplete="off" spellCheck={false} onChange={event => field("url", event.target.value)} placeholder={form.hasUrl ? `已保存 ${form.endpoint}，留空保留原地址` : "https://gateway.example.com/message/send"}/>
                        <small>支持路径和查询参数中的变量；保存后地址不回显，留空保留。认证信息可放入请求头。</small></label>
                    <div className="notification-header-label"><b>请求头</b><button type="button" onClick={() => field("headers", [...form.headers, {name: "", value: ""}])}><Plus size={13}/>添加请求头</button></div>
                    <div className="notification-headers">{form.headers.map((item, index) => <div className="notification-header-row" key={index}>
                        <input aria-label={`请求头名称 ${index + 1}`} value={item.name} placeholder="Authorization" maxLength={80} onChange={event => header(index, "name", event.target.value)}/>
                        <input type="password" aria-label={`请求头值 ${index + 1}`} autoComplete="new-password" value={item.value} placeholder={item.hasValue ? "已保存，留空保留原值" : "Bearer … 或 ${execution.id}"} maxLength={8192} onChange={event => header(index, "value", event.target.value)}/>
                        <button type="button" aria-label={`移除请求头 ${index + 1}`} title="删除此请求头及保存的值" onClick={() => field("headers", form.headers.filter((_, position) => position !== index))}><X size={15}/></button>
                    </div>)}</div>
                    <div className="notification-form-grid"><label>请求体格式<select value={form.bodyFormat} onChange={event => field("bodyFormat", event.target.value)}><option value="json">JSON（自动转义变量）</option><option value="text">纯文本</option></select></label>
                        <label>连接 / 读取超时（毫秒）<input type="number" min={1000} max={30000} required value={form.timeoutMs} onChange={event => field("timeoutMs", Number(event.target.value))}/></label></div>
                    <label>请求体<textarea rows={9} spellCheck={false} value={form.body} maxLength={65536} disabled={busy || form.method === "GET"} onChange={event => field("body", event.target.value)}/><small>{form.method === "GET" ? "GET 不发送请求体，可在 URL 查询参数中使用变量。" : "JSON 变量写在字符串中，支持嵌套对象和数组；完整数值变量会保留数值类型。"}</small></label>
                    <div className="notification-triggers"><b>触发条件</b>{Object.entries(outcomes).map(([key, label]) => <label className="model-checkbox" key={key}><input type="checkbox" checked={form.statuses.includes(key)} onChange={event => field("statuses", event.target.checked ? [...form.statuses, key] : form.statuses.filter(item => item !== key))}/>{label}</label>)}</div>
                    <label className="model-checkbox"><input type="checkbox" checked={form.enabled} onChange={event => field("enabled", event.target.checked)}/>启用这条通知</label>
                    <details className="notification-advanced"><summary>可选：响应成功条件</summary><p>默认 HTTP 2xx 为成功。若接口使用业务状态码，可增加字段判断；不会自动跟随重定向。</p>
                        <div className="notification-form-grid"><label>响应 JSON 字段<input value={form.successPath} placeholder="例如 errcode 或 data.success" onChange={event => field("successPath", event.target.value)}/></label>
                            <label>预期 JSON 值<input value={form.successValue} placeholder={'例如 0、true 或 "ok"'} onChange={event => field("successValue", event.target.value)}/></label></div></details>
                </fieldset>
                <footer className="model-editor-actions">{form.id && <button type="button" className="danger" disabled={busy} onClick={() => run(async () => {
                    if (!await confirm(`删除通知“${form.name}”？发送记录仍会保留。`)) return;
                    await api(`/api/notifications?id=${encodeURIComponent(form.id)}`, {method: "DELETE"}); setForm(empty()); setDirty(false);
                })}><Trash2 size={14}/>删除</button>}
                    <button type="button" disabled={busy || !form.name || (!form.url && !form.hasUrl)} onClick={sendTest}><Send size={14}/>测试发送</button>
                    <button className="primary" type="submit" disabled={busy}><Save size={14}/>{busy ? "处理中…" : "保存配置"}</button></footer>
                <p className="model-hint">URL 和请求头在本机保存后脱敏显示；不发送思考过程和工具输出。审批挂起不触发完成通知，第一版不自动重试。</p>
            </form>
        </div>
        <section className="notification-variables"><h3>动态变量</h3><p>接收人、群 ID 等固定字段直接写在请求体中；以下变量按每轮对话替换。</p>
            <dl>{(data?.variables || Object.keys(descriptions)).map(name => <div key={name}><dt><code>{`\u0024{${name}}`}</code></dt><dd>{descriptions[name]}</dd></div>)}</dl></section>
        <section className="notification-records"><h3>最近发送记录</h3><p>最多保留最近 200 条。超时可能已被接收，手动重发前请核对接收方。</p>
            {!data?.records.length && <p>暂无发送记录。可以先发送一条测试通知。</p>}
            {data?.records.map(record => <article className="notification-record" key={record.id}>
                <div><b>{record.name}</b><span className={`notification-status ${record.status}`}>{states[record.status] || record.status}</span></div>
                <small>{time(record.createdAt)} · {record.kind === "test" ? "测试通知" : record.kind === "manual" ? "手动重发" : outcomes[record.outcome]}{record.httpStatus ? ` · HTTP ${record.httpStatus}` : ""}</small>
                <p>{record.title}</p>{record.error && <p className="notification-record-error">{record.error}</p>}
                <div className="notification-record-footer"><code title={record.executionId}>执行 ID：{record.executionId}</code>
                    <button type="button" disabled={busy || ["queued", "sending"].includes(record.status) || !data.configs.some(config => config.id === record.configId)} onClick={() => run(async () => {
                        if (!await confirm("将使用当前保存的通知配置重发这条记录，接收方可能收到重复消息。继续吗？")) return;
                        await api(`/api/notifications/resend?id=${encodeURIComponent(record.id)}`, {method: "POST"}); notify("已提交重发");
                    })}>手动重发</button></div>
            </article>)}
        </section>{dialog}
    </div>;
}
