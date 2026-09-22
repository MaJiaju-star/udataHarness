package com.udata.harness.controller;

import com.udata.harness.common.domain.SessionMetadata;
import com.udata.harness.common.request.ChatRequest;
import com.udata.harness.common.request.CreateSessionRequest;
import com.udata.harness.common.request.HitlDecisionRequest;
import com.udata.harness.common.request.SessionPermissionRequest;
import com.udata.harness.common.request.SessionTitleRequest;
import com.udata.harness.service.ChatService;
import com.udata.harness.service.SessionService;
import org.noear.solon.annotation.Body;
import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Delete;
import org.noear.solon.annotation.Get;
import org.noear.solon.annotation.Header;
import org.noear.solon.annotation.Inject;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.annotation.Param;
import org.noear.solon.annotation.Post;
import org.noear.solon.annotation.Produces;
import org.noear.solon.core.handle.Result;
import org.noear.solon.core.util.MimeType;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/**
 * 会话生命周期、SSE 流式聊天和 HITL 审批的核心 HTTP 接入层。
 *
 * <p>普通 JSON 接口委托给 {@link SessionService}，模型执行与审批恢复委托给
 * {@link ChatService}。所有会话操作都以 {@code X-User-Id} 作为租户边界，Service 会再次
 * 校验 {@code sessionId} 的归属，防止跨用户读取历史或控制运行。</p>
 *
 * <p>{@code /chat/stream} 与 {@code /hitl/decide} 返回的每个字符串已经是前端可消费的
 * SSE 事件 JSON。Controller 必须保持 {@link Flux} 的惰性和非阻塞特征，不能调用
 * {@code block()}、收集为 List 或二次序列化，否则会破坏逐 Token 输出、取消和 HITL 暂停。</p>
 */
@Controller
@Mapping("/api")
public class SessionController {
    /** 管理会话元数据、历史记录、权限模式与运行取消。 */
    @Inject
    private SessionService sessionService;

    /** 执行 Harness 对话并把模型、工具和 HITL 状态转换为 SSE 事件。 */
    @Inject
    private ChatService chatService;

    /**
     * 获取前端初始化所需的运行元数据。
     *
     * @param userId 当前用户标识
     * @return 默认模型、工作区或能力状态等前端启动信息
     */
    @Get
    @Mapping("/meta")
    public Result<Map<String, Object>> meta(@Header("X-User-Id") String userId) {
        return Result.succeed(sessionService.meta(userId));
    }

    /**
     * 查询当前用户的全部会话。
     *
     * <p>返回对象中的 {@code active} 是根据内存运行注册表动态补充的瞬时状态，
     * 不是持久化事实。</p>
     *
     * @param userId 当前用户标识
     * @return 当前用户可见的会话元数据列表
     */
    @Get
    @Mapping("/sessions")
    public Result<List<SessionMetadata>> sessions(@Header("X-User-Id") String userId) {
        return Result.succeed(sessionService.list(userId));
    }

    /**
     * 创建一个新的独立会话。
     *
     * @param userId 当前用户标识，决定会话及工作区归属
     * @param request 可选标题和模型；空值由服务层使用默认值补齐
     * @return 已持久化的会话元数据，默认权限模式为 {@code standard}
     */
    @Post
    @Mapping("/sessions")
    public Result<SessionMetadata> create(@Header("X-User-Id") String userId,
                                          @Body CreateSessionRequest request) {
        return Result.succeed(sessionService.create(userId, request));
    }

    /**
     * 修改单个会话的工具权限模式。
     *
     * <p>{@code standard} 对需要确认的工具进入 HITL；{@code full} 自动批准此会话中
     * 可批准的工具调用。为避免正在执行的 Flux 前后权限不一致，运行中的会话禁止切换模式。</p>
     *
     * @param userId 当前用户标识
     * @param request 目标 sessionId 与 standard/full 权限值
     * @return 更新后的会话元数据
     */
    @Post
    @Mapping("/sessions/permission")
    public Result<SessionMetadata> updatePermission(
            @Header("X-User-Id") String userId,
            @Body SessionPermissionRequest request) {
        return Result.succeed(sessionService.updatePermission(userId, request));
    }

