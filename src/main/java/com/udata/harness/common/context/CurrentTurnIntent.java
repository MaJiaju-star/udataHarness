package com.udata.harness.common.context;

import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.UserMessage;
import org.noear.snack4.ONode;
import java.util.List;
import org.noear.solon.ai.agent.react.ReActTrace;

/** Protect the current user turn when its request is summarized but its recent tool work is retained. */
final class CurrentTurnIntent {
    final boolean split;
    final String request;
    private CurrentTurnIntent(boolean split, String request) { this.split = split; this.request = request; }

    static CurrentTurnIntent prepare(List<ChatMessage> messages, int cutoff) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            if (message instanceof UserMessage && !ContextMessageIds.checkpoint(message) && !ContextMessageIds.internal(message))
                return new CurrentTurnIntent(i < cutoff, message.getContent() == null ? "" : message.getContent());
        }
        return new CurrentTurnIntent(false, "");
    }

    static CurrentTurnIntent prepare(ReActTrace trace, int cutoff) {
        if (trace.getOriginalPrompt() == null) return prepare(trace.getWorkingMemory().getMessages(), cutoff);
        List<ChatMessage> original = trace.getOriginalPrompt().getMessages();
        List<ChatMessage> memory = trace.getWorkingMemory().getMessages();
        for (int i = original.size() - 1; i >= 0; i--) {
            ChatMessage user = original.get(i);
            if (!(user instanceof UserMessage) || ContextMessageIds.checkpoint(user) || ContextMessageIds.internal(user)) continue;
            String id = ContextMessageIds.id(user);
            for (int j = memory.size() - 1; j >= 0; j--) {
                ChatMessage candidate = memory.get(j);
                if (id != null ? id.equals(ContextMessageIds.id(candidate))
                        : candidate instanceof UserMessage && !ContextMessageIds.internal(candidate)
                            && java.util.Objects.equals(user.getContent(), candidate.getContent()))
                    return new CurrentTurnIntent(j < cutoff, user.getContent() == null ? "" : user.getContent());
            }
            // A previous checkpoint in this same run may already cover the original user message.
            for (int j = 0; j < cutoff; j++) if (ContextMessageIds.checkpoint(memory.get(j)))
                return new CurrentTurnIntent(true, user.getContent() == null ? "" : user.getContent());
            return new CurrentTurnIntent(false, "");
        }
        return prepare(memory, cutoff);
    }

    String instructions() {
        if (!split) return "";
        return "\nThe cut splits the CURRENT USER TURN: its early messages are above, while recent work is retained. "
                + "Include the exact heading ## Current Turn Intent. Under it preserve the original request, acceptance "
                + "conditions, user corrections, early decisions/progress and facts needed to understand the retained tail. "
                + "Do not report the task complete just because earlier steps passed. This section is mandatory.";
    }

    boolean valid(String summary) {
        if (!split) return true;
        java.util.regex.Matcher heading = java.util.regex.Pattern.compile("(?m)^## Current Turn Intent[ \\t]*\\r?$").matcher(summary);
        if (!heading.find()) return false;
        String section = summary.substring(heading.end()).trim();
        if (section.startsWith("## ")) return false;
        int nextHeading = section.indexOf("\n## ");
        if (nextHeading >= 0) section = section.substring(0, nextHeading).trim();
        return !section.isEmpty() && !"(none)".equalsIgnoreCase(section) && !"- (none)".equalsIgnoreCase(section);
    }

    String protect(String summary) {
        if (!split || request.isEmpty() || request.length() > 1024) return summary;
        return summary + "\n\nOriginal current user request (verbatim JSON string, quoted for continuity):\n" + ONode.serialize(request);
    }
}
