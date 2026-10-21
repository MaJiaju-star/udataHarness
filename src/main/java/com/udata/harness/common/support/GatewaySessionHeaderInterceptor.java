package com.udata.harness.common.support;

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActInterceptor;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.net.http.HttpUtils;

/** Request-local gateway routing identity. Never mutate a shared model or use thread-local state. */
public final class GatewaySessionHeaderInterceptor implements ReActInterceptor {
    public static final String HEADER = "session-id";
    private static final String ROOT_SESSION = "udata.gateway.root-session-id";

    public static String rootSessionId(AgentSession session) {
        String inherited = session.getContext().getAs(ROOT_SESSION);
        return valid(inherited == null ? session.getSessionId() : inherited);
    }

    /** Child sessions retain their own memory identity while sharing the gateway routing identity. */
    public static void inherit(AgentSession parent, AgentSession child) {
        child.getContext().put(ROOT_SESSION, rootSessionId(parent));
    }

    public static void apply(HttpUtils http, String sessionId) {
        http.header(HEADER, valid(sessionId));
    }

    private static String valid(String id) {
        if (id == null || id.isEmpty() || id.length() > 512 || id.indexOf('\r') >= 0 || id.indexOf('\n') >= 0)
            throw new IllegalArgumentException("Invalid gateway session ID");
        return id;
    }

    @Override
    public void onAgentStart(ReActTrace trace) {
        final String sessionId = rootSessionId(trace.getSession());
        // Run options are copies; the immutable captured ID survives retries, compression and async HTTP.
        trace.getOptions().getModelOptions().httpCustomizeAdd(http -> apply(http, sessionId));
    }
}
