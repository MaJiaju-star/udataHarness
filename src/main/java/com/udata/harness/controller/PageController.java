package com.udata.harness.controller;

import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Get;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.core.handle.Context;

/**
 * React 单页应用的服务端入口。
 *
 * <p>Vite 构建产物位于 {@code src/main/resources/static}。访问根路径时内部转发到
 * {@code index.html}，浏览器地址保持不变，静态资源随后由 Solon 的静态文件处理器提供。</p>
 */
@Controller
public class PageController {
    /**
     * 将应用根路径转发到 React 入口页。
     *
     * @param context 当前 Solon HTTP 上下文
     * @throws Throwable 转发过程无法完成时向 Solon 异常处理链传播
     */
    @Get
    @Mapping("/")
    public void index(Context context) throws Throwable {
        context.forward("/index.html");
    }
}
