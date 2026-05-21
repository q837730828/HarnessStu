package com.ahi.harness.session;

import com.ahi.harness.core.Message;
import com.ahi.harness.core.ToolCall;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Writes the full messages removed from active context during compaction.
 *
 * Conversation keeps only a summary and file path; this store preserves the
 * original material for later read_file/grep recovery.
 */
public class CompactionArchiveStore {
    private final File workspace;
    private final ObjectMapper mapper = new ObjectMapper();

    public CompactionArchiveStore(File workspace) {
        this.workspace = workspace;
    }

    public ArchiveResult archive(List<Message> messages) throws Exception {
        File directory = new File(workspace, ".harness/compactions");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException("Cannot create compaction archive directory: " + directory.getAbsolutePath());
        }
        String name = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
                .replace(':', '-')
                .replace('.', '-')
                + ".jsonl";
        File file = new File(directory, name);
        Writer writer = new OutputStreamWriter(new FileOutputStream(file, false), StandardCharsets.UTF_8);
        try {
            int index = 0;
            for (Message message : messages) {
                // Keep enough structure to reconstruct what the model saw,
                // including tool call ids and arguments.
                ObjectNode node = mapper.createObjectNode();
                node.put("index", index++);
                node.put("role", message.role());
                if (message.content() == null) {
                    node.putNull("content");
                } else {
                    node.put("content", message.content());
                }
                if (message.reasoningContent() != null) {
                    node.put("reasoning_content", message.reasoningContent());
                }
                if (message.toolCallId() != null) {
                    node.put("tool_call_id", message.toolCallId());
                }
                if (!message.toolCalls().isEmpty()) {
                    ArrayNode calls = mapper.createArrayNode();
                    for (ToolCall call : message.toolCalls()) {
                        ObjectNode callNode = mapper.createObjectNode();
                        callNode.put("id", call.id());
                        callNode.put("name", call.name());
                        callNode.put("arguments_json", call.argumentsJson());
                        calls.add(callNode);
                    }
                    node.set("tool_calls", calls);
                }
                writer.write(mapper.writeValueAsString(node));
                writer.write("\n");
            }
        } finally {
            writer.close();
        }
        return new ArchiveResult(relative(file), messages.size());
    }

    private String relative(File file) {
        String root = workspace.getAbsolutePath();
        String path = file.getAbsolutePath();
        if (path.startsWith(root + File.separator)) {
            return path.substring(root.length() + 1).replace(File.separatorChar, '/');
        }
        return path;
    }

    public static class ArchiveResult {
        private final String path;
        private final int messageCount;

        public ArchiveResult(String path, int messageCount) {
            this.path = path;
            this.messageCount = messageCount;
        }

        public String path() {
            return path;
        }

        public int messageCount() {
            return messageCount;
        }
    }
}
