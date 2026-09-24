package com.udata.harness.common.support;

import org.noear.snack4.ONode;
import org.noear.solon.Utils;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.tool.AbsToolProvider;
import org.noear.solon.annotation.Param;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Solon AI Tool：校验并返回声明式 Apache ECharts 配置供 Web UI 渲染。
 *
 * <p>跨越该边界的只有纯 JSON 兼容值：不传输 JavaScript 源代码或回调，返回的 option
 * 是经过深拷贝与大小限制的独立值树。</p>
 */
public class ChartTool extends AbsToolProvider {
    /**
     * option 树允许的最大嵌套深度，超出即拒绝。
     */
    private static final int MAX_DEPTH = 12;

    /**
     * 单次清洗允许处理的最大节点数，防止超大配置拖垮服务。
     */
    private static final int MAX_VALUES = 12_000;

    /**
     * 序列化后 JSON 的最大长度（字符数），超出即拒绝。
     */
    private static final int MAX_JSON_LENGTH = 256_000;

    /**
     * 允许透传到前端的 ECharts 顶层配置键白名单，其余项一律剥离。
     */
    private static final Set<String> ALLOWED_TOP_LEVEL = Collections.unmodifiableSet(Utils.asSet(
            "title", "legend", "grid", "dataset", "xAxis", "yAxis", "series",
            "tooltip", "dataZoom", "visualMap", "radar", "color", "backgroundColor",
            "animation", "animationDuration", "aria"));
    /**
     * 支持的 series 类型白名单，杜绝未知的任意图表类型。
     */
    private static final Set<String> ALLOWED_SERIES_TYPES = Collections.unmodifiableSet(Utils.asSet(
            "line", "bar", "pie", "scatter", "radar", "heatmap", "funnel", "gauge"));

    /**
     * 命中即丢弃的键黑名单，覆盖原型污染键、回调与 HTML/URL 注入面。
     */
    private static final Set<String> BLOCKED_KEYS = Collections.unmodifiableSet(Utils.asSet(
            "__proto__", "prototype", "constructor", "formatter", "extracsstext",
            "link", "sublink", "optiontocontent", "onclick", "transform", "reg"));

    /**
     * 接收模型生成的声明式 ECharts 配置，返回前端可直接消费的安全配置。
     *
     * <p>处理顺序固定为：顶层字段白名单筛选、递归值清洗、series 类型校验、
     * tooltip 安全模式归一化、默认标题/动画/无障碍配置补齐，最后限制序列化大小。
     * Tool 不接收也不生成 JavaScript 函数，因此前端不需要使用 {@code eval}。</p>
     *
     * @param title 供缺省 {@code option.title} 使用的短标题
     * @param option 模型生成的 ECharts option，只允许 JSON 兼容值
     * @return 包含版本、渲染引擎和清洗后 option 的 JSON 字符串
     * @throws IllegalArgumentException 配置为空、超限或包含不支持的 series 时抛出
     */
    @ToolMapping(
            name = "render_echart",
            description = "Render an interactive Apache ECharts visualization in the chat UI. Use this whenever the user asks for a chart, graph, trend, comparison, distribution, dashboard visualization, or visualized numeric data. Pass a declarative ECharts option object only; JavaScript functions are not supported. Supported series types: line, bar, pie, scatter, radar, heatmap, funnel, gauge.")
    public String renderEchart(
            @Param(description = "Short chart title. It is used when option.title is omitted") String title,
            @Param(description = "Declarative Apache ECharts option JSON object. Do not include JavaScript source, functions, HTML, URLs, formatter callbacks, toolbox, or dataset transforms") Map<String, Object> option) {
        if (option == null || option.isEmpty()) {
            throw new IllegalArgumentException("option is required");
        }

        //1. 顶层字段白名单筛选，剥离 ECharts 未知/危险顶层项。
        Map<String, Object> selected = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : option.entrySet()) {
            if (ALLOWED_TOP_LEVEL.contains(entry.getKey())) {
                selected.put(entry.getKey(), entry.getValue());
            }
        }
        if (selected.isEmpty()) {
            throw new IllegalArgumentException("option does not contain any supported ECharts fields");
        }

        Budget budget = new Budget();
        @SuppressWarnings("unchecked")
        Map<String, Object> safeOption = (Map<String, Object>) sanitizeValue(null, selected, 0, budget);
        //2. 递归清洗后校验 series 类型，并强制 tooltip 使用安全渲染模式。
        validateSeries(safeOption.get("series"));
        normalizeTooltip(safeOption);
        //3. 补齐默认标题、动画与无障碍配置。
        if (!safeOption.containsKey("title") && Utils.isNotBlank(title)) {
            safeOption.put("title", Utils.asMap("text", title.trim()));
        }
        safeOption.putIfAbsent("animationDuration", 450);
        safeOption.putIfAbsent("aria", Utils.asMap("enabled", true));

