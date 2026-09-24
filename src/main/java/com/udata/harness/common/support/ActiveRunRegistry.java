package com.udata.harness.common.support;

import org.noear.solon.annotation.Component;
import org.reactivestreams.Subscription;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 活跃对话 Reactor 订阅注册表。
 *
 * <p>begin 防止同一 session 并发执行，bind 保存可取消订阅，doFinally 必须调用
 * end 清理状态。本组件是进程内状态，不承担持久化职责。</p>
 */
@Component
public class ActiveRunRegistry {
    /**
     * 正在运行的会话 Id 集合，begin/end 成对维护，防止同一会话并发执行。
     */
    private final Set<String> active = ConcurrentHashMap.newKeySet();

    /**
     * 会话 Id 到可取消订阅的映射，用于主动终止运行。
     */
    private final ConcurrentHashMap<String, Subscription> subscriptions = new ConcurrentHashMap<>();

    /**
     * 尝试占位当前会话的运行，保证同一会话同一时刻只执行一个 Flux。
     *
     * @param sessionId 会话标识
     * @return 之前未在运行时返回 true
     */
    public boolean begin(String sessionId) {
        return active.add(sessionId);
    }

    /**
     * 绑定可取消的 Reactor 订阅。
     *
     * @param sessionId 会话标识
     * @param subscription 订阅句柄
     */
    public void bind(String sessionId, Subscription subscription) {
        subscriptions.put(sessionId, subscription);
    }

    /**
     * 取消指定会话的活动订阅。
     *
     * @param sessionId 会话标识
     * @return 找到活动订阅并发出取消信号时为 true
     */
    public boolean cancel(String sessionId) {
        Subscription subscription = subscriptions.get(sessionId);
        if (subscription == null) {
            return false;
        }
        subscription.cancel();
        return true;
    }

    /**
     * 释放会话活动状态与订阅句柄，必须在 doFinally 中调用。
     *
     * @param sessionId 会话标识
     */
    public void end(String sessionId) {
        subscriptions.remove(sessionId);
        active.remove(sessionId);
    }

    /**
     * 判断会话是否存在活动运行。
     *
     * @param sessionId 会话标识
     * @return 运行时返回 true
     */
    public boolean isActive(String sessionId) {
        return active.contains(sessionId);
    }
}
