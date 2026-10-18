package com.udata.harness.service.impl;

import com.udata.harness.repository.SessionRepository;
import com.udata.harness.common.context.ToolResultContextInterceptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.Utils;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.talents.lsp.LspServerParameters;
import org.noear.solon.core.Props;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserHarnessEngineServiceImplTest {
    @TempDir
    Path tempDir;

    @Test
    void buildsEngineBeforeFirstModelRequest() throws Exception {
        UserHarnessEngineServiceImpl service = new UserHarnessEngineServiceImpl();
        inject(service, "sessionRepository", new SessionRepository(tempDir.resolve("data")));
        inject(service, "workspaces", new UserWorkspaceServiceImpl(tempDir.resolve("workspace")));
        inject(service, "dataDir", tempDir.resolve("data").toString());
        inject(service, "harnessHome", ".soloncode");
        inject(service, "systemPrompt", "Test agent");
        inject(service, "maxTurns", 10);
        inject(service, "sessionWindowSize", 20);
        inject(service, "compressionMaxMessages", 20);
        inject(service, "compressionMaxContextRatio", 0.75D);
        inject(service, "modelContextLength", 20000L);
        inject(service, "modelMaxAttempts", 4);
        inject(service, "modelName", "deepseek");
        inject(service, "apiUrl", "https://api.deepseek.com");
        inject(service, "apiKey", "");
        inject(service, "provider", "openai");
        inject(service, "contextCacheConfiguration", new com.udata.harness.common.context.ContextCacheConfiguration());
        inject(service, "model", "deepseek-v4-flash");

        assertNotNull(service.get("alice"));
        org.junit.jupiter.api.Assertions.assertNull(service.get("alice").getMainAgent().getConfig().getDefaultOptions().getTool("context_restore"));
        org.junit.jupiter.api.Assertions.assertNull(service.get("alice").getMainAgent().getConfig().getDefaultOptions().getTool("context_search"));
        org.junit.jupiter.api.Assertions.assertNull(service.get("alice").getMainAgent().getConfig().getDefaultOptions().getTool("context_task_update"));
        org.junit.jupiter.api.Assertions.assertNull(service.get("alice").getMainAgent().getConfig().getDefaultOptions().getTool("context_task_snapshot"));
        org.junit.jupiter.api.Assertions.assertTrue(service.get("alice").getMainAgent().getConfig()
                .getDefaultOptions().getInterceptors().stream()
                .anyMatch(item -> item.target instanceof ToolResultContextInterceptor));
        org.junit.jupiter.api.Assertions.assertTrue(service.get("alice").getMainAgent().getConfig()
                .getDefaultOptions().getInterceptors().stream()
                .anyMatch(item -> item.target instanceof com.udata.harness.common.context.ContextTelemetryInterceptor));
        assertEquals(4, service.get("alice").getModelRetries());
        org.noear.solon.ai.harness.HarnessEngine original = service.get("alice");
        UserWorkspaceServiceImpl workspaces = (UserWorkspaceServiceImpl) read(service, "workspaces");
        String workspaceId = workspaces.register("alice",
                java.nio.file.Files.createDirectories(tempDir.resolve("idea-project")).toString(), false).getWorkspaceId();
        org.noear.solon.ai.harness.HarnessEngine idea = service.get("alice", workspaceId);
        org.junit.jupiter.api.Assertions.assertNotSame(original, idea);
        org.junit.jupiter.api.Assertions.assertSame(original, service.get("alice"));
        workspaces.activate("alice", workspaceId);
        org.junit.jupiter.api.Assertions.assertSame(idea, service.get("alice"));
        org.junit.jupiter.api.Assertions.assertSame(original, service.get("alice", "default"));
    }

    @Test
    void loadsConfiguredModelList() throws Exception {
        UserHarnessEngineServiceImpl service = configuredService();
        Props props = new Props();
        props.setProperty("agent.model.models[0].name", "deepseek-flash");
        props.setProperty("agent.model.models[0].model", "deepseek-v4-flash");
        props.setProperty("agent.model.models[1].name", "deepseek-pro");
        props.setProperty("agent.model.models[1].model", "deepseek-v4-pro");

        List<ChatConfig> models = service.loadModelConfigs(props);

        assertEquals(2, models.size());
        assertEquals("deepseek-flash", models.get(0).getNameOrModel());
        assertEquals("deepseek-v4-pro", models.get(1).getModel());
    }

    @Test
    void canDisableContextResultManagement() throws Exception {
        UserHarnessEngineServiceImpl service = configuredService();
        inject(service, "sessionRepository", new SessionRepository(tempDir.resolve("data")));
        inject(service, "workspaces", new UserWorkspaceServiceImpl(tempDir.resolve("workspace")));
        inject(service, "dataDir", tempDir.resolve("data").toString());
        inject(service, "harnessHome", ".soloncode");
        inject(service, "contextToolResultsEnabled", false);
        org.noear.solon.ai.agent.react.ReActOptions options = service.get("alice")
                .getMainAgent().getConfig().getDefaultOptions();
        org.junit.jupiter.api.Assertions.assertNull(options.getTool("context_restore"));
        org.junit.jupiter.api.Assertions.assertNull(options.getTool("context_search"));
        org.junit.jupiter.api.Assertions.assertNull(options.getTool("context_task_update"));
        assertFalse(options.getInterceptors().stream()
                .anyMatch(item -> item.target instanceof ToolResultContextInterceptor));
    }

    @Test
    void disablesAllWebToolsWithMasterSwitch() throws Exception {
        UserHarnessEngineServiceImpl service = configuredService();
        inject(service, "webToolsEnabled", false);

        assertEquals(Utils.asList("websearch", "codesearch", "webfetch"), service.disabledWebTools());
    }

    @Test
    void disablesIndividualWebTool() throws Exception {
        UserHarnessEngineServiceImpl service = configuredService();
        inject(service, "codeSearchEnabled", false);

        assertEquals(Utils.asList("codesearch"), service.disabledWebTools());
    }

    @Test
    void optionalToolsAreHiddenByDefaultAndCanBeReenabled() throws Exception {
        UserHarnessEngineServiceImpl disabled = runnableService();
        org.noear.solon.ai.harness.HarnessEngine defaultEngine = disabled.get("alice");
        org.noear.solon.ai.agent.react.ReActOptions defaultOptions = defaultEngine.getMainAgent()
                .getConfig().getDefaultOptions();
        for (String name : Utils.asList("render_echart", "render_antv_chart", "bash_start",
                "bash_wait", "bash_stop", "todoread", "todowrite", "task", "multitask", "lsp", "now")) {
            assertFalse(toolNames(defaultOptions).contains(name), name);
        }
        for (String name : Utils.asList("read", "write", "edit", "bash", "ls", "glob", "grep")) {
            assertTrue(toolNames(defaultOptions).contains(name), name);
        }
        assertFalse(defaultEngine.isBashAsyncEnabled());
        assertFalse(defaultEngine.isSubagentEnabled());
        assertTrue(disabled.disabledOptionalTools().contains("lsp"));
        assertTrue(disabled.disabledOptionalTools().contains("todo"));

        UserHarnessEngineServiceImpl enabled = runnableService();
        for (String field : Utils.asList("renderEchartEnabled", "renderAntvChartEnabled",
                "bashStartEnabled", "bashWaitEnabled", "bashStopEnabled", "lspToolEnabled",
                "todoReadEnabled", "todoWriteEnabled", "taskToolEnabled", "nowToolEnabled")) {
            inject(enabled, field, true);
        }
        org.noear.solon.ai.harness.HarnessEngine enabledEngine = enabled.get("alice", "default", "super");
        org.noear.solon.ai.agent.react.ReActOptions enabledOptions = enabledEngine.getMainAgent()
                .getConfig().getDefaultOptions();
        for (String name : Utils.asList("render_echart", "render_antv_chart",
                "bash_start", "bash_wait", "bash_stop",
                "todoread", "todowrite", "task", "multitask", "lsp", "now")) {
            assertTrue(toolNames(enabledOptions).contains(name), name);
        }
        assertTrue(enabledEngine.isBashAsyncEnabled());
        org.junit.jupiter.api.Assertions.assertTrue(enabledEngine.isSubagentEnabled());
        assertTrue(enabled.disabledOptionalTools().isEmpty());
        java.util.Set<String> restricted = toolNames(enabled.get("alice", "default", "economy")
                .getMainAgent().getConfig().getDefaultOptions());
        for (String name : Utils.asList("verify", "now", "lsp", "task", "bash_start", "render_echart")) {
            assertFalse(restricted.contains(name), name);
        }
    }

    @Test
    void todoSwitchesCanExposeOnlyOneTool() throws Exception {
        UserHarnessEngineServiceImpl service = runnableService();
        inject(service, "todoReadEnabled", true);
        org.noear.solon.ai.agent.react.ReActOptions options = service.get("alice", "default", "super")
                .getMainAgent().getConfig().getDefaultOptions();
        assertTrue(toolNames(options).contains("todoread"));
        assertFalse(toolNames(options).contains("todowrite"));

        UserHarnessEngineServiceImpl writeOnly = runnableService();
        inject(writeOnly, "todoWriteEnabled", true);
        org.noear.solon.ai.agent.react.ReActOptions writeOptions = writeOnly.get("alice", "default", "super")
                .getMainAgent().getConfig().getDefaultOptions();
        assertTrue(toolNames(writeOptions).contains("todowrite"));
        assertFalse(toolNames(writeOptions).contains("todoread"));
    }

    @Test
    void capabilityModesExposeOnlyTheirFileTools() throws Exception {
        UserHarnessEngineServiceImpl service = runnableService();
        org.noear.solon.ai.harness.HarnessEngine economyEngine = service.get("alice", "default", "economy");
        org.noear.solon.ai.harness.HarnessEngine standardEngine = service.get("alice", "default", "standard");
        org.noear.solon.ai.agent.react.ReActOptions economyOptions = economyEngine.getMainAgent()
                .getConfig().getDefaultOptions();
        org.noear.solon.ai.agent.react.ReActOptions standardOptions = standardEngine.getMainAgent()
                .getConfig().getDefaultOptions();
        java.util.Set<String> economy = toolNames(economyOptions);
        java.util.Set<String> standard = toolNames(standardOptions);
        assertTrue(economyOptions.getInterceptors().stream()
                .anyMatch(item -> item.target == economyEngine.getHitlInterceptor()));
        assertTrue(standardOptions.getInterceptors().stream()
                .anyMatch(item -> item.target == standardEngine.getHitlInterceptor()));
        for (String name : Utils.asList("read", "write", "edit", "bash", "ls")) {
            assertTrue(economy.contains(name), name);
            assertTrue(standard.contains(name), name);
        }
        for (String name : Utils.asList("glob", "grep")) {
            assertFalse(economy.contains(name), name);
            assertTrue(standard.contains(name), name);
        }
        for (String name : economy) assertTrue(Utils.asList("read", "write", "edit", "bash", "ls").contains(name), name + " in " + economy);
        for (String name : standard) assertTrue(Utils.asList("read", "write", "edit", "bash", "ls", "glob", "grep").contains(name), name + " in " + standard);
    }

    @Test
    void persistsSandboxPreferenceForUser() throws Exception {
        UserHarnessEngineServiceImpl service = configuredService();
        inject(service, "dataDir", tempDir.resolve("data").toString());
        service.setSandboxEnabled("alice", false);

        UserHarnessEngineServiceImpl reloaded = configuredService();
        inject(reloaded, "dataDir", tempDir.resolve("data").toString());

        assertFalse(reloaded.isSandboxEnabled("alice"));
    }

    @Test
    void registersLspForExistingAndFutureWorkspaces() throws Exception {
        UserHarnessEngineServiceImpl service = configuredService();
        UserWorkspaceServiceImpl workspaces = new UserWorkspaceServiceImpl(tempDir.resolve("workspace"));
        inject(service, "sessionRepository", new SessionRepository(tempDir.resolve("data")));
        inject(service, "workspaces", workspaces);
        inject(service, "dataDir", tempDir.resolve("data").toString());
        inject(service, "harnessHome", ".soloncode");

        org.noear.solon.ai.harness.HarnessEngine first = service.get("alice");
        LspServerParameters javaServer = new LspServerParameters(
                Utils.asList("jdtls"), Utils.asList(".java"));
        service.putLsp("java", javaServer);

        String workspaceId = workspaces.register("alice",
                java.nio.file.Files.createDirectories(tempDir.resolve("project-b")).toString(),
                false).getWorkspaceId();
        org.noear.solon.ai.harness.HarnessEngine second = service.get("alice", workspaceId);
        assertEquals(javaServer, first.getLspServers().get("java"));
        assertEquals(javaServer, second.getLspServers().get("java"));

        service.removeLsp("java");
        assertFalse(first.getLspServers().containsKey("java"));
        assertFalse(second.getLspServers().containsKey("java"));
    }

    @Test
    void restoresBundledTypeScriptAfterRemovingOverride() throws Exception {
        String previous = System.getProperty("udata.bundle.dir");
        Path bundle = tempDir.resolve("bundle");
        Path plugins = Files.createDirectories(bundle.resolve("jdtls/plugins"));
        Files.createDirectories(bundle.resolve(System.getProperty("os.name", "")
                .toLowerCase().contains("win") ? "jdtls/config_win" : "jdtls/config_linux"));
        Files.createFile(plugins.resolve("org.eclipse.equinox.launcher_1.0.jar"));
        Path script = bundle.resolve(
                "typescript-lsp/node_modules/typescript-language-server/lib/cli.mjs");
        Files.createDirectories(script.getParent());
        Files.createFile(script);
        try {
            System.setProperty("udata.bundle.dir", bundle.toString());
            UserHarnessEngineServiceImpl service = configuredService();
            inject(service, "sessionRepository", new SessionRepository(tempDir.resolve("data")));
            inject(service, "workspaces", new UserWorkspaceServiceImpl(tempDir.resolve("workspace")));
            inject(service, "dataDir", tempDir.resolve("data").toString());
            inject(service, "harnessHome", ".soloncode");

            org.noear.solon.ai.harness.HarnessEngine engine = service.get("alice");
            assertEquals(script.toString(), engine.getLspServers()
                    .get("typescript").getCommand().get(1));

            service.putLsp("typescript", new LspServerParameters(
                    Utils.asList("custom-tsls"), Utils.asList(".js")));
            assertEquals("custom-tsls", engine.getLspServers()
                    .get("typescript").getCommand().get(0));

            service.removeLsp("typescript");
            assertEquals(script.toString(), engine.getLspServers()
                    .get("typescript").getCommand().get(1));
        } finally {
            if (previous == null) {
                System.clearProperty("udata.bundle.dir");
            } else {
                System.setProperty("udata.bundle.dir", previous);
            }
        }
    }

    private UserHarnessEngineServiceImpl configuredService() throws Exception {
        UserHarnessEngineServiceImpl service = new UserHarnessEngineServiceImpl();
        inject(service, "compressionMaxContextRatio", 0.75D);
        inject(service, "modelContextLength", 1000000L);
        inject(service, "modelMaxAttempts", 3);
        inject(service, "modelName", "deepseek-flash");
        inject(service, "apiUrl", "https://api.deepseek.com");
        inject(service, "apiKey", "");
        inject(service, "provider", "openai");
        inject(service, "model", "deepseek-v4-flash");
        return service;
    }

    private UserHarnessEngineServiceImpl runnableService() throws Exception {
        UserHarnessEngineServiceImpl service = configuredService();
        inject(service, "sessionRepository", new SessionRepository(tempDir.resolve("data")));
        inject(service, "workspaces", new UserWorkspaceServiceImpl(tempDir.resolve("workspace")));
        inject(service, "dataDir", tempDir.resolve("data").toString());
        inject(service, "harnessHome", ".soloncode");
        return service;
    }

    private java.util.Set<String> toolNames(org.noear.solon.ai.agent.react.ReActOptions options) {
        java.util.Set<String> names = options.getTools().stream()
                .map(org.noear.solon.ai.chat.tool.FunctionTool::name)
                .collect(java.util.stream.Collectors.toSet());
        options.getModelOptions().talents().stream()
                .flatMap(item -> {
                    java.util.Collection<org.noear.solon.ai.chat.tool.FunctionTool> tools =
                            item.target.getTools(null);
                    return tools == null ? java.util.stream.Stream.empty() : tools.stream();
                })
                .map(org.noear.solon.ai.chat.tool.FunctionTool::name)
                .forEach(names::add);
        return names;
    }

    private void inject(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private Object read(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
}
