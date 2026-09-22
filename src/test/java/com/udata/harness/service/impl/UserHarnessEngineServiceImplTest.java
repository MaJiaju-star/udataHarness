package com.udata.harness.service.impl;

import com.udata.harness.repository.SessionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;

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
        inject(service, "modelName", "deepseek");
        inject(service, "apiUrl", "https://api.deepseek.com");
        inject(service, "apiKey", "");
        inject(service, "provider", "openai");
        inject(service, "model", "deepseek-v4-flash");

        assertNotNull(service.get("alice"));
    }

    private void inject(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
