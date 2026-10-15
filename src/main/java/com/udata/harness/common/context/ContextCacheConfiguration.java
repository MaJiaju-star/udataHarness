package com.udata.harness.common.context;

import org.noear.solon.Solon;
import org.noear.solon.annotation.Component;
import org.noear.solon.annotation.Inject;
import org.noear.solon.annotation.Init;
import org.noear.solon.core.Props;

/** Prices are explicitly configured per routed model alias; no vendor prices are hard coded. */
@Component
public class ContextCacheConfiguration {
    @Inject("${agent.context.cache.enabled:true}")
    public boolean enabled = true;
    @Inject("${agent.context.cache.expected-remaining-calls:4}")
    public int remainingCalls = 4;
    @Inject("${agent.context.cache.restore-budget-tokens:2000}")
    public int restoreBudgetTokens = 2000;
    @Inject("${agent.context.cache.safety-margin:1.2}")
    public double safetyMargin = 1.2;
    @Inject("${agent.context.cache.pressure-ratio:0.8}")
    public double pressureRatio = 0.8;

    @Init
    public void validate() {
        new ContextCostPolicy(enabled, remainingCalls, restoreBudgetTokens, safetyMargin, pressureRatio,
                ContextCostPolicy.Prices.from(new Props()));
    }

    public ContextCostPolicy policy(String model) {
        Props props = Solon.app() == null ? new Props() : Solon.cfg().getProp("agent.context.cache.models." + model);
        return new ContextCostPolicy(enabled, remainingCalls, restoreBudgetTokens, safetyMargin, pressureRatio,
                ContextCostPolicy.Prices.from(props));
    }
}
