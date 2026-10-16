package com.udata.harness.common.context;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.AgentTrace;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.react.task.ToolExchanger;
import org.noear.solon.ai.chat.content.ContentBlock;
import org.noear.solon.ai.chat.content.ImageBlock;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.ToolMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.tool.ToolCall;
import org.noear.solon.ai.chat.tool.ToolResult;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.udata.harness.common.context.ContextArtifactStoreTest.repeat;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 {@link ToolResultContextInterceptor} 的工具结果上下文治理能力：
 *
 * <ul>
 * <li>单结果投影 —— bash、read 与通用白名单工具按各自预算裁剪，
 * 全文持久化为 artifact，历史中只保留可恢复的占位文本；</li>
 * <li>批级投影 —— 批次超出软预算时从大到小裁剪，优先保护源码 read；</li>
 * <li>错误投影 —— 超长错误与 bash 诊断保留失败证据与 error 标志；</li>
 * <li>read 复用 —— 重复引用、同版本区间增量，且投影/不完整的历史不授权复用；</li>
 * <li>任务收敛 —— 任务切换/关闭时归档旧正文，普通轮次历史保持冻结；</li>
 * <li>兜底不变量 —— 持久化失败、受保护消息、非文本块与白名单外工具一律原样放行。</li>
 * </ul>
 */
class ToolResultContextInterceptorTest {
    @TempDir Path directory;

    private ToolResultContextInterceptor interceptor(int single, int batch) {
        return new ToolResultContextInterceptor(new ContextArtifactStore(directory),
                new ContextManagementOptions(single, batch, 20, 4000));
    }

    /** 默认 read 预算取可见字符上限的 80%：中等结果原样保留，超限结果投影为可恢复摘录。 */
    @Test
    void defaultReadBudgetKeepsMediumResultsAndProjectsLargerOnes() {
        ContextManagementOptions options = new ContextManagementOptions(200000, 256000, 4000, 16000);
        assertEquals(160000, options.readVisibleChars);
        ToolResultContextInterceptor interceptor = new ToolResultContextInterceptor(
                new ContextArtifactStore(directory), options);
        Trace trace = new Trace();
        String medium = repeat("x", 120000);
        ToolExchanger visible = exchange("medium", "read", medium);
        interceptor.onToolCallEnd(trace, visible, observation(visible), null, 1);
        assertEquals(medium, visible.getResult());

        ToolExchanger large = exchange("large", "read", repeat("x", 180000));
        interceptor.onToolCallEnd(trace, large, observation(large), null, 1);
        assertTrue(large.getResult().startsWith("<read-projection type=\"excerpt\">"));
        assertTrue(large.getResult().length() <= 160000);
        assertTrue(large.getResult().contains("@tool-results/"));
    }

    /** 较大的 bash 预算在归档前保留有用的头部与尾部输出，而非仅留开头片段。 */
    @Test
    void largerBashBudgetRetainsUsefulHeadAndTailBeforeArchiving() {
        String output = repeat("H", 10000) + repeat("M", 25000) + repeat("T", 10000);
        ToolOutcomeProjection projection = ToolOutcomeProjection.plan("bash", output, false, 20000);
        assertNotNull(projection);
        String visible = projection.render(repeat("a", 64));
        assertTrue(visible.length() > 12000);
        assertTrue(visible.length() <= 20000);
        assertTrue(visible.contains(repeat("H", 5000)));
        assertTrue(visible.contains(repeat("T", 5000)));
    }

    /** 超过单结果限额的结果在进入工作记忆前即被归档为 artifact 占位。 */
    @Test
    void archivesOversizedResultBeforeItEntersMemory() {
        String original = repeat("log line\n", 600);
        ToolExchanger exchange = exchange("call", "bash", original);
        Trace trace = new Trace();
        interceptor(2000, 10000).onToolCallEnd(trace, exchange, observation(exchange), null, 1);
        assertTrue(exchange.getResult().startsWith("<context-artifact"));
        assertTrue(exchange.getResult().length() < original.length());
        assertEquals(0, trace.memory.size());
    }