    /** 显式修改会话标题。 */
    @Post
    @Mapping("/sessions/title")
    public Result<SessionMetadata> updateTitle(
            @Header("X-User-Id") String userId,
            @Body SessionTitleRequest request) {
        return Result.succeed(sessionService.updateTitle(userId, request));
    }

    /**
     * 删除当前用户的会话及其持久化历史。
     *
     * @param userId 当前用户标识
     * @param sessionId 待删除会话标识
     * @return 无响应数据
     */
    @Delete
    @Mapping("/sessions")
    public Result<Void> delete(@Header("X-User-Id") String userId,
                               @Param("sessionId") String sessionId) {
        sessionService.delete(userId, sessionId);
        return Result.succeed();
    }

    /**
     * 读取会话的完整可展示消息历史。
     *
     * <p>结果包含用户、助手和工具消息，供页面刷新或重新进入会话时恢复 UI。
     * 消息转换和内部字段过滤由服务层处理。</p>
     *
     * @param userId 当前用户标识
     * @param sessionId 目标会话标识
     * @return 按时间顺序排列的消息对象
     */
    @Get
    @Mapping("/sessions/messages")
    public Result<List<Map<String, Object>>> messages(@Header("X-User-Id") String userId,
                                                       @Param("sessionId") String sessionId) {
        return Result.succeed(sessionService.messages(userId, sessionId));
    }

    /**
     * 发起或继续一次流式智能体对话。
     *
     * <p>响应媒体类型为 {@code text/event-stream;charset=UTF-8}。流可能包含文本增量、
     * 思考内容、工具开始/结束、HITL 请求、错误和完成事件；前端应按事件 type 分派，
     * 不能假设每个事件都是助手文本。</p>
     *
     * @param userId 当前用户标识
     * @param request 会话标识、用户提示词及可选模型
     * @return 随 Harness 执行实时发射的 SSE 事件流
     */
    @Post
    @Mapping("/chat/stream")
    @Produces(MimeType.TEXT_EVENT_STREAM_UTF8_VALUE)
    public Flux<String> chat(@Header("X-User-Id") String userId,
                             @Body ChatRequest request) {
        return chatService.chat(userId, request);
    }

    /**
     * 请求取消当前会话正在进行的模型或工具执行。
     *
     * <p>取消是协作式的：返回 true 表示找到了活动运行并发出了取消信号，
     * 不代表外部进程已在该 HTTP 响应产生前完全退出。</p>
     *
     * @param userId 当前用户标识
     * @param sessionId 目标会话标识
     * @return 是否找到并成功标记活动运行
     */
    @Post
    @Mapping("/chat/cancel")
    public Result<Boolean> cancel(@Header("X-User-Id") String userId,
                                  @Param("sessionId") String sessionId) {
        return Result.succeed(sessionService.cancel(userId, sessionId));
    }

    /**
     * 提交 HITL 决策并从暂停点继续输出。
     *
     * <p>请求可批准、跳过、拒绝或修改工具参数；{@code alwaysAllow} 的持久化范围由
     * ChatService/Harness 权限规则决定。该接口本身仍返回 SSE，因为审批后模型可能继续
     * 产生文本、调用更多工具，或再次进入新的审批点。</p>
     *
     * @param userId 当前用户标识
     * @param request 会话标识、审批动作、备注及可选修改参数
     * @return 审批恢复后的连续 SSE 事件流
     */
    @Post
    @Mapping("/hitl/decide")
    @Produces(MimeType.TEXT_EVENT_STREAM_UTF8_VALUE)
    public Flux<String> hitlDecide(@Header("X-User-Id") String userId,
                                    @Body HitlDecisionRequest request) {
        return chatService.decide(userId, request);
    }

}
