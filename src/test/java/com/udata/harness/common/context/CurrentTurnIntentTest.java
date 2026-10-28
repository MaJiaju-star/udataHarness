package com.udata.harness.common.context;

import com.udata.harness.common.support.WorkspaceInstructionMessages;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.message.ChatMessage;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class CurrentTurnIntentTest {
    @Test void projectInstructionsAndCheckpointsNeverReplaceTheActualUserTask() {
        ChatMessage task = ChatMessage.ofUser("Fix the payment test");
        ChatMessage guidance = ChatMessage.ofUser("Use JUnit, not a new user task");
        guidance.addMetadata(WorkspaceInstructionMessages.MARKER, true);
        ChatMessage summary = ChatMessage.ofUser("summarized earlier work");
        summary.addMetadata(ContextMessageIds.CHECKPOINT, true);
        CurrentTurnIntent intent = CurrentTurnIntent.prepare(Arrays.asList(task, summary, guidance), 1);
        assertTrue(intent.split);
        assertEquals(task.getContent(), intent.request);
        ChatMessage recall = ChatMessage.ofUser("memory reference, not a task");
        recall.addMetadata(ContextMessageIds.INTERNAL_CONTEXT, true);
        CurrentTurnIntent withRecall = CurrentTurnIntent.prepare(Arrays.asList(task, guidance, recall), 1);
        assertTrue(withRecall.split);
        assertEquals(task.getContent(), withRecall.request);
        ChatMessage literal = ChatMessage.ofUser("<system-reminder>My actual next request</system-reminder>");
        CurrentTurnIntent quoted = CurrentTurnIntent.prepare(Arrays.asList(task, guidance, literal), 1);
        assertFalse(quoted.split);
        assertEquals(literal.getContent(), quoted.request);
    }
}
