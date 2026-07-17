package com.ahi.harness.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ListFilesTool implements Tool {
    private static final int DEFAULT_LIMIT = 200;
    private final File workspace;

    public ListFilesTool(File workspace) {
        this.workspace = workspace;
    }

    @Override
    public String name() {
        return "list_files";
    }

    @Override
    public String description() {
        return "List files under a workspace-relative directory.";
    }

    @Override
    public ObjectNode parameters() {
        ObjectNode schema = JsonSchemas.object();
        JsonSchemas.addOptional(schema, "path", JsonSchemas.stringProperty("Workspace-relative directory. Defaults to ."));
        JsonSchemas.addOptional(schema, "limit", JsonSchemas.stringProperty("Maximum number of files to return. Defaults to 200."));
        return schema;
    }

    @Override
    public ToolResult execute(JsonNode arguments) throws Exception {
        String path = arguments.path("path").asText(".");
        int limit = parseLimit(arguments.path("limit").asText(String.valueOf(DEFAULT_LIMIT)));
        File root = WorkspacePaths.resolveInside(workspace, path);
        if (!root.exists()) {
            return ToolResult.failure("Directory does not exist: " + path);
        }
        if (!root.isDirectory()) {
            return ToolResult.failure("Path is not a directory: " + path);
        }

        List<String> files = new ArrayList<String>();
        collect(root, files, limit);
        Collections.sort(files);
        StringBuilder out = new StringBuilder();
        out.append("workspace: ").append(workspace.getAbsolutePath()).append('\n');
        out.append("root: ").append(relative(root)).append('\n');
        for (String file : files) {
            out.append(file).append('\n');
        }
        if (files.size() >= limit) {
            out.append("...limit reached ").append(limit).append('\n');
        }
        return ToolResult.success(out.toString());
    }

    private void collect(File dir, List<String> files, int limit) {
        if (files.size() >= limit || shouldSkip(dir)) {
            return;
        }
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (files.size() >= limit) {
                return;
            }
            if (shouldSkip(child)) {
                continue;
            }
            if (child.isDirectory()) {
                collect(child, files, limit);
            } else {
                files.add(relative(child));
            }
        }
    }

    private boolean shouldSkip(File file) {
        String name = file.getName();
        if (".harness".equals(name)) {
            return false;
        }
        if (file.getParentFile() != null && ".harness".equals(file.getParentFile().getName())) {
            return !"compactions".equals(name) && !"observations".equals(name) && !"runtime".equals(name);
        }
        if (isInsideHarnessButNotReadableRuntime(file)) {
            return true;
        }
        return ".git".equals(name)
                || "target".equals(name)
                || "node_modules".equals(name)
                || ".idea".equals(name);
    }

    private boolean isInsideHarnessButNotReadableRuntime(File file) {
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
        if (path.equals(root)) {
            return ".";
        }
        if (path.startsWith(root + File.separator)) {
            return path.substring(root.length() + 1).replace(File.separatorChar, '/');
        }
        return path;
    }

    private int parseLimit(String text) {
        try {
            int value = Integer.parseInt(text);
            return Math.max(1, Math.min(value, 1000));
        } catch (Exception ignored) {
            return DEFAULT_LIMIT;
        }
    }
}
