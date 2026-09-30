package com.udata.harness.common.context;

import org.noear.snack4.ONode;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 会话级、内容寻址的工具证据归档：保存原始工具输出，供模型按需恢复。
 *
 * <p>证据 ID 是 {@code SHA-256(tool + 规范化参数 + 原文)} 的十六进制串，因此同一工具、同一范围、
 * 同一正文会得到同一 ID：写入天然幂等，重复工具调用不会产生第二份存档。文档中的“首次归档时间”
 * 指的是证据创建时间，而不是后来再次调用工具的时间。</p>
 *
 * <p>读取范围被严格限制在构造时给定的 artifacts 目录，文件名只能是 64 位小写十六进制，
 * 因此不存在目录穿越，也不提供任意路径读取能力。</p>
 *
 * <p>所有写操作都在实例上同步：同会话的工具结果可能被并发投影。存档一旦写入就不会被后面
 * 的同 ID 调用改写，因此历史证据始终可复现。</p>
 */
public class ContextArtifactStore {
    /**
     * artifacts 目录，每个证据对应一个 {@code <sha256>.json} 文件。
     */
    private final Path root;

    /**
     * 在会话的上下文目录下创建证据库。
     *
     * @param contextRoot 会话的 {@code <sessionId>.context/} 目录
     */
    public ContextArtifactStore(Path contextRoot) { root = contextRoot.resolve("artifacts"); }

    /**
     * 归档一段原始工具输出，并返回其内容寻址 ID。
     *
     * <p>同 ID 已存在时直接复用，不重新写入，也不校验新正文是否相同。工具执行本身从不因去重被跳过，
     * 去重减少的只是模型重复看到的正文。</p>
     *
     * @param tool    工具名
     * @param args    规范化前的工具参数
     * @param content 工具返回的原始文本
     * @return 有效证据 ID，可交给 {@link #restore} 或 {@link #search}
     */
    public synchronized String put(String tool, Map<String, Object> args, String content) {
        //1. 参数按键排序后序列化，使同参数不同顺序不产生不同指纹。
        String arguments = ONode.serialize(canonical(args));
        //2. 以“工具 + 规范化参数 + 原文”计算内容寻址 ID，同版本证据天然幂等。
        String id = digest(tool + "\n" + arguments + "\n" + content);
        Path path = path(id);
        if (!Files.exists(path)) {
            //3. 首次出现才落盘：完整记录原文、参数与时间，作为可追溯的唯一副本。
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("id", id);
            record.put("tool", tool);
            record.put("arguments", arguments);
            record.put("contentHash", digest(content));
            record.put("createdAt", System.currentTimeMillis());
            record.put("content", content);
            ContextFiles.write(path, record);
        }
        return id;
    }

    /**
     * 判断证据是否已归档；ID 非法时抛出而非返回 {@code false}。
     *
     * @param id 证据 ID
     * @return 对应文件是否为已存在的普通文件
     * @throws IllegalArgumentException ID 不是 64 位小写十六进制时抛出
     */
    public boolean contains(String id) { return Files.isRegularFile(path(id)); }

