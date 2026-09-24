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
 * Solon AI Tool：校验并返回声明式 Ant Design Charts 配置。
 *
 * <p>它与 {@link ChartTool} 并存，只传输 JSON 兼容值；JavaScript 函数、HTML、
 * 外部资源、回调和原型污染键不会进入浏览器。</p>
 */
public class AntVChartTool extends AbsToolProvider {
    /**
     * 配置树允许的最大嵌套深度，超出即拒绝。
     */
    private static final int MAX_DEPTH = 10;

    /**
     * 单次清洗允许处理的最大节点数，防止超大配置拖垮服务。
     */
    private static final int MAX_VALUES = 12_000;

    /**
     * 序列化后 JSON 的最大长度（字符数），超出即拒绝。
     */
    private static final int MAX_JSON_LENGTH = 256_000;

    /**
     * data 允许的最大行数。
     */
    private static final int MAX_ROWS = 10_000;

    /**
     * 允许的图表类型白名单。
     */
    private static final Set<String> ALLOWED_CHART_TYPES =
            Collections.unmodifiableSet(Utils.asSet("column", "bar", "line", "area", "pie", "scatter"));

    /**
     * 允许透传到前端的顶层配置键白名单，其余键一律丢弃。
     */
    private static final Set<String> ALLOWED_CONFIG_KEYS = Collections.unmodifiableSet(Utils.asSet(
            "data", "xField", "yField", "colorField", "angleField", "seriesField",
            "shapeField", "sizeField", "stack", "group", "percent", "normalize",
            "sort", "transpose", "axis", "legend", "tooltip", "label", "style",
            "scale", "interaction", "animate", "theme", "padding", "inset",
            "height", "autoFit", "radius", "innerRadius", "smooth", "point",
            "area", "line", "connectNulls"));

    /**
     * 命中即丢弃的配置键黑名单，覆盖原型污染键、回调与外部资源键。
     */
    private static final Set<String> BLOCKED_KEYS = Collections.unmodifiableSet(Utils.asSet(
            "__proto__", "prototype", "constructor", "formatter", "innerhtml",
            "dangerouslysetinnerhtml", "onclick", "onready", "oninit", "onerror",
            "link", "url", "src", "image", "transform", "callback"));

    /**
     * 生成 AntV 统计图表配置。
     *
     * @param title 图表卡片标题
     * @param chartType column、bar、line、area、pie 或 scatter
     * @param config Ant Design Charts 声明式配置
     * @return 带版本、引擎、图表类型和安全配置的 JSON 字符串
     */
    @ToolMapping(
            name = "render_antv_chart",
            description = "Render an interactive AntV / Ant Design Charts visualization in the chat UI. Prefer this for polished business column, bar, line, area, pie, or scatter charts. Pass declarative JSON only; JavaScript functions, HTML, callbacks, transforms, images, and external URLs are not supported.")
    public String renderAntVChart(
            @Param(description = "Short chart title") String title,
            @Param(description = "One of: column, bar, line, area, pie, scatter") String chartType,
            @Param(description = "Declarative Ant Design Charts config. Include data and field mappings such as xField/yField or angleField/colorField")
                    Map<String, Object> config) {
        String normalizedType = chartType == null
                ? ""
                : chartType.trim().toLowerCase(Locale.ROOT);
        if (!ALLOWED_CHART_TYPES.contains(normalizedType)) {
            throw new IllegalArgumentException("unsupported AntV chart type: " + chartType);
        }
        if (config == null || config.isEmpty()) {
            throw new IllegalArgumentException("config is required");
        }

        //1. 先校验图表类型，再对 config 做顶层字段白名单筛选。
        Map<String, Object> selected = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : config.entrySet()) {
            if (ALLOWED_CONFIG_KEYS.contains(entry.getKey())) {
                selected.put(entry.getKey(), entry.getValue());
            }
        }
        Budget budget = new Budget();
        @SuppressWarnings("unchecked")
        Map<String, Object> safeConfig =
                (Map<String, Object>) sanitizeValue(null, selected, 0, budget);
        //2. 校验 data 与必需字段映射，再补齐自适应、高度与动画默认值。
        validateConfig(normalizedType, safeConfig);
        safeConfig.putIfAbsent("autoFit", true);
        safeConfig.putIfAbsent("height", 360);
        safeConfig.putIfAbsent("animate", Utils.asMap("enter", Utils.asMap("type", "fadeIn")));

