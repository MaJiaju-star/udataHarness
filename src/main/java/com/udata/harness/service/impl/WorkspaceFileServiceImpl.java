package com.udata.harness.service.impl;

import com.udata.harness.common.domain.GlobalSearchItem;
import com.udata.harness.common.domain.GlobalSearchResponse;
import com.udata.harness.common.domain.SearchMatch;
import com.udata.harness.common.request.GlobalSearchRequest;
import com.udata.harness.service.UserWorkspaceService;
import com.udata.harness.service.WorkspaceFileService;
import org.noear.solon.Utils;
import org.noear.solon.core.handle.DownloadedFile;
import org.noear.solon.core.handle.UploadedFile;
import org.noear.solon.annotation.Component;
import org.noear.solon.annotation.Inject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardCopyOption;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 文件面板服务实现。
 *
 * <p>限制文件大小、树深度和搜索结果数量，仅跳过符号链接；任何请求路径
 * 都会标准化并验证仍在用户根目录内。</p>
 */
@Component
public class WorkspaceFileServiceImpl implements WorkspaceFileService {
    /**
     * 允许在线打开或保存的单文件最大体积（2 MB），超出仅返回元数据。
     */
    private static final long MAX_FILE_SIZE = 2L * 1024 * 1024;

    /**
     * 全局搜索单次返回的命中文件数量上限。
     */
    private static final int MAX_SEARCH_RESULTS = 500;

    /**
     * 单个文件在全局搜索中最多返回的匹配行数。
     */
    private static final int MAX_MATCHES_PER_FILE = 50;

    /**
     * 用户工作区服务，用于解析并校验请求路径对应的用户根目录。
     */
    @Inject
    private UserWorkspaceService workspaces;

    /**
     * 构建无参实例，供容器与测试直接装配；workspaces 由字段注入。
     */
    public WorkspaceFileServiceImpl() {
    }

    /**
     * 使用给定工作区服务构造实例，供单元测试直接注入。
     *
     * @param workspaces 用户工作区服务
     */
    public WorkspaceFileServiceImpl(UserWorkspaceService workspaces) {
        this.workspaces = workspaces;
    }

    /**
     * 构建受深度限制的目录树；空路径表示当前用户工作区根目录。
     *
     * @param userId 当前用户标识
     * @param path 相对目录路径，可为空表示根目录
     * @param depth 递归深度，会被裁剪到 1 到 5 之间
     * @return 适合文件面板渲染的树节点列表
     */
    public List<Map<String, Object>> tree(String userId, String path, int depth) {
        //1. 解析并校验目标确实是工作区内的目录，再做深度裁剪。
        Path root = workspaces.getOrCreate(userId);
        Path directory = resolve(root, path, true);
        if (!Files.isDirectory(directory)) {
            throw new IllegalArgumentException("Directory not found");
        }
        //2. depth 被裁剪到 [1,5]，避免恶意深处递归。
        return buildTree(root, directory, Math.max(1, Math.min(depth, 5)), 1);
    }

    /**
     * 读取 UTF-8 文本文件及基础元数据。
     *
     * <p>目录、工作区外路径和不可读文件会在解析阶段被拒绝，避免接口成为任意文件读取通道。</p>
     *
     * @param userId 当前用户标识
     * @param path 文件相对路径
     * @return 包含路径、名称、大小、预览类型与文本内容的元数据；图片/视频只返回元数据
     * @throws IllegalArgumentException 目标不存在、目录或超出 2MB 时抛出
     */
    public Map<String, Object> read(String userId, String path) {
        Path root = workspaces.getOrCreate(userId);
        Path file = resolve(root, path, true);
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("File not found");
        }
        try {
            //1. 先采集与内容无关的元数据，并识别预览类型。
            long size = Files.size(file);
            String contentType = detectContentType(file);
            String previewType = previewType(file);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("path", relative(root, file));
            result.put("name", file.getFileName().toString());
            result.put("size", size);
            result.put("contentType", contentType);
            result.put("previewType", previewType);
            result.put("modifiedAt", Files.getLastModifiedTime(file).toMillis());
            //2. 富媒体由前端按 URL 预览，不把二进制内容放进 JSON。
            if ("image".equals(previewType) || "video".equals(previewType)) {
                return result;
            }
            //3. 文本按大小限制后以 UTF-8 读取完整内容。
            if (size > MAX_FILE_SIZE) {
                throw new IllegalArgumentException("File too large (max 2MB)");
            }
            result.put("content", new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
            return result;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read file", e);
        }
    }

