import {Check, ChevronDown, ChevronRight, FileText, FilePenLine, FilePlus2, FolderOpen,
    Search, Terminal, Globe, Bot, Wrench, RefreshCw, Clock3, X} from "lucide-react";
import {toolSummary, toolStatus, toolDuration} from "./toolSummary.js";

const icons = {read: FileText, edit: FilePenLine, write: FilePlus2, grep: Search, glob: Search,
    ls: FolderOpen, bash: Terminal, webfetch: Globe, websearch: Globe, task: Bot, multitask: Bot};

export default function ToolCallHeader({tool, open, onToggle}) {
    const summary = toolSummary(tool);
    const status = toolStatus(tool);
    const Icon = icons[summary.kind] || Wrench;
    const StatusIcon = ["error", "rejected", "skipped"].includes(status.kind) ? X : status.kind === "waiting" ? Clock3
        : ["running", "generating"].includes(status.kind) ? RefreshCw : Check;
    const duration = toolDuration(tool.durationMs);
    const separator = Math.max(summary.target.lastIndexOf("/"), summary.target.lastIndexOf("\\"));
    const hasDirectory = summary.path && separator >= 0 && separator < summary.target.length - 1;
    return <button className="tool-heading" onClick={onToggle} aria-expanded={open}
                   aria-label={`${tool.name}：${summary.target}，${status.label}，${open ? "收起" : "展开"}详情`}>
        <span className="tool-icon"><Icon size={14}/></span>
        <span className="tool-heading-main">
            <span className="tool-heading-title">
                <b className="tool-heading-name" title={tool.name}>{tool.name}</b>
                <code className="tool-heading-target" title={summary.target}>
                    {hasDirectory ? <><span className="tool-target-directory">{summary.target.slice(0, separator + 1)}</span>
                        <strong>{summary.target.slice(separator + 1)}</strong></>
                        : <span className="tool-target-text">{summary.target}</span>}
                </code>
            </span>
            <span className="tool-heading-detail">
                {summary.detail && <span className="tool-heading-description" title={summary.detail}>{summary.detail}</span>}
                {(summary.added != null || summary.removed != null) && <span className="tool-heading-diff">
                    {summary.added != null && <span className="diff-add">+{summary.added}</span>}
                    {summary.removed != null && <span className="diff-remove">−{summary.removed}</span>}
                </span>}
            </span>
        </span>
        <span className={`tool-heading-status ${status.kind}`} title={[status.label, duration, summary.detail].filter(Boolean).join(" · ")}>
            <span><StatusIcon size={12} className={["running", "generating"].includes(status.kind) ? "spin" : undefined}/>{status.label}</span>
            {open && duration && <small>{duration}</small>}
        </span>
        <span className="tool-heading-chevron">{open ? <ChevronDown size={15}/> : <ChevronRight size={15}/>}</span>
    </button>;
}
