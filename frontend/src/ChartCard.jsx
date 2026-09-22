const COLORS = ["#10a37f", "#6c63d9", "#e59b3b", "#dc5f6d", "#3f8fd2", "#8b6f47", "#49a8a0", "#aa67a7"];

function parseJson(value) {
    if (typeof value !== "string") return null;
    try {
        return JSON.parse(value);
    } catch {
        return null;
    }
}

export function chartSpecFromTool(tool) {
    if (tool?.name !== "render_chart") return null;
    const output = parseJson(tool.output);
    const candidate = output && typeof output === "object" ? output : tool.args;
    if (!candidate || !["line", "bar", "pie"].includes(candidate.type)) return null;

    const labels = Array.isArray(candidate.labels) ? candidate.labels.map(String) : [];
    const seriesNames = Array.isArray(candidate.seriesNames) ? candidate.seriesNames.map(String) : [];
    const seriesData = Array.isArray(candidate.seriesData)
        ? candidate.seriesData.map(series => Array.isArray(series) ? series.map(Number) : [])
        : [];
    if (!labels.length || !seriesNames.length || seriesNames.length !== seriesData.length
        || seriesData.some(series => series.length !== labels.length || series.some(value => !Number.isFinite(value)))) {
        return null;
    }
    return {...candidate, labels, seriesNames, seriesData};
}

const formatNumber = value => new Intl.NumberFormat("zh-CN", {
    maximumFractionDigits: Math.abs(value) >= 100 ? 0 : 2
}).format(value);

function Legend({names, pieLabels}) {
    const labels = pieLabels || names;
    return <div className="chart-legend">
        {labels.map((name, index) => <span key={`${name}-${index}`}>
            <i style={{background: COLORS[index % COLORS.length]}}/>{name}
        </span>)}
    </div>;
}

function CartesianChart({spec}) {
    const width = 720;
    const height = 320;
    const margin = {top: 18, right: 18, bottom: 52, left: 58};
    const plotWidth = width - margin.left - margin.right;
    const plotHeight = height - margin.top - margin.bottom;
    const values = spec.seriesData.flat();
    let min = Math.min(0, ...values);
    let max = Math.max(0, ...values);
    if (min === max) {
        min -= 1;
        max += 1;
    }
    const range = max - min;
    const x = index => margin.left + (spec.labels.length === 1
        ? plotWidth / 2
        : index * plotWidth / (spec.labels.length - 1));
    const y = value => margin.top + (max - value) / range * plotHeight;
    const ticks = Array.from({length: 5}, (_, index) => min + range * index / 4);
    const labelStep = Math.max(1, Math.ceil(spec.labels.length / 10));
    const categoryWidth = plotWidth / spec.labels.length;
    const barWidth = Math.max(2, Math.min(38, categoryWidth * .72 / spec.seriesData.length));

    return <>
        <svg className="chart-svg" viewBox={`0 0 ${width} ${height}`} role="img" aria-label={spec.title}>
            {ticks.map(value => <g key={value}>
                <line className="chart-grid" x1={margin.left} x2={width - margin.right} y1={y(value)} y2={y(value)}/>
                <text className="chart-axis-label" x={margin.left - 10} y={y(value) + 4} textAnchor="end">
                    {formatNumber(value)}
                </text>
            </g>)}
            <line className="chart-axis" x1={margin.left} x2={width - margin.right} y1={y(0)} y2={y(0)}/>
            {spec.labels.map((label, index) => index % labelStep === 0 || index === spec.labels.length - 1
                ? <text className="chart-axis-label" key={`${label}-${index}`} x={x(index)}
                        y={height - 20} textAnchor="middle">{label.length > 12 ? `${label.slice(0, 11)}…` : label}</text>
                : null)}

            {spec.type === "line" && spec.seriesData.map((series, seriesIndex) => {
                const points = series.map((value, index) => `${x(index)},${y(value)}`).join(" ");
                return <g key={spec.seriesNames[seriesIndex]}>
                    <polyline className="chart-line" points={points} style={{stroke: COLORS[seriesIndex % COLORS.length]}}/>
                    {series.map((value, index) => <circle className="chart-point" key={index}
                            cx={x(index)} cy={y(value)} r="4" style={{fill: COLORS[seriesIndex % COLORS.length]}}>
                        <title>{`${spec.labels[index]} · ${spec.seriesNames[seriesIndex]}: ${formatNumber(value)}`}</title>
                    </circle>)}
                </g>;
            })}

            {spec.type === "bar" && spec.seriesData.map((series, seriesIndex) => series.map((value, index) => {
                const zeroY = y(0);
                const valueY = y(value);
                const center = margin.left + categoryWidth * (index + .5);
                const totalWidth = barWidth * spec.seriesData.length;
                return <rect className="chart-bar" key={`${seriesIndex}-${index}`}
                        x={center - totalWidth / 2 + seriesIndex * barWidth}
                        y={Math.min(zeroY, valueY)} width={Math.max(1, barWidth - 2)}
                        height={Math.max(1, Math.abs(zeroY - valueY))}
                        rx="3" style={{fill: COLORS[seriesIndex % COLORS.length]}}>
                    <title>{`${spec.labels[index]} · ${spec.seriesNames[seriesIndex]}: ${formatNumber(value)}`}</title>
                </rect>;
            }))}
        </svg>
        <Legend names={spec.seriesNames}/>
    </>;
}

