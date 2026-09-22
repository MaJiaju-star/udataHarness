package com.udata.harness.common.support;

import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscription;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActiveRunRegistryTest {
    @Test
    void preventsConcurrentRunsAndCancelsBoundSubscription() {
        ActiveRunRegistry registry = new ActiveRunRegistry();
        AtomicBoolean cancelled = new AtomicBoolean();
        Subscription subscription = new Subscription() {
            @Override
            public void request(long n) {
            }

            @Override
            public void cancel() {
                cancelled.set(true);
            }
        };

        assertTrue(registry.begin("session"));
        assertFalse(registry.begin("session"));
        registry.bind("session", subscription);
        assertTrue(registry.cancel("session"));
        assertTrue(cancelled.get());

        registry.end("session");
        assertFalse(registry.isActive("session"));
        assertTrue(registry.begin("session"));
    }
}
