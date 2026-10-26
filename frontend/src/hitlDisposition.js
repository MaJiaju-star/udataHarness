/** Approval outcomes are terminal states, never successful executions. */
export function applyHitlDisposition(tools, action) {
    if (!["reject", "skip"].includes(action)) return tools;
    return tools.map(tool => !tool.disposition && !tool.executionStarted && !tool.output
        ? {...tool, disposition: action, generating: false, running: false}
        : tool);
}
