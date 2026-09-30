package com.udata.harness.common.context;

import org.noear.solon.ai.chat.*;
import org.noear.solon.ai.chat.dialect.ChatDialect;
import org.noear.solon.ai.chat.prompt.Prompt;

/**
 * 摘要模型的观测代理：把摘要调用委托给底层模型，同时记录其用量。
 *
 * <p>直接复用底层模型的配置与方言（{@link #getConfig()}、{@link #getDialect()} 等全部委托），
 * 仅在 {@link #prompt} 中追加一个观测拦截器；因此<b>不会修改共享模型实例本身</b>，
 * 同一模型仍可被主推理正常使用。</p>
 *
 * <p>观测器以 {@code Integer.MIN_VALUE} 优先级加入，保证它在其他拦截器之前记录，
 * 不受后续拦截器改写请求影响；{@code purpose} 固定为 {@code summary}，
 * 与主推理的 {@code reason} 在指标中分桶。</p>
 */
public final class ObservedSummaryModel extends ChatModel {
    /**
     * 真实的模型实现，所有请求与配置都委托给它。
     */
    private final ChatModel delegate;

    /**
     * 摘要用量的观测拦截器，仅在本次 prompt 上生效。
     */
    private final ModelCallObserver observer;

    /**
     * 创建不绑定 runId 的摘要观测代理。
     *
     * @param model   真实模型
     * @param metrics 目标指标
     */
    public ObservedSummaryModel(ChatModel model, ContextMetrics metrics) {
        this(model, metrics, "");
    }

    /**
     * 创建绑定指定 runId 的摘要观测代理。
     *
     * <p>{@link ChatModel} 父类需要一个可用的配置壳，但实际暴露的配置永远来自 {@code delegate}，
     * 因此壳只复制必要的连接信息，不继承共享模型的可变状态。</p>
     *
     * @param model   真实模型
     * @param metrics 目标指标
     * @param runId   归属的运行标识，用于把摘要调用关联回触发它的那次推理
     */
    public ObservedSummaryModel(ChatModel model, ContextMetrics metrics, String runId) {
        super(shell(model)); delegate = model; observer = new ModelCallObserver(metrics, "summary", runId);
    }

    /**
     * 构造供父类使用的配置壳，仅复制连接所需字段。
     *
     * @param model 真实模型
     * @return 仅含 apiUrl、model、provider、standard 的配置
     */
    private static ChatConfig shell(ChatModel model) {
        ChatConfig config = new ChatConfig();
        config.setApiUrl(model.getConfig().getApiUrl()); config.setModel(model.getModel());
        config.setProvider(model.getProvider()); config.setStandard(model.getStandard());
        return config;
    }

    /** {@inheritDoc} */
    @Override public ChatConfigReadonly getConfig() { return delegate.getConfig(); }
    /** {@inheritDoc} */
    @Override public ChatDialect getDialect() { return delegate.getDialect(); }
    /** {@inheritDoc} */
    @Override public String getModel() { return delegate.getModel(); }
    /** {@inheritDoc} */
    @Override public String getProvider() { return delegate.getProvider(); }
    /** {@inheritDoc} */
    @Override public String getStandard() { return delegate.getStandard(); }
    /** {@inheritDoc} */
    @Override public String getStandardOrProvider() { return delegate.getStandardOrProvider(); }
    /** {@inheritDoc} */
    @Override public String getNameOrModel() { return delegate.getNameOrModel(); }

    /**
     * 构造摘要请求，并挂上用量观测器。
     *
     * @param prompt 摘要提示词
     * @return 底层模型的请求描述，已添加摘要观测拦截器
     */
    @Override public ChatRequestDesc prompt(Prompt prompt) {
        return delegate.prompt(prompt).options(o -> o.interceptorAdd(Integer.MIN_VALUE, observer));
    }

    /**
     * {@inheritDoc}
     *
     * <p>固定返回类名，避免代理身份在日志中被误认为底层模型的真实名称。</p>
     */
    @Override public String toString() { return "ObservedSummaryModel"; }
}
