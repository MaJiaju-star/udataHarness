import {useCallback, useEffect, useState} from "react";
import {Bot, Plus, Copy, Trash2, Save, RefreshCw, PlugZap, ShieldCheck} from "lucide-react";
import "./modelSettings.css";

const empty = () => ({id: "", label: "", model: "", provider: "openai", apiUrl: "", apiKey: "",
    contextLength: 128000, enabled: true, clearApiKey: false});
const protocols = {openai: "OpenAI Chat Completions（兼容接口）", "openai-responses": "OpenAI Responses",
    anthropic: "Anthropic / Claude", gemini: "Google Gemini", ollama: "Ollama", dashscope: "DashScope"};
const examples = {openai: "https://api.example.com/v1/chat/completions", "openai-responses": "https://api.example.com/v1/responses",
    anthropic: "https://api.anthropic.com/v1/messages", gemini: "Gemini API 地址", ollama: "http://localhost:11434/api/chat",
    dashscope: "DashScope API 地址"};

export default function ModelSettings({api, notify, onChanged}) {
    const [data, setData] = useState(null);
    const [form, setForm] = useState(empty);
    const [defaults, setDefaults] = useState({});
    const [error, setError] = useState("");
    const [busy, setBusy] = useState("");
    const [tested, setTested] = useState(null);
    const load = useCallback(async () => {
        const result = await api("/api/models");
        setData(result); setDefaults(result.defaults || {}); return result;
    }, [api]);
    useEffect(() => { load().catch(err => setError(err.message)); }, [load]);
    const field = (key, value) => { setForm(prev => ({...prev, [key]: value})); setTested(null); };
    const select = row => {
        setForm({...empty(), ...row, apiKey: "", clearApiKey: false}); setError(""); setTested(null);
    };
    const copy = row => {
        setForm({...empty(), ...row, id: "", copyFromId: row.source === "personal" ? row.id : "",
            source: "personal", label: `${row.label}（副本）`, apiKey: "", hasApiKey: row.source === "personal" && row.hasApiKey});
        setError(""); setTested(null);
    };
    const run = async (name, action) => {
        setBusy(name); setError("");
        try { await action(); } catch (err) { setError(err.message); } finally { setBusy(""); }
    };
    const readonly = form.source === "system";
    const rows = data?.models || [];
    return <div className="panel-page model-settings">
        <header className="panel-header">
            <div><span className="eyebrow">MODEL CONNECTIONS</span><h2>模型配置</h2>
                <p>管理你的模型连接，保存后无需重启。系统预置模型来自 model.yml。</p></div>
            <div className="panel-actions"><button disabled={!!busy} onClick={() => run("refresh", load)}><RefreshCw size={15}/>刷新</button>
                <button disabled={!!busy} onClick={() => {setForm(empty()); setTested(null); setError("");}}><Plus size={15}/>新增模型</button></div>
        </header>
        {error && <div className="model-banner error" role="alert">{error}</div>}
        {data?.pendingApply && <div className="model-banner" role="status">配置已保存。当前用户有运行中或待审批任务，将在空闲后的新请求中生效。</div>}
        <div className="model-layout">
            <aside className="model-catalog" aria-label="模型列表">
                {!data && <p>正在加载模型…</p>}
                {data && !rows.length && <p>尚未配置模型。点击“新增模型”开始配置。</p>}
                {rows.map(row => <div className={`model-entry ${form.id === row.id ? "selected" : ""}`} key={row.id}>
                    <button className="model-select" disabled={!!busy} onClick={() => select(row)}>
                        <Bot size={17}/><span><b>{row.label}</b><small>{row.provider} · {row.model}</small>
                            <em>{row.source === "system" ? "系统预置 · 只读" : "个人配置"}{row.enabled ? "" : " · 已停用"}</em></span>
                    </button>
                    <button className="model-copy" title={`复制 ${row.label}`} aria-label={`复制 ${row.label}`} disabled={!!busy} onClick={() => copy(row)}><Copy size={14}/></button>
                </div>)}
            </aside>
            <form className="model-editor" onSubmit={event => {event.preventDefault(); run("save", async () => {
                const result = await api("/api/models", {method: "POST", body: JSON.stringify(form)});
                setData(result); setDefaults(result.defaults || {});
                const saved = result.models.find(row => row.id === form.id) || result.models.at(-1);
                if (saved) select(saved);
                await load(); await onChanged?.(); notify("模型配置已保存，空闲后的新请求使用最新配置");
            });}}>
                <div className="model-editor-heading"><h3>{readonly ? "系统预置模型" : form.id ? "编辑个人模型" : "新增个人模型"}</h3>
                    {readonly && <button type="button" disabled={!!busy} onClick={() => copy(form)}><Copy size={14}/>复制为个人配置</button>}</div>
                {readonly && <p className="model-hint">系统预置项只读。复制后填写自己的 API Key，系统密钥不会复制或显示。</p>}
                <fieldset disabled={readonly || !!busy}>
                    <label>显示名称<input required maxLength={80} value={form.label} onChange={e => field("label", e.target.value)} placeholder="例如：日常开发模型"/></label>
                    <label>调用协议<select value={form.provider} onChange={e => field("provider", e.target.value)}>
                        {(data?.providers || Object.keys(protocols)).map(id => <option value={id} key={id}>{protocols[id] || id}</option>)}</select></label>
                    <label>API 地址<input required type="url" value={form.apiUrl} onChange={e => field("apiUrl", e.target.value)} placeholder={examples[form.provider]}/>
                        <small>填写服务商对应协议的接口地址；兼容网关可使用完整端点地址。</small></label>
                    <label>实际模型标识<input required maxLength={200} value={form.model} onChange={e => field("model", e.target.value)} placeholder="服务端实际识别的 model 值"/></label>
                    <label>API Key<input type="password" autoComplete="new-password" value={form.apiKey} onChange={e => field("apiKey", e.target.value)}
                        placeholder={form.hasApiKey ? "已配置，留空保留原密钥" : "本地无鉴权模型可留空"}/></label>
                    {form.hasApiKey && <label className="model-checkbox"><input type="checkbox" checked={form.clearApiKey} onChange={e => field("clearApiKey", e.target.checked)}/>清除原密钥（填写新密钥时使用新密钥）</label>}
                    <label>上下文窗口（Token）<input required type="number" min={1024} max={100000000} step={1} value={form.contextLength} onChange={e => field("contextLength", e.target.value)}/></label>
                    <label className="model-checkbox"><input type="checkbox" checked={form.enabled} onChange={e => field("enabled", e.target.checked)}/>启用，允许新会话选择</label>
                </fieldset>
                <div className="model-secret-note"><ShieldCheck size={14}/>密钥不回传浏览器；保存后与普通配置分开加密存储。</div>
                {tested && <div className={`model-banner ${tested.success ? "success" : "error"}`} role="status">{tested.message} · {tested.durationMs} ms</div>}
                {!readonly && <div className="model-editor-actions">
                    {form.id && <button type="button" className="danger" disabled={!!busy} onClick={() => run("delete", async () => {
                        if (!window.confirm(`删除“${form.label}”？已有会话需重新选择模型。`)) return;
                        await api(`/api/models?id=${encodeURIComponent(form.id)}`, {method: "DELETE"});
                        setForm(empty()); await load(); await onChanged?.(); notify("模型已删除");
                    })}><Trash2 size={14}/>删除</button>}
                    <button type="button" disabled={!!busy} onClick={() => run("test", async () => {
                        setTested(await api("/api/models/test", {method: "POST", body: JSON.stringify(form)}));
                    })}><PlugZap size={14}/>{busy === "test" ? "测试中…" : "测试连接"}</button>
                    <button type="submit" className="primary" disabled={!!busy}><Save size={14}/>{busy === "save" ? "保存中…" : "保存配置"}</button>
                </div>}
                <p className="model-hint">测试连接会向所填服务发送一次简短请求，可能产生少量用量；不会修改配置。</p>
            </form>
        </div>
        <section className="model-defaults"><h3>默认模型</h3><p className="model-hint">未指定模型的会话按模式默认选择；已指定模型的会话保留原选择。</p>
            <div>{[["default", "个人默认"], ["economy", "经济模式"], ["standard", "标准模式"], ["super", "超级模式"]].map(([key, label]) => <label key={key}>{label}
                <select value={defaults[key] || ""} disabled={!!busy} onChange={e => setDefaults(prev => ({...prev, [key]: e.target.value}))}>
                    <option value="">继承默认配置</option>{rows.filter(row => row.enabled).map(row => <option value={row.id} key={row.id}>{row.label}</option>)}
                </select></label>)}</div>
            <button disabled={!!busy} onClick={() => run("defaults", async () => {
                await api("/api/models/defaults", {method: "POST", body: JSON.stringify(defaults)});
                await load(); await onChanged?.(); notify("默认模型已更新");
            })}><Save size={14}/>保存默认选择</button>
        </section>
    </div>;
}
