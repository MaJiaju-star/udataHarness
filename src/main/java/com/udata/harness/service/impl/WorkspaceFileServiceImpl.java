package com.udata.harness.service.impl;

import com.udata.harness.service.UserWorkspaceService;
import com.udata.harness.service.WorkspaceFileService;
import org.noear.solon.core.handle.DownloadedFile;
import org.noear.solon.core.handle.UploadedFile;
import org.noear.solon.annotation.Component;
import org.noear.solon.annotation.Inject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardCopyOption;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 文件面板服务实现。
 *
 * <p>限制文件大小、树深度和搜索结果数量，仅跳过符号链接；任何请求路径
 * 都会标准化并验证仍在用户根目录内。</p>
 */
@Component
public class WorkspaceFileServiceImpl implements WorkspaceFileService {
    private static final long MAX_FILE_SIZE = 2L * 1024 * 1024;
    @Inject
    private UserWorkspaceService workspaces;

    public WorkspaceFileServiceImpl() {
    }

    public WorkspaceFileServiceImpl(UserWorkspaceService workspaces) {
        this.workspaces = workspaces;
    }

    /** 构建受深度限制的目录树；空路径表示当前用户工作区根目录。 */
    public List<Map<String, Object>> tree(String userId, String path, int depth) {
        Path root = workspaces.getOrCreate(userId);
        Path directory = resolve(root, path, true);
        if (!Files.isDirectory(directory)) {
            throw new IllegalArgumentException("Directory not found");
        }
        return buildTree(root, directory, Math.max(1, Math.min(depth, 5)), 1);
    }

    /**
     * 读取 UTF-8 文本文件及基础元数据。
     *
     * <p>目录、工作区外路径和不可读文件会在解析阶段被拒绝，避免接口成为任意文件读取通道。</p>
     */
    public Map<String, Object> read(String userId, String path) {
        Path root = workspaces.getOrCreate(userId);
        Path file = resolve(root, path, true);
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("File not found");
        }
        try {
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
            if ("image".equals(previewType) || "video".equals(previewType)) {
                return result;
            }
            if (size > MAX_FILE_SIZE) {
                throw new IllegalArgumentException("File too large (max 2MB)");
            }
            result.put("content", Files.readString(file, StandardCharsets.UTF_8));
            return result;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read file", e);
        }
    }

    /** 在用户工作区内写入文本；父目录不存在时按需创建。 */
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
            Files.writeString(file, content == null ? "" : content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            return read(userId, relative(root, file));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot save file", e);
        }
    }

    /** 在工作区内创建目录，不允许通过绝对路径或 {@code ..} 逃逸。 */
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
     */
    public void delete(String userId, String path) {
        Path root = workspaces.getOrCreate(userId);
        Path target = resolve(root, path, true);
        if (target.equals(root)) {
            throw new IllegalArgumentException("Cannot delete workspace root");
        }
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

    /** 将 multipart 文件流保存到指定相对目录。 */
    @Override
    public Map<String, Object> upload(String userId, String directory, UploadedFile file) {
        if (file == null || file.getName() == null || file.getName().isBlank()) {
            throw new IllegalArgumentException("file is required");
        }
        //1. 文件名只取最后一段，目录仍通过统一 resolve 执行 containment 校验。
        Path root = workspaces.getOrCreate(userId);
        String fileName = Path.of(file.getName()).getFileName().toString();
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

    /** 创建文件下载响应，目录和不存在路径会被拒绝。 */
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

    private List<Map<String, Object>> buildTree(Path root, Path directory, int maxDepth, int currentDepth) {
        List<Map<String, Object>> result = new ArrayList<>();
        try (Stream<Path> paths = Files.list(directory)) {
            paths.filter(path -> !Files.isSymbolicLink(path))
                    .sorted(Comparator.comparing((Path path) -> !Files.isDirectory(path))
                            .thenComparing(path -> path.getFileName().toString().toLowerCase()))
                    .forEach(path -> {
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

    private Map<String, Object> node(Path root, Path path, boolean expanded) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", path.getFileName().toString());
        item.put("path", relative(root, path));
        item.put("type", Files.isDirectory(path) ? "directory" : "file");
        item.put("expanded", expanded);
        return item;
    }

    /** 根据扩展名识别编辑器支持的富媒体预览类型。 */
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

    /** 获取浏览器预览使用的 MIME 类型，系统无法识别时按文件名回退。 */
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
     * @param requireExists 为 true 时同时要求目标已经存在
     */
    private Path resolve(Path root, String relative, boolean requireExists) {
        String value = relative == null ? "" : relative.trim().replace('\\', '/');
        if (value.startsWith("/") || value.matches("^[A-Za-z]:.*")) {
            throw new IllegalArgumentException("Absolute paths are not allowed");
        }
        Path target = root.resolve(value).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("Path escapes workspace");
        }
        Path cursor = root;
        for (Path part : root.relativize(target)) {
            cursor = cursor.resolve(part);
            if (Files.exists(cursor) && Files.isSymbolicLink(cursor)) {
                throw new IllegalArgumentException("Symbolic links are not allowed");
            }
        }
        if (requireExists && !Files.exists(target)) {
            throw new IllegalArgumentException("Path not found");
        }
        return target;
    }

    private String relative(Path root, Path path) {
        return root.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }
}
