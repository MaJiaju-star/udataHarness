import {useEffect, useMemo, useRef, useState} from "react";
import {createPortal} from "react-dom";
import {Activity, Archive, RefreshCw, X} from "lucide-react";
import {actionLabels, chartPath, contextCategories, knownNumber, moneyText, prefixAssessmentLabels, prefixChanges, reasonLabels, summarizeContext, tokenText} from "./contextMetrics.js";

const time = value => value ? new Date(Number(value)).toLocaleTimeString("zh-CN") : "—";

export default function ContextPanel({api, sessionId, running, onClose}) {
    const [data, setData] = useState(null);
    const [error, setError] = useState("");
    const [loading, setLoading] = useState(true);
    const [refresh, setRefresh] = useState(0);
    const [tab, setTab] = useState("actions");
    const [agent, setAgent] = useState("all");
    const panel = useRef(null);
    const close = useRef(null);
    useEffect(() => {
        const previous = document.activeElement;
        close.current?.focus();
        return () => { if (previous?.isConnected) previous.focus(); };
    }, []);
    useEffect(() => {
        let cancelled = false;
        setLoading(true);
        api(`/api/context/overview?sessionId=${encodeURIComponent(sessionId)}&limit=200`)
            .then(value => { if (!cancelled) { setData(value); setError(""); } })
            .catch(err => { if (!cancelled) setError(err.message); })
            .finally(() => { if (!cancelled) setLoading(false); });
        return () => { cancelled = true; };
    }, [api, sessionId, refresh, running]);
    useEffect(() => {
        if (!running) return;
        const timer = setInterval(() => setRefresh(value => value + 1), 3000);
        return () => clearInterval(timer);
    }, [running]);
    const summary = useMemo(() => summarizeContext(data, agent), [data, agent]);
    const agents = useMemo(() => [...new Set([...(data?.observations || []), ...(data?.requestDiagnostics || [])].map(row => row.agentName).filter(Boolean))], [data]);
    const {latest} = summary;
    const hit = knownNumber(latest?.usage?.cacheHitRate);
    function onKeyDown(event) {
        if (event.key === "Escape") { event.preventDefault(); onClose(); }
        if (event.key === "Tab") {
            const elements = [...panel.current.querySelectorAll("button:not(:disabled), select, summary")];
            const first = elements[0], last = elements.at(-1);
            if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus(); }
            else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus(); }
        }
        event.stopPropagation();
    }
    return createPortal(<div className="modal-backdrop context-panel-backdrop" onClick={onClose}>
        <section ref={panel} className="context-panel" role="dialog" aria-modal="true" aria-labelledby="context-panel-title"
                 onClick={event => event.stopPropagation()} onKeyDown={onKeyDown}>
            <header className="context-panel-header">
                <div><span className="context-eyebrow"><Activity size={14}/> CONTEXT OBSERVATORY</span>
                    <h2 id="context-panel-title">上下文观察台</h2>
                    <p>查看活跃上下文如何变化，以及每次取舍的原因。</p></div>
                <div className="context-panel-tools">
                    <button type="button" disabled={loading} onClick={() => setRefresh(value => value + 1)}><RefreshCw size={15}/>刷新</button>
                    <button ref={close} type="button" className="icon-button" aria-label="关闭上下文观察台" onClick={onClose}><X size={19}/></button>
                </div>
            </header>
            <div className="context-panel-scroll">
                <div className="context-status-row"><span className={running ? "context-live" : ""}>{running ? "运行中 · 每 3 秒刷新" : "会话记录"}</span>
                    <span>成本策略 {data ? data.policyEnabled ? "开启" : "关闭" : "—"}</span>
                    <label>Agent <select aria-label="筛选 Agent" value={agent} onChange={event => setAgent(event.target.value)}>
                        <option value="all">全部</option>{agents.map(name => <option key={name}>{name}</option>)}
                    </select></label></div>
                {loading && !data && <p role="status">正在读取上下文记录…</p>}
                {error && <p className="context-error" role="alert">读取失败：{error}。可以点击刷新重试。</p>}
                {data && <>
                    <div className="context-metrics">
                        <Metric label="最近请求 · 上下文估算" value={tokenText(latest?.estimatedTokens)} detail={`${latest?.messageCount ?? "—"} 条消息 · 估算包含系统与工具定义`}/>
                        <Metric label="最近请求 · Cache Read" value={hit == null ? "未知" : `${(hit * 100).toFixed(1)}%`}
                                detail={`供应商 Usage · ${tokenText(latest?.usage?.cacheReadTokens)} tokens`}/>
                        <Metric label="窗口内 · 用量费用估算" value={Object.entries(summary.costs).map(([currency, cost]) => moneyText(cost, currency)).join(" / ") || "未知"}
                                detail={`${summary.knownCosts} 项可计价 · ${summary.unknownCosts} 项未知${agent !== "all" ? " · 不含共享压缩调用" : " · 含已记录压缩调用"}`}/>
                        <Metric label="窗口内 · 归档 / 恢复" value={`${summary.archives} / ${summary.restores}`} detail="会话管理动作；不受 Agent 筛选影响"/>
                    </div>
                    <section className="context-chart-card" aria-labelledby="context-trend-title">
                        <div className="context-section-heading"><h3 id="context-trend-title">上下文与实际输入</h3><span>{summary.calls.length} 次逻辑模型调用</span></div>
                        <Trend calls={summary.calls}/>
                        <p className="context-caption">上下文与公共前缀均为应用层估算；公共前缀不代表实际缓存命中。实际输入按配置口径归一，原始 Usage 见模型调用表格。多 Agent 建议分别筛选。</p>
                    </section>
                    <section className="context-composition" aria-labelledby="context-composition-title">
                        <h3 id="context-composition-title">最近请求的上下文组成</h3>
                        {latest ? <><div className="context-composition-bar" aria-hidden="true">{contextCategories.map(([key], index) =>
                            <span key={key} className={`context-color-${index}`} style={{flexGrow: latest.categories?.[key] || 0}}/>)}</div>
                            <div className="context-composition-labels">{contextCategories.map(([key, label], index) =>
                                <span key={key}><i className={`context-color-${index}`}/>{label}<b>{tokenText(latest.categories?.[key])}</b></span>)}</div></>
                            : <p className="context-empty">暂无观测。启用上下文管理后，完成一次模型调用即可看到记录。</p>}
                    </section>
                    <div className="context-tabs" aria-label="上下文记录分类">{[["actions", "管理动作"], ["calls", "模型调用"], ["prefix", "请求前缀"], ["tasks", "任务档案"], ["compactions", "压缩开销"]].map(([key, label]) =>
                        <button type="button" key={key} aria-pressed={tab === key} onClick={() => setTab(key)}>{label}</button>)}</div>
                    {tab === "actions" && <div className="context-action-list">{!(data.actions || []).length && <p className="context-empty">暂无管理动作。任务切换、归档或恢复后会显示在这里。</p>}
                        {[...(data.actions || [])].reverse().map((row, index) => <Action key={`${row.timestamp}-${index}`} row={row}/>)}</div>}
                    {tab === "prefix" && <div className="context-action-list">
                        <p className="context-caption">比较同一会话、同一 Agent 的相邻推理请求，包含重试，不含压缩请求。只保存结构指纹，不保存正文。前缀相同不保证服务端缓存命中。</p>
                        {summary.diagnostics.map(row => <PrefixDiagnostic key={row.id} row={row}/>)}
                        {!summary.diagnostics.length && <p className="context-empty">暂无请求前缀诊断。开启诊断后，完成新的模型调用即可查看。</p>}
                    </div>}
                    {tab === "calls" && <div className="context-table-scroll"><table className="context-table"><caption>逐请求 Usage 与应用层估算；费用按该调用记录的配置计算。</caption>
                        <thead><tr><th>时间 / 模型</th><th>上下文估算</th><th>公共前缀估算</th><th>Usage 原始输入</th><th>实际输入汇总</th><th>Cache Read</th><th>费用估算</th><th>耗时</th></tr></thead>
                        <tbody>{[...summary.calls].reverse().map(row => <tr key={row.id}><td title={row.id}>{time(row.timestamp)}<small>{row.agentName} · {row.model}</small></td>
                            <td>{tokenText(row.estimatedTokens)}</td><td>{tokenText(row.memoryPrefixEstimatedTokens)}</td><td>{tokenText(row.usage?.promptTokens)}</td>
                            <td>{tokenText(row.usage?.inputTokens)}</td><td>{tokenText(row.usage?.cacheReadTokens)}</td>
                            <td>{moneyText(row.usage?.estimatedCost, row.usage?.currency)}</td><td>{tokenText(row.durationMs)} ms</td></tr>)}</tbody>
                    </table>{!summary.calls.length && <p className="context-empty">暂无模型调用记录</p>}</div>}
                    {tab === "tasks" && <div className="context-task-list">{(data.tasks || []).map(task => <article key={task.taskId}>
                        <Archive size={17}/><div><b>{task.module || task.taskId}</b><small>{task.taskId} · {time(task.updatedAt)}</small></div>
                        <span>{reasonLabels[task.state] || task.state}</span></article>)}{!data.tasks?.length && <p className="context-empty">暂无任务档案。Agent 记录任务焦点后，这里会显示状态。</p>}</div>}
                    {tab === "compactions" && <div className="context-action-list">{[...summary.compactions].reverse().map(row => <article className="context-action" key={row.id}>
                        <div><b>{row.status === "failed" ? "压缩失败" : "上下文检查点"}</b><time>{time(row.timestamp)}</time></div>
                        <p>{row.status === "failed" ? row.reason : `${tokenText(row.beforeTokens)} → ${tokenText(row.afterTokens)} 估算 tokens · 前缀对齐 ${row.cacheAligned ? "是" : "否"}`}</p>
                        <small>{row.selectedModel} · 调用费用估算 {moneyText(row.normalizedUsage?.estimatedCost, row.normalizedUsage?.currency)}
                            {row.pricingBasis === "current_configuration" ? " · 旧记录按当前价格估算" : ""}</small></article>)}
                        {!summary.compactions.length && <p className="context-empty">暂无压缩调用。出现检查点时，会单独展示其开销。</p>}</div>}
                    <footer className="context-caption">每类最多展示最近 {data.limit} 项，费用只汇总当前窗口。未知值不作为零；不包含未返回 Usage 的重试、未记录的工具费用或账单调整。</footer>
                </>}
            </div>
        </section>
    </div>, document.body);
}