        //4. 序列化并做最终长度限制。
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("version", 1);
        result.put("engine", "echarts");
        result.put("option", safeOption);
        String json = ONode.serialize(result);
        if (json.length() > MAX_JSON_LENGTH) {
            throw new IllegalArgumentException("chart option is too large");
        }
        return json;
    }

    /**
     * 深拷贝并清洗任意 JSON 值树。
     *
     * <p>{@code depth} 防止恶意深层嵌套耗尽栈，{@link Budget} 对整个对象图统一计数，
     * 避免攻击者用大量短数组绕过最终字符串长度限制。Map 键会过滤原型链、回调、
     * URL 和数据转换相关字段；未知 Java 对象不会被隐式序列化。</p>
     *
     * @param key 当前字段名（用于异常定位）
     * @param value 待清洗值
     * @param depth 当前嵌套深度
     * @param budget 全图计数预算
     * @return 清洗后的纯 JSON 兼容值
     */
    private Object sanitizeValue(String key, Object value, int depth, Budget budget) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("chart option nesting is too deep");
        }
        if (++budget.values > MAX_VALUES) {
            throw new IllegalArgumentException("chart option contains too many values");
        }
        if (value == null || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof String) {
            String text = (String) value;
            String normalized = text.trim().toLowerCase(Locale.ROOT);
            if (normalized.startsWith("javascript:") || normalized.startsWith("data:")
                    || normalized.startsWith("http://") || normalized.startsWith("https://")
                    || normalized.startsWith("image://http")) {
                throw new IllegalArgumentException("external URLs and executable strings are not allowed");
            }
            return text.length() > 4_000 ? text.substring(0, 4_000) : text;
        }
        if (value instanceof Map<?, ?>) {
            Map<?, ?> map = (Map<?, ?>) value;
            Map<String, Object> safe = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String childKey = String.valueOf(entry.getKey());
                if (BLOCKED_KEYS.contains(childKey.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                safe.put(childKey, sanitizeValue(childKey, entry.getValue(), depth + 1, budget));
            }
            return safe;
        }
        if (value instanceof Collection<?>) {
            Collection<?> collection = (Collection<?>) value;
            List<Object> safe = new ArrayList<>();
            for (Object item : collection) {
                safe.add(sanitizeValue(key, item, depth + 1, budget));
            }
            return safe;
        }
        if (value.getClass().isArray()) {
            List<Object> safe = new ArrayList<>();
            for (int i = 0; i < Array.getLength(value); i++) {
                safe.add(sanitizeValue(key, Array.get(value, i), depth + 1, budget));
            }
            return safe;
        }
        throw new IllegalArgumentException("chart option contains a non-JSON value at " + key);
    }

    /**
     * 校验 series 的结构和类型白名单。
     *
     * <p>series 决定前端需要加载的渲染器，因此这里必须和浏览器端注册的组件集合保持一致。</p>
     *
     * @param value option.series 原始值
     * @throws IllegalArgumentException series 缺失、类型不在白名单或数量超限时抛出
     */
    private void validateSeries(Object value) {
        if (value == null) {
            throw new IllegalArgumentException("option.series is required");
        }
        List<?> series = value instanceof List<?> ? (List<?>) value : Utils.asList(value);
        if (series.isEmpty() || series.size() > 20) {
            throw new IllegalArgumentException("option.series must contain 1 to 20 series");
        }
        for (Object item : series) {
            if (!(item instanceof Map<?, ?>)) {
                throw new IllegalArgumentException("every series must be an object");
            }
            Map<?, ?> map = (Map<?, ?>) item;
            String type = String.valueOf(map.get("type")).toLowerCase(Locale.ROOT);
            if (!ALLOWED_SERIES_TYPES.contains(type)) {
                throw new IllegalArgumentException("unsupported ECharts series type: " + type);
            }
        }
    }

    /**
     * 强制 tooltip 使用 richText 渲染，避免 HTML tooltip 接触模型生成的文本。
     *
     * @param option 已清洗的 option
     */
    @SuppressWarnings("unchecked")
    private void normalizeTooltip(Map<String, Object> option) {
        Object tooltip = option.get("tooltip");
        if (tooltip instanceof Map<?, ?>) {
            Map<?, ?> map = (Map<?, ?>) tooltip;
            ((Map<String, Object>) map).put("renderMode", "richText");
        }
    }

    /**
     * 清洗过程中的全局值计数预算。
     */
    private static final class Budget {
        /**
         * 已累计处理的节点数，达到上限即终止清洗。
         */
        private int values;
    }
}
