package com.udata.harness.common.context;

/**
 * 本地侧、与模型无关的工具结果投影（截断/预览/归档）限额配置，不可变。
 *
 * <p>这些值仅控制本地如何对工具结果进行截断、预览与归档；
 * 与具体模型无关，也不依赖任何厂商（provider）。
 * 所有限额的单位均为字符。</p>
 *
 * <p>实例在构造时校验：每个限额必须为正数，
 * 且 {@link #previewChars} 必须严格小于 {@link #singleResultChars}。</p>
 */
public final class ContextManagementOptions {
    /**
     * 单个工具结果可内联保留在发送历史中的最大字符数；
     * 超过该长度的结果将被投影为归档件。
     */
    public final int singleResultChars;

    /**
     * 一批工具结果允许的总字符数上限；超出后触发批级截断/归档。
     */
    public final int batchResultChars;

    /**
     * 与归档件一同存储的预览，以及内联放入发送历史的预览，
     * 二者共用的最大字符数。
     */
    public final int previewChars;

    /**
     * {@code context_restore} 工具将归档件恢复到会话中时，单次最多可返回的字符数。
     */
    public final int restoreMaxChars;

    /**
     * 在应用投影之前，{@code read} 工具结果中被视为在发送历史里直接可见的最大字符数。
     */
    public final int readVisibleChars;

    /**
     * 创建配置，并自动推导 {@link #readVisibleChars}
     * （取 {@code min(singleResultChars, 6000)}）。
     *
     * @param singleResultChars 单个内联工具结果的限额，必须 &gt; 0
     * @param batchResultChars  一批工具结果的总限额，必须 &gt; 0
     * @param previewChars      存储预览的限额，必须 &gt; 0 且 &lt; {@code singleResultChars}
     * @param restoreMaxChars   上下文恢复工具的限额，必须 &gt; 0
     * @throws IllegalArgumentException 任一限额非法时抛出
     */
    public ContextManagementOptions(int singleResultChars, int batchResultChars,
                                    int previewChars, int restoreMaxChars) {
        this(singleResultChars, batchResultChars, previewChars, restoreMaxChars,
                Math.min(singleResultChars, 6000));
    }

    /**
     * 创建配置，显式指定 {@link #readVisibleChars}。
     *
     * @param singleResultChars 单个内联工具结果的限额，必须 &gt; 0
     * @param batchResultChars  一批工具结果的总限额，必须 &gt; 0
     * @param previewChars      存储预览的限额，必须 &gt; 0 且 &lt; {@code singleResultChars}
     * @param restoreMaxChars   上下文恢复工具的限额，必须 &gt; 0
     * @param readVisibleChars  read 结果直接可见部分的限额，必须 &gt; 0
     * @throws IllegalArgumentException 任一限额非法时抛出
     */
    public ContextManagementOptions(int singleResultChars, int batchResultChars,
                                    int previewChars, int restoreMaxChars, int readVisibleChars) {
        if (singleResultChars < 1 || batchResultChars < 1 || previewChars < 1
                || previewChars >= singleResultChars || restoreMaxChars < 1
                || readVisibleChars < 1) {
            throw new IllegalArgumentException("Invalid context management limits");
        }
        this.singleResultChars = singleResultChars;
        this.batchResultChars = batchResultChars;
        this.previewChars = previewChars;
        this.restoreMaxChars = restoreMaxChars;
        this.readVisibleChars = readVisibleChars;
    }
}