function Metric({label, value, detail}) {
    return <article className="context-metric"><span>{label}</span><strong>{value}</strong><small>{detail}</small></article>;
}

function PrefixDiagnostic({row}) {
    const first = row.firstMessageDifference;
    const rate = value => knownNumber(value) == null ? "未知" : `${(value * 100).toFixed(1)}%`;
    return <article className="context-action">
        <div><b>{prefixAssessmentLabels[row.assessment] || row.assessment}</b><time>{time(row.timestamp)}</time></div>
        <p>{prefixChanges(row)}</p>
        <small>输入 {tokenText(row.previousInputTokens)} → {tokenText(row.usage?.inputTokens)} tokens · 缓存读取 {tokenText(row.previousCacheReadTokens)} → {tokenText(row.usage?.cacheReadTokens)} tokens · 命中率 {rate(row.previousCacheHitRate)} → {rate(row.usage?.cacheHitRate)}</small>
        <small>{row.agentName} · {row.format} · 状态 {row.status} · 间隔 {knownNumber(row.intervalMs) == null ? "—" : `${(row.intervalMs / 1000).toFixed(1)} 秒`}</small>
        <details><summary>查看变化位置</summary>
            <p>共同历史前缀 {row.messagePrefixCount ?? "—"} 项；请求消息 {row.previousMessageCount ?? "—"} → {row.messageCount ?? "—"} 项。</p>
            {row.firstSystemDifference && <p>系统指令首先变化的片段：{row.firstSystemDifference.previousSection || "—"} → {row.firstSystemDifference.section || "—"}，字符 {row.firstSystemDifference.previousChars ?? "—"} → {row.firstSystemDifference.chars ?? "—"}。</p>}
            {first && <p>第 {first.index + 1} 项：{first.kind === "appended" ? "新增" : first.kind === "removed" ? "移除" : "修改"}，角色 {first.previousRole || "—"} → {first.role || "—"}，序列化字符 {first.previousChars ?? "—"} → {first.chars ?? "—"}
                {first.changedFields?.length > 0 && `；字段 ${first.changedFields.join("、")}`}
                {first.type && first.type !== "unknown" && `；类型 ${first.previousType || "—"} → ${first.type}`}
                {first.sharedContentBlocks != null && `；其中前 ${first.sharedContentBlocks} 个内容块相同`}
                {first.changedContentBlockFields?.length > 0 && `；随后内容块的字段 ${first.changedContentBlockFields.join("、")} 变化`}。</p>}
            {row.toolOrderChanged && <p>工具排列或集合发生变化。</p>}
            {row.addedTools?.length > 0 && <p>新增工具：{row.addedTools.join("、")}</p>}
            {row.removedTools?.length > 0 && <p>移除工具：{row.removedTools.join("、")}</p>}
            {row.changedTools?.map(tool => <p key={tool.name}>工具 {tool.name}：{tool.fields.join("、") || "定义结构"} 变化。</p>)}
            {row.previousStatus && row.previousStatus !== "success" && <p>上一次请求未成功完成，不能据此假定它已建立缓存。</p>}
            <small>调用 {row.runId}:{row.reasonId} · 请求 {row.id} · 指纹 {row.requestHash?.slice(0, 16)}…</small>
        </details>
    </article>;
}