    /**
     * 把证据 ID 解析为文件路径，兼作路径穿越防护。
     *
     * @param id 证据 ID，必须为 64 位小写十六进制
     * @return artifacts 目录下的 {@code <id>.json}
     * @throws IllegalArgumentException ID 为 {@code null} 或格式不符时抛出
     */
    private Path path(String id) {
        if (id == null || !id.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid artifact ID");
        return root.resolve(id + ".json");
    }

    /**
     * 读取证据并校验完整性。
     *
     * <p>重算 ID 与内容哈希，任何不一致都视为文件被篡改或损坏，直接失败而不是把不可信内容交给模型。
     *
     * @param id 证据 ID
     * @return 已通过完整性校验的存档记录
     * @throws IllegalStateException ID、参数或内容哈希不一致时抛出
     */
    private ONode record(String id) {
        ONode record = ContextFiles.read(path(id));
        if (!id.equals(record.get("id").getString())
                || !id.equals(digest(record.get("tool").getString() + "\n" + record.get("arguments").getString() + "\n" + record.get("content").getString()))
                || !record.get("contentHash").getString().equals(digest(record.get("content").getString()))) {
            throw new IllegalStateException("Artifact integrity check failed: " + id);
        }
        return record;
    }

    /**
     * 按字符范围恢复证据片段，偏移量为 UTF-16 字符单位。
     *
     * <p>返回值显式标注为历史证据并携带版本哈希，调用方需自行核对当前文件与测试结果。</p>
     *
     * @param id       证据 ID
     * @param offset   起始偏移，须不小于 0 且不超过原文长度
     * @param maxChars 本次最多返回的字符数，取值 1..16000；单次上限与工具描述保持一致
     * @return 含元数据、{@code historicalEvidence} 标记、片段正文与 {@code nextOffset} 的结果
     * @throws IllegalArgumentException 范围非法或偏移超出原文长度时抛出
     */
    public Map<String, Object> restore(String id, int offset, int maxChars) {
        //1. 先校验请求范围，再读取证据，避免为非法参数做多余的完整校验与磁盘读取。
        if (offset < 0 || maxChars < 1 || maxChars > 16000) throw new IllegalArgumentException("Invalid restore range");
        ONode record = record(id);
        String content = record.get("content").getString();
        if (offset > content.length()) throw new IllegalArgumentException("Offset exceeds artifact length");
        int end = Math.min(content.length(), offset + maxChars);
        //2. 先铺元数据与历史证据声明，再挂上本次片段。
        Map<String, Object> result = metadata(record);
        result.put("historicalEvidence", true);
        result.put("warning", "Historical tool output; verify the current file or rerun tests before relying on freshness.");
        result.put("offset", offset);
        result.put("content", content.substring(offset, end));
        result.put("totalChars", content.length());
        //3. 只在后面还有内容时给出 nextOffset，null 表示已读到末尾。
        result.put("nextOffset", end < content.length() ? end : null);
        return result;
    }

    /**
     * 在会话全部证据中检索关键词，所有词都必须命中。
     *
     * <p>命中内容来自工具名、参数正文与原始输出；这里是全文件扫描，没有向量库与索引，
     * 也不做排序打分，不推断任何相关性排名。</p>
     *
     * @param query 关键词，按空白分词后全部匹配，长 1..256 字符
     * @param tool  可选工具名过滤，为空时不限制
     * @param limit 最大命中数，取值 1..20
     * @return 命中列表，按 {@code createdAt} 从新到旧；目录不存在时返回空列表
     * @throws IllegalArgumentException 关键词或数量参数非法时抛出
     * @throws IllegalStateException    遍历 artifacts 目录失败时抛出
     */
    public List<Map<String, Object>> search(String query, String tool, int limit) {
        //1. 校验检索参数；目录尚未创建时直接返回空结果，不报错。
        if (query == null || query.trim().isEmpty() || query.length() > 256) throw new IllegalArgumentException("Query must have 1..256 characters");
        if (limit < 1 || limit > 20) throw new IllegalArgumentException("Limit must be 1..20");
        if (!Files.exists(root)) return Collections.emptyList();
        String[] terms = query.toLowerCase(Locale.ROOT).trim().split("\\s+");
        List<Map<String, Object>> found = new ArrayList<>();
        try (Stream<Path> paths = Files.list(root)) {
            //2. 仅遍历符合 ID 命名规范的文件，非证据文件一律忽略。
            for (Path path : paths.filter(p -> p.getFileName().toString().matches("[a-f0-9]{64}\\.json"))
                    .collect(Collectors.toList())) {
                ONode record = record(path.getFileName().toString().substring(0, 64));
                if (tool != null && !tool.isEmpty() && !tool.equals(record.get("tool").getString())) continue;
                String content = record.get("content").getString();
                //3. 所有词必须同时命中，避免单个高频词把结果集撑大。
                String searchable = (record.get("tool").getString() + " " + record.get("arguments").getString()
                        + " " + content).toLowerCase(Locale.ROOT);
                if (!Arrays.stream(terms).allMatch(searchable::contains)) continue;
                Map<String, Object> item = metadata(record);
                //4. 截取首个命中词附近的预览，并同时给出预览偏移与原文长度，便于后续有界恢复。
                int hit = content.toLowerCase(Locale.ROOT).indexOf(terms[0]);
                int start = Math.max(0, hit - 100);
                item.put("previewOffset", start);
                item.put("preview", content.substring(start, Math.min(content.length(), start + 500)));
                item.put("totalChars", content.length());
                found.add(item);
                //5. 结果保界：只留最新 limit 条，并避免序列化后超出返回体积上限。
                found.sort(Comparator.comparingLong(m -> -((Number) m.get("createdAt")).longValue()));
                if (found.size() > limit) found.remove(found.size() - 1);
                while (found.size() > 1 && ONode.serialize(found).length() > 16000) found.remove(found.size() - 1);
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot search context artifacts", e);
        }
        return found;
    }

    /**
     * 构造检索结果的元数据部分（不含正文），并限制参数体积。
     *
     * <p>参数可能包含很长的查询字符串或代码片段，因此超过 1000 字符时截断，并用同一个计算出的长度
     * 同步设置 {@code argumentsTruncated} 标记，避免截断与标记不一致。
     *
     * @param record 已通过完整性校验的存档记录
     * @return 含 ID、工具名、内容哈希、参数、创建时间的元数据
     */
    private Map<String, Object> metadata(ONode record) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : Arrays.asList("id", "tool", "contentHash")) result.put(key, record.get(key).getString());
        result.put("arguments", record.get("arguments").getString());
        String arguments = record.get("arguments").getString();
        if (arguments.length() > 1000) result.put("arguments", arguments.substring(0, 1000) + "...[truncated]");
        result.put("argumentsTruncated", arguments.length() > 1000);
        result.put("createdAt", record.get("createdAt").getLong());
        return result;
    }

