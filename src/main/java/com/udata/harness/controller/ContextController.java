package com.udata.harness.controller;

import com.udata.harness.repository.ModelContextAgentSession;
import com.udata.harness.repository.SessionRepository;
import org.noear.solon.annotation.*;
import org.noear.solon.core.handle.Result;

import java.util.List;
import java.util.Map;

/**
 * 上下文管理证据链的只读 HTTP 接入层。
 *
 * <p>面向排障与回归验证，暴露当前会话的上下文报告、历史证据检索和有界恢复，
 * 使被投影裁剪的原始工具结果可按需取回。三个接口都不写盘、不驱动模型推理，
 * 因此不会改变模型视图和已生成的摘要。</p>
 *
 * <p>所有接口都以 {@code X-User-Id} 作为租户边界：归属校验委托给
 * {@link SessionRepository#getSession}，即使 {@code sessionId} 格式合法，
 * 不属于该用户的会话同样会失败，从入口阻断跨用户读取。</p>
 *
 * <p>恢复出的内容是历史证据及其版本哈希，不代表与当前工作区一致；调用方须自行
 * 核对当前文件与测试结果。</p>
 */
@Controller
@Mapping("/api/sessions/context")
public class ContextController {
    /**
     * 按会话标识加载主会话上下文，同时作为租户归属校验入口。
     */
    @Inject private SessionRepository sessions;

    /**
     * 查询当前会话的上下文报告。
     *
     * <p>报告包含代次、归档消息数、活跃模型消息数、结构化任务状态与指标四部分，
     * 用于观测治理后的上下文体量。指标保留供应商回报的 prompt/completion/缓存 Token
     * 原始口径，不做跨供应商累加，也不推断缓存命中率。</p>
     *
     * @param userId 当前用户标识，同时作为会话归属校验依据
     * @param sessionId 目标会话标识
     * @return 含 {@code generation}、{@code archiveMessages}、{@code activeMessages}、
     *         {@code taskState}、{@code metrics} 的数据对象
     * @throws IllegalArgumentException 会话不存在或不属于该用户时抛出
     */
    @Get
    @Mapping("")
    public Result<Map<String, Object>> report(@Header("X-User-Id") String userId, @Param("sessionId") String sessionId) {
        return Result.succeed(session(userId, sessionId).contextReport());
    }

    /**
     * 按精确关键词检索当前会话已归档的工具证据。
     *
     * <p>检索同时覆盖工具名称、规范化的调用参数与原始正文，所有关键词都必须命中；
     * 命中结果只返回预览片段与定位信息，取回完整正文需再调用 {@link #restore}。</p>
     *
     * @param userId 当前用户标识，同时作为会话归属校验依据
     * @param sessionId 目标会话标识
     * @param query 检索关键词，以空白切分后需全部命中，长度 1~256 字符
     * @param tool 可选工具名过滤，为空表示不限工具
     * @return 按创建时间倒序的证据元数据列表，最多十条
     * @throws IllegalArgumentException 会话不存在、不属于该用户或关键词非法时抛出
     */
    @Get
    @Mapping("/search")
    public Result<List<Map<String, Object>>> search(@Header("X-User-Id") String userId,
            @Param("sessionId") String sessionId, @Param("query") String query,
            @Param(value = "tool", required = false) String tool) {
        return Result.succeed(session(userId, sessionId).contextArtifacts().search(query, tool, 10));
    }

    /**
     * 从历史证据中恢复有界片段。
     *
     * <p>偏移量与长度均以 UTF-16 字符为单位，响应会给出续读用的 {@code nextOffset}，
     * 调用方据此分页读取，避免一次拉取整段历史重新占满上下文。</p>
     *
     * @param userId 当前用户标识，同时作为会话归属校验依据
     * @param sessionId 目标会话标识
     * @param id 证据标识，为 64 位小写十六进制内容哈希
     * @param offset 起始偏移量，单位为 UTF-16 字符，默认 0
     * @param maxChars 单次最大返回字符数，默认 4000，上限 16000
     * @return 含证据正文片段、版本哈希与续读偏移量的数据对象
     * @throws IllegalArgumentException 会话不存在、不属于该用户、证据标识非法或恢复范围越界时抛出
     */
    @Get
    @Mapping("/restore")
    public Result<Map<String, Object>> restore(@Header("X-User-Id") String userId,
            @Param("sessionId") String sessionId, @Param("id") String id,
            @Param(value = "offset", defaultValue = "0") int offset,
            @Param(value = "maxChars", defaultValue = "4000") int maxChars) {
        return Result.succeed(session(userId, sessionId).contextArtifacts().restore(id, offset, maxChars));
    }

    /**
     * 按用户归属加载上下文会话。
     *
     * @param userId 当前用户标识
     * @param id 目标会话标识
     * @return 承载上下文报告、证据归档与任务状态的模型上下文会话
     * @throws IllegalArgumentException 会话不存在或不属于该用户时抛出
     */
    private ModelContextAgentSession session(String userId, String id) {
        //1. 复用 SessionRepository 的归属校验，保证三个只读接口的租户边界一致
        return (ModelContextAgentSession) sessions.getSession(userId, id);
    }
}