function Action({row}) {
    const decision = row.action === "cache_decision";
    return <article className="context-action"><div><b>{actionLabels[row.action] || row.action}</b>
        {decision && <span className={`context-decision ${row.allow ? "allow" : "retain"}`}>{row.allow ? "允许重组" : "保留前缀"}</span>}
        <time>{time(row.timestamp)}</time></div><p>{reasonLabels[row.reason] || row.reason || "—"}</p>
        {decision ? <><small>{row.model} · {row.candidateCount} 条候选 · 预计减少 {tokenText(row.savedEstimatedTokens)} tokens</small>
            {row.reasonId && <small title={`${row.runId}:${row.reasonId}`}>来源调用 {row.reasonId.slice(0, 16)}… · 影响后续请求</small>}
            {knownNumber(row.estimatedBenefit) != null && <small>预计收益 {moneyText(row.estimatedBenefit, row.currency)} · 重建及恢复 {moneyText(row.estimatedOverhead, row.currency)} · 安全系数 {row.safetyMargin}</small>}</>
            : row.action === "context_rewrite" ? <small>消息正文 {tokenText(row.charsBefore)} → {tokenText(row.charsAfter)} 字符 · 已替换 {row.appliedCount} 条工具正文</small>
            : <small>{row.id ? `${row.id.slice(0, 16)}… · ` : ""}{row.action === "archive" || row.action === "restore" ? `${tokenText(row.chars)} 字符` : ""}</small>}
    </article>;
}

