package com.udata.harness.common.support;

import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChartToolTest {
    private final ChartTool tool = new ChartTool();

    @Test
    void returnsASanitizedEChartsOption() {
        String json = tool.renderEchart("季度收入", Map.of(
                "tooltip", Map.of("trigger", "axis", "formatter", "<b>unsafe</b>"),
                "xAxis", Map.of("type", "category", "data", List.of("Q1", "Q2")),
                "yAxis", Map.of("type", "value"),
                "series", List.of(Map.of("type", "bar", "data", List.of(12.5, 18.0)))));

        Map<?, ?> result = ONode.deserialize(json, Map.class);
        assertEquals("echarts", result.get("engine"));
        Map<?, ?> option = (Map<?, ?>) result.get("option");
        assertEquals("季度收入", ((Map<?, ?>) option.get("title")).get("text"));
        assertEquals("richText", ((Map<?, ?>) option.get("tooltip")).get("renderMode"));
        assertFalse(json.contains("formatter"));
    }

    @Test
    void rejectsExecutableUrlsAndUnknownSeries() {
        assertThrows(IllegalArgumentException.class, () -> tool.renderEchart("bad", Map.of(
                "backgroundColor", "javascript:alert(1)",
                "series", List.of(Map.of("type", "line", "data", List.of(1))))));
        assertThrows(IllegalArgumentException.class, () -> tool.renderEchart("bad", Map.of(
                "series", List.of(Map.of("type", "custom", "data", List.of(1))))));
    }
}
