package com.udata.harness.service.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.talents.lsp.LspServerParameters;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证交付包中的 TypeScript 语言服务器不依赖全局 npm 安装。
 */
class BundledTypeScriptLspTest {
    @TempDir
    Path tempDir;

    @Test
    void usesBundledServerWithConfiguredNode() throws Exception {
        String previousBundle = System.getProperty("udata.bundle.dir");
        String previousNode = System.getProperty("udata.node.command");
        Path entry = tempDir.resolve(
                "typescript-lsp/node_modules/typescript-language-server/lib/cli.mjs");
        Files.createDirectories(entry.getParent());
        Files.createFile(entry);
        try {
            System.setProperty("udata.bundle.dir", tempDir.toString());
            System.setProperty("udata.node.command", "C:/node/node.exe");

            LspServerParameters parameters = BundledTypeScriptLsp.forWorkspace();

            assertEquals("C:/node/node.exe", parameters.getCommand().get(0));
            assertEquals(entry.toString(), parameters.getCommand().get(1));
            assertEquals("--stdio", parameters.getCommand().get(2));
            assertTrue(parameters.getExtensions().contains(".jsx"));
            assertTrue(parameters.getExtensions().contains(".tsx"));
        } finally {
            restore("udata.bundle.dir", previousBundle);
            restore("udata.node.command", previousNode);
        }
    }

    @Test
    void skipsBundlesWithoutTypeScriptServer() {
        String previous = System.getProperty("udata.bundle.dir");
        try {
            System.setProperty("udata.bundle.dir", tempDir.toString());
            assertNull(BundledTypeScriptLsp.forWorkspace());
        } finally {
            restore("udata.bundle.dir", previous);
        }
    }

    /**
     * 将测试修改的进程属性恢复到原值。
     *
     * @param name 属性名称
     * @param value 原值，为 null 时移除属性
     */
    private void restore(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }
}
