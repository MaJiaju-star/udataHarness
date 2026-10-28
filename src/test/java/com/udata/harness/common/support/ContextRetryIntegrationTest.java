package com.udata.harness.common.support;

import com.udata.harness.common.context.CacheAlignedCompressionInterceptor;
import com.udata.harness.repository.CompactingFileAgentSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.talents.cli.SkillTalent;
import org.noear.solon.ai.talents.mount.catalog.SkillDescriptor;
import org.noear.snack4.ONode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static com.udata.harness.common.support.CacheTestGateway.*;
import static org.junit.jupiter.api.Assertions.*;

class ContextRetryIntegrationTest {
    @TempDir Path root;
    private static final String SUMMARY = "## Primary Request and Intent\n- Continue the user task.\n"
            + "## Confirmed Facts and User Constraints\n(none)\n## Files and Code\n(none)\n"
            + "## Errors and Failed Attempts\n(none)\n## Current Work and Pending Jobs\n- Verify the result.\n"
            + "## Next Step\n- Verify the current result.\n## Critical Context\n(none)";

    @Test void ordinaryRetriesFreezeProjectFilesAndCatalogsUntilNextInference() throws Throwable {
        for (boolean stream : Arrays.asList(false, true)) {
            write("PROJECT_ORIGINAL"); CapabilityCatalogInterceptorTest.Skills skills = skills("CATALOG_ORIGINAL");
            AtomicInteger calls = new AtomicInteger();
            try (CacheTestGateway gateway = new CacheTestGateway(body -> {
                if (calls.getAndIncrement() == 0) { write("PROJECT_CHANGED"); skills.entries.set(0, descriptor("CATALOG_CHANGED")); return error(503, "temporary outage"); }
                return answer("done");
            })) {
                HarnessEngine engine = engine(gateway, skills, true); AgentSession session = InMemoryAgentSession.of("retry-" + stream);
                call(engine, session, stream);
                assertEquals(2, gateway.requests.size()); assertEquals(gateway.requests.get(0), gateway.requests.get(1));
                assertTrue(gateway.requests.get(1).contains("PROJECT_ORIGINAL")); assertFalse(gateway.requests.get(1).contains("PROJECT_CHANGED"));
                assertTrue(catalog(gateway.requests.get(1), "skills").contains("CATALOG_ORIGINAL"));
                call(engine, session, stream);
                assertTrue(catalog(gateway.requests.get(2), "skills").contains("CATALOG_CHANGED"));
                assertTrue(gateway.requests.get(2).contains("PROJECT_CHANGED"));
            }
        }
    }

    @Test void http400OverflowCompactsAndRestoresFrozenCatalogsInSyncAndStreamAndAfterRestart() throws Throwable {
        for (boolean stream : Arrays.asList(false, true)) {
            write("PROJECT_ORIGINAL"); CapabilityCatalogInterceptorTest.Skills skills = skills("CATALOG_ORIGINAL");
            AtomicInteger ordinary = new AtomicInteger(), summaries = new AtomicInteger();
            try (CacheTestGateway gateway = new CacheTestGateway(body -> {
                if (body.contains("acting as a compaction engine")) { summaries.incrementAndGet(); return answer(SUMMARY); }
                if (ordinary.getAndIncrement() == 1) { write("PROJECT_CHANGED"); skills.entries.set(0, descriptor("CATALOG_CHANGED")); return error(400, "context_length_exceeded: maximum context length"); }
                return answer("done");
            })) {
                HarnessEngine engine = engine(gateway, skills, true);
                Path store = root.resolve("session-" + stream);
                CompactingFileAgentSession session = new CompactingFileAgentSession("overflow-" + stream, store.toString());
                call(engine, session, stream); fillHistory(session); call(engine, session, stream);
                assertEquals(1, summaries.get(), "400 context overflow must reach compression hooks");
                assertEquals(4, gateway.requests.size());
                String recovered = gateway.requests.get(3);
                assertTrue(recovered.contains("<compacted-summary>"));
                assertTrue(catalog(recovered, "skills").contains("CATALOG_ORIGINAL"));
                assertFalse(catalog(recovered, "skills").contains("CATALOG_CHANGED"));
                assertTrue(recovered.contains("PROJECT_ORIGINAL")); assertFalse(recovered.contains("PROJECT_CHANGED"));
                assertEquals(system(gateway.requests.get(1)), system(recovered));
                assertEquals(tools(gateway.requests.get(1)), tools(recovered));
                assertTrue(recovered.length() < gateway.requests.get(1).length());
                CompactingFileAgentSession reopened = new CompactingFileAgentSession("overflow-" + stream, store.toString());
                call(engine, reopened, stream);
                assertTrue(catalog(gateway.requests.get(4), "skills").contains("CATALOG_CHANGED"));
                assertTrue(gateway.requests.get(4).contains("<compacted-summary>"));
            }
        }
    }

