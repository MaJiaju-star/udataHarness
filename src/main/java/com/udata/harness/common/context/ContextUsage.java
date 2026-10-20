package com.udata.harness.common.context;

import org.noear.solon.ai.AiUsage;
import org.noear.snack4.ONode;
import java.util.LinkedHashMap;
import java.util.Map;

/** Normalize non-overlapping categories; omitted cache fields remain unknown rather than silently zero. */
public final class ContextUsage {
    public static Map<String, Object> normalize(AiUsage usage, ContextCostPolicy.Prices prices) {
        if (usage == null) return new LinkedHashMap<>();
        // AiUsage has already normalized provider input (including Anthropic caches).
        // accounting only applies to the raw-count overload, never add caches twice.
        return normalize(usage.promptTokens(), usage.completionTokens(), usage.cacheReadInputTokens(),
                usage.cacheCreationInputTokens(), usage.getSource(), prices, "inclusive");
    }

    public static Map<String, Object> normalize(long prompt, long output, long read, long write, ONode source,
                                                ContextCostPolicy.Prices prices) {
        return normalize(prompt, output, read, write, source, prices, prices.accounting);
    }

    private static Map<String, Object> normalize(long prompt, long output, long read, long write, ONode source,
                                                ContextCostPolicy.Prices prices, String accounting) {
        boolean readKnown = read > 0 || present(source, "cache_read_input_tokens") || present(source, "prompt_cache_hit_tokens")
                || present(source, "cachedContentTokenCount") || present(source, "cached_content_token_count")
                || (source != null && present(source.get("prompt_tokens_details"), "cached_tokens"))
                || (source != null && present(source.get("input_tokens_details"), "cached_tokens"));
        boolean writeKnown = write > 0 || present(source, "cache_creation_input_tokens")
                || "inclusive".equals(accounting);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("promptTokens", prompt); result.put("outputTokens", output);
        result.put("cacheReadTokens", readKnown ? read : null);
        result.put("cacheWriteTokens", writeKnown ? write : null);
        Long input = null;
        if ("inclusive".equals(accounting)) input = prompt;
        else if ("exclusive".equals(accounting) && readKnown && writeKnown) input = prompt + read + write;
        if (input != null && (input < 0 || read < 0 || write < 0 || input < read + write)) input = null;
        result.put("inputTokens", input);
        result.put("cacheHitRate", input != null && input > 0 && readKnown ? (double) read / input : null);
        result.put("estimatedCost", prices.cost(result));
        result.put("currency", prices.currency);
        result.put("prices", prices.view());
        return result;
    }

    private static boolean present(ONode source, String key) { return source != null && source.hasKey(key) && !source.get(key).isNull(); }
}
