package com.udata.harness.common.context;

import org.noear.snack4.ONode;
import org.noear.solon.ai.AiUsage;
import org.noear.solon.ai.agent.react.*;
import org.noear.solon.ai.agent.react.intercept.compress.CompressionUtil;
import org.noear.solon.ai.chat.*;
import org.noear.solon.ai.chat.message.*;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.ToolCall;
import org.noear.solon.ai.chat.content.ContentBlock;
import org.noear.solon.ai.chat.content.AbsMedia;
import java.util.*;

/** Session-local pressure measurement. Cache tokens occupy context even when discounted.
 * Anchors are transient: restart, route/header changes and absent usage fall back to estimation. */
public final class TokenPressureMeter {
    public static final String FRAME = "_udata_pressure_frame";
    public static final String SYSTEM = "_udata_pressure_system";
    private long sequence;
    private long epoch;
    private long observedSequence;
    private Frame anchorFrame;
    private long anchorInput;
    private List<ChatMessage> preparedSystems;
    private Collection<FunctionTool> preparedTools;
    private String preparedHeader;
    private List<ChatMessage> preparedMemory;

    public static final class Frame {
        public final TokenPressureMeter meter;
        public final String header;
        public final long estimatedInput, window, reservedOutput;
        public final List<ChatMessage> memory;
        private final long sequence;
        private final long epoch;
        private Frame(TokenPressureMeter meter, String header, long input, long window, long output,
                      List<ChatMessage> memory, long sequence) {
            this.meter = meter; this.header = header; estimatedInput = input;
            this.window = window; reservedOutput = output; this.memory = memory; this.sequence = sequence; epoch = meter.epoch;
        }
    }
    public static final class Measurement {
        public final Frame frame;
        public final long inputTokens, threshold;
        public final String basis;
        Measurement(Frame frame, long input, long threshold, String basis) {
            this.frame = frame; inputTokens = input; this.threshold = threshold; this.basis = basis;
        }
        public Map<String,Object> detail() {
            Map<String,Object> result = new LinkedHashMap<>();
            result.put("basis", basis); result.put("inputTokens", inputTokens);
            result.put("estimatedInputTokens", frame.estimatedInput); result.put("contextWindow", frame.window);
            result.put("reservedOutputTokens", frame.reservedOutput); result.put("thresholdTokens", threshold);
            return result;
        }
    }

    /** Read effective options without preparing a request or invoking talents/interceptors. */
    public static ChatOptions options(ReActTrace trace) {
        final ChatOptions[] result = new ChatOptions[1];
        trace.getOptions().getChatModel().prompt(Collections.<ChatMessage>emptyList()).options(o -> {
            if (trace.getConfig().getStyle() == ReActStyle.NATIVE_TOOL) {
                o.toolAdd(trace.getOptions().getTools()); o.toolAdd(trace.getProtocolTools());
            }
            o.optionSet(trace.getOptions().getModelOptions().options());
            if (trace.getOptions().getCacheControl() != null) o.cacheControl(trace.getOptions().getCacheControl());
            result[0] = o;
        });
        return result[0];
    }

