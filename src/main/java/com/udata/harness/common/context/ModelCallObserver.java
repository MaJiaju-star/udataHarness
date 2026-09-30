package com.udata.harness.common.context;

import com.udata.harness.repository.ModelContextAgentSession;
import org.noear.solon.ai.agent.react.*;
import org.noear.solon.ai.chat.*;
import org.noear.solon.ai.chat.event.*;
import org.noear.solon.ai.chat.interceptor.*;
import org.noear.solon.ai.harness.agent.AgentDefinition;
import reactor.core.publisher.Flux;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 模型调用用量的请求级拦截器：为每次推理或摘要调用生成一条独立记录。
 *
 * <p>记录按 {@code callId} 与 {@code runId} 关联：{@code purpose} 区分主推理（{@code reason}）
 * 与摘要调用（{@code summary}），{@code runId} 则把摘要调用归到触发它的那次运行上，
 * 使报告能按供应商/模型/用途分桶。</p>
 *
 * <p>只记录可观测的事实：拿不到用量时保留为未知，<b>不按零成本估算</b>；也从不记录提示词正文，
 * 避免把会话内容写进指标文件。</p>
 *
 * <p>观测器有两种绑定方式：在构造时绑定 {@link ContextMetrics}（摘要策略等自建实例使用），
 * 或由 {@link #onReasonStart} 把指标根目录写进 toolContext（注册到主 Agent 的共享观测器使用，
 * 每次请求再懒加载）。两种方式都不修改共享模型配置。</p>
 */
public class ModelCallObserver implements ReActInterceptor {
    /**
     * 指标根目录的 toolContext 键；请求级共享观测器靠它定位会话指标。
     */
    private static final String ROOT = "_udata_metrics_root";

    /**
     * 当前运行标识的 toolContext 键，用于把记录归到触发它的 run。
     */
    private static final String RUN = "_udata_metrics_run";

    /**
     * 构造时绑定的指标；为 {@code null} 时退化为从 toolContext 懒加载。
     */
    private final ContextMetrics bound;

    /**
     * 调用用途，取值为 {@code reason}（主推理）或 {@code summary}（摘要）。
     */
    private final String purpose;

    /**
     * 与 {@link #bound} 配套的固定 runId；空串表示随每个请求从 toolContext 取。
     */
    private final String boundRun;

    /**
     * 创建不带指标绑定的观测器，用途固定为主推理。
     *
     * <p>运行时会按请求从 toolContext 解析指标根目录，因此可以注册为共享拦截器。</p>
     */
    public ModelCallObserver() { this(null, "reason"); }

    /**
     * 创建请求级观测器，runId 留空。
     *
     * @param metrics 目标指标；为 {@code null} 时从 toolContext 解析
     * @param purpose 调用用途，{@code reason} 或 {@code summary}
     */
    public ModelCallObserver(ContextMetrics metrics, String purpose) { this(metrics, purpose, ""); }

    /**
     * 创建完全绑定的观测器。
     *
     * @param metrics 目标指标；非空时不再从 toolContext 解析根目录
     * @param purpose 调用用途，{@code reason} 或 {@code summary}
     * @param runId   固定归属的运行标识；非空时优先于 toolContext 中的当前 run
     */
    public ModelCallObserver(ContextMetrics metrics, String purpose, String runId) { bound = metrics; this.purpose = purpose; boundRun = runId; }

    /**
     * 推理开始前把指标根目录与当前 runId 写入 toolContext，供后续请求级记录使用。
     *
     * <p>只对主 Agent 的会话注入，子 Agent 调用不参与本次用量统计。</p>
     *
     * @param trace        当前 ReAct 执行链路
     * @param systemPrompt 系统提示词构建器，本方法不修改其内容
     */
    @Override public void onReasonStart(ReActTrace trace, StringBuilder systemPrompt) {
        //1. 仅主 Agent 会话注入：把指标根目录与当前 runId 传给后续可能脱离 trace 的模型调用。
        if (trace.getSession() instanceof ModelContextAgentSession && AgentDefinition.AGENT_MAIN.equals(trace.getAgentName())) {
            trace.getOptions().getToolContext().put(ROOT, ((ModelContextAgentSession) trace.getSession()).contextMetrics().root().toString());
            trace.getOptions().getToolContext().put(RUN, trace.getRunId());
        }
    }

    /**
     * 解析本次请求应写入的指标：优先使用构造时绑定的实例，否则按 toolContext 中的根目录新建。
     *
     * @param req 当前模型请求
     * @return 目标指标；既未绑定也无根目录时返回 {@code null}，表示本次调用不记录
     */
    private ContextMetrics metrics(ChatRequest req) {
        Object root = req.getOptions().toolContext().get(ROOT);
        return bound != null ? bound : root instanceof String ? new ContextMetrics(Paths.get((String) root)) : null;
    }

    /**
     * 写入一条调用记录。
     *
     * <p>模型名优先取响应回报的实际模型，取不到时回退到请求配置；用量直接透传供应商回报值，
     * {@code null} 表示未知，不会被当成零用量。</p>
     *
     * @param metrics  目标指标，为 {@code null} 时静默跳过
     * @param req      当前模型请求
     * @param id       本次调用的唯一 {@code callId}
     * @param start    开始时间戳，用于计算耗时
     * @param response 模型响应；失败或未完成时为 {@code null}
     * @param outcome  结果口径：{@code success}、{@code incomplete}、{@code failed} 或 {@code cancelled}
     */
    private void record(ContextMetrics metrics, ChatRequest req, String id, long start, ChatResponse response, String outcome) {
        //1. 没有可写的指标时直接跳过，不因观测失败影响正常推理链路。
        if (metrics == null) return;
        metrics.modelCall(id, purpose, req.getConfig().getProvider(),
                response != null && response.getModel() != null ? response.getModel() : req.getConfig().getModel(),
                bound != null ? boundRun : String.valueOf(req.getOptions().toolContext().getOrDefault(RUN, "")),
                System.currentTimeMillis() - start, outcome, response == null ? null : response.getUsage());
    }

    /**
     * 记录一次非流式模型调用。
     *
     * @param req   当前模型请求
     * @param chain 后续拦截链
     * @return 模型响应
     * @throws IOException 调用链抛出的 IO 异常，记录为 {@code failed} 后原样向上传播
     */
    @Override public ChatResponse interceptCall(ChatRequest req, CallChain chain) throws IOException {
        //1. 先解析指标并生成唯一 callId，保证成功与异常两条路径都能落到同一标识上。
        ContextMetrics metrics = metrics(req);
        long start = System.currentTimeMillis(); String id = UUID.randomUUID().toString();
        try {
            //2. 正常返回：响应为空视为 incomplete，否则记 success。
            ChatResponse response = chain.doIntercept(req);
            record(metrics, req, id, start, response, response == null ? "incomplete" : "success");
            return response;
        } catch (IOException | RuntimeException e) {
            //3. 异常路径先记 failed 再原样抛出，不吞异常、也不改写错误语义。
            record(metrics, req, id, start, null, "failed"); throw e;
        }
    }

    /**
     * 记录一次流式模型调用，在流结束、出错或被取消时落一条记录。
     *
     * <p>用 {@code Flux.defer} 推迟到订阅时才解析指标与起始时间，因此每条订阅都能拿到自己的
     * {@code callId}；{@code doFinally} 覆盖正常完成、错误与取消三种终态。</p>
     *
     * @param req   当前模型请求
     * @param chain 后续流式拦截链
     * @return 包装后的流；每条订阅产生一条独立记录
     */
    @Override public Flux<ChatEvent> interceptStream(ChatRequest req, StreamChain chain) {
        return Flux.defer(() -> {
            ContextMetrics metrics = metrics(req);
            long start = System.currentTimeMillis(); String id = UUID.randomUUID().toString();
            AtomicReference<ChatResponse> response = new AtomicReference<>();
            AtomicBoolean ended = new AtomicBoolean();
            return Flux.defer(() -> chain.doIntercept(req)).doOnNext(event -> {
                //1. 只捕获响应结束事件，用于拿到完整响应（含供应商回报的用量）。
                if (event.getType() == ChatEventType.RESPONSE_END) { response.set(event.getResponse()); ended.set(true); }
            }).doFinally(signal -> record(metrics, req, id, start, response.get(),
                    //2. 按终态信号映射结果口径：取消、异常先于“未收到结束事件”判定。
                    "cancel".equals(signal.toString()) ? "cancelled" : "onError".equals(signal.toString()) ? "failed"
                            : ended.get() ? "success" : "incomplete"));
        });
    }
}
