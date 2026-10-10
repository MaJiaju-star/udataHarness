package com.udata.harness.config;

import org.noear.solon.annotation.Component;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.Filter;
import org.noear.solon.core.handle.FilterChain;

/**
 * 禁用 HTML 入口页的浏览器缓存，避免升级后仍加载旧版界面。
 *
 * <p>Vite 构建产物中只有 {@code index.html} 引用带内容哈希的 JS/CSS。若入口页被缓存，
 * 用户会一直请求旧哈希资源，即使后端已重新打包也难以感知更新（IDEA 插件 0.1.7
 * 的「页面更新缓存修复」即针对该问题）。因此仅对入口页强制协商与校验：</p>
 *
 * <ul>
 *   <li>{@code no-store}：禁止任何缓存副本落盘；</li>
 *   <li>{@code no-cache}：允许缓存但每次使用前必须回源校验；</li>
 *   <li>{@code must-revalidate}：过期后必须校验，不得使用陈旧副本。</li>
 * </ul>
 *
 * <p>带哈希的静态资源不在此过滤范围内，仍沿用正常缓存策略，命中时无需回源。</p>
 *
 * <p>{@code index = -1000} 让该过滤器以较高优先级尽早执行，确保响应头在静态文件
 * 处理器写出响应体之前就已设置。</p>
 */
@Component(index = -1000)
public class WebEntryCacheFilter implements Filter {
    /**
     * HTML 入口页路径：根路径会在 {@code PageController} 内部转发到 {@code /index.html}，
     * 两者都需要禁用缓存。
     */
    private static final String ENTRY = "/index.html";

    /**
     * 对入口页设置禁用缓存的响应头，其余请求原样放行。
     *
     * @param ctx   当前 Solon HTTP 上下文，用于读取请求路径并写入响应头
     * @param chain 过滤器链，处理完成后继续向后传递请求
     * @throws Throwable 后续处理器抛出异常时原样向上传播
     */
    @Override
    public void doFilter(Context ctx, FilterChain chain) throws Throwable {
        String path = ctx.path();
        if ("/".equals(path) || ENTRY.equals(path)) {
            ctx.headerSet("Cache-Control", "no-store, no-cache, must-revalidate");
        }
        chain.doFilter(ctx);
    }
}
