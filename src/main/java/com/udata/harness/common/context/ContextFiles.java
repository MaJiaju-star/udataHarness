package com.udata.harness.common.context;

import org.noear.snack4.ONode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.UUID;

/**
 * 会话本地 JSON 文件的读写工具：写入采用“先写临时文件再原子替换”。
 *
 * <p>上下文管理要求“先落盘、再改内存”，因此写入必须是原子的：中途崩溃不会留下半截文件，
 * 也不会把旧版本覆盖成损坏内容。不支持 {@code ATOMIC_MOVE} 的文件系统上退化为普通替换。</p>
 *
 * <p>读取失败、解析失败与写入失败均转为 {@link IllegalStateException}，由调用方决定是回滚还是中断本轮请求。</p>
 */
final class ContextFiles {
    /**
     * 工具类，不允许实例化。
     */
    private ContextFiles() { }

    /**
     * 按 UTF-8 读取并解析一个上下文 JSON 文件。
     *
     * @param path 目标文件
     * @return 解析后的 JSON 根节点
     * @throws IllegalStateException 文件不存在、不可读或内容不是合法 JSON 时抛出
     */
    static ONode read(Path path) {
        try {
            return ONode.ofJson(new String(Files.readAllBytes(path), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read context file: " + path, e);
        }
    }

    /**
     * 原子写入 JSON：写同目录临时文件后替换目标。
     *
     * <p>临时文件与目标同目录（保证同文件系统，替换才是原子的），文件名带随机后缀以避开并发写冲突。
     * 无论成败都在 {@code finally} 中删除临时文件，不留残余；临时文件的失败不会被误报为目标写入失败。</p>
     *
     * @param path  目标文件
     * @param value 待序列化对象
     * @throws IllegalStateException 目录创建或写入失败时抛出
     */
    static void write(Path path, Object value) {
        //1. 在目标同目录生成带随机后缀的临时文件，保证后续 move 是同文件系统内的原子操作。
        Path temporary = path.resolveSibling(path.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            Files.createDirectories(path.getParent());
            Files.write(temporary, ONode.serialize(value).getBytes(StandardCharsets.UTF_8));
            //2. 优先原子替换；文件系统不支持时退化为普通替换，仍保证内容完整。
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot persist context file: " + path, e);
        } finally {
            //3. 清理临时文件；此处失败不影响写入结果，因此静默忽略。
            try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
        }
    }
}
