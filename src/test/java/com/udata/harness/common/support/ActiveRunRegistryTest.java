package com.udata.harness.common.support;

import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscription;

import java.util.concurrent.atomic.AtomicBoolean;
import reactor.core.publisher.Flux;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActiveRunRegistryTest {
    @Test
    void stopCancelsUpstreamCompletesSseAndReleasesSession() {
        ActiveRunRegistry registry = new ActiveRunRegistry();
        AtomicBoolean upstreamCancelled = new AtomicBoolean();
        AtomicBoolean completed = new AtomicBoolean();
        assertTrue(registry.begin("stream"));
        Flux.<String>never()
                .doOnCancel(() -> upstreamCancelled.set(true))
                .doOnSubscribe(subscription -> registry.bind("stream", subscription))
                .transform(source -> registry.cancellable("stream", source))
                .doFinally(signal -> registry.end("stream"))
                .subscribe(value -> {}, error -> { throw new AssertionError(error); }, () -> completed.set(true));
        assertTrue(registry.cancel("stream"));
        assertTrue(upstreamCancelled.get());
        assertTrue(completed.get());
        assertFalse(registry.isActive("stream"));
        assertTrue(registry.begin("stream"));
    }

    @Test
    void stopBeforeSubscriptionPreventsWorkFromStarting() {
        ActiveRunRegistry registry = new ActiveRunRegistry();
        AtomicBoolean started = new AtomicBoolean();
        AtomicBoolean completed = new AtomicBoolean();
        assertTrue(registry.begin("early"));
        assertTrue(registry.cancel("early"));
        Flux.<String>defer(() -> { started.set(true); return Flux.never(); })
                .doOnSubscribe(subscription -> registry.bind("early", subscription))
                .transform(source -> registry.cancellable("early", source))
                .doFinally(signal -> registry.end("early"))
                .subscribe(value -> {}, error -> { throw new AssertionError(error); }, () -> completed.set(true));
        assertFalse(started.get());
        assertTrue(completed.get());
        assertFalse(registry.isActive("early"));
    }

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