    @Test void ordinary400ErrorsKeepSdkPolicyWithoutInvokingContextRecovery() throws Throwable {
        for (boolean stream : Arrays.asList(false, true)) {
            try (CacheTestGateway gateway = new CacheTestGateway(body -> error(400, "invalid tool schema"))) {
                HarnessEngine engine = engine(gateway, skills("CATALOG"), true);
                try { call(engine, InMemoryAgentSession.of("invalid-" + stream), stream); } catch (Throwable expected) { }
                // Sync SDK parsing loses HTTP status and retries ChatException; streaming retains the status.
                assertEquals(stream ? 1 : 2, gateway.requests.size());
                assertFalse(gateway.requests.stream().anyMatch(body -> body.contains("acting as a compaction engine")));
            }
        }
    }

    @Test void failedCompressionKeepsHistoryAndStopsAtConfiguredAttempts() throws Throwable {
        AtomicInteger ordinary = new AtomicInteger(), summaries = new AtomicInteger();
        try (CacheTestGateway gateway = new CacheTestGateway(body -> {
            if (body.contains("acting as a compaction engine")) { summaries.incrementAndGet(); return answer("invalid summary"); }
            ordinary.incrementAndGet(); return error(400, "context_length_exceeded");
        })) {
            HarnessEngine engine = engine(gateway, skills("CATALOG"), true);
            AgentSession session = InMemoryAgentSession.of("failed-compaction"); fillHistory(session);
            try { call(engine, session, false); } catch (Throwable expected) { }
            assertEquals(2, ordinary.get()); assertEquals(1, summaries.get());
            assertTrue(session.getMessages().stream().anyMatch(m -> m.getContent().contains("HISTORY_EVIDENCE")));
            assertFalse(gateway.requests.get(2).contains("<compacted-summary>"));
        }
    }

    @Test void partialStreamErrorsDoNotRestartAlreadyEmittedOutput() throws Throwable {
        try (CacheTestGateway gateway = new CacheTestGateway(body -> new Response(200,
                "data: {\"id\":\"partial\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"already emitted\"}}]}\n\n"
                        + "data: {\"error\":{\"message\":\"context_length_exceeded\",\"type\":\"invalid_request_error\"}}\n\n", true))) {
            HarnessEngine engine = engine(gateway, skills("CATALOG"), true);
            try { call(engine, InMemoryAgentSession.of("partial"), true); } catch (Throwable expected) { }
            assertEquals(1, gateway.requests.size());
        }
    }

    private HarnessEngine engine(CacheTestGateway gateway, CapabilityCatalogInterceptorTest.Skills skills, boolean compression) {
        HarnessEngine.Builder builder = HarnessEngine.of(root.toString(), ".soloncode").systemPrompt("Stable policy")
                .toolsAdd(Collections.emptyList()).sessionProvider(InMemoryAgentSession::of).modelAdd(gateway.config()).defaultModel("local").modelRetries(2).maxTurns(3)
                .extensionAdd((owner, name, agent) -> {
                    new WorkspaceInstructionsInterceptor(root).install(agent);
                    agent.defaultTalentAdd(new SkillTalent(skills));
                    agent.defaultInterceptorAdd(Integer.MIN_VALUE + 3, new ContextOverflowRetryInterceptor());
                    CapabilityCatalogInterceptor.install(agent); StableToolDefinitionsInterceptor.install(agent);
                });
        if (compression) builder.compressionInterceptor(new CacheAlignedCompressionInterceptor(1000, .99, .01, 1024));
        return builder.build();
    }
    private void call(HarnessEngine engine, AgentSession session, boolean stream) throws Throwable {
        if (stream) engine.prompt("continue the current task").session(session).options(o -> o.retryConfig(2, 1)).stream().blockLast();
        else engine.prompt("continue the current task").session(session).options(o -> o.retryConfig(2, 1)).call();
    }
    private void fillHistory(AgentSession session) {
        String evidence = "HISTORY_EVIDENCE " + String.join("", Collections.nCopies(300, "long source evidence "));
        for (int i = 0; i < 8; i++) { session.addMessage(ChatMessage.ofUser("old task " + i)); session.addMessage(ChatMessage.ofAssistant(evidence)); }
        session.updateSnapshot();
    }
    private void write(String text) throws Exception { Files.write(root.resolve("CLAUDE.md"), text.getBytes(StandardCharsets.UTF_8)); }
    private static SkillDescriptor descriptor(String text) { return new SkillDescriptor("worker", "worker", text, "1"); }
    private static CapabilityCatalogInterceptorTest.Skills skills(String text) {
        CapabilityCatalogInterceptorTest.Skills result = new CapabilityCatalogInterceptorTest.Skills(); result.entries.add(descriptor(text)); return result;
    }
}
