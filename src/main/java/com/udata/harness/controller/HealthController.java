package com.udata.harness.controller;

import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Get;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.core.handle.Result;
import java.util.LinkedHashMap;
import java.util.Map;

@Controller
public class HealthController {
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