    public synchronized Measurement measure(ReActTrace trace, String system, List<ChatMessage> memory, double ratio) {
        ChatModel model = trace.getOptions().getChatModel();
        ChatOptions options = options(trace);
        long window = model.getConfig().getContextLength();
        if (window <= 0) window = 128000;
        long output = outputReserve(options.options(), window);
        if (!hasOutputLimit(options.options()) && model.getStandardOrProvider() != null
                && model.getStandardOrProvider().toLowerCase(Locale.ROOT).contains("anthropic")) output = 32000;
        String header = model.getConfig().getApiUrl() + "\n" + model.getProvider() + "\n" + model.getStandard()
                + "\n" + model.getModel() + "\n" + window + "\n" + system + "\n"
                + toolSchema(options.tools()) + "\n" + ONode.serialize(options.options())
                + "\n" + ONode.serialize(options.cacheControl());
        long local = messagesTokens(memory) + textTokens(system) + textTokens(toolSchema(options.tools())) + 8;
        long preparationReserve = Math.min(2000, Math.max(256, (long) Math.ceil(window * ratio * .05)));
        String basis = "estimated";
        long input = local + preparationReserve;
        if (anchorFrame != null && header.equals(anchorFrame.header)) {
            // Keep the local floor: an underestimated/ambiguous usage cannot lower pressure.
            input = Math.max(local + preparationReserve, anchorInput + local - anchorFrame.estimatedInput);
            basis = "usage_calibrated";
        }
        Frame frame = new Frame(this, header, local, window, output, copy(memory), ++sequence);
        long headroom = Math.min(2000, Math.max(64, window / 20));
        long threshold = Math.min((long) Math.floor(window * ratio), window - output - headroom);
        if (threshold <= 0) throw new IllegalStateException("Output reservation leaves no context pressure budget");
        return new Measurement(frame, input, threshold, basis);
    }

    /** Called only for completed main reasoning calls, never for summaries or failed attempts. */
    public synchronized void observe(Frame frame, ChatRequest request, ChatResponse response) {
        if (frame == null || frame.meter != this || frame.epoch != epoch || response == null || response.getError() != null || !response.isTerminal()) return;
        if (frame.sequence <= observedSequence) return;
        observedSequence = frame.sequence;
        if (!Objects.equals(request.getConfig().getModel(), response.getModel()) && response.getModel() != null) return;
        List<ChatMessage> wire = request.getFinalPrompt().getMessages();
        List<ChatMessage> systems = new ArrayList<>();
        int offset = 0;
        while (offset < wire.size() && wire.get(offset) instanceof SystemMessage) systems.add(wire.get(offset++));
        // Only reuse a prepared header when the wire history is exactly this frame's memory.
        if (sameMessages(wire.subList(offset, wire.size()), frame.memory)) {
            preparedSystems = copy(systems); preparedTools = new ArrayList<>(request.getOptions().tools());
            preparedHeader = frame.header;
            preparedMemory = frame.memory;
        } else { preparedHeader = null; }
        AiUsage usage = response.getUsage();
        if (usage == null || usage.promptTokens() < 0) { anchorFrame = null; return; }
        long prompt = usage.promptTokens();
        // Solon 4.1 AnthropicResponseParser already normalizes input + cache creation + cache read
        // into promptTokens. OpenAI/DeepSeek also include cached input. Never add cache fields again.
        if (prompt <= 0) { anchorFrame = null; return; }
        anchorFrame = frame;
        anchorInput = Math.max(prompt, frame.estimatedInput);
    }

