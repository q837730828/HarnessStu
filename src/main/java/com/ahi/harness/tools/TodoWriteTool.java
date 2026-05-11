package com.ahi.harness.tools;

import com.ahi.harness.ConsoleLog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashSet;
import java.util.Set;

public class TodoWriteTool implements Tool {
    private static final int MAX_TODOS = 50;
    private static final int MAX_ID_CHARS = 64;
    private static final int MAX_CONTENT_CHARS = 240;

    private final File workspace;
    private final ConsoleLog log;
    private final ObjectMapper mapper = new ObjectMapper();

    public TodoWriteTool(File workspace, ConsoleLog log) {
        this.workspace = workspace;
        this.log = log;
    }

    @Override
    public String name() {
        return "todo_write";
    }

    @Override
    public String description() {
        return "Replace the current agent planning todo list. Use it for multi-step work and keep at most one item in_progress.";
    }

    @Override
    public ObjectNode parameters() {
        ObjectNode schema = JsonSchemas.object();
        ObjectNode todos = mapper.createObjectNode();
        todos.put("type", "array");
        todos.put("description", "Complete replacement todo list for the current task.");
        todos.put("maxItems", MAX_TODOS);

        ObjectNode item = mapper.createObjectNode();
        item.put("type", "object");
        item.put("additionalProperties", false);
        ObjectNode properties = mapper.createObjectNode();
        properties.set("id", JsonSchemas.stringProperty("Stable short todo id, for example 1 or inspect-agent-loop."));
        properties.set("content", JsonSchemas.stringProperty("Concrete task step."));
        ObjectNode status = JsonSchemas.stringProperty("Todo status.");
        ArrayNode statuses = mapper.createArrayNode();
        statuses.add("pending");
        statuses.add("in_progress");
        statuses.add("completed");
        status.set("enum", statuses);
        properties.set("status", status);
        item.set("properties", properties);
        ArrayNode required = mapper.createArrayNode();
        required.add("id");
        required.add("content");
        required.add("status");
        item.set("required", required);

        todos.set("items", item);
        JsonSchemas.addRequired(schema, "todos", todos);
        return schema;
    }

    @Override
    public ToolResult execute(JsonNode arguments) throws Exception {
        JsonNode todos = arguments.path("todos");
        if (!todos.isArray()) {
            return ToolResult.failure("todo_write requires a todos array.");
        }
        if (todos.size() > MAX_TODOS) {
            return ToolResult.failure("Too many todos: " + todos.size() + " > " + MAX_TODOS);
        }

        ObjectNode root = mapper.createObjectNode();
        ArrayNode normalized = mapper.createArrayNode();
        Set<String> ids = new LinkedHashSet<String>();
        int inProgress = 0;

        for (int i = 0; i < todos.size(); i++) {
            JsonNode item = todos.get(i);
            String prefix = "todos[" + i + "]";
            if (!item.isObject()) {
                return ToolResult.failure(prefix + " must be an object.");
            }

            String id = textField(item, "id");
            String content = textField(item, "content");
            String status = textField(item, "status");
            String error = validateTodo(prefix, id, content, status, ids);
            if (error != null) {
                return ToolResult.failure(error);
            }
            if ("in_progress".equals(status)) {
                inProgress++;
            }

            ObjectNode out = mapper.createObjectNode();
            out.put("id", id.trim());
            out.put("content", content.trim());
            out.put("status", status.trim());
            normalized.add(out);
        }

        if (inProgress > 1) {
            return ToolResult.failure("Only one todo may be in_progress, found " + inProgress + ".");
        }

        root.set("todos", normalized);
        File file = todoFile();
        File parent = file.getParentFile();
        if (!parent.exists() && !parent.mkdirs()) {
            return ToolResult.failure("Cannot create todo directory: " + parent.getAbsolutePath());
        }

        String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n";
        Files.write(file.toPath(), json.getBytes(StandardCharsets.UTF_8));
        log.block("TODO", "Updated todo list", json);
        return ToolResult.success("Saved " + normalized.size() + " todo(s) to .harness/todos/current.json");
    }

    private String validateTodo(String prefix, String id, String content, String status, Set<String> ids) {
        if (id.trim().isEmpty()) {
            return prefix + ".id is required.";
        }
        if (id.length() > MAX_ID_CHARS) {
            return prefix + ".id is too long: " + id.length() + " > " + MAX_ID_CHARS;
        }
        if (!ids.add(id.trim())) {
            return "Duplicate todo id: " + id.trim();
        }
        if (content.trim().isEmpty()) {
            return prefix + ".content is required.";
        }
        if (content.length() > MAX_CONTENT_CHARS) {
            return prefix + ".content is too long: " + content.length() + " > " + MAX_CONTENT_CHARS;
        }
        if (!"pending".equals(status) && !"in_progress".equals(status) && !"completed".equals(status)) {
            return prefix + ".status must be pending, in_progress, or completed.";
        }
        return null;
    }

    private String textField(JsonNode item, String name) {
        JsonNode value = item.get(name);
        return value == null || value.isNull() ? "" : value.asText("");
    }

    private File todoFile() {
        return new File(new File(workspace, ".harness/todos"), "current.json");
    }
}
