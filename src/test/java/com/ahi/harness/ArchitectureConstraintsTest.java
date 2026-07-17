package com.ahi.harness;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertTrue;

public class ArchitectureConstraintsTest {
    @Test
    public void processBuilderIsKeptBehindRuntimeBoundaries() throws Exception {
        List<String> offenders = findJavaFilesContaining("new ProcessBuilder", new String[] {
                "BashTool.java",
                "ExternalStdioClient.java",
                "McpStdioClient.java",
                "HookBus.java",
                "SlashCommandHandler.java"
        });

        assertTrue("Unexpected direct ProcessBuilder usage: " + offenders, offenders.isEmpty());
    }

    @Test
    public void toolsAreCoveredByPermissionPolicy() throws Exception {
        List<String> toolNames = new ArrayList<String>();
        collectToolNames(new File("src/main/java/com/ahi/harness/tools"), toolNames);
        String policy = read(new File("src/main/java/com/ahi/harness/permission/PermissionPolicy.java"));
        List<String> missing = new ArrayList<String>();
        for (String name : toolNames) {
            if (!policy.contains("\"" + name + "\"") && !name.startsWith("external__")) {
                missing.add(name);
            }
        }

        assertTrue("Tools missing explicit PermissionPolicy coverage: " + missing, missing.isEmpty());
    }

    private List<String> findJavaFilesContaining(String needle, String[] allowedNames) throws Exception {
        List<String> offenders = new ArrayList<String>();
        scan(new File("src/main/java"), needle, allowedNames, offenders);
        return offenders;
    }

    private void scan(File file, String needle, String[] allowedNames, List<String> offenders) throws Exception {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) {
                return;
            }
            for (File child : children) {
                scan(child, needle, allowedNames, offenders);
            }
            return;
        }
        if (!file.getName().endsWith(".java")) {
            return;
        }
        if (!read(file).contains(needle)) {
            return;
        }
        for (String allowed : allowedNames) {
            if (file.getName().equals(allowed)) {
                return;
            }
        }
        offenders.add(file.getPath());
    }

    private void collectToolNames(File file, List<String> names) throws Exception {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) {
                return;
            }
            for (File child : children) {
                collectToolNames(child, names);
            }
            return;
        }
        if (!file.getName().endsWith("Tool.java")
                || file.getName().equals("Tool.java")
                || file.getName().equals("ExternalStdioTool.java")) {
            return;
        }
        String text = read(file);
        int method = text.indexOf("public String name()");
        if (method < 0) {
            return;
        }
        int returnIndex = text.indexOf("return \"", method);
        if (returnIndex < 0) {
            return;
        }
        int start = returnIndex + "return \"".length();
        int end = text.indexOf("\"", start);
        if (end > start) {
            names.add(text.substring(start, end));
        }
    }

    private String read(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}
