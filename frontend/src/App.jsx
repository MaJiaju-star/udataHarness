import {lazy, Suspense, useCallback, useEffect, useMemo, useRef, useState} from "react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import ChartCard, {chartSpecFromTool} from "./ChartCard.jsx";
import GlobalSearchDialog from "./GlobalSearchDialog.jsx";
import ThemePicker from "./ThemePicker.jsx";
import {useWorkspaceStore} from "./workspaceStore.js";
import {
    Activity, ArrowDown, ArrowLeft, Bot, Box, BrainCircuit, Check, ChevronDown, ChevronRight, CircleStop, Code2,
    ClipboardPaste, Copy, Download, File, FileCode2, FilePlus2, Files, Folder, FolderOpen,
    Eye, Film, GitCompareArrows, HardDrive, Image as ImageIcon, Link2, Menu, MessageSquare,
    MoreHorizontal, Network, PanelLeftClose, PanelLeftOpen, PanelRightClose, Pencil, Plus, RefreshCw,
    Save, Scissors, Search, Send, Settings2, ShieldCheck, ShieldOff, Sparkles, SquareTerminal, Trash2,
    Upload, UserRound, X, Zap
} from "lucide-react";

const navItems = [
    {id: "files", label: "文件", icon: Files},
    {id: "sessions", label: "会话", icon: MessageSquare}
];
const thinkingDepthOptions = [
    {value: "auto", label: "自动"},
    {value: "none", label: "关闭思考"},
    {value: "low", label: "低"},
    {value: "medium", label: "中"},
    {value: "high", label: "高"},
    {value: "max", label: "极高"}
];

const EChartCard = lazy(() => import("./EChartCard.jsx"));
const AntVChartCard = lazy(() => import("./AntVChartCard.jsx"));
const MonacoEditor = lazy(() => import("./MonacoEditor.jsx"));
const MonacoDiffEditor = lazy(() => import("./MonacoEditor.jsx").then(module => ({default: module.MonacoDiffEditor})));

const uid = () => `${Date.now()}-${Math.random().toString(36).slice(2)}`;
const emptyTokenUsage = () => ({
    promptTokens: 0,
    thinkTokens: 0,
    completionTokens: 0,
    totalTokens: 0,
    cacheCreationInputTokens: 0,
    cacheReadInputTokens: 0,
    modelCallCount: 0,
    tokensPerSecond: 0,
    durationMs: 0
});
const formatTokens = value => new Intl.NumberFormat("zh-CN", {
    notation: Number(value || 0) >= 10000 ? "compact" : "standard",
    maximumFractionDigits: 1
}).format(Number(value || 0));

