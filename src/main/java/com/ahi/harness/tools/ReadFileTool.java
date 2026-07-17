package com.ahi.harness.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

public class ReadFileTool implements Tool {
    private static final int MAX_CHARS = 20000;
    private final File workspace;

    public ReadFileTool(File workspace) {
        this.workspace = workspace;
    }

    @Override
    public String name() {
        return "read_file";
    }

    @Override
    public String description() {
        return "Read a UTF-8 text file inside the workspace.";
    }

    @Override
    public ObjectNode parameters() {
        ObjectNode schema = JsonSchemas.object();
        JsonSchemas.addRequired(schema, "path", JsonSchemas.stringProperty("Workspace-relative file path."));
        return schema;
    }

    @Override
    public ToolResult execute(JsonNode arguments) throws Exception {
        String path = arguments.path("path").asText();
        File file = WorkspacePaths.resolveInside(workspace, path);
        if (!file.exists()) {
            return ToolResult.failure("File does not exist: " + path);
        }
        if (!file.isFile()) {
            return ToolResult.failure("Path is not a file: " + path);
        }
        if (path.replace('\\', '/').startsWith(".harness/") && !WorkspacePaths.isReadableHarnessArchivePath(workspace, file)) {
            return ToolResult.failure("Reading .harness runtime files is blocked except .harness/compactions, .harness/observations, and .harness/runtime records.");
        }
        List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            out.append(String.format("%4d | %s%n", i + 1, lines.get(i)));
            if (out.length() > MAX_CHARS) {
                out.append("...truncated at ").append(MAX_CHARS).append(" chars\n");
                break;
            }
        }
        return ToolResult.success(out.toString());
    }
}
