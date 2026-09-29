package com.udata.harness.config;

import org.noear.solon.annotation.Component;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.Filter;
import org.noear.solon.core.handle.FilterChain;

/** Keep the HTML entry fresh; Vite's hashed assets can retain their normal cache. */
@Component(index = -1000)
public class WebEntryCacheFilter implements Filter {
    @Override
    public void doFilter(Context ctx, FilterChain chain) throws Throwable {
        if ("/".equals(ctx.path()) || "/index.html".equals(ctx.path())) {
            ctx.headerSet("Cache-Control", "no-store, no-cache, must-revalidate");
        }
        chain.doFilter(ctx);
    }
}
