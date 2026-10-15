package com.udata.harness.common.context;

import org.noear.solon.core.Props;
import java.util.LinkedHashMap;
import java.util.Map;

/** Conservative, explainable estimate. Capacity protection takes precedence over monetary estimates. */
public final class ContextCostPolicy {
    private final boolean enabled;
    private final int calls;
    private final int restoreTokens;
    private final double margin;
    private final double pressure;
    public final Prices prices;

    public ContextCostPolicy(boolean enabled, int calls, int restoreTokens, double margin, double pressure, Prices prices) {
        if (calls < 1 || calls > 100 || restoreTokens < 0 || !Double.isFinite(margin) || margin < 1
                || !Double.isFinite(pressure) || pressure <= 0 || pressure > 1) {
            throw new IllegalArgumentException("Invalid context cache policy configuration");
        }
        this.enabled = enabled; this.calls = calls; this.restoreTokens = restoreTokens;
        this.margin = margin; this.pressure = pressure; this.prices = prices;
    }

    public Map<String, Object> decide(long before, long saved, long capacity, Map<String, Object> previous) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("beforeEstimatedTokens", before);
        result.put("savedEstimatedTokens", saved);
        result.put("expectedRemainingCalls", calls);
        result.put("restoreBudgetTokens", restoreTokens);
        result.put("safetyMargin", margin);
        result.put("currency", prices.currency);
        boolean urgent = capacity > 0 && before >= capacity * pressure;
        String reason;
        boolean allow;
        if (!enabled) { allow = true; reason = "policy_disabled"; }
        else if (urgent) { allow = true; reason = "capacity_pressure"; }
        else if (!prices.valid()) { allow = false; reason = "prices_unknown"; }
        else if (previous == null || !(previous.get("cacheReadTokens") instanceof Number)
                || !(previous.get("inputTokens") instanceof Number)) {
            allow = false; reason = "cache_usage_unknown";
        } else {
            double cached = number(previous.get("cacheReadTokens"));
            double input = number(previous.get("inputTokens"));
            double hit = input > 0 ? Math.min(1, cached / input) : 0;
            double rate = prices.input * (1 - hit) + prices.read * hit;
            double benefit = saved * calls * rate / 1000000D;
            // Assume the entire last cached prefix needs rebuilding; a conservative upper bound.
            double rewarm = cached * Math.max(0, Math.max(prices.input, prices.write) - prices.read) / 1000000D;
            double restore = restoreTokens * prices.input / 1000000D;
            double overhead = rewarm + restore;
            result.put("estimatedBenefit", benefit);
            result.put("estimatedRewarmCost", rewarm);
            result.put("estimatedRestoreCost", restore);
            result.put("estimatedOverhead", overhead);
            result.put("observedCacheReadTokens", cached);
            allow = saved > 0 && benefit > overhead * margin;
            reason = allow ? "estimated_net_saving" : "preserve_cached_prefix";
        }
        result.put("allow", allow); result.put("reason", reason);
        return result;
    }

    static double number(Object value) { return value instanceof Number ? ((Number) value).doubleValue() : 0; }

    public static final class Prices {
        public final double input, read, write, output;
        public final String accounting, currency;
        public Prices(double input, double read, double write, double output, String accounting, String currency) {
            this.input = input; this.read = read; this.write = write; this.output = output;
            this.accounting = accounting; this.currency = currency;
        }
        public boolean valid() {
            return finite(input) && finite(read) && finite(write) && finite(output)
                    && ("inclusive".equals(accounting) || "exclusive".equals(accounting))
                    && currency != null && currency.matches("[A-Z]{3}");
        }
        private static boolean finite(double n) { return Double.isFinite(n) && n >= 0; }
        public static Prices from(Props props) {
            return new Prices(rate(props, "input-per-million"), rate(props, "cache-read-per-million"),
                    rate(props, "cache-write-per-million"), rate(props, "output-per-million"),
                    props.getProperty("accounting", "unknown"), props.getProperty("currency", "USD"));
        }
        private static double rate(Props props, String name) {
            try { return Double.parseDouble(props.getProperty(name, "-1")); }
            catch (NumberFormatException e) { return -1; }
        }
        public Map<String, Object> view() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("configured", valid()); result.put("accounting", accounting); result.put("currency", currency);
            if (valid()) {
                result.put("inputPerMillion", input); result.put("cacheReadPerMillion", read);
                result.put("cacheWritePerMillion", write); result.put("outputPerMillion", output);
            }
            return result;
        }
        public Double cost(Map<String, Object> usage) {
            if (!valid() || usage == null || !(usage.get("inputTokens") instanceof Number)
                    || !(usage.get("cacheReadTokens") instanceof Number) || !(usage.get("cacheWriteTokens") instanceof Number)
                    || !(usage.get("outputTokens") instanceof Number)) return null;
            double total = number(usage.get("inputTokens"));
            double cached = number(usage.get("cacheReadTokens"));
            double created = number(usage.get("cacheWriteTokens"));
            if (cached < 0 || created < 0 || total < cached + created || number(usage.get("outputTokens")) < 0) return null;
            return ((total - cached - created) * input + cached * read + created * write
                    + number(usage.get("outputTokens")) * output) / 1000000D;
        }
    }
}