    /** 仅当可见历史中存在同文件、同区间且内容一致的 read 结果时才去重为引用；历史不可见或内容变化则原样返回。 */
    @Test
    void onlyDeduplicatesAVisibleMatchingReadRangeAndContent() throws IOException {
        Trace trace = new Trace();
        String text = repeat("code", 500);
        ToolCall call = call("old", "read", Collections.singletonMap("file_path", "a.java"));
        trace.memory.addMessage(assistant(call), ChatMessage.ofTool(ToolResult.success(text), "read", "old", false));
        ToolExchanger repeated = exchange("new", "read", text);
        ToolResultContextInterceptor interceptor = interceptor(10000, 20000);
        interceptor.onToolCallEnd(trace, repeated, observation(repeated), null, 1);
        assertTrue(repeated.getResult().contains("type=\"reference\""));
        assertTrue(repeated.getResult().length() <= 6000);
        assertTrue(new ContextArtifactStore(directory).restore("session-test",
                artifactId(repeated.getResult()), 0, text.length()).endsWith(text));

        trace.memory.clear(); // Archived/compacted original is no longer visible.
        ToolExchanger reread = exchange("reread", "read", text);
        interceptor.onToolCallEnd(trace, reread, observation(reread), null, 1);
        assertEquals(text, reread.getResult());

        trace.memory.addMessage(assistant(call), ChatMessage.ofTool(ToolResult.success(text), "read", "old", false));
        ToolExchanger changed = exchange("changed", "read", text + "changed");
        interceptor.onToolCallEnd(trace, changed, observation(changed), null, 1);
        assertEquals(text + "changed", changed.getResult());

        ToolExchanger differentRange = new ToolExchanger("range", "read",
                Collections.<String, Object>singletonMap("file_path", "b.java"));
        differentRange.setResult(text);
        interceptor.onToolCallEnd(trace, differentRange, observation(differentRange), null, 1);
        assertEquals(text, differentRange.getResult());
    }

    /** 超出 read 预算的结果被投影为带恢复说明的显式摘录，全文可从 artifact 还原。 */
    @Test
    void oversizedReadIsAnExplicitRecoverableExcerpt() throws Exception {
        String version = repeat("a", 64);
        String original = numberedRead(1, 140, version, 70);
        ContextArtifactStore store = new ContextArtifactStore(directory);
        ToolResultContextInterceptor interceptor = new ToolResultContextInterceptor(store,
                new ContextManagementOptions(50000, 200000, 2000, 16000, 6000));
        ToolExchanger current = exchange("excerpt", "read", original);
        interceptor.onToolCallEnd(new Trace(), current, observation(current), null, 1);
        String projected = current.getResult();
        assertTrue(projected.startsWith("<read-projection type=\"excerpt\">"));
        assertTrue(projected.contains("不代表完整文件"));
        assertTrue(projected.length() <= 6000);
        String id = artifactId(projected);
        assertTrue(store.restore("session-test", id, 0, original.length()).endsWith(original));
    }

    /** 摘录快照落盘失败时保留原始 read 结果，绝不以半成品投影替换。 */
    @Test
    void readProjectionKeepsOriginalIfSnapshotCannotBeSaved() {
        ContextArtifactStore failed = new ContextArtifactStore(directory) {
            @Override public synchronized Artifact save(String sessionId, String invocationId, String tool,
                                                         String content, String reason, int preview,
                                                         Map<String, String> metadata) throws IOException {
                throw new IOException("simulated disk failure");
            }
        };
        String original = numberedRead(1, 140, repeat("e", 64), 70);
        ToolExchanger read = exchange("failed-read", "read", original);
        new ToolResultContextInterceptor(failed,
                new ContextManagementOptions(50000, 200000, 2000, 16000, 6000))
                .onToolCallEnd(new Trace(), read, observation(read), null, 1);
        assertEquals(original, read.getResult());
    }

