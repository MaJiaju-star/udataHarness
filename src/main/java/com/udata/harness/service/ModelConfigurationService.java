package com.udata.harness.service;

import com.udata.harness.repository.ModelConfigurationFiles;
import org.noear.snack4.ONode;
import org.noear.solon.Solon;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.annotation.Component;
import org.noear.solon.annotation.Inject;
import org.noear.solon.core.Props;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** User model catalog. System presets are read-only; secrets never enter API DTOs. */
@Component
public class ModelConfigurationService {
    public static final List<String> PROVIDERS = Collections.unmodifiableList(Arrays.asList(
            "openai", "openai-responses", "anthropic", "gemini", "ollama", "dashscope"));
    @Inject("${agent.data-dir:./data}") private String dataDir;
    private Path testRoot;
    private Props testProps;
    public ModelConfigurationService() { }
    public ModelConfigurationService(Path root, Props props) {
        testRoot = root; testProps = props;
    }
    private Path root() { return testRoot != null ? testRoot : Paths.get(dataDir).toAbsolutePath().normalize(); }
    private Props props() { return testProps != null ? testProps : Solon.cfg(); }
    private Path file(String user) {
        Path file = root().resolve("users").resolve(UserWorkspaceService.requireUserId(user)).resolve("models.json");
        ModelConfigurationFiles.protect(file);
        return file;
    }
    @SuppressWarnings("unchecked")
    private Map<String, Object> state(String user) {
        UserWorkspaceService.requireUserId(user);
        try {
            if (!Files.exists(file(user))) {
                Map<String, Object> state = new LinkedHashMap<>();
                state.put("revision", "initial"); state.put("models", new ArrayList<>()); state.put("defaults", new LinkedHashMap<>());
                return state;
            }
            Map<String, Object> state = ONode.ofJson(new String(Files.readAllBytes(file(user)), StandardCharsets.UTF_8)).toBean(Map.class);
            if (!(state.get("models") instanceof List) || !(state.get("defaults") instanceof Map))
                throw new IOException("Invalid model configuration");
            return state;
        } catch (IOException | RuntimeException e) { throw new IllegalStateException("模型配置读取失败，请检查 models.json", e); }
    }
    private void write(String user, Map<String, Object> state) {
        state.put("revision", UUID.randomUUID().toString());
        try {
            Path target = file(user); Files.createDirectories(target.getParent());
            Path temp = Files.createTempFile(target.getParent(), "models-", ".tmp");
            try {
                Files.write(temp, ONode.serialize(state).getBytes(StandardCharsets.UTF_8));
                try { Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                catch (AtomicMoveNotSupportedException e) { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING); }
            } finally { Files.deleteIfExists(temp); }
        } catch (IOException e) { throw new IllegalStateException("模型配置保存失败", e); }
    }
    private static String string(Object value) { return value instanceof String ? ((String) value).trim() : ""; }
    @SuppressWarnings("unchecked") private static List<Map<String, Object>> rows(Map<String, Object> state) {
        return (List<Map<String, Object>>) state.get("models");
    }
    public synchronized String revision(String user) { return string(state(user).get("revision")); }
    public List<ChatConfig> systemModels() {
        Props root = props(), common = root.getProp("agent.model");
        Collection<Props> items = root.getListedProp("agent.model.models");
        boolean single = items.isEmpty();
        if (single) items = Collections.singletonList(common);
        List<ChatConfig> result = new ArrayList<>();
        for (Props item : items) {
            String model = item.get("model", single ? common.get("model", "") : "");
            if (model.trim().isEmpty()) continue;
            ChatConfig config = new ChatConfig();
            config.setName(item.get("name", single ? common.get("default", model) : model)); config.setModel(model);
            config.setApiUrl(item.get("api-url", common.get("api-url", "")));
            config.setApiKey(item.get("api-key", common.get("api-key", "")));
            config.setProvider(item.get("provider", common.get("provider", "openai")));
            config.setContextLength(item.getLong("context-length", common.getLong("context-length", 128000)));
            result.add(config);
        }
        return result;
    }
    private ChatConfig config(String user, Map<String, Object> row) {
        ChatConfig config = new ChatConfig();
        config.setName(string(row.get("id"))); config.setModel(string(row.get("model")));
        config.setApiUrl(string(row.get("apiUrl"))); config.setProvider(string(row.get("provider")));
        config.setContextLength(((Number) row.get("contextLength")).longValue());
        if (needsApiKeyReset(row)) throw new IllegalStateException("旧版模型密钥需要在后台重新填写并保存");
        config.setApiKey(string(row.get("apiKey")));
        return config;
    }
    public synchronized List<ChatConfig> effective(String user) {
        Map<String, ChatConfig> configs = new LinkedHashMap<>();
        for (ChatConfig config : systemModels()) configs.put(config.getNameOrModel(), config);
        for (Map<String, Object> row : rows(state(user))) if (!Boolean.FALSE.equals(row.get("enabled")) && !needsApiKeyReset(row)) {
            ChatConfig config = config(user, row); configs.put(config.getNameOrModel(), config);
        }
        return new ArrayList<>(configs.values());
    }
    private static Map<String, Object> safe(ChatConfig config, String source, String label) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", config.getNameOrModel()); out.put("name", config.getNameOrModel()); out.put("label", label);
        out.put("model", config.getModel()); out.put("provider", config.getProvider());
        out.put("apiUrl", config.getApiUrl()); out.put("contextLength", config.getContextLength());
        out.put("hasApiKey", config.getApiKey() != null && !config.getApiKey().isEmpty());
        out.put("enabled", true); out.put("source", source);
        return out;
    }
    public synchronized Map<String, Object> list(String user) {
        Map<String, Object> state = state(user), result = new LinkedHashMap<>();
        List<Map<String, Object>> list = new ArrayList<>();
        for (ChatConfig config : systemModels()) list.add(safe(config, "system", config.getNameOrModel()));
        for (Map<String, Object> row : rows(state)) {
            Map<String, Object> item = new LinkedHashMap<>(row);
            item.remove("secretRef"); item.remove("apiKey");
            item.put("hasApiKey", !string(row.get("apiKey")).isEmpty());
            item.put("apiKeyNeedsReset", needsApiKeyReset(row));
            item.put("source", "personal"); item.put("name", row.get("id")); list.add(item);
        }
        result.put("models", list); result.put("defaults", new LinkedHashMap<>((Map<?, ?>) state.get("defaults")));
        result.put("providers", PROVIDERS); result.put("revision", state.get("revision"));
        return result;
    }
    private Map<String, Object> validated(Map<String, Object> input, String id) {
        Map<String, Object> row = new LinkedHashMap<>();
        String label = string(input.get("label")), model = string(input.get("model")), provider = string(input.get("provider"));
        if (label.isEmpty() || label.length() > 80 || model.isEmpty() || model.length() > 200)
            throw new IllegalArgumentException("请填写模型显示名称和实际模型标识（名称最多 80 字符）");
        if (!PROVIDERS.contains(provider)) throw new IllegalArgumentException("不支持的模型协议");
        String url = string(input.get("apiUrl"));
        try {
            URI uri = URI.create(url);
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getFragment() != null || uri.getRawQuery() != null)
                throw new IllegalArgumentException();
        } catch (IllegalArgumentException e) { throw new IllegalArgumentException("API 地址须为不含账号、查询参数的 HTTP(S) 地址"); }
        long context;
        try { context = Long.parseLong(String.valueOf(input.get("contextLength"))); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("上下文窗口须为整数"); }
        if (context < 1024 || context > 100000000) throw new IllegalArgumentException("上下文窗口须在 1024～100000000 之间");
        row.put("id", id); row.put("label", label); row.put("model", model); row.put("provider", provider);
        row.put("apiUrl", url); row.put("contextLength", context); row.put("enabled", !Boolean.FALSE.equals(input.get("enabled")));
        return row;
    }
    public synchronized String save(String user, Map<String, Object> input) {
        Map<String, Object> state = state(user);
        String id = string(input.get("id"));
        Map<String, Object> previous = rows(state).stream().filter(row -> row.get("id").equals(string(input.get("id")))).findFirst().orElse(null);
        if (!id.isEmpty() && previous == null) throw new IllegalArgumentException("个人模型不存在；系统模型请先复制");
        String copyFrom = string(input.get("copyFromId"));
        Map<String, Object> copied = copyFrom.isEmpty() ? null : rows(state).stream()
                .filter(row -> copyFrom.equals(row.get("id"))).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("待复制的个人模型不存在"));
        if (id.isEmpty()) id = "user-" + UUID.randomUUID();
        Map<String, Object> row = validated(input, id);
        String key = string(input.get("apiKey"));
        if (key.length() > 16384 || key.contains("\n") || key.contains("\r")) throw new IllegalArgumentException("API Key 格式无效");
        Map<String, Object> source = previous != null ? previous : copied;
        boolean clear = Boolean.TRUE.equals(input.get("clearApiKey"));
        if (key.isEmpty() && !clear && source != null && needsApiKeyReset(source))
            throw new IllegalArgumentException("旧版模型密钥需要重新填写，或勾选清除密钥后保存");
        row.put("apiKey", !key.isEmpty() ? key : clear || source == null ? "" : string(source.get("apiKey")));
        if (previous != null) rows(state).remove(previous);
        rows(state).add(row);
        clearInvalidDefaults(state);
        write(user, state);
        return id;
    }
    @SuppressWarnings("unchecked")
    private void clearInvalidDefaults(Map<String, Object> state) {
        Set<String> valid = new HashSet<>();
        for (ChatConfig model : systemModels()) valid.add(model.getNameOrModel());
        for (Map<String, Object> model : rows(state)) if (!Boolean.FALSE.equals(model.get("enabled"))) valid.add(string(model.get("id")));
        ((Map<String, Object>) state.get("defaults")).values().removeIf(value -> !valid.contains(string(value)));
    }
    public synchronized void remove(String user, String id) {
        Map<String, Object> state = state(user);
        if (!rows(state).removeIf(row -> id.equals(row.get("id")))) throw new IllegalArgumentException("个人模型不存在");
        clearInvalidDefaults(state); write(user, state);
    }
    public synchronized void defaults(String user, Map<String, Object> choices) {
        Map<String, Object> state = state(user), defaults = new LinkedHashMap<>();
        Set<String> valid = new HashSet<>(); for (ChatConfig config : effective(user)) valid.add(config.getNameOrModel());
        for (String mode : Arrays.asList("default", "economy", "standard", "super")) {
            String selected = string(choices.get(mode));
            if (!selected.isEmpty() && !valid.contains(selected)) throw new IllegalArgumentException("默认模型不存在或已停用");
            if (!selected.isEmpty()) defaults.put(mode, selected);
        }
        state.put("defaults", defaults); write(user, state);
    }
    public synchronized String preferred(String user, String mode) {
        Map<?, ?> choices = (Map<?, ?>) state(user).get("defaults");
        List<ChatConfig> models = effective(user);
        String selected = string(choices.get(mode));
        if (selected.isEmpty()) selected = string(choices.get("default"));
        if (selected.isEmpty()) selected = props().get("agent.modes." + mode + ".model", "");
        if (selected.isEmpty()) selected = props().get("agent.model.default", "");
        final String candidate = selected;
        return models.stream().anyMatch(config -> candidate.equals(config.getNameOrModel())) ? candidate
                : models.isEmpty() ? "" : models.get(0).getNameOrModel();
    }
    public Map<String, Object> testConnection(String user, Map<String, Object> input) {
        ChatConfig selected;
        synchronized (this) {
            String id = string(input.get("id"));
            if (id.isEmpty()) id = string(input.get("copyFromId"));
            final String sourceId = id;
            Map<String, Object> previous = rows(state(user)).stream().filter(row -> sourceId.equals(row.get("id"))).findFirst().orElse(null);
            Map<String, Object> row = validated(input, "test");
            boolean clear = Boolean.TRUE.equals(input.get("clearApiKey"));
            if (previous != null && needsApiKeyReset(previous) && !clear && string(input.get("apiKey")).isEmpty())
                throw new IllegalArgumentException("旧版模型密钥需要重新填写");
            row.put("apiKey", previous == null || clear ? "" : previous.get("apiKey"));
            selected = config(user, row);
            if (!string(input.get("apiKey")).isEmpty()) selected.setApiKey(string(input.get("apiKey")));
        }
        long started = System.nanoTime();
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            org.noear.solon.ai.chat.ChatResponse response = ChatModel.of(selected).timeout(Duration.ofSeconds(20)).build()
                    .prompt("Reply only OK.").options(options -> options.optionSet("max_tokens", 32)).call();
            if (response == null || response.getError() != null) throw new IllegalStateException("Invalid response");
            result.put("success", true); result.put("message", "连接成功，模型已返回响应");
        } catch (Exception e) {
            // Provider errors may echo credentials/headers. Never return their raw body.
            result.put("success", false); result.put("message", "连接失败，请检查协议、API 地址、模型标识、密钥及网络（最长等待 20 秒）");
        }
        result.put("durationMs", (System.nanoTime() - started) / 1000000L); return result;
    }
    private static boolean needsApiKeyReset(Map<String, Object> row) {
        return !row.containsKey("apiKey") && !string(row.get("secretRef")).isEmpty();
    }
}
