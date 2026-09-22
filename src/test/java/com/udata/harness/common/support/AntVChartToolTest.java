package com.udata.harness.common.support;

import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AntVChartToolTest {
    private final AntVChartTool tool = new AntVChartTool();

    @Test
    void returnsSanitizedColumnConfig() {
        String json = tool.renderAntVChart("季度收入", "column", Map.of(
                "data", List.of(
                        Map.of("quarter", "Q1", "value", 12.5),
                        Map.of("quarter", "Q2", "value", 18.0)),
                "xField", "quarter",
                "yField", "value",
                "tooltip", Map.of("formatter", "<b>unsafe</b>")));

        Map<?, ?> result = ONode.deserialize(json, Map.class);
        assertEquals("antv", result.get("engine"));
        assertEquals("column", result.get("chartType"));
        Map<?, ?> config = (Map<?, ?>) result.get("config");
        assertEquals("quarter", config.get("xField"));
        assertFalse(json.contains("formatter"));
    }

    @Test
    void rejectsInvalidTypeAndExecutableValue() {
        assertThrows(IllegalArgumentException.class, () -> tool.renderAntVChart(
                "bad", "sankey", Map.of("data", List.of(Map.of("x", "A", "y", 1)))));
        assertThrows(IllegalArgumentException.class, () -> tool.renderAntVChart(
                "bad", "line", Map.of(
                        "data", List.of(Map.of("x", "A", "y", 1)),
                        "xField", "x",
                        "yField", "y",
                        "theme", "https://example.com/theme.json")));
    }
}
