package com.udata.harness.service.impl;

import com.udata.harness.repository.SessionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.Utils;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.core.Props;

import java.lang.reflect.Field;
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
        assertEquals(4, service.get("alice").getModelRetries());
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

    private UserHarnessEngineServiceImpl configuredService() throws Exception {
        UserHarnessEngineServiceImpl service = new UserHarnessEngineServiceImpl();
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
}