    /** 超长 bash 失败输出的投影保留失败证据（状态与错误行），且全文可从 artifact 还原。 */
    @Test
    void longBashResultShowsFailureEvidenceAndRestoresCapturedText() throws IOException {
        String raw = repeat("build step ok\n", 650) + "[ERROR] Cannot compile App.java:42\n"
                + repeat("build tail\n", 400) + "[exit_code=1]";
        ToolExchanger exchange = exchange("bash-1", "bash", raw);
        ContextArtifactStore store = new ContextArtifactStore(directory);
        ToolResultContextInterceptor interceptor = new ToolResultContextInterceptor(store,
                new ContextManagementOptions(50000, 200000, 2000, 16000, 6000, 6000, 4000));
        interceptor.onToolCallEnd(new Trace(), exchange, observation(exchange), null, 1);
        String projected = exchange.getResult();
        assertTrue(projected.contains("tool=\"bash\" status=\"failed\""));
        assertTrue(projected.contains("Cannot compile App.java:42"));
        assertTrue(projected.length() <= 6000);
        assertTrue(store.restore("session-test", artifactId(projected), 0, raw.length()).endsWith(raw));
    }

    /** 用户显式请求较小的 {@code max_output_chars} 时，输出按请求预算（而非配置预算）投影，且仍可恢复。 */
    @Test
    void requestedSmallBashOutputStillGetsRecoverableProjection() {
        String raw = repeat("[ERROR] compiler diagnostic\n", 400);
        Map<String, Object> args = new java.util.HashMap<>();
        args.put("command", "compile");
        args.put("max_output_chars", 1000);
        ToolExchanger exchange = new ToolExchanger("small-preview", "bash", args);
        exchange.setResult(raw);
        ToolResultContextInterceptor interceptor = new ToolResultContextInterceptor(
                new ContextArtifactStore(directory),
                new ContextManagementOptions(50000, 200000, 2000, 16000, 6000, 6000, 4000));
        interceptor.onToolCallEnd(new Trace(), exchange, observation(exchange), null, 1);
        assertTrue(exchange.getResult().contains("<tool-result-projection"));
        assertTrue(exchange.getResult().length() <= 1000);
    }

    /** 超长工具错误按错误预算裁剪，保留 error 标志与原始快照。 */
    @Test
    void longToolErrorKeepsErrorFlagAndOriginalSnapshot() throws IOException {
        String raw = repeat("stack frame\n", 750) + "Caused by: permission denied";
        ToolExchanger exchange = exchange("edit-error", "edit", raw);
        exchange.setToolResult(ToolResult.error(raw));
        ContextArtifactStore store = new ContextArtifactStore(directory);
        ToolResultContextInterceptor interceptor = new ToolResultContextInterceptor(store,
                new ContextManagementOptions(50000, 200000, 2000, 16000, 6000, 6000, 4000));
        interceptor.onToolCallEnd(new Trace(), exchange, observation(exchange), null, 1);
        assertTrue(exchange.getToolResult().isError());
        assertTrue(exchange.getResult().contains("status=\"error\""));
        assertTrue(exchange.getResult().contains("permission denied"));
        assertTrue(exchange.getResult().length() <= 4000);
        assertTrue(store.restore("session-test", artifactId(exchange.getResult()), 0, raw.length()).endsWith(raw));
    }

    /** 被物理上限截断的 bash 输出标记为不完整捕获，提示模型只能取得已捕获部分。 */
    @Test
    void physicallyCappedBashOutputIsMarkedAsIncomplete() {
        String raw = repeat("log line\n", 950)
                + "... [输出超过 1048576 字节的物理上限，命令已被终止。请缩小输出范围]";
        ToolExchanger exchange = exchange("capped", "bash", raw);
        ToolResultContextInterceptor interceptor = new ToolResultContextInterceptor(
                new ContextArtifactStore(directory),
                new ContextManagementOptions(50000, 200000, 2000, 16000, 6000, 6000, 4000));
        interceptor.onToolCallEnd(new Trace(), exchange, observation(exchange), null, 1);
        assertTrue(exchange.getResult().contains("capture_complete=\"false\""));
        assertTrue(exchange.getResult().contains("只能取得已捕获部分"));
    }

