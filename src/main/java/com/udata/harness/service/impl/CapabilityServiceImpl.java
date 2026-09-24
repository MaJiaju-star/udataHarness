package com.udata.harness.service.impl;

import com.udata.harness.common.request.CapabilityRequest;
import com.udata.harness.common.request.SkillArchiveRequest;
import com.udata.harness.service.CapabilityService;
import com.udata.harness.service.UserHarnessEngineService;
import com.udata.harness.service.UserWorkspaceService;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.agent.AgentDefinition;
import org.noear.solon.annotation.Component;
import org.noear.solon.annotation.Inject;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * SKILL/Subagent 文件库实现。
 *
 * <p>激活技能时复制整个目录到用户的 {@code .soloncode/skills}，保留 scripts、
 * references 和 assets。ZIP 导入限制压缩/解压大小、条目数和符号链接，并通过
 * containment 检查防止 Zip Slip。</p>
 */
@Component
public class CapabilityServiceImpl implements CapabilityService {
    /**
     * 能力名称合法字符集：小写字母或数字开头，后接小写字母、数字、点、下划线和连字符，总长不超过 64。
     */
    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");

    /**
     * 单个 ZIP 归档允许的最大压缩体积（20 MB），超出则拒绝导入。
     */
    private static final long MAX_ARCHIVE_BYTES = 20L * 1024 * 1024;

    /**
     * ZIP 解压后所有条目累计允许的最大体积（50 MB），用于防御 Zip 炸弹。
     */
    private static final long MAX_EXPANDED_BYTES = 50L * 1024 * 1024;

    /**
     * 用户引擎服务，技能激活后用于刷新对应用户的 HarnessEngine。
     */
    @Inject
    private UserHarnessEngineService engines;

    /**
     * 用户工作区服务，用于定位 {@code .soloncode/skills} 激活目录。
     */
    @Inject
    private UserWorkspaceService workspaces;

    /**
     * 应用数据目录，能力库（Skill 与 Subagent）的物理根路径。
     */
    @Inject("${agent.data-dir:./data}")
    private String dataDir;

    /**
     * 构建无参实例，供容器与测试直接装配；依赖由字段注入。
     */
    public CapabilityServiceImpl() {
    }

    /**
     * 使用给定依赖构造实例，供单元测试直接注入。
     *
     * @param engines 用户引擎服务
     * @param workspaces 用户工作区服务
     * @param dataDir 应用数据目录（能力库根）
     */
    public CapabilityServiceImpl(
            UserHarnessEngineService engines,
            UserWorkspaceService workspaces,
            Path dataDir) {
        this.engines = engines;
        this.workspaces = workspaces;
        this.dataDir = dataDir.toString();
    }

    /**
     * 列出后台 Skill 仓库，并合并当前用户的激活与运行时加载状态。
     *
     * <p>{@code active} 表示目录已经复制到用户工作区，{@code loaded} 表示当前
     * HarnessEngine 已完成刷新；两者分开有助于诊断复制成功但刷新失败的情况。</p>
     *
     * @param userId 当前用户标识
     * @return 技能摘要列表，按名称升序
     */
    public List<Map<String, Object>> listSkills(String userId) {
        Path library = skillLibrary();
        Path activeRoot = activeSkillsRoot(userId);
        List<Map<String, Object>> result = new ArrayList<>();
        try (Stream<Path> paths = Files.list(library)) {
            paths.filter(Files::isDirectory)
                    .filter(path -> Files.isRegularFile(path.resolve("SKILL.md"))
                            || Files.isRegularFile(path.resolve("skill.md")))
                    .forEach(path -> {
                        String name = path.getFileName().toString();
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("name", name);
                        item.put("description", description(path));
                        item.put("active", Files.isDirectory(activeRoot.resolve(name)));
                        item.put("loaded", engines.isSkillLoaded(userId, name));
                        item.put("fileCount", countFiles(path));
                        result.add(item);
                    });
        } catch (IOException e) {
            throw new IllegalStateException("Cannot list Skill library", e);
        }
        result.sort(Comparator.comparing(item -> String.valueOf(item.get("name"))));
        return result;
    }