    /**
     * 在用户工作区内写入文本；父目录不存在时按需创建。
     *
     * @param userId 当前用户标识
     * @param path 目标文件相对路径
     * @param content 完整文本内容，null 视为空文件
     * @return 写入后的文件元数据（与 {@link #read} 返回结构一致）
     * @throws IllegalArgumentException 内容超出 2MB 时抛出
     */
    public Map<String, Object> save(String userId, String path, String content) {
        if (content != null && content.getBytes(StandardCharsets.UTF_8).length > MAX_FILE_SIZE) {
            throw new IllegalArgumentException("File too large (max 2MB)");
        }
        Path root = workspaces.getOrCreate(userId);
        Path file = resolve(root, path, false);
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.write(file, (content == null ? "" : content).getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            return read(userId, relative(root, file));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot save file", e);
        }
    }

    /**
     * 在工作区内创建目录，不允许通过绝对路径或 {@code ..} 逃逸。
     *
     * @param userId 当前用户标识
     * @param path 待创建目录的相对路径
     */
    public void createDirectory(String userId, String path) {
        Path root = workspaces.getOrCreate(userId);
        Path directory = resolve(root, path, false);
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create directory", e);
        }
    }

    /**
     * 删除工作区内文件或空目录。
     *
     * <p>工作区根目录本身不可删除；目录必须为空，避免一次请求递归移除大量用户数据。</p>
     *
     * @param userId 当前用户标识
     * @param path 待删除对象的相对路径
     * @throws IllegalArgumentException 目标为工作区根目录时抛出
     */
    public void delete(String userId, String path) {
        Path root = workspaces.getOrCreate(userId);
        Path target = resolve(root, path, true);
        //1. 工作区根目录是安全边界，永远不可删除。
        if (target.equals(root)) {
            throw new IllegalArgumentException("Cannot delete workspace root");
        }
        //2. 自底向上逐个删除；任一失败立即中断，避免留下半删除状态。
        try (Stream<Path> paths = Files.walk(target)) {
            paths.sorted(Comparator.reverseOrder()).forEach(item -> {
                try {
                    Files.delete(item);
                } catch (IOException e) {
                    throw new IllegalStateException("Cannot delete " + relative(root, item), e);
                }
            });
        } catch (IOException e) {
            throw new IllegalStateException("Cannot delete path", e);
        }
    }

    /**
     * 在可见文本文件中执行大小受限的关键字搜索。
     *
     * <p>不按目录名称过滤结果，版本控制目录、依赖目录和构建产物均可被搜索。</p>
     *
     * @param userId 当前用户标识
     * @param keyword 相对路径匹配关键字
     * @return 命中的节点元数据；最多返回 200 条，且不深层递归
     * @throws IllegalArgumentException 关键字为空时抛出
     */
    public List<Map<String, Object>> search(String userId, String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) {
            throw new IllegalArgumentException("keyword is required");
        }
        Path root = workspaces.getOrCreate(userId);
        String needle = keyword.trim().toLowerCase();
        List<Map<String, Object>> result = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(root, 20)) {
            paths.filter(path -> !path.equals(root))
                    .filter(path -> !Files.isSymbolicLink(path))
                    .filter(path -> relative(root, path).toLowerCase().contains(needle))
                    .limit(200)
                    .forEach(path -> result.add(node(root, path, false)));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot search workspace", e);
        }
        return result;
    }

    /**
     * 按文件名称或文本内容执行全局检索。
     *
     * <p>不排除隐藏目录；内容模式跳过符号链接、二进制文件和超过 2MB 的文件。</p>
     *
     * @param userId 当前用户标识
     * @param request 检索词、mode（name/content）、扩展名过滤与结果上限
     * @return 按文件分组且包含行列信息的检索结果
     * @throws IllegalArgumentException 请求或关键字为空时抛出
     */
    @Override
    public GlobalSearchResponse globalSearch(String userId, GlobalSearchRequest request) {
        if (request == null || request.getKeyword() == null || request.getKeyword().trim().isEmpty()) {
            throw new IllegalArgumentException("keyword is required");
        }
        Path root = workspaces.getOrCreate(userId);
        String keyword = request.getKeyword().trim();
        //1. 归一化检索参数：模式默认 name，结果上限裁剪到配置最大值。
        String mode = "content".equalsIgnoreCase(request.getMode()) ? "content" : "name";
        int maxResults = request.getMaxResults() == null
                ? MAX_SEARCH_RESULTS : Math.max(1, Math.min(request.getMaxResults(), MAX_SEARCH_RESULTS));
        Set<String> extensions = normalizeExtensions(request.getExtensions());

        //2. 初始化响应并按文件系统顺序扫描普通文件。
        GlobalSearchResponse response = new GlobalSearchResponse();
        response.setKeyword(keyword);
        response.setMode(mode);
        try (Stream<Path> paths = Files.walk(root)) {
            Iterator<Path> iterator = paths.iterator();
            while (iterator.hasNext() && response.getTotalMatches() < maxResults) {
                Path file = iterator.next();
                if (!Files.isRegularFile(file) || Files.isSymbolicLink(file)
                        || !matchesExtension(file, extensions)) {
                    continue;
                }
                if ("content".equals(mode)) {
                    try {
                        searchFileContent(root, file, keyword, maxResults, response);
                    } catch (IOException ignored) {
                        // 单个不可读文件不应中断整个工作区检索。
                    }
                } else {
                    searchFileName(root, file, keyword, response);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot search workspace", e);
        }

        //3. 汇总文件数并标记达到上限的响应，方便前端提示结果已截断。
        response.setTotalFiles(response.getItems().size());
        response.setTruncated(response.getTotalMatches() >= maxResults);
        return response;
    }

    /**
     * 文件名模式同时匹配文件名和相对于工作区的路径。
     *
     * @param root 用户工作区根目录
     * @param file 待匹配文件
     * @param keyword 关键字
     * @param response 聚合写入的响应对象
     */
    private void searchFileName(Path root, Path file, String keyword, GlobalSearchResponse response) {
        String path = relative(root, file);
        if (!path.toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT))) {
            return;
        }
        response.getItems().add(new GlobalSearchItem(path, file.getFileName().toString()));
        response.setTotalMatches(response.getTotalMatches() + 1);
    }

    /**
     * 读取一个受限文本文件并记录逐行匹配位置。
     *
     * @param root 用户工作区根目录
     * @param file 待检索文件
     * @param keyword 关键字（大小写不敏感）
     * @param maxResults 全局命中数上限
     * @param response 聚合写入的响应对象
     * @throws IOException 读取文件失败时抛出，由调用方按单文件失败忽略
     */
    private void searchFileContent(Path root, Path file, String keyword, int maxResults,
                                   GlobalSearchResponse response) throws IOException {
        if (Files.size(file) > MAX_FILE_SIZE) {
            return;
        }
        byte[] bytes = Files.readAllBytes(file);
        if (isBinary(bytes)) {
            return;
        }
        String content = new String(bytes, StandardCharsets.UTF_8);
        String[] lines = content.split("\\R", -1);
        String needle = keyword.toLowerCase(Locale.ROOT);
        GlobalSearchItem item = null;

        for (int lineIndex = 0; lineIndex < lines.length
                && response.getTotalMatches() < maxResults; lineIndex++) {
            String line = lines[lineIndex];
            String normalized = line.toLowerCase(Locale.ROOT);
            int fromIndex = 0;
            while (fromIndex <= normalized.length()) {
                int columnIndex = normalized.indexOf(needle, fromIndex);
                if (columnIndex < 0 || response.getTotalMatches() >= maxResults
                        || (item != null && item.getMatches().size() >= MAX_MATCHES_PER_FILE)) {
                    break;
                }
                if (item == null) {
                    item = new GlobalSearchItem(relative(root, file), file.getFileName().toString());
                    response.getItems().add(item);
                }
                item.getMatches().add(new SearchMatch(
                        lineIndex + 1,
                        columnIndex + 1,
                        columnIndex + keyword.length() + 1,
                        previewLine(line, columnIndex, keyword.length())));
                response.setTotalMatches(response.getTotalMatches() + 1);
                fromIndex = columnIndex + Math.max(1, keyword.length());
            }
        }
    }

    /**
     * 将可选扩展名过滤统一转换成不带点的小写集合。
     *
     * @param values 原始扩展名列表，可为 null
     * @return 归一化后的扩展名集合
     */
    private Set<String> normalizeExtensions(List<String> values) {
        Set<String> extensions = new HashSet<>();
        if (values == null) {
            return extensions;
        }
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                extensions.add(value.trim().toLowerCase(Locale.ROOT).replaceFirst("^\\.", ""));
            }
        }
        return extensions;
    }

    /**
     * 判断文件扩展名是否满足可选过滤条件。
     *
     * @param file 待判定文件
     * @param extensions 允许的扩展名集合；为空表示不过滤
     * @return 满足过滤条件时为 true
     */
    private boolean matchesExtension(Path file, Set<String> extensions) {
        if (extensions.isEmpty()) {
            return true;
        }
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        int index = name.lastIndexOf('.');
        String extension = index < 0 ? "" : name.substring(index + 1);
        return extensions.contains(extension);
    }

    /**
     * 使用 NUL 字节快速识别不适合文本检索的二进制文件。
     *
     * @param bytes 文件字节
     * @return 含 NUL 字节时为 true
     */
    private boolean isBinary(byte[] bytes) {
        for (byte value : bytes) {
            if (value == 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * 生成围绕命中位置的单行摘要，避免超长行撑大响应。
     *
     * @param line 原始行
     * @param columnIndex 命中列下标（0 起）
     * @param keywordLength 关键字长度
     * @return 带省略号截断标记的摘要文本
     */
    private String previewLine(String line, int columnIndex, int keywordLength) {
        int start = Math.max(0, columnIndex - 90);
        int end = Math.min(line.length(), columnIndex + keywordLength + 140);
        return (start > 0 ? "…" : "") + line.substring(start, end) + (end < line.length() ? "…" : "");
    }

    /**
     * 将 multipart 文件流保存到指定相对目录。
     *
     * @param userId 当前用户标识
     * @param directory 目标相对目录，可为空表示工作区根目录
     * @param file 上传文件
     * @return 保存后的节点元数据
     * @throws IllegalArgumentException 文件为空或名称缺失时抛出
     */
    @Override
    public Map<String, Object> upload(String userId, String directory, UploadedFile file) {
        if (file == null || Utils.isBlank(file.getName())) {
            throw new IllegalArgumentException("file is required");
        }
        //1. 文件名只取最后一段，目录仍通过统一 resolve 执行 containment 校验。
        Path root = workspaces.getOrCreate(userId);
        String fileName = Paths.get(file.getName()).getFileName().toString();
        String parent = directory == null ? "" : directory.trim().replace('\\', '/');
        Path target = resolve(root, parent.isEmpty() ? fileName : parent + "/" + fileName, false);

        //2. 直接复制上传流，支持文本和二进制文件。
        try {
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            Files.copy(file.getContent(), target, StandardCopyOption.REPLACE_EXISTING);
            return node(root, target, false);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot upload file", e);
        }
    }

    /**
     * 创建文件下载响应，目录和不存在路径会被拒绝。
     *
     * @param userId 当前用户标识
     * @param path 文件相对路径
     * @return 供框架流式发出的下载对象
     * @throws IllegalArgumentException 目标不是普通文件时抛出
     */
    @Override
    public DownloadedFile download(String userId, String path) {
        Path root = workspaces.getOrCreate(userId);
        Path file = resolve(root, path, true);
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("File not found");
        }
        try {
            return new DownloadedFile(file.toFile());
        } catch (IOException e) {
            throw new IllegalStateException("Cannot download file", e);
        }
    }

    /**
     * 递归构建目录树节点。
     *
     * @param root 用户工作区根目录，用于计算相对路径
     * @param directory 当前目录
     * @param maxDepth 最大递归深度
     * @param currentDepth 当前深度（1 起）
     * @return 目录下的节点列表；目录优先排序
     */
    private List<Map<String, Object>> buildTree(Path root, Path directory, int maxDepth, int currentDepth) {
        List<Map<String, Object>> result = new ArrayList<>();
        //1. 只遍历当前层，跳过符号链接，并按“目录优先 + 名称升序”稳定排序。
        try (Stream<Path> paths = Files.list(directory)) {
            paths.filter(path -> !Files.isSymbolicLink(path))
                    .sorted(Comparator.comparing((Path path) -> !Files.isDirectory(path))
                            .thenComparing(path -> path.getFileName().toString().toLowerCase()))
                    .forEach(path -> {
                        //2. 未达深度上限的子目录递归展开。
                        Map<String, Object> item = node(root, path, currentDepth < maxDepth);
                        if (Files.isDirectory(path) && currentDepth < maxDepth) {
                            item.put("children", buildTree(root, path, maxDepth, currentDepth + 1));
                        }
                        result.add(item);
                    });
        } catch (IOException e) {
            throw new IllegalStateException("Cannot list directory", e);
        }
        return result;
    }

    /**
     * 构造单个树节点的元数据。
     *
     * @param root 用户工作区根目录
     * @param path 目标路径
     * @param expanded 前端是否默认展开
     * @return 包含 name/path/type/expanded 的节点
     */
    private Map<String, Object> node(Path root, Path path, boolean expanded) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", path.getFileName().toString());
        item.put("path", relative(root, path));
        item.put("type", Files.isDirectory(path) ? "directory" : "file");
        item.put("expanded", expanded);
        return item;
    }

    /**
     * 根据扩展名识别编辑器支持的富媒体预览类型。
     *
     * @param file 目标文件
     * @return image/video/html/markdown/text 之一
     */
    private String previewType(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        if (name.matches(".*\\.(png|jpe?g|gif|webp|svg|bmp|ico|avif)$")) {
            return "image";
        }
        if (name.matches(".*\\.(mp4|webm|ogv|mov|m4v)$")) {
            return "video";
        }
        if (name.matches(".*\\.(html?|xhtml)$")) {
            return "html";
        }
        if (name.matches(".*\\.(md|markdown|mdown|mkd)$")) {
            return "markdown";
        }
        return "text";
    }

    /**
     * 获取浏览器预览使用的 MIME 类型，系统无法识别时按文件名回退。
     *
     * @param file 目标文件
     * @return MIME 类型；完全无法识别时返回 application/octet-stream
     * @throws IOException 探测过程失败时抛出
     */
    private String detectContentType(Path file) throws IOException {
        String contentType = Files.probeContentType(file);
        if (contentType == null) {
            contentType = URLConnection.guessContentTypeFromName(file.getFileName().toString());
        }
        return contentType == null ? "application/octet-stream" : contentType;
    }

    /**
     * 将客户端相对路径解析为规范化绝对路径，并验证仍位于用户根目录下。
     *
     * @param root 用户工作区根目录
     * @param relative 客户端传入的相对路径，可为空
     * @param requireExists 为 true 时同时要求目标已经存在
     * @return 规范化后的目标绝对路径
     * @throws IllegalArgumentException 传入绝对路径、越出工作区或途经符号链接时抛出
     */
    private Path resolve(Path root, String relative, boolean requireExists) {
        String value = relative == null ? "" : relative.trim().replace('\\', '/');
        //1. 拒绝绝对路径（含 Windows 盘符），只接受工作区内相对路径。
        if (value.startsWith("/") || value.matches("^[A-Za-z]:.*")) {
            throw new IllegalArgumentException("Absolute paths are not allowed");
        }
        //2. 规范化后必须仍以工作区根目录开头，阻止 ../ 越界。
        Path target = root.resolve(value).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("Path escapes workspace");
        }
        //3. 逐级检查路径组件，任何一层是符号链接都拒绝，防止通过链接逃逸。
        Path cursor = root;
        for (Path part : root.relativize(target)) {
            cursor = cursor.resolve(part);
            if (Files.exists(cursor) && Files.isSymbolicLink(cursor)) {
                throw new IllegalArgumentException("Symbolic links are not allowed");
            }
        }
        //4. 可选地要求目标已存在。
        if (requireExists && !Files.exists(target)) {
            throw new IllegalArgumentException("Path not found");
        }
        return target;
    }

    /**
     * 计算相对于工作区根目录的斜杠风格路径。
     *
     * @param root 用户工作区根目录
     * @param path 目标路径
     * @return 统一使用 {@code /} 分隔的相对路径
     */
    private String relative(Path root, Path path) {
        return root.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }
}
