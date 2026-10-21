package com.udata.harness.controller;

import com.udata.harness.service.ScheduledTaskService;
import org.noear.solon.annotation.*;
import org.noear.solon.core.handle.Result;
import java.util.Map;

@Controller
@Mapping("/api/automations")
public class AutomationController {
    @Inject private ScheduledTaskService tasks;
    @Get @Mapping("")
    public Result<Map<String, Object>> list(@Header("X-User-Id") String user, @Param("sessionId") String sessionId) { return action(() -> tasks.list(user, sessionId)); }
    @Post @Mapping("")
    public Result<String> save(@Header("X-User-Id") String user, @Body Map<String, Object> body) { return action(() -> tasks.save(user, body)); }
    @Post @Mapping("/state")
    public Result<Void> state(@Header("X-User-Id") String user, @Param("id") String id, @Param("enabled") boolean enabled) { return action(() -> { tasks.enabled(user, id, enabled); return null; }); }
    @Post @Mapping("/run")
    public Result<Void> run(@Header("X-User-Id") String user, @Param("id") String id) { return action(() -> { tasks.runNow(user, id); return null; }); }
    @Post @Mapping("/stop")
    public Result<Void> stop(@Header("X-User-Id") String user, @Param("runId") String runId) { return action(() -> { tasks.stop(user, runId); return null; }); }
    @Delete @Mapping("")
    public Result<Void> remove(@Header("X-User-Id") String user, @Param("id") String id) { return action(() -> { tasks.remove(user, id); return null; }); }
    private <T> Result<T> action(java.util.function.Supplier<T> action) {
        try { return Result.succeed(action.get()); }
        catch (IllegalArgumentException e) { return Result.failure(400, e.getMessage()); }
        catch (IllegalStateException e) { return Result.failure(500, "定时任务处理失败，请检查配置、模型和文件权限"); }
    }
}
