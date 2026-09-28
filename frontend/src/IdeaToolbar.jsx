import {callIdea} from "./host.js";

export default function IdeaToolbar({sessions, current, running, onSelect, onCreate, onRename, onDelete, onOpenHistory, onAttach, notify, workspace}) {
    async function attach(method) {
        try { onAttach(await callIdea(method)); } catch (error) { notify(error.message); }
    }
    return <div className="idea-toolbar">
        <div className="idea-project" title={workspace}>IDEA · {workspace || "正在连接项目"}</div>
        <div className="idea-toolbar-row">
            <select aria-label="当前项目会话" value={current?.sessionId || ""} disabled={running}
                    onChange={event => onSelect(sessions.find(item => item.sessionId === event.target.value))}>
                <option value="" disabled>选择会话</option>
                {sessions.map(item => <option key={item.sessionId} value={item.sessionId}>
                    {item.active ? "● " : ""}{item.title}
                </option>)}
            </select>
            <button onClick={onOpenHistory}>历史会话</button>
            <button disabled={running} onClick={onCreate}>＋ 新建</button>
            <button disabled={running || !current} onClick={event => onRename(event, current)}>重命名</button>
            <button disabled={running || !current} onClick={event => onDelete(event, current)}>删除</button>
            <button disabled={running} onClick={() => callIdea("settings").catch(error => notify(error.message))}>连接</button>
        </div>
        <div className="idea-toolbar-row">
            <button disabled={running} onClick={() => attach("getSelection")}>添加选区</button>
            <button disabled={running} onClick={() => attach("getCurrentFile")}>添加当前文件</button>
            <button disabled={running} onClick={() => callIdea("refreshFiles").then(result => result.warning && notify(result.warning)).catch(error => notify(error.message))}>同步文件</button>
            <button onClick={() => callIdea("openWeb").catch(error => notify(error.message))}>网页工作台</button>
        </div>
    </div>;
}
