package com.udata.harness.common.support;

import org.noear.solon.ai.agent.exception.LlmNoReturnException;
import org.noear.solon.ai.agent.react.ReActInterceptor;
import org.noear.solon.ai.agent.react.intercept.compress.CompressionUtil;
import org.noear.solon.ai.chat.ChatRequest;
import org.noear.solon.ai.chat.ChatResponse;
import org.noear.solon.ai.chat.event.ChatEvent;
import org.noear.solon.ai.chat.interceptor.CallChain;
import org.noear.solon.ai.chat.interceptor.StreamChain;
import org.noear.solon.net.http.HttpResponseException;
import reactor.core.publisher.Flux;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Solon 4.1.1 rejects HTTP 400 before its compression retry hook can run. */
public final class ContextOverflowRetryInterceptor implements ReActInterceptor {
    @Override public ChatResponse interceptCall(ChatRequest request, CallChain chain) throws IOException {
        try { return chain.doIntercept(request); }
        catch (IOException error) {
            Throwable classified = classify(error);
            if (classified instanceof RuntimeException) throw (RuntimeException) classified;
            throw error;
        }
        catch (RuntimeException error) { throw (RuntimeException) classify(error); }
    }

    @Override public Flux<ChatEvent> interceptStream(ChatRequest request, StreamChain chain) {
        try { return chain.doIntercept(request).onErrorMap(ContextOverflowRetryInterceptor::classify); }
        catch (RuntimeException error) { throw (RuntimeException) classify(error); }
    }

    private static Throwable classify(Throwable error) {
        if (error instanceof RetryableContextOverflowException) return error;
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpResponseException) {
                HttpResponseException response = (HttpResponseException) cause;
                if (response.code() == 400) {
                    byte[] body = response.bodyBytes();
                    boolean overflow = CompressionUtil.isPromptTooLongError(error)
                            || body != null && CompressionUtil.isPromptTooLongError(new RuntimeException(new String(body, StandardCharsets.UTF_8)));
                    if (overflow) return new RetryableContextOverflowException(error);
                }
                break;
            }
        }
        return error;
    }

    /** The SDK recognizes this base type as retryable and still enforces attempts and emitted-stream guards. */
    static final class RetryableContextOverflowException extends LlmNoReturnException {
        RetryableContextOverflowException(Throwable cause) {
            super("context length exceeded; retry through the context recovery hooks");
            initCause(cause);
        }
    }
}
