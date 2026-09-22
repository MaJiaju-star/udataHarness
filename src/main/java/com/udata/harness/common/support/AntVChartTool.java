package com.udata.harness.common.support;

import org.noear.snack4.ONode;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.tool.AbsToolProvider;
import org.noear.solon.annotation.Param;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
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
    private static final int MAX_DEPTH = 10;
    private static final int MAX_VALUES = 12_000;
    private static final int MAX_JSON_LENGTH = 256_000;
    private static final int MAX_ROWS = 10_000;

    private static final Set<String> ALLOWED_CHART_TYPES =
            Set.of("column", "bar", "line", "area", "pie", "scatter");
    private static final Set<String> ALLOWED_CONFIG_KEYS = Set.of(
            "data", "xField", "yField", "colorField", "angleField", "seriesField",
            "shapeField", "sizeField", "stack", "group", "percent", "normalize",
            "sort", "transpose", "axis", "legend", "tooltip", "label", "style",
            "scale", "interaction", "animate", "theme", "padding", "inset",
            "height", "autoFit", "radius", "innerRadius", "smooth", "point",
            "area", "line", "connectNulls");
    private static final Set<String> BLOCKED_KEYS = Set.of(
            "__proto__", "prototype", "constructor", "formatter", "innerhtml",
            "dangerouslysetinnerhtml", "onclick", "onready", "oninit", "onerror",
            "link", "url", "src", "image", "transform", "callback");

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
        validateConfig(normalizedType, safeConfig);
        safeConfig.putIfAbsent("autoFit", true);
        safeConfig.putIfAbsent("height", 360);
        safeConfig.putIfAbsent("animate", Map.of("enter", Map.of("type", "fadeIn")));

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

    private void validateConfig(String chartType, Map<String, Object> config) {
        Object data = config.get("data");
        if (!(data instanceof List<?> rows) || rows.isEmpty() || rows.size() > MAX_ROWS) {
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

    private void requireField(Map<String, Object> config, String name) {
        Object value = config.get(name);
        if (!(value instanceof String field) || field.isBlank()) {
            throw new IllegalArgumentException("config." + name + " is required");
        }
    }

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
        if (value instanceof String text) {
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
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> safe = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String childKey = String.valueOf(entry.getKey());
                if (!BLOCKED_KEYS.contains(childKey.toLowerCase(Locale.ROOT))) {
                    safe.put(childKey, sanitizeValue(childKey, entry.getValue(), depth + 1, budget));
                }
            }
            return safe;
        }
        if (value instanceof Collection<?> collection) {
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

    private static final class Budget {
        private int values;
    }
}
