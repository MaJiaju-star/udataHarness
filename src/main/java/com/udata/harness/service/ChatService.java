package com.udata.harness.service;

import com.udata.harness.common.request.ChatRequest;
import com.udata.harness.common.request.HitlDecisionRequest;
import reactor.core.publisher.Flux;

/**
 * 流式对话、HITL 恢复和取消的统一服务契约。
 *
 * <p>返回的 Flux 元素是已经序列化的事件 JSON，由 Controller 直接作为 SSE 数据发出。
 * 实现必须保持事件顺序，并在终止、错误或取消时清理会话运行注册表。</p>
 */
public interface ChatService {
    /**
     * 在已有会话中提交用户提示词并启动一次 Harness 流式运行。
     */
    Flux<String> chat(String userId, ChatRequest request);

    /**
     * 将用户决策应用到待审批工具，并从 Harness 暂停点继续运行。
     */
    Flux<String> decide(String userId, HitlDecisionRequest request);

    /**
     * 取消 sessionId 对应的活动订阅。
     *
     * @return 找到活动运行并发出取消信号时为 true
     */
    boolean cancel(String sessionId);
}
