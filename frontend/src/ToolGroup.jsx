import {useEffect, useId} from "react";
import {ChevronDown, ChevronRight, Wrench} from "lucide-react";
import {summarizeToolGroup} from "./toolGroups.js";
import {useDisclosure} from "./HistoryView.jsx";

export default function ToolGroup({tools, renderTool}) {
    const {counts, categories, current, failures, targets} = summarizeToolGroup(tools);
    const pending = counts.running + counts.generating;
    const active = pending + counts.waiting > 0;
    const key = `group:${tools[0]?.streamId || tools[0]?.callId}`;
    const [open, setOpen] = useDisclosure(key, active);
    const [manual, setManual] = useDisclosure(`${key}:manual`, false);
    const id = useId();
    // Explicit reading choices survive completion and virtual-list remounts.
    useEffect(() => { if (!manual) setOpen(active); }, [active, manual, setOpen]);
    if (tools.length === 1) return renderTool(tools[0], false);
    const status = [pending && `执行中 ${pending}`, counts.waiting && `等待 ${counts.waiting}`,
        counts.rejected && `已拒绝 ${counts.rejected}`, counts.skipped && `已跳过 ${counts.skipped}`,
        counts.error && `失败 ${counts.error}`, counts.success && `已完成 ${counts.success}`].filter(Boolean).join(" / ");
    return <section className={`tool-group ${open ? "is-open" : ""} ${counts.error ? "has-error" : ""}`} aria-label={`工具组，${tools.length} 项`}>
        <button type="button" className="tool-group-heading" aria-expanded={open} aria-controls={id} title={[categories, current || targets, status].filter(Boolean).join("；")}
                onClick={() => { setManual(true); setOpen(value => !value); }}>
            <span className="tool-group-title"><Wrench size={15}/><b>工具调用 · {tools.length} 项</b><span className="tool-group-status" title={status}>{status}</span>{open ? <ChevronDown size={15}/> : <ChevronRight size={15}/>}</span>
            {failures && <span className="tool-group-errors" title={failures}>失败：{failures}</span>}
        </button>
        <div id={id} hidden={!open} className="tool-group-list">{open && tools.map(tool => renderTool(tool, true))}</div>
    </section>;
}
