/** Missing/older responses cannot replace a newer actual inference result. */
export function latestProjectInstructions(...states) {
    return states.filter(state => state && typeof state.loaded === "boolean")
        .reduce((latest, state) => !latest || Number(state.checkedAt || 0) >= Number(latest.checkedAt || 0) ? state : latest, null);
}

export function projectInstructionsLabel(status, {checked = false, error = ""} = {}) {
    if (status) return status.loaded ? `${status.source}${status.truncated ? " · 截断" : ""}`
        : status.mode === "off" ? "已关闭" : status.mode === "claude" ? "CLAUDE.md 不可用" : status.mode === "agents" ? "AGENTS.md 不可用" : "未加载";
    return error ? "查询失败" : checked ? "尚未读取" : "查询中…";
}
