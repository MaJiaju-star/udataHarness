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

class ToolResultContextInterceptorTest {
    @TempDir Path directory;

    private ToolResultContextInterceptor interceptor(int single, int batch) {
        return new ToolResultContextInterceptor(new ContextArtifactStore(directory),
                new ContextManagementOptions(single, batch, 20, 4000));
    }

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

    static ToolExchanger exchange(String id, String tool, String text) {
        ToolExchanger value = new ToolExchanger(id, tool, Collections.<String, Object>singletonMap("file_path", "a.java"));
        value.setResult(text);
        return value;
    }

    static ToolMessage observation(ToolExchanger value) {
        return ChatMessage.ofTool(value.getToolResult(), value.getToolName(), value.getCallId(), false);
    }

    static ToolCall call(String id, String name, Map<String, Object> args) {
        return new ToolCall("0", id, name, null, args);
    }

    private static Map<String, Object> readArgs(int offset, int limit) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("file_path", "a.java");
        args.put("offset", offset);
        args.put("limit", limit);
        return args;
    }

    private static String numberedRead(int start, int end, String version, int width) {
        StringBuilder result = new StringBuilder("--- File: a.java (Lines: " + start + " - " + end
                + ", Size: 1.00 KB) ---\n--------------------------------------------------\n");
        for (int line = start; line <= end; line++) {
            result.append(String.format("%6d | line-%d-%s\n", line, line, repeat("x", width)));
        }
        return result.append("\n--- [文件读取完毕] ---\n文件版本 SHA-256: ")
                .append(version).toString();
    }

    private static String artifactId(String output) {
        Matcher match = Pattern.compile("artifact_id=\\\"([a-f0-9]{64})\\\"").matcher(output);
        assertTrue(match.find());
        return match.group(1);
    }

    static AssistantMessage assistant(ToolCall... calls) {
        return new AssistantMessage(null, null, Arrays.asList(calls), Collections.emptyList());
    }

    static class Trace extends ReActTrace {
        final AgentSession session = InMemoryAgentSession.of("session-test");
        final Prompt memory = Prompt.of(Collections.<ChatMessage>emptyList());
        AssistantMessage reason;
        @Override public AgentSession getSession() { return session; }
        @Override public Prompt getWorkingMemory() { return memory; }
        @Override public AssistantMessage getLastReasonMessage() { return reason; }
    }
}