    /** 关键异常的错误结果物化晚于单结果钩子，在批末（onActionEnd）补投影且保留 error 标志与快照。 */
    @Test
    void criticalExceptionMessageIsProjectedAfterItEntersWorkingMemory() throws IOException {
        String raw = repeat("stack frame\n", 750) + "Caused by: disk unavailable";
        Trace trace = new Trace();
        trace.reason = assistant(call("critical", "edit", Collections.emptyMap()));
        trace.memory.addMessage(trace.reason,
                ChatMessage.ofTool(ToolResult.error(raw), "edit", "critical", false));
        ContextArtifactStore store = new ContextArtifactStore(directory);
        ToolResultContextInterceptor interceptor = new ToolResultContextInterceptor(store,
                new ContextManagementOptions(50000, 200000, 2000, 16000, 6000, 6000, 4000));
        interceptor.onActionEnd(trace, Collections.emptyList());
        ToolMessage result = (ToolMessage) trace.memory.getMessages().get(1);
        assertTrue(result.isError());
        assertTrue(result.getContent().contains("status=\"error\""));
        assertTrue(result.getContent().contains("disk unavailable"));
        assertTrue(store.restore("session-test", artifactId(result.getContent()), 0, raw.length()).endsWith(raw));
    }

    /** 诊断持久化失败时保留原文与错误标志，投影绝不导致结果丢失。 */
    @Test
    void failedDiagnosticPersistenceRetainsOriginalAndErrorFlag() {
        ContextArtifactStore failed = new ContextArtifactStore(directory) {
            @Override public synchronized Artifact save(String sessionId, String invocationId, String tool,
                                                         String content, String reason, int preview,
                                                         Map<String, String> metadata) throws IOException {
                throw new IOException("simulated disk failure");
            }
        };
        String raw = repeat("failure\n", 800);
        ToolExchanger exchange = exchange("error-persist", "edit", raw);
        exchange.setToolResult(ToolResult.error(raw));
        new ToolResultContextInterceptor(failed,
                new ContextManagementOptions(50000, 200000, 2000, 16000, 6000, 6000, 4000))
                .onToolCallEnd(new Trace(), exchange, observation(exchange), null, 1);
        assertTrue(exchange.getToolResult().isError());
        assertEquals(raw, exchange.getResult());
    }

    /** 批级归档可对已投影的 read 结果再次归档，invocation 作用域限定避免快照键冲突。 */
    @Test
    void batchArchivingCanRearchiveProjectedReadWithoutSnapshotCollision() {
        Trace trace = new Trace();
        ToolResultContextInterceptor interceptor = new ToolResultContextInterceptor(
                new ContextArtifactStore(directory),
                new ContextManagementOptions(50000, 500, 20, 4000, 6000));
        ToolExchanger read = exchange("batch-read", "read",
                numberedRead(1, 140, repeat("f", 64), 70));
        interceptor.onToolCallEnd(trace, read, observation(read), null, 1);
        assertTrue(read.getResult().contains("type=\"excerpt\""));
        trace.reason = assistant(call("batch-read", "read", readArgs(1, 140)));
        trace.memory.addMessage(trace.reason,
                ChatMessage.ofTool(ToolResult.success(read.getResult()), "read", "batch-read", false));
        interceptor.onActionEnd(trace, Collections.singletonList(read));
        assertTrue(((ToolMessage) trace.memory.getMessages().get(1)).getContent()
                .contains("batch_result_limit"));
    }

    /** 批预算超限时优先裁剪其他工具结果，完整源码 read 保留至最后。 */
    @Test
    void batchBudgetKeepsSourceReadBeforeShrinkingOtherResults() {
        Trace trace = new Trace();
        ToolExchanger read = exchange("source-read", "read", repeat("R", 15000));
        ToolExchanger grep = exchange("search", "grep", repeat("G", 15000));
        trace.reason = assistant(call("source-read", "read", Collections.emptyMap()),
                call("search", "grep", Collections.emptyMap()));
        trace.memory.addMessage(trace.reason,
                ChatMessage.ofTool(ToolResult.success(read.getResult()), "read", "source-read", false),
                ChatMessage.ofTool(ToolResult.success(grep.getResult()), "grep", "search", false));
        new ToolResultContextInterceptor(new ContextArtifactStore(directory),
                new ContextManagementOptions(100000, 25000, 20, 4000, 50000))
                .onActionEnd(trace, Arrays.asList(read, grep));
        assertEquals(repeat("R", 15000), ((ToolMessage) trace.memory.getMessages().get(1)).getContent());
        assertTrue(((ToolMessage) trace.memory.getMessages().get(2)).getContent().contains("batch_result_limit"));
    }

