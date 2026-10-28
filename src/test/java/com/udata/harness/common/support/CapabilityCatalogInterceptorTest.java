package com.udata.harness.common.support;

import com.udata.harness.common.context.ContextMessageIds;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActInterceptor;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.talent.Talent;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.FunctionToolDesc;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.agent.AgentDefinition;
import org.noear.solon.ai.mcp.client.McpClientProvider;
import org.noear.solon.ai.talents.cli.SkillTalent;
import org.noear.solon.ai.talents.gateway.McpGatewayTalent;
import org.noear.solon.ai.talents.mount.catalog.*;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import static com.udata.harness.common.support.CacheTestGateway.*;
import static org.junit.jupiter.api.Assertions.*;

class CapabilityCatalogInterceptorTest {
    @TempDir Path root;

    @Test void agentCatalogChangesAppendWithoutChangingSystemOrToolDefinitions() throws Throwable {
        try (CacheTestGateway gateway = new CacheTestGateway(body -> answer("done"))) {
            HarnessEngine engine = engine(gateway, null, null);
            AgentSession session = InMemoryAgentSession.of("agents");
            engine.getAgentManager().addAgent(agent("worker", "AGENT_ALPHA"));
            call(engine, session, false); call(engine, session, true);
            engine.getAgentManager().addAgent(agent("worker", "AGENT_BETA"));
            call(engine, session, false);
            assertTrue(catalog(gateway.requests.get(0), "subagents").contains("AGENT_ALPHA"));
            assertTrue(catalog(gateway.requests.get(2), "subagents").contains("AGENT_BETA"));
            assertFalse(catalog(gateway.requests.get(2), "subagents").contains("AGENT_ALPHA"));
            assertEquals(2, catalogs(session, "subagents").size());
            for (String request : gateway.requests) {
                assertEquals(system(gateway.requests.get(0)), system(request));
                assertEquals(tools(gateway.requests.get(0)), tools(request));
                assertFalse(system(request).contains("AGENT_ALPHA")); assertFalse(system(request).contains("AGENT_BETA"));
            }
            List<org.noear.snack4.ONode> before = org.noear.snack4.ONode.ofJson(gateway.requests.get(1)).get("messages").getArray();
            List<org.noear.snack4.ONode> after = org.noear.snack4.ONode.ofJson(gateway.requests.get(2)).get("messages").getArray();
            for (int i = 0; i < before.size(); i++) assertEquals(before.get(i).toJson(), after.get(i).toJson());
        }
    }

    @Test void skillsAreSortedFilteredAndUpdatedWithoutSystemChurnAndRemovedOnDisable() throws Throwable {
        Skills skills = new Skills(); skills.entries.add(descriptor("zeta", "SKILL_Z")); skills.entries.add(descriptor("alpha", "SKILL_A"));
        skills.entries.add(descriptor("hidden", "SECRET_SKILL"));
        SkillTalent talent = new SkillTalent(skills);
        try (CacheTestGateway gateway = new CacheTestGateway(body -> answer("done"))) {
            HarnessEngine engine = engine(gateway, talent, null); AgentSession session = InMemoryAgentSession.of("skills");
            call(engine, session, false);
            Collections.reverse(skills.entries); call(engine, session, true);
            assertEquals(1, catalogs(session, "skills").size(), "Reordered discovery results must not create a new snapshot");
            skills.entries.set(skills.entries.indexOf(skills.getDescriptor("alpha")), descriptor("alpha", "SKILL_A_CHANGED"));
            call(engine, session, false);
            String initial = catalog(gateway.requests.get(0), "skills");
            assertTrue(initial.indexOf("SKILL_A") < initial.indexOf("SKILL_Z")); assertFalse(initial.contains("SECRET_SKILL"));
            assertTrue(catalog(gateway.requests.get(2), "skills").contains("SKILL_A_CHANGED"));
            assertEquals(system(gateway.requests.get(0)), system(gateway.requests.get(2)));
            assertEquals(tools(gateway.requests.get(0)), tools(gateway.requests.get(2)));
            assertFalse(system(gateway.requests.get(2)).contains("SKILL_A"));
            talent.setEnabled(false); call(engine, session, false);
            assertTrue(catalog(gateway.requests.get(3), "skills").contains("disabled or unavailable"));
            assertFalse(tools(gateway.requests.get(3)).contains("skillread"));
        }
    }

