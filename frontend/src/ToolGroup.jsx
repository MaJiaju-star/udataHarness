import {useEffect, useId, useState} from "react";
import {ChevronDown, ChevronRight, Wrench} from "lucide-react";
import {summarizeToolGroup} from "./toolGroups.js";
import {useDisclosure} from "./HistoryView.jsx";

export default function ToolGroup({tools, renderTool}) {
    const {counts, categories, current, failures, targets} = summarizeToolGroup(tools);
    const pending = counts.running + counts.generating;
    const active = pending + counts.waiting > 0;
    const [open, setOpen] = useDisclosure(`group:${tools[0]?.streamId || tools[0]?.callId}`, active, active);
    const id = useId();
    // Follow lifecycle transitions, not every stream delta, so manual toggles remain usable.
    if (tools.length === 1) return renderTool(tools[0], false);
    const status = [pending && `执行中 ${pending}`, counts.waiting && `等待 ${counts.waiting}`,
        counts.error && `失败 ${counts.error}`, counts.success && `已完成 ${counts.success}`].filter(Boolean).join(" / ");
    return <section className={`tool-group ${open ? "is-open" : ""} ${counts.error ? "has-error" : ""}`} aria-label={`工具组，${tools.length} 项`}>
        <button type="button" className="tool-group-heading" aria-expanded={open} aria-controls={id} onClick={() => setOpen(value => !value)}>
            <span className="tool-group-title"><Wrench size={15}/><b>工具调用 · {tools.length} 项</b><span className="tool-group-status" title={status}>{status}</span>{open ? <ChevronDown size={15}/> : <ChevronRight size={15}/>}</span>
            <span className="tool-group-summary">
                <span className="tool-group-categories" title={categories}>{categories}</span>
                <span className="tool-group-preview" title={current || targets}>{current ? `当前：${current}` : targets}</span>
            </span>
            {failures && <span className="tool-group-errors" title={failures}>失败：{failures}</span>}
        </button>
        <div id={id} hidden={!open} className="tool-group-list">{open && tools.map(tool => renderTool(tool, true))}</div>
    </section>;
}
