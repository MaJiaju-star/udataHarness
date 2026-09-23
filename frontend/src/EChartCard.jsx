import {useEffect, useMemo, useRef, useState} from "react";
import * as echarts from "echarts/core";
import {
    BarChart, FunnelChart, GaugeChart, HeatmapChart, LineChart,
    PieChart, RadarChart, ScatterChart
} from "echarts/charts";
import {
    AriaComponent, DataZoomComponent, DatasetComponent, GridComponent,
    LegendComponent, RadarComponent, TitleComponent, TooltipComponent,
    VisualMapComponent
} from "echarts/components";
import {CanvasRenderer} from "echarts/renderers";
import {useWorkspaceStore} from "./workspaceStore.js";

echarts.use([
    BarChart, FunnelChart, GaugeChart, HeatmapChart, LineChart, PieChart, RadarChart, ScatterChart,
    AriaComponent, DataZoomComponent, DatasetComponent, GridComponent, LegendComponent,
    RadarComponent, TitleComponent, TooltipComponent, VisualMapComponent, CanvasRenderer
]);

const ALLOWED_TOP_LEVEL = new Set([
    "title", "legend", "grid", "dataset", "xAxis", "yAxis", "series",
    "tooltip", "dataZoom", "visualMap", "radar", "color", "backgroundColor",
    "animation", "animationDuration", "aria"
]);
const ALLOWED_SERIES = new Set(["line", "bar", "pie", "scatter", "radar", "heatmap", "funnel", "gauge"]);
const BLOCKED_KEYS = new Set([
    "__proto__", "prototype", "constructor", "formatter", "extracsstext",
    "link", "sublink", "optiontocontent", "onclick", "transform", "reg"
]);
const THEME_COLORS = {
    emerald: ["#10a37f", "#6c63d9", "#e59b3b", "#3f8fd2", "#dc5f6d"],
    ocean: ["#2f7de1", "#39a6a3", "#8b6fe8", "#e09a3e", "#dd6474"],
    violet: ["#7c5ce7", "#3f91d8", "#c47a19", "#3aa184", "#dc5f86"],
    amber: ["#c47a19", "#4f8fdf", "#4ca886", "#8b6fe8", "#d85f67"]
};

function parseJson(value) {
    if (typeof value !== "string") return null;
    try {
        return JSON.parse(value);
    } catch {
        return null;
    }
}

function sanitize(value, depth = 0) {
    if (depth > 12) throw new Error("图表配置层级过深");
    if (value == null || typeof value === "number" || typeof value === "boolean") return value;
    if (typeof value === "string") {
        const normalized = value.trim().toLowerCase();
        if (/^(javascript:|data:|https?:|image:\/\/https?:)/.test(normalized)) {
            throw new Error("图表配置包含不允许的外部地址");
        }
        return value;
    }
    if (Array.isArray(value)) return value.map(item => sanitize(item, depth + 1));
    if (typeof value === "object") {
        // ECharts expects regular option objects and calls hasOwnProperty on
        // nested values such as dataset.source. Prototype-pollution keys are
        // still removed below, so a plain object is safe at this boundary.
        const result = {};
        for (const [key, child] of Object.entries(value)) {
            if (!BLOCKED_KEYS.has(key.toLowerCase())) result[key] = sanitize(child, depth + 1);
        }
        return result;
    }
    throw new Error("图表配置包含不可执行的值");
}

function optionFromTool(tool) {
    const output = parseJson(tool?.output);
    const raw = output?.option || tool?.args?.option;
    if (!raw || typeof raw !== "object" || Array.isArray(raw)) {
        throw new Error("工具没有返回有效的 ECharts option");
    }
    const selected = Object.fromEntries(Object.entries(raw).filter(([key]) => ALLOWED_TOP_LEVEL.has(key)));
    const option = sanitize(selected);
    const series = Array.isArray(option.series) ? option.series : [option.series];
    if (!series.length || series.some(item => !item || !ALLOWED_SERIES.has(String(item.type).toLowerCase()))) {
        throw new Error("图表包含不支持的 series 类型");
    }
    option.tooltip = {...(option.tooltip || {}), renderMode: "richText"};
    option.aria = {...(option.aria || {}), enabled: true};
    return option;
}

export default function EChartCard({tool}) {
    const containerRef = useRef(null);
    const [runtimeError, setRuntimeError] = useState("");
    const resolvedTheme = useWorkspaceStore(state => state.resolvedTheme);
    const colorTheme = useWorkspaceStore(state => state.colorTheme);
    const parsed = useMemo(() => {
        try {
            return {option: optionFromTool(tool), error: ""};
        } catch (cause) {
            return {option: null, error: cause.message};
        }
    }, [tool]);
    const option = parsed.option;

    useEffect(() => {
        if (!containerRef.current || !option) return undefined;
        let chart;
        let observer;
        try {
            chart = echarts.init(containerRef.current, resolvedTheme === "dark" ? "dark" : null, {renderer: "canvas"});
            chart.setOption({...option, color: option.color || THEME_COLORS[colorTheme]},
                {notMerge: true, lazyUpdate: false});
            observer = new ResizeObserver(() => chart?.resize());
            observer.observe(containerRef.current);
            setRuntimeError("");
        } catch (cause) {
            setRuntimeError(cause.message || "图表渲染失败");
        }
        return () => {
            observer?.disconnect();
            chart?.dispose();
        };
    }, [option, resolvedTheme, colorTheme]);

    const title = option?.title?.text || tool?.args?.title || "数据图表";
    const error = parsed.error || runtimeError;
    if (error || !option) {
        return <div className="chart-error">{error || "ECharts 配置无效，已保留原始工具结果供检查。"}</div>;
    }
    return <section className="chart-card echart-card">
        <header>
            <div><span>APACHE ECHARTS</span><h4>{title}</h4></div>
            <b>交互图表</b>
        </header>
        <div ref={containerRef} className="echart-canvas" role="img" aria-label={title}/>
    </section>;
}