    @Test void nativeMcpDirectoryIsStableAndRemovalStillInvalidatesItsTools() throws Throwable {
        McpGatewayTalent mcp = new McpGatewayTalent().dynamicThreshold(0);
        FakeProvider z = new FakeProvider("z_tool", "MCP_Z"), a = new FakeProvider("a_tool", "MCP_A");
        mcp.addMcpServer("z_server", z).addMcpServer("a_server", a);
        try (CacheTestGateway gateway = new CacheTestGateway(body -> answer("done"))) {
            HarnessEngine engine = engine(gateway, mcp, null); AgentSession session = InMemoryAgentSession.of("mcp");
            call(engine, session, false);
            mcp.removeMcpServer("z_server"); call(engine, session, true);
            assertEquals(system(gateway.requests.get(0)), system(gateway.requests.get(1)));
            assertEquals(tools(gateway.requests.get(0)), tools(gateway.requests.get(1)));
            assertFalse(system(gateway.requests.get(0)).contains("MCP_Z"));
            assertTrue(catalog(gateway.requests.get(0), "mcp").indexOf("a_server") < catalog(gateway.requests.get(0), "mcp").indexOf("z_server"));
            assertFalse(catalog(gateway.requests.get(1), "mcp").contains("MCP_Z"));
            assertTrue(mcp.getToolDetail("z_tool").contains("未找到"));
            mcp.removeMcpServer("a_server"); call(engine, session, false);
            assertTrue(catalog(gateway.requests.get(2), "mcp").contains("disabled or unavailable"));
            assertFalse(tools(gateway.requests.get(2)).contains("call_tool"));
        }
    }

    @Test void clippedCatalogIsRestoredAndSessionSnapshotsRemainIndependent() throws Throwable {
        ReActInterceptor clip = new ReActInterceptor() {
            @Override public void onReasonStart(ReActTrace trace, StringBuilder system) {
                List<ChatMessage> messages = new ArrayList<>();
                for (ChatMessage message : trace.getWorkingMemory().getMessages()) {
                    if ("subagents".equals(message.getMetadataAs(CapabilityCatalogInterceptor.MARKER))) {
                        ChatMessage copy = ChatMessage.ofUser("clipped"); copy.addMetadata(message.getMetadata()); messages.add(copy);
                    } else messages.add(message);
                }
                trace.getWorkingMemory().replaceMessages(messages);
            }
        };
        try (CacheTestGateway gateway = new CacheTestGateway(body -> answer("done"))) {
            HarnessEngine engine = engine(gateway, null, clip); engine.getAgentManager().addAgent(agent("worker", "CURRENT_AGENT"));
            AgentSession one = InMemoryAgentSession.of("one"), two = InMemoryAgentSession.of("two");
            call(engine, one, false); call(engine, one, false); call(engine, two, true);
            assertEquals(2, catalogs(one, "subagents").size()); assertEquals(1, catalogs(two, "subagents").size());
            assertTrue(catalog(gateway.requests.get(1), "subagents").contains("CURRENT_AGENT"));
            for (ChatMessage context : catalogs(one, "subagents")) assertTrue(ContextMessageIds.internal(context));
        }
    }

    @Test void laterSameNameToolOverrideStillExecutesAfterCatalogTalentIsDisabled() throws Throwable {
        Skills skills = new Skills(); skills.entries.add(descriptor("alpha", "SKILL_A"));
        SkillTalent talent = new SkillTalent(skills);
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        try (CacheTestGateway gateway = new CacheTestGateway(body -> {
            if (calls.getAndIncrement() % 2 == 0) return new Response(200,
                    "{\"role\":\"assistant\",\"tool_calls\":[{\"id\":\"override\",\"type\":\"function\",\"function\":{\"name\":\"skillread\",\"arguments\":\"{}\"}}]}", false);
            return answer("done");
        })) {
            HarnessEngine engine = engine(gateway, talent, null, new SkillOverride());
            AgentSession session = InMemoryAgentSession.of("override");
            call(engine, session, false); talent.setEnabled(false); call(engine, session, true);
            assertEquals(4, gateway.requests.size());
            assertTrue(gateway.requests.get(1).contains("SAFE_SKILL_READ_RESULT"));
            assertTrue(gateway.requests.get(3).contains("SAFE_SKILL_READ_RESULT"));
            assertTrue(tools(gateway.requests.get(2)).contains("safe skill override"));
        }
    }

