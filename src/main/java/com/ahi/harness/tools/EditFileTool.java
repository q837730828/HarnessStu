package com.ahi.harness.tools;

import com.ahi.harness.ConsoleLog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Safe text editing primitive.
 *
 * The model must provide an exact old_text block. That makes edits auditable and
 * avoids broad "rewrite this file" behavior.
 */
public class EditFileTool implements Tool {
    private static final int MAX_FILE_CHARS = 300000;
    private static final int MAX_DIFF_CHARS = 20000;

    private final File workspace;
    private final ConsoleLog log;

    public EditFileTool(File workspace, ConsoleLog log) {
        this.workspace = workspace;
        this.log = log;
    }

    @Override
    public String name() {
        return "edit_file";
    }

    @Override
    public String description() {
        return "Edit a UTF-8 workspace file by replacing one exact old_text block with new_text. Creates a backup before writing and returns a diff.";
    }

    @Override
    public ObjectNode parameters() {
        ObjectNode schema = JsonSchemas.object();
        JsonSchemas.addRequired(schema, "path", JsonSchemas.stringProperty("Workspace-relative file path to edit."));
        JsonSchemas.addRequired(schema, "old_text", JsonSchemas.stringProperty("Exact text block to replace. Must match the current file."));
        JsonSchemas.addRequired(schema, "new_text", JsonSchemas.stringProperty("Replacement text block."));
        JsonSchemas.addOptional(schema, "expected_replacements", JsonSchemas.stringProperty("Expected replacement count. Defaults to 1."));
        return schema;
    }

    @Override
    public ToolResult execute(JsonNode arguments) throws Exception {
        String path = arguments.path("path").asText();
        String oldText = arguments.path("old_text").asText();
        String newText = arguments.path("new_text").asText();
        int expected = parseExpected(arguments.path("expected_replacements").asText("1"));

        if (path.trim().isEmpty()) {
            return ToolResult.failure("path is required");
        }
        if (oldText.isEmpty()) {
            return ToolResult.failure("old_text is required and cannot be empty. Read the file first, then replace an exact block.");
        }
        if (expected < 1) {
            return ToolResult.failure("expected_replacements must be >= 1");
        }

        File file = WorkspacePaths.resolveInside(workspace, path);
        if (!file.exists()) {
            return ToolResult.failure("File does not exist: " + path);
        }
        if (!file.isFile()) {
            return ToolResult.failure("Path is not a file: " + path);
        }
        if (file.length() > MAX_FILE_CHARS) {
            return ToolResult.failure("File is too large for MVP edit_file: " + file.length() + " bytes");
        }

        String original = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        int count = countOccurrences(original, oldText);
        if (count != expected) {
            // A non-unique match is usually a stale read or an imprecise patch.
            return ToolResult.failure("Expected " + expected + " replacement(s), but found " + count
                    + ". The model should read_file again and provide a more exact old_text block.");
        }

        String updated = replaceOccurrences(original, oldText, newText, expected);
        if (original.equals(updated)) {
            return ToolResult.failure("Edit produced no changes.");
        }

        String diff = unifiedDiff(relative(file), original, updated);
        log.block("DIFF", "Diff before write / 写入前 diff", trim(diff, MAX_DIFF_CHARS));

        // Backup first, then write. If verification fails later, the user has a
        // local recovery file under .harness/backups.
        File backup = backupFile(file);
        Files.write(file.toPath(), updated.getBytes(StandardCharsets.UTF_8));

        StringBuilder out = new StringBuilder();
        out.append("Edited file: ").append(relative(file)).append('\n');
        out.append("Backup: ").append(relative(backup)).append('\n');
        out.append("Replacements: ").append(expected).append('\n');
        out.append("Diff:\n").append(trim(diff, MAX_DIFF_CHARS));
        return ToolResult.success(out.toString());
    }

