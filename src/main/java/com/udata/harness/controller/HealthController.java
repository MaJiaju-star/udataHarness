package com.udata.harness.controller;

import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Get;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.core.handle.Result;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 服务健康与客户端协议自描述接口。
 *
 * <p>不需要 {@code X-User-Id}，也不访问工作区与会话数据，因此可用于探活、
 * 网关路由检查和客户端握手前的协议探测。</p>
 *
 * <p>返回的 {@code workspaceHeader} 告知客户端应以哪个请求头声明目标工作区；
 * 该请求头只是目录路由键，不构成身份认证。</p>
 */
@Controller
public class HealthController {
    /**
     * 返回服务名称与当前客户端协议版本。
     *
     * @return 含 {@code name}、{@code protocolVersion}、{@code workspaceHeader} 的成功响应
     */
    @Get
    @Mapping("/api/health")
    public Result<Map<String, Object>> health() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", "UData Harness");
        data.put("protocolVersion", 1);
        data.put("workspaceHeader", "X-Workspace-Id");
        return Result.succeed(data);
    }
}
