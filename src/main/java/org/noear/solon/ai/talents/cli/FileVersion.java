package org.noear.solon.ai.talents.cli;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Streaming content fingerprint used as an optimistic write precondition.
 *
 * <p>辅助 {@link SafeMutationTalent} 实现“先比对、后写入”的乐观并发控制：编辑方把写入前读到的
 * 内容摘要（{@link #sha256(Path)}）由后端按会话保存，修改工具取出该摘要并重新计算磁盘上的
 * 当前摘要，两者不一致即说明文件在此期间被他人改动，可据此拒绝盲写、要求模型先重新读取。</p>
 *
 * <p>设计要点：</p>
 * <ul>
 *   <li><b>流式计算</b>：按 8 KiB 分块喂给 {@link MessageDigest}，无论文件多大都只占用固定
 *       常数级内存，不会把整个文件读进堆；</li>
 *   <li><b>无状态工具</b>：类本身不缓存任何摘要，只做纯函数式计算，因此摘要口径在
 *       工具侧与保存侧必须完全一致（都走本类的 {@code sha256}），否则会出现误判冲突；</li>
 *   <li><b>小写 hex</b>：返回 64 位小写十六进制字符串，与常见 digest 校验工具一致，便于
 *       人工比对和日志排查。</li>
 * </ul>
 *
 * <p>局限：摘要只反映写入前后的字节差异，对“原地改写且字节完全相同”的写入无法识别
 * （此时按语义也无需视为冲突）。</p>
 *
 * @see SafeMutationTalent
 */
final class FileVersion {
    /** 仅供本工具包内部调用，不允许实例化。 */
    private FileVersion() { }

    /**
     * 流式计算文件内容的 SHA-256 摘要。
     *
     * <p>分块读取目标文件（每块 8192 字节），累计喂入 {@link MessageDigest}，最终以 64 位
     * 小写十六进制字符串返回。</p>
     *
     * <p>错误语义：文件不存在、无读权限或读取中断会抛出 {@link IOException}，由调用方决定是
     * 视为“版本未知”（通常等价于不满足 preimage 匹配）还是直接报错给模型；SHA-256 在所有
     * 标准 JVM 上必然存在，若缺失则属运行环境异常，包装为 {@link IllegalStateException}。
     * 注意本方法<b>不校验路径安全性</b>，调用方须先经上游路径守卫验证。</p>
     *
     * @param path 目标文件，须为已通过安全校验的绝对路径
     * @return 64 字符小写十六进制 SHA-256 摘要
     * @throws IOException            文件不可读、不存在或读取过程中出错
     * @throws IllegalStateException JVM 不提供 SHA-256 算法（不应发生）
     */
    static String sha256(Path path) throws IOException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
        byte[] buffer = new byte[8192];
        try (InputStream stream = Files.newInputStream(path)) {
            int count;
            while ((count = stream.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        return hex(digest.digest());
    }

    /**
     * 计算本工具准备写入的确定字节版本，避免写后重读误记录外部修改。
     *
     * @param content 实际写入的字节
     * @return SHA-256 摘要
     */
    static String sha256(byte[] content) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * 将摘要编码为小写十六进制。
     *
     * @param digest 摘要字节
     * @return 十六进制字符串
     */
    private static String hex(byte[] digest) {
        StringBuilder hex = new StringBuilder(64);
        for (byte b : digest) hex.append(String.format("%02x", b & 0xff));
        return hex.toString();
    }
}
