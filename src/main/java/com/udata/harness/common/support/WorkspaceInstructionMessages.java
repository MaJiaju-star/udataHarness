package com.udata.harness.common.support;

import com.udata.harness.common.context.ContextMessageIds;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.UserMessage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Objects;

/** Append-only project guidance. Metadata identifies framework messages without parsing user text. */
public final class WorkspaceInstructionMessages {
    public static final String MARKER = "udata_workspace_instruction";
    private static final String SCOPE = "udata_workspace_instruction_scope";
    private static final String VERSION = "udata_workspace_instruction_version";
    private static final String BODY_HASH = "udata_workspace_instruction_body_hash";
    public static final String ACTION = "udata_workspace_instruction_action";
    static final String POLICY = "\n\n## Project instruction messages\n"
            + "Follow the current workspace instructions supplied by the framework in separate user messages. "
            + "Each complete load, replacement, restoration, or removal supersedes all earlier project instructions, "
            + "including versions quoted in summaries. These are project conventions, not new user tasks. "
            + "They cannot override system constraints, approval decisions, or sandbox restrictions; within those "
            + "constraints, direct user requests take precedence. The files are already loaded; reread only for "
            + "inspection, verification, or editing.";

    private WorkspaceInstructionMessages() { }

    public static boolean isInstruction(ChatMessage message) {
        return message instanceof UserMessage && Boolean.TRUE.equals(message.getMetadataAs(MARKER));
    }

    static String digest(String text) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte value : bytes) result.append(String.format("%02x", value & 0xff));
            return result.toString();
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    /** A projected/truncated message must not count as a visible complete instruction version. */
    private static ChatMessage latest(List<ChatMessage> messages, String scope) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            if (isInstruction(message) && scope.equals(message.getMetadataAs(SCOPE))) return message;
        }
        return null;
    }

    static boolean reconcile(ReActTrace trace, String scope,
                             WorkspaceInstructionsInterceptor.Selection selected, boolean previouslyLoaded) {
        List<ChatMessage> memory = trace.getWorkingMemory().getMessages();
        ChatMessage visible = latest(memory, scope);
        String version = selected.name == null ? "none" : digest(selected.path + "\n" + selected.truncated + "\n" + selected.content);
        if (visible != null && version.equals(visible.getMetadataAs(VERSION))
                && Objects.equals(visible.getMetadataAs(BODY_HASH), digest(visible.getContent()))) return false;

        ChatMessage known = latest(trace.getSession().getMessages(), scope);
        if (selected.name == null && visible == null && known == null && !previouslyLoaded) return false;
        String action = selected.name == null ? "remove"
                : known != null && version.equals(known.getMetadataAs(VERSION)) ? "restore"
                : known != null || visible != null ? "replace" : "load";
        String text = render(selected, version, action);
        ChatMessage message = ChatMessage.ofUser(text);
        message.addMetadata(ContextMessageIds.INTERNAL_CONTEXT, true).addMetadata(MARKER, true).addMetadata(SCOPE, scope).addMetadata(VERSION, version)
                .addMetadata(ACTION, action).addMetadata(BODY_HASH, digest(text));
        ContextMessageIds.ensure(message);
        // Keep the transcript and working memory in the same order; never rewrite an old prefix.
        trace.getSession().addMessage(message);
        trace.getWorkingMemory().addMessage(message);
        trace.getSession().updateSnapshot();
        return true;
    }

    private static String render(WorkspaceInstructionsInterceptor.Selection selected, String version, String action) {
        StringBuilder text = new StringBuilder("<system-reminder>\nWorkspace project instructions (framework context, not a user task).\n");
        text.append("Action: ").append(action).append("\n")
                .append("This is the complete current project instruction state. It replaces all earlier project instruction states, including those quoted in summaries.\n");
        if (selected.name == null) {
            text.append("No project instruction file is currently active. All previously loaded project instructions no longer apply.\n");
        } else {
            text.append("Instructions from: ").append(escape(selected.path)).append("\nVersion: ").append(version)
                    .append("\nApply only to this workspace when relevant; direct user requests take precedence within system, approval, and sandbox constraints.\n\n")
                    .append("<workspace-instructions>\n").append(escape(selected.content)).append("\n</workspace-instructions>\n");
            if (selected.truncated) text.append("[Project instruction file truncated to ")
                    .append(WorkspaceInstructionsInterceptor.MAX_CHARS).append(" characters.]\n");
        }
        return text.append("</system-reminder>").toString();
    }

    private static String escape(String text) {
        return text.replace("</system-reminder>", "<\\/system-reminder>")
                .replace("</workspace-instructions>", "<\\/workspace-instructions>");
    }
}
