package com.udata.harness.common.context;

import com.udata.harness.repository.CompactingFileAgentSession;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.react.intercept.ContextCompressionInterceptor;
import org.noear.solon.ai.agent.react.intercept.compress.CompressionUtil;
import org.noear.solon.ai.chat.ChatRequest;
import org.noear.solon.ai.chat.ChatResponse;
import org.noear.solon.ai.chat.ChatRole;
import org.noear.solon.ai.chat.ChatSession;
import org.noear.solon.ai.AiUsage;
import org.noear.solon.ai.chat.event.ChatEvent;
import org.noear.solon.ai.chat.event.ChatEventType;
import org.noear.solon.ai.chat.interceptor.CallChain;
import org.noear.solon.ai.chat.interceptor.ChatInterceptor;
import org.noear.solon.ai.chat.interceptor.StreamChain;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.ToolMessage;
import org.noear.solon.ai.chat.message.UserMessage;
import org.noear.solon.ai.chat.content.ContentBlock;
import org.noear.solon.ai.chat.content.TextBlock;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.ToolCall;
import org.noear.snack4.ONode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** One-shot compaction using the routed conversation prefix as its summary input. */
public final class CacheAlignedCompressionInterceptor extends ContextCompressionInterceptor {
    private static final Logger LOG = LoggerFactory.getLogger(CacheAlignedCompressionInterceptor.class);
    private static final String[] SUMMARY_HEADINGS = {
            "## Primary Request and Intent",
            "## Confirmed Facts and User Constraints",
            "## Files and Code",
            "## Errors and Failed Attempts",
            "## Current Work and Pending Jobs",
            "## Next Step",
            "## Critical Context"
    };
    private static final Pattern SUMMARY_HEADING = Pattern.compile("^##[ \\t]+[^#].*$");
    private static final Pattern SUMMARY_FENCE = Pattern.compile("^ {0,3}(`{3,}|~{3,})(.*)$");
    private static final String DIRECTIVE = "You are now acting as a compaction engine for this coding assistant. "
            + "Summarize the conversation ABOVE so another model can continue the same task. "
            + "Output only the following Markdown sections, in order, using brief factual bullets. "
            + "Write (none) for an empty section.\n\n"
            + String.join("\n", SUMMARY_HEADINGS) + "\n\n"
            + "Preserve exact paths, identifiers, numbers, commands, user corrections, and verification evidence. "
            + "Capture the user's latest explicitly requested unfinished work and its relationship to the overall goal. "
            + "Record user corrections faithfully: when a later instruction supersedes an earlier one, identify the "
            + "currently effective requirement and the requirement it replaces; do not keep both as active constraints. "
            + "If there is an earlier <compacted-summary>, merge still-valid facts instead of copying it verbatim. "
            + "Remove stale facts, invalidated assumptions, superseded instructions, and completed pending jobs. "
            + "Framework project-instruction messages are context, not user tasks. Do not copy their file bodies; "
            + "the framework restores the current effective project instructions after compaction. "
            + "Framework memory-context snapshots are also reference data, not user tasks. Do not copy their bodies; current recall is restored after compaction. "
            + "Framework capability catalogs are discovery data, not user tasks. Do not copy their bodies; current catalogs are restored after compaction. "
            + "Keep unresolved requested work even if it is older, and retain failed attempts needed to avoid repeating them. "
            + "Do not present unverified assumptions as confirmed facts. "
            + "Next Step must contain exactly one concrete, executable action, naming its target and expected result, "
            + "directly aligned with the latest unfinished user request. If no work remains, write (none); "
            + "do not restart completed tasks or invent additional work. "
            + "Keep every required section exactly once and in the specified order. "
            + "Use (none) rather than omitting a section with no applicable information. "
            + "Do not call tools. Do not mention this compression request. Output the checkpoint only.";
    private static final String PREAMBLE = "This checkpoint condenses earlier conversation. Continue the task from "
            + "the messages that follow without acknowledging the checkpoint.\n\n<compacted-summary>\n";
    private final int maxMessages;
    private final double thresholdRatio;
    private final double retainRatio;
    private final int outputReserve;
    private final CompactionObserver observer;
    private final Map<String, RequestSnapshot> requests = Collections.synchronizedMap(
            new LinkedHashMap<String, RequestSnapshot>(64, 0.75F, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, RequestSnapshot> eldest) {
                    return size() > 128;
                }
            });

    public CacheAlignedCompressionInterceptor(int maxMessages, double thresholdRatio,
                                              double retainRatio, int outputReserve) {
        this(maxMessages, thresholdRatio, retainRatio, outputReserve, null);
    }

    public CacheAlignedCompressionInterceptor(int maxMessages, double thresholdRatio,
                                              double retainRatio, int outputReserve,
                                              CompactionObserver observer) {
        super(Math.max(10, maxMessages), thresholdRatio, null);
        if (retainRatio <= 0 || retainRatio >= thresholdRatio || outputReserve < 1) {
            throw new IllegalArgumentException("Invalid cache-aligned compaction budget");
        }
        this.maxMessages = Math.max(10, maxMessages);
        this.thresholdRatio = thresholdRatio;
        this.retainRatio = retainRatio;
        this.outputReserve = outputReserve;
        this.observer = observer;
    }

    @Override
    public ChatResponse interceptCall(ChatRequest request, CallChain chain) throws IOException {
        com.udata.harness.common.support.StableToolDefinitionsInterceptor.normalize(request.getOptions());
        Object key = request.getOptions().toolContext().get(ChatSession.ATTR_SESSIONID);
        RequestSnapshot snapshot = key == null ? null : RequestSnapshot.of(request);
        ChatResponse response = chain.doIntercept(request);
        if (snapshot != null && response != null && response.getError() == null)
            requests.put(requestKey(key, request.getOptions().agentName()), snapshot);
        return response;
    }

    @Override
    public Flux<ChatEvent> interceptStream(ChatRequest request, StreamChain chain) {
        com.udata.harness.common.support.StableToolDefinitionsInterceptor.normalize(request.getOptions());
        Object key = request.getOptions().toolContext().get(ChatSession.ATTR_SESSIONID);
        if (key == null) return chain.doIntercept(request);
        RequestSnapshot snapshot = RequestSnapshot.of(request);
        return chain.doIntercept(request).doOnNext(event -> {
            // RESPONSE_END is emitted outside ChatInterceptor's stream chain in Solon 4.1.1.
            if (event.is(ChatEventType.STEP_END, ChatEventType.RESPONSE_END)) {
                requests.put(requestKey(key, request.getOptions().agentName()), snapshot);
            }
        });
    }

    @Override
    public void onReasonStart(ReActTrace trace, StringBuilder systemPrompt) {
        compact(trace, systemPrompt.toString(), false);
    }

    @Override
    public boolean onReasonRetry(ReActTrace trace, Throwable error, int attempt, String systemPrompt) {
        return CompressionUtil.isPromptTooLongError(error) && compact(trace, systemPrompt, true);
    }

    private boolean compact(ReActTrace trace, String systemPrompt, boolean force) {
        if (trace.getWorkingMemory() == null || trace.getSession() == null) return false;
        String contextEpoch = ContextEpoch.observe(trace, systemPrompt);
        if (trace.hasStreamSink() && trace.isStreamCancelled()) return false;
        List<ChatMessage> memory = trace.getWorkingMemory().getMessages();
        if (memory.size() < 5) return false;
        long contextLength = trace.getOptions().getChatModel().getConfig().getContextLength();
        if (contextLength <= 0) contextLength = 128000;
        int reserve = (int) Math.min(outputReserve, Math.max(64, contextLength / 8));
        long threshold = Math.min((long) (contextLength * thresholdRatio), contextLength - reserve);
        RequestSnapshot previous = requests.get(requestKey(trace.getSession().getSessionId(), trace.getAgentName()));
        int toolTokens = previous == null
                ? toolTokens(trace.getOptions().getTools()) + toolTokens(trace.getProtocolTools())
                : previous.toolTokens;
        long inputTokens = tokens(memory) + CompressionUtil.countTokens(systemPrompt)
                + toolTokens;
        if (!force && memory.size() <= maxMessages && inputTokens <= threshold) return false;

        long keepBudget = force ? Math.max(32, contextLength / 100)
                : Math.max(32, (long) ((contextLength - reserve) * retainRatio));
        long tailTokens = 0;
        int cutoff = memory.size();
        for (int i = memory.size() - 1; i >= 0; i--) {
            tailTokens += messageTokens(memory.get(i));
            cutoff = i;
            if (tailTokens >= keepBudget && memory.size() - i >= 3) break;
        }
        cutoff = Math.min(cutoff, memory.size() - 3);
        while (cutoff > 0 && !balanced(memory, cutoff)) cutoff--;
        if (cutoff <= 0) return false;

        List<ChatMessage> prefix = new ArrayList<>(memory.subList(0, cutoff));
        CurrentTurnIntent intent = CurrentTurnIntent.prepare(trace, cutoff);
        List<ChatMessage> summaryInput = new ArrayList<>();
        boolean cacheAligned = previous != null && previous.matches(prefix,
                trace.getOptions().getChatModel().getConfig().getNameOrModel(), systemPrompt, contextEpoch);
        if (cacheAligned) summaryInput.addAll(previous.leading);
        else summaryInput.add(ChatMessage.ofSystem(systemPrompt));
        summaryInput.addAll(prefix);
        summaryInput.add(ChatMessage.ofUser(DIRECTIVE + intent.instructions()));
        AtomicBoolean verifiedPrefix = new AtomicBoolean(false);

        try {
            ChatResponse response = trace.getOptions().getChatModel().prompt(summaryInput)
                    .options(options -> {
                        options.autoToolCall(false);
                        options.interceptorAdd(com.udata.harness.common.support.StableToolDefinitionsInterceptor.ORDER,
                                com.udata.harness.common.support.StableToolDefinitionsInterceptor.INSTANCE);
                        if (cacheAligned) options.toolAdd(previous.tools);
                        else {
                            options.toolAdd(trace.getOptions().getTools());
                            options.toolAdd(trace.getProtocolTools());
                        }
                        options.httpCustomizeAdd(trace.getOptions().getModelOptions().httpCustomize());
                        options.max_tokens(Math.min(reserve, 8192));
                        if (cacheAligned) options.interceptorAdd(new PrefixVerifier(previous, verifiedPrefix));
                    }).call();
            if (response == null || response.getError() != null
                    || "length".equals(response.getFinishReason())
                    || "content_filter".equals(response.getFinishReason())
                    || !response.getToolCalls().isEmpty()) {
                auditFailure(trace, "invalid_response");
                return false;
            }
            for (ContentBlock block : response.getBlocks()) {
                if (!(block instanceof TextBlock)) {
                    auditFailure(trace, "unsupported_content");
                    return false;
                }
            }
            String text = response.getContent();
            if (trace.hasStreamSink() && trace.isStreamCancelled()) return false;
            if (text == null || text.trim().isEmpty()) {
                auditFailure(trace, "empty_summary");
                return false;
            }
            if (!intent.valid(text)) { auditFailure(trace, "missing_turn_intent"); return false; }
            if (!validSummaryStructure(text, intent.split)) {
                auditFailure(trace, "invalid_summary_structure");
                return false;
            }
            String summary = PREAMBLE + intent.protect(text.trim()) + "\n</compacted-summary>";
            if (CompressionUtil.countTokens(summary) >= tokens(prefix)) {
                auditFailure(trace, "not_smaller");
                return false;
            }

            // The raw transcript is never rewritten. Save the checkpoint first so a crash cannot
            // leave the in-memory model surface ahead of its durable projection.
            if (trace.getSession() instanceof CompactingFileAgentSession) {
                CompactingFileAgentSession session = (CompactingFileAgentSession) trace.getSession();
                List<ChatMessage> raw = session.getMessages();
                CompactionCheckpointStore.Checkpoint old = session.checkpoints().load(raw);
                int covered = old == null ? 0 : old.coveredMessages;
                covered = ActiveContextView.coveredThrough(raw, covered, prefix);
                session.checkpoints().save(raw, covered, summary,
                        trace.getOptions().getChatModel().getConfig().getNameOrModel(), memory.subList(cutoff, memory.size()));
            }

            List<ChatMessage> projected = new ArrayList<>();
            ChatMessage checkpointMessage = ChatMessage.ofUser(summary);
            checkpointMessage.addMetadata(ContextMessageIds.CHECKPOINT, true);
            if (trace.getSession() instanceof CompactingFileAgentSession) {
                CompactingFileAgentSession session = (CompactingFileAgentSession) trace.getSession();
                checkpointMessage.addMetadata(ContextMessageIds.ENTRY_ID, session.checkpoints().load(session.getMessages()).entryId);
            } else ContextMessageIds.ensure(checkpointMessage);
            projected.add(checkpointMessage);
            projected.addAll(memory.subList(cutoff, memory.size()));
            ContextEpoch.checkpointCommitted(trace, projected);
            if (observer != null) {
                try {
                    observer.record(trace.getSession().getSessionId(),
                            trace.getOptions().getChatModel().getConfig().getNameOrModel(),
                            inputTokens, tokens(projected) + CompressionUtil.countTokens(systemPrompt)
                                    + toolTokens,
                            verifiedPrefix.get(), response.getUsage());
                } catch (RuntimeException e) {
                    LOG.warn("Compaction audit failed ({})", e.getClass().getSimpleName());
                }
            }
            LOG.info("Context checkpoint created: agent={}, before={}, after={}, cacheAligned={}",
                    trace.getAgentName(), memory.size(), projected.size(), verifiedPrefix.get());
            return true;
        } catch (IOException | RuntimeException e) {
            LOG.warn("Context checkpoint failed; retaining original working memory ({})", e.getClass().getSimpleName());
            auditFailure(trace, e instanceof IOException ? "checkpoint_io" : "summary_request");
            return false;
        }
    }

    /** Validate the checkpoint envelope, not the semantic accuracy of its contents. */
    static boolean validSummaryStructure(String summary) {
        return validSummaryStructure(summary, false);
    }

    static boolean validSummaryStructure(String summary, boolean requireTurnIntent) {
        if (summary == null || summary.trim().isEmpty()) return false;
        int nextHeading = 0;
        boolean inSection = false;
        boolean hasContent = false;
        boolean hasTurnIntent = false;
        char fenceCharacter = 0;
        int fenceLength = 0;
        for (String line : summary.split("\\r?\\n", -1)) {
            Matcher fence = SUMMARY_FENCE.matcher(line);
            if (fence.matches()) {
                String marker = fence.group(1);
                if (fenceCharacter == 0) {
                    if (!inSection) return false;
                    fenceCharacter = marker.charAt(0);
                    fenceLength = marker.length();
                } else if (marker.charAt(0) == fenceCharacter && marker.length() >= fenceLength
                        && fence.group(2).trim().isEmpty()) {
                    fenceCharacter = 0;
                }
                continue;
            }
            if (fenceCharacter == 0 && SUMMARY_HEADING.matcher(line).matches()) {
                if (inSection && !hasContent) return false;
                String heading = line.trim();
                if ("## Current Turn Intent".equals(heading)) {
                    if (!inSection || hasTurnIntent) return false;
                    hasTurnIntent = true;
                } else {
                    if (nextHeading >= SUMMARY_HEADINGS.length
                            || !SUMMARY_HEADINGS[nextHeading].equals(heading)) return false;
                    nextHeading++;
                }
                inSection = true;
                hasContent = false;
            } else if (!line.trim().isEmpty()) {
                if (!inSection) return false;
                hasContent = true;
            }
        }
        return fenceCharacter == 0 && nextHeading == SUMMARY_HEADINGS.length && hasContent
                && (!requireTurnIntent || hasTurnIntent);
    }

    private void auditFailure(ReActTrace trace, String code) {
        if (observer == null) return;
        try {
            observer.failed(trace.getSession().getSessionId(),
                    trace.getOptions().getChatModel().getConfig().getNameOrModel(), code);
        } catch (RuntimeException e) {
            LOG.warn("Compaction failure audit unavailable ({})", e.getClass().getSimpleName());
        }
    }

    private static long tokens(List<ChatMessage> messages) {
        long total = 0;
        for (ChatMessage message : messages) total += messageTokens(message);
        return total;
    }

    private static long messageTokens(ChatMessage message) {
        long total = CompressionUtil.countTokens(message.getContent() == null ? "" : message.getContent()) + 4;
        if (message instanceof AssistantMessage) {
            AssistantMessage assistant = (AssistantMessage) message;
            if (assistant.getThinking() != null) total += CompressionUtil.countTokens(assistant.getThinking());
            if (assistant.getToolCalls() != null) {
                for (ToolCall call : assistant.getToolCalls()) {
                    total += CompressionUtil.countTokens(call.getName() + call.getArgumentsStr()) + 10;
                }
            }
            if (assistant.isMultiModal()) total += CompressionUtil.countTokens(ONode.serialize(assistant.getBlocks()));
        } else if (message instanceof UserMessage) {
            UserMessage user = (UserMessage) message;
            if (user.isMultiModal()) total += CompressionUtil.countTokens(ONode.serialize(user.getBlocks()));
        } else if (message instanceof ToolMessage) {
            ToolMessage tool = (ToolMessage) message;
            if (tool.isMultiModal()) total += CompressionUtil.countTokens(ONode.serialize(tool.getBlocks()));
        }
        return total;
    }

    private static int toolTokens(Collection<FunctionTool> tools) {
        int total = 0;
        if (tools == null) return 0;
        for (FunctionTool tool : tools) {
            total += CompressionUtil.countTokens(tool.name() + " " + tool.descriptionAndMeta()
                    + " " + tool.inputSchema());
        }
        return total;
    }

    private static boolean balanced(List<ChatMessage> messages, int end) {
        Set<String> outstanding = new HashSet<>();
        for (int i = 0; i < end; i++) {
            ChatMessage message = messages.get(i);
            if (message instanceof AssistantMessage) {
                Collection<ToolCall> calls = ((AssistantMessage) message).getToolCalls();
                if (calls != null) for (ToolCall call : calls) outstanding.add(call.getId());
            } else if (message instanceof ToolMessage) {
                if (!outstanding.remove(((ToolMessage) message).getToolCallId())) return false;
            }
        }
        return outstanding.isEmpty();
    }

    private static final class RequestSnapshot {
        final String model;
        final List<ChatMessage> leading;
        final List<String> wireMessageHashes;
        final String wireTools;
        final List<FunctionTool> tools;
        final int toolTokens;
        final String epoch;

        private RequestSnapshot(String model, List<ChatMessage> leading,
                                List<String> wireMessageHashes,
                                String wireTools, List<FunctionTool> tools, String epoch) {
            this.model = model;
            this.leading = leading;
            this.wireMessageHashes = wireMessageHashes;
            this.wireTools = wireTools;
            this.tools = tools;
            this.toolTokens = CompressionUtil.countTokens(wireTools);
            this.epoch = epoch;
        }

        static RequestSnapshot of(ChatRequest request) {
            List<ChatMessage> messages = request.getFinalPrompt().getMessages();
            int leadingCount = 0;
            while (leadingCount < messages.size() && messages.get(leadingCount).getRole() == ChatRole.SYSTEM) {
                leadingCount++;
            }
            List<ChatMessage> leading = new ArrayList<>(messages.subList(0, leadingCount));
            ONode body = ONode.ofJson(request.toRequestData());
            List<String> wire = new ArrayList<>();
            if (body.get("messages") != null && body.get("messages").isArray()) {
                for (ONode message : body.get("messages").getArray()) wire.add(hash(message.toJson()));
            }
            return new RequestSnapshot(request.getConfig().getNameOrModel(), leading,
                    wire, body.get("tools") == null ? "" : body.get("tools").toJson(),
                    new ArrayList<>(request.getOptions().tools()),
                    (String) request.getOptions().toolContext().get(ContextEpoch.REQUEST_EPOCH));
        }

        boolean matches(List<ChatMessage> prefix, String currentModel, String currentSystemPrompt, String currentEpoch) {
            return epoch != null && epoch.equals(currentEpoch) && model.equals(currentModel) && !leading.isEmpty()
                    && currentSystemPrompt.equals(leading.get(leading.size() - 1).getContent())
                    && leading.size() + prefix.size() <= wireMessageHashes.size();
        }

        boolean wirePrefixMatches(ChatRequest request) {
            if (wireMessageHashes.isEmpty()) return false;
            ONode body = ONode.ofJson(request.toRequestData());
            if (body.get("messages") == null || !body.get("messages").isArray()) return false;
            List<ONode> messages = body.get("messages").getArray();
            if (messages.size() < 2 || messages.size() - 1 > wireMessageHashes.size()) return false;
            String toolsJson = body.get("tools") == null ? "" : body.get("tools").toJson();
            if (!wireTools.equals(toolsJson)) return false;
            for (int i = 0; i < messages.size() - 1; i++) {
                if (!wireMessageHashes.get(i).equals(hash(messages.get(i).toJson()))) return false;
            }
            return true;
        }
    }

    private static final class PrefixVerifier implements ChatInterceptor {
        private final RequestSnapshot previous;
        private final AtomicBoolean matched;

        private PrefixVerifier(RequestSnapshot previous, AtomicBoolean matched) {
            this.previous = previous;
            this.matched = matched;
        }

        @Override
        public ChatResponse interceptCall(ChatRequest request, CallChain chain) throws IOException {
            matched.set(previous.wirePrefixMatches(request));
            return chain.doIntercept(request);
        }
    }

    private static String hash(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest) result.append(String.format("%02x", value & 0xff));
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String requestKey(Object sessionId, String agentName) {
        return sessionId + ":" + (agentName == null ? "" : agentName);
    }

    public interface CompactionObserver {
        void record(String sessionId, String model, long beforeTokens, long afterTokens,
                    boolean cacheAligned, AiUsage usage);

        default void failed(String sessionId, String model, String code) { }
    }
}
