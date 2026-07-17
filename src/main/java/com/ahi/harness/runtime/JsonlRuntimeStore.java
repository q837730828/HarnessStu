package com.ahi.harness.runtime;

import com.ahi.harness.core.Message;
import com.ahi.harness.core.ToolCall;
import com.ahi.harness.protocol.AgentIdentity;
import com.ahi.harness.protocol.AgentMessage;
import com.ahi.harness.protocol.AgentRun;
import com.ahi.harness.protocol.AgentThread;
import com.ahi.harness.protocol.Artifact;
import com.ahi.harness.protocol.Checkpoint;
import com.ahi.harness.protocol.CancellationRequest;
import com.ahi.harness.protocol.Interrupt;
import com.ahi.harness.protocol.ProtocolError;
import com.ahi.harness.protocol.RunStep;
import com.ahi.harness.protocol.RuntimeEvent;
import com.ahi.harness.protocol.TraceSpan;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Single-process file implementation of RuntimeStore.
 *
 * Resource snapshots use JSON while histories use append-only JSONL. This makes
 * the current state easy to inspect and preserves every Step/Interrupt revision
 * for learning and audit. It intentionally does not claim multi-process safety.
 */
public class JsonlRuntimeStore implements RuntimeStore {
    private final File root;
    private final ObjectMapper mapper = new ObjectMapper();

    public JsonlRuntimeStore(File workspace) {
        this.root = new File(workspace, ".harness/runtime");
    }

    @Override
    public synchronized void saveAgent(AgentIdentity agent) throws Exception {
        writeSnapshot(new File(new File(root, "agents"), safe(agent.getId()) + ".json"), agent);
    }

    @Override
    public synchronized void saveThread(AgentThread thread) throws Exception {
        writeSnapshot(new File(new File(root, "threads"), safe(thread.getId()) + ".json"), thread);
    }

    @Override
    public synchronized void saveRun(AgentRun run) throws Exception {
        writeSnapshot(new File(runDirectory(run.getId()), "run.json"), run);
    }

    @Override
    public synchronized void appendStep(RunStep step) throws Exception {
        append(new File(runDirectory(step.getRunId()), "steps.jsonl"), step);
    }

    @Override
    public synchronized void appendEvent(RuntimeEvent event) throws Exception {
        append(new File(runDirectory(event.getRunId()), "events.jsonl"), event);
    }

    @Override
    public synchronized void appendMessage(AgentMessage message) throws Exception {
        append(new File(runDirectory(message.getRunId()), "messages.jsonl"), message);
    }

    @Override
    public synchronized void appendArtifact(Artifact artifact) throws Exception {
        append(new File(runDirectory(artifact.getRunId()), "artifacts.jsonl"), artifact);
    }

    @Override
    public synchronized void appendInterrupt(Interrupt interrupt) throws Exception {
        append(new File(runDirectory(interrupt.getRunId()), "interrupts.jsonl"), interrupt);
    }

    @Override
    public synchronized void appendSpan(TraceSpan span) throws Exception {
        append(new File(runDirectory(span.getRunId()), "spans.jsonl"), span);
    }

    @Override
    public synchronized void appendError(ProtocolError error) throws Exception {
        append(new File(runDirectory(error.getRunId()), "errors.jsonl"), error);
    }

    @Override
    public synchronized List<AgentRun> listRuns() throws Exception {
        File directory = new File(root, "runs");
        File[] children = directory.listFiles();
        List<AgentRun> runs = new ArrayList<AgentRun>();
        if (children == null) {
            return runs;
        }
        for (File child : children) {
            if (!child.isDirectory()) {
                continue;
            }
            AgentRun run = loadRun(child.getName());
            if (run != null) {
                runs.add(run);
            }
        }
        Collections.sort(runs, new Comparator<AgentRun>() {
            @Override
            public int compare(AgentRun left, AgentRun right) {
                return left.getCreatedAt().compareTo(right.getCreatedAt());
            }
        });
        return runs;
    }

    @Override
    public synchronized AgentRun loadRun(String runId) throws Exception {
        File file = new File(runDirectory(runId), "run.json");
        if (!file.exists()) {
            return null;
        }
        JsonNode node = mapper.readTree(file);
        String id = node.path("id").asText(runId);
        return AgentRun.rehydrate(
                id,
                text(node, "traceId", id),
                text(node, "threadId", ""),
                text(node, "agentId", ""),
                text(node, "input", ""),
                text(node, "createdAt", ""),
                node.path("maxModelCalls").asInt(0),
                node.path("timeoutSeconds").asInt(0),
                text(node, "parentRunId", null),
                text(node, "resumedFromCheckpointId", null),
                node.path("modelCalls").asInt(0),
                text(node, "deadlineAt", null),
                text(node, "startedAt", null),
                text(node, "completedAt", null),
                text(node, "updatedAt", ""),
                AgentRun.Status.valueOf(node.path("status").asText("CREATED")),
                text(node, "currentStepId", null),
                text(node, "activeInterruptId", null),
                text(node, "resultArtifactId", null),
                text(node, "error", null)
        );
    }

