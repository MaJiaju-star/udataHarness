package com.udata.harness.common.support;

import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActInterceptor;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.chat.ChatRequest;
import org.noear.solon.ai.chat.ChatResponse;
import org.noear.solon.ai.chat.ModelOptionsAmend;
import org.noear.solon.ai.chat.event.ChatEvent;
import org.noear.solon.ai.chat.interceptor.CallChain;
import org.noear.solon.ai.chat.interceptor.StreamChain;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.core.util.RankEntity;
import reactor.core.publisher.Flux;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Sort only resolved tool maps, preserving original FunctionTool instances and last-writer wins. */
public final class StableToolDefinitionsInterceptor implements ReActInterceptor {
    public static final StableToolDefinitionsInterceptor INSTANCE = new StableToolDefinitionsInterceptor();
    public static final int ORDER = Integer.MIN_VALUE + 2;

    public static void install(ReActAgent.Builder builder) {
        builder.modelOptions(options -> {
            List<RankEntity<ReActInterceptor>> existing = new ArrayList<>(options.interceptors());
            options.interceptors().clear();
            options.interceptorAdd(ORDER, INSTANCE);
            options.interceptorAdd(existing);
        });
    }

    public static void normalize(ModelOptionsAmend<?, ?> options) {
        List<FunctionTool> tools = new ArrayList<>(options.tools());
        tools.sort(Comparator.comparing(FunctionTool::name));
        options.tools().clear();
        options.toolAdd(tools);
    }

    @Override public void onAgentStart(ReActTrace trace) { normalize(trace.getOptions().getModelOptions()); }
    @Override public void onReasonStart(ReActTrace trace, StringBuilder systemPrompt) { normalize(trace.getOptions().getModelOptions()); }
    @Override public ChatResponse interceptCall(ChatRequest request, CallChain chain) throws IOException {
        normalize(request.getOptions());
        return chain.doIntercept(request);
    }
    @Override public Flux<ChatEvent> interceptStream(ChatRequest request, StreamChain chain) {
        normalize(request.getOptions());
        return chain.doIntercept(request);
    }
}
