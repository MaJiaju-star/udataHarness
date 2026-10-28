export const contextCategories = [
    ["system", "系统规则"], ["tools", "工具定义"], ["user", "用户与任务"],
    ["assistant", "推理与回复"], ["tool", "工具正文"], ["references", "归档引用"]
];

export const actionLabels = {
    archive: "原文归档", restore: "按需恢复", search: "历史检索", task_update: "任务更新", cache_decision: "Cache 决策",
    context_rewrite: "活跃视图已收敛", rewrite_failed: "上下文重组失败"
};
export const reasonLabels = {
    prices_unknown: "价格未配置，保留前缀", cache_usage_unknown: "缓存数据未知，保留前缀",
    preserve_cached_prefix: "预计收益不足，保留前缀", estimated_net_saving: "预计净收益满足门槛",
    capacity_pressure: "容量压力，优先收敛", policy_disabled: "成本策略已关闭",
    task_paused: "旧任务已暂停", task_closed: "旧任务已关闭", duplicate_read: "重复读取",
    single_result_limit: "单条结果超预算", batch_result_limit: "本批结果超预算",
    active: "进行中", paused: "已暂停", closed: "已关闭",
    task_lifecycle: "按任务生命周期替换旧正文", artifact_io: "归档读写失败，保留原活跃视图"
};

export const knownNumber = value => typeof value === "number" && Number.isFinite(value) ? value : null;
export const prefixAssessmentLabels = {
    first_request: "首次记录，正在建立对照", fixed_prefix_changed: "固定前缀发生变化",
    history_changed: "历史前缀发生变化", parameters_changed: "请求参数发生变化",
    comparison_incomplete: "请求过大，前缀对照不完整", cache_usage_unknown: "上游缓存数据未知",
    stable_prefix_lower_cache_read: "前缀保持稳定，但缓存读取下降",
    appended_input_growth: "新增输入使命中率下降",
    prefix_preserved: "前缀保持稳定", request_not_successful: "请求未成功完成"
};
export function prefixChanges(row) {
    const labels = {provider: "协议", endpoint: "接口地址", model: "模型", system: "系统指令", tools: "工具定义", messages: "历史消息"};
    const changes = (row.changedSections || []).map(key => key === "messages" && row.appendOnly ? "历史仅追加" : labels[key] || key);
    if (row.changedParameters?.length) changes.push(`参数：${row.changedParameters.join("、")}`);
    return changes.join("；") || (row.baselineAvailable ? "未发现请求内容变化" : "尚无上一次请求可对比");
}
export const tokenText = value => knownNumber(value) == null ? "未知" : Math.round(value).toLocaleString("zh-CN");
export const moneyText = (value, currency = "USD") => knownNumber(value) == null ? "未知" :
    `${currency} ${value.toLocaleString("zh-CN", {minimumFractionDigits: 4, maximumFractionDigits: 6})}`;

export function summarizeContext(data, agent = "all") {
    const calls = [...new Map((data?.observations || []).filter(row => agent === "all" || row.agentName === agent)
        .map(row => [row.id, row])).values()].sort((a, b) => a.timestamp - b.timestamp);
    const costs = {};
    let knownCosts = 0, unknownCosts = 0;
    const compactions = data?.compactions || [];
    for (const row of [...calls, ...(agent === "all" ? compactions : [])]) {
        const usage = row.scope === "compaction" ? row.normalizedUsage : row.usage;
        const cost = knownNumber(usage?.estimatedCost);
        if (cost == null) { unknownCosts++; continue; }
        const currency = usage.currency || "USD";
        costs[currency] = (costs[currency] || 0) + cost;
        knownCosts++;
    }
    const actions = data?.actions || [];
    const diagnostics = [...new Map((data?.requestDiagnostics || []).filter(row => agent === "all" || row.agentName === agent)
        .map(row => [row.id, row])).values()].sort((a, b) => b.timestamp - a.timestamp);
    return {calls, latest: calls.at(-1), costs, knownCosts, unknownCosts, diagnostics,
        archives: actions.filter(row => row.action === "archive").length,
        restores: actions.filter(row => row.action === "restore").length,
        decisions: actions.filter(row => row.action === "cache_decision"), compactions};
}

export function chartPath(values, max, width = 880, height = 160) {
    let open = false;
    return values.map((value, index) => {
        if (knownNumber(value) == null) { open = false; return ""; }
        const x = 18 + index * (width - 36) / Math.max(1, values.length - 1);
        const y = height - 18 - value / Math.max(1, max) * (height - 36);
        const command = open ? "L" : "M";
        open = true;
        return `${command}${x.toFixed(1)},${y.toFixed(1)}`;
    }).filter(Boolean).join(" ");
}
