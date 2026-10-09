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
 * 普通 update 保护既有目标；用户明确变更要求时可用 revise，留下原子审计记录。
 * 引用原文用于来源追溯，不自动证明改动获得了语义上的授权。</p>
 */
public class TaskStateStore {
    private static final Object[] LOCKS = new Object[64];
    static { for (int i = 0; i < LOCKS.length; i++) LOCKS[i] = new Object(); }
    private Object writeLock() { return LOCKS[(path.toAbsolutePath().normalize().hashCode() & Integer.MAX_VALUE) % LOCKS.length]; }
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
        Map<String,Object> document = document();
        return document.get("formatVersion") instanceof Number && ((Number) document.get("formatVersion")).intValue() == 2
                ? new LinkedHashMap<>((Map<String,Object>) document.get("state")) : document;
    }

    @SuppressWarnings("unchecked")
    private Map<String,Object> document() { return Files.exists(path) ? ContextFiles.read(path).toBean(Map.class) : new LinkedHashMap<>(); }

    @SuppressWarnings("unchecked")
    private List<Map<String,Object>> history() {
        Object history = document().get("history");
        return history instanceof List ? new ArrayList<>((List<Map<String,Object>>) history) : new ArrayList<>();
    }

    /** Full audit persists with state in one atomic file; model sees bounded previews only on request. */
    public synchronized Map<String,Object> historyPreview(int limit) {
        if (limit < 1 || limit > 20) throw new IllegalArgumentException("History limit must be 1..20");
        List<Map<String,Object>> history = history(), result = new ArrayList<>();
        for (int i = history.size() - 1; i >= 0 && result.size() < limit; i--) {
            Map<String,Object> record = history.get(i), preview = new LinkedHashMap<>();
            preview.put("revision", record.get("revision")); preview.put("source", record.get("source"));
            preview.put("reason", record.get("reason"));
            String changes = ONode.serialize(record.get("changes"));
            preview.put("changesPreview", changes.substring(0, Math.min(800, changes.length())));
            preview.put("changesTruncated", changes.length() > 800);
            result.add(preview);
            if (ONode.serialize(result).length() > 14000) { result.remove(result.size()-1); break; }
        }
        Map<String,Object> response = new LinkedHashMap<>(); response.put("totalRevisions", history.size());
        response.put("recent", result); response.put("returnedRevisions", result.size()); return response;
    }

    private void persist(Map<String,Object> state, List<Map<String,Object>> history) {
        if (ONode.serialize(state).length() > 16000) throw new IllegalArgumentException("Task state exceeds 16000 characters");
        Map<String,Object> envelope = new LinkedHashMap<>(); envelope.put("formatVersion", 2);
        envelope.put("state", state); envelope.put("history", history); ContextFiles.write(path, envelope);
    }

    /** Amend only requirements, with optimistic concurrency and a quote from the current user request. */
    public synchronized Map<String,Object> revise(long expectedRevision, Map<String,Object> patch, String quote, String reason, String currentUser) {
        synchronized (writeLock()) { return reviseLocked(expectedRevision, patch, quote, reason, currentUser); }
    }
    @SuppressWarnings("unchecked")
    private Map<String,Object> reviseLocked(long expectedRevision, Map<String,Object> patch, String quote, String reason, String currentUser) {
        Map<String,Object> before = get();
        if (((Number) before.getOrDefault("revision", 0)).longValue() != expectedRevision) throw new IllegalArgumentException("Stale task revision; read task_state_get before revising");
        if (quote == null || quote.trim().length() < 8 || quote.length() > 2000 || currentUser == null || !currentUser.contains(quote))
            throw new IllegalArgumentException("source_quote must cite at least 8 characters verbatim from the current user request");
        if (reason == null || reason.trim().isEmpty() || reason.length() > 1000 || patch == null || patch.isEmpty()) throw new IllegalArgumentException("Revision patch and reason are required");
        Map<String,Object> next = new LinkedHashMap<>(before), changes = new LinkedHashMap<>();
        for (Map.Entry<String,Object> entry : patch.entrySet()) {
            String key = entry.getKey(); Object value = entry.getValue();
            if ("objective".equals(key)) {
                if (!(value instanceof String) || ((String) value).trim().isEmpty() || ((String) value).length() > 4000) throw new IllegalArgumentException("Invalid objective");
            } else if ("constraints".equals(key) || "acceptance".equals(key)) {
                if (!(value instanceof List) || ((List<?>) value).size() > 100) throw new IllegalArgumentException("Invalid requirement list");
                for (Object item : (List<?>) value) if (!(item instanceof String) || ((String) item).length() > 2000) throw new IllegalArgumentException("Invalid requirement item");
                value = new ArrayList<>(new LinkedHashSet<>((List<?>) value));
            } else throw new IllegalArgumentException("Only objective, constraints and acceptance can be revised");
            if (!Objects.equals(before.get(key), value)) {
                Map<String,Object> change = new LinkedHashMap<>(); change.put("before", before.get(key)); change.put("after", value);
                changes.put(key, change); next.put(key, value);
            }
        }
        if (changes.isEmpty()) return before;
        long revision = expectedRevision + 1; next.put("revision", revision);
        Map<String,Object> source = new LinkedHashMap<>(); source.put("kind", "user_quote"); source.put("quote", quote);
        source.put("messageHash", ContextArtifactStore.digest(currentUser)); source.put("revision", revision);
        // A quote records provenance; it is not a semantic proof that the amendment is authorized.
        Map<String,Object> origins = new LinkedHashMap<>();
        if (before.get("requirementSources") instanceof Map) origins.putAll((Map<String,Object>) before.get("requirementSources"));
        for (String key : changes.keySet()) origins.put(key, source);
        next.put("requirementSources", origins);
        Map<String,Object> event = new LinkedHashMap<>(); event.put("revision", revision); event.put("time", System.currentTimeMillis());
        event.put("source", source); event.put("reason", reason); event.put("changes", changes);
        List<Map<String,Object>> history = history(); history.add(event); persist(next, history); return next;
    }

    @SuppressWarnings("unchecked")
    private boolean retired(String key, String item, List<Map<String,Object>> history) {
        for (Map<String,Object> event : history) {
            Map<String,Object> changes = (Map<String,Object>) event.get("changes");
            if (!(changes.get(key) instanceof Map)) continue;
            Map<String,Object> change = (Map<String,Object>) changes.get(key);
            if (change.get("before") instanceof List && ((List<?>) change.get("before")).contains(item)
                    && !((List<?>) change.get("after")).contains(item)) return true;
        }
        return false;
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
        synchronized (writeLock()) { return updateLocked(patch); }
    }
    @SuppressWarnings("unchecked")
    private Map<String,Object> updateLocked(Map<String,Object> patch) {
        //1. 空 patch 无意义，直接拒绝，避免白白递增 revision。
        if (patch == null || patch.isEmpty()) throw new IllegalArgumentException("Task state patch is required");
        Map<String,Object> before = get();
        Map<String, Object> next = new LinkedHashMap<>(before);
        List<Map<String,Object>> audit = history();
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
                    if (("constraints".equals(key) || "acceptance".equals(key)) && !items.contains(item) && retired(key, (String) item, audit))
                        throw new IllegalArgumentException("Superseded requirement cannot be reintroduced by update; use task_state_revise");
                    items.add((String) item);
                }
                next.put(key, new ArrayList<>(items));
            } else throw new IllegalArgumentException("Unknown task field: " + key);
        }
        //6. 递增 revision 并做总长检查，两者都通过才落盘，保证磁盘上始终是一个完整状态。
        next.put("revision", ((Number) next.getOrDefault("revision", 0)).longValue() + 1);
        Map<String,Object> origins = new LinkedHashMap<>();
        if (next.get("requirementSources") instanceof Map) origins.putAll((Map<String,Object>) next.get("requirementSources"));
        for (String key : Arrays.asList("objective", "constraints", "acceptance")) {
            if (patch.containsKey(key) && !Objects.equals(before.get(key), next.get(key))) {
                Map<String,Object> origin = new LinkedHashMap<>(); origin.put("kind", "agent_maintained"); origin.put("revision", next.get("revision")); origins.put(key, origin);
            }
        }
        if (!origins.isEmpty()) next.put("requirementSources", origins);
        persist(next, audit);
        return next;
    }
}
