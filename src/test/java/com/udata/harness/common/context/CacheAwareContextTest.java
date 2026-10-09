package com.udata.harness.common.context;

import com.udata.harness.common.support.PersistentContextCompressionInterceptor;
import com.udata.harness.repository.ModelContextAgentSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.AiUsage;
import org.noear.solon.ai.agent.react.*;
import org.noear.solon.ai.agent.react.intercept.ContextCompressionInterceptor;
import org.noear.solon.ai.chat.*;
import org.noear.solon.ai.chat.interceptor.*;
import org.noear.solon.ai.chat.message.*;
import org.noear.solon.ai.chat.tool.*;
import org.noear.solon.ai.harness.agent.AgentDefinition;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class CacheAwareContextTest {
    @TempDir Path directory;
    private ChatResponse response(String text, String finish, AiUsage usage) {
        return (ChatResponse) Proxy.newProxyInstance(ChatResponse.class.getClassLoader(), new Class<?>[]{ChatResponse.class}, (p,m,a) -> {
            switch (m.getName()) {
                case "getUsage": return usage;
                case "getModel": return "test";
                case "getMessage": return ChatMessage.ofAssistant(text);
                case "getContent": case "getText": return text;
                case "getFinishReason": return finish;
                case "isTerminal": case "hasContent": return true;
                case "isEmpty": return false;
                case "getToolCalls": case "getBlocks": case "getEvents": return Collections.emptyList();
                default: return null;
            }
        });
    }
    private ChatModel model(long window, String provider, ChatInterceptor transport) {
        return ChatModel.of("http://127.0.0.1:1").provider(provider).model("test").contextLength(window)
                .defaultInterceptorAdd(transport).build();
    }
    private ModelContextAgentSession session(String id) { return new ModelContextAgentSession(id, directory.resolve(id).toString()); }
    private TestTrace trace(ChatModel model, ModelContextAgentSession session) { return new TestTrace(model, session); }
    private static class TestTrace extends ReActTrace {
        TestTrace(ChatModel model, ModelContextAgentSession session) {
            prepare(ReActAgent.of(model).name(AgentDefinition.AGENT_MAIN).build().getConfig(),
                    new ReActOptions(model), session, null, AgentDefinition.AGENT_MAIN);
        }
    }
    private static String repeat(String text, int count) { return String.join("", Collections.nCopies(count, text)); }
    private void seed(TestTrace trace, int count) {
        for (int i = 0; i < count; i++) trace.getWorkingMemory().addMessage(ChatMessage.ofAssistant("history " + i + repeat(" evidence", 80)));
        trace.getWorkingMemory().addMessage(ChatMessage.ofUser("Keep the public API and fix this bug"));
    }
    private void callReason(TestTrace trace, String system, AiUsage usage) throws Exception {
        ModelContextAgentSession session = (ModelContextAgentSession) trace.getSession();
        TokenPressureMeter.Measurement pressure = session.tokenPressure().measure(trace, system, trace.getWorkingMemory().getMessages(), .75);
        BudgetContextCompactor.bind(trace, pressure);
        List<ChatMessage> messages = new ArrayList<>(); messages.add(ChatMessage.ofSystem(system));
        messages.addAll(trace.getWorkingMemory().getMessages());
        // A real prepared request/interceptor chain, with only the transport mocked.
        trace.getOptions().getChatModel().prompt(messages).options(o -> {
            o.autoToolCall(false); o.toolAdd(trace.getOptions().getTools());
            o.toolContextPut(trace.getOptions().getToolContext());
            o.interceptorAdd(Integer.MIN_VALUE, new ModelCallObserver(session.contextMetrics(), "reason"));
        }).call();
    }

    @Test void summaryReusesPreparedSystemToolsAndChronologicalMessagePrefix() throws Exception {
        AtomicReference<List<ChatMessage>> sent = new AtomicReference<>();
        AtomicReference<String> tools = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        ChatModel model = model(100000, "openai", new ChatInterceptor() {
            @Override public void onPrepare(ChatSession session, ChatOptions options, org.noear.solon.ai.chat.prompt.Prompt prompt, StringBuilder system) {
                system.append("Stable default instruction");
            }
            @Override public ChatResponse interceptCall(ChatRequest req, CallChain chain) {
                if (calls.incrementAndGet() == 1) {
                    sent.set(TokenPressureMeter.copy(req.getFinalPrompt().getMessages()));
                    tools.set(TokenPressureMeter.toolSchema(req.getOptions().tools()));
                    return response("reason", "stop", new AiUsage(2000, 0, 10, 2010, null));
                }
                List<ChatMessage> actual = req.getFinalPrompt().getMessages();
                assertTrue(TokenPressureMeter.sameMessages(sent.get().subList(0, 5), actual.subList(0, 5)));
                assertEquals(tools.get(), TokenPressureMeter.toolSchema(req.getOptions().tools()));
                assertEquals(PrefixSummaryStrategy.INSTRUCTION, actual.get(actual.size() - 1).getContent());
                assertEquals("none", req.getOptions().options().get("tool_choice"));
                return response("Decisions, evidence and next step", "stop", new AiUsage(2000, 0, 20, 2020, null));
            }
        });
        ModelContextAgentSession session = session("prefix"); TestTrace trace = trace(model, session);
        trace.getOptions().getModelOptions().toolAdd(ContextTools.definitions().get(0));
        seed(trace, 12); callReason(trace, "Main system", null);
        trace.setExtra(TokenPressureMeter.SYSTEM, "Main system");
        List<ChatMessage> old = new ArrayList<>(trace.getWorkingMemory().getMessages().subList(0, 8));
        ChatMessage result = new PrefixSummaryStrategy().compress(new ObservedSummaryModel(model, session.contextMetrics()), 1, trace, old);
        assertTrue(result.getContent().startsWith("<compacted-summary>"));
        assertEquals(2, calls.get());
        assertEquals(1L, ((Number) ((Map<?,?>) session.contextMetrics().report().get("events")).get("summary_prefix")).longValue());
    }

    @Test void usageCalibrationCountsNormalizedCachedTokensOnceForOpenAiAndAnthropic() throws Exception {
        for (String provider : Arrays.asList("openai", "anthropic")) {
            AiUsage usage = new AiUsage(4000, 0, 200, 4200, 1000, 2000, null);
            ChatModel model = model(100000, provider, new ChatInterceptor() {
                @Override public ChatResponse interceptCall(ChatRequest req, CallChain chain) { return response("done", "stop", usage); }
            });
            ModelContextAgentSession session = session(provider); TestTrace trace = trace(model, session);
            trace.getWorkingMemory().addMessage(ChatMessage.ofUser("request"));
            TokenPressureMeter.Measurement before = session.tokenPressure().measure(trace, "system", trace.getWorkingMemory().getMessages(), .75);
            callReason(trace, "system", usage);
            TokenPressureMeter.Measurement after = session.tokenPressure().measure(trace, "system", trace.getWorkingMemory().getMessages(), .75);
            assertEquals("usage_calibrated", after.basis);
            assertEquals(4000, after.inputTokens);
            if (provider.equals("anthropic")) assertEquals(32000, after.frame.reservedOutput);
            trace.getWorkingMemory().addMessage(ChatMessage.ofAssistant("new result"));
            TokenPressureMeter.Measurement appended = session.tokenPressure().measure(trace, "system", trace.getWorkingMemory().getMessages(), .75);
            assertEquals(after.inputTokens + appended.frame.estimatedInput - before.frame.estimatedInput, appended.inputTokens);
            assertEquals("estimated", session.tokenPressure().measure(trace, "changed system", trace.getWorkingMemory().getMessages(), .75).basis);
            trace.getOptions().getChatModel().getConfig().switchModel("different");
            assertEquals("estimated", session.tokenPressure().measure(trace, "system", trace.getWorkingMemory().getMessages(), .75).basis);
        }
    }

    @Test void absentUsageAndRestartUseEstimateAndOutputReservationConstrainsThreshold() throws Exception {
        ChatModel model = model(20000, "openai", new ChatInterceptor() {
            @Override public ChatResponse interceptCall(ChatRequest req, CallChain chain) { return response("ok", "stop", null); }
        });
        ModelContextAgentSession session = session("unknown"); TestTrace trace = trace(model, session);
        trace.getWorkingMemory().addMessage(ChatMessage.ofUser("task")); callReason(trace, "system", null);
        assertEquals("estimated", session.tokenPressure().measure(trace, "system", trace.getWorkingMemory().getMessages(), .75).basis);
        trace.getOptions().getModelOptions().optionSet("max_tokens", 8000);
        TokenPressureMeter.Measurement measured = session.tokenPressure().measure(trace, "system", trace.getWorkingMemory().getMessages(), .75);
        assertEquals(11000, measured.threshold); assertEquals(8000, measured.frame.reservedOutput);
        assertEquals("estimated", session("unknown").tokenPressure().measure(trace, "system", trace.getWorkingMemory().getMessages(), .75).basis);
    }

    @Test void actualAnthropicDialectNormalizesCacheAndUsesItsDefaultOutputLimit() throws Exception {
        ChatModel model = model(100000, "anthropic", new ChatInterceptor() {
            @Override public ChatResponse interceptCall(ChatRequest req, CallChain chain) {
                assertEquals(32000, org.noear.snack4.ONode.ofJson(req.toRequestData()).get("max_tokens").getInt());
                ChatAccumulator accumulator = new ChatAccumulator(req, false);
                ChatConfig config = new ChatConfig(); config.setProvider("anthropic"); config.setModel("test");
                new org.noear.solon.ai.llm.dialect.anthropic.AnthropicChatDialect().parseResponseJson(
                        org.noear.solon.ai.chat.event.ChatStreamContextDefault.ofNoEmit(config, accumulator),
                        "{\"id\":\"fixture\",\"type\":\"message\",\"model\":\"test\",\"role\":\"assistant\","
                                + "\"content\":[{\"type\":\"text\",\"text\":\"done\"}],\"stop_reason\":\"end_turn\","
                                + "\"usage\":{\"input_tokens\":1000,\"output_tokens\":10,\"cache_creation_input_tokens\":1000,\"cache_read_input_tokens\":2000}}");
                assertEquals(4000, accumulator.getUsage().promptTokens());
                return accumulator.snapshotTerminal();
            }
        });
        ModelContextAgentSession session = session("anthropicDialect"); TestTrace trace = trace(model, session);
        trace.getWorkingMemory().addMessage(ChatMessage.ofUser("task")); callReason(trace, "system", null);
        TokenPressureMeter.Measurement pressure = session.tokenPressure().measure(trace, "system", trace.getWorkingMemory().getMessages(), .75);
        assertEquals("usage_calibrated", pressure.basis); assertEquals(4000, pressure.inputTokens);
        assertEquals(32000, pressure.frame.reservedOutput);
    }

    @Test void truncatedFilteredAndNonterminalSummariesAreRejected() {
        for (String finish : Arrays.asList("length", "max_tokens", "max-tokens", "content_filter", "tool", "error"))
            assertThrows(IllegalStateException.class, () -> PrefixSummaryStrategy.validateResponse(response("partial", finish, null)));
        assertThrows(IllegalStateException.class, () -> PrefixSummaryStrategy.validateResponse(response(" ", "stop", null)));
    }

    @Test void inflatedSummaryLeavesMemoryAndDurableContextIntact() {
        ChatModel model = model(2500, "openai", new ChatInterceptor() {});
        ModelContextAgentSession session = session("inflated"); TestTrace trace = trace(model, session); seed(trace, 30);
        session.addMessage(trace.getWorkingMemory().getMessages());
        session.saveModelContext(trace.getWorkingMemory().getMessages(), false);
        List<ChatMessage> before = TokenPressureMeter.copy(trace.getWorkingMemory().getMessages());
        PersistentContextCompressionInterceptor compactor = new PersistentContextCompressionInterceptor(1000, .75, 1, 2,
                (m,r,t,old) -> ChatMessage.ofUser(repeat("huge summary ", 10000))).contextManagement(6000, 12);
        assertThrows(IllegalStateException.class, () -> compactor.onReasonStart(trace, new StringBuilder("system")));
        assertTrue(TokenPressureMeter.sameMessages(before, trace.getWorkingMemory().getMessages()));
        assertTrue(TokenPressureMeter.sameMessages(before, session("inflated").getLatestMessages(200)));
        assertEquals(0L, session.contextReport().get("generation"));
    }

    @Test void lateMessageDuringSummaryIsNotOverwrittenByStaleCommitOrRollback() {
        ChatModel model = model(2500, "openai", new ChatInterceptor() {});
        ModelContextAgentSession session = session("stale"); TestTrace trace = trace(model, session); seed(trace, 30);
        session.addMessage(trace.getWorkingMemory().getMessages()); session.saveModelContext(trace.getWorkingMemory().getMessages(), false);
        ChatMessage late = ChatMessage.ofUser("A new requirement arrived during the summary");
        PersistentContextCompressionInterceptor compactor = new PersistentContextCompressionInterceptor(1000, .75, 1, 2, (m,r,t,old) -> {
            t.getWorkingMemory().addMessage(late); session.addMessage(late); return ChatMessage.ofUser("small summary");
        }).contextManagement(6000, 12);
        assertThrows(IllegalStateException.class, () -> compactor.onReasonStart(trace, new StringBuilder("system")));
        assertEquals(late, trace.getWorkingMemory().getMessages().get(31));
        assertEquals(late.getContent(), session("stale").getLatestMessages(200).get(31).getContent());
        assertEquals(0L, session.contextReport().get("generation"));
    }

    @Test void validBudgetSummaryPreservesLatestUserAndRestartAndLeavesHeadroom() {
        ChatModel model = model(5000, "openai", new ChatInterceptor() {});
        ModelContextAgentSession session = session("valid"); TestTrace trace = trace(model, session); seed(trace, 45);
        session.addMessage(trace.getWorkingMemory().getMessages());
        PersistentContextCompressionInterceptor compactor = new PersistentContextCompressionInterceptor(1000, .75, 1, 2,
                (m,r,t,old) -> ChatMessage.ofUser("Verified fix, API unchanged; run focused tests next")).contextManagement(6000, 12);
        compactor.onReasonStart(trace, new StringBuilder("system"));
        List<ChatMessage> after = trace.getWorkingMemory().getMessages();
        assertTrue(after.stream().anyMatch(m -> m.hasMetadata(ContextCompressionInterceptor.META_COMPRESSED)));
        assertEquals("Keep the public API and fix this bug", after.get(after.size() - 1).getContent());
        assertTrue(TokenPressureMeter.sameMessages(after, session("valid").getLatestMessages(200)));
        TokenPressureMeter.Measurement measured = session.tokenPressure().measure(trace, "system", after, .75);
        assertTrue(measured.inputTokens <= measured.threshold * 4 / 5);
        assertEquals(46, session.getMessages().size()); assertEquals(1L, session.contextReport().get("generation"));
    }

    @Test void guardRejectsConcurrentModelViewAndRequirementRevision() {
        ChatModel model = model(100000, "openai", new ChatInterceptor() {});
        ModelContextAgentSession session = session("guard"); TestTrace trace = trace(model, session); seed(trace, 5);
        SummaryCommitGuard first = new SummaryCommitGuard(trace);
        session.saveModelContext(trace.getWorkingMemory().getMessages(), false);
        assertThrows(IllegalStateException.class, first::check);
        SummaryCommitGuard second = new SummaryCommitGuard(trace);
        session.taskState().update(Collections.singletonMap("objective", "new task state"));
        assertThrows(IllegalStateException.class, second::check);
    }

    @Test void actualReactLoopUsesProductionSummaryStrategyAndRestoresItsCommittedView() throws Throwable {
        AtomicInteger steps = new AtomicInteger(), summaries = new AtomicInteger(), reads = new AtomicInteger();
        ChatModel model = model(5000, "openai", new ChatInterceptor() {
            @Override public ChatResponse interceptCall(ChatRequest req, CallChain chain) {
                if (PrefixSummaryStrategy.INSTRUCTION.equals(req.getFinalPrompt().getLastMessage().getContent())) {
                    summaries.incrementAndGet();
                    return response("API preserved; inspected source; continue remaining reads and verify tests", "stop", new AiUsage(2000, 0, 40, 2040, null));
                }
                int step = steps.getAndIncrement();
                if (step >= 25) return response("Completed investigation; tests still need verification", "stop", new AiUsage(2000, 0, 20, 2020, null));
                AssistantMessage toolCall = new AssistantMessage("", null, false, null, null,
                        Collections.singletonList(new ToolCall("0", "call-" + step, "read", "{}", Collections.emptyMap())), null);
                return (ChatResponse) Proxy.newProxyInstance(ChatResponse.class.getClassLoader(), new Class<?>[]{ChatResponse.class}, (p,m,a) -> {
                    switch (m.getName()) {
                        case "getMessage": return toolCall;
                        case "getToolCalls": return toolCall.getToolCalls();
                        case "getModel": return "test";
                        case "getUsage": return new AiUsage(2000, 0, 30, 2030, null);
                        case "getFinishReason": return "tool";
                        case "isTerminal": return true;
                        case "hasContent": case "isEmpty": return false;
                        case "getBlocks": case "getEvents": return Collections.emptyList();
                        default: return null;
                    }
                });
            }
        });
        FunctionTool read = new FunctionTool() {
            @Override public String name() { return "read"; }
            @Override public String title() { return "read"; }
            @Override public String description() { return "Read a source excerpt"; }
            @Override public boolean returnDirect() { return false; }
            @Override public java.lang.reflect.Type returnType() { return String.class; }
            @Override public String inputSchema() { return "{\"type\":\"object\"}"; }
            @Override public Object handle(Map<String,Object> args) { return "file version " + reads.incrementAndGet() + repeat(" source evidence", 60); }
        };
        ModelContextAgentSession session = session("react");
        PersistentContextCompressionInterceptor compactor = new PersistentContextCompressionInterceptor(1000, .75, 1, 2,
                new PrefixSummaryStrategy()).contextManagement(6000, 12);
        ReActAgent.of(model).name(AgentDefinition.AGENT_MAIN).sessionWindowSize(200).maxTurns(40)
                .defaultToolAdd(read).defaultInterceptorAdd(compactor)
                .defaultInterceptorAdd(Integer.MIN_VALUE, new ModelCallObserver()).build()
                .prompt("Investigate the source while preserving the public API").session(session).call();
        assertEquals(25, reads.get()); assertTrue(summaries.get() > 0);
        List<ChatMessage> recovered = session("react").getLatestMessages(200);
        assertTrue(recovered.stream().anyMatch(m -> m.hasMetadata(ContextCompressionInterceptor.META_COMPRESSED)));
        assertTrue(recovered.stream().anyMatch(m -> "Investigate the source while preserving the public API".equals(m.getContent())));
        assertTrue(session.getMessages().size() > recovered.size());
        ContextProtection.validateToolGroups(session.getMessages(), recovered);
        Map<?,?> buckets = (Map<?,?>) session.contextMetrics().report().get("providerUsageByModelAndPurpose");
        assertEquals(summaries.get(), ((Number) ((Map<?,?>) buckets.get("openai/test/summary")).get("calls")).intValue());
        assertEquals(26, ((Number) ((Map<?,?>) buckets.get("openai/test/reason")).get("calls")).intValue());
    }

    @Test void clearedSessionIgnoresInFlightUsageAnchor() throws Exception {
        ModelContextAgentSession session = session("clearAnchor");
        AtomicReference<TokenPressureMeter.Frame> captured = new AtomicReference<>();
        ChatModel model = model(100000, "openai", new ChatInterceptor() {
            @Override public ChatResponse interceptCall(ChatRequest req, CallChain chain) {
                captured.set((TokenPressureMeter.Frame) req.getOptions().toolContext().get(TokenPressureMeter.FRAME));
                session.clear();
                return response("old response", "stop", new AiUsage(10000, 0, 20, 10020, null));
            }
        });
        TestTrace trace = trace(model, session); callReason(trace, "system", null);
        assertNotNull(captured.get());
        assertEquals("estimated", session.tokenPressure().measure(trace, "system", trace.getWorkingMemory().getMessages(), .75).basis);
    }

    @Test void cancelledSummaryCannotCommit() {
        ChatModel model = model(100000, "openai", new ChatInterceptor() {});
        SummaryCommitGuard guard = new SummaryCommitGuard(trace(model, session("cancelled")));
        Thread.currentThread().interrupt();
        try { assertThrows(IllegalStateException.class, guard::check); }
        finally { Thread.interrupted(); }
    }
    @Test void messagePressureCountsTextOnceAndIgnoresPersistenceMetadataAndTimestamp() {
        ChatMessage first = ChatMessage.ofUser(repeat("source code ", 100));
        ChatMessage second = ChatMessage.ofUser(first.getContent());
        second.addMetadata("large_internal_field", repeat("not sent ", 1000));
        assertTrue(TokenPressureMeter.sameMessages(Collections.singletonList(first), Collections.singletonList(second)));
        assertEquals(TokenPressureMeter.textTokens(first.getContent()) + 7,
                TokenPressureMeter.messagesTokens(Collections.singletonList(first)));
        assertEquals(TokenPressureMeter.messagesTokens(Collections.singletonList(first)),
                TokenPressureMeter.messagesTokens(Collections.singletonList(second)));
    }
}
