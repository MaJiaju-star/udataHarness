import {useMemo} from "react";
import {Area, Bar, Column, Line, Pie, Scatter} from "@ant-design/charts";
import {useWorkspaceStore} from "./workspaceStore.js";

const CHARTS = {area: Area, bar: Bar, column: Column, line: Line, pie: Pie, scatter: Scatter};
const ALLOWED_CONFIG = new Set([
    "data", "xField", "yField", "colorField", "angleField", "seriesField",
    "shapeField", "sizeField", "stack", "group", "percent", "normalize",
    "sort", "transpose", "axis", "legend", "tooltip", "label", "style",
    "scale", "interaction", "animate", "theme", "padding", "inset",
    "height", "autoFit", "radius", "innerRadius", "smooth", "point",
    "area", "line", "connectNulls"
]);
const BLOCKED_KEYS = new Set([
    "__proto__", "prototype", "constructor", "formatter", "innerhtml",
    "dangerouslysetinnerhtml", "onclick", "onready", "oninit", "onerror",
    "link", "url", "src", "image", "transform", "callback"
]);

function parseJson(value) {
    if (typeof value !== "string") return null;
    try { return JSON.parse(value); } catch { return null; }
}

function sanitize(value, depth = 0) {
    if (depth > 10) throw new Error("AntV 配置层级过深");
    if (value == null || typeof value === "number" || typeof value === "boolean") return value;
    if (typeof value === "string") {
        if (/^(javascript:|data:|https?:|<)/i.test(value.trim())) {
            throw new Error("AntV 配置包含不允许的可执行内容或外部地址");
        }
        return value;
    }
    if (Array.isArray(value)) return value.map(item => sanitize(item, depth + 1));
    if (typeof value === "object") {
        const result = {};
        for (const [key, child] of Object.entries(value)) {
            if (!BLOCKED_KEYS.has(key.toLowerCase())) result[key] = sanitize(child, depth + 1);
        }
        return result;
    }
    throw new Error("AntV 配置包含不可执行的值");
}

function chartFromTool(tool) {
    const output = parseJson(tool?.output);
    const chartType = String(output?.chartType || tool?.args?.chartType || "").toLowerCase();
    if (!CHARTS[chartType]) throw new Error(`不支持的 AntV 图表类型：${chartType || "未指定"}`);
    const raw = output?.config || tool?.args?.config;
    if (!raw || typeof raw !== "object" || Array.isArray(raw)) {
        throw new Error("工具没有返回有效的 AntV config");
    }
    const selected = Object.fromEntries(
        Object.entries(raw).filter(([key]) => ALLOWED_CONFIG.has(key))
    );
    const config = sanitize(selected);
    if (!Array.isArray(config.data) || config.data.length === 0) {
        throw new Error("AntV 图表缺少 data");
    }
    return {
        chartType,
        config: {...config, autoFit: true, height: Math.min(Number(config.height) || 360, 480)},
        title: output?.title || tool?.args?.title || "数据图表"
    };
}

export default function AntVChartCard({tool}) {
    const resolvedTheme = useWorkspaceStore(state => state.resolvedTheme);
    const colorTheme = useWorkspaceStore(state => state.colorTheme);
    const parsed = useMemo(() => {
        try { return {...chartFromTool(tool), error: ""}; }
        catch (cause) { return {error: cause.message || "AntV 图表配置无效"}; }
    }, [tool]);

    if (parsed.error) return <div className="chart-error">{parsed.error}</div>;
    const Chart = CHARTS[parsed.chartType];
    return <section className="chart-card antv-chart-card">
        <header>
            <div><span>ANTV · ANT DESIGN CHARTS</span><h4>{parsed.title}</h4></div>
            <b>交互图表</b>
        </header>
        <div className="antv-canvas" role="img" aria-label={parsed.title}>
            <Chart {...parsed.config} key={`${resolvedTheme}-${colorTheme}`}
                   theme={parsed.config.theme || (resolvedTheme === "dark" ? "classicDark" : "classic")}/>
        </div>
    </section>;
}
