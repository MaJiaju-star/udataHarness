package com.udata.harness.controller;

import com.udata.harness.notification.NotificationService;
import org.noear.solon.annotation.Body;
import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Delete;
import org.noear.solon.annotation.Get;
import org.noear.solon.annotation.Header;
import org.noear.solon.annotation.Inject;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.annotation.Param;
import org.noear.solon.annotation.Post;
import org.noear.solon.core.handle.Result;

import java.util.Map;
import java.util.function.Supplier;

/**
 * 通知配置与发送记录接口（{@code /api/notifications}）。
 *
 * <p>数据按 {@code X-User-Id} 归属隔离，由 {@link NotificationService} 持久化；
 * 接口仅负责配置维护与入队，HTTP 发送在后台异步执行，失败不自动重试，
 * 结果通过 {@code GET /api/notifications} 的 {@code records} 查询。</p>
 *
 * <p>列表中的 URL、请求头值已脱敏，不能依赖列表响应还原原始值；
 * 保存时 URL 留空表示沿用原值，请求头值留空且未标记 {@code clearValue} 时同样沿用原值。</p>
 */
@Controller
@Mapping("/api/notifications")
public class NotificationController {

    /**
     * 负责配置校验、持久化、入队与后台投递。
     */
    @Inject
    private NotificationService notifications;

    /**
     * 查询当前用户的通知配置、发送记录与可用模板变量。
     *
     * @param user 当前调用用户（{@code X-User-Id}）
     * @return 含 {@code configs}（已脱敏）、{@code records}（新记录在前）、{@code variables}
     */
    @Get
    @Mapping("")
    public Result<Map<String, Object>> list(@Header("X-User-Id") String user) {
        return action(() -> notifications.list(user));
    }

    /**
     * 新建或更新通知配置；携带 {@code id} 更新，否则新建，每位用户最多 20 条。
     *
     * @param user 当前调用用户
     * @param input 配置内容；URL/请求头留空表示沿用原值
     * @return 保存后的配置 id
     */
    @Post
    @Mapping("")
    public Result<String> save(@Header("X-User-Id") String user,
                               @Body Map<String, Object> input) {
        return action(() -> notifications.save(user, input));
    }

    /**
     * 删除通知配置；已产生的发送记录保留。
     *
     * @param user 当前调用用户
     * @param id 配置 id
     * @return 无响应数据
     */
    @Delete
    @Mapping("")
    public Result<Void> remove(@Header("X-User-Id") String user,
                               @Param("id") String id) {
        return action(() -> {
            notifications.remove(user, id);
            return null;
        });
    }

    /**
     * 启用或停用通知配置。停用不取消已入队的记录；
     * 自动发送前会再次检查启用状态，已停用则记为 {@code skipped}。
     *
     * @param user 当前调用用户
     * @param id 配置 id
     * @param enabled 是否启用
     * @return 无响应数据
     */
    @Post
    @Mapping("/state")
    public Result<Void> state(@Header("X-User-Id") String user,
                              @Param("id") String id,
                              @Param("enabled") boolean enabled) {
        return action(() -> {
            notifications.enabled(user, id, enabled);
            return null;
        });
    }

    /**
     * 立即入队一次测试发送，使用示例变量；配置无需先保存。
     *
     * @param user 当前调用用户
     * @param input 待测配置内容；携带已有 {@code id} 时沿用其保存的 URL 等原值
     * @return 新发送记录 id，结果见 {@code records}
     */
    @Post
    @Mapping("/test")
    public Result<String> test(@Header("X-User-Id") String user,
                               @Body Map<String, Object> input) {
        return action(() -> notifications.test(user, input));
    }

    /**
     * 手动重发一条历史发送记录，沿用原记录的配置与变量。
     *
     * @param user 当前调用用户
     * @param id 发送记录 id；{@code queued}/{@code sending} 状态不可重发
     * @return 新发送记录 id
     */
    @Post
    @Mapping("/resend")
    public Result<String> resend(@Header("X-User-Id") String user,
                                 @Param("id") String id) {
        return action(() -> notifications.resend(user, id));
    }

    /**
     * 统一错误映射：参数或校验不合法返回 400，
     * 数据文件读写等内部失败返回 500。
     *
     * @param supplier 实际业务逻辑
     * @param <T> 响应数据类型
     * @return 包装后的处理结果
     */
    private <T> Result<T> action(Supplier<T> supplier) {
        try {
            return Result.succeed(supplier.get());
        } catch (IllegalArgumentException e) {
            return Result.failure(400, e.getMessage());
        } catch (IllegalStateException e) {
            return Result.failure(500, "通知配置处理失败，请检查数据文件权限");
        }
    }
}
