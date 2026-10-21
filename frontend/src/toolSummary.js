const text = value => typeof value === "string" ? value.replace(/\s+/g, " ").trim() : "";
const positive = value => Number.isInteger(Number(value)) && Number(value) > 0 ? Number(value) : null;

function argumentsOf(tool) {
    if (tool.args && typeof tool.args === "object" && !Array.isArray(tool.args)) return tool.args;
    try {
        const parsed = JSON.parse(tool.argsText || "{}");
        return parsed && typeof parsed === "object" && !Array.isArray(parsed) ? parsed : {};
    } catch {
        return {};
    }
}

export function toolSummary(tool) {
    const args = argumentsOf(tool);
    const name = String(tool.name || "tool").toLowerCase();
    const path = text(args.file_path || args.filePath || args.path || args.uri || tool.editDiff?.filePath);
    const result = {kind: name, target: "", detail: "", path: false, added: null, removed: null};
    if (["read", "edit", "write"].includes(name)) {
        result.target = path;
        result.path = true;
        if (name === "read") {
            const points = Array.isArray(args.focus_lines) ? args.focus_lines.map(positive).filter(Boolean) : [];
            const focus = positive(args.focus_line);
            const offset = positive(args.offset);
            const limit = positive(args.limit);
            if (points.length || focus) {
                result.detail = `定位行 ${points.length ? points.slice(0, 8).join("、") : focus}`;
                if (positive(args.context_lines)) result.detail += ` · 前后各 ${positive(args.context_lines)} 行`;
            } else if (args.char_offset != null) {
                result.detail = `快照字符偏移 ${args.char_offset}${positive(args.char_limit) ? ` · 最多 ${positive(args.char_limit)} 字符` : ""}`;
            } else if (offset || limit) {
                result.detail = limit ? `第 ${offset || 1}–${(offset || 1) + limit - 1} 行（请求范围）`
                    : `从第 ${offset} 行开始`;
            } else {
                const actualRange = typeof tool.output === "string"
                    ? tool.output.match(/^--- File: .+ \(Lines: (\d+) - (\d+),/) : null;
                result.detail = actualRange ? `第 ${actualRange[1]}–${actualRange[2]} 行` : "读取文件";
            }
        } else {
            result.detail = name === "edit" ? "修改文件" : "写入文件";
            const diff = tool.editDiff;
            if (diff) {
                if (Number.isFinite(diff.addedLines)) result.added = diff.addedLines;
                if (Number.isFinite(diff.deletedLines)) result.removed = diff.deletedLines;
                if (positive(diff.firstChangedLine)) result.detail += ` · 首处第 ${diff.firstChangedLine} 行`;
            }
        }
    } else if (["grep", "glob"].includes(name)) {
        result.target = text(args.pattern);
        result.detail = `范围 ${path || "当前工作区"}${text(args.include) ? ` · ${text(args.include)}` : ""}`;
    } else if (name === "ls") {
        result.target = path || ".";
        result.path = true;
        result.detail = args.recursive ? "递归列出目录" : "列出目录";
    } else if (["bash", "bash_start"].includes(name)) {
        result.kind = "bash";
        result.target = text(args.command || args.cmd);
        result.detail = text(args.description) || (text(args.cwd) ? `目录 ${text(args.cwd)}` : "终端命令");
    } else if (["webfetch", "websearch", "codesearch"].includes(name)) {
        result.target = text(args.url || args.query || args.pattern);
        result.detail = name === "webfetch" ? "获取网页" : "搜索";
    } else if (["task", "multitask"].includes(name)) {
        result.target = text(args.description || args.agent_name);
        const tasks = Array.isArray(args.tasks) ? args.tasks : [];
        result.detail = tasks.length ? `${tasks.length} 个并行任务` : "子代理任务";
    } else {
        // Unknown/MCP tools expose only recognizable targets, never dump arbitrary arguments.
        result.target = path || text(args.query || args.symbol || args.name || args.description);
        result.path = Boolean(path);
        result.detail = text(args.operation || args.action);
    }
    if (!result.target) result.target = tool.generating ? "正在准备参数…" : "查看调用详情";
    return result;
}

export function toolStatus(tool) {
    if (tool.error) return {kind: "error", label: "执行失败"};
    if (tool.generating) return {kind: "generating", label: "生成参数"};
    if (tool.running) return {kind: "running", label: "执行中"};
    if (!tool.executionStarted && !tool.output && tool.argsText) return {kind: "waiting", label: "等待执行"};
    return {kind: "success", label: "已完成"};
}

export function toolDuration(value) {
    if (value == null || !Number.isFinite(Number(value)) || Number(value) < 0) return "";
    const ms = Number(value);
    return ms < 1000 ? `${Math.round(ms)} ms` : `${(ms / 1000).toFixed(1)} s`;
}
