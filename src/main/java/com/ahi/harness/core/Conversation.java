package com.ahi.harness.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Conversation {
    private final List<Message> messages = new ArrayList<Message>();

    public void addSystem(String content) {
        messages.add(Message.system(content));
    }

    public void addUser(String content) {
        messages.add(Message.user(content));
    }

    public void addAssistant(String content, List<ToolCall> toolCalls) {
        addAssistant(content, null, toolCalls);
    }

    public void addAssistant(String content, String reasoningContent, List<ToolCall> toolCalls) {
        messages.add(Message.assistant(content, reasoningContent, toolCalls));
    }

    public void addToolResult(String toolCallId, String content) {
        messages.add(Message.tool(toolCallId, content));
    }

    public List<Message> messages() {
        return Collections.unmodifiableList(messages);
    }
}
