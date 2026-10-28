import assert from "node:assert/strict";
import test from "node:test";
import {chartPath, knownNumber, moneyText, prefixChanges, summarizeContext} from "../src/contextMetrics.js";

test("physical request diagnostics stay separate from usage accounting and filter by agent", () => {
    const a = {id: "a", timestamp: 1, agentName: "main"}, b = {id: "b", timestamp: 2, agentName: "child"};
    const result = summarizeContext({requestDiagnostics: [a, a, b]});
    assert.deepEqual(result.diagnostics, [b, a]); assert.equal(result.calls.length, 0);
    assert.deepEqual(summarizeContext({requestDiagnostics: [a, b]}, "main").diagnostics, [a]);
    assert.equal(prefixChanges({baselineAvailable: true, appendOnly: true, changedSections: ["messages"]}), "历史仅追加");
    assert.match(prefixChanges({changedSections: ["system", "tools"], changedParameters: ["thinking"]}), /系统指令.*工具定义.*thinking/);
});

test("unknown metrics remain unknown and explicit zero is retained", () => {
    assert.equal(knownNumber(null), null);
    assert.equal(knownNumber(undefined), null);
    assert.equal(knownNumber(NaN), null);
    assert.equal(knownNumber(0), 0);
    assert.equal(moneyText(null), "未知");
    assert.match(moneyText(0), /USD 0/);
});

test("costs deduplicate calls, include compaction and keep currencies separate", () => {
    const row = {id: "a", agentName: "main", timestamp: 1, usage: {estimatedCost: .01, currency: "USD"}};
    const data = {observations: [row, row, {id: "b", timestamp: 2, agentName: "child", usage: {estimatedCost: .02, currency: "CNY"}},
        {id: "c", timestamp: 3, agentName: "main", usage: {estimatedCost: null}}],
    compactions: [{id: "compact", scope: "compaction", normalizedUsage: {estimatedCost: .03, currency: "USD"}}]};
    const result = summarizeContext(data);
    assert.equal(result.calls.length, 3);
    assert.equal(result.costs.USD, .04);
    assert.equal(result.costs.CNY, .02);
    assert.equal(result.unknownCosts, 1);
    const filtered = summarizeContext(data, "main");
    assert.equal(filtered.calls.length, 2);
    assert.equal(filtered.costs.USD, .01);
    assert.equal(filtered.costs.CNY, undefined);
});

test("chart gaps do not imply zero usage or bridge missing telemetry", () => {
    assert.equal(chartPath([null, undefined], 100), "");
    const path = chartPath([10, null, 20], 100);
    assert.equal((path.match(/M/g) || []).length, 2);
    assert.equal(path.includes("NaN"), false);
    assert.equal(chartPath([0], 0).includes("NaN"), false);
});
