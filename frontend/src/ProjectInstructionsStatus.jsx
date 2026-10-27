import {useEffect, useRef, useState} from "react";
import {ChevronDown} from "lucide-react";
import ComposerPopover from "./ComposerPopover.jsx";
import {latestProjectInstructions, projectInstructionsLabel} from "./projectInstructions.js";

/** Session-owned reconciliation independent of message batches and sidebar metadata replacement. */
export default function ProjectInstructionsStatus({api, sessionId, live, running, onOpenFile}) {
    const [remote, setRemote] = useState(null);
    const [saving, setSaving] = useState(false), [saveError, setSaveError] = useState("");
    const modeRevision = useRef(0);
    useEffect(() => {
        if (!sessionId || !api) return;
        let disposed = false, pending = false;
        const controller = new AbortController();
        const refresh = async () => {
            if (pending || disposed) return;
            pending = true;
            const revision = modeRevision.current;
            try {
                const response = await api(`/api/sessions/status?sessionId=${encodeURIComponent(sessionId)}`, {signal: controller.signal});
                if (!disposed) setRemote(previous => ({sessionId, checked: true, error: "",
                    mode: revision === modeRevision.current ? response.projectInstructionMode || "auto" : previous?.mode || "auto",
                    status: latestProjectInstructions(previous?.sessionId === sessionId ? previous.status : null, response.projectInstructions)}));
            } catch (error) {
                if (!disposed) setRemote(previous => ({sessionId, checked: true, error: error.message, mode: previous?.sessionId === sessionId ? previous.mode : "auto",
                    status: previous?.sessionId === sessionId ? previous.status : null}));
            } finally { pending = false; }
        };
        const visible = () => { if (!document.hidden) refresh(); };
        refresh();
        const interval = window.setInterval(visible, 5000);
        window.addEventListener("focus", visible);
        document.addEventListener("visibilitychange", visible);
        return () => {
            disposed = true; controller.abort(); window.clearInterval(interval);
            window.removeEventListener("focus", visible); document.removeEventListener("visibilitychange", visible);
        };
    }, [api, sessionId, running]);
    const current = remote?.sessionId === sessionId ? remote : null;
    const status = latestProjectInstructions(current?.status, live);
    const label = projectInstructionsLabel(status, current || {});
    const mode = current?.mode || status?.mode || "auto";
    const modeLabel = {auto: "自动选择", claude: "仅 CLAUDE.md", agents: "仅 AGENTS.md", off: "不加载"}[mode];
    const pendingMode = status && (status.mode || "auto") !== mode;
    const changeMode = async value => {
        setSaving(true); setSaveError(""); modeRevision.current++;
        try {
            const result = await api("/api/sessions/project-instructions", {method: "POST", body: new URLSearchParams({sessionId, mode: value})});
            setRemote(previous => previous?.sessionId === sessionId ? {...previous, mode: result.mode} : previous);
        } catch (error) { setSaveError(error.message); }
        finally { setSaving(false); }
    };
    const title = status ? (status.loaded
        ? `最近推理已注入：${status.path}；${status.characters} 字符${status.truncated ? "（已截断）" : ""}。`
        : status.mode === "off" ? "最近推理已关闭项目指令注入。"
            : ["claude", "agents"].includes(status.mode) ? `最近推理指定的 ${status.mode === "claude" ? "CLAUDE.md" : "AGENTS.md"} 不可用或为空，未回退到其他文件。`
                : "最近推理未加载项目指令：CLAUDE.md 和 AGENTS.md 均不可用或为空。") + `当前选择：${modeLabel}${pendingMode ? "（下次推理生效）" : ""}。文件修改后，下次模型请求重新读取。`
        : current?.error ? `项目指令状态查询失败：${current.error}；切回页面时自动重试。`
            : current?.checked ? "后端尚未记录指令读取结果。发送消息后重新查询；这不表示文件不存在。" : "正在查询后端记录的项目指令状态。";
    const canOpen = status?.loaded && ["CLAUDE.md", "AGENTS.md"].includes(status.source) && onOpenFile;
    return <span className="project-instructions-control">
        {canOpen ? <button type="button" className="project-instructions-metric project-instructions-open" data-loaded="true"
        title={`点击查看和编辑。${title}`} aria-label={`打开项目指令 ${status.source}`}
        onClick={() => onOpenFile(status.source, {line: 1})}>项目指令 {label}</button>
        : <span className="project-instructions-metric" data-loaded={Boolean(status?.loaded)} title={title}>项目指令 {label}</span>}
        {pendingMode && <small className="instruction-mode-pending">下次生效</small>}
        <ComposerPopover label="项目指令设置" className="instruction-mode-button" trigger={<ChevronDown size={12}/>}>
            <label>加载模式<select aria-label="项目指令加载模式" value={mode} disabled={saving || !current?.checked} onChange={event => changeMode(event.target.value)}>
                <option value="auto">自动选择 · CLAUDE.md 优先</option><option value="claude">仅 CLAUDE.md</option>
                <option value="agents">仅 AGENTS.md</option><option value="off">不加载项目指令</option>
            </select></label>
            <p>按当前用户和工作区保存，对该工作区的会话生效。下一次模型请求读取新设置；手动模式不回退到其他文件。</p>
            <p>最近实际结果：{label}{pendingMode ? "（加载模式已修改，等待下一次推理）" : ""}</p>
            {saving && <p role="status">正在保存…</p>}{saveError && <p role="alert">{saveError}</p>}
        </ComposerPopover>
    </span>;
}