    /** 仅当版本一致时，与可见历史重叠的行才压缩为区间增量；版本变化则原样返回。 */
    @Test
    void overlappingVisibleLinesBecomeRangeDeltaOnlyForSameVersion() throws IOException {
        String version = repeat("b", 64);
        String previous = numberedRead(1, 100, version, 32);
        String current = numberedRead(80, 150, version, 32);
        Trace trace = new Trace();
        trace.memory.addMessage(assistant(call("old", "read", readArgs(1, 100))),
                ChatMessage.ofTool(ToolResult.success(previous), "read", "old", false));
        ToolExchanger read = new ToolExchanger("new", "read", readArgs(80, 71));
        read.setResult(current);
        interceptor(50000, 200000).onToolCallEnd(trace, read, observation(read), null, 1);
        assertTrue(read.getResult().contains("type=\"range_delta\""));
        assertTrue(read.getResult().length() <= 6000);
        assertTrue(read.getResult().contains("可复用的完整行：80-100"));
        assertTrue(read.getResult().contains("   101 | "));
        assertFalse(read.getResult().contains("    80 | "));
        assertTrue(new ContextArtifactStore(directory).restore("session-test",
                artifactId(read.getResult()), 0, current.length()).endsWith(current));

        ToolExchanger changed = new ToolExchanger("changed", "read", readArgs(80, 71));
        changed.setResult(numberedRead(80, 150, repeat("c", 64), 32));
        interceptor(50000, 200000).onToolCallEnd(trace, changed, observation(changed), null, 1);
        assertEquals(numberedRead(80, 150, repeat("c", 64), 32), changed.getResult());
    }

    /** 隐藏版本元数据仍可支持增量复用，且不会在投影中暴露摘要。 */
    @Test
    void internalVersionsAuthorizeRangeReuseWithoutVisibleHashes() throws IOException {
        String version = repeat("b", 64);
        String previous = numberedRead(1, 100, version, 32).replace("文件版本 SHA-256: " + version, "");
        String current = numberedRead(80, 150, version, 32).replace("文件版本 SHA-256: " + version, "");
        ToolResult prior = ToolResult.success(previous);
        prior.metas().put("sourceHash", version);
        Trace trace = new Trace();
        trace.memory.addMessage(assistant(call("old", "read", readArgs(1, 100))),
                ChatMessage.ofTool(prior, "read", "old", false));
        ToolExchanger read = new ToolExchanger("new", "read", readArgs(80, 71));
        ToolResult result = ToolResult.success(current);
        result.metas().put("sourceHash", version);
        read.setToolResult(result);
        interceptor(50000, 200000).onToolCallEnd(trace, read, observation(read), null, 1);
        assertTrue(read.getResult().contains("type=\"range_delta\""));
        assertFalse(read.getResult().contains(version));
        assertFalse(read.getResult().contains("SHA-256"));
        assertEquals(version, read.getToolResult().metas().get("sourceHash"));

        //1. 即使可见正文相同，文件其他范围发生变化也不能只引用旧正文。
        ToolExchanger changed = new ToolExchanger("changed", "read", readArgs(1, 100));
        ToolResult changedResult = ToolResult.success(previous);
        changedResult.metas().put("sourceHash", repeat("c", 64));
        changed.setToolResult(changedResult);
        interceptor(50000, 200000).onToolCallEnd(trace, changed, observation(changed), null, 1);
        assertEquals(previous, changed.getResult());
    }

    /** 已投影或不完整的历史 read 不是完整可见正文，不能授权区间复用。 */
    @Test
    void projectedOrIncompleteOldReadCannotAuthorizeRangeReuse() {
        String version = repeat("d", 64);
        String current = numberedRead(80, 110, version, 32);
        Trace trace = new Trace();
        trace.memory.addMessage(assistant(call("old", "read", readArgs(1, 100))),
                ChatMessage.ofTool(ToolResult.success("<read-projection type=\"excerpt\">only a preview</read-projection>"),
                        "read", "old", false));
        ToolExchanger read = new ToolExchanger("new", "read", readArgs(80, 31));
        read.setResult(current);
        interceptor(50000, 200000).onToolCallEnd(trace, read, observation(read), null, 1);
        assertEquals(current, read.getResult());
    }

