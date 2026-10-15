package com.udata.harness.common.context;

import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;
import org.noear.solon.core.Props;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ContextCostPolicyTest {
    private final ContextCostPolicy.Prices inclusive = new ContextCostPolicy.Prices(10, 1, 12, 20, "inclusive", "USD");

    @Test
    void pricesInclusiveAndExclusiveInputsWithoutDoubleCounting() {
        Map<String, Object> a = ContextUsage.normalize(1000, 100, 600, 100,
                ONode.ofJson("{\"prompt_tokens_details\":{\"cached_tokens\":600}}"), inclusive);
        assertEquals(1000L, a.get("inputTokens"));
        assertEquals((300 * 10 + 600 + 100 * 12 + 100 * 20) / 1000000D, (Double) a.get("estimatedCost"), 1e-12);
        ContextCostPolicy.Prices exclusive = new ContextCostPolicy.Prices(10, 1, 12, 20, "exclusive", "USD");
        Map<String, Object> b = ContextUsage.normalize(300, 100, 600, 100, null, exclusive);
        assertEquals(1000L, b.get("inputTokens"));
        assertEquals(a.get("estimatedCost"), b.get("estimatedCost"));
    }

    @Test
    void missingCacheFieldsAreUnknownButExplicitZeroIsKnown() {
        Map<String, Object> unconfigured = ContextUsage.normalize(1000, 100, 0, 0, null, ContextCostPolicy.Prices.from(new Props()));
        assertEquals(1000L, unconfigured.get("promptTokens"));
        assertNull(unconfigured.get("inputTokens")); assertNull(unconfigured.get("estimatedCost"));
        Map<String, Object> missing = ContextUsage.normalize(1000, 100, 0, 0, ONode.ofJson("{\"prompt_tokens\":1000}"), inclusive);
        assertNull(missing.get("cacheReadTokens")); assertNull(missing.get("estimatedCost"));
        Map<String, Object> zero = ContextUsage.normalize(1000, 100, 0, 0,
                ONode.ofJson("{\"prompt_tokens_details\":{\"cached_tokens\":0}}"), inclusive);
        assertEquals(0L, zero.get("cacheReadTokens")); assertNotNull(zero.get("estimatedCost"));
    }

    @Test
    void preservesExpensiveCachedPrefixButAllowsNetSavingAndCapacityPressure() {
        Map<String, Object> usage = ContextUsage.normalize(100000, 100, 90000, 0, null, inclusive);
        ContextCostPolicy policy = new ContextCostPolicy(true, 4, 2000, 1.2, .8, inclusive);
        assertFalse((Boolean) policy.decide(100000, 1000, 1000000, usage).get("allow"));
        assertEquals("preserve_cached_prefix", policy.decide(100000, 1000, 1000000, usage).get("reason"));
        ContextCostPolicy longTask = new ContextCostPolicy(true, 10, 2000, 1.2, .8, inclusive);
        assertTrue((Boolean) longTask.decide(100000, 99000, 1000000, usage).get("allow"));
        Map<String, Object> pressure = policy.decide(800000, 1000, 1000000, usage);
        assertTrue((Boolean) pressure.get("allow")); assertEquals("capacity_pressure", pressure.get("reason"));
    }

    @Test
    void missingPricesOrUsagePreserveHistoryAndDisabledPolicyMaintainsLegacyBehavior() {
        ContextCostPolicy unknown = new ContextCostPolicy(true, 4, 2000, 1.2, .8, ContextCostPolicy.Prices.from(new Props()));
        assertEquals("prices_unknown", unknown.decide(100, 50, 1000000, null).get("reason"));
        ContextCostPolicy priced = new ContextCostPolicy(true, 4, 2000, 1.2, .8, inclusive);
        assertEquals("cache_usage_unknown", priced.decide(100, 50, 1000000, null).get("reason"));
        ContextCostPolicy disabled = new ContextCostPolicy(false, 4, 2000, 1.2, .8, inclusive);
        assertTrue((Boolean) disabled.decide(100, 50, 1000000, null).get("allow"));
        assertThrows(IllegalArgumentException.class, () -> new ContextCostPolicy(true, 0, 2000, 1.2, .8, inclusive));
        assertFalse(new ContextCostPolicy.Prices(Double.NaN, 1, 2, 3, "inclusive", "USD").valid());
        assertNull(inclusive.cost(ContextUsage.normalize(100, 0, 200, 0, null, inclusive)));
    }

    @Test
    void estimatesCommonMemoryPrefixWithoutPersistingRawContent() {
        java.util.List<org.noear.solon.ai.chat.message.ChatMessage> messages = java.util.Arrays.asList(
                org.noear.solon.ai.chat.message.ChatMessage.ofUser("PRIVATE_CONSTRAINT"),
                org.noear.solon.ai.chat.message.ChatMessage.ofUser("Next task"));
        ContextTelemetryInterceptor.Footprint a = ContextTelemetryInterceptor.footprint(messages, "system", null, null, "m");
        ContextTelemetryInterceptor.Footprint b = ContextTelemetryInterceptor.footprint(messages, "system", null, null, "m");
        assertEquals(a.view.get("estimatedTokens"), b.shared(a));
        assertEquals(0L, ContextTelemetryInterceptor.footprint(messages, "changed", null, null, "m").shared(a));
        assertNull(a.shared(null));
        assertFalse(ONode.serialize(a.view).contains("PRIVATE_CONSTRAINT"));
    }
}