    /**
     * 递归把参数转为有序结构，使同参数的不同键顺序得到同一哈希。
     *
     * <p>Map 换成 {@link TreeMap} 排序，Collection 保持原有顺序（顺序影响语义），其余值原样返回。</p>
     *
     * @param value 任意参数值
     * @return 键有序的等价结构
     */
    private static Object canonical(Object value) {
        if (value instanceof Map) {
            Map<String, Object> result = new TreeMap<>();
            ((Map<?, ?>) value).forEach((k, v) -> result.put(String.valueOf(k), canonical(v)));
            return result;
        }
        if (value instanceof Collection) {
            List<Object> result = new ArrayList<>();
            for (Object item : (Collection<?>) value) result.add(canonical(item));
            return result;
        }
        return value;
    }

    /**
     * 计算文本的 SHA-256 十六进制摘要。
     *
     * <p>手写十六进制转换而不是依赖 {@code String.format}，在压缩触发路径上避免额外的格式化开销；
     * SHA-256 是 JDK 必备算法，缺失时视为运行环境损坏而非可恢复错误。</p>
     *
     * @param text 待摘要文本，按 UTF-8 编码
     * @return 64 位小写十六进制摘要
     */
    public static String digest(String text) {
        try {
            //1. 按 UTF-8 编码后做 SHA-256，保证跨平台哈希一致。
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            //2. 逐字节展开为小写十六进制，与文件名正则 [a-f0-9]{64} 严格对应。
            char[] hex = "0123456789abcdef".toCharArray();
            char[] result = new char[bytes.length * 2];
            for (int i = 0; i < bytes.length; i++) {
                result[i * 2] = hex[(bytes[i] & 0xff) >>> 4];
                result[i * 2 + 1] = hex[bytes[i] & 0x0f];
            }
            return new String(result);
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