    /** 错误结果、带 META_FIRST 的受保护消息与含非文本块的结果一律保持原样。 */
    @Test
    void preservesErrorsProtectedMessagesAndNonTextBlocks() {
        Trace trace = new Trace();
        String text = repeat("failure", 1000);
        ToolResultContextInterceptor interceptor = interceptor(1000, 2000);
        ToolExchanger failed = exchange("failed", "bash", text);
        failed.setToolResult(ToolResult.error(text));
        interceptor.onToolCallEnd(trace, failed, observation(failed), null, 1);
        assertTrue(failed.getToolResult().isError());
        assertEquals(text, failed.getResult());

        ToolExchanger protectedResult = exchange("protected", "read", text);
        ToolMessage protectedMessage = observation(protectedResult);
        protectedMessage.addMetadata(AgentTrace.META_FIRST, 1);
        interceptor.onToolCallEnd(trace, protectedResult, protectedMessage, null, 1);
        assertEquals(text, protectedResult.getResult());

        ToolExchanger rich = exchange("rich", "read", text);
        ContentBlock extra = ImageBlock.ofBase64("aGVsbG8=", "image/png");
        rich.getToolResult().addBlock(extra);
        interceptor.onToolCallEnd(trace, rich, observation(rich), null, 1);
        assertTrue(rich.getToolResult().getBlocks().contains(extra));
        assertEquals(text, rich.getResult());
    }

    /** artifact 持久化失败时保留原始结果，治理失败不影响可用性。 */
    @Test
    void keepsOriginalWhenPersistenceFails() {
        ContextArtifactStore failed = new ContextArtifactStore(directory) {
            @Override public synchronized Artifact save(String sessionId, String invocationId, String tool,
                                                         String content, String reason, int preview) throws IOException {
                throw new IOException("simulated disk failure");
            }
        };
        ToolResultContextInterceptor interceptor = new ToolResultContextInterceptor(failed,
                new ContextManagementOptions(1000, 2000, 20, 1000));
        ToolExchanger exchange = exchange("call", "bash", repeat("log", 1000));
        String original = exchange.getResult();
        interceptor.onToolCallEnd(new Trace(), exchange, observation(exchange), null, 1);
        assertEquals(original, exchange.getResult());
    }

    /** 批预算仅替换本批次新结果：既有历史冻结、工具调用配对保持，且替换时丢弃过期的 token_size 元数据。 */
    @Test
    void batchBudgetOnlyReplacesNewResultsAndPreservesToolPairs() {
        Trace trace = new Trace();
        ToolCall first = call("first", "grep", Collections.emptyMap());
        ToolCall second = call("second", "grep", Collections.emptyMap());
        trace.reason = assistant(first, second);
        String large = repeat("result", 600);
        // A compatible provider reused "first" from an earlier request. Old history must stay frozen.
        ToolMessage old = ChatMessage.ofTool(ToolResult.success(large), "grep", "first", false);
        ToolMessage one = ChatMessage.ofTool(ToolResult.success(large), "grep", "first", false);
        one.addMetadata("token_size", 999999);
        one.addMetadata("provenance", "test");
        ToolMessage two = ChatMessage.ofTool(ToolResult.success(large), "grep", "second", false);
        trace.memory.addMessage(old, trace.reason, one, two);
        interceptor(10000, 4500).onActionEnd(trace, Collections.emptyList());
        assertSame(old, trace.memory.getMessages().get(0));
        assertSame(trace.reason, trace.memory.getMessages().get(1));
        ToolMessage projected = (ToolMessage) trace.memory.getMessages().get(2);
        assertEquals("first", projected.getToolCallId());
        assertTrue(projected.getContent().contains("batch_result_limit"));
        assertEquals("test", projected.getMetadata().get("provenance"));
        assertFalse(projected.hasMetadata("token_size"));
        long chars = trace.memory.getMessages().subList(2, 4).stream()
                .mapToLong(value -> value.getContent().length()).sum();
        assertTrue(chars <= 4500);
    }