    public synchronized List<ChatMessage> systems(String header) {
        return header.equals(preparedHeader) ? copy(preparedSystems) : null;
    }
    public synchronized Collection<FunctionTool> tools(String header) {
        return header.equals(preparedHeader) ? new ArrayList<>(preparedTools) : null;
    }
    public synchronized int commonPrefix(String header, List<ChatMessage> memory) {
        if (!header.equals(preparedHeader)) return 0;
        int count = 0;
        while (count < memory.size() && count < preparedMemory.size()
                && wireJson(memory.get(count)).equals(wireJson(preparedMemory.get(count)))) count++;
        return count + preparedSystems.size();
    }
    public synchronized void reset() {
        epoch++; anchorFrame = null; preparedHeader = null; preparedSystems = null; preparedTools = null; preparedMemory = null;
    }
    public static long outputReserve(Map<String,Object> options, long window) {
        long limit = 0;
        for (String key : Arrays.asList("max_completion_tokens", "max_tokens", "max_output_tokens")) {
            Object value = options.get(key);
            if (value instanceof Number) limit = Math.max(limit, ((Number) value).longValue());
        }
        return limit > 0 ? limit : Math.min(4096, Math.max(128, window / 10));
    }
    private static boolean hasOutputLimit(Map<String,Object> options) {
        for (String key : Arrays.asList("max_completion_tokens", "max_tokens", "max_output_tokens")) {
            Object value = options.get(key);
            if (value instanceof Number && ((Number) value).longValue() > 0) return true;
        }
        return false;
    }
    public static String toolSchema(Collection<FunctionTool> tools) {
        List<Object> schemas = new ArrayList<>();
        for (FunctionTool tool : tools) schemas.add(Arrays.asList(tool.name(), tool.descriptionAndMeta(), tool.inputSchema()));
        return ONode.serialize(schemas);
    }
    public static long textTokens(String text) { return text == null ? 0 : CompressionUtil.getEncoding().countTokens(text); }
    public static long messagesTokens(List<ChatMessage> messages) {
        long tokens = 3;
        for (ChatMessage message : messages) {
            // ChatMessage.toJson is a persistence format: content and TextBlock (and assistant
            // text/raw fields) duplicate the same payload. They are not separate wire tokens.
            tokens += textTokens(message.getContent()) + 4;
            List<ContentBlock> blocks = Collections.emptyList();
            if (message instanceof AssistantMessage) {
                AssistantMessage assistant = (AssistantMessage) message;
                tokens += textTokens(assistant.getThinking());
                for (ToolCall call : assistant.getToolCalls() == null ? Collections.<ToolCall>emptyList() : assistant.getToolCalls())
                    tokens += textTokens(call.getName()) + textTokens(call.getArgumentsStr()) + textTokens(call.getId()) + 10;
                blocks = assistant.getBlocks();
            } else if (message instanceof UserMessage) blocks = ((UserMessage) message).getBlocks();
            else if (message instanceof ToolMessage) {
                ToolMessage tool = (ToolMessage) message;
                tokens += textTokens(tool.getName()) + textTokens(tool.getToolCallId()); blocks = tool.getBlocks();
            }
            for (ContentBlock block : blocks == null ? Collections.<ContentBlock>emptyList() : blocks) if (block instanceof AbsMedia) {
                AbsMedia<?> media = (AbsMedia<?>) block;
                tokens += media.getData() != null && !media.getData().isEmpty() ? Math.max(1, media.getData().length() / 4) + 20
                        : textTokens(media.getUrl()) + 10;
            }
        }
        return tokens;
    }
    public static String wireJson(ChatMessage message) {
        ChatMessage snapshot = ChatMessage.fromJson(ChatMessage.toJson(message));
        snapshot.getContent(); // Normalize lazy text projections on a copy, never mutate live messages.
        if (snapshot instanceof AssistantMessage) {
            AssistantMessage assistant = (AssistantMessage) snapshot;
            assistant.getThinking();
            if (assistant.getToolCalls() != null) for (ToolCall call : assistant.getToolCalls()) call.getArgumentsStr();
        }
        ONode node = ONode.ofJson(ChatMessage.toJson(snapshot));
        // Local identity/timestamps are not sent by the dialect and cannot break cache equivalence.
        node.getObject().remove("metadata"); node.getObject().remove("createdAt");
        return ONode.serialize(canonical(node));
    }
    private static Object canonical(ONode node) {
        if (node.isObject()) {
            Map<String,Object> values = new TreeMap<>();
            for (Map.Entry<String,ONode> entry : node.getObject().entrySet()) values.put(entry.getKey(), canonical(entry.getValue()));
            return values;
        }
        if (node.isArray()) {
            List<Object> values = new ArrayList<>();
            for (ONode value : node.getArray()) values.add(canonical(value));
            return values;
        }
        return node.toBean(Object.class);
    }
    public static boolean sameMessages(List<ChatMessage> a, List<ChatMessage> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (!wireJson(a.get(i)).equals(wireJson(b.get(i)))) return false;
        return true;
    }
    public static List<ChatMessage> copy(List<ChatMessage> messages) {
        List<ChatMessage> result = new ArrayList<>();
        for (ChatMessage m : messages) result.add(ChatMessage.fromJson(ChatMessage.toJson(m)));
        return result;
    }
}
