package com.udata.harness.service.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.talents.lsp.LspServerParameters;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证离线包从自身目录加载 Java LSP，并隔离工作区索引。
 */
class BundledJavaLspTest {
    @TempDir
    Path tempDir;

    @Test
    void usesBundleWithoutSearchingForJdtlsInPath() throws Exception {
        String previous = System.getProperty("udata.bundle.dir");
        Path bundle = tempDir.resolve("bundle");
        Path plugins = Files.createDirectories(bundle.resolve("jdtls/plugins"));
        Files.createDirectories(bundle.resolve(System.getProperty("os.name", "")
                .toLowerCase().contains("win") ? "jdtls/config_win" : "jdtls/config_linux"));
        Files.createFile(plugins.resolve("org.eclipse.equinox.launcher_1.0.jar"));
        try {
            System.setProperty("udata.bundle.dir", bundle.toString());
            LspServerParameters first = BundledJavaLsp.forWorkspace(
                    tempDir.resolve("data").toString(), "alice", "project-a");
            LspServerParameters second = BundledJavaLsp.forWorkspace(
                    tempDir.resolve("data").toString(), "alice", "project-b");
            List<String> firstCommand = first.getCommand();

            assertEquals(Paths.get(System.getProperty("java.home"))
                    .resolve(System.getProperty("os.name", "").toLowerCase().contains("win")
                            ? "bin/java.exe" : "bin/java").toString(), firstCommand.get(0));
            assertTrue(firstCommand.contains(plugins.resolve(
                    "org.eclipse.equinox.launcher_1.0.jar").toString()));
            assertEquals(".java", first.getExtensions().get(0));
            assertFalse(firstCommand.get(firstCommand.indexOf("-data") + 1).equals(
                    second.getCommand().get(second.getCommand().indexOf("-data") + 1)));
        } finally {
            if (previous == null) {
                System.clearProperty("udata.bundle.dir");
            } else {
                System.setProperty("udata.bundle.dir", previous);
            }
        }
    }

    @Test
    void leavesNormalSourceRunUnchanged() {
        String previous = System.getProperty("udata.bundle.dir");
        try {
            System.clearProperty("udata.bundle.dir");
            assertNull(BundledJavaLsp.forWorkspace(tempDir.toString(), "alice", "default"));
        } finally {
            if (previous != null) {
                System.setProperty("udata.bundle.dir", previous);
            }
        }
    }
}
