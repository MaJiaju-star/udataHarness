package org.noear.solon.ai.talents.cli;

import org.noear.solon.ai.talents.mount.MountManager;
import org.noear.solon.ai.talents.mount.ResolvedResource;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Reuses the exact Solon filesystem guard for application-owned search tools.
 *
 * <p>本类把 {@link TerminalTalent} 内部的路径安全组件（{@link TerminalSupport} 守卫与
 * {@link MountManager} 挂载表）打包成一组可复用的查询入口，供应用层搜索工具
 * （如 {@link TerminalTalent#glob(String, String, String)}、{@link TerminalTalent#grep(String, String, String, String)}
 * 与 {@link BoundedGlobTool} 等）在遍历、过滤文件时保持与 Solon
 * 自带工具<strong>完全一致</strong>的安全语义，避免重复实现一套可能产生偏差的守卫逻辑。</p>
 *
 * <p>主要能力：</p>
 * <ul>
 *   <li>非本地虚拟挂载判定（{@link #isNonLocalVirtual(String)}）</li>
 *   <li>工作目录解析（{@link #workPath(String)}）与安全路径归一化（{@link #resolve(Path, String)}）</li>
 *   <li>隐藏/沙盒拒绝项过滤（{@link #hiddenOrDenied(Path, Path, Path)}）</li>
 *   <li>展示路径格式化（{@link #display(Path, String, Path, Path)}）与相对键生成（{@link #relative(Path, Path)}）</li>
 * </ul>
 *
 * <p><strong>注意</strong>：构造时通过反射读取 {@code TerminalTalent} 的私有字段，
 * 若 Solon 内部字段结构变更将抛出 {@link IllegalStateException}（fail-fast）。</p>
 */
final class SearchToolAccess {
    /** 宿主技能实例，提供沙盒开关等运行时状态。 */
    final TerminalTalent terminal;
    /** Solon 文件系统守卫（路径归一化、忽略规则、读取拒绝、展示格式化）。 */
    final TerminalSupport support;
    /** 挂载管理器，用于把 {@code @alias/...} 逻辑路径解析为资源。 */
    final MountManager mounts;
    /** 工作区根目录（绝对化 + 归一化后的基准路径）。 */
    final Path workspace;

    /**
     * 构造访问器，并通过反射取出 {@link TerminalTalent} 的私有守卫组件。
     *
     * @param terminal  宿主技能实例
     * @param workspace 工作区根目录（字符串形式，构造时绝对化并归一化）
     * @throws IllegalStateException 反射获取守卫组件失败（Solon 内部结构调整或安全策略限制）
     */
    SearchToolAccess(TerminalTalent terminal, String workspace) {
        this.terminal = terminal;
        this.workspace = Paths.get(workspace).toAbsolutePath().normalize();
        try {
            Field supportField = TerminalTalent.class.getDeclaredField("support");
            supportField.setAccessible(true);
            support = (TerminalSupport) supportField.get(terminal);
            Field mountField = TerminalTalent.class.getDeclaredField("mountManager");
            mountField.setAccessible(true);
            mounts = (MountManager) mountField.get(terminal);
        } catch (ReflectiveOperationException | SecurityException e) {
            throw new IllegalStateException("Solon search path guard is unavailable", e);
        }
    }

    /**
     * 判断给定路径是否为“非本地可访问”的虚拟挂载（如远程/仅 shell 挂载点）。
     *
     * <p>非 {@code @} 前缀的路径直接返回 {@code false}；否则解析挂载资源，
     * 当来源不支持本地路径访问或无法给出本地路径时视为非本地虚拟。</p>
     *
     * @param path 输入路径（可能为 {@code @alias/...} 形式的逻辑路径）
     * @return {@code true} 表示该路径不可作为本地文件直接遍历
     */
    boolean isNonLocalVirtual(String path) {
        if (!path.startsWith("@")) return false;
        ResolvedResource resource = mounts.resolveResource(path);
        return !resource.getSource().capabilities().isLocalPathAccessible()
                || !resource.getLocalPath().isPresent();
    }

    /**
     * 解析当前工作目录：{@code cwd} 为 {@code null} 时回落到工作区根目录。
     *
     * @param cwd 可选的工作目录（相对或绝对路径）
     * @return 绝对化并归一化后的工作目录
     */
    Path workPath(String cwd) {
        return cwd == null ? workspace : Paths.get(cwd).toAbsolutePath().normalize();
    }

    /**
     * 走 Solon 安全守卫解析路径（防符号链接逃逸 / 越出策略根）。
     *
     * @param workPath 基准工作目录
     * @param path     待解析路径（相对基准目录或绝对路径）
     * @return 归一化且通过安全校验的目标路径
     * @throws IOException 路径越界、符号链接逃逸等校验失败
     */
    Path resolve(Path workPath, String path) throws IOException {
        return support.resolveSafePath(workPath, path, false,
                terminal.isSandboxEnabled(), false, null);
    }

    /**
     * 判断条目是否应被跳过：命中隐藏/忽略规则，或在沙盒策略下被拒绝读取。
     *
     * @param workPath  基准工作目录（用于忽略规则的相对化）
     * @param policyRoot 策略根目录（读取拒绝判定的边界）
     * @param item      待判定的文件/目录
     * @return {@code true} 表示该条目应被过滤掉
     */
    boolean hiddenOrDenied(Path workPath, Path policyRoot, Path item) {
        return support.isIgnored(workPath, item)
                || support.isReadDenied(policyRoot, item, terminal.isSandboxEnabled(), null);
    }

    /**
     * 按 Solon 约定生成条目的展示路径（相对工作目录或输入路径）。
     *
     * @param workPath  基准工作目录
     * @param inputPath 调用方输入的原始路径（用于计算展示相对路径）
     * @param target    目标根路径
     * @param item      待展示的文件/目录
     * @return 展示用路径字符串
     */
    String display(Path workPath, String inputPath, Path target, Path item) {
        return support.formatDisplayPath(workPath, inputPath, target, item, terminal.isSandboxEnabled());
    }

    /**
     * 生成 {@code file} 相对 {@code target} 的排序/续页键，反斜杠归一化为 {@code /}。
     *
     * @param target 基准路径（通常为目标根目录）
     * @param file   目标文件路径
     * @return 相对键；两者相同或相对结果为空时返回文件名
     */
    static String relative(Path target, Path file) {
        String key = target.relativize(file).toString().replace('\\', '/');
        return key.isEmpty() ? file.getFileName().toString() : key;
    }
}
