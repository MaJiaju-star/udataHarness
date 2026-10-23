package com.udata.harness.controller;

import com.udata.harness.service.SessionHistoryService;
import org.noear.solon.annotation.*;
import org.noear.solon.core.handle.Result;
import java.util.Map;
import java.util.function.Supplier;

@Controller @Mapping("/api/sessions")
public class SessionHistoryController {
    @Inject private SessionHistoryService history;
    @Get @Mapping("/history")
    public Result<Map<String, Object>> page(@Header("X-User-Id") String user, @Param("sessionId") String session,
                                           @Param(value="before", required=false) Integer before, @Param(value="size", required=false) Integer size) {
        return action(() -> history.page(user, session, before, size));
    }
    @Get @Mapping("/history-content")
    public Result<Map<String, Object>> content(@Header("X-User-Id") String user, @Param("sessionId") String session,
        @Param("messageIndex") int message, @Param(value="toolIndex", required=false) Integer tool,
        @Param(value="offset", defaultValue="0") int offset, @Param(value="limit", defaultValue="8000") int limit,
        @Param(value="part", required=false) String part) {
        return action(() -> history.content(user, session, message, tool, offset, limit, part));
    }
    private <T> Result<T> action(Supplier<T> work) {
        try { return Result.succeed(work.get()); }
        catch (IllegalArgumentException e) { return Result.failure(400, e.getMessage()); }
    }
}
