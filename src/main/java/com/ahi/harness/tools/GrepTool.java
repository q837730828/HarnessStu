package com.ahi.harness.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

public class GrepTool implements Tool {
    private static final int LIMIT = 100;
    private final File workspace;

    public GrepTool(File workspace) {
        this.workspace = workspace;
    }

    @Override
    public String name() {
        return "grep";
    }

    @Override
    public String description() {
        return "Search for a literal text pattern in UTF-8 workspace files.";
    }

    @Override
    public ObjectNode parameters() {
        ObjectNode schema = JsonSchemas.object();
        JsonSchemas.addRequired(schema, "pattern", JsonSchemas.stringProperty("Literal text to search for."));
        JsonSchemas.addOptional(schema, "path", JsonSchemas.stringProperty("Workspace-relative directory. Defaults to ."));
        return schema;
    }

    @Override
    public ToolResult execute(JsonNode arguments) throws Exception {
        String pattern = arguments.path("pattern").asText();
        String path = arguments.path("path").asText(".");
        if (pattern.trim().isEmpty()) {
            return ToolResult.failure("pattern is required");
        }

        File root = WorkspacePaths.resolveInside(workspace, path);
        List<String> matches = new ArrayList<String>();
        search(root, pattern, matches);
        if (matches.isEmpty()) {
            return ToolResult.success("No matches for: " + pattern);
        }
        StringBuilder out = new StringBuilder();
        for (String match : matches) {
            out.append(match).append('\n');
        }
        if (matches.size() >= LIMIT) {
            out.append("...limit reached ").append(LIMIT).append('\n');
        }
        return ToolResult.success(out.toString());
    }

    private void search(File file, String pattern, List<String> matches) throws Exception {
        if (matches.size() >= LIMIT || shouldSkip(file)) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) {
                return;
            }
            for (File child : children) {
                search(child, pattern, matches);
                if (matches.size() >= LIMIT) {
                    return;
                }
            }
            return;
        }
        if (!file.isFile() || file.length() > 1024L * 1024L) {
            return;
        }

        List<String> lines;
        try {
            lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return;
        }
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.contains(pattern)) {
                matches.add(relative(file) + ":" + (i + 1) + ": " + line.trim());
                if (matches.size() >= LIMIT) {
                    return;
                }
            }
        }
    }

    private boolean shouldSkip(File file) {
        String name = file.getName();
        if (".harness".equals(name)) {
            return false;
        }
        if (file.getParentFile() != null && ".harness".equals(file.getParentFile().getName())) {
            return !"compactions".equals(name) && !"observations".equals(name);
        }
        if (isInsideHarnessButNotCompactions(file)) {
            return true;
        }
        return ".git".equals(name)
                || "target".equals(name)
                || "node_modules".equals(name)
                || ".idea".equals(name)
                || name.endsWith(".class")
                || name.endsWith(".jar");
    }

    private boolean isInsideHarnessButNotCompactions(File file) {
        try {
            File harness = new File(workspace, ".harness").getCanonicalFile();
            File target = file.getCanonicalFile();
            String harnessPath = harness.getPath();
            String targetPath = target.getPath();
            if (!targetPath.equals(harnessPath) && targetPath.startsWith(harnessPath + File.separator)) {
                return !WorkspacePaths.isReadableHarnessArchivePath(workspace, target);
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private String relative(File file) {
        String root = workspace.getAbsolutePath();
        String path = file.getAbsolutePath();
        if (path.startsWith(root + File.separator)) {
            return path.substring(root.length() + 1).replace(File.separatorChar, '/');
        }
        return path;
    }
}
