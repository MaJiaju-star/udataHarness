package com.udata.harness.common.request;

import org.junit.jupiter.api.Test;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;

class ChatRequestTest {
    @Test
    void preservesLegacyPromptAndAddsBoundedEditorSnapshot() {
        ChatRequest request = new ChatRequest();
        request.setPrompt(" Explain this ");
        assertEquals("Explain this", request.promptWithContext());
        ChatRequest.CodeContext context = new ChatRequest.CodeContext();
        context.setPath("src/App.java");
        context.setContent("class App {}");
        context.setStartLine(1); context.setEndLine(1);
        request.setContext(Collections.singletonList(context));
        assertTrue(request.promptWithContext().contains("src/App.java:1-1"));
        assertTrue(request.promptWithContext().contains("class App {}"));
        context.setPath("../outside.txt");
        assertThrows(IllegalArgumentException.class, request::promptWithContext);
        context.setPath("src/App.java");
        context.setContent(String.join("", Collections.nCopies(200001, "x")));
        assertThrows(IllegalArgumentException.class, request::promptWithContext);
    }
}
