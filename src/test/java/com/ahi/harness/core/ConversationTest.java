package com.ahi.harness.core;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ConversationTest {
    @Test
    public void compactionKeepsSystemAndRecentMessagesWithArchivePointer() {
        Conversation conversation = new Conversation();
        conversation.addSystem("system");
        for (int i = 0; i < 8; i++) {
            conversation.addUser("user " + i);
            conversation.addAssistant("assistant " + i, java.util.Collections.<ToolCall>emptyList());
        }

        List<Message> archived = conversation.compact(4, ".harness/compactions/test.jsonl");

        assertTrue(archived.size() > 0);
        assertEquals("system", conversation.messages().get(0).role());
        assertTrue(conversation.messages().get(1).content().contains(".harness/compactions/test.jsonl"));
        assertTrue(conversation.messages().size() <= 6);
    }
}
