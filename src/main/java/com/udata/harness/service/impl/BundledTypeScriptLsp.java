package com.udata.harness.service.impl;

import org.noear.solon.ai.talents.lsp.LspServerParameters;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

/**
 * 从离线交付包中装配 TypeScript 和 JavaScript 语言服务器。
 */
final class BundledTypeScriptLsp {
    private static final String SERVER_ENTRY =
            "typescript-lsp/node_modules/typescript-language-server/lib/cli.mjs";

    private BundledTypeScriptLsp() {
    }

    /**
     * 判断交付包是否包含 TypeScript Language Server。
     *
     * @return 存在包内启动脚本时为 true
     */
    static boolean isBundled() {
        return serverEntry() != null;
    }

    /**
     * 使用部署机已有的 Node.js 与包内 TypeScript 依赖建立 LSP 配置。
     *
     * @return LSP 参数；未从包含该语言服务器的交付包启动时返回 null
     */
    static LspServerParameters forWorkspace() {
        Path entry = serverEntry();
        if (entry == null) {
            return null;
        }
        return new LspServerParameters(
                Arrays.asList(nodeCommand(), entry.toString(), "--stdio"),
                Arrays.asList(".ts", ".tsx", ".js", ".jsx", ".mjs", ".cjs", ".mts", ".cts"));
    }

    /**
     * 查找包内的 TypeScript Language Server 入口。
     *
     * @return 入口文件；普通运行或旧版交付包返回 null
     */
    private static Path serverEntry() {
        if (!BundledJavaLsp.isBundled()) {
            return null;
        }
        Path entry = Paths.get(System.getProperty("udata.bundle.dir"))
                .toAbsolutePath().normalize().resolve(SERVER_ENTRY);
        return Files.isRegularFile(entry) ? entry : null;
    }

    /**
     * 选择部署机已有的 Node.js 命令。
     *
     * @return 显式命令、NODE_HOME 下的可执行文件，或 PATH 中的 node
     */
    private static String nodeCommand() {
        String configured = System.getProperty("udata.node.command");
        if (configured != null && !configured.trim().isEmpty()) {
            return configured.trim();
        }
        String nodeHome = System.getenv("NODE_HOME");
        if (nodeHome != null && !nodeHome.trim().isEmpty()) {
            boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
            Path candidate = Paths.get(nodeHome).resolve(windows ? "node.exe" : "bin/node");
            if (Files.isRegularFile(candidate)) {
                return candidate.toAbsolutePath().normalize().toString();
            }
        }
        return "node";
    }
}