function polarPoint(cx, cy, radius, angle) {
    const radians = (angle - 90) * Math.PI / 180;
    return {x: cx + radius * Math.cos(radians), y: cy + radius * Math.sin(radians)};
}

function arcPath(cx, cy, radius, startAngle, endAngle) {
    const start = polarPoint(cx, cy, radius, endAngle);
    const end = polarPoint(cx, cy, radius, startAngle);
    return `M ${cx} ${cy} L ${start.x} ${start.y} A ${radius} ${radius} 0 ${endAngle - startAngle > 180 ? 1 : 0} 0 ${end.x} ${end.y} Z`;
}

function PieChart({spec}) {
    const values = spec.seriesData[0];
    const total = values.reduce((sum, value) => sum + Math.max(0, value), 0);
    let angle = 0;
    return <>
        <svg className="chart-svg pie" viewBox="0 0 720 320" role="img" aria-label={spec.title}>
            {values.map((value, index) => {
                const sweep = total ? Math.max(0, value) / total * 360 : 0;
                const start = angle;
                angle += sweep;
                return sweep >= 359.999
                    ? <circle key={index} cx="360" cy="156" r="112" style={{fill: COLORS[index % COLORS.length]}}>
                        <title>{`${spec.labels[index]}: ${formatNumber(value)} (100%)`}</title>
                    </circle>
                    : <path className="chart-slice" key={index} d={arcPath(360, 156, 112, start, angle)}
                            style={{fill: COLORS[index % COLORS.length]}}>
                        <title>{`${spec.labels[index]}: ${formatNumber(value)} (${total ? formatNumber(value / total * 100) : 0}%)`}</title>
                    </path>;
            })}
            <circle cx="360" cy="156" r="53" className="chart-donut-hole"/>
            <text x="360" y="151" textAnchor="middle" className="chart-total-label">合计</text>
            <text x="360" y="174" textAnchor="middle" className="chart-total-value">{formatNumber(total)}</text>
        </svg>
        <Legend pieLabels={spec.labels}/>
    </>;
}

export default function ChartCard({spec}) {
    if (!spec) return <div className="chart-error">图表数据格式无效，已保留原始工具结果供检查。</div>;
    return <section className="chart-card">
        <header>
            <div><span>DATA VISUALIZATION</span><h4>{spec.title || "数据图表"}</h4></div>
            <b>{spec.type === "line" ? "折线图" : spec.type === "bar" ? "柱状图" : "饼图"}</b>
        </header>
        {spec.type === "pie" ? <PieChart spec={spec}/> : <CartesianChart spec={spec}/>}
    </section>;
}
