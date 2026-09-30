package com.udata.harness.common.context;

import org.noear.snack4.ONode;
import java.nio.file.*;
import java.util.*;

/**
 * 结构化任务状态的持久化存储：面向长链路任务的状态真相源。
 *
 * <p>{@code objective} 首次写入后保护：值不变时允许重复提交（幂等），一旦变更即拒绝并提示另建任务；
 * {@code constraints} 与 {@code acceptance} 累积合并，其余列表按 patch 整体替换。
 * 因此“未解决的风险”不会被重复描述漏掉，而“已完成项”可以随阶段推进而重置。</p>
 *
 * <p>{@code evidence} 中的每个 ID 必须是已存在的证据（由 {@link ContextArtifactStore} 校验），
 * 避免状态引用到不存在的证据；每次更新都会递增 {@code revision}。</p>
 *
 * <p>状态是 Agent 自行维护的记录，<b>不是任务已正确完成的自动证明</b>；
 * 目标根本变化时建议新建任务，当前版本没有覆盖目标或移除约束的工具。</p>
 */
public class TaskStateStore {
    /**
     * 单值字段：以 patch 直接覆盖。
     */
    private static final Set<String> STRINGS = new LinkedHashSet<>(Arrays.asList("objective", "phase", "module", "nextStep"));

    /**
     * 列表字段：默认按 patch 替换，其中 constraints 与 acceptance 累积合并。
     */
    private static final Set<String> LISTS = new LinkedHashSet<>(Arrays.asList("acceptance", "constraints", "decisions", "completed", "pending", "questions", "evidence"));

    /**
     * 状态文件：名为 {@code task-state.json} 的会话本地 JSON。
     */
    private final Path path;

    /**
     * 证据库引用，仅用于校验 evidence 中的 ID 是否存在。
     */
    private final ContextArtifactStore artifacts;

    /**
     * 创建任务状态存储。
     *
     * @param root      会话的 {@code <sessionId>.context/} 目录
     * @param artifacts 该会话的证据库，用于校验 evidence 引用
     */
    public TaskStateStore(Path root, ContextArtifactStore artifacts) {
        this.path = root.resolve("task-state.json");
        this.artifacts = artifacts;
    }

    /**
     * 读取当前任务状态。
     *
     * @return 状态副本；状态文件尚未创建时返回空 Map，而非构造默认状态
     */
    @SuppressWarnings("unchecked")
    public synchronized Map<String, Object> get() {
        return Files.exists(path) ? ContextFiles.read(path).toBean(Map.class) : new LinkedHashMap<>();
    }

    /**
     * 按 patch 更新任务状态，并在校验通过后原子写盘。
     *
     * <p>任一项校验失败都会抛异常且<b>不落盘</b>，因此状态要么完全更新，要么保持原样，
     * 不会出现“一半字段已生效”的中间态。</p>
     *
     * @param patch 待更新字段，不能为空
     * @return 更新后的完整状态（含递增后的 {@code revision}）
     * @throws IllegalArgumentException patch 为空、字段未知、类型或长度非法、目标被修改、
     *                                  证据 ID 不存在，或状态总长超过 16000 字符时抛出
     */
    public synchronized Map<String, Object> update(Map<String, Object> patch) {
        //1. 空 patch 无意义，直接拒绝，避免白白递增 revision。
        if (patch == null || patch.isEmpty()) throw new IllegalArgumentException("Task state patch is required");
        Map<String, Object> next = new LinkedHashMap<>(get());
        //2. 逐个字段校验并写入临时副本：单值字段覆盖，列表字段合并或替换。
        for (Map.Entry<String, Object> entry : patch.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (STRINGS.contains(key)) {
                if (!(value instanceof String) || ((String) value).length() > 4000) throw new IllegalArgumentException("Invalid task field: " + key);
                if ("objective".equals(key) && ((String) value).trim().isEmpty()) throw new IllegalArgumentException("Objective cannot be empty");
                //3. 目标保护：值不变时幂等允许，变更则拒绝，防止目标在长链路中被静默改写。
                if ("objective".equals(key) && next.containsKey(key) && !next.get(key).equals(value)) {
                    throw new IllegalArgumentException("Existing objective is protected; create a new task for a different objective");
                }
                next.put(key, value);
            } else if (LISTS.contains(key)) {
                //4. 列表字段用 LinkedHashSet 去重并保持插入顺序；约束与验收条件先继承旧值，实现“只增不减”。
                if (!(value instanceof List) || ((List<?>) value).size() > 100) throw new IllegalArgumentException("Invalid task list: " + key);
                LinkedHashSet<String> items = new LinkedHashSet<>();
                if (("constraints".equals(key) || "acceptance".equals(key)) && next.get(key) instanceof List) {
                    for (Object item : (List<?>) next.get(key)) items.add((String) item);
                }
                for (Object item : (List<?>) value) {
                    if (!(item instanceof String) || ((String) item).length() > 2000) throw new IllegalArgumentException("Invalid task item: " + key);
                    //5. 证据引用必须已归档，避免状态里出现无法恢复的悬空 ID。
                    if ("evidence".equals(key) && !artifacts.contains((String) item)) throw new IllegalArgumentException("Unknown evidence ID: " + item);
                    items.add((String) item);
                }
                next.put(key, new ArrayList<>(items));
            } else throw new IllegalArgumentException("Unknown task field: " + key);
        }
        //6. 递增 revision 并做总长检查，两者都通过才落盘，保证磁盘上始终是一个完整状态。
        next.put("revision", ((Number) next.getOrDefault("revision", 0)).longValue() + 1);
        if (ONode.serialize(next).length() > 16000) throw new IllegalArgumentException("Task state exceeds 16000 characters");
        ContextFiles.write(path, next);
        return next;
    }
}
