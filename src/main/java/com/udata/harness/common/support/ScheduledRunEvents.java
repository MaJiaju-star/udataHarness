package com.udata.harness.common.support;

import org.noear.solon.annotation.Component;
import org.noear.snack4.ONode;
import java.util.function.BiConsumer;
import org.slf4j.LoggerFactory;

/** Separates background job bookkeeping from ChatService, including later HITL continuation. */
@Component
public class ScheduledRunEvents {
    public static final String SESSION_KEY = "udata.scheduled.execution";
    private volatile BiConsumer<String, ONode> listener;
    public void listen(BiConsumer<String, ONode> listener) { this.listener = listener; }
    public void event(String id, String json) {
        if (id == null || id.isEmpty() || listener == null) return;
        ONode event = ONode.ofJson(json);
        String type = event.get("type").getString();
        if ("session".equals(type) || "error".equals(type) || "hitl".equals(type) || "run_end".equals(type)) emit(id, event);
    }
    public void ended(String id, String signal, boolean pending) {
        ONode event = new ONode(); event.set("type", "segment_end"); event.set("signal", signal); event.set("pending", pending);
        emit(id, event);
    }
    private void emit(String id, ONode event) {
        if (id == null || id.isEmpty() || listener == null) return;
        try { listener.accept(id, event); }
        catch (RuntimeException e) { LoggerFactory.getLogger(getClass()).error("Cannot save scheduled execution state: {}", id, e); }
    }
}
