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
    private final Set<String> active = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, Subscription> subscriptions = new ConcurrentHashMap<>();

    public boolean begin(String sessionId) {
        return active.add(sessionId);
    }

    public void bind(String sessionId, Subscription subscription) {
        subscriptions.put(sessionId, subscription);
    }

    public boolean cancel(String sessionId) {
        Subscription subscription = subscriptions.get(sessionId);
        if (subscription == null) {
            return false;
        }
        subscription.cancel();
        return true;
    }

    public void end(String sessionId) {
        subscriptions.remove(sessionId);
        active.remove(sessionId);
    }

    public boolean isActive(String sessionId) {
        return active.contains(sessionId);
    }
}
