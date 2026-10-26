import {toolStatus, toolSummary} from "./toolSummary.js";

export function canGroupTool(tool) {
    return tool && !["todoread", "todowrite", "render_chart", "render_echart", "render_antv_chart"].includes(tool.name);
}

// Group only adjacent timeline entries; never move calls across narrative or agent events.
export function groupToolActivities(activities, tools = []) {
    const byId = new Map(tools.map(tool => [tool.callId, tool]));
    const seen = new Set(), result = [];
    let group = null;
    for (const activity of activities) {
        const tool = activity.type === "tool" ? byId.get(activity.callId) : null;
        if (tool && seen.has(tool.callId)) continue;
        if (tool) seen.add(tool.callId);
        if (activity.type === "tool" && canGroupTool(tool)) {
            if (!group) {
                group = {type: "tool_group", id: `tools-${activity.id || tool.streamId || tool.callId}`, tools: []};
                result.push(group);
            }
            group.tools.push(tool);
        } else {
            group = null;
            result.push(activity);
        }
    }
    return result;
}

export function summarizeToolGroup(tools) {
    const counts = {success: 0, error: 0, running: 0, generating: 0, waiting: 0, rejected: 0, skipped: 0};
    const kinds = new Map();
    const labels = {read: "读取", edit: "编辑", write: "写入", grep: "搜索", glob: "搜索", ls: "目录", bash: "命令", task: "子代理", multitask: "子代理"};
    const active = [], failures = [], targets = [];
    for (const tool of tools) {
        const status = toolStatus(tool), summary = toolSummary(tool);
        counts[status.kind]++;
        const kind = labels[summary.kind] || tool.name || "工具";
        kinds.set(kind, (kinds.get(kind) || 0) + 1);
        const target = `${tool.name} ${summary.target}`;
        targets.push(target);
        if (status.kind === "error") failures.push(target);
        else if (["waiting", "running", "generating"].includes(status.kind)) active.push(target);
    }
    const preview = values => values.slice(0, 2).join("、") + (values.length > 2 ? `，另有 ${values.length - 2} 项` : "");
    return {counts, categories: [...kinds].map(([name, count]) => `${name} ${count}`).join(" · "),
        current: active.length ? preview(active) : "", failures: preview(failures), targets: preview(targets)};
}
