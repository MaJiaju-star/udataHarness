package com.udata.harness;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * 默认终端方言引导：在 Solon 创建 TerminalTalent 前确定 Windows 上使用的 shell。
 *
 * <p>选择规则：仅在 Windows 且未显式配置 {@code solon.ai.cli.shell} 系统属性
 * 或 {@code SOLON_AI_CLI_SHELL} 环境变量时生效，默认方言为 {@code pwsh}；
 * 其余平台或已显式配置时不作任何改动。</p>
 *
 * <p>由 {@link com.udata.harness.App#main(String[])} 在 {@code Solon.start} 之前调用，
 * 决策逻辑抽离为 {@link #chooseDefaultShell} 以便单测。</p>
 */
final class ShellBootstrap {
    private static final String SHELL_PROPERTY = "solon.ai.cli.shell";
    private static final String SHELL_ENV = "SOLON_AI_CLI_SHELL";

    private ShellBootstrap() { }

    /**
     * 探测当前运行环境并把选定的默认 shell 写入 {@code solon.ai.cli.shell} 系统属性。
     *
     * <p>非 Windows 平台、或属性/环境变量已有显式值时保持原状；
     * Windows 上找不到 PowerShell 7 时抛出 {@link IllegalStateException} 快速失败。</p>
     *
     * @throws IllegalStateException Windows 上 PATH 中找不到 {@code pwsh} 且未显式配置其他终端
     */
    static void configureDefaultShell() {
        String selected = chooseDefaultShell(System.getProperty("os.name", ""),
                System.getProperty(SHELL_PROPERTY), System.getenv(SHELL_ENV), ShellBootstrap::powerShell7Available);
        if (selected != null) System.setProperty(SHELL_PROPERTY, selected);
    }

    /**
     * 纯决策函数：根据运行环境选出应写入系统属性的默认 shell 名称。
     *
     * @param osName           {@code os.name} 系统属性，含 {@code win}（忽略大小写）才视为 Windows
     * @param property         {@code solon.ai.cli.shell} 系统属性当前值，非空则视为已显式配置
     * @param environment      {@code SOLON_AI_CLI_SHELL} 环境变量当前值，非空则视为已显式配置
     * @param powerShell7Probe PowerShell 7 可用性探针，仅在需要默认值时才会被调用
     * @return 默认 shell 名称（{@code pwsh}）；非 Windows 或已显式配置时返回 {@code null}，表示无需改动
     * @throws IllegalStateException Windows 上探针判定 PowerShell 7 不可用且未显式配置其他终端
     */
    static String chooseDefaultShell(String osName, String property, String environment,
                                     BooleanSupplier powerShell7Probe) {
        if (!osName.toLowerCase(Locale.ROOT).contains("win")
                || hasValue(property) || hasValue(environment)) return null;
        if (!powerShell7Probe.getAsBoolean()) {
            throw new IllegalStateException("Windows 默认终端需要 PowerShell 7（pwsh.exe）。"
                    + "请安装 PowerShell 7 并确保服务进程的 PATH 能找到 pwsh，"
                    + "或显式设置 SOLON_AI_CLI_SHELL=powershell/cmd 使用其他终端。");
        }
        return "pwsh";
    }

    /** 判断配置值是否非空白（{@code null}、空串或纯空白均视为未配置）。 */
    private static boolean hasValue(String value) {
        return value != null && !value.trim().isEmpty();
    }

    /**
     * 实际探测本机是否安装了 PowerShell 7：执行 {@code pwsh -NoProfile -NonInteractive}
     * 读取 {@code $PSVersionTable.PSVersion.Major}，主版本号 &ge; 7 即视为可用。
     *
     * <p>超时（8 秒）、启动失败、输出无法解析或线程被中断均视为不可用；
     * 探测子进程无论结果如何都会在 {@code finally} 中被强制销毁。</p>
     *
     * @return PowerShell 7 可用时返回 {@code true}
     */
    static boolean powerShell7Available() {
        Process process = null;
        try {
            process = new ProcessBuilder("pwsh", "-NoProfile", "-NonInteractive", "-Command",
                    "$PSVersionTable.PSVersion.Major").redirectErrorStream(true).start();
            if (!process.waitFor(8, TimeUnit.SECONDS) || process.exitValue() != 0) return false;
            try (BufferedReader output = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
                String major = output.readLine();
                return major != null && Integer.parseInt(major.trim()) >= 7;
            }
        } catch (IOException | NumberFormatException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
        }
    }
}
