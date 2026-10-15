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
        inject(service, "model", "deepseek-v4-flash");

        assertNotNull(service.get("alice"));
        assertNotNull(service.get("alice").getMainAgent().getConfig().getDefaultOptions().getTool("context_restore"));
        assertNotNull(service.get("alice").getMainAgent().getConfig().getDefaultOptions().getTool("context_search"));
        assertNotNull(service.get("alice").getMainAgent().getConfig().getDefaultOptions().getTool("context_task_update"));
        assertNotNull(service.get("alice").getMainAgent().getConfig().getDefaultOptions().getTool("context_task_snapshot"));
        org.junit.jupiter.api.Assertions.assertTrue(service.get("alice").getMainAgent().getConfig()
                .getDefaultOptions().getInterceptors().stream()
                .anyMatch(item -> item.target instanceof ToolResultContextInterceptor));
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
