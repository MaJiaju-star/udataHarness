package com.udata.harness.service.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 LSP 配置在服务重启后仍可恢复。
 */
class IntegrationServiceImplTest {
    @TempDir
    Path tempDir;

    @Test
    void persistsAndRemovesLspConfiguration() throws Exception {
        IntegrationServiceImpl service = newService();
        service.saveLsp("java", Arrays.asList("jdtls"), Arrays.asList(".java"),
                "{\"JAVA_HOME\":\"C:/jdk21\"}", true);

        IntegrationServiceImpl reloaded = newService();
        Map<String, Object> item = reloaded.listLsp().get(0);
        assertEquals("java", item.get("name"));
        assertEquals(Arrays.asList(".java"), item.get("extensions"));
        assertTrue((Boolean) item.get("hasEnv"));
        assertFalse(item.containsKey("env"));

        reloaded.removeLsp("java");
        assertTrue(newService().listLsp().isEmpty());
    }

    /**
     * 使用独立数据目录创建一个集成服务实例。
     *
     * @return 已加载持久化配置的实例
     * @throws Exception 反射注入失败时抛出
     */
    private IntegrationServiceImpl newService() throws Exception {
        IntegrationServiceImpl service = new IntegrationServiceImpl();
        Field dataDir = IntegrationServiceImpl.class.getDeclaredField("dataDir");
        dataDir.setAccessible(true);
        dataDir.set(service, tempDir.toString());
        Field engines = IntegrationServiceImpl.class.getDeclaredField("engines");
        engines.setAccessible(true);
        engines.set(service, new UserHarnessEngineServiceImpl());
        service.init();
        return service;
    }
}