function Trend({calls}) {
    const series = [
        {key: "estimatedTokens", label: "上下文估算", color: "var(--accent)", values: calls.map(row => knownNumber(row.estimatedTokens))},
        {key: "input", label: "实际输入", color: "var(--info)", values: calls.map(row => knownNumber(row.usage?.inputTokens))},
        {key: "cache", label: "Cache Read", color: "var(--purple)", values: calls.map(row => knownNumber(row.usage?.cacheReadTokens))}
    ];
    const max = Math.max(1, ...series.flatMap(row => row.values).filter(value => value != null));
    if (!calls.length) return <p className="context-empty">完成模型调用后，会生成上下文变化曲线。</p>;
    return <><div className="context-chart-scale"><span>{tokenText(max)} tokens</span><span>按调用时间排列 · {time(calls[0].timestamp)} – {time(calls.at(-1).timestamp)}</span></div>
        <svg className="context-trend" viewBox="0 0 880 160" role="img" aria-label="上下文估算、实际输入和缓存读取趋势；精确数值见模型调用表格">
            <title>逐请求上下文与缓存趋势</title>
            {[18, 80, 142].map(y => <line key={y} x1="18" x2="862" y1={y} y2={y} stroke="var(--line)" strokeDasharray="3 5"/>)}
            {series.map(row => <g key={row.key}><path d={chartPath(row.values, max)} stroke={row.color} fill="none" strokeWidth="2.5"/>
                {row.values.map((value, index) => value != null && <circle key={index} cx={18 + index * 844 / Math.max(1, calls.length - 1)} cy={142 - value / max * 124} r="2.5" fill={row.color}>
                    <title>{row.label} · 第 {index + 1} 次调用 · {tokenText(value)} tokens</title></circle>)}</g>)}
        </svg><div className="context-legend">{series.map(row => <span key={row.key}><i style={{background: row.color}}/>{row.label}</span>)}</div></>;
}