    /** 小结果、上下文恢复工具与白名单外工具原样放行，不做任何投影。 */
    @Test
    void leavesSmallAndRestoreAndUnknownToolResultsAlone() {
        ToolResultContextInterceptor interceptor = interceptor(1000, 2000);
        for (String tool : Arrays.asList("context_restore", "render_echart", "mcp_custom")) {
            ToolExchanger exchange = exchange("call-" + tool, tool, repeat("data", 1000));
            String before = exchange.getResult();
            interceptor.onToolCallEnd(new Trace(), exchange, observation(exchange), null, 1);
            assertEquals(before, exchange.getResult());
        }
        ToolExchanger small = exchange("small", "bash", "ok");
        interceptor.onToolCallEnd(new Trace(), small, observation(small), null, 1);
        assertEquals("ok", small.getResult());
    }

    /** 任务切换时归档旧任务正文为可检索的 artifact，协议约束消息保留，全文可按任务检索恢复。 */
    @Test
    void taskSwitchArchivesOldBodiesWhilePreservingProtocolConstraintsAndRecovery() throws Exception {
        ContextArtifactStore store = new ContextArtifactStore(directory);
        ContextTaskStore tasks = new ContextTaskStore(store);
        tasks.update("session-test", "A", "auth", "active", "Keep API", null, null, null);
        ToolResultContextInterceptor interceptor = new ToolResultContextInterceptor(store,
                new ContextManagementOptions(20000, 40000, 20, 4000), tasks, directory);
        Trace trace = new Trace();
        trace.reason = assistant(call("old", "read", Collections.emptyMap()));
        ToolMessage body = ChatMessage.ofTool(ToolResult.success(repeat("code", 2000) + "EVIDENCE"), "read", "old", false);
        body.addMetadata("token_size", 12345);
        org.noear.solon.ai.chat.message.UserMessage constraint = ChatMessage.ofUser("Keep API");
        trace.memory.addMessage(constraint, trace.reason, body);
        interceptor.onActionEnd(trace, Collections.emptyList());
        assertSame(body, trace.memory.getMessages().get(2)); // Same-task rounds keep original history.
        tasks.update("session-test", "B", "orders", "active", null, null, null, null);
        trace.reason = assistant(call("new", "grep", Collections.emptyMap()));
        ToolMessage current = ChatMessage.ofTool(ToolResult.success("new body"), "grep", "new", false);
        trace.memory.addMessage(trace.reason, current);
        interceptor.onActionEnd(trace, Collections.emptyList());
        assertSame(constraint, trace.memory.getMessages().get(0));
        ToolMessage archived = (ToolMessage) trace.memory.getMessages().get(2);
        assertEquals("old", archived.getToolCallId());
        assertTrue(archived.getContent().contains("task_paused"));
        assertFalse(archived.hasMetadata("token_size"));
        assertSame(current, trace.memory.getMessages().get(4));
        String search = store.search("session-test", "EVIDENCE", "A", null, 0, 5);
        assertTrue(search.contains("matches=1"));
        String id = search.substring(search.indexOf("artifact_id=") + 12, search.indexOf("artifact_id=") + 76);
        assertTrue(store.restore("session-test", id, 8000, 8).endsWith("\nEVIDENCE"));
    }

