package com.ahi.harness.session;

import com.ahi.harness.ConsoleLog;
import com.ahi.harness.core.Conversation;
import com.ahi.harness.core.Message;
import com.ahi.harness.core.ToolCall;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Append-only session log used for /resume.
 *
 * JSONL keeps every event independently readable and makes partial recovery
 * possible even if a later write is interrupted.
 */
public class JsonlSessionStore implements SessionStore {
    private final ObjectMapper mapper = new ObjectMapper();
    private final File file;
    private final ConsoleLog log;

    public JsonlSessionStore(File directory, ConsoleLog log) {
        this.log = log;
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException("Cannot create session directory: " + directory.getAbsolutePath());
        }
        String name = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
                .replace(':', '-')
                .replace('.', '-')
                + ".jsonl";
        this.file = new File(directory, name);
        this.log.info("HARNESS", "session_log = " + file.getAbsolutePath());
    }

    @Override
    public synchronized void append(String type, String content) throws Exception {
        ObjectNode node = mapper.createObjectNode();
        node.put("ts", DateTimeFormatter.ISO_INSTANT.format(Instant.now()));
        node.put("type", type);
        node.put("content", content == null ? "" : content);
        write(node);
    }

    @Override
    public synchronized void appendMessage(Message message) throws Exception {
        // Store the structured provider-facing message, not just display text, so
        // replay can preserve assistant tool calls and tool observations.
        ObjectNode node = mapper.createObjectNode();
        node.put("ts", DateTimeFormatter.ISO_INSTANT.format(Instant.now()));
        node.put("type", "message");
        ObjectNode payload = mapper.createObjectNode();
        payload.put("role", message.role());
        if (message.content() == null) {
            payload.putNull("content");
        } else {
            payload.put("content", message.content());
        }
        if (message.reasoningContent() != null) {
            payload.put("reasoning_content", message.reasoningContent());
        }
        if (message.toolCallId() != null) {
            payload.put("tool_call_id", message.toolCallId());
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
            payload.set("tool_calls", calls);
        }
        node.set("message", payload);
        write(node);
    }

    public File file() {
        return file;
    }

    public static List<File> listSessions(File directory) {
        if (!directory.exists() || !directory.isDirectory()) {
            return Collections.emptyList();
        }
        File[] files = directory.listFiles();
        if (files == null) {
            return Collections.emptyList();
        }
        List<File> sessions = new ArrayList<File>();
        for (File file : files) {
            if (file.isFile() && file.getName().endsWith(".jsonl")) {
                sessions.add(file);
            }
        }
        Collections.sort(sessions);
        return sessions;
    }

    public static File latestSession(File directory) {
        List<File> sessions = listSessions(directory);
        return sessions.isEmpty() ? null : sessions.get(sessions.size() - 1);
    }

    public static List<Message> loadMessages(File file) throws Exception {
        List<Message> messages = new ArrayList<Message>();
        if (file == null || !file.exists()) {
            return messages;
        }
        ObjectMapper mapper = new ObjectMapper();
        BufferedReader reader = new BufferedReader(new InputStreamReader(new java.io.FileInputStream(file), StandardCharsets.UTF_8));
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                JsonNode node = mapper.readTree(line);
                if ("message".equals(node.path("type").asText())) {
                    // Current format: structured message payload.
                    JsonNode message = node.path("message");
                    String role = message.path("role").asText();
                    String content = message.path("content").isNull() ? null : message.path("content").asText();
                    if ("system".equals(role)) {
                        messages.add(Message.system(content));
                    } else if ("user".equals(role)) {
                        messages.add(Message.user(content));
                    } else if ("assistant".equals(role)) {
                        List<ToolCall> calls = new ArrayList<ToolCall>();
                        JsonNode toolCalls = message.path("tool_calls");
                        if (toolCalls.isArray()) {
                            for (JsonNode item : toolCalls) {
                                String argsText = item.path("arguments_json").asText("{}");
                                calls.add(new ToolCall(
                                        item.path("id").asText(),
                                        item.path("name").asText(),
                                        mapper.readTree(argsText),
                                        argsText
                                ));
                            }
                        }
                        messages.add(Message.assistant(content, message.path("reasoning_content").asText(null), calls));
                    } else if ("tool".equals(role)) {
                        messages.add(Message.tool(message.path("tool_call_id").asText(), content));
                    }
                } else {
                    // Backward compatibility for older logs that only stored
                    // user text events.
                    String type = node.path("type").asText();
                    String content = node.path("content").asText("");
                    if ("user".equals(type)) {
                        messages.add(Message.user(content));
                    }
                }
            }
        } finally {
            reader.close();
        }
        return messages;
    }

    private void write(ObjectNode node) throws Exception {
        Writer writer = new OutputStreamWriter(new FileOutputStream(file, true), StandardCharsets.UTF_8);
        try {
            writer.write(mapper.writeValueAsString(node));
            writer.write("\n");
        } finally {
            writer.close();
        }
    }
}