        //3. 组装最终结果并做序列化长度限制。
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("version", 1);
        result.put("engine", "antv");
        result.put("chartType", normalizedType);
        result.put("title", title == null ? "" : title.trim());
        result.put("config", safeConfig);
        String json = ONode.serialize(result);
        if (json.length() > MAX_JSON_LENGTH) {
            throw new IllegalArgumentException("AntV chart config is too large");
        }
        return json;
    }

    /**
     * 校验 data 行数与必需字段映射。
     *
     * <p>饼图需要 angleField/colorField，其余类型需要 xField/yField。</p>
     *
     * @param chartType 已归一化的图表类型
     * @param config 已清洗的配置
     * @throws IllegalArgumentException 数据缺失/超限或必需字段缺失时抛出
     */
    private void validateConfig(String chartType, Map<String, Object> config) {
        Object data = config.get("data");
        if (!(data instanceof List<?>)) {
            throw new IllegalArgumentException("config.data must contain 1 to " + MAX_ROWS + " rows");
        }
        List<?> rows = (List<?>) data;
        if (rows.isEmpty() || rows.size() > MAX_ROWS) {
            throw new IllegalArgumentException("config.data must contain 1 to " + MAX_ROWS + " rows");
        }
        if (rows.stream().anyMatch(row -> !(row instanceof Map<?, ?>))) {
            throw new IllegalArgumentException("every config.data row must be an object");
        }
        if ("pie".equals(chartType)) {
            requireField(config, "angleField");
            requireField(config, "colorField");
        } else {
            requireField(config, "xField");
            requireField(config, "yField");
        }
    }

    /**
     * 断言指定字段为非空字符串。
     *
     * @param config 待检查配置
     * @param name 字段名
     * @throws IllegalArgumentException 字段缺失或为空时抛出
     */
    private void requireField(Map<String, Object> config, String name) {
        Object value = config.get(name);
        if (!(value instanceof String) || Utils.isBlank((String) value)) {
            throw new IllegalArgumentException("config." + name + " is required");
        }
    }

    /**
     * 深拷贝并清洗任意 JSON 值树（规则与 {@link ChartTool} 一致）。
     *
     * @param key 当前字段名（用于异常定位）
     * @param value 待清洗值
     * @param depth 当前嵌套深度
     * @param budget 全图计数预算
     * @return 清洗后的纯 JSON 兼容值
     */
    private Object sanitizeValue(String key, Object value, int depth, Budget budget) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("AntV chart config nesting is too deep");
        }
        if (++budget.values > MAX_VALUES) {
            throw new IllegalArgumentException("AntV chart config contains too many values");
        }
        if (value == null || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof String) {
            String text = (String) value;
            String normalized = text.trim().toLowerCase(Locale.ROOT);
            if (normalized.startsWith("javascript:")
                    || normalized.startsWith("data:")
                    || normalized.startsWith("http://")
                    || normalized.startsWith("https://")
                    || normalized.startsWith("<")) {
                throw new IllegalArgumentException("HTML, external URLs and executable strings are not allowed");
            }
            return text.length() > 4_000 ? text.substring(0, 4_000) : text;
        }
        if (value instanceof Map<?, ?>) {
            Map<?, ?> map = (Map<?, ?>) value;
            Map<String, Object> safe = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String childKey = String.valueOf(entry.getKey());
                if (!BLOCKED_KEYS.contains(childKey.toLowerCase(Locale.ROOT))) {
                    safe.put(childKey, sanitizeValue(childKey, entry.getValue(), depth + 1, budget));
                }
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
        throw new IllegalArgumentException("AntV chart config contains a non-JSON value at " + key);
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
