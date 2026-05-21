package com.ahi.harness.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * In-memory message list sent to the model.
 *
 * This class knows just enough about roles to keep system messages stable and to
 * compact older turns without breaking assistant/tool pairing.
 */
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

    public int estimatedTokens() {
        // Cheap approximation used for warnings and compaction. It is not a
        // tokenizer, but chars/4 is good enough for budget guardrails.
        int chars = 0;
        for (Message message : messages) {
            chars += length(message.role());
            chars += length(message.content());
            chars += length(message.reasoningContent());
            chars += length(message.toolCallId());
            for (ToolCall call : message.toolCalls()) {
                chars += length(call.id());
                chars += length(call.name());
                chars += length(call.argumentsJson());
            }
            chars += 8;
        }
        return Math.max(1, (chars + 3) / 4);
    }

    public List<Message> compact(int keepRecentMessages, String archivePath) {
        if (messages.size() <= keepRecentMessages + 2) {
            return Collections.emptyList();
        }
        List<Message> system = new ArrayList<Message>();
        int firstNonSystem = 0;
        while (firstNonSystem < messages.size() && "system".equals(messages.get(firstNonSystem).role())) {
            system.add(messages.get(firstNonSystem));
            firstNonSystem++;
        }

        int keepStart = Math.max(firstNonSystem, messages.size() - keepRecentMessages);
        // If the boundary lands on a tool result, pull the paired assistant
        // message back into active context too.
        while (keepStart > firstNonSystem && "tool".equals(messages.get(keepStart).role())) {
            keepStart--;
        }

        List<Message> archived = new ArrayList<Message>(messages.subList(firstNonSystem, keepStart));
        if (archived.isEmpty()) {
            return Collections.emptyList();
        }

        StringBuilder summary = new StringBuilder();
        // Active context carries only an index and a path. The full content stays
        // in the archive so compaction is lossy for the prompt but traceable.
        summary.append("Earlier conversation was compacted locally into an archive file.\n");
        summary.append("Archive path: ").append(archivePath).append('\n');
        summary.append("Archived messages: ").append(archived.size()).append('\n');
        summary.append("This compaction is lossy in active context but traceable: if needed, use read_file or grep on the archive path to recover details.\n");
        summary.append("Brief index:\n");
        for (Message message : archived) {
            summary.append("- ").append(message.role()).append(": ").append(trim(message.content(), 160));
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
        return archived;
    }

    public void compact(int keepRecentMessages) {
        compact(keepRecentMessages, "<no archive>");
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

    private int length(String value) {
        return value == null ? 0 : value.length();
    }
}
