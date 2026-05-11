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

    public void addMessage(Message message) {
        messages.add(message);
    }

    public void replaceMessages(List<Message> replacement) {
        messages.clear();
        messages.addAll(replacement);
    }

    public int size() {
        return messages.size();
    }

    public void compact(int keepRecentMessages) {
        if (messages.size() <= keepRecentMessages + 2) {
            return;
        }
        List<Message> system = new ArrayList<Message>();
        int firstNonSystem = 0;
        while (firstNonSystem < messages.size() && "system".equals(messages.get(firstNonSystem).role())) {
            system.add(messages.get(firstNonSystem));
            firstNonSystem++;
        }

        int keepStart = Math.max(firstNonSystem, messages.size() - keepRecentMessages);
        while (keepStart > firstNonSystem && "tool".equals(messages.get(keepStart).role())) {
            keepStart--;
        }

        StringBuilder summary = new StringBuilder();
        summary.append("Earlier conversation was compacted locally. Key trace follows:\n");
        for (int i = firstNonSystem; i < keepStart; i++) {
            Message message = messages.get(i);
            summary.append("- ").append(message.role()).append(": ").append(trim(message.content(), 240));
            if (!message.toolCalls().isEmpty()) {
                summary.append(" tool_calls=").append(message.toolCalls().size());
            }
            summary.append('\n');
        }

        List<Message> compacted = new ArrayList<Message>();
        compacted.addAll(system);
        compacted.add(Message.system(summary.toString()));
        compacted.addAll(messages.subList(keepStart, messages.size()));
        replaceMessages(compacted);
    }

    public List<Message> messages() {
        return Collections.unmodifiableList(messages);
    }

    private String trim(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        String normalized = value.replace("\r\n", "\n");
        if (normalized.length() <= maxChars) {
            return normalized;
        }
        return normalized.substring(0, maxChars) + "...";
    }
}