    @Test void skillRefreshUpdatesTheNextToolLoopCatalogAndNativeDiscoveryTier() throws Throwable {
        Skills skills = new Skills() {
            @Override public void refresh() { for (int i = 0; i < 31; i++) entries.add(descriptor("new-" + i, "DISCOVERED_SKILL_" + i)); }
        };
        skills.entries.add(descriptor("alpha", "SKILL_A"));
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        try (CacheTestGateway gateway = new CacheTestGateway(body -> {
            if (calls.getAndIncrement() == 0) return new Response(200,
                    "{\"role\":\"assistant\",\"tool_calls\":[{\"id\":\"refresh\",\"type\":\"function\",\"function\":{\"name\":\"skillrefresh\",\"arguments\":\"{}\"}}]}", false);
            return answer("done");
        })) {
            HarnessEngine engine = engine(gateway, new SkillTalent(skills), null);
            AgentSession session = InMemoryAgentSession.of("refresh"); call(engine, session, false);
            assertEquals(2, gateway.requests.size());
            assertFalse(tools(gateway.requests.get(0)).contains("skilllist"));
            assertTrue(tools(gateway.requests.get(1)).contains("skilllist"));
            assertTrue(catalog(gateway.requests.get(1), "skills").contains("new-30"));
            assertEquals(2, catalogs(session, "skills").size());
        }
    }

    public static class SkillOverride extends org.noear.solon.ai.chat.talent.AbsTalent {
        @org.noear.solon.ai.annotation.ToolMapping(name = "skillread", description = "safe skill override")
        public String read() { return "SAFE_SKILL_READ_RESULT"; }
    }

    private HarnessEngine engine(CacheTestGateway gateway, Talent talent, ReActInterceptor rewrite) {
        return engine(gateway, talent, rewrite, null);
    }
    private HarnessEngine engine(CacheTestGateway gateway, Talent talent, ReActInterceptor rewrite, Talent override) {
        return HarnessEngine.of(root.toString(), ".soloncode").systemPrompt("Stable policy").toolsAdd(Arrays.asList("task", "multitask", "skillread", "skillrefresh"))
                .sessionProvider(InMemoryAgentSession::of).subagentEnabled(true).modelAdd(gateway.config()).defaultModel("local").modelRetries(2).maxTurns(3)
                .extensionAdd((owner, name, agent) -> {
                    if (talent != null) agent.defaultTalentAdd(talent);
                    if (override != null) agent.defaultTalentAdd(override);
                    if (rewrite != null) agent.defaultInterceptorAdd(1000, rewrite);
                    CapabilityCatalogInterceptor.install(agent); StableToolDefinitionsInterceptor.install(agent);
                }).build();
    }
    private void call(HarnessEngine engine, AgentSession session, boolean stream) throws Throwable {
        if (stream) engine.prompt("continue").session(session).stream().blockLast(); else engine.prompt("continue").session(session).call();
    }
    private static AgentDefinition agent(String name, String description) { return AgentDefinition.builder().name(name).description(description).systemPrompt("work").build(); }
    private static SkillDescriptor descriptor(String name, String description) { return new SkillDescriptor(name, name, description, "1"); }
    private static List<ChatMessage> catalogs(AgentSession session, String kind) {
        return session.getMessages().stream().filter(message -> kind.equals(message.getMetadataAs(CapabilityCatalogInterceptor.MARKER))).collect(Collectors.toList());
    }
    static class FakeProvider extends McpClientProvider {
        final List<FunctionTool> tools;
        FakeProvider(String name, String description) { super(properties()); tools = Collections.singletonList(new FunctionToolDesc(name).description(description)); }
        private static Properties properties() { Properties props = new Properties(); props.setProperty("channel", "sse"); props.setProperty("url", "http://127.0.0.1:1/sse"); return props; }
        @Override public Collection<FunctionTool> getToolsActivated() { return tools; }
        @Override public void close() { }
    }
    static class Skills implements SkillCatalog {
        final List<SkillDescriptor> entries = new ArrayList<>();
        @Override public void refresh() { }
        @Override public int getSkillCount() { return (int) entries.stream().filter(this::isAllowed).count(); }
        @Override public Collection<SkillDescriptor> getDescriptors() { return new ArrayList<>(entries); }
        @Override public Collection<SkillDescriptor> searchDescriptors(String query) { return entries; }
        @Override public SkillDescriptor getDescriptor(String name) { return entries.stream().filter(s -> name.equals(s.getId())).findFirst().orElse(null); }
        @Override public SkillContent readContent(String name) { return null; }
        @Override public boolean isAllowed(SkillDescriptor descriptor) { return !"hidden".equals(descriptor.getId()); }
    }
}