    /**
     * 合并 Harness 内置 Agent 与服务级自定义 Agent，标记哪些条目允许编辑。
     *
     * @param userId 当前用户标识，用于取用户引擎视图
     * @return Subagent 摘要列表，按名称升序
     */
    public List<Map<String, Object>> listAgents(String userId) {
        HarnessEngine engine = engines.get(userId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (AgentDefinition agent : engine.getAgentManager().getAgents()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", agent.getName());
            item.put("description", agent.getDescription());
            item.put("mountAlias", agent.getMountAlias());
            item.put("editable", "@shared-agents".equals(agent.getMountAlias()));
            item.put("tools", agent.getMetadata().getTools());
            result.add(item);
        }
        result.sort(Comparator.comparing(item -> String.valueOf(item.get("name"))));
        return result;
    }

    /**
     * 以单文件 SKILL.md 形式创建或覆盖后台 Skill 包。
     *
     * <p>适用于只包含 Markdown 指令的简单技能；带脚本与资源的多文件技能应使用
     * {@link #importSkill}。</p>
     *
     * @param request 技能名称与 SKILL.md 内容
     * @throws IllegalArgumentException 名称非法或内容为空/超大时抛出
     */
    public void saveSkill(CapabilityRequest request) {
        String name = requireName(request);
        String content = requireContent(request);
        Path directory = skillLibrary().resolve(name).normalize();
        requireInside(skillLibrary(), directory);
        try {
            Files.createDirectories(directory);
            Files.write(directory.resolve("SKILL.md"), content.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot save Skill package", e);
        }
    }

    /**
     * 导入完整 ZIP Skill 包。
     *
     * <p>先解压到临时目录并定位唯一 Skill 根，再复制到仓库。路径穿越、符号链接逃逸、
     * 文件数和总体积均在解压阶段受限，失败时不会留下半完成目标目录。</p>
     *
     * @param request 技能名称与 ZIP 内容的 Base64 文本
     * @throws IllegalArgumentException 名非法、Base64 无效或 ZIP 超出限制时抛出
     */
    public void importSkill(SkillArchiveRequest request) {
        String name = requireName(request == null ? null : request.getName());
        if (request == null || request.getArchiveBase64() == null) {
            throw new IllegalArgumentException("archiveBase64 is required");
        }
        byte[] archive;
        try {
            archive = Base64.getDecoder().decode(request.getArchiveBase64());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("archiveBase64 is invalid");
        }
        if (archive.length > MAX_ARCHIVE_BYTES) {
            throw new IllegalArgumentException("ZIP is too large (max 20MB)");
        }

        Path library = skillLibrary();
        //1. 解压到带随机后缀的临时目录，避免与并发导入互相干扰。
        Path extracted = library.resolve(".import-" + name + "-" + UUID.randomUUID()).normalize();
        Path replacement = library.resolve(".replace-" + name + "-" + UUID.randomUUID()).normalize();
        try {
            Files.createDirectories(extracted);
            unzip(archive, extracted);
            //2. 定位唯一 Skill 根，先复制到 replacement 再原子替换目标目录。
            Path source = locateSkillRoot(extracted);
            copyTree(source, replacement);
            Path target = library.resolve(name).normalize();
            deleteTree(target);
            Files.move(replacement, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot import Skill ZIP", e);
        } finally {
            //3. 无论成败均清理临时与暂存目录。
            deleteTree(extracted);
            deleteTree(replacement);
        }
    }

    /**
     * 将仓库中的 Skill 完整复制到指定用户工作区并刷新该用户引擎。
     *
     * <p>复制目标仅位于 {@code <workspace>/skills}，不会与其他用户共享可写目录。</p>
     *
     * @param userId 目标用户标识
     * @param name 技能名称（后台仓库）
     * @throws IllegalArgumentException 技能包不存在或缺少 SKILL.md 时抛出
     */
    public void activateSkill(String userId, String name) {
        name = requireName(name);
        Path source = skillLibrary().resolve(name).normalize();
        if (!Files.isDirectory(source) || !hasSkillFile(source)) {
            throw new IllegalArgumentException("Skill package not found");
        }
        Path activeRoot = activeSkillsRoot(userId);
        //1. 先复制到临时暂存目录，再原子替换目标目录，保证激活过程不会留下半成品。
        Path staging = activeRoot.resolve("." + name + "-staging-" + UUID.randomUUID()).normalize();
        Path target = activeRoot.resolve(name).normalize();
        requireInside(activeRoot, target);
        try {
            copyTree(source, staging);
            deleteTree(target);
            Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING);
            //2. 复制成功后刷新该用户引擎，使新能力对后续对话生效。
            engines.refreshUserCapabilities(userId);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot activate Skill", e);
        } finally {
            deleteTree(staging);
        }
    }

    /**
     * 删除用户工作区中的激活副本并刷新引擎；后台仓库原包保持不变。
     *
     * @param userId 目标用户标识
     * @param name 技能名称
     */
    public void deactivateSkill(String userId, String name) {
        name = requireName(name);
        Path activeRoot = activeSkillsRoot(userId);
        Path target = activeRoot.resolve(name).normalize();
        requireInside(activeRoot, target);
        deleteTree(target);
        engines.refreshUserCapabilities(userId);
    }

    /**
     * 保存服务级 Subagent Markdown，并刷新所有已经实例化的用户引擎。
     *
     * @param request Subagent 名称与 Markdown 定义内容
     */
    public void saveAgent(CapabilityRequest request) {
        String name = requireName(request);
        String content = requireContent(request);
        Path root = agentLibrary();
        Path file = root.resolve(name + ".md").normalize();
        requireInside(root, file);
        try {
            Files.write(file, content.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            engines.refreshAgentsForAll();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot save Subagent", e);
        }
    }

    /**
     * 删除后台 Skill 原包；已复制到用户工作区的激活副本不会被隐式删除。
     *
     * @param name 待删除技能名称
     */
    public void deleteSkill(String name) {
        name = requireName(name);
        Path target = skillLibrary().resolve(name).normalize();
        requireInside(skillLibrary(), target);
        deleteTree(target);
    }

    /**
     * 删除服务级自定义 Agent 定义并刷新所有引擎。
     *
     * @param name 待删除 Subagent 名称
     */
    public void deleteAgent(String name) {
        name = requireName(name);
        Path file = agentLibrary().resolve(name + ".md").normalize();
        requireInside(agentLibrary(), file);
        try {
            Files.deleteIfExists(file);
            engines.refreshAgentsForAll();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot delete Subagent", e);
        }
    }

    /**
     * 刷新指定用户的能力缓存。
     *
     * @param userId 目标用户标识
     */
    public void refresh(String userId) {
        engines.refreshUserCapabilities(userId);
    }

    /**
     * 校验能力名称并返回规范化值。
     *
     * <p>严格字符集既用于产品命名，也构成文件路径安全边界的一部分。</p>
     *
     * @param name 原始名称
     * @return 去除首尾空白并转小写后的合法名称
     * @throws IllegalArgumentException 名称不符合白名单时抛出
     */
    public static String requireName(String name) {
        String value = name == null ? "" : name.trim().toLowerCase();
        if (!NAME.matcher(value).matches()) {
            throw new IllegalArgumentException("name must match " + NAME.pattern());
        }
        return value;
    }

    /**
     * 定位后台 Skill 仓库目录，必要目录不存在时创建。
     *
     * @return {@code <data-dir>/skill-library} 绝对路径
     */
    private Path skillLibrary() {
        return ensure(Paths.get(dataDir).toAbsolutePath().normalize().resolve("skill-library"));
    }

    /**
     * 定位服务级 Subagent 目录，必要目录不存在时创建。
     *
     * @return {@code <data-dir>/capabilities/agents} 绝对路径
     */
    private Path agentLibrary() {
        return ensure(Paths.get(dataDir).toAbsolutePath().normalize().resolve("capabilities").resolve("agents"));
    }

    /**
     * 定位用户的激活技能目录，必要目录不存在时创建。
     *
     * @param userId 目标用户标识
     * @return {@code <workspace>/.soloncode/skills} 绝对路径
     */
    private Path activeSkillsRoot(String userId) {
        return ensure(workspaces.getOrCreate(userId).resolve(".soloncode").resolve("skills"));
    }

    /**
     * 确保目录存在，不存在时递归创建。
     *
     * @param path 目标目录
     * @return 传入的同一路径
     */
    private static Path ensure(Path path) {
        try {
            Files.createDirectories(path);
            return path;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create directory: " + path, e);
        }
    }

    private static String requireName(CapabilityRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request is required");
        }
        return requireName(request.getName());
    }

    private static String requireContent(CapabilityRequest request) {
        String content = request.getContent();
        if (content == null || content.trim().isEmpty()) {
            throw new IllegalArgumentException("content is required");
        }
        if (content.getBytes(StandardCharsets.UTF_8).length > 512 * 1024) {
            throw new IllegalArgumentException("content is too large");
        }
        return content;
    }

    private static boolean hasSkillFile(Path directory) {
        return Files.isRegularFile(directory.resolve("SKILL.md"))
                || Files.isRegularFile(directory.resolve("skill.md"));
    }

    private static Path locateSkillRoot(Path extracted) throws IOException {
        if (hasSkillFile(extracted)) {
            return extracted;
        }
        try (Stream<Path> children = Files.list(extracted)) {
            List<Path> directories = children.filter(Files::isDirectory)
                    .collect(Collectors.toList());
            if (directories.size() == 1 && hasSkillFile(directories.get(0))) {
                return directories.get(0);
            }
        }
        throw new IllegalArgumentException("ZIP root must contain SKILL.md");
    }

    /**
     * 在受控目录中解压 ZIP。
     *
     * <p>每个条目都做规范化包含检查，并限制条目数、单文件/总解压大小，防止 Zip Slip
     * 与 Zip Bomb。任何限制触发都会终止整个导入。</p>
     *
     * @param archive ZIP 字节
     * @param target 解压目标目录
     * @throws IOException 写入或解析失败时抛出
     * @throws IllegalArgumentException 超出条目数或解压体积限制、或路径越界时抛出
     */
    private static void unzip(byte[] archive, Path target) throws IOException {
        long total = 0;
        int entries = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            //1. 逐个条目解压，先限制条目数再做包含检查。
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > 1000) {
                    throw new IllegalArgumentException("ZIP has too many entries");
                }
                Path output = target.resolve(entry.getName().replace('\\', '/')).normalize();
                requireInside(target, output);
                if (entry.isDirectory()) {
                    Files.createDirectories(output);
                } else {
                    Files.createDirectories(output.getParent());
                    //2. 流式写出并在累计字节超限时立即中止，防 Zip Bomb。
                    try (java.io.OutputStream stream = Files.newOutputStream(output)) {
                        int read;
                        while ((read = zip.read(buffer)) >= 0) {
                            total += read;
                            if (total > MAX_EXPANDED_BYTES) {
                                throw new IllegalArgumentException("Expanded ZIP is too large");
                            }
                            stream.write(buffer, 0, read);
                        }
                    }
                }
                zip.closeEntry();
            }
        }
    }

    /**
     * 递归复制目录树，拒绝符号链接并逐项做包含检查。
     *
     * @param source 源目录
     * @param target 目标目录
     * @throws IOException 复制失败时抛出
     * @throws IllegalArgumentException 发现符号链接或路径越界时抛出
     */
    private static void copyTree(Path source, Path target) throws IOException {
        try (Stream<Path> paths = Files.walk(source)) {
            for (Path path : paths.collect(Collectors.toList())) {
                if (Files.isSymbolicLink(path)) {
                    throw new IllegalArgumentException("Symbolic links are not allowed");
                }
                Path destination = target.resolve(source.relativize(path)).normalize();
                requireInside(target, destination);
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /**
     * 递归删除目录树；目标不存在时静默返回。
     *
     * @param target 待删除目录
     */
    private static void deleteTree(Path target) {
        if (target == null || !Files.exists(target)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(target)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).collect(Collectors.toList())) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot delete directory: " + target, e);
        }
    }

    /**
     * 统计目录下的普通文件数量。
     *
     * @param directory 目标目录
     * @return 文件数；扫描失败时返回 0
     */
    private static long countFiles(Path directory) {
        try (Stream<Path> paths = Files.walk(directory)) {
            return paths.filter(Files::isRegularFile).count();
        } catch (IOException e) {
            return 0;
        }
    }

    /**
     * 从 SKILL.md 的 front matter 中提取 description 字段作为技能摘要。
     *
     * @param directory 技能目录
     * @return 提取到的 description；未命中或读取失败时返回占位文本
     */
    private static String description(Path directory) {
        Path file = Files.exists(directory.resolve("SKILL.md"))
                ? directory.resolve("SKILL.md") : directory.resolve("skill.md");
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.startsWith("description:")) {
                    return trimmed.substring("description:".length()).trim().replaceAll("^['\"]|['\"]$", "");
                }
            }
        } catch (IOException ignored) {
        }
        return "Skill package";
    }

    /**
     * 验证规范化后的候选路径仍在指定根目录中，否则立即拒绝。
     *
     * @param root 受控根目录
     * @param path 待校验路径
     * @throws IllegalArgumentException 路径越出根目录时抛出
     */
    private static void requireInside(Path root, Path path) {
        if (!path.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("Path escapes managed directory");
        }
    }
}
