package com.ahi.harness.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class TodoReadTool implements Tool {
    private final File workspace;
    private final ObjectMapper mapper = new ObjectMapper();

    public TodoReadTool(File workspace) {
        this.workspace = workspace;
    }

    @Override
    public String name() {
        return "todo_read";
    }

    @Override
    public String description() {
        return "Read the current agent planning todo list from harness runtime state.";
    }

    @Override
    public ObjectNode parameters() {
        return JsonSchemas.object();
    }

    @Override
    public ToolResult execute(JsonNode arguments) throws Exception {
        File file = todoFile();
        JsonNode todos = emptyTodos();
        String state = "not created yet";
        if (file.exists()) {
            try {
                todos = mapper.readTree(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            } catch (Exception e) {
                return ToolResult.failure("Todo file is not valid JSON: " + e.getMessage());
            }
            state = "loaded";
        }

        StringBuilder out = new StringBuilder();
        out.append("Todo file: .harness/todos/current.json (").append(state).append(")\n");
        out.append(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(todos));
        return ToolResult.success(out.toString());
    }

    private File todoFile() {
        return new File(new File(workspace, ".harness/todos"), "current.json");
    }

    private ObjectNode emptyTodos() {
        ObjectNode root = mapper.createObjectNode();
        root.set("todos", mapper.createArrayNode());
        return root;
    }
}
