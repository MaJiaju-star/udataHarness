import test from "node:test";
import assert from "node:assert/strict";
import {latestProjectInstructions, projectInstructionsLabel} from "../src/projectInstructions.js";

test("backend status reconciles absent SSE and newer unloaded results replace stale file names", () => {
    const loaded = {loaded: true, source: "CLAUDE.md", checkedAt: 10};
    const unloaded = {loaded: false, checkedAt: 20};
    assert.equal(latestProjectInstructions(undefined, loaded), loaded);
    assert.equal(latestProjectInstructions(unloaded, loaded), unloaded);
    assert.equal(latestProjectInstructions(loaded, null), loaded);
    assert.equal(projectInstructionsLabel(unloaded), "未加载");
});

test("unknown, failed and actual load results have distinct display states", () => {
    assert.equal(projectInstructionsLabel(null), "查询中…");
    assert.equal(projectInstructionsLabel(null, {checked: true}), "尚未读取");
    assert.equal(projectInstructionsLabel(null, {checked: true, error: "offline"}), "查询失败");
    assert.equal(projectInstructionsLabel({loaded: true, source: "AGENTS.md", truncated: true}), "AGENTS.md · 截断");
    assert.equal(projectInstructionsLabel(latestProjectInstructions({}), {checked: true}), "尚未读取");
    assert.equal(projectInstructionsLabel({loaded: false, mode: "off"}), "已关闭");
    assert.equal(projectInstructionsLabel({loaded: false, mode: "claude"}), "CLAUDE.md 不可用");
    assert.equal(projectInstructionsLabel({loaded: false, mode: "agents"}), "AGENTS.md 不可用");
});
