package com.udata.harness.controller;

import com.udata.harness.service.ModelConfigurationService;
import com.udata.harness.service.UserHarnessEngineService;
import org.noear.solon.annotation.*;
import org.noear.solon.core.handle.Result;
import java.util.LinkedHashMap;
import java.util.Map;

/** Personal models share the application's existing trusted X-User-Id boundary. */
@Controller
@Mapping("/api/models")
public class ModelController {
    @Inject private ModelConfigurationService models;
    @Inject private UserHarnessEngineService engines;
    @Get @Mapping("")
    public Result<Map<String, Object>> list(@Header("X-User-Id") String user) {
        Map<String, Object> result = models.list(user);
        result.put("pendingApply", engines.modelsPending(user));
        return Result.succeed(result);
    }
    @Post @Mapping("")
    public Result<Map<String, Object>> save(@Header("X-User-Id") String user, @Body Map<String, Object> body) {
        return action(() -> { models.save(user, body); return models.list(user); });
    }
    @Delete @Mapping("")
    public Result<Map<String, Object>> remove(@Header("X-User-Id") String user, @Param("id") String id) {
        return action(() -> { models.remove(user, id); return models.list(user); });
    }
    @Post @Mapping("/defaults")
    public Result<Map<String, Object>> defaults(@Header("X-User-Id") String user, @Body Map<String, Object> body) {
        return action(() -> { models.defaults(user, body); return models.list(user); });
    }
    @Post @Mapping("/test")
    public Result<Map<String, Object>> test(@Header("X-User-Id") String user, @Body Map<String, Object> body) {
        return action(() -> models.testConnection(user, body));
    }
    private Result<Map<String, Object>> action(java.util.function.Supplier<Map<String, Object>> action) {
        try { return Result.succeed(action.get()); }
        catch (IllegalArgumentException e) { return Result.failure(400, e.getMessage()); }
        catch (IllegalStateException e) { return Result.failure(500, "模型配置处理失败，请检查配置与密钥文件权限"); }
    }
}
