package com.udata.harness.common.context;

import org.noear.snack4.ONode;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Fingerprints of the provider's serialized request. Never retains any request content. */
final class RequestPrefixSnapshot {
    private static final int LIMIT = 8192;
    private static final Set<String> STRUCTURAL = new HashSet<>(Arrays.asList("messages", "input", "system", "instructions", "tools"));

    static Map<String, Object> capture(String json, String provider, String endpoint) {
        Map<String, Object> body = ONode.ofJson(json).toBean(Map.class);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("version", 1);
        result.put("requestHash", digest(body));
        result.put("providerHash", digest(provider)); result.put("endpointHash", digest(endpoint));
        result.put("modelHash", digest(body.get("model")));
        String format = body.containsKey("input") ? "responses" : body.containsKey("messages")
                ? body.containsKey("system") ? "anthropic" : "chat_completions" : "unsupported";
        result.put("format", format);
        List<Object> system = new ArrayList<>();
        if (body.containsKey("system")) system.add(body.get("system"));
        if (body.containsKey("instructions")) system.add(body.get("instructions"));
        Object input = body.containsKey("input") ? body.get("input") : body.get("messages");
        List<?> messages = input instanceof List ? (List<?>) input : input == null ? Collections.emptyList() : Collections.singletonList(input);
        List<Map<String, Object>> entries = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            Object item = messages.get(i);
            if (item instanceof Map) {
                Map<?, ?> message = (Map<?, ?>) item;
                Object role = message.get("role");
                if ("system".equals(role) || "developer".equals(role)) system.add(item);
            }
            if (i < LIMIT) entries.add(entry(item, i));
        }
        result.put("systemHash", digest(system)); result.put("systemChars", ONode.serialize(system).length());
        result.put("systemSections", systemSections(system));
        result.put("messagesHash", digest(messages)); result.put("messageCount", messages.size()); result.put("messages", entries);
        List<?> tools = body.get("tools") instanceof List ? (List<?>) body.get("tools") : Collections.emptyList();
        List<Map<String, Object>> declarations = new ArrayList<>();
        for (int i = 0; i < Math.min(LIMIT, tools.size()); i++) {
            Object tool = tools.get(i); Map<String, Object> declaration = entry(tool, i);
            Map<?, ?> definition = tool instanceof Map ? (Map<?, ?>) tool : Collections.emptyMap();
            if (definition.get("function") instanceof Map) definition = (Map<?, ?>) definition.get("function");
            Object name = definition.containsKey("name") ? definition.get("name") : definition.get("type");
            declaration.put("name", identifier(name));
            declaration.put("fields", fields(definition)); declarations.add(declaration);
        }
        result.put("toolsHash", digest(tools)); result.put("toolCount", tools.size()); result.put("tools", declarations);
        Map<String, Object> parameters = new LinkedHashMap<>();
        for (Map.Entry<String, Object> field : body.entrySet()) if (!STRUCTURAL.contains(field.getKey())) parameters.put(identifier(field.getKey()), digest(field.getValue()));
        result.put("parameters", parameters);
        result.put("complete", !"unsupported".equals(format) && messages.size() <= LIMIT && tools.size() <= LIMIT);
        return result;
    }

    private static Map<String, Object> entry(Object value, int index) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("index", index); entry.put("hash", digest(value)); entry.put("chars", ONode.serialize(value).length());
        if (value instanceof Map) {
            Map<?, ?> item = (Map<?, ?>) value;
            entry.put("role", identifier(item.get("role"))); entry.put("type", identifier(item.get("type")));
            Object text = item.get("content");
            String content = text instanceof String ? (String) text : "";
            entry.put("source", "tool".equals(item.get("role")) || "function_call_output".equals(item.get("type")) ? "tool_result"
                    : "function_call".equals(item.get("type")) ? "tool_call"
                    : content.startsWith("<system-reminder>\nMemory references") ? "memory_context"
                    : content.startsWith("<system-reminder>\nCapability catalog:") ? "capability_catalog"
                    : content.startsWith("<system-reminder>\nWorkspace project instructions") ? "project_instructions" : "conversation");
            entry.put("fields", fields(item));
            // Detect shared content inside an Anthropic merged message, without storing its text.
            Object blockContent = item.get("content");
            if (blockContent instanceof List) {
                List<String> blocks = new ArrayList<>(); List<Map<String, Object>> blockFields = new ArrayList<>();
                for (Object block : (List<?>) blockContent) {
                    if (blocks.size() == LIMIT) break;
                    blocks.add(digest(block)); blockFields.add(block instanceof Map ? fields((Map<?, ?>) block) : Collections.emptyMap());
                }
                entry.put("blocks", blocks); entry.put("blockFields", blockFields);
            }
        }
        return entry;
    }

    private static Map<String, Object> fields(Map<?, ?> map) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> field : map.entrySet()) result.put(identifier(field.getKey()), digest(field.getValue()));
        return result;
    }

    static Map<String, Object> compare(Map<String, Object> previous, Map<String, Object> current) {
        Map<String, Object> diff = new LinkedHashMap<>();
        diff.put("baselineAvailable", previous != null);
        if (previous == null) { diff.put("change", "first_request"); return diff; }
        diff.put("previousRequestId", previous.get("id")); diff.put("previousStatus", previous.get("status"));
        diff.put("intervalMs", Math.max(0, number(current.get("timestamp")) - number(previous.get("timestamp"))));
        List<String> changed = new ArrayList<>();
        for (String key : Arrays.asList("providerHash", "endpointHash", "modelHash", "systemHash", "toolsHash", "messagesHash"))
            if (!Objects.equals(previous.get(key), current.get(key))) changed.add(key.replace("Hash", ""));
        diff.put("changedSections", changed);
        List<Map<String, Object>> oldSections = rows(previous, "systemSections"), sections = rows(current, "systemSections");
        for (int i = 0; i < Math.max(oldSections.size(), sections.size()); i++) {
            Map<String, Object> a = i < oldSections.size() ? oldSections.get(i) : Collections.emptyMap();
            Map<String, Object> b = i < sections.size() ? sections.get(i) : Collections.emptyMap();
            if (!Objects.equals(a.get("hash"), b.get("hash"))) {
                Map<String, Object> section = new LinkedHashMap<>(); section.put("index", i);
                section.put("previousSection", a.get("name")); section.put("section", b.get("name"));
                section.put("previousChars", a.get("chars")); section.put("chars", b.get("chars"));
                diff.put("firstSystemDifference", section); break;
            }
        }
        List<Map<String, Object>> oldMessages = rows(previous, "messages"), newMessages = rows(current, "messages");
        int shared = 0; long chars = 0;
        while (shared < Math.min(oldMessages.size(), newMessages.size())
                && Objects.equals(oldMessages.get(shared).get("hash"), newMessages.get(shared).get("hash"))) {
            chars += number(newMessages.get(shared).get("chars")); shared++;
        }
        boolean complete = Boolean.TRUE.equals(previous.get("complete")) && Boolean.TRUE.equals(current.get("complete"));
        boolean appendOnly = complete && shared == oldMessages.size() && newMessages.size() >= oldMessages.size();
        boolean fixed = !changed.contains("provider") && !changed.contains("endpoint") && !changed.contains("model")
                && !changed.contains("system") && !changed.contains("tools");
        diff.put("complete", complete); diff.put("messagePrefixCount", shared); diff.put("messagePrefixChars", chars);
        diff.put("previousMessageCount", previous.get("messageCount")); diff.put("messageCount", current.get("messageCount"));
        diff.put("appendOnly", appendOnly); diff.put("fixedPrefixUnchanged", fixed);
        if (shared < Math.max(oldMessages.size(), newMessages.size())) {
            Map<String, Object> first = new LinkedHashMap<>(); first.put("index", shared);
            Map<String, Object> before = shared < oldMessages.size() ? oldMessages.get(shared) : Collections.emptyMap();
            Map<String, Object> after = shared < newMessages.size() ? newMessages.get(shared) : Collections.emptyMap();
            first.put("kind", before.isEmpty() ? "appended" : after.isEmpty() ? "removed" : "modified");
            first.put("previousRole", before.get("role")); first.put("role", after.get("role"));
            first.put("previousType", before.get("type")); first.put("type", after.get("type"));
            first.put("previousSource", before.get("source")); first.put("source", after.get("source"));
            first.put("previousChars", before.get("chars")); first.put("chars", after.get("chars"));
            first.put("changedFields", changedFields(before.get("fields"), after.get("fields")));
            if (before.get("blocks") instanceof List && after.get("blocks") instanceof List) {
                List<?> a = (List<?>) before.get("blocks"), b = (List<?>) after.get("blocks"); int blocks = 0;
                while (blocks < Math.min(a.size(), b.size()) && Objects.equals(a.get(blocks), b.get(blocks))) blocks++;
                first.put("sharedContentBlocks", blocks);
                List<Map<String, Object>> aFields = rows(before, "blockFields"), bFields = rows(after, "blockFields");
                if (blocks < aFields.size() || blocks < bFields.size())
                    first.put("changedContentBlockFields", changedFields(blocks < aFields.size() ? aFields.get(blocks) : null,
                            blocks < bFields.size() ? bFields.get(blocks) : null));
            }
            diff.put("firstMessageDifference", first);
        }
        List<Map<String, Object>> oldTools = rows(previous, "tools"), newTools = rows(current, "tools");
        List<String> oldNames = names(oldTools), newNames = names(newTools);
        diff.put("toolOrderChanged", !oldNames.equals(newNames));
        List<String> added = new ArrayList<>(newNames); added.removeAll(oldNames); diff.put("addedTools", added);
        List<String> removed = new ArrayList<>(oldNames); removed.removeAll(newNames); diff.put("removedTools", removed);
        List<Map<String, Object>> changedTools = new ArrayList<>();
        Map<Object, Map<String, Object>> byName = new HashMap<>();
        for (Map<String, Object> old : oldTools) byName.put(old.get("name"), old);
        for (Map<String, Object> tool : newTools) {
            Map<String, Object> old = byName.get(tool.get("name"));
            if (old != null && !Objects.equals(tool.get("hash"), old.get("hash"))) {
                Map<String, Object> detail = new LinkedHashMap<>(); detail.put("name", tool.get("name"));
                detail.put("fields", changedFields(old.get("fields"), tool.get("fields"))); changedTools.add(detail);
            }
        }
        diff.put("changedTools", changedTools);
        List<String> parameters = changedFields(previous.get("parameters"), current.get("parameters"));
        diff.put("changedParameters", parameters);
        List<String> prefixParameters = new ArrayList<>(parameters);
        prefixParameters.removeAll(Arrays.asList("stream", "stream_options")); // Transport does not change the model's token prefix.
        diff.put("prefixParameterChanges", prefixParameters);
        diff.put("prefixPreserved", complete && fixed && appendOnly && prefixParameters.isEmpty());
        diff.put("change", !complete ? "comparison_incomplete" : !fixed ? "fixed_prefix_changed" : !appendOnly ? "history_changed"
                : !prefixParameters.isEmpty() ? "parameters_changed" : Objects.equals(previous.get("requestHash"), current.get("requestHash")) ? "identical" : "append_only");
        return diff;
    }

    private static List<String> changedFields(Object before, Object after) {
        Map<?, ?> a = before instanceof Map ? (Map<?, ?>) before : Collections.emptyMap();
        Map<?, ?> b = after instanceof Map ? (Map<?, ?>) after : Collections.emptyMap();
        Set<Object> keys = new LinkedHashSet<>(a.keySet()); keys.addAll(b.keySet()); List<String> changed = new ArrayList<>();
        for (Object key : keys) if (!Objects.equals(a.get(key), b.get(key))) changed.add(identifier(key));
        return changed;
    }
    private static List<Map<String, Object>> rows(Map<String, Object> map, String key) {
        Object value = map.get(key); return value instanceof List ? (List<Map<String, Object>>) value : Collections.emptyList();
    }
    private static List<String> names(List<Map<String, Object>> tools) {
        List<String> names = new ArrayList<>(); for (Map<String, Object> tool : tools) names.add(String.valueOf(tool.get("name"))); return names;
    }
    private static List<Map<String, Object>> systemSections(List<Object> system) {
        List<String> texts = new ArrayList<>(); collectSystemText(system, texts);
        List<Map<String, Object>> result = new ArrayList<>();
        Pattern headings = Pattern.compile("(?m)^# \\[Talent: ([^\\r\\n\\]]+)\\]|^## Project instruction messages");
        for (String text : texts) {
            Matcher matcher = headings.matcher(text); int start = 0; String name = "base_system";
            while (matcher.find() && result.size() < LIMIT) {
                if (matcher.start() > start) result.add(section(name, text.substring(start, matcher.start())));
                name = matcher.group(1) == null ? "project_instruction_policy" : "talent:" + identifier(matcher.group(1));
                start = matcher.start();
            }
            if (result.size() < LIMIT) result.add(section(name, text.substring(start)));
        }
        return result;
    }
    private static Map<String, Object> section(String name, String text) {
        Map<String, Object> section = new LinkedHashMap<>(); section.put("name", name); section.put("hash", digest(text)); section.put("chars", text.length()); return section;
    }
    private static void collectSystemText(Object value, List<String> texts) {
        if (value instanceof String) texts.add((String) value);
        else if (value instanceof List) for (Object item : (List<?>) value) collectSystemText(item, texts);
        else if (value instanceof Map) {
            Map<?, ?> item = (Map<?, ?>) value;
            if (item.containsKey("text")) collectSystemText(item.get("text"), texts);
            else if (item.containsKey("content")) collectSystemText(item.get("content"), texts);
        }
    }
    private static String identifier(Object value) {
        if (value == null) return "unknown";
        String text = String.valueOf(value);
        return text.matches("[A-Za-z0-9_.:-]{1,128}") ? text : "hash-" + digest(text).substring(0, 16);
    }
    private static String digest(Object value) { return ContextArtifactStore.digest(ONode.serialize(value)); }
    static long number(Object value) { return value instanceof Number ? ((Number) value).longValue() : 0; }
}
