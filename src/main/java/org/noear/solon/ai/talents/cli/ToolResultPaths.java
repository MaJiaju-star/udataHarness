package org.noear.solon.ai.talents.cli;

import com.udata.harness.common.context.ContextArtifactStore;

import java.io.IOException;
import java.nio.file.Path;

/**
 * 工具结果快照的虚拟路径解析器：把 {@value #ROOT} 命名空间下的逻辑路径映射为
 * {@link ContextArtifactStore} 中的磁盘文件（{@code @tool-results/<sha256>.txt}）。
 *
 * <p>该命名空间是<b>会话绑定、只读</b>的历史工具结果快照：
 * 解析结果永远落在当前会话的产物目录内，与工作区文件无关，
 * 因此写入/编辑类工具会先用 {@link #isVirtual(String)} 拦截并拒绝。</p>
 *
 * <p>路径形态：</p>
 * <ul>
 *   <li>{@code @tool-results} —— 命名空间根，仅在 {@code allowDirectory=true} 时
 *       解析为会话产物目录（供 grep 等目录级工具检索快照）；</li>
 *   <li>{@code @tool-results/<64位小写十六进制>.txt} —— 单个快照，
 *       十六进制段为产物 ID（内容 SHA-256）。</li>
 * </ul>
 *
 * @see ContextArtifactStore#bodyPath(String, String)
 * @see ContextArtifactStore#sessionArtifacts(String)
 */
final class ToolResultPaths {
    /** 虚拟命名空间根（只读历史快照）。 */
    static final String ROOT = "@tool-results";
    private final ContextArtifactStore store;

    ToolResultPaths(ContextArtifactStore store) {
        this.store = store;
    }

    /**
     * 判断给定路径是否落在 {@value #ROOT} 虚拟命名空间内（根或其子路径）。
     * 仅做前缀识别，不做格式校验；格式校验由 {@link #resolve} 负责。
     *
     * @param value 待判断路径，可为 null
     * @return 是虚拟快照路径返回 true；null 返回 false
     */
    static boolean isVirtual(String value) {
        return value != null && (value.equals(ROOT) || value.startsWith(ROOT + "/"));
    }

    /**
     * 把虚拟路径解析为快照在磁盘上的实际 {@link Path}。
     *
     * <p>解析限定在当前会话的产物目录内：会话或产物存储不可用时直接抛出异常
     * （fail-closed），不会回落到工作区路径，避免跨会话读取。</p>
     *
     * @param value         虚拟路径：{@value #ROOT} 或 {@code @tool-results/<64位十六进制>.txt}
     * @param sessionId     当前会话 ID，决定产物目录归属；null 或空视为不可用
     * @param allowDirectory 是否允许解析命名空间根为会话产物目录
     *                       （目录级工具传 true，文件级读取传 false）
     * @return 快照文件或会话产物目录的磁盘路径
     * @throws IOException 存储/会话不可用，或 {@value #ROOT} 路径形态不合法（含目录不被允许）
     */
    Path resolve(String value, String sessionId, boolean allowDirectory) throws IOException {
        if (store == null || sessionId == null || sessionId.isEmpty()) {
            throw new IOException("Tool-result snapshots are unavailable in this session");
        }
        if (ROOT.equals(value) && allowDirectory) return store.sessionArtifacts(sessionId);
        if (!value.matches("@tool-results/[a-f0-9]{64}\\.txt")) {
            throw new IOException("Invalid tool-result path");
        }
        return store.bodyPath(sessionId, value.substring(ROOT.length() + 1, value.length() - 4));
    }
}