    private File backupFile(File file) throws Exception {
        File backupRoot = new File(workspace, ".harness/backups");
        if (!backupRoot.exists() && !backupRoot.mkdirs()) {
            throw new IllegalStateException("Cannot create backup directory: " + backupRoot.getAbsolutePath());
        }
        String stamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
                .replace(':', '-')
                .replace('.', '-');
        String safePath = relative(file).replace('/', '_').replace('\\', '_');
        File backup = new File(backupRoot, stamp + "_" + safePath + ".bak");
        Files.copy(file.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
        return backup;
    }

    private int parseExpected(String text) {
        try {
            return Integer.parseInt(text);
        } catch (Exception ignored) {
            return 1;
        }
    }

    private int countOccurrences(String text, String needle) {
        int count = 0;
        int from = 0;
        while (true) {
            int index = text.indexOf(needle, from);
            if (index < 0) {
                return count;
            }
            count++;
            from = index + needle.length();
        }
    }

    private String replaceOccurrences(String text, String oldText, String newText, int expected) {
        StringBuilder out = new StringBuilder();
        int from = 0;
        int replaced = 0;
        while (replaced < expected) {
            int index = text.indexOf(oldText, from);
            if (index < 0) {
                break;
            }
            out.append(text, from, index);
            out.append(newText);
            from = index + oldText.length();
            replaced++;
        }
        out.append(text.substring(from));
        return out.toString();
    }

    private String unifiedDiff(String path, String before, String after) {
        // This is a compact educational diff, not a full Myers implementation.
        // It shows the changed window plus a few context lines.
        List<String> oldLines = splitLines(before);
        List<String> newLines = splitLines(after);
        int prefix = commonPrefix(oldLines, newLines);
        int suffix = commonSuffix(oldLines, newLines, prefix);

        int context = 3;
        int oldStart = Math.max(0, prefix - context);
        int newStart = Math.max(0, prefix - context);
        int oldEnd = Math.min(oldLines.size(), oldLines.size() - suffix + context);
        int newEnd = Math.min(newLines.size(), newLines.size() - suffix + context);

        StringBuilder out = new StringBuilder();
        out.append("--- ").append(path).append('\n');
        out.append("+++ ").append(path).append('\n');
        out.append("@@ old:").append(oldStart + 1).append("-").append(oldEnd)
                .append(" new:").append(newStart + 1).append("-").append(newEnd).append(" @@\n");

        for (int i = oldStart; i < prefix; i++) {
            out.append(" ").append(oldLines.get(i)).append('\n');
        }
        for (int i = prefix; i < oldLines.size() - suffix; i++) {
            out.append("-").append(oldLines.get(i)).append('\n');
        }
        for (int i = prefix; i < newLines.size() - suffix; i++) {
            out.append("+").append(newLines.get(i)).append('\n');
        }
        for (int i = newLines.size() - suffix; i < newEnd; i++) {
            if (i >= prefix && i >= 0 && i < newLines.size()) {
                out.append(" ").append(newLines.get(i)).append('\n');
            }
        }
        return out.toString();
    }

    private List<String> splitLines(String text) {
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        String[] parts = normalized.split("\n", -1);
        List<String> lines = new ArrayList<String>();
        for (int i = 0; i < parts.length; i++) {
            if (i == parts.length - 1 && parts[i].isEmpty()) {
                continue;
            }
            lines.add(parts[i]);
        }
        return lines;
    }

    private int commonPrefix(List<String> oldLines, List<String> newLines) {
        int limit = Math.min(oldLines.size(), newLines.size());
        int i = 0;
        while (i < limit && oldLines.get(i).equals(newLines.get(i))) {
            i++;
        }
        return i;
    }

    private int commonSuffix(List<String> oldLines, List<String> newLines, int prefix) {
        int oldIndex = oldLines.size() - 1;
        int newIndex = newLines.size() - 1;
        int count = 0;
        while (oldIndex >= prefix && newIndex >= prefix && oldLines.get(oldIndex).equals(newLines.get(newIndex))) {
            count++;
            oldIndex--;
            newIndex--;
        }
        return count;
    }

    private String trim(String value, int maxChars) {
        if (value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, maxChars) + "\n...<truncated " + (value.length() - maxChars) + " chars>";
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
