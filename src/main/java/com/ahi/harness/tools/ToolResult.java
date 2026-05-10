package com.ahi.harness.tools;

public class ToolResult {
    private final boolean success;
    private final String content;

    private ToolResult(boolean success, String content) {
        this.success = success;
        this.content = content == null ? "" : content;
    }

    public static ToolResult success(String content) {
        return new ToolResult(true, content);
    }

    public static ToolResult failure(String content) {
        return new ToolResult(false, content);
    }

    public String toObservation() {
        return (success ? "OK" : "ERROR") + "\n" + content;
    }
}