    /** 任务关闭收敛归档安全结果，错误结果、受保护消息与无归属消息保持原样。 */
    @Test
    void closedTaskConvergenceRetainsErrorsProtectedResultsAndUnknownOwnership() throws Exception {
        ContextArtifactStore store = new ContextArtifactStore(directory);
        ContextTaskStore tasks = new ContextTaskStore(store);
        tasks.update("session-test", "A", "auth", "active", null, null, null, null);
        ToolResultContextInterceptor interceptor = new ToolResultContextInterceptor(store,
                new ContextManagementOptions(20000, 40000, 20, 4000), tasks, directory);
        Trace trace = new Trace();
        trace.reason = assistant(call("safe", "bash", Collections.emptyMap()));
        String text = repeat("log", 600);
        ToolMessage safe = ChatMessage.ofTool(ToolResult.success(text), "bash", "safe", false);
        ToolMessage error = ChatMessage.ofTool(ToolResult.error(text), "bash", "error", false);
        ToolMessage protectedResult = ChatMessage.ofTool(ToolResult.success(text), "read", "protected", false);
        protectedResult.addMetadata(AgentTrace.META_FIRST, true);
        trace.memory.addMessage(trace.reason, safe, error, protectedResult);
        interceptor.onActionEnd(trace, Collections.emptyList());
        tasks.update("session-test", "A", null, "closed", null, "Fixed", "Tests passed", null);
        trace.reason = assistant(call("new", "grep", Collections.emptyMap()));
        trace.memory.addMessage(trace.reason, ChatMessage.ofTool(ToolResult.success("ok"), "grep", "new", false));
        interceptor.onActionEnd(trace, Collections.emptyList());
        assertTrue(trace.memory.getMessages().get(1).getContent().contains("task_closed"));
        assertSame(error, trace.memory.getMessages().get(2));
        assertSame(protectedResult, trace.memory.getMessages().get(3));
    }

    /** 构造指定 id、工具名并预置纯文本结果的已完成交换。 */
    static ToolExchanger exchange(String id, String tool, String text) {
        ToolExchanger value = new ToolExchanger(id, tool, Collections.<String, Object>singletonMap("file_path", "a.java"));
        value.setResult(text);
        return value;
    }

    /** 生成交换当前结果对应的观测消息（即回传给模型的 ToolMessage）。 */
    static ToolMessage observation(ToolExchanger value) {
        return ChatMessage.ofTool(value.getToolResult(), value.getToolName(), value.getCallId(), false);
    }

    /** 构造一条 assistant 的工具调用。 */
    static ToolCall call(String id, String name, Map<String, Object> args) {
        return new ToolCall("0", id, name, null, args);
    }

    /** 构造 read 工具的规范参数（file_path + offset + limit）。 */
    private static Map<String, Object> readArgs(int offset, int limit) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("file_path", "a.java");
        args.put("offset", offset);
        args.put("limit", limit);
        return args;
    }

    /**
     * 构造符合 read 工具输出规范的带行号文本（文件头、行号栏、版本 SHA-256 尾部）。
     *
     * @param start   起始行号（含）
     * @param end     结束行号（含）
     * @param version 文件版本 SHA-256（64 位十六进制）
     * @param width   每行内容的填充宽度
     */
    private static String numberedRead(int start, int end, String version, int width) {
        StringBuilder result = new StringBuilder("--- File: a.java (Lines: " + start + " - " + end
                + ", Size: 1.00 KB) ---\n--------------------------------------------------\n");
        for (int line = start; line <= end; line++) {
            result.append(String.format("%6d | line-%d-%s\n", line, line, repeat("x", width)));
        }
        return result.append("\n--- [文件读取完毕] ---\n文件版本 SHA-256: ")
                .append(version).toString();
    }

    /** 从投影占位文本（{@code artifact_id="…"} 或 {@code @tool-results/…}）中提取制品 id。 */
    private static String artifactId(String output) {
        Matcher match = Pattern.compile("(?:artifact_id=\\\"|@tool-results/)([a-f0-9]{64})(?:\\\"|\\.txt)").matcher(output);
        assertTrue(match.find());
        return match.group(1);
    }

    /** 构造携带工具调用列表的 assistant 消息（Reason）。 */
    static AssistantMessage assistant(ToolCall... calls) {
        return new AssistantMessage(null, null, Arrays.asList(calls), Collections.emptyList());
    }

    /** 轻量 ReAct 轨迹测试替身：固定 session-id 并允许直接设置工作记忆与最后一条 Reason。 */
    static class Trace extends ReActTrace {
        final AgentSession session = InMemoryAgentSession.of("session-test");
        final Prompt memory = Prompt.of(Collections.<ChatMessage>emptyList());
        AssistantMessage reason;
        @Override public AgentSession getSession() { return session; }
        @Override public Prompt getWorkingMemory() { return memory; }
        @Override public AssistantMessage getLastReasonMessage() { return reason; }
    }
}