    @Override
    public synchronized List<RuntimeEvent> readEvents(String runId, long afterSequence) throws Exception {
        File file = new File(runDirectory(runId), "events.jsonl");
        List<RuntimeEvent> events = new ArrayList<RuntimeEvent>();
        if (!file.exists()) {
            return events;
        }
        for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
            if (line.trim().isEmpty()) {
                continue;
            }
            JsonNode node = mapper.readTree(line);
            long sequence = node.path("sequence").asLong();
            if (sequence <= afterSequence) {
                continue;
            }
            events.add(RuntimeEvent.rehydrate(
                    node.path("id").asText(),
                    node.path("threadId").asText(),
                    node.path("runId").asText(),
                    text(node, "stepId", null),
                    text(node, "traceId", null),
                    text(node, "spanId", null),
                    sequence,
                    RuntimeEvent.Type.valueOf(node.path("type").asText()),
                    node.hasNonNull("channel")
                            ? RuntimeEvent.Channel.valueOf(node.path("channel").asText()) : null,
                    node.path("timestamp").asText(),
                    text(node, "detail", "")
            ));
        }
        return events;
    }

    @Override
    public synchronized void saveCancellationRequest(CancellationRequest request) throws Exception {
        writeSnapshot(new File(runDirectory(request.getRunId()), "cancel-request.json"), request);
    }

    @Override
    public synchronized CancellationRequest findCancellationRequest(String runId) throws Exception {
        File file = new File(runDirectory(runId), "cancel-request.json");
        if (!file.exists()) {
            return null;
        }
        JsonNode node = mapper.readTree(file);
        return new CancellationRequest(
                node.path("id").asText(),
                node.path("runId").asText(runId),
                node.path("reason").asText("cancel requested"),
                node.path("requestedAt").asText()
        );
    }

    @Override
    public synchronized void saveCheckpoint(Checkpoint checkpoint, List<Message> messages) throws Exception {
        ObjectNode rootNode = mapper.valueToTree(checkpoint);
        ArrayNode messageNodes = mapper.createArrayNode();
        for (Message message : messages) {
            messageNodes.add(messageNode(message));
        }
        rootNode.set("messages", messageNodes);

        File directory = new File(runDirectory(checkpoint.getRunId()), "checkpoints");
        writeSnapshot(new File(directory, safe(checkpoint.getId()) + ".json"), rootNode);
    }

    @Override
    public synchronized List<Message> loadCheckpointMessages(String runId, String checkpointId) throws Exception {
        File file = new File(new File(runDirectory(runId), "checkpoints"), safe(checkpointId) + ".json");
        List<Message> messages = new ArrayList<Message>();
        if (!file.exists()) {
            return messages;
        }
        JsonNode rootNode = mapper.readTree(file);
        for (JsonNode node : rootNode.path("messages")) {
            messages.add(parseMessage(node));
        }
        return messages;
    }

    private ObjectNode messageNode(Message message) {
        ObjectNode node = mapper.createObjectNode();
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
        return node;
    }

    private Message parseMessage(JsonNode node) throws Exception {
        String role = node.path("role").asText();
        String content = node.path("content").isNull() ? null : node.path("content").asText(null);
        if ("system".equals(role)) {
            return Message.system(content);
        }
        if ("user".equals(role)) {
            return Message.user(content);
        }
        if ("tool".equals(role)) {
            return Message.tool(node.path("tool_call_id").asText(), content);
        }
        List<ToolCall> calls = new ArrayList<ToolCall>();
        for (JsonNode call : node.path("tool_calls")) {
            String arguments = call.path("arguments_json").asText("{}");
            calls.add(new ToolCall(
                    call.path("id").asText(),
                    call.path("name").asText(),
                    mapper.readTree(arguments),
                    arguments
            ));
        }
        return Message.assistant(content, node.path("reasoning_content").asText(null), calls);
    }

    private String text(JsonNode node, String field, String fallback) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? fallback : value.asText(fallback);
    }

    private File runDirectory(String runId) {
        return new File(new File(root, "runs"), safe(runId));
    }

    private String safe(String id) {
        if (id == null || !id.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("Invalid protocol id: " + id);
        }
        return id;
    }

    private void append(File file, Object value) throws Exception {
        ensureParent(file);
        Writer writer = new OutputStreamWriter(new FileOutputStream(file, true), StandardCharsets.UTF_8);
        try {
            writer.write(mapper.writeValueAsString(value));
            writer.write("\n");
        } finally {
            writer.close();
        }
    }

    private void writeSnapshot(File file, Object value) throws Exception {
        ensureParent(file);
        File temporary = new File(file.getParentFile(), file.getName() + ".tmp");
        Writer writer = new OutputStreamWriter(new FileOutputStream(temporary, false), StandardCharsets.UTF_8);
        try {
            writer.write(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value));
            writer.write("\n");
        } finally {
            writer.close();
        }
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    private void ensureParent(File file) {
        File parent = file.getParentFile();
        if (!parent.exists() && !parent.mkdirs()) {
            throw new IllegalStateException("Cannot create runtime store directory: " + parent.getAbsolutePath());
        }
    }
}
