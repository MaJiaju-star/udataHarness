package com.udata.harness.config;

import org.junit.jupiter.api.Test;
import org.noear.solon.core.handle.ContextEmpty;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class WebEntryCacheFilterTest {
    @Test
    void htmlEntryDisablesCachingBeforeResourceHandler() throws Throwable {
        for (String path : new String[]{"/", "/index.html"}) {
            Map<String, String> headers = new HashMap<>();
            ContextEmpty ctx = context(path, headers);
            AtomicBoolean called = new AtomicBoolean();
            new WebEntryCacheFilter().doFilter(ctx, next -> {
                assertEquals("no-store, no-cache, must-revalidate", headers.get("Cache-Control"));
                called.set(true);
            });
            assertTrue(called.get());
        }
    }

    @Test
    void hashedAssetsAndApiKeepTheirOwnCachePolicy() throws Throwable {
        for (String path : new String[]{"/assets/index-abc123.js", "/api/sessions"}) {
            Map<String, String> headers = new HashMap<>();
            headers.put("Cache-Control", "max-age=600");
            new WebEntryCacheFilter().doFilter(context(path, headers), next -> {});
            assertEquals("max-age=600", headers.get("Cache-Control"));
        }
    }

    private ContextEmpty context(String path, Map<String, String> headers) {
        return new ContextEmpty() {
            @Override public String path() { return path; }
            @Override public void headerSet(String name, String value) { headers.put(name, value); }
        };
    }
}
