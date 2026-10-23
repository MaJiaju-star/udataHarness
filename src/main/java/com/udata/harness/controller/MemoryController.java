package com.udata.harness.controller;

import com.udata.harness.memory.MemoryService;
import org.noear.solon.annotation.*;
import org.noear.solon.core.handle.Result;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 记忆管理接口（/api/memory）。
 * <p>
 * 提供用户记忆的查询、保存、状态更新及记忆开关/预算设置；
 * 用户身份统一从请求头 X-User-Id 获取。
 */
@Controller
@Mapping("/api/memory")
public class MemoryController {

    @Inject
    private MemoryService memories;

    /**
     * 查询当前用户在指定工作区下的记忆列表。
     *
     * @param user 用户 ID（请求头 X-User-Id）
     * @param ws   工作区 ID
     * @return 记忆列表及相关元信息
     */
    @Get
    @Mapping("")
    public Result<Map<String, Object>> list(@Header("X-User-Id") String user,
                                            @Param("workspaceId") String ws) {
        return action(() -> memories.list(user, ws));
    }

    /**
     * 设置当前用户的记忆开关与预算。
     *
     * @param user  用户 ID（请求头 X-User-Id）
     * @param input 请求体，需包含 {@code enabled}(Boolean) 与 {@code budget}(Number)
     * @return 无返回内容；参数缺失时返回 400
     */
    @Post
    @Mapping("/settings")
    public Result<Void> settings(@Header("X-User-Id") String user,
                                 @Body Map<String, Object> input) {
        return action(() -> {
            if (!(input.get("enabled") instanceof Boolean) || !(input.get("budget") instanceof Number)) {
                throw new IllegalArgumentException("请提供 enabled 和 budget");
            }
            memories.settings(user, (Boolean) input.get("enabled"),
                    ((Number) input.get("budget")).intValue());
            return null;
        });
    }

    /**
     * 保存一条记忆（来源标记为 manual）。
     *
     * @param user  用户 ID（请求头 X-User-Id）
     * @param ws    工作区 ID
     * @param input 记忆内容
     * @return 保存后的记忆信息
     */
    @Post
    @Mapping("")
    public Result<Map<String, Object>> save(@Header("X-User-Id") String user,
                                            @Param("workspaceId") String ws,
                                            @Body Map<String, Object> input) {
        return action(() -> memories.save(user, ws, input, "manual"));
    }

    /**
     * 更新记忆的状态（如 active / archived）。
     *
     * @param user  用户 ID（请求头 X-User-Id）
     * @param ws    工作区 ID
     * @param input 请求体，需包含 {@code id}、{@code revision}(Number)、{@code status}
     * @return 无返回内容；缺少版本号时返回 400
     */
    @Post
    @Mapping("/state")
    public Result<Void> state(@Header("X-User-Id") String user,
                              @Param("workspaceId") String ws,
                              @Body Map<String, Object> input) {
        return action(() -> {
            if (!(input.get("revision") instanceof Number)) {
                throw new IllegalArgumentException("缺少记忆版本");
            }
            memories.state(user, ws,
                    String.valueOf(input.get("id")),
                    ((Number) input.get("revision")).longValue(),
                    String.valueOf(input.get("status")));
            return null;
        });
    }

    /**
     * 统一执行业务逻辑并包装为 Result：
     * IllegalArgumentException → 400，IllegalStateException → 500。
     *
     * @param work 业务逻辑
     * @param <T>  返回值类型
     * @return 包装后的执行结果
     */
    private <T> Result<T> action(Supplier<T> work) {
        try {
            return Result.succeed(work.get());
        } catch (IllegalArgumentException e) {
            return Result.failure(400, e.getMessage());
        } catch (IllegalStateException e) {
            return Result.failure(500, e.getMessage());
        }
    }
}
