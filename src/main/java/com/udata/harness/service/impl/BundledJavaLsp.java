package com.udata.harness.service.impl;

import org.noear.solon.ai.talents.lsp.LspServerParameters;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 从离线交付包中装配 Java 语言服务器。
 *
 * <p>启动脚本设置 {@code udata.bundle.dir} 后才启用；普通源码运行仍使用手动配置。</p>
 */
final class BundledJavaLsp {
    private static final String BUNDLE_DIR_PROPERTY = "udata.bundle.dir";

    private BundledJavaLsp() {
    }

    /**
     * 判断当前进程是否从离线交付包启动。
     *
     * @return 已指定交付包目录时为 true
     */
    static boolean isBundled() {
        String configuredRoot = System.getProperty(BUNDLE_DIR_PROPERTY);
        return configuredRoot != null && !configuredRoot.trim().isEmpty();
    }

    /**
     * 为一个用户工作区生成独立的 JDT LS 启动参数。
     *
     * @param dataDir 应用数据目录
     * @param userId 用户标识
     * @param workspaceId 工作区标识
     * @return 离线包内的 LSP 参数；普通运行时返回 null
     */
    static LspServerParameters forWorkspace(String dataDir, String userId, String workspaceId) {
        if (!isBundled()) {
            return null;
        }

        //1. 使用后端当前的 Java 运行时，定位包内 JDT LS 配置和启动 JAR。
        Path bundle = Paths.get(System.getProperty(BUNDLE_DIR_PROPERTY))
                .toAbsolutePath().normalize();
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        Path java = Paths.get(System.getProperty("java.home"))
                .resolve(windows ? "bin/java.exe" : "bin/java");
        Path server = bundle.resolve("jdtls");
        Path config = server.resolve(windows ? "config_win" : "config_linux");
        Path launcher = launcher(server.resolve("plugins"));
        if (!Files.isRegularFile(java) || !Files.isDirectory(config)) {
            throw new IllegalStateException("Bundled Java LSP is incomplete: " + bundle);
        }

        //2. 每个用户工作区使用独立的数据目录，避免 JDT LS 索引互相覆盖。
        Path state = Paths.get(dataDir).toAbsolutePath().normalize()
                .resolve("lsp").resolve(userId).resolve(workspaceId);
        try {
            Files.createDirectories(state);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create Java LSP data directory: " + state, e);
        }

        //3. 直接调用当前 Java 和包内 JDT LS，不依赖 jdtls 的 PATH 或 Python。
        List<String> command = new ArrayList<>(Arrays.asList(
                java.toString(),
                "-Declipse.application=org.eclipse.jdt.ls.core.id1",
                "-Dosgi.bundles.defaultStartLevel=4",
                "-Declipse.product=org.eclipse.jdt.ls.core.product",
                "-Dlog.protocol=false",
                "-Dlog.level=ERROR",
                "-Xms256m",
                "-Xmx1g",
                "--add-modules=ALL-SYSTEM",
                "--add-opens", "java.base/java.util=ALL-UNNAMED",
                "--add-opens", "java.base/java.lang=ALL-UNNAMED",
                "-jar", launcher.toString(),
                "-configuration", config.toString(),
                "-data", state.toString()));
        return new LspServerParameters(command, Arrays.asList(".java"));
    }

    /**
     * 查找离线包中唯一的 Eclipse Equinox 启动 JAR。
     *
     * @param plugins JDT LS 插件目录
     * @return 启动 JAR 路径
     */
    private static Path launcher(Path plugins) {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(
                plugins, "org.eclipse.equinox.launcher_*.jar")) {
            for (Path file : files) {
                return file;
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot inspect bundled Java LSP: " + plugins, e);
        }
        throw new IllegalStateException("Bundled Java LSP launcher not found: " + plugins);
    }
}