function cacheHitRate(usage, provider) {
    const cached = Number(usage.cacheReadInputTokens || 0);
    const prompt = Number(usage.promptTokens || 0);
    const created = Number(usage.cacheCreationInputTokens || 0);
    const separateCacheAccounting = String(provider || "").toLowerCase().includes("anthropic");
    const eligible = separateCacheAccounting ? prompt + cached + created : prompt;
    if (eligible <= 0) return null;
    return Math.min(100, Math.max(0, cached * 100 / eligible));
}
const formatTime = value => value
    ? new Date(value).toLocaleString("zh-CN", {month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit"})
    : "—";

/**
 * 流式协议正常返回字符串；若模型适配器意外给出数字或对象，则安全转换，
 * 避免 React 渲染阶段因调用字符串方法而让整个工作台白屏。
 */
function asText(value) {
    if (value == null) return "";
    if (typeof value === "string") return value;
    if (typeof value === "number" || typeof value === "boolean") return String(value);
    try {
        return JSON.stringify(value);
    } catch {
        return String(value);
    }
}

/**
 * 合并模型文本增量，并兼容少数模型适配器在工具切换点重放累计正文的情况。
 *
 * 正常短增量保持原样追加；只有完整累计前缀、较长的完全重复块，或长度至少 12
 * 的首尾重叠才会被折叠，避免影响模型有意输出的短词重复。
 */
function mergeStreamText(current, incoming) {
    const existing = asText(current);
    const addition = asText(incoming);
    if (!addition) return existing;
    if (!existing) return addition;
    if (addition.startsWith(existing)) return addition;
    if (addition.length >= 12 && existing.endsWith(addition)) return existing;

    const maxOverlap = Math.min(existing.length, addition.length);
    for (let length = maxOverlap; length >= 12; length--) {
        if (existing.endsWith(addition.slice(0, length))) {
            return existing + addition.slice(length);
        }
    }
    return existing + addition;
}

/**
 * 按 reasonId 维护思考活动。一次 ReAct 对话会多次调用模型，每次调用都有独立
 * reasonId；同一 reasonId 的流式增量更新同一卡片，新 reasonId 创建新卡片。
 */
function mergeThinkingActivity(activities, event) {
    const next = (activities || []).map(item => ({...item}));
    let index = event.reasonId
        ? next.findIndex(item => item.type === "thinking" && item.reasonId === event.reasonId)
        : -1;

    // 兼容没有 reasonId 的模型：只续写当前仍在流式输出的最后一张思考卡。
    if (index < 0 && !event.reasonId) {
        for (let cursor = next.length - 1; cursor >= 0; cursor--) {
            if (next[cursor].type === "thinking" && next[cursor].active) {
                index = cursor;
                break;
            }
        }
    }

    if (index < 0) {
        next.push({
            id: event.reasonId || uid(),
            type: "thinking",
            reasonId: event.reasonId || null,
            content: asText(event.content),
            active: event.finished !== true,
            source: "native"
        });
    } else {
        next[index] = {
            ...next[index],
            content: next[index].source === "embedded"
                ? asText(event.content)
                : mergeStreamText(next[index].content, event.content),
            active: event.finished !== true,
            source: "native"
        };
    }
    return next;
}

/**
 * 将一个 reasonId 的普通文本流拆成思考与可见正文，并写入活动时间线。
 *
 * DeepSeek 的工具调用终态可能返回 isThinking=false，但 content 实际是
 * <think>...</think>。因此不能只依赖 SSE type，必须基于该 reason 的累计内容解析；
 * 同时保留 text 活动，避免过程正文被统一堆到所有工具卡之后。
 */
function mergeReasonContentActivities(activities, reasonBuffers, event, preserveNativeThinking = false) {
    const nextActivities = (activities || []).map(item => ({...item}));
    const nextBuffers = {...(reasonBuffers || {})};
    const reasonId = event.reasonId || `anonymous-${event.runId || "run"}`;
    const raw = mergeStreamText(nextBuffers[reasonId], event.content);
    nextBuffers[reasonId] = raw;

    const parsed = splitThinkContent(raw);
    let thinkingIndex = nextActivities.findIndex(
        item => item.type === "thinking" && item.reasonId === reasonId
    );
    const thinkingId = thinkingIndex >= 0
        ? nextActivities[thinkingIndex].id
        : `embedded-thinking-${reasonId}`;
    const textId = `text-${reasonId}`;
    let textIndex = nextActivities.findIndex(item => item.id === textId);

    const nativeThinking = preserveNativeThinking
        && thinkingIndex >= 0
        && nextActivities[thinkingIndex].source === "native";
    if (parsed.thinking && !nativeThinking) {
        const thinkingItem = {
            id: thinkingId,
            type: "thinking",
            reasonId,
            content: thinkingIndex >= 0
                ? mergeStreamText(nextActivities[thinkingIndex].content, parsed.thinking)
                : parsed.thinking,
            active: parsed.pending && event.finished !== true,
            source: "embedded"
        };
        if (thinkingIndex >= 0) {
            nextActivities[thinkingIndex] = {...nextActivities[thinkingIndex], ...thinkingItem};
        } else {
            // 同一 reason 已经产生可见正文时，思考卡仍应排在正文之前。
            textIndex = nextActivities.findIndex(item => item.id === textId);
            if (textIndex >= 0) nextActivities.splice(textIndex, 0, thinkingItem);
            else nextActivities.push(thinkingItem);
        }
    }

    if (parsed.visible) {
        const textItem = {
            id: textId,
            type: "text",
            reasonId,
            content: parsed.visible
        };
        textIndex = nextActivities.findIndex(item => item.id === textId);
        if (textIndex >= 0) {
            nextActivities[textIndex] = {...nextActivities[textIndex], ...textItem};
        } else {
            nextActivities.push(textItem);
        }
    }

    return {activities: nextActivities, reasonBuffers: nextBuffers};
}

function mergeSubagentTool(tools, activities, event) {
    const nextTools = (tools || []).map(tool => ({...tool}));
    let nextActivities = (activities || []).map(item => ({...item}));
    const subtype = event.subtype;
    const ensureActivity = callId => {
        if (!nextActivities.some(item => item.type === "tool" && item.callId === callId)) {
            nextActivities.push({id: `tool-${callId}`, type: "tool", callId});
        }
    };
    if (subtype === "tool_args_start") {
        if (!nextTools.some(tool => tool.streamId === event.streamId)) {
            const tool = {
                callId: `subagent-stream-${event.streamId || uid()}`,
                streamId: event.streamId,
                providerCallId: event.callId,
                name: event.toolName || "正在生成工具调用",
                argsText: "",
                generating: true,
                running: false,
                streamed: true
            };
            nextTools.push(tool);
        }
    } else if (subtype === "tool_args_delta") {
        let index = nextTools.findIndex(tool => tool.streamId === event.streamId);
        if (index < 0) {
            const tool = {
                callId: `subagent-stream-${event.streamId || uid()}`,
                streamId: event.streamId,
                providerCallId: event.callId,
                name: event.toolName || "正在生成工具调用",
                argsText: "",
                generating: true,
                running: false,
                streamed: true
            };
            nextTools.push(tool);
            index = nextTools.length - 1;
        }
        nextTools[index] = {
            ...nextTools[index],
            providerCallId: event.callId || nextTools[index].providerCallId,
            name: event.toolName || nextTools[index].name,
            argsText: `${nextTools[index].argsText || ""}${asText(event.content)}`
        };
    } else if (subtype === "tool_args_end") {
        const index = nextTools.findIndex(tool => tool.streamId === event.streamId);
        if (index >= 0) nextTools[index] = {
            ...nextTools[index],
            providerCallId: event.callId || nextTools[index].providerCallId,
            name: event.toolName || nextTools[index].name,
            argsText: asText(event.content) || nextTools[index].argsText,
            generating: false
        };
    } else if (subtype === "tool_start") {
        nextActivities = nextActivities.map(item =>
            item.type === "thinking" && item.active ? {...item, active: false} : item);
        let index = event.callId
            ? nextTools.findIndex(tool => tool.callId === event.callId || tool.providerCallId === event.callId)
            : -1;
        if (index < 0) {
            index = nextTools.findIndex(tool => tool.streamed && !tool.executionStarted
                && event.toolName && tool.name === event.toolName);
        }
        if (index < 0) {
            index = nextTools.findIndex(tool => tool.streamed && !tool.executionStarted);
        }
        if (index < 0) {
            const tool = {callId: event.callId || uid(), name: event.toolName || "tool"};
            nextTools.push(tool);
            index = nextTools.length - 1;
        }
        const previousCallId = nextTools[index].callId;
        const callId = event.callId || previousCallId;
        nextTools[index] = {
            ...nextTools[index],
            callId,
            name: event.toolName || nextTools[index].name,
            args: event.args || {},
            generating: false,
            running: true,
            executionStarted: true
        };
        if (callId !== previousCallId) {
            nextActivities = nextActivities.map(item =>
                item.type === "tool" && item.callId === previousCallId
                    ? {...item, callId}
                    : item);
        }
        ensureActivity(callId);
    } else if (subtype === "tool_end") {
        let index = event.callId
            ? nextTools.findIndex(tool => tool.callId === event.callId || tool.providerCallId === event.callId)
            : nextTools.findIndex(tool => tool.running);
        if (index < 0) {
            const tool = {callId: event.callId || uid(), name: event.toolName || "tool"};
            nextTools.push(tool);
            index = nextTools.length - 1;
        }
        nextTools[index] = {
            ...nextTools[index],
            running: false,
            generating: false,
            durationMs: event.durationMs,
            output: event.error || event.content,
            error: Boolean(event.error)
        };
        ensureActivity(nextTools[index].callId);
    }
    return {tools: nextTools, activities: nextActivities};
}

function subagentEventId(event) {
    return event.subagentId || event.taskId || event.childRunId || event.runId
        || `${event.agentName || "agent"}-${event.taskIndex || 1}`;
}

function mergeSubagentEvent(subagents, event) {
    const subagentId = subagentEventId(event);
    const current = subagents?.[subagentId] || {
        subagentId,
        taskId: event.taskId,
        index: event.taskIndex || 1,
        agentName: event.agentName || "subagent",
        description: event.description || "子任务",
        multitask: Boolean(event.multitask),
        status: "running",
        tools: [],
        activities: [],
        reasonBuffers: {},
        nativeThinkingReasons: {}
    };
    const next = {
        ...current,
        taskId: event.taskId || current.taskId,
        index: event.taskIndex || current.index,
        agentName: event.agentName || current.agentName,
        description: event.description || current.description,
        multitask: event.multitask ?? current.multitask,
        activities: [...(current.activities || [])],
        reasonBuffers: {...(current.reasonBuffers || {})},
        nativeThinkingReasons: {...(current.nativeThinkingReasons || {})}
    };
    if (event.subtype === "thinking") {
        const reasonId = event.reasonId || `anonymous-${event.runId || "run"}`;
        next.nativeThinkingReasons[reasonId] = true;
        next.activities = mergeThinkingActivity(next.activities, event);
    } else if (["text", "text_replay", "chunk"].includes(event.subtype)) {
        const reasonId = event.reasonId || `anonymous-${event.runId || "run"}`;
        const merged = mergeReasonContentActivities(
            next.activities,
            next.reasonBuffers,
            event,
            Boolean(next.nativeThinkingReasons[reasonId]));
        next.activities = merged.activities;
        next.reasonBuffers = merged.reasonBuffers;
    } else if (event.subtype === "run_start") {
        next.status = "running";
    } else if (event.subtype === "run_end") {
        next.status = "success";
        next.activities = next.activities.map(item =>
            item.type === "thinking" && item.active ? {...item, active: false} : item);
    } else if (event.subtype === "run_pending") {
        next.status = "pending";
        next.activities = next.activities.map(item =>
            item.type === "thinking" && item.active ? {...item, active: false} : item);
    } else if (event.subtype === "error") {
        next.status = "error";
        next.activities = next.activities.map(item =>
            item.type === "thinking" && item.active ? {...item, active: false} : item);
        next.error = event.message || event.content || "子智能体执行失败";
    }
    const toolState = mergeSubagentTool(next.tools, next.activities, event);
    next.tools = toolState.tools;
    next.activities = toolState.activities;
    if (event.usage) next.usage = event.usage;
    if (event.durationMs != null) next.durationMs = event.durationMs;
    return {...(subagents || {}), [subagentId]: next};
}

function restoreHistory(history = []) {
    const result = [];
    for (const item of history) {
        const message = {
            id: uid(),
            role: item.role === "user" ? "user" : "assistant",
            content: item.content || "",
            thinking: item.thinking ? item.content : "",
            tools: item.tools || [],
            fileActivities: item.fileActivities || [],
            finished: true
        };
        const previous = result.at(-1);
        if (message.role === "assistant" && previous?.role === "assistant") {
            previous.content = [previous.content, message.content].filter(Boolean).join("\n\n");
            previous.thinking = [previous.thinking, message.thinking].filter(Boolean).join("\n");
            previous.tools = [...(previous.tools || []), ...(message.tools || [])];
            previous.fileActivities = [
                ...(previous.fileActivities || []),
                ...(message.fileActivities || [])
            ];
        } else {
            result.push(message);
        }
    }
    return result;
}

function fileActivityFromTools(tools = [], recorded = []) {
    const activity = {read: new Set(), created: new Set(), modified: new Set()};
    for (const item of recorded) {
        if (activity[item.type] && item.path) activity[item.type].add(item.path);
    }
    for (const tool of tools) {
        if (tool.error || /(?:错误|失败|not found|cannot )/i.test(String(tool.output || ""))) continue;
        const name = String(tool.name || "").toLowerCase();
        const path = tool.args?.file_path || tool.args?.path;
        if (!path || typeof path !== "string" || path.startsWith("@")) continue;
        if (name === "read") activity.read.add(path);
        if (name === "write" && tool.fileOperation === "modified") activity.modified.add(path);
        if (name === "write" && tool.fileOperation !== "modified") activity.created.add(path);
        if (name === "edit" || name === "apply_patch") activity.modified.add(path);
    }
    return {
        read: [...activity.read],
        created: [...activity.created],
        modified: [...activity.modified]
    };
}

function splitThinkContent(value = "") {
    value = asText(value);
    let visible = "";
    let thinking = "";
    let cursor = 0;
    let pending = false;

    while (cursor < value.length) {
        const open = value.indexOf("<think>", cursor);
        if (open < 0) {
            visible += value.slice(cursor);
            break;
        }
        visible += value.slice(cursor, open);
        const contentStart = open + "<think>".length;
        const close = value.indexOf("</think>", contentStart);
        if (close < 0) {
            thinking += value.slice(contentStart);
            pending = true;
            break;
        }
        thinking += `${thinking ? "\n" : ""}${value.slice(contentStart, close)}`;
        cursor = close + "</think>".length;
    }

    // Streaming may stop in the middle of an opening tag. Keep partial XML out
    // of the visible answer until the following chunk completes it.
    const partialOpen = ["<", "<t", "<th", "<thi", "<thin"];
    const suffix = partialOpen.findLast?.(part => visible.endsWith(part))
        || [...partialOpen].reverse().find(part => visible.endsWith(part));
    if (suffix) visible = visible.slice(0, -suffix.length);

    // Do the same for a closing tag while it is still arriving.
    if (pending) {
        const partialClose = ["<", "</", "</t", "</th", "</thi", "</thin", "</think"];
        const closeSuffix = [...partialClose].reverse().find(part => thinking.endsWith(part));
        if (closeSuffix) thinking = thinking.slice(0, -closeSuffix.length);
    }

    return {visible, thinking: thinking.trim(), pending};
}

async function readEventStream(stream, onEvent) {
    const reader = stream.getReader();
    const decoder = new TextDecoder();
    let buffer = "";
    while (true) {
        const {value, done} = await reader.read();
        buffer += decoder.decode(value || new Uint8Array(), {stream: !done});
        const frames = buffer.split(/\r?\n\r?\n/);
        buffer = frames.pop() || "";
        for (const frame of frames) {
            const payload = frame.split(/\r?\n/)
                .filter(line => line.startsWith("data:"))
                .map(line => line.slice(5).trimStart()).join("\n");
            if (payload) onEvent(JSON.parse(payload));
        }
        if (done) break;
    }
    if (buffer.trim()) {
        const payload = buffer.replace(/^data:\s?/, "").trim();
        if (payload) onEvent(JSON.parse(payload));
    }
}

function App() {
    const [userId, setUserId] = useState(() => localStorage.getItem("udataHarnessUserId") || "");
    const [meta, setMeta] = useState(null);
    const [sessions, setSessions] = useState([]);
    const [current, setCurrent] = useState(null);
    const [messages, setMessages] = useState([]);
    const [running, setRunning] = useState(false);
    const [mobile, setMobile] = useState(() => window.matchMedia("(max-width: 760px)").matches);
    const [notice, setNotice] = useState("");
    const [page, setPage] = useState(() => window.location.hash === "#/admin" ? "admin" : "workspace");
    const [workspacePickerOpen, setWorkspacePickerOpen] = useState(false);
    const [globalSearch, setGlobalSearch] = useState(null);
    const [tokenUsage, setTokenUsage] = useState(emptyTokenUsage);
    const [selectedModel, setSelectedModel] = useState("");
    const [thinkingDepth, setThinkingDepth] = useState(
        () => localStorage.getItem("udataThinkingDepth") || "auto");
    const leftTab = useWorkspaceStore(state => state.leftTab);
    const setLeftTab = useWorkspaceStore(state => state.setLeftTab);
    const leftWidth = useWorkspaceStore(state => state.leftWidth);
    const editorWidth = useWorkspaceStore(state => state.editorWidth);
    const leftCollapsed = useWorkspaceStore(state => state.leftCollapsed);
    const mobilePane = useWorkspaceStore(state => state.mobilePane);
    const toggleLeft = useWorkspaceStore(state => state.toggleLeft);
    const setLeftWidth = useWorkspaceStore(state => state.setLeftWidth);
    const setEditorWidth = useWorkspaceStore(state => state.setEditorWidth);
    const setMobilePane = useWorkspaceStore(state => state.setMobilePane);
    const openEditorFile = useWorkspaceStore(state => state.openFile);
    const resetEditor = useWorkspaceStore(state => state.resetEditor);
    const hasDirtyFiles = useWorkspaceStore(state => state.hasDirtyFiles);
    const themeMode = useWorkspaceStore(state => state.themeMode);
    const colorTheme = useWorkspaceStore(state => state.colorTheme);
    const setResolvedTheme = useWorkspaceStore(state => state.setResolvedTheme);
    const [systemDark, setSystemDark] = useState(
        () => window.matchMedia("(prefers-color-scheme: dark)").matches);
    const resolvedTheme = themeMode === "system" ? (systemDark ? "dark" : "light") : themeMode;

    const notify = useCallback(message => {
        setNotice(message);
        window.clearTimeout(window.__udataNotice);
        window.__udataNotice = window.setTimeout(() => setNotice(""), 3200);
    }, []);

    const trackTokenUsage = useCallback(event => {
        if (!event?.usage) return;
        const usage = event.usage;
        setTokenUsage(currentUsage => {
            if (event.usageScope === "run") {
                const hasModelCallUsage = currentUsage.modelCallCount > 0;
                return {
                    ...currentUsage,
                    promptTokens: hasModelCallUsage
                        ? currentUsage.promptTokens : Number(usage.promptTokens ?? 0),
                    completionTokens: hasModelCallUsage
                        ? currentUsage.completionTokens : Number(usage.completionTokens ?? 0),
                    totalTokens: hasModelCallUsage
                        ? currentUsage.totalTokens : Number(usage.totalTokens ?? 0),
                    cacheCreationInputTokens: hasModelCallUsage
                        ? currentUsage.cacheCreationInputTokens
                        : Number(usage.cacheCreationInputTokens ?? 0),
                    cacheReadInputTokens: hasModelCallUsage
                        ? currentUsage.cacheReadInputTokens
                        : Number(usage.cacheReadInputTokens ?? 0),
                    tokensPerSecond: Number(event.tokensPerSecond || 0),
                    durationMs: Number(event.durationMs || 0)
                };
            }
            return {
                ...currentUsage,
                promptTokens: currentUsage.promptTokens + Number(usage.promptTokens || 0),
                thinkTokens: currentUsage.thinkTokens + Number(usage.thinkTokens || 0),
                completionTokens: currentUsage.completionTokens + Number(usage.completionTokens || 0),
                totalTokens: currentUsage.totalTokens + Number(usage.totalTokens || 0),
                cacheCreationInputTokens: currentUsage.cacheCreationInputTokens
                    + Number(usage.cacheCreationInputTokens || 0),
                cacheReadInputTokens: currentUsage.cacheReadInputTokens
                    + Number(usage.cacheReadInputTokens || 0),
                modelCallCount: currentUsage.modelCallCount + 1
            };
        });
    }, []);

    const api = useCallback(async (path, options = {}) => {
        const {raw = false, ...fetchOptions} = options;
        const multipart = fetchOptions.body instanceof FormData;
        const response = await fetch(path, {
            ...fetchOptions,
            headers: {
                ...(multipart ? {} : {"Content-Type": "application/json"}),
                "X-User-Id": userId,
                ...(fetchOptions.headers || {})
            }
        });
        if (!response.ok) throw new Error(await response.text() || `HTTP ${response.status}`);
        if (raw) return response;
        const body = await response.json();
        if (body.code && body.code >= 400) throw new Error(body.description || "请求失败");
        return body.data === undefined ? body : body.data;
    }, [userId]);

    const refreshWorkspaceFiles = useCallback(async () => {
        const workspace = useWorkspaceStore.getState();
        workspace.requestTreeRefresh();
        const opened = workspace.openedPaths
            .map(path => workspace.buffers[path])
            .filter(Boolean);
        if (opened.length === 0) return;

        const results = await Promise.allSettled(opened.map(buffer =>
            api(`/api/files/read?path=${encodeURIComponent(buffer.path)}`)));
        const changes = [];
        results.forEach((result, index) => {
            if (result.status !== "fulfilled") return;
            const disk = result.value;
            const browser = useWorkspaceStore.getState().buffers[opened[index].path];
            if (!browser) return;
            if (disk.previewType === "image" || disk.previewType === "video") {
                if (disk.modifiedAt !== browser.modifiedAt || disk.size !== browser.size) {
                    useWorkspaceStore.getState().applyExternalFile(disk);
                }
                return;
            }
            if ((disk.content || "") !== (browser.content || "")) {
                changes.push({path: browser.path, browserContent: browser.content || "", disk});
            } else {
                useWorkspaceStore.getState().applyExternalFile(disk);
            }
        });
        if (changes.length > 0) {
            useWorkspaceStore.getState().queueExternalChanges(changes);
            notify(`${changes.length} 个已打开文件发生外部变更`);
        }
    }, [api, notify]);

    async function refreshAfterRun() {
        const results = await Promise.allSettled([loadSessions(), refreshWorkspaceFiles()]);
        const failure = results.find(result => result.status === "rejected");
        if (failure) notify(failure.reason?.message || "刷新工作区失败");
    }

    const loadSessions = useCallback(async () => {
        const data = await api("/api/sessions");
        setSessions(data || []);
        setCurrent(selected => selected
            ? (data || []).find(item => item.sessionId === selected.sessionId) || selected
            : selected);
        return data || [];
    }, [api]);

    useEffect(() => {
        const syncPage = () => setPage(window.location.hash === "#/admin" ? "admin" : "workspace");
        window.addEventListener("hashchange", syncPage);
        return () => window.removeEventListener("hashchange", syncPage);
    }, []);

    useEffect(() => {
        if (!userId) return;
        Promise.all([api("/api/meta"), api("/api/sessions")])
            .then(([metaData, sessionData]) => {
                setMeta(metaData);
                setSessions(sessionData || []);
                setSelectedModel(value => (metaData.models || []).some(item => item.name === value)
                    ? value : metaData.defaultModel || metaData.models?.[0]?.name || "");
            })
            .catch(error => notify(error.message));
    }, [userId, api, notify]);

    useEffect(() => {
        const media = window.matchMedia("(max-width: 760px)");
        const onChange = event => {
            setMobile(event.matches);
        };
        media.addEventListener("change", onChange);
        return () => media.removeEventListener("change", onChange);
    }, []);

    useEffect(() => {
        const media = window.matchMedia("(prefers-color-scheme: dark)");
        const onChange = event => setSystemDark(event.matches);
        setSystemDark(media.matches);
        media.addEventListener("change", onChange);
        return () => media.removeEventListener("change", onChange);
    }, []);

    useEffect(() => {
        document.documentElement.dataset.mode = resolvedTheme;
        document.documentElement.dataset.palette = colorTheme;
        setResolvedTheme(resolvedTheme);
        const color = getComputedStyle(document.documentElement).getPropertyValue("--app-bg").trim();
        document.querySelector('meta[name="theme-color"]')?.setAttribute("content", color);
    }, [resolvedTheme, colorTheme, setResolvedTheme]);

    useEffect(() => {
        const openGlobalSearch = event => {
            if (page !== "workspace" || !(event.ctrlKey || event.metaKey)) return;
            if (event.key.toLowerCase() === "p") {
                event.preventDefault();
                setGlobalSearch({mode: "name"});
            } else if (event.shiftKey && event.key.toLowerCase() === "f") {
                event.preventDefault();
                setGlobalSearch({mode: "content"});
            }
        };
        window.addEventListener("keydown", openGlobalSearch);
        return () => window.removeEventListener("keydown", openGlobalSearch);
    }, [page]);

    async function chooseSession(session) {
        if (running) return;
        setCurrent(session);
        setSelectedModel(session.model || meta?.defaultModel || "");
        setTokenUsage(emptyTokenUsage());
        if (mobile) useWorkspaceStore.getState().setMobilePane("chat");
        const history = await api(`/api/sessions/messages?sessionId=${encodeURIComponent(session.sessionId)}`);
        setMessages(restoreHistory(history));
    }

    async function openFile(path, location) {
        const data = await api(`/api/files/read?path=${encodeURIComponent(path)}`);
        openEditorFile(data);
        if (location?.line) {
            const workspace = useWorkspaceStore.getState();
            workspace.setFileViewMode(path, "edit");
            workspace.revealFileLocation(path, location);
        }
    }

    async function switchWorkspace(workspaceId) {
        if (running) throw new Error("请先停止当前智能体任务");
        if (hasDirtyFiles() && !window.confirm("当前有未保存文件，切换工作区将放弃这些修改。继续吗？")) return;
        await api("/api/workspaces/activate", {
            method: "POST",
            body: JSON.stringify({workspaceId})
        });
        await reloadWorkspace();
    }

    async function registerWorkspace(path) {
        if (running) throw new Error("请先停止当前智能体任务");
        if (hasDirtyFiles() && !window.confirm("当前有未保存文件，打开新工作区将放弃这些修改。继续吗？")) return;
        await api("/api/workspaces", {method: "POST", body: JSON.stringify({path})});
        await reloadWorkspace();
    }

    async function reloadWorkspace() {
        const [metaData, sessionData] = await Promise.all([api("/api/meta"), api("/api/sessions")]);
        setMeta(metaData);
        setSessions(sessionData || []);
        setSelectedModel(metaData.defaultModel || metaData.models?.[0]?.name || "");
        setCurrent(null);
        setMessages([]);
        setTokenUsage(emptyTokenUsage());
        resetEditor();
        setWorkspacePickerOpen(false);
        notify(`已打开 ${metaData.activeWorkspace?.name || "工作区"}`);
    }

    async function createSession() {
        const created = await api("/api/sessions", {
            method: "POST",
            body: JSON.stringify({title: "新的编码任务", model: selectedModel || meta?.defaultModel})
        });
        await loadSessions();
        await chooseSession(created);
    }

    async function deleteSession(event, session) {
        event.stopPropagation();
        if (!window.confirm(`删除会话“${session.title}”？`)) return;
        await api(`/api/sessions?sessionId=${encodeURIComponent(session.sessionId)}`, {method: "DELETE"});
        if (current?.sessionId === session.sessionId) {
            setCurrent(null);
            setMessages([]);
        }
        await loadSessions();
        notify("会话已删除");
    }

    async function renameSession(event, session) {
        event.stopPropagation();
        const title = window.prompt("重命名会话", session.title);
        if (title === null || title.trim() === session.title) return;
        if (!title.trim()) throw new Error("会话名称不能为空");
        const updated = await api("/api/sessions/title", {
            method: "POST",
            body: JSON.stringify({sessionId: session.sessionId, title: title.trim()})
        });
        setSessions(items => items.map(item =>
            item.sessionId === updated.sessionId ? {...item, ...updated} : item
        ));
        setCurrent(selected => selected?.sessionId === updated.sessionId
            ? {...selected, ...updated}
            : selected);
        notify("会话已重命名");
    }

    const mutateAssistant = useCallback((messageId, event) => {
        setMessages(items => items.map(message => {
            if (message.id !== messageId) return message;
            const next = {
                ...message,
                tools: [...(message.tools || [])],
                activities: [...(message.activities || [])],
                reasonBuffers: {...(message.reasonBuffers || {})},
                subagents: {...(message.subagents || {})},
                runtimeChunks: {...(message.runtimeChunks || {})}
            };
            if (event.type === "text" || event.type === "text_replay") {
                const merged = mergeReasonContentActivities(
                    next.activities,
                    next.reasonBuffers,
                    event);
                next.activities = merged.activities;
                next.reasonBuffers = merged.reasonBuffers;
                next.content = next.activities
                    .filter(item => item.type === "text")
                    .map(item => item.content)
                    .join("\n\n");
            }
            if (event.type === "thinking") {
                next.thinking = mergeStreamText(next.thinking, event.content);
                next.activities = mergeThinkingActivity(next.activities, event);
            }
            if (event.type === "tool_args_start") {
                next.activities = next.activities.map(item =>
                    item.type === "thinking" && item.active ? {...item, active: false} : item
                );
                const existing = next.tools.find(tool => tool.streamId === event.streamId);
                if (!existing) {
                    const tool = {
                        callId: `stream-${event.streamId || uid()}`,
                        streamId: event.streamId,
                        name: event.toolName || "正在生成工具调用",
                        argsText: "",
                        generating: true,
                        streamed: true,
                        running: false
                    };
                    next.tools.push(tool);
                    next.activities.push({id: `tool-${tool.callId}`, type: "tool", callId: tool.callId});
                }
            }
            if (event.type === "tool_args_delta") {
                let index = next.tools.findIndex(tool => tool.streamId === event.streamId);
                if (index < 0) {
                    const callId = `stream-${event.streamId || uid()}`;
                    next.tools.push({
                        callId, streamId: event.streamId,
                        name: event.toolName || "正在生成工具调用",
                        argsText: "", generating: true, streamed: true, running: false
                    });
                    next.activities.push({id: `tool-${callId}`, type: "tool", callId});
                    index = next.tools.length - 1;
                }
                next.tools[index] = {
                    ...next.tools[index],
                    name: event.toolName || next.tools[index].name,
                    argsText: `${next.tools[index].argsText || ""}${asText(event.content)}`,
                    generating: true
                };
            }
            if (event.type === "tool_args_end") {
                const index = next.tools.findIndex(tool => tool.streamId === event.streamId);
                if (index >= 0) next.tools[index] = {
                    ...next.tools[index],
                    name: event.toolName || next.tools[index].name,
                    argsText: asText(event.content) || next.tools[index].argsText,
                    generating: false
                };
            }
            if (event.type === "tool_start") {
                // 工具开始意味着它之前的模型思考阶段已经结束，即使供应商没有发出
                // finished=true 的最后一个 thinking 增量，也不能让卡片一直显示推理中。
                next.activities = next.activities.map(item =>
                    item.type === "thinking" && item.active ? {...item, active: false} : item
                );
                const streamedIndex = next.tools.findIndex(tool =>
                    tool.streamed && !tool.executionStarted
                    && (!event.toolName || tool.name === event.toolName)
                );
                if (streamedIndex >= 0) {
                    const previousCallId = next.tools[streamedIndex].callId;
                    const callId = event.callId || previousCallId;
                    next.tools[streamedIndex] = {
                        ...next.tools[streamedIndex], callId,
                        name: event.toolName || next.tools[streamedIndex].name,
                        args: event.args || {}, generating: false, running: true,
                        executionStarted: true, fileOperation: event.fileOperation
                    };
                    next.activities = next.activities.map(item =>
                        item.type === "tool" && item.callId === previousCallId
                            ? {...item, callId}
                            : item
                    );
                } else {
                    const tool = {
                        callId: event.callId || uid(), name: event.toolName || "tool",
                        args: event.args || {}, running: true, executionStarted: true,
                        fileOperation: event.fileOperation
                    };
                    next.tools.push(tool);
                    next.activities.push({id: `tool-${tool.callId}`, type: "tool", callId: tool.callId});
                }
            }
            if (event.type === "tool_end") {
                const index = next.tools.findIndex(tool => event.callId ? tool.callId === event.callId : tool.running);
                if (index >= 0) next.tools[index] = {
                    ...next.tools[index], running: false, durationMs: event.durationMs,
                    output: event.error || event.content, error: Boolean(event.error)
                };
            }
            if (event.type === "subagent_event") {
                const subagentId = subagentEventId(event);
                if (!next.activities.some(item => item.type === "subagent" && item.subagentId === subagentId)) {
                    next.activities.push({id: `subagent-${subagentId}`, type: "subagent", subagentId});
                }
                next.subagents = mergeSubagentEvent(next.subagents, {...event, subagentId});
            }
            if (event.type === "chunk" && event.content) {
                const chunkId = `${event.runId || "run"}-${event.chunkType || "chunk"}`;
                const currentChunk = next.runtimeChunks[chunkId] || {
                    id: chunkId,
                    chunkType: event.chunkType || "运行事件",
                    content: ""
                };
                next.runtimeChunks[chunkId] = {
                    ...currentChunk,
                    content: mergeStreamText(currentChunk.content, event.content)
                };
                if (!next.activities.some(item => item.type === "runtime_chunk" && item.chunkId === chunkId)) {
                    next.activities.push({id: `runtime-${chunkId}`, type: "runtime_chunk", chunkId});
                }
            }
            if (event.type === "hitl") {
                next.hitl = event;
                // HITL 是等待用户决策的正常暂停，不应和普通运行错误同时展示。
                next.error = null;
            }
            if (event.type === "done" && !next.content && event.content) next.content = event.content;
            if (event.type === "done" || event.type === "run_end" || event.type === "error") {
                next.finished = true;
                next.activities = next.activities.map(item =>
                    item.type === "thinking" && item.active ? {...item, active: false} : item
                );
            }
            if (event.type === "error") next.error = event.message || event.content || "智能体运行失败";
            return next;
        }));
    }, []);

    async function stream(path, body, messageId) {
        const response = await fetch(path, {
            method: "POST",
            headers: {"Content-Type": "application/json", "X-User-Id": userId},
            body: JSON.stringify(body)
        });
        if (!response.ok || !response.body) throw new Error(await response.text() || "流式连接不可用");
        await readEventStream(response.body, event => {
            trackTokenUsage(event);
            mutateAssistant(messageId, event);
        });
    }

    async function sendPrompt(text) {
        if (!current || running || !text.trim()) return;
        setTokenUsage(emptyTokenUsage());
        const assistantId = uid();
        setMessages(items => [
            ...items,
            {id: uid(), role: "user", content: text.trim(), tools: []},
            {
                id: assistantId,
                role: "assistant",
                content: "",
                thinking: "",
                tools: [],
                activities: [],
                reasonBuffers: {},
                subagents: {},
                runtimeChunks: {},
                finished: false
            }
        ]);
        setRunning(true);
        try {
            await stream("/api/chat/stream", {
                sessionId: current.sessionId,
                prompt: text.trim(),
                model: selectedModel || current.model,
                thinkingDepth
            }, assistantId);
        } catch (error) {
            mutateAssistant(assistantId, {type: "error", message: error.message});
        } finally {
            setRunning(false);
            await refreshAfterRun();
        }
    }

    async function decideHitl(messageId, action, alwaysAllow, callUuids) {
        if (!current || running) return;
        setRunning(true);
        try {
            setMessages(items => items.map(item => item.id === messageId
                ? {...item, hitl: null, error: null}
                : item));
            await stream("/api/hitl/decide", {
                sessionId: current.sessionId, action, alwaysAllow, callUuids
            }, messageId);
        } catch (error) {
            mutateAssistant(messageId, {type: "error", message: error.message});
        } finally {
            setRunning(false);
            await refreshAfterRun();
        }
    }

    async function stopRun() {
        if (!current) return;
        await api(`/api/chat/cancel?sessionId=${encodeURIComponent(current.sessionId)}`, {method: "POST"});
        notify("正在停止智能体");
    }

    async function changePermissionMode(permissionMode) {
        if (!current || running || current.permissionMode === permissionMode) return;
        if (permissionMode === "full" && !window.confirm(
            "完整权限模式会自动执行写文件、编辑和命令等操作，不再逐次请求审批。确定为当前会话开启吗？"
        )) return;
        const updated = await api("/api/sessions/permission", {
            method: "POST",
            body: JSON.stringify({sessionId: current.sessionId, permissionMode})
        });
        setCurrent(updated);
        setSessions(items => items.map(item =>
            item.sessionId === updated.sessionId ? {...item, ...updated} : item
        ));
        notify(permissionMode === "full" ? "已开启完整权限模式" : "已恢复标准权限模式");
    }

    async function changeSandbox(enabled) {
        if (running || meta?.sandboxEnabled === enabled) return;
        if (!enabled && !window.confirm(
            "关闭沙箱后，终端和文件工具可能访问工作区外的路径。确定为当前用户关闭吗？"
        )) return;
        const sandboxEnabled = await api("/api/settings/sandbox", {
            method: "POST",
            body: JSON.stringify({enabled})
        });
        setMeta(value => ({...value, sandboxEnabled}));
        notify(sandboxEnabled ? "已开启沙箱保护" : "已关闭沙箱保护");
    }

    function changeUser(nextUserId) {
        const value = nextUserId.trim();
        if (!/^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/.test(value)) {
            throw new Error("userId 仅支持字母、数字、点、下划线和短横线");
        }
        localStorage.setItem("udataHarnessUserId", value);
        setUserId(value);
        setCurrent(null);
        setMessages([]);
        setTokenUsage(emptyTokenUsage());
        setSelectedModel("");
        setMeta(null);
    }

    if (!userId) return <UserGate onSubmit={changeUser}/>;

    if (page === "admin") {
        return <>
            <AdminShell api={api} notify={notify} userId={userId} workspace={meta?.workspace}
                        onBack={() => {
                            window.location.hash = "";
                            setPage("workspace");
                        }}/>
            {notice && <div className="toast"><Check size={16}/>{notice}</div>}
        </>;
    }

    const title = current?.title || "开始一个新的任务";
    const sidebarOpen = !leftCollapsed;
    const activeModel = meta?.models?.find(item => item.name === selectedModel)
        || meta?.models?.[0];

    return (
        <div className={`app-shell workbench pane-${mobilePane} ${sidebarOpen ? "" : "sidebar-collapsed"} ${mobile ? "mobile-shell" : ""}`}
             style={{"--left-width": `${leftWidth}px`, "--editor-width": `${editorWidth}px`}}>
            <Sidebar
                open={sidebarOpen}
                view={leftTab}
                setView={setLeftTab}
                sessions={sessions}
                current={current}
                userId={userId}
                workspace={meta?.workspace}
                api={api}
                notify={notify}
                onOpenFile={path => openFile(path).catch(error => notify(error.message))}
                onOpenWorkspace={() => setWorkspacePickerOpen(true)}
                onCreate={() => createSession().catch(error => notify(error.message))}
                onSelect={session => chooseSession(session).catch(error => notify(error.message))}
                onRename={(event, session) => renameSession(event, session).catch(error => notify(error.message))}
                onDelete={(event, session) => deleteSession(event, session).catch(error => notify(error.message))}
                onOpenAdmin={() => {
                    window.location.hash = "/admin";
                    setPage("admin");
                }}
                onChangeUser={() => {
                    const value = window.prompt("切换 userId", userId);
                    if (value && value !== userId) {
                        try { changeUser(value); } catch (error) { notify(error.message); }
                    }
                }}
                onClose={toggleLeft}
            />
            <ResizeHandle axis="left"
                          onResize={delta => setLeftWidth(Math.max(220, Math.min(420, leftWidth + delta)))}
                          onReset={() => setLeftWidth(260)}/>
            <main className="workspace-shell">
                <header className="app-header">
                    <button className="icon-button sidebar-toggle" onClick={toggleLeft}
                            aria-label="切换侧栏">
                        {mobile ? <Menu size={20}/> : sidebarOpen ? <PanelLeftClose size={19}/> : <PanelLeftOpen size={19}/>}
                    </button>
                    <div className="header-title">
                        <h1>{title}</h1>
                        <span className="header-meta">
                            {running ? <><span className="pulse-dot"/> 智能体运行中</> :
                                <><span className="ready-dot"/> {meta?.models?.[0]?.model || "正在连接"}</>}
                        </span>
                    </div>
                    <button className="global-search-trigger" onClick={() => setGlobalSearch({mode: "content"})}>
                        <Search size={15}/><span>搜索工作区</span><kbd>Ctrl Shift F</kbd>
                    </button>
                    <div className="header-actions">
                        <div className="mobile-pane-switch" role="group" aria-label="主面板">
                            <button className={mobilePane === "chat" ? "active" : ""} onClick={() => setMobilePane("chat")}>对话</button>
                            <button className={mobilePane === "editor" ? "active" : ""} onClick={() => setMobilePane("editor")}>编辑器</button>
                        </div>
                        {running &&
                            <button className="stop-button" onClick={() => stopRun().catch(error => notify(error.message))}>
                                <CircleStop size={16}/> 停止
                            </button>}
                        <div className="model-chip"><Zap size={14}/>{selectedModel || meta?.defaultModel || "model"}</div>
                    </div>
                </header>

                <section className="content-shell">
                    <ChatView api={api} current={current} messages={messages} running={running}
                              models={meta?.models || []} selectedModel={selectedModel}
                              thinkingDepth={thinkingDepth}
                              tokenUsage={tokenUsage}
                              contextLength={activeModel?.contextLength || 1000000}
                              provider={activeModel?.provider}
                              sandboxEnabled={meta?.sandboxEnabled !== false}
                              onModelChange={setSelectedModel}
                              onThinkingDepthChange={value => {
                                  setThinkingDepth(value);
                                  localStorage.setItem("udataThinkingDepth", value);
                              }}
                              onOpenFile={path => openFile(path).catch(error => notify(error.message))}
                              onSend={sendPrompt} onCreate={() => createSession().catch(error => notify(error.message))}
                              onDecide={decideHitl}
                              onSandboxChange={enabled => changeSandbox(enabled)
                                  .catch(error => notify(error.message))}
                              onPermissionMode={mode => changePermissionMode(mode)
                                  .catch(error => notify(error.message))}/>
                </section>
            </main>
            <ResizeHandle axis="editor"
                          onResize={delta => setEditorWidth(Math.max(320, Math.min(680, editorWidth - delta)))}
                          onReset={() => setEditorWidth(420)}/>
            <EditorPanel api={api} notify={notify} editorTheme={resolvedTheme === "dark" ? "vs-dark" : "vs"}/>
            {globalSearch && <GlobalSearchDialog api={api} initialMode={globalSearch.mode}
                onClose={() => setGlobalSearch(null)}
                onOpenFile={openFile}/>}
            {workspacePickerOpen && <WorkspacePicker
                api={api}
                workspaces={meta?.workspaces || []}
                activeWorkspace={meta?.activeWorkspace}
                onActivate={workspaceId => switchWorkspace(workspaceId).catch(error => notify(error.message))}
                onRegister={path => registerWorkspace(path).catch(error => notify(error.message))}
                onClose={() => setWorkspacePickerOpen(false)}
            />}
            {notice && <div className="toast"><Check size={16}/>{notice}</div>}
        </div>
    );
}

function AdminShell({api, notify, userId, workspace, onBack}) {
    const [section, setSection] = useState("mcp");
    const sections = [
        {id: "mcp", label: "MCP 管理", description: "外部工具服务", icon: Network},
        {id: "skills", label: "Skill 管理", description: "可复用能力包", icon: Sparkles},
        {id: "subagents", label: "SubAgent 管理", description: "专项智能体", icon: Bot}
    ];
    const active = sections.find(item => item.id === section);

    return <div className="admin-shell">
        <aside className="admin-sidebar">
            <div className="admin-brand">
                <span className="brand-symbol"><Settings2 size={19}/></span>
                <span><b>管理后台</b><small>UdataBuddy Harness</small></span>
            </div>
            <button className="admin-back-button" onClick={onBack}><ArrowLeft size={17}/>返回对话工作台</button>
            <div className="admin-nav-label">能力与集成</div>
            <nav className="admin-nav">
                {sections.map(item => {
                    const Icon = item.icon;
                    return <button key={item.id} className={section === item.id ? "active" : ""}
                                   onClick={() => setSection(item.id)}>
                        <span className={`admin-nav-icon ${item.id}`}><Icon size={18}/></span>
                        <span><b>{item.label}</b><small>{item.description}</small></span>
                        <ChevronRight size={15}/>
                    </button>;
                })}
            </nav>
            <div className="admin-user">
                <span className="avatar">{userId.slice(0, 1).toUpperCase()}</span>
                <span><b>{userId}</b><small title={workspace}>{workspace || "正在准备工作区"}</small></span>
            </div>
        </aside>
        <main className="admin-main">
            <header className="admin-header">
                <div>
                    <span>UDATABUDDY ADMIN</span>
                    <h1>{active?.label}</h1>
                </div>
                <button onClick={onBack}><ArrowLeft size={16}/>返回对话</button>
            </header>
            <section className="admin-content">
                {section === "mcp" && <IntegrationsView api={api} notify={notify}/>}
                {section === "skills" && <CapabilitiesView api={api} notify={notify} section="skills"/>}
                {section === "subagents" && <CapabilitiesView api={api} notify={notify} section="subagents"/>}
            </section>
        </main>
    </div>;
}

function UserGate({onSubmit}) {
    const [value, setValue] = useState("local-user");
    const [error, setError] = useState("");
    return (
        <div className="user-gate">
            <div className="gate-glow"/>
            <form className="gate-card" onSubmit={event => {
                event.preventDefault();
                try { onSubmit(value); } catch (e) { setError(e.message); }
            }}>
                <div className="brand-orb"><Sparkles size={25}/></div>
                <span className="eyebrow">SOLON AI WORKSPACE</span>
                <h1>欢迎使用 UdataBuddy</h1>
                <p>输入用户标识，为你创建隔离的会话、文件和智能体工作区。</p>
                <label>用户标识</label>
                <div className="gate-input"><UserRound size={18}/><input value={value}
                    onChange={event => setValue(event.target.value)} autoFocus/></div>
                {error && <div className="field-error">{error}</div>}
                <button className="primary-button gate-submit">进入工作台 <ChevronRight size={18}/></button>
            </form>
        </div>
    );
}

function Sidebar({open, view, setView, sessions, current, userId, workspace, api, notify, onOpenFile,
                     onOpenWorkspace, onCreate, onSelect, onRename, onDelete, onOpenAdmin, onChangeUser, onClose}) {
    return (
        <aside className="sidebar" aria-label="工作区导航">
            <div className="brand">
                <div className="brand-symbol"><Sparkles size={19}/></div>
                {open && <div><strong>UdataBuddy</strong><span>AI Agent Workspace</span></div>}
                {open && <button className="sidebar-close" onClick={onClose} aria-label="关闭导航"><X size={18}/></button>}
            </div>
            {open && <button className="workspace-switcher" onClick={onOpenWorkspace} title={workspace}>
                <HardDrive size={16}/><span><b>{workspace?.split(/[\\/]/).pop() || "选择工作区"}</b><small>{workspace}</small></span>
                <ChevronDown size={14}/>
            </button>}
            <nav className="workspace-tabs" aria-label="侧栏视图">
                {navItems.map(item => {
                    const Icon = item.icon;
                    return <button key={item.id} className={view === item.id ? "active" : ""}
                                   onClick={() => setView(item.id)} title={item.label} aria-label={item.label}>
                        <Icon size={17}/>{open && <span>{item.label}</span>}
                    </button>;
                })}
            </nav>
            {open && <div className="sidebar-content">
                {view === "files"
                    ? <ExplorerPanel api={api} notify={notify} onOpenFile={onOpenFile}/>
                    : <SessionPanel sessions={sessions} current={current} onCreate={onCreate}
                                    onSelect={onSelect} onRename={onRename} onDelete={onDelete}/>}
            </div>}
            <div className="sidebar-footer">
                <ThemePicker expanded={open}/>
                <button className="sidebar-tool" onClick={onOpenAdmin} title="后台管理"><Settings2 size={17}/>{open && <span>后台管理</span>}</button>
                <button className="profile-card" onClick={onChangeUser} title="切换用户">
                    <span className="avatar">{userId.slice(0, 1).toUpperCase()}</span>
                    {open && <span className="profile-copy"><b>{userId}</b><small>切换用户</small></span>}
                </button>
            </div>
        </aside>
    );
}

function SessionPanel({sessions, current, onCreate, onSelect, onRename, onDelete}) {
    return <div className="session-panel">
        <button className="new-chat-button" onClick={onCreate}><Plus size={17}/><span>新建会话</span></button>
        <div className="section-label"><span>当前工作区会话</span><MoreHorizontal size={15}/></div>
        <div className="session-scroll">
            {sessions.length === 0 && <div className="sidebar-empty">这个工作区还没有会话</div>}
            {sessions.map(session => <button key={session.sessionId}
                className={`session-item ${current?.sessionId === session.sessionId ? "active" : ""}`}
                onClick={() => onSelect(session)}>
                <MessageSquare size={15}/>
                <span><b>{session.title}</b><small>{formatTime(session.updatedAt)}</small></span>
                {session.active && <i className="session-live"/>}
                <span className="session-actions">
                    <span className="session-rename" role="button" tabIndex={0} title="重命名会话"
                          onClick={event => onRename(event, session)}><Pencil size={13}/></span>
                    <span className="session-delete" role="button" tabIndex={0} title="删除会话"
                          onClick={event => onDelete(event, session)}><Trash2 size={14}/></span>
                </span>
            </button>)}
        </div>
    </div>;
}

function flattenFiles(items = []) {
    return items.flatMap(item => [
        {name: item.name, path: item.path, type: item.type},
        ...flattenFiles(item.children || [])
    ]);
}

function findCompletionTrigger(value, cursor) {
    const beforeCursor = value.slice(0, cursor);
    const match = beforeCursor.match(/(^|\s)([/@#])([^\s/@#]*)$/);
    if (!match) return null;
    return {
        symbol: match[2],
        query: match[3].toLowerCase(),
        start: beforeCursor.length - match[2].length - match[3].length,
        end: cursor
    };
}

function activeFileReference(path, selection) {
    if (!path) return "";
    if (!selection || selection.empty) return `@${path}`;
    return `@${path}#L${selection.startLine}-L${selection.endLine}`;
}

function ChatView({api, current, messages, running, models, selectedModel, thinkingDepth,
                      tokenUsage, contextLength, provider, sandboxEnabled,
                      onModelChange, onThinkingDepthChange, onSend, onCreate, onDecide,
                      onPermissionMode, onSandboxChange, onOpenFile}) {
    const [prompt, setPrompt] = useState("");
    const [completionSources, setCompletionSources] = useState({skills: [], files: [], agents: []});
    const [completion, setCompletion] = useState(null);
    const [activeCompletion, setActiveCompletion] = useState(0);
    const [showScrollToBottom, setShowScrollToBottom] = useState(false);
    const textareaRef = useRef(null);
    const endRef = useRef(null);
    const scrollContainerRef = useRef(null);
    const autoFollowRef = useRef(true);
    const programmaticScrollRef = useRef(false);
    const scrollTimerRef = useRef(null);
    const activePath = useWorkspaceStore(state => state.activePath);
    const selections = useWorkspaceStore(state => state.selections);
    const referenceEnabled = useWorkspaceStore(state => state.referenceEnabled);
    const toggleReference = useWorkspaceStore(state => state.toggleReference);
    const promptInsertion = useWorkspaceStore(state => state.promptInsertion);
    const consumePromptInsertion = useWorkspaceStore(state => state.consumePromptInsertion);
    const fileReference = referenceEnabled
        ? activeFileReference(activePath, selections[activePath])
        : "";

    const scrollToBottom = useCallback((behavior = "auto") => {
        const element = scrollContainerRef.current;
        if (!element) return;
        autoFollowRef.current = true;
        programmaticScrollRef.current = behavior === "smooth";
        setShowScrollToBottom(false);
        element.scrollTo({top: element.scrollHeight, behavior});
        if (scrollTimerRef.current) window.clearTimeout(scrollTimerRef.current);
        if (behavior === "smooth") {
            scrollTimerRef.current = window.setTimeout(() => {
                programmaticScrollRef.current = false;
            }, 500);
        }
    }, []);

    const handleMessageScroll = () => {
        const element = scrollContainerRef.current;
        if (!element) return;
        const nearBottom = element.scrollHeight - element.scrollTop - element.clientHeight <= 60;
        if (programmaticScrollRef.current) {
            if (nearBottom) programmaticScrollRef.current = false;
            return;
        }
        autoFollowRef.current = nearBottom;
        setShowScrollToBottom(!nearBottom);
    };

    const pauseAutoFollow = () => {
        programmaticScrollRef.current = false;
        autoFollowRef.current = false;
        setShowScrollToBottom(true);
    };

    useEffect(() => {
        autoFollowRef.current = true;
        setShowScrollToBottom(false);
        window.requestAnimationFrame(() => scrollToBottom());
    }, [current?.sessionId, scrollToBottom]);

    useEffect(() => {
        if (!autoFollowRef.current) {
            setShowScrollToBottom(true);
            return undefined;
        }
        const frame = window.requestAnimationFrame(() => scrollToBottom());
        return () => window.cancelAnimationFrame(frame);
    }, [messages, running, scrollToBottom]);

    useEffect(() => () => {
        if (scrollTimerRef.current) window.clearTimeout(scrollTimerRef.current);
    }, []);

    useEffect(() => {
        if (!promptInsertion) return;
        setPrompt(current => {
            const separator = current && !/\s$/.test(current) ? " " : "";
            return `${current}${separator}${promptInsertion.text} `;
        });
        consumePromptInsertion(promptInsertion.id);
        window.requestAnimationFrame(() => textareaRef.current?.focus());
    }, [promptInsertion, consumePromptInsertion]);

    useEffect(() => {
        let cancelled = false;
        if (!current) {
            setCompletionSources({skills: [], files: [], agents: []});
            return () => { cancelled = true; };
        }
        Promise.all([
            api("/api/capabilities/skills"),
            api("/api/files/tree?depth=12"),
            api("/api/capabilities/agents")
        ]).then(([skills, tree, agents]) => {
            if (cancelled) return;
            setCompletionSources({
                skills: (skills || []).filter(skill => skill.active || skill.loaded),
                files: flattenFiles(tree || []),
                agents: agents || []
            });
        }).catch(() => {
            if (!cancelled) setCompletionSources({skills: [], files: [], agents: []});
        });
        return () => { cancelled = true; };
    }, [api, current]);

    const completionItems = useMemo(() => {
        if (!completion) return [];
        const filter = (items, fields) => items
            .filter(item => fields.some(field => (item[field] || "").toLowerCase().includes(completion.query)))
            .slice(0, 8);
        if (completion.symbol === "/") {
            return filter(completionSources.skills, ["name", "description"]).map(skill => ({
                key: skill.name,
                value: `/${skill.name}`,
                title: skill.name,
                description: skill.description || "已加载 Skill",
                icon: Sparkles,
                type: "Skill"
            }));
        }
        if (completion.symbol === "@") {
            return filter(completionSources.files, ["name", "path"]).map(file => ({
                key: file.path,
                value: `@${file.path}`,
                title: file.name,
                description: file.path,
                icon: file.type === "directory" ? Folder : FileCode2,
                type: file.type === "directory" ? "目录" : "文件"
            }));
        }
        return filter(completionSources.agents, ["name", "description"]).map(agent => ({
            key: agent.name,
            value: `#${agent.name}`,
            title: agent.name,
            description: agent.description || "专项智能体",
            icon: Bot,
            type: "SubAgent"
        }));
    }, [completion, completionSources]);

    useEffect(() => {
        setActiveCompletion(0);
    }, [completion?.symbol, completion?.query]);

    const refreshCompletion = (value, cursor) => {
        setCompletion(findCompletionTrigger(value, cursor));
    };

    const selectCompletion = item => {
        if (!completion || !item) return;
        const next = `${prompt.slice(0, completion.start)}${item.value} ${prompt.slice(completion.end)}`;
        const nextCursor = completion.start + item.value.length + 1;
        setPrompt(next);
        setCompletion(null);
        window.requestAnimationFrame(() => {
            textareaRef.current?.focus();
            textareaRef.current?.setSelectionRange(nextCursor, nextCursor);
        });
    };

    const submit = () => {
        if (!prompt.trim() || !current || running) return;
        const alreadyReferencesActiveFile = activePath && prompt.includes(`@${activePath}`);
        const value = fileReference && !alreadyReferencesActiveFile
            ? `${fileReference}\n\n${prompt.trim()}`
            : prompt;
        setPrompt("");
        setCompletion(null);
        scrollToBottom();
        onSend(value);
    };

    if (!current) {
        return <div className="welcome-view">
            <div className="welcome-icon"><Bot size={33}/></div>
            <span className="eyebrow">SOLON HARNESS AGENT</span>
            <h2>今天想构建什么？</h2>
            <p>创建一个会话，让智能体读取代码、编辑文件、运行命令，并协调 Skill 与 Subagent 完成复杂任务。</p>
            <button className="primary-button" onClick={onCreate}><Plus size={18}/> 创建第一个会话</button>
            <div className="feature-row">
                <Feature icon={FileCode2} title="理解项目" text="搜索并分析完整代码库"/>
                <Feature icon={SquareTerminal} title="执行任务" text="运行命令并验证结果"/>
                <Feature icon={ShieldCheck} title="权限可控" text="HITL 审批敏感操作"/>
            </div>
        </div>;
    }

    return <div className="chat-layout">
        <div className="message-stage">
        <div ref={scrollContainerRef} className="message-scroll" onScroll={handleMessageScroll}
             onWheel={event => event.deltaY < 0 && pauseAutoFollow()}
             onTouchMove={() => {
                 const element = scrollContainerRef.current;
                 if (element && element.scrollHeight - element.scrollTop - element.clientHeight > 60) {
                     pauseAutoFollow();
                 }
             }}>
            <div className="message-column">
                {messages.length === 0 && <div className="conversation-empty">
                    <div className="mini-orb"><Sparkles size={20}/></div>
                    <h3>会话已就绪</h3>
                    <p>描述目标、粘贴错误信息，或让智能体先浏览当前项目。</p>
                    <div className="suggestion-grid">
                        {["分析项目结构并给出改进建议", "查找潜在 Bug 并补充测试", "使用 Subagent 并行理解核心模块"].map(text =>
                            <button key={text} onClick={() => setPrompt(text)}>{text}<ChevronRight size={15}/></button>)}
                    </div>
                </div>}
                {messages.map(message =>
                    <Message key={message.id} message={message}
                             onOpenFile={onOpenFile}
                             onDecide={(action, always, callUuids) =>
                                 onDecide(message.id, action, always, callUuids)}/>)}
                {running && messages.at(-1)?.role !== "assistant" &&
                    <div className="typing-row"><span/><span/><span/></div>}
                <div ref={endRef}/>
            </div>
        </div>
        {showScrollToBottom && <button className="scroll-to-bottom" type="button"
            onClick={() => scrollToBottom("smooth")}
            aria-label={running ? "回到底部并继续跟随 AI 回复" : "回到底部"}>
            <ArrowDown size={14}/><span>{running ? "AI 正在回复" : "回到底部"}</span>
        </button>}
        </div>
        <div className="composer-wrap">
            <div className={`permission-mode-bar ${current.permissionMode === "full" ? "full" : ""}`}>
                <div className="permission-mode-copy">
                    {current.permissionMode === "full" ? <Zap size={15}/> : <ShieldCheck size={15}/>}
                    <span>
                        <b>{current.permissionMode === "full" ? "完整权限" : "标准权限"}</b>
                        <small>{current.permissionMode === "full"
                            ? "当前会话的工具调用自动执行，无需审批"
                            : "敏感工具执行前会询问你的许可"}</small>
                    </span>
                </div>
                <div className="permission-mode-controls">
                    <button className={`sandbox-toggle ${sandboxEnabled ? "active" : "off"}`}
                            type="button" role="switch" aria-checked={sandboxEnabled}
                            disabled={running} onClick={() => onSandboxChange(!sandboxEnabled)}
                            title="控制当前用户工作区的文件与终端沙箱">
                        {sandboxEnabled ? <ShieldCheck size={13}/> : <ShieldOff size={13}/>}沙箱
                        <b>{sandboxEnabled ? "开" : "关"}</b>
                    </button>
                    <div className="permission-mode-switch" role="group" aria-label="权限模式">
                        <button className={current.permissionMode !== "full" ? "active" : ""}
                                disabled={running} onClick={() => onPermissionMode("standard")}>
                            标准
                        </button>
                        <button className={current.permissionMode === "full" ? "active" : ""}
                                disabled={running} onClick={() => onPermissionMode("full")}>
                            完整权限
                        </button>
                    </div>
                </div>
            </div>
            <div className="composer-input-shell">
                {completion && <div className="completion-menu" role="listbox">
                    <div className="completion-heading">
                        <span>{completion.symbol === "/" ? "选择 Skill" : completion.symbol === "@" ? "选择文件或目录" : "选择 SubAgent"}</span>
                        <small>↑↓ 选择 · Enter/Tab 补全 · Esc 关闭</small>
                    </div>
                    <div className="completion-list">
                        {completionItems.length > 0 ? completionItems.map((item, index) => {
                            const Icon = item.icon;
                            return <button key={item.key}
                                           className={index === activeCompletion ? "active" : ""}
                                           role="option"
                                           aria-selected={index === activeCompletion}
                                           onMouseDown={event => {
                                               event.preventDefault();
                                               selectCompletion(item);
                                           }}
                                           onMouseEnter={() => setActiveCompletion(index)}>
                                <span className={`completion-icon symbol-${completion.symbol === "/" ? "skill" : completion.symbol === "@" ? "file" : "agent"}`}>
                                    <Icon size={16}/>
                                </span>
                                <span className="completion-copy">
                                    <b>{completion.symbol}{item.title}</b>
                                    <small>{item.description}</small>
                                </span>
                                <span className="completion-type">{item.type}</span>
                            </button>;
                        }) : <div className="completion-empty">
                            {completion.symbol === "/"
                                ? "没有匹配的已加载 Skill"
                                : completion.symbol === "@"
                                    ? "没有匹配的工作区文件或目录"
                                    : "没有匹配的 SubAgent"}
                        </div>}
                    </div>
                </div>}
                <div className={`composer-box ${running ? "running" : ""}`}>
                    <div className="composer-config-bar">
                        <label>
                            <Bot size={13}/><span>模型</span>
                            <select value={selectedModel} disabled={running}
                                    onChange={event => onModelChange(event.target.value)}>
                                {models.map(item => <option key={item.name} value={item.name}>
                                    {item.name}
                                </option>)}
                            </select>
                        </label>
                        <label>
                            <BrainCircuit size={13}/><span>思考</span>
                            <select value={thinkingDepth} disabled={running}
                                    onChange={event => onThinkingDepthChange(event.target.value)}>
                                {thinkingDepthOptions.map(item => <option key={item.value} value={item.value}>
                                    {item.label}
                                </option>)}
                            </select>
                        </label>
                    </div>
                    {fileReference && <div className="context-reference" title={fileReference}>
                        <FileCode2 size={14}/><code>{fileReference}</code>
                    </div>}
                    <textarea ref={textareaRef} value={prompt} disabled={running}
                              placeholder="输入任务，使用 / Skill、@ 文件或目录、# SubAgent…"
                              onBlur={() => window.setTimeout(() => setCompletion(null), 120)}
                              onClick={event => refreshCompletion(prompt, event.currentTarget.selectionStart)}
                              onChange={event => {
                                  const value = event.target.value;
                                  setPrompt(value);
                                  refreshCompletion(value, event.target.selectionStart);
                              }}
                              onKeyDown={event => {
                                  if (completion && completionItems.length > 0) {
                                      if (event.key === "ArrowDown" || event.key === "ArrowUp") {
                                          event.preventDefault();
                                          const direction = event.key === "ArrowDown" ? 1 : -1;
                                          setActiveCompletion(index =>
                                              (index + direction + completionItems.length) % completionItems.length);
                                          return;
                                      }
                                      if (event.key === "Enter" || event.key === "Tab") {
                                          event.preventDefault();
                                          selectCompletion(completionItems[activeCompletion]);
                                          return;
                                      }
                                  }
                                  if (event.key === "Escape" && completion) {
                                      event.preventDefault();
                                      setCompletion(null);
                                      return;
                                  }
                                  if (event.key === "Enter" && !event.shiftKey) {
                                      event.preventDefault();
                                      submit();
                                  }
                              }}/>
                    <div className="composer-footer">
                        <div className="composer-tools">
                            <button className={`reference-toggle ${referenceEnabled ? "active" : ""}`}
                                    type="button"
                                    aria-pressed={referenceEnabled}
                                    title={referenceEnabled ? "关闭自动引用" : "开启自动引用"}
                                    onClick={toggleReference}>
                                <Link2 size={13}/>引用
                            </button>
                            <span><Activity size={14}/>{running ? "正在执行任务" : "/ Skill · @ 文件/目录 · # SubAgent"}</span>
                        </div>
                        <button className="send-button" onClick={submit} disabled={!prompt.trim() || running}>
                            <Send size={17}/>
                        </button>
                    </div>
                </div>
            </div>
            <TokenMeter usage={tokenUsage} contextLength={contextLength}
                        provider={provider} running={running}/>
        </div>
    </div>;
}

function Feature({icon: Icon, title, text}) {
    return <div className="feature-card"><Icon size={19}/><div><b>{title}</b><span>{text}</span></div></div>;
}

function TokenMeter({usage, contextLength, provider, running}) {
    const cacheTokens = usage.cacheReadInputTokens + usage.cacheCreationInputTokens;
    const hitRate = cacheHitRate(usage, provider);
    const hitRateText = hitRate == null ? "—" : `${hitRate.toFixed(1)}%`;
    const hitRateFormula = String(provider || "").toLowerCase().includes("anthropic")
        ? "缓存读取 ÷（输入 + 缓存读取 + 缓存创建）"
        : "缓存读取 ÷ 输入";
    const speed = usage.tokensPerSecond > 0 ? `${usage.tokensPerSecond.toFixed(1)} tok/s` : "— tok/s";
    const details = [
        `输入 ${formatTokens(usage.promptTokens)}`,
        `思考 ${formatTokens(usage.thinkTokens)}`,
        `输出 ${formatTokens(usage.completionTokens)}`,
        `缓存读取 ${formatTokens(usage.cacheReadInputTokens)}`,
        `缓存创建 ${formatTokens(usage.cacheCreationInputTokens)}`,
        `缓存命中率 ${hitRateText}`,
        `命中率口径 ${hitRateFormula}`,
        `平均速度 ${speed}`,
        `模型上下文 ${formatTokens(contextLength)}`
    ].join(" · ");
    return <div className={`composer-token-meter ${running ? "running" : ""}`} role="status" title={details}>
        <Activity size={15}/>
        <b>本轮 {formatTokens(usage.totalTokens)} tokens</b>
        <span>输入 {formatTokens(usage.promptTokens)}</span>
        <span>输出 {formatTokens(usage.completionTokens)}</span>
        <span>缓存 {formatTokens(cacheTokens)}</span>
        <span>命中 {hitRateText}</span>
        <span>{speed}</span>
        <i>上下文 {formatTokens(contextLength)}</i>
    </div>;
}

function Message({message, onDecide, onOpenFile}) {
    const parsed = message.role === "assistant"
        ? splitThinkContent(message.content || "")
        : {visible: message.content || "", thinking: "", pending: false};
    const thinking = [message.thinking, parsed.thinking].filter(Boolean).join("\n").trim();
    const activities = message.activities || [];
    const hasThinkingActivities = activities.some(item => item.type === "thinking");
    const hasTextActivities = activities.some(item => item.type === "text");
    const activityToolIds = new Set(
        activities.filter(item => item.type === "tool").map(item => item.callId)
    );
    return <article className={`message-row ${message.role}`}>
        <div className="message-avatar">
            {message.role === "user" ? <UserRound size={17}/> : <Sparkles size={17}/>}
        </div>
        <div className="message-body">
            <div className="message-author">{message.role === "user" ? "你" : "UdataBuddy"}</div>
            {!hasThinkingActivities && thinking && <Thinking content={thinking} active={parsed.pending}/>}
            {activities.map(activity => {
                if (activity.type === "thinking") {
                    return <Thinking key={`thinking-${activity.id}`} content={activity.content}
                                     active={activity.active}/>;
                }
                if (activity.type === "tool") {
                    const tool = message.tools?.find(item => item.callId === activity.callId);
                    return tool ? <ToolCall key={tool.callId} tool={tool}/> : null;
                }
                if (activity.type === "subagent") {
                    const subagent = message.subagents?.[activity.subagentId];
                    return subagent ? <SubagentCard key={activity.id} subagent={subagent}/> : null;
                }
                if (activity.type === "runtime_chunk") {
                    const chunk = message.runtimeChunks?.[activity.chunkId];
                    return chunk ? <RuntimeChunkCard key={activity.id} chunk={chunk}/> : null;
                }
                if (activity.type === "text" && activity.content) {
                    return <div className="markdown-body assistant timeline-text" key={activity.id}>
                        <ReactMarkdown remarkPlugins={[remarkGfm]}>{activity.content}</ReactMarkdown>
                    </div>;
                }
                return null;
            })}
            {message.tools?.filter(tool => !activityToolIds.has(tool.callId))
                .map(tool => <ToolCall key={tool.callId} tool={tool}/>)}
            {!hasTextActivities && parsed.visible && <div className={`markdown-body ${message.role}`}>
                {message.role === "assistant"
                    ? <ReactMarkdown remarkPlugins={[remarkGfm]}>{parsed.visible}</ReactMarkdown>
                    : parsed.visible}
            </div>}
            {message.role === "assistant" && message.finished &&
                <FileActivitySummary tools={message.tools} activities={message.fileActivities}
                                     onOpenFile={onOpenFile}/>}
            {!parsed.visible && !thinking && !message.tools?.length
                && !Object.keys(message.subagents || {}).length
                && !Object.keys(message.runtimeChunks || {}).length
                && message.role === "assistant" && !message.error &&
                <div className="typing-row inline"><span/><span/><span/></div>}
            {message.hitl && <HitlCard event={message.hitl} onDecide={onDecide}/>}
            {message.error && <div className="message-error"><X size={16}/>{message.error}</div>}
        </div>
    </article>;
}

function FileActivitySummary({tools, activities, onOpenFile}) {
    const [open, setOpen] = useState(true);
    const activity = fileActivityFromTools(tools, activities);
    const sections = [
        {id: "read", label: "读取", icon: Search, paths: activity.read},
        {id: "created", label: "创建", icon: FilePlus2, paths: activity.created},
        {id: "modified", label: "修改", icon: Pencil, paths: activity.modified}
    ].filter(section => section.paths.length > 0);
    const total = sections.reduce((sum, section) => sum + section.paths.length, 0);
    if (total === 0) return null;
    return <section className="file-activity-summary" aria-label="本轮文件活动">
        <button className="file-activity-heading" onClick={() => setOpen(value => !value)}
                aria-expanded={open}>
            <span className="file-activity-icon"><Files size={15}/></span>
            <span><b>本轮文件活动</b><small>{total} 项文件记录</small></span>
            <span className="file-activity-counts">
                {activity.read.length > 0 && <i>{activity.read.length} 读</i>}
                {activity.created.length > 0 && <i>{activity.created.length} 建</i>}
                {activity.modified.length > 0 && <i>{activity.modified.length} 改</i>}
            </span>
            {open ? <ChevronDown size={15}/> : <ChevronRight size={15}/>}
        </button>
        {open && <div className="file-activity-groups">
            {sections.map(section => {
                const Icon = section.icon;
                return <div className={`file-activity-group ${section.id}`} key={section.id}>
                    <div><Icon size={13}/><b>{section.label}</b></div>
                    <div>{section.paths.map(path => <button key={path} title={path}
                        onClick={() => onOpenFile?.(path)}><FileCode2 size={13}/><code>{path}</code></button>)}</div>
                </div>;
            })}
        </div>}
    </section>;
}

function Thinking({content, active = false}) {
    const [open, setOpen] = useState(active);
    useEffect(() => {
        setOpen(active);
    }, [active]);
    const preview = content.replace(/\s+/g, " ").trim().slice(0, 88);
    return <div className={`thinking-card ${active ? "active" : ""}`}>
        <button onClick={() => setOpen(value => !value)} aria-expanded={open}>
            <span className="thinking-icon"><BrainCircuit size={16}/></span>
            <span className="thinking-title">
                <b>{active ? "正在思考" : "已完成思考"}</b>
                {!open && <small>{preview}{content.length > 88 ? "…" : ""}</small>}
            </span>
            {active && <span className="thinking-status"><i/> 推理中</span>}
            {open ? <ChevronDown size={16}/> : <ChevronRight size={16}/>}
        </button>
        <div className={`thinking-collapse ${open ? "open" : ""}`} aria-hidden={!open}>
            <div className="thinking-content"><div className="thinking-line"/><pre>{content}</pre></div>
        </div>
    </div>;
}

function SubagentCard({subagent}) {
    const running = subagent.status === "running";
    const [open, setOpen] = useState(false);
    const activities = subagent.activities || [];
    const activityToolIds = new Set(
        activities.filter(item => item.type === "tool").map(item => item.callId)
    );
    const shortId = String(subagent.taskId || subagent.subagentId || "task")
        .split(":").at(-1).slice(-6);
    const status = subagent.status === "success" ? "已完成"
        : subagent.status === "error" ? "执行失败"
            : subagent.status === "pending" ? "等待中" : "执行中";
    const usage = subagent.usage?.totalTokens
        ? `${formatTokens(subagent.usage.totalTokens)} tokens`
        : subagent.durationMs != null ? `${subagent.durationMs} ms` : "";
    return <section className={`subagent-card ${subagent.status || "running"}`}>
        <button className="subagent-heading" onClick={() => setOpen(value => !value)} aria-expanded={open}>
            <span className="subagent-icon">{running
                ? <RefreshCw className="spin" size={15}/>
                : subagent.status === "error" ? <X size={15}/> : <Bot size={15}/>}</span>
            <span className="subagent-title">
                <b>{subagent.agentName}</b>
                <small>{subagent.description}</small>
            </span>
            <i title={subagent.subagentId}>{subagent.multitask
                ? `并行 #${subagent.index} · ${shortId}`
                : `任务 · ${shortId}`}</i>
            <span className="subagent-status">{status}{usage ? ` · ${usage}` : ""}</span>
            {open ? <ChevronDown size={15}/> : <ChevronRight size={15}/>}
        </button>
        {open && <div className="subagent-content">
            {activities.map(activity => {
                if (activity.type === "thinking") {
                    return <Thinking key={`subagent-thinking-${activity.id}`}
                                     content={activity.content} active={activity.active}/>;
                }
                if (activity.type === "text" && activity.content) {
                    return running
                        ? <pre className="subagent-stream" key={activity.id}>{activity.content}</pre>
                        : <div className="markdown-body assistant subagent-text" key={activity.id}>
                            <ReactMarkdown remarkPlugins={[remarkGfm]}>{activity.content}</ReactMarkdown>
                        </div>;
                }
                if (activity.type === "tool") {
                    const tool = subagent.tools?.find(item => item.callId === activity.callId);
                    return tool ? <ToolCall key={`subagent-tool-${tool.callId}`} tool={tool}/> : null;
                }
                return null;
            })}
            {subagent.tools?.filter(tool => !activityToolIds.has(tool.callId)
                && (tool.executionStarted || tool.output || tool.error))
                .map(tool => <ToolCall key={`subagent-extra-tool-${tool.callId}`} tool={tool}/>)}
            {subagent.error && <div className="message-error"><X size={15}/>{subagent.error}</div>}
            {!activities.length && !subagent.tools?.length && running &&
                <div className="typing-row inline"><span/><span/><span/></div>}
        </div>}
    </section>;
}

function RuntimeChunkCard({chunk}) {
    const [open, setOpen] = useState(false);
    return <section className="runtime-chunk-card">
        <button onClick={() => setOpen(value => !value)} aria-expanded={open}>
            <span><Code2 size={14}/><b>运行事件</b><small>{chunk.chunkType}</small></span>
            {open ? <ChevronDown size={15}/> : <ChevronRight size={15}/>}
        </button>
        {open && <pre>{chunk.content}</pre>}
    </section>;
}

function ToolCall({tool}) {
    const [open, setOpen] = useState(Boolean(tool.generating));
    useEffect(() => {
        if (tool.generating) setOpen(true);
    }, [tool.generating]);
    const chartSpec = chartSpecFromTool(tool);
    const status = tool.generating ? "参数生成中" : tool.running ? "执行中" : `${tool.durationMs || 0} ms`;
    const details = tool.executionStarted
        ? JSON.stringify(tool.args || {}, null, 2)
        : tool.argsText || "";
    const showDetails = Boolean(details || tool.output);
    return <>
        <div className={`tool-call ${tool.generating ? "generating" : ""} ${tool.error ? "error" : ""}`}>
            <button onClick={() => setOpen(value => !value)}>
                <span className="tool-icon">{tool.generating || tool.running
                    ? <RefreshCw className="spin" size={15}/>
                    : <Check size={15}/>}</span>
                <span><b>{tool.name}</b><small>{status}</small></span>
                {open ? <ChevronDown size={15}/> : <ChevronRight size={15}/>}
            </button>
            {open && showDetails && <pre className={tool.generating ? "streaming-args" : ""}>
                {details}{tool.output ? `\n\n${tool.output}` : ""}
            </pre>}
        </div>
        {tool.name === "render_chart" && !tool.running && <ChartCard spec={chartSpec}/>}
        {tool.name === "render_echart" && !tool.running &&
            <Suspense fallback={<div className="chart-loading"><RefreshCw className="spin" size={15}/> 正在加载图表引擎</div>}>
                <EChartCard tool={tool}/>
            </Suspense>}
        {tool.name === "render_antv_chart" && !tool.running &&
            <Suspense fallback={<div className="chart-loading"><RefreshCw className="spin" size={15}/> 正在加载 AntV 图表引擎</div>}>
                <AntVChartCard tool={tool}/>
            </Suspense>}
    </>;
}

function HitlCard({event, onDecide}) {
    const [always, setAlways] = useState(false);
    const tasks = event.tasks?.length ? event.tasks : [{
        callUuid: event.callUuid,
        toolName: event.toolName,
        args: event.args,
        comment: event.comment
    }];
    const callUuids = tasks.map(task => task.callUuid).filter(Boolean);
    const multiple = tasks.length > 1;
    const comments = [...new Set(tasks.map(task => task.comment?.trim()).filter(Boolean))];
    const commonComment = multiple && comments.length === 1 ? comments[0] : "";
    return <div className="hitl-card">
        <div className="hitl-head"><ShieldCheck size={19}/><div>
            <b>{multiple ? `${tasks.length} 项操作需要你的授权` : "需要你的授权"}</b>
            <span>{commonComment || (multiple
                ? "确认后将对本批操作统一应用选择"
                : tasks[0]?.toolName || "工具调用")}</span>
        </div></div>
        <div className="hitl-task-list">
            {tasks.map((task, index) => <div className="hitl-task" key={task.callUuid || `${task.toolName}-${index}`}>
                <div><b>{task.toolName || "工具调用"}</b><span>{
                    commonComment ? `操作 ${index + 1}` : task.comment || "敏感操作"
                }</span></div>
                <pre>{JSON.stringify(task.args || {}, null, 2)}</pre>
            </div>)}
        </div>
        <label><input type="checkbox" checked={always} onChange={e => setAlways(e.target.checked)}/>
            {multiple ? "本会话始终允许这些工具" : "本会话始终允许此工具"}</label>
        <div className="hitl-actions">
            <button className="danger-soft"
                    onClick={() => onDecide("reject", always, callUuids)}>拒绝</button>
            <button onClick={() => onDecide("skip", always, callUuids)}>跳过</button>
            <button className="primary-button small"
                    onClick={() => onDecide("approve", always, callUuids)}>
                {multiple ? "全部允许执行" : "允许执行"}
            </button>
        </div>
    </div>;
}

function PanelHeader({eyebrow, title, description, actions}) {
    return <div className="panel-header">
        <div><span className="eyebrow">{eyebrow}</span><h2>{title}</h2><p>{description}</p></div>
        {actions && <div className="panel-actions">{actions}</div>}
    </div>;
}

function replaceTreeChildren(items, path, children) {
    return (items || []).map(item => {
        if (item.path === path) return {...item, children};
        if (!item.children) return item;
        return {...item, children: replaceTreeChildren(item.children, path, children)};
    });
}

function findTreeItem(items, path) {
    for (const item of items || []) {
        if (item.path === path) return item;
        const child = findTreeItem(item.children, path);
        if (child) return child;
    }
    return null;
}

function removeTreeItem(items, path) {
    return items
        .filter(item => item.path !== path)
        .map(item => item.children
            ? {...item, children: removeTreeItem(item.children, path)}
            : item);
}

function ContextMenu({x, y, items, onClose}) {
    useEffect(() => {
        const close = event => {
            if (event.type === "keydown" && event.key !== "Escape") return;
            onClose();
        };
        window.addEventListener("mousedown", close);
        window.addEventListener("keydown", close);
        window.addEventListener("resize", close);
        window.addEventListener("scroll", close, true);
        return () => {
            window.removeEventListener("mousedown", close);
            window.removeEventListener("keydown", close);
            window.removeEventListener("resize", close);
            window.removeEventListener("scroll", close, true);
        };
    }, [onClose]);

    const left = Math.max(8, Math.min(x, window.innerWidth - 196));
    const top = Math.max(8, Math.min(y, window.innerHeight - items.length * 36 - 16));
    return <div className="context-menu" role="menu" style={{left, top}}
                onMouseDown={event => event.stopPropagation()}>
        {items.map(item => {
            const Icon = item.icon;
            return <button key={item.id} role="menuitem" disabled={item.disabled}
                           className={item.danger ? "danger" : ""}
                           onClick={() => {
                               onClose();
                               item.action();
                           }}>
                <Icon size={14}/><span>{item.label}</span>
            </button>;
        })}
    </div>;
}

function ExplorerPanel({api, notify, onOpenFile}) {
    const [tree, setTree] = useState([]);
    const [query, setQuery] = useState("");
    const [loading, setLoading] = useState(true);
    const [expandedPaths, setExpandedPaths] = useState(() => new Set());
    const expandedPathsRef = useRef(expandedPaths);
    const [loadingPaths, setLoadingPaths] = useState(() => new Set());
    const [contextMenu, setContextMenu] = useState(null);
    const uploadInputRef = useRef(null);
    const batchUploadInputRef = useRef(null);
    const uploadDirectoryRef = useRef("");
    const activePath = useWorkspaceStore(state => state.activePath);
    const removeEditorPath = useWorkspaceStore(state => state.removePath);
    const insertPromptReference = useWorkspaceStore(state => state.insertPromptReference);
    const treeRefreshVersion = useWorkspaceStore(state => state.treeRefreshVersion);
    expandedPathsRef.current = expandedPaths;

    const loadTree = useCallback(async () => {
        setLoading(true);
        try {
            //1. 重新加载根目录，并按层级恢复刷新前已展开的目录
            let nextTree = await api("/api/files/tree?depth=1");
            const restoredPaths = new Set();
            const paths = [...expandedPathsRef.current]
                .sort((left, right) => left.split(/[\\/]/).length - right.split(/[\\/]/).length);
            for (const path of paths) {
                const item = findTreeItem(nextTree, path);
                if (!item || item.type !== "directory") continue;
                const children = await api(`/api/files/tree?path=${encodeURIComponent(path)}&depth=1`);
                nextTree = replaceTreeChildren(nextTree, path, children || []);
                restoredPaths.add(path);
            }

            //2. 一次性更新树和仍然存在的展开目录，避免刷新过程中树节点闪烁收缩
            setTree(nextTree);
            setExpandedPaths(restoredPaths);
        }
        finally { setLoading(false); }
    }, [api]);
    useEffect(() => {
        loadTree().catch(error => notify(error.message));
    }, [loadTree, notify, treeRefreshVersion]);

    async function search() {
        if (!query.trim()) return loadTree();
        setTree(await api(`/api/files/search?keyword=${encodeURIComponent(query.trim())}`));
        setExpandedPaths(new Set());
    }

    async function toggleDirectory(item) {
        if (loadingPaths.has(item.path)) return;
        if (expandedPaths.has(item.path)) {
            setExpandedPaths(paths => {
                const next = new Set(paths);
                next.delete(item.path);
                return next;
            });
            return;
        }
        if (!Array.isArray(item.children)) {
            setLoadingPaths(paths => new Set(paths).add(item.path));
            try {
                const children = await api(`/api/files/tree?path=${encodeURIComponent(item.path)}&depth=1`);
                setTree(items => replaceTreeChildren(items, item.path, children || []));
            } catch (error) {
                notify(error.message);
                return;
            } finally {
                setLoadingPaths(paths => {
                    const next = new Set(paths);
                    next.delete(item.path);
                    return next;
                });
            }
        }
        setExpandedPaths(paths => new Set(paths).add(item.path));
    }
    async function create() {
        const path = window.prompt("新文件的相对路径", "src/new-file.txt");
        if (!path) return;
        const data = await api("/api/files/save", {method: "POST", body: JSON.stringify({path, content: ""})});
        await loadTree();
        onOpenFile(data.path);
    }

    async function uploadFiles(files) {
        const selectedFiles = Array.from(files || []);
        if (selectedFiles.length === 0) return;
        for (const file of selectedFiles) {
            const form = new FormData();
            form.append("file", file, file.name);
            await api(`/api/files/upload?path=${encodeURIComponent(uploadDirectoryRef.current)}`, {
                method: "POST",
                body: form
            });
        }
        await loadTree();
        notify(selectedFiles.length === 1
            ? `已上传 ${selectedFiles[0].name}`
            : `已上传 ${selectedFiles.length} 个文件`);
    }

    async function downloadFile(item) {
        const response = await api(`/api/files/download?path=${encodeURIComponent(item.path)}`, {raw: true});
        const blob = await response.blob();
        const url = URL.createObjectURL(blob);
        const anchor = document.createElement("a");
        anchor.href = url;
        anchor.download = item.name;
        document.body.appendChild(anchor);
        anchor.click();
        anchor.remove();
        window.setTimeout(() => URL.revokeObjectURL(url), 1000);
    }

    async function deleteTreeItem(item) {
        const normalizedPath = item.path.replace(/\\/g, "/");
        const prefix = `${normalizedPath}/`;
        const affectedBuffers = Object.values(useWorkspaceStore.getState().buffers)
            .filter(buffer => {
                const path = buffer.path.replace(/\\/g, "/");
                return path === normalizedPath || path.startsWith(prefix);
            });
        const dirty = affectedBuffers.some(buffer => buffer.dirty);
        const description = item.type === "directory"
            ? `目录“${item.name}”及其中的全部内容`
            : `文件“${item.name}”`;
        const dirtyNotice = dirty ? "\n\n其中包含未保存的编辑内容。" : "";
        if (!window.confirm(`确定删除${description}吗？此操作无法撤销。${dirtyNotice}`)) return;

        await api(`/api/files?path=${encodeURIComponent(item.path)}`, {method: "DELETE"});
        setTree(items => removeTreeItem(items, item.path));
        setExpandedPaths(paths => new Set(
            [...paths].filter(path => path !== normalizedPath && !path.startsWith(prefix))
        ));
        removeEditorPath(item.path);
        notify(`已删除${item.type === "directory" ? "目录" : "文件"} ${item.name}`);
    }

    function openUploadPicker(directory, multiple) {
        uploadDirectoryRef.current = directory || "";
        (multiple ? batchUploadInputRef : uploadInputRef).current?.click();
    }

    function openTreeContextMenu(event, item = null) {
        event.preventDefault();
        event.stopPropagation();
        setContextMenu({x: event.clientX, y: event.clientY, item});
    }

    function referenceTreeItem(item) {
        insertPromptReference(`@${item.path}`);
        notify(`已引用 ${item.path}`);
    }

    function treeMenuItems(item) {
        if (item?.type === "file") {
            return [
                {id: "open", label: "打开", icon: FileCode2, action: () => onOpenFile(item.path)},
                {id: "reference", label: "引用", icon: Link2, action: () => referenceTreeItem(item)},
                {id: "download", label: "下载", icon: Download,
                    action: () => downloadFile(item).catch(error => notify(error.message))},
                {id: "copy-path", label: "复制路径", icon: Copy,
                    action: () => navigator.clipboard.writeText(item.path)
                        .then(() => notify("路径已复制"))
                        .catch(error => notify(error.message))},
                {id: "delete", label: "删除文件", icon: Trash2, danger: true,
                    action: () => deleteTreeItem(item).catch(error => notify(error.message))}
            ];
        }
        const directory = item?.path || "";
        return [
            ...(item ? [{id: "reference", label: "引用", icon: Link2,
                action: () => referenceTreeItem(item)}] : []),
            {id: "upload", label: "上传文件", icon: Upload,
                action: () => openUploadPicker(directory, false)},
            {id: "batch-upload", label: "批量上传", icon: Files,
                action: () => openUploadPicker(directory, true)},
            ...(item ? [{id: "copy-path", label: "复制路径", icon: Copy,
                action: () => navigator.clipboard.writeText(item.path)
                    .then(() => notify("路径已复制"))
                    .catch(error => notify(error.message))},
                {id: "delete", label: "删除目录", icon: Trash2, danger: true,
                    action: () => deleteTreeItem(item).catch(error => notify(error.message))}] : [])
        ];
    }

    return <div className="explorer-panel">
        <div className="explorer-toolbar">
            <span>资源管理器</span>
            <button onClick={() => create().catch(error => notify(error.message))} aria-label="新建文件"><FilePlus2 size={15}/></button>
            <button onClick={() => loadTree().catch(error => notify(error.message))} aria-label="刷新文件树"><RefreshCw size={14}/></button>
        </div>
        <div className="search-box"><Search size={15}/><input value={query} placeholder="搜索文件"
            onChange={event => setQuery(event.target.value)}
            onKeyDown={event => event.key === "Enter" && search().catch(error => notify(error.message))}/></div>
        <div className="tree-scroll" role="tree" aria-label="工作区文件"
             onContextMenu={event => openTreeContextMenu(event)}>
            {loading ? <PanelLoading/> : <FileTree items={tree || []}
                onOpen={onOpenFile}
                onToggle={toggleDirectory}
                onContextMenu={openTreeContextMenu}
                expandedPaths={expandedPaths}
                loadingPaths={loadingPaths}
                activePath={activePath}/>}
        </div>
        <input ref={uploadInputRef} className="visually-hidden" type="file" tabIndex={-1}
               aria-label="上传文件"
               onChange={event => {
                   uploadFiles(event.target.files).catch(error => notify(error.message));
                   event.target.value = "";
               }}/>
        <input ref={batchUploadInputRef} className="visually-hidden" type="file" multiple tabIndex={-1}
               aria-label="批量上传文件"
               onChange={event => {
                   uploadFiles(event.target.files).catch(error => notify(error.message));
                   event.target.value = "";
               }}/>
        {contextMenu && <ContextMenu x={contextMenu.x} y={contextMenu.y}
            items={treeMenuItems(contextMenu.item)} onClose={() => setContextMenu(null)}/>}
    </div>;
}

function FileTree({items, onOpen, onToggle, onContextMenu, expandedPaths, loadingPaths, activePath, depth = 0}) {
    return items.map(item => {
        const directory = item.type === "directory";
        const expanded = directory && expandedPaths.has(item.path);
        const loading = directory && loadingPaths.has(item.path);
        return <div key={item.path} role="none">
        <button className={`tree-node ${directory ? "directory" : "file"} ${activePath === item.path ? "active" : ""}`}
                style={{paddingLeft: 6 + depth * 16}}
                role="treeitem"
                aria-level={depth + 1}
                aria-expanded={directory ? expanded : undefined}
                onContextMenu={event => onContextMenu(event, item)}
                onKeyDown={event => {
                    if (!directory) return;
                    if ((event.key === "ArrowRight" && !expanded)
                            || (event.key === "ArrowLeft" && expanded)) {
                        event.preventDefault();
                        onToggle(item);
                    }
                }}
                onClick={() => directory ? onToggle(item) : onOpen(item.path)}>
            <span className="tree-chevron">
                {directory && (loading ? <RefreshCw className="spin" size={12}/>
                    : expanded ? <ChevronDown size={13}/> : <ChevronRight size={13}/>)}
            </span>
            {directory
                ? expanded ? <FolderOpen size={16}/> : <Folder size={16}/>
                : <File size={15}/>}<span className="tree-label">{item.name}</span>
        </button>
        {expanded && item.children && <div role="group"><FileTree items={item.children}
            onOpen={onOpen}
            onToggle={onToggle}
            onContextMenu={onContextMenu}
            expandedPaths={expandedPaths}
            loadingPaths={loadingPaths}
            activePath={activePath}
            depth={depth + 1}/></div>}
    </div>;
    });
}

function EditorPanel({api, notify, editorTheme}) {
    const openedPaths = useWorkspaceStore(state => state.openedPaths);
    const activePath = useWorkspaceStore(state => state.activePath);
    const buffers = useWorkspaceStore(state => state.buffers);
    const activateFile = useWorkspaceStore(state => state.activateFile);
    const updateBuffer = useWorkspaceStore(state => state.updateBuffer);
    const setEditorSelection = useWorkspaceStore(state => state.setEditorSelection);
    const markSaved = useWorkspaceStore(state => state.markSaved);
    const applyExternalFile = useWorkspaceStore(state => state.applyExternalFile);
    const setFileViewMode = useWorkspaceStore(state => state.setFileViewMode);
    const closeFile = useWorkspaceStore(state => state.closeFile);
    const closeFiles = useWorkspaceStore(state => state.closeFiles);
    const selections = useWorkspaceStore(state => state.selections);
    const revealLocations = useWorkspaceStore(state => state.revealLocations);
    const enableReference = useWorkspaceStore(state => state.enableReference);
    const externalChange = useWorkspaceStore(state => state.externalChanges[0]);
    const externalChangeCount = useWorkspaceStore(state => state.externalChanges.length);
    const resolveExternalChange = useWorkspaceStore(state => state.resolveExternalChange);
    const [contextMenu, setContextMenu] = useState(null);
    const [tabContextMenu, setTabContextMenu] = useState(null);
    const [refreshing, setRefreshing] = useState(false);
    const editorRef = useRef(null);
    const active = activePath ? buffers[activePath] : null;

    async function save() {
        if (!active || active.previewType === "image" || active.previewType === "video") return;
        const file = await api("/api/files/save", {
            method: "POST",
            body: JSON.stringify({path: active.path, content: active.content})
        });
        markSaved(active.path, file);
        notify(`已保存 ${active.path}`);
    }

    async function refreshFromDisk() {
        if (!active || refreshing) return;
        if (active.dirty && !window.confirm(`“${active.name}”包含未保存修改。重新读取会使用磁盘版本覆盖这些修改，是否继续？`)) {
            return;
        }
        setRefreshing(true);
        try {
            const file = await api(`/api/files/read?path=${encodeURIComponent(active.path)}`);
            applyExternalFile(file);
            notify(`已从磁盘刷新 ${active.path}`);
        } finally {
            setRefreshing(false);
        }
    }

    function close(event, path) {
        event.stopPropagation();
        if (buffers[path]?.dirty && !window.confirm(`“${buffers[path].name}”尚未保存，仍要关闭吗？`)) return;
        closeFile(path);
    }

    function closeMany(paths) {
        if (paths.length === 0) return;
        const dirtyCount = paths.filter(path => buffers[path]?.dirty).length;
        if (dirtyCount > 0 && !window.confirm(
            `${dirtyCount} 个待关闭文件包含未保存修改，仍要继续吗？`
        )) return;
        closeFiles(paths);
    }

    function tabMenuItems(path) {
        const index = openedPaths.indexOf(path);
        const left = openedPaths.slice(0, index);
        const right = openedPaths.slice(index + 1);
        return [
            {id: "close-all", label: "关闭所有标签", icon: X,
                action: () => closeMany(openedPaths)},
            {id: "close-left", label: "关闭左侧标签", icon: PanelLeftClose,
                disabled: left.length === 0, action: () => closeMany(left)},
            {id: "close-right", label: "关闭右侧标签", icon: PanelRightClose,
                disabled: right.length === 0, action: () => closeMany(right)}
        ];
    }

    async function copySelection(cut = false) {
        const editor = editorRef.current;
        const selection = editor?.getSelection();
        const model = editor?.getModel();
        if (!selection || selection.isEmpty() || !model) return;
        await navigator.clipboard.writeText(model.getValueInRange(selection));
        if (cut) {
            editor.executeEdits("context-menu", [{range: selection, text: "", forceMoveMarkers: true}]);
        }
        editor.focus();
    }

    async function pasteSelection() {
        const editor = editorRef.current;
        const selection = editor?.getSelection();
        if (!selection) return;
        const text = await navigator.clipboard.readText();
        editor.executeEdits("context-menu", [{range: selection, text, forceMoveMarkers: true}]);
        editor.focus();
    }

    function referenceSelection() {
        if (!active) return;
        enableReference();
        const reference = activeFileReference(active.path, selections[active.path]);
        notify(`已引用 ${reference}`);
    }

    function editorMenuItems(hasSelection) {
        return [
            {id: "copy", label: "复制", icon: Copy, disabled: !hasSelection,
                action: () => copySelection().catch(error => notify(error.message))},
            {id: "paste", label: "粘贴", icon: ClipboardPaste,
                action: () => pasteSelection().catch(error => notify(error.message))},
            {id: "cut", label: "剪切", icon: Scissors, disabled: !hasSelection,
                action: () => copySelection(true).catch(error => notify(error.message))},
            {id: "reference", label: "引用", icon: Link2,
                action: referenceSelection}
        ];
    }

    const canPreview = active && ["html", "markdown"].includes(active.previewType);
    const editable = active && !["image", "video"].includes(active.previewType)
        && active.viewMode !== "preview";

    return <section className="editor-pane" aria-label="文件预览与编辑"
                    onKeyDown={event => {
                        if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === "s") {
                            event.preventDefault();
                            save().catch(error => notify(error.message));
                        }
                    }}>
        <div className="editor-tabs" role="tablist" aria-label="已打开文件">
            {openedPaths.map(path => <button key={path} role="tab" aria-selected={path === activePath}
                className={path === activePath ? "active" : ""} onClick={() => activateFile(path)} title={path}
                onContextMenu={event => {
                    event.preventDefault();
                    event.stopPropagation();
                    setTabContextMenu({x: event.clientX, y: event.clientY, path});
                }}>
                {buffers[path]?.previewType === "image" ? <ImageIcon size={14}/>
                    : buffers[path]?.previewType === "video" ? <Film size={14}/>
                        : <FileCode2 size={14}/>}<span>{buffers[path]?.name || path}</span>
                {buffers[path]?.dirty && <i/>}
                <span className="tab-close" role="button" onClick={event => close(event, path)}><X size={13}/></span>
            </button>)}
        </div>
        <div className="editor-toolbar">
            <span title={active?.path}>{active?.path || "文件预览"}</span>
            <div>
                {canPreview && <div className="view-mode-switch" role="group" aria-label="文件显示模式">
                    <button className={active.viewMode === "preview" ? "active" : ""}
                            onClick={() => setFileViewMode(active.path, "preview")}>
                        <Eye size={13}/>预览
                    </button>
                    <button className={active.viewMode !== "preview" ? "active" : ""}
                            onClick={() => setFileViewMode(active.path, "edit")}>
                        <Code2 size={13}/>编辑
                    </button>
                </div>}
                <button disabled={!active || refreshing}
                        onClick={() => refreshFromDisk().catch(error => notify(error.message))}>
                    <RefreshCw className={refreshing ? "spin" : ""} size={14}/>刷新
                </button>
                <button disabled={!active || !active.dirty || active.previewType === "image" || active.previewType === "video"}
                        onClick={() => save().catch(error => notify(error.message))}>
                    <Save size={14}/>保存
                </button>
            </div>
        </div>
        <div className="editor-surface" onContextMenuCapture={event => {
            if (!editable) return;
            event.preventDefault();
            event.stopPropagation();
            const selection = editorRef.current?.getSelection();
            setContextMenu({
                x: event.clientX,
                y: event.clientY,
                hasSelection: Boolean(selection && !selection.isEmpty())
            });
        }}>
            {active ? <FileContentView active={active} api={api}
                    location={revealLocations[active.path]}
                    editorTheme={editorTheme}
                    onChange={value => updateBuffer(active.path, value || "")}
                    onSelectionChange={selection => setEditorSelection(active.path, selection)}
                    onEditorReady={editor => { editorRef.current = editor; }}/>
                : <div className="editor-empty"><FileCode2 size={36}/><b>打开文件开始编辑</b><span>从左侧文件树选择一个文本文件</span></div>}
        </div>
        {contextMenu && <ContextMenu x={contextMenu.x} y={contextMenu.y}
            items={editorMenuItems(contextMenu.hasSelection)} onClose={() => setContextMenu(null)}/>}
        {tabContextMenu && <ContextMenu x={tabContextMenu.x} y={tabContextMenu.y}
            items={tabMenuItems(tabContextMenu.path)} onClose={() => setTabContextMenu(null)}/>}
        {externalChange && <ExternalChangeDialog key={`${externalChange.path}-${externalChange.disk.modifiedAt}`}
            change={externalChange}
            count={externalChangeCount}
            editorTheme={editorTheme}
            onResolve={content => resolveExternalChange(externalChange.path, content)}/>}
    </section>;
}

function FileContentView({active, api, location, editorTheme, onChange, onSelectionChange, onEditorReady}) {
    if (active.previewType === "image" || active.previewType === "video") {
        return <BlobMediaPreview file={active} api={api}/>;
    }
    if (active.viewMode === "preview" && active.previewType === "markdown") {
        return <div className="document-preview markdown-preview markdown-body">
            <ReactMarkdown remarkPlugins={[remarkGfm]}>{active.content || ""}</ReactMarkdown>
        </div>;
    }
    if (active.viewMode === "preview" && active.previewType === "html") {
        return <div className="document-preview html-preview">
            <iframe title={`预览 ${active.name}`} sandbox="allow-scripts" srcDoc={active.content || ""}/>
        </div>;
    }
    return <Suspense fallback={<PanelLoading/>}><MonacoEditor
        path={active.path}
        value={active.content}
        language={editorLanguage(active.path)}
        location={location}
        theme={editorTheme}
        onChange={onChange}
        onSelectionChange={onSelectionChange}
        onEditorReady={onEditorReady}/></Suspense>;
}

function BlobMediaPreview({file, api}) {
    const [url, setUrl] = useState("");
    const [error, setError] = useState("");
    useEffect(() => {
        let objectUrl = "";
        let active = true;
        setUrl("");
        setError("");
        api(`/api/files/download?path=${encodeURIComponent(file.path)}`, {raw: true})
            .then(response => response.blob())
            .then(blob => {
                if (!active) return;
                objectUrl = URL.createObjectURL(blob);
                setUrl(objectUrl);
            })
            .catch(reason => active && setError(reason.message));
        return () => {
            active = false;
            if (objectUrl) URL.revokeObjectURL(objectUrl);
        };
    }, [api, file.path, file.revision]);

    if (error) return <div className="preview-state error"><X size={22}/><b>无法加载预览</b><span>{error}</span></div>;
    if (!url) return <PanelLoading/>;
    return <div className={`media-preview ${file.previewType}`}>
        {file.previewType === "image"
            ? <img src={url} alt={file.name}/>
            : <video src={url} controls preload="metadata">浏览器不支持此视频格式</video>}
        <span>{file.name} · {formatTokens(file.size)} bytes</span>
    </div>;
}

function ExternalChangeDialog({change, count, editorTheme, onResolve}) {
    const [draft, setDraft] = useState(change.disk.content || "");
    useEffect(() => {
        const onKeyDown = event => {
            if (event.key === "Escape") onResolve(change.browserContent);
        };
        window.addEventListener("keydown", onKeyDown);
        return () => window.removeEventListener("keydown", onKeyDown);
    }, [change, onResolve]);

    return <div className="modal-backdrop external-change-backdrop" role="presentation">
        <section className="external-change-dialog" role="dialog" aria-modal="true"
                 aria-labelledby="external-change-title">
            <header>
                <span className="conflict-icon"><GitCompareArrows size={19}/></span>
                <div><h2 id="external-change-title">文件已被智能体修改</h2>
                    <p><code>{change.path}</code>{count > 1 && ` · 还有 ${count - 1} 个文件待处理`}</p></div>
            </header>
            <div className="diff-labels"><span>浏览器版本</span><span>磁盘版本 · 可编辑</span></div>
            <div className="external-diff">
                <Suspense fallback={<PanelLoading/>}><MonacoDiffEditor
                    path={change.path}
                    original={change.browserContent}
                    modified={draft}
                    language={editorLanguage(change.path)}
                    theme={editorTheme}
                    onChange={setDraft}/></Suspense>
            </div>
            <footer>
                <span>Esc 保留浏览器版本并标记为未保存</span>
                <div>
                    <button onClick={() => onResolve(change.browserContent)}>保留浏览器版本</button>
                    <button onClick={() => onResolve(change.disk.content || "")}>采用磁盘版本</button>
                    <button className="primary-button" onClick={() => onResolve(draft)}>采用编辑结果</button>
                </div>
            </footer>
        </section>
    </div>;
}

function editorLanguage(path = "") {
    const extension = path.split(".").pop()?.toLowerCase();
    return ({js: "javascript", jsx: "javascript", ts: "typescript", tsx: "typescript", java: "java",
        json: "json", css: "css", html: "html", md: "markdown", yml: "yaml", yaml: "yaml",
        xml: "xml", sql: "sql", py: "python", sh: "shell"})[extension] || "plaintext";
}

function ResizeHandle({axis, onResize, onReset}) {
    const start = useRef(0);
    return <div className={`resize-handle ${axis}`} role="separator" aria-orientation="vertical"
                aria-label={axis === "left" ? "调整侧栏宽度" : "调整编辑器宽度"}
                tabIndex={0}
                title="拖动调整宽度，双击恢复默认"
                onDoubleClick={onReset}
                onKeyDown={event => {
                    if (event.key !== "ArrowLeft" && event.key !== "ArrowRight") return;
                    event.preventDefault();
                    onResize(event.key === "ArrowLeft" ? -16 : 16);
                }}
                onPointerDown={event => {
                    start.current = event.clientX;
                    event.currentTarget.setPointerCapture(event.pointerId);
                }}
                onPointerMove={event => {
                    if (!event.currentTarget.hasPointerCapture(event.pointerId)) return;
                    const delta = event.clientX - start.current;
                    start.current = event.clientX;
                    onResize(delta);
                }}/>
}

function WorkspacePicker({api, workspaces, activeWorkspace, onActivate, onRegister, onClose}) {
    const [entries, setEntries] = useState([]);
    const [path, setPath] = useState("");
    const [history, setHistory] = useState([]);
    const [loading, setLoading] = useState(true);

    const loadRoots = useCallback(async () => {
        setLoading(true);
        try {
            setEntries(await api("/api/workspaces/roots"));
            setPath("");
            setHistory([]);
        } finally {
            setLoading(false);
        }
    }, [api]);

    useEffect(() => { loadRoots().catch(() => setLoading(false)); }, [loadRoots]);
    useEffect(() => {
        const closeOnEscape = event => event.key === "Escape" && onClose();
        window.addEventListener("keydown", closeOnEscape);
        return () => window.removeEventListener("keydown", closeOnEscape);
    }, [onClose]);

    async function browse(directory) {
        setLoading(true);
        try {
            const children = await api(`/api/workspaces/children?path=${encodeURIComponent(directory)}`);
            if (path) setHistory(items => [...items, path]);
            setPath(directory);
            setEntries(children || []);
        } finally {
            setLoading(false);
        }
    }

    async function back() {
        if (history.length === 0) return loadRoots();
        const parent = history[history.length - 1];
        const children = await api(`/api/workspaces/children?path=${encodeURIComponent(parent)}`);
        setHistory(items => items.slice(0, -1));
        setPath(parent);
        setEntries(children || []);
    }

    return <div className="modal-backdrop" role="presentation" onMouseDown={event => event.target === event.currentTarget && onClose()}>
        <section className="workspace-picker" role="dialog" aria-modal="true" aria-labelledby="workspace-picker-title">
            <header><div><span className="eyebrow">LOCAL WORKSPACE</span><h2 id="workspace-picker-title">打开本地目录</h2></div>
                <button className="icon-button" onClick={onClose} aria-label="关闭"><X size={18}/></button></header>
            {workspaces.length > 0 && <div className="recent-workspaces">
                <span>最近使用</span>
                {workspaces.map(item => <button key={item.workspaceId} className={item.workspaceId === activeWorkspace?.workspaceId ? "active" : ""}
                    onClick={() => onActivate(item.workspaceId)}><HardDrive size={15}/><span><b>{item.name}</b><small>{item.path}</small></span></button>)}
            </div>}
            <div className="directory-browser">
                <div className="directory-path">
                    <button onClick={() => back().catch(() => {})} disabled={!path}><ArrowLeft size={15}/></button>
                    <input value={path} onChange={event => setPath(event.target.value)} placeholder="选择目录或输入绝对路径"/>
                </div>
                <div className="directory-list" aria-busy={loading}>
                    {loading ? <PanelLoading/> : entries.map(item => <button key={item.path} onDoubleClick={() => browse(item.path)}
                        onClick={() => setPath(item.path)} title="双击进入目录">
                        <FolderOpen size={17}/><span><b>{item.name}</b><small>{item.path}</small></span><ChevronRight size={14}/>
                    </button>)}
                </div>
            </div>
            <footer><button className="secondary-button" onClick={onClose}>取消</button>
                <button className="primary-button" disabled={!path} onClick={() => onRegister(path)}><FolderOpen size={16}/>打开此目录</button></footer>
        </section>
    </div>;
}

function IntegrationsView({api, notify}) {
    const [items, setItems] = useState([]);
    const [json, setJson] = useState('{"name":"example","transport":"sse","url":"http://127.0.0.1:3000/sse","enabled":true}');
    const load = useCallback(async () => {
        const mcp = await api("/api/integrations/mcp");
        setItems(mcp || []);
    }, [api]);
    useEffect(() => { load().catch(e => notify(e.message)); }, [load, notify]);

    async function save() {
        let config;
        try { config = JSON.parse(json); } catch { throw new Error("请输入有效 JSON"); }
        await api("/api/integrations/mcp", {method: "POST", body: JSON.stringify(config)});
        notify("MCP 配置已保存");
        await load();
    }
    async function remove(name) {
        await api(`/api/integrations/mcp?name=${encodeURIComponent(name)}`, {method: "DELETE"});
        await load();
    }

    return <div className="panel-page">
        <PanelHeader eyebrow="CONNECTIONS" title="MCP" description="通过 Model Context Protocol 为智能体连接外部工具服务。"
                     actions={<button onClick={() => load()}><RefreshCw size={16}/>刷新连接</button>}/>
        <div className="integration-panels">
            <section className="surface integration-surface">
                <div className="integration-heading">
                    <span className="integration-icon mcp"><Box size={20}/></span>
                    <div><h3>MCP Servers</h3><p>Model Context Protocol 工具</p></div>
                </div>
                <textarea className="json-editor" value={json} onChange={e => setJson(e.target.value)}/>
                <button className="primary-button full-button" onClick={() => save().catch(e => notify(e.message))}>
                    <Save size={16}/>保存 MCP
                </button>
                <div className="connection-list">
                    {items.length === 0 && <span className="muted-empty">还没有配置服务</span>}
                    {items.map(item => <div className="connection-item" key={item.name}>
                        <span className="connection-status"/><div><b>{item.name}</b><small>{item.url || (item.command || []).join?.(" ") || item.command}</small></div>
                        <button className="icon-button" onClick={() => remove(item.name).catch(e => notify(e.message))}><Trash2 size={15}/></button>
                    </div>)}
                </div>
            </section>
        </div>
    </div>;
}

function CapabilitiesView({api, notify, section = "skills"}) {
    const [skills, setSkills] = useState([]);
    const [agents, setAgents] = useState([]);
    const [skillName, setSkillName] = useState("");
    const [skillFile, setSkillFile] = useState(null);
    const [skillContent, setSkillContent] = useState("---\nname: code-review\ndescription: 审查代码正确性、安全性与可维护性\n---\n\n# Code Review\n\n先阅读相关代码和测试，再按严重级别报告问题。");
    const [agentName, setAgentName] = useState("");
    const [agentContent, setAgentContent] = useState('---\nname: reviewer\ndescription: 专注代码审查的只读子智能体\ntools: ["read", "grep", "glob", "skill"]\n---\n\n你是代码审查专家。先检查事实，再报告高置信度问题。');

    const load = useCallback(async () => {
        if (section === "skills") {
            setSkills(await api("/api/capabilities/skills") || []);
        } else {
            setAgents(await api("/api/capabilities/agents") || []);
        }
    }, [api, section]);
    useEffect(() => { load().catch(e => notify(e.message)); }, [load, notify]);

    async function saveSkill() {
        if (!skillName.trim()) throw new Error("请输入 Skill 名称");
        await api("/api/capabilities/skills", {method: "POST", body: JSON.stringify({name: skillName, content: skillContent})});
        setSkillName("");
        notify("Skill 已保存到后台仓库");
        await load();
    }
    async function importZip() {
        if (!skillName.trim() || !skillFile) throw new Error("请输入名称并选择 ZIP");
        if (skillFile.size > 20 * 1024 * 1024) throw new Error("ZIP 最大 20MB");
        const bytes = new Uint8Array(await skillFile.arrayBuffer());
        let binary = "";
        for (let offset = 0; offset < bytes.length; offset += 0x8000) {
            binary += String.fromCharCode(...bytes.subarray(offset, offset + 0x8000));
        }
        await api("/api/capabilities/skills/import", {
            method: "POST", body: JSON.stringify({name: skillName, archiveBase64: btoa(binary)})
        });
        setSkillFile(null);
        setSkillName("");
        notify("完整 Skill 包已导入");
        await load();
    }
    async function skillAction(skill, action) {
        await api(`/api/capabilities/skills/action?name=${encodeURIComponent(skill.name)}&action=${action}`, {method: "POST"});
        notify(action === "activate" ? "Skill 已复制并加载" : "Skill 已从当前用户停用");
        await load();
    }
    async function deleteSkill(skill) {
        if (!window.confirm(`删除后台 Skill 包“${skill.name}”？已生效副本不会被自动删除。`)) return;
        await api(`/api/capabilities/skills?name=${encodeURIComponent(skill.name)}`, {method: "DELETE"});
        await load();
    }
    async function saveAgent() {
        if (!agentName.trim()) throw new Error("请输入 Subagent 名称");
        await api("/api/capabilities/agents", {method: "POST", body: JSON.stringify({name: agentName, content: agentContent})});
        setAgentName("");
        notify("Subagent 已保存并刷新");
        await load();
    }
    async function deleteAgent(agent) {
        await api(`/api/capabilities/agents?name=${encodeURIComponent(agent.name)}`, {method: "DELETE"});
        await load();
    }

    const isSkills = section === "skills";

    return <div className="panel-page">
        <PanelHeader eyebrow="CAPABILITIES" title={isSkills ? "Skill 管理" : "SubAgent 管理"}
                     description={isSkills
                         ? "维护全局 Skill 仓库，并精确控制当前用户加载哪些能力包。"
                         : "创建和维护可由 task / multitask 调度的专项智能体。"}
                     actions={<button onClick={() => api("/api/capabilities/refresh", {method: "POST"}).then(load)}><RefreshCw size={16}/>重新扫描</button>}/>
        {isSkills && <div className="capability-banner"><ShieldCheck size={20}/><div><b>按用户隔离加载</b>
            <span>点击生效后，完整目录复制到当前工作区的 <code>.soloncode/skills/&lt;name&gt;</code>，由 Solon Harness 加载。</span></div></div>}
        <div className="capability-grid single">
            {isSkills && <section className="surface capability-manager">
                <div className="manager-heading"><span className="integration-icon skill"><Sparkles size={20}/></span>
                    <div><h3>Skill 仓库</h3><p>导入完整目录，或快速创建 SKILL.md</p></div></div>
                <label>Skill 名称<input value={skillName} placeholder="code-review" onChange={e => setSkillName(e.target.value)}/></label>
                <label className="upload-zone">
                    <Upload size={21}/><span>{skillFile ? skillFile.name : "选择 ZIP 完整包"}</span><small>根目录需包含 SKILL.md · 最大 20MB</small>
                    <input type="file" accept=".zip,application/zip" onChange={e => setSkillFile(e.target.files?.[0] || null)}/>
                </label>
                <button className="secondary-button full-button" onClick={() => importZip().catch(e => notify(e.message))}><Upload size={16}/>导入 ZIP</button>
                <div className="or-divider"><span>或者编辑 SKILL.md</span></div>
                <textarea className="markdown-editor" value={skillContent} onChange={e => setSkillContent(e.target.value)}/>
                <button className="primary-button full-button" onClick={() => saveSkill().catch(e => notify(e.message))}><Save size={16}/>保存到仓库</button>
                <div className="capability-list">
                    {skills.length === 0 && <span className="muted-empty">后台仓库中暂无 Skill 包</span>}
                    {skills.map(skill => <div className="capability-item" key={skill.name}>
                        <div className="capability-state">{skill.loaded ? <Check size={15}/> : <Box size={15}/>}</div>
                        <div><b>{skill.name}</b><small>{skill.description}</small>
                            <span>{skill.fileCount} files · {skill.loaded ? "Harness 已加载" : skill.active ? "已复制，等待刷新" : "未生效"}</span></div>
                        <div className="capability-actions">
                            <button className={skill.active ? "" : "primary-button"} onClick={() => skillAction(skill, skill.active ? "deactivate" : "activate").catch(e => notify(e.message))}>
                                {skill.active ? "停用" : "生效"}</button>
                            <button className="icon-button" onClick={() => deleteSkill(skill).catch(e => notify(e.message))}><Trash2 size={15}/></button>
                        </div>
                    </div>)}
                </div>
            </section>}

            {!isSkills && <section className="surface capability-manager">
                <div className="manager-heading"><span className="integration-icon agent"><Bot size={20}/></span>
                    <div><h3>Subagents</h3><p>专项智能体，可由 task / multitask 调度</p></div></div>
                <label>Subagent 名称<input value={agentName} placeholder="reviewer" onChange={e => setAgentName(e.target.value)}/></label>
                <textarea className="markdown-editor tall" value={agentContent} onChange={e => setAgentContent(e.target.value)}/>
                <button className="primary-button full-button" onClick={() => saveAgent().catch(e => notify(e.message))}><Save size={16}/>保存 Subagent</button>
                <div className="capability-list">
                    {agents.map(agent => <div className="capability-item" key={agent.name}>
                        <div className="capability-state agent"><Bot size={15}/></div>
                        <div><b>{agent.name}</b><small>{agent.description}</small>
                            <span>{agent.editable ? "后台自定义" : "Harness 内置"}</span></div>
                        {agent.editable && <button className="icon-button" onClick={() => deleteAgent(agent).catch(e => notify(e.message))}><Trash2 size={15}/></button>}
                    </div>)}
                </div>
            </section>}
        </div>
    </div>;
}

function EmptyState({icon: Icon, title, text}) {
    return <div className="panel-empty"><Icon size={30}/><b>{title}</b><span>{text}</span></div>;
}

function PanelLoading() {
    return <div className="panel-loading"><RefreshCw className="spin" size={19}/>正在加载</div>;
}

export default App;
