package com.udata.harness.common.context;

import com.udata.harness.repository.CompactingFileAgentSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActOptions;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.session.FileAgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.ToolMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.ToolCall;
import org.noear.solon.ai.chat.tool.ToolResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ContextIdentityTest {
    @TempDir Path directory;

    @Test
    void newAndLegacyMessageIdsSurviveRestartAndDuplicateTextHasDistinctIds() {
        FileAgentSession legacy = new FileAgentSession("s", directory.toString());
        legacy.addMessage(ChatMessage.ofUser("same"), ChatMessage.ofUser("same"));
        CompactingFileAgentSession session = new CompactingFileAgentSession("s", directory.toString());
        String first = ContextMessageIds.id(session.getMessages().get(0));
        String second = ContextMessageIds.id(session.getMessages().get(1));
        assertNotEquals(first, second);
        CompactingFileAgentSession reopened = new CompactingFileAgentSession("s", directory.toString());
        assertEquals(first, ContextMessageIds.id(reopened.getMessages().get(0)));
        ChatMessage fresh = ChatMessage.ofAssistant("new"); session.addMessage(fresh);
        assertEquals(ContextMessageIds.id(fresh), ContextMessageIds.id(new CompactingFileAgentSession("s", directory.toString()).getMessages().get(2)));
    }

    @Test
    void projectedBodyPersistsWithoutChangingRawAndCompactsUsingClonedEntryIds() throws Exception {
        CompactingFileAgentSession session = toolSession();
        List<ChatMessage> raw = session.getMessages();
        ToolMessage projected = tool("short snapshot", "call");
        ContextMessageIds.projection(raw.get(2), projected);
        List<ChatMessage> view = cloneMessages(raw); view.set(2, projected);
        session.checkpoints().project(raw, view, true);
        CompactingFileAgentSession restarted = new CompactingFileAgentSession("s", directory.toString());
        assertEquals("ORIGINAL_TOOL_BODY", restarted.getMessages().get(2).getContent());
        assertEquals("short snapshot", restarted.getLatestMessages(200).get(2).getContent());
        assertEquals(ContextMessageIds.id(raw.get(2)), ContextMessageIds.id(restarted.getLatestMessages(200).get(2)));
        List<ChatMessage> prefix = cloneMessages(restarted.getLatestMessages(200).subList(0, 3));
        int covered = ActiveContextView.coveredThrough(restarted.getMessages(), 0, prefix);
        assertEquals(3, covered);
        restarted.checkpoints().save(restarted.getMessages(), covered, "checkpoint facts", "model");
        CompactingFileAgentSession again = new CompactingFileAgentSession("s", directory.toString());
        assertEquals(4, again.getMessages().size());
        assertEquals(2, again.getLatestMessages(200).size());
        assertEquals("checkpoint facts", again.getLatestMessages(200).get(0).getContent());
        assertEquals("final", again.getLatestMessages(200).get(1).getContent());
        assertFalse(again.getLatestMessages(200).toString().contains("ORIGINAL_TOOL_BODY"));
        assertEquals(session.checkpoints().load(raw).entryId, ContextMessageIds.id(again.getLatestMessages(200).get(0)));
    }

    @Test
    void rejectsSkippedAndDuplicateCoverageAndLeavesToolsPaired() throws Exception {
        CompactingFileAgentSession session = toolSession(); List<ChatMessage> raw = session.getMessages();
        assertThrows(IllegalArgumentException.class, () -> ActiveContextView.coveredThrough(raw, 0, Collections.singletonList(raw.get(1))));
        assertThrows(IllegalArgumentException.class, () -> ActiveContextView.coveredThrough(raw, 1, Collections.singletonList(raw.get(0))));
        List<ChatMessage> duplicate = cloneMessages(raw); duplicate.get(1).addMetadata(ContextMessageIds.ENTRY_ID, ContextMessageIds.id(raw.get(0)));
        assertThrows(java.io.IOException.class, () -> session.checkpoints().save(duplicate, 2, "facts", "m"));
        ToolMessage wrong = tool("bad", "different-call"); ContextMessageIds.projection(raw.get(2), wrong);
        List<ChatMessage> view = cloneMessages(raw); view.set(2, wrong); session.checkpoints().project(raw, view, true);
        assertEquals("call", ((ToolMessage) session.getLatestMessages(200).get(2)).getToolCallId());
        assertEquals("ORIGINAL_TOOL_BODY", session.getLatestMessages(200).get(2).getContent());
    }

    @Test
    void oldCheckpointLoadsAndInvalidRawPrefixFallsBackAndAdvancesEpoch() throws Exception {
        CompactingFileAgentSession session = toolSession(); List<ChatMessage> raw = session.getMessages();
        session.checkpoints().save(raw, 3, "facts", "m");
        Path file = directory.resolve("compaction-checkpoint.properties");
        Properties values = ContextArtifactStore.readProperties(file);
        values.remove("version"); values.remove("coveredEntryIds"); values.remove("semanticPrefixSha256");
        ContextArtifactStore.writeProperties(file, values);
        assertNotNull(session.checkpoints().load(raw));
        String epoch = session.checkpoints().observeSignature("config", raw);
        raw.set(0, ChatMessage.ofUser("changed requirement")); ContextMessageIds.ensure(raw.get(0));
        assertNull(session.checkpoints().load(raw));
        assertEquals("changed requirement", session.checkpoints().build(raw).get(0).getContent());
        assertNotEquals(epoch, session.checkpoints().observeSignature("config", raw));
    }

    @Test
    void epochSurvivesRestartAndOnlyPrefixOrConfigurationChangesInvalidateUsage() throws Exception {
        CompactingFileAgentSession session = toolSession(); Trace trace = new Trace(session);
        String a = ContextEpoch.observe(trace, "rules");
        Map<String, Object> usage = new LinkedHashMap<>(); usage.put("contextEpoch", a); usage.put("inputTokens", 100L);
        trace.setExtra(ContextTelemetryInterceptor.LAST_USAGE, usage);
        trace.memory.addMessage(ChatMessage.ofAssistant("new output"));
        assertEquals(a, ContextEpoch.observe(trace, "rules")); assertSame(usage, ContextEpoch.validUsage(trace));
        assertEquals(a, new CompactingFileAgentSession("s", directory.toString()).checkpoints().epoch());
        List<ChatMessage> view = new ArrayList<>(trace.memory.getMessages());
        ToolMessage replacement = tool("snapshot", "call"); ContextMessageIds.projection(view.get(2), replacement); view.set(2, replacement);
        ContextEpoch.replace(trace, view, true);
        String b = ContextEpoch.current(trace); assertNotEquals(a, b); assertNull(ContextEpoch.validUsage(trace));
        assertEquals(b, ContextEpoch.observe(trace, "rules"));
        assertNotEquals(b, ContextEpoch.observe(trace, "new rules"));
        String rules = ContextEpoch.current(trace);
        trace.tools.add(new CacheAlignedCompressionInterceptorTest.SearchFixture().getTools().iterator().next());
        assertNotEquals(rules, ContextEpoch.observe(trace, "new rules"));
        String tools = ContextEpoch.current(trace); trace.options = options("another-model", "http://127.0.0.1/other");
        assertNotEquals(tools, ContextEpoch.observe(trace, "new rules"));
    }

    @Test
    void failedPersistenceDoesNotChangeActiveViewOrAuthorizeOldUsage() throws Exception {
        CompactingFileAgentSession session = toolSession(); Trace trace = new Trace(session);
        String before = ContextEpoch.observe(trace, "rules");
        Files.delete(directory.resolve("compaction-checkpoint.properties"));
        Files.createDirectory(directory.resolve("compaction-checkpoint.properties"));
        List<ChatMessage> view = cloneMessages(trace.memory.getMessages());
        ToolMessage replacement = tool("snapshot", "call"); ContextMessageIds.projection(view.get(2), replacement); view.set(2, replacement);
        assertThrows(java.io.IOException.class, () -> ContextEpoch.replace(trace, view, true));
        assertEquals("ORIGINAL_TOOL_BODY", trace.memory.getMessages().get(2).getContent());
        Map<String, Object> stale = new HashMap<>(); stale.put("contextEpoch", before); trace.setExtra(ContextTelemetryInterceptor.LAST_USAGE, stale);
        assertNull(ContextEpoch.validUsage(trace));
    }

    @Test
    void independentSessionsAndModelRoutesHaveIndependentEpochs() {
        Trace a = new Trace(InMemoryAgentSession.of("a")), b = new Trace(InMemoryAgentSession.of("b"));
        String first = ContextEpoch.observe(a, "rules"); assertNotEquals(first, ContextEpoch.observe(b, "rules"));
        a.options = options("model", "http://127.0.0.1/new-route");
        assertNotEquals(first, ContextEpoch.observe(a, "rules"));
    }

    @Test
    void currentTurnProtectionIgnoresCheckpointsAndRequiresIntentSectionOnlyForSplitTurns() {
        ChatMessage checkpoint = ChatMessage.ofUser("old facts"); checkpoint.addMetadata(ContextMessageIds.CHECKPOINT, true);
        ChatMessage request = ChatMessage.ofUser("修复鉴权；不要改变公开接口；验收过期 token。");
        List<ChatMessage> messages = Arrays.asList(checkpoint, request, ChatMessage.ofAssistant("early progress"), tool("recent", "call"));
        CurrentTurnIntent split = CurrentTurnIntent.prepare(messages, 3);
        assertTrue(split.split); assertFalse(split.valid("## Facts\n- done"));
        assertFalse(split.valid("## Current Turn Intent\n"));
        assertFalse(split.valid("## Current Turn Intent\n## Next Step\n- Run tests"));
        assertFalse(split.valid("## Current Turn Intent\n- (none)\n"));
        assertTrue(split.valid("## Current Turn Intent\n- Preserve contract\n"));
        assertTrue(split.protect("facts").contains("不要改变公开接口"));
        assertFalse(CurrentTurnIntent.prepare(messages, 1).split);
        assertEquals("facts", CurrentTurnIntent.prepare(messages, 1).protect("facts"));
    }

    @Test
    void legacyCompactionModeKeepsStableIdsButDoesNotLoadCacheAlignedCheckpoint() throws Exception {
        CompactingFileAgentSession session = toolSession(); session.checkpoints().save(session.getMessages(), 3, "facts", "m");
        CompactingFileAgentSession legacy = new CompactingFileAgentSession("s", directory.toString(), false);
        assertEquals(4, legacy.getLatestMessages(200).size());
        assertEquals(ContextMessageIds.id(session.getMessages().get(0)), ContextMessageIds.id(legacy.getLatestMessages(200).get(0)));
        assertNotEquals(ContextEpoch.observe(new Trace(session), "rules"), ContextEpoch.observe(new Trace(legacy), "rules"));
    }

    @Test
    void rebuildingHistoryInANewTraceInvalidatesThePreviousVisiblePrefix() {
        CompactingFileAgentSession session = toolSession(); Trace first = new Trace(session);
        ContextEpoch.observe(first, "rules");
        first.memory.addMessage(ChatMessage.ofAssistant("transient reasoning"), tool("transient output", "new-call"));
        String previous = ContextEpoch.observe(first, "rules");
        Trace reopened = new Trace(new CompactingFileAgentSession("s", directory.toString()));
        assertNotEquals(previous, ContextEpoch.observe(reopened, "rules"));
    }

    @Test
    void inFlightEpochChangeKeepsUsageInAuditButRejectsItAsTheNewBaseline() throws Exception {
        ContextArtifactStore observations = new ContextArtifactStore(directory.resolve("observations"));
        ContextTelemetryInterceptor telemetry = new ContextTelemetryInterceptor(observations, new ContextCacheConfiguration());
        Trace trace = new Trace(InMemoryAgentSession.of("s")); trace.memory.addMessage(ChatMessage.ofUser("request"));
        telemetry.onReasonStart(trace, new StringBuilder("rules"));
        String requestEpoch = ContextEpoch.current(trace);
        ContextEpoch.replace(trace, new ArrayList<>(trace.memory.getMessages()), true);
        assertNotEquals(requestEpoch, ContextEpoch.current(trace));
        org.noear.solon.ai.AiUsage usage = org.noear.solon.ai.AiUsage.builder().promptTokens(100).cacheReadInputTokens(50).completionTokens(10).build();
        telemetry.onReasonEnd(trace, response(usage), ChatMessage.ofAssistant("answer"), 20);
        assertNull(ContextEpoch.validUsage(trace));
        assertNull(trace.getExtra(ContextTelemetryInterceptor.LAST_SNAPSHOT));
        Map<String, Object> event = observations.observations("s", false, 10).get(0);
        assertEquals(requestEpoch, event.get("contextEpoch"));
        assertEquals(100, ((Number) ((Map<?, ?>) event.get("usage")).get("promptTokens")).intValue());
    }

    @Test
    void zeroUsageDoesNotBecomeACacheDecisionBaseline() {
        Trace trace = new Trace(InMemoryAgentSession.of("s")); trace.memory.addMessage(ChatMessage.ofUser("request"));
        ContextTelemetryInterceptor telemetry = new ContextTelemetryInterceptor(new ContextArtifactStore(directory), new ContextCacheConfiguration());
        telemetry.onReasonStart(trace, new StringBuilder("rules"));
        telemetry.onReasonEnd(trace, response(org.noear.solon.ai.AiUsage.builder().build()), ChatMessage.ofAssistant("answer"), 1);
        assertNull(ContextEpoch.validUsage(trace));
    }

    @Test
    void injectedReflectionDoesNotReplaceTheOriginalUserIntent() {
        Trace trace = new Trace(InMemoryAgentSession.of("s"));
        ChatMessage user = ChatMessage.ofUser("Preserve the auth API and verify expired tokens."); ContextMessageIds.ensure(user);
        trace.original = Prompt.of(Collections.singletonList(user));
        trace.memory.addMessage(user, ChatMessage.ofAssistant("early finding"), tool("recent evidence", "call"),
                ChatMessage.ofUser("System self-reflection: reconsider the current approach."));
        CurrentTurnIntent intent = CurrentTurnIntent.prepare(trace, 2);
        assertTrue(intent.split); assertEquals(user.getContent(), intent.request);
        assertTrue(intent.protect("facts").contains("verify expired tokens"));
        assertFalse(intent.protect("facts").contains("System self-reflection"));
    }

    @Test
    void batchProjectionUsesReasonEntryIdAfterObjectReplacementWithReusedWireIds() {
        ToolResultContextInterceptorTest.Trace trace = new ToolResultContextInterceptorTest.Trace();
        trace.reason = ToolResultContextInterceptorTest.assistant(new ToolCall("0", "reused", "grep", null, Collections.emptyMap()));
        ContextMessageIds.ensure(trace.reason);
        ChatMessage clonedReason = ChatMessage.fromJson(ChatMessage.toJson(trace.reason));
        String large = String.join("", Collections.nCopies(2000, "code line\n"));
        ToolMessage old = tool(large, "reused"), current = tool(large, "reused");
        trace.memory.addMessage(old, clonedReason, current);
        new ToolResultContextInterceptor(new ContextArtifactStore(directory), new ContextManagementOptions(30000, 2000, 20, 1000))
                .onActionEnd(trace, Collections.emptyList());
        assertSame(old, trace.memory.getMessages().get(0));
        assertTrue(trace.memory.getMessages().get(2).getContent().startsWith("<context-artifact"));
        assertEquals(ContextMessageIds.id(current), ContextMessageIds.id(trace.memory.getMessages().get(2)));
    }

    private static org.noear.solon.ai.chat.ChatResponse response(org.noear.solon.ai.AiUsage usage) {
        return (org.noear.solon.ai.chat.ChatResponse) java.lang.reflect.Proxy.newProxyInstance(
                org.noear.solon.ai.chat.ChatResponse.class.getClassLoader(), new Class<?>[]{org.noear.solon.ai.chat.ChatResponse.class},
                (proxy, method, args) -> "getUsage".equals(method.getName()) ? usage : null);
    }

    private CompactingFileAgentSession toolSession() {
        CompactingFileAgentSession session = new CompactingFileAgentSession("s", directory.toString());
        ToolCall call = new ToolCall("0", "call", "grep", null, Collections.emptyMap());
        session.addMessage(ChatMessage.ofUser("requirement"), ToolResultContextInterceptorTest.assistant(call),
                tool("ORIGINAL_TOOL_BODY", "call"), ChatMessage.ofAssistant("final"));
        return session;
    }

    private static ToolMessage tool(String text, String callId) { return ChatMessage.ofTool(ToolResult.success(text), "grep", callId, false); }
    private static List<ChatMessage> cloneMessages(List<ChatMessage> messages) {
        List<ChatMessage> result = new ArrayList<>(); for (ChatMessage message : messages) result.add(ChatMessage.fromJson(ChatMessage.toJson(message))); return result;
    }
    private static ReActOptions options(String name, String url) {
        ChatConfig config = new ChatConfig(); config.setName(name); config.setModel(name); config.setProvider("openai"); config.setApiUrl(url);
        return new ReActOptions(ChatModel.of(config).build());
    }
    private static class Trace extends ReActTrace {
        final AgentSession session;
        final Prompt memory;
        final List<FunctionTool> tools = new ArrayList<>();
        ReActOptions options = options("model", "http://127.0.0.1/unused");
        Prompt original;
        Trace(AgentSession session) { this.session = session; memory = Prompt.of(new ArrayList<>(session.getLatestMessages(200))); }
        @Override public AgentSession getSession() { return session; }
        @Override public Prompt getWorkingMemory() { return memory; }
        @Override public ReActOptions getOptions() { return options; }
        @Override public Collection<FunctionTool> getProtocolTools() { return tools; }
        @Override public Prompt getOriginalPrompt() { return original; }
    }
}
