package com.udata.harness.common.request;

import java.util.Map;
import java.util.List;

/**
 * HITL 审批请求，支持 approve、skip、reject、参数修改及永久允许。
 *
 * <p>{@code callUuids} 对应当前批次中每个工具调用的唯一标识。批量工具审批必须按
 * callUuid 提交，不能只使用 toolName，因为同一批次可能出现多个同名工具调用。</p>
 */
public class HitlDecisionRequest {
    /**
     * 目标会话标识。
     */
    private String sessionId;

    /**
     * 审批动作：approve/skip/reject。
     */
    private String action;

    /**
     * 可选审批备注。
     */
    private String comment;

    /**
     * 是否永久允许本批工具（会话级）。
     */
    private boolean alwaysAllow;

    /**
     * 本批待审批工具调用的 callUuid 列表。
     */
    private List<String> callUuids;

    /**
     * 可选修改后的工具参数（按 callUuid 对应）。
     */
    private Map<String, Object> modifiedArgs;

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }

    public boolean isAlwaysAllow() {
        return alwaysAllow;
    }

    public void setAlwaysAllow(boolean alwaysAllow) {
        this.alwaysAllow = alwaysAllow;
    }

    public List<String> getCallUuids() {
        return callUuids;
    }

    public void setCallUuids(List<String> callUuids) {
        this.callUuids = callUuids;
    }

    public Map<String, Object> getModifiedArgs() {
        return modifiedArgs;
    }

    public void setModifiedArgs(Map<String, Object> modifiedArgs) {
        this.modifiedArgs = modifiedArgs;
    }
}
