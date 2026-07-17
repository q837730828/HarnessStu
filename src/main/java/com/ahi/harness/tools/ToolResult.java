package com.ahi.harness.tools;

/**
 * Normalized result returned by every tool.
 *
 * AgentLoop converts this into a provider-facing tool observation by prefixing
 * success/failure state.
 */
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

    /** Runtime protocol recording needs the normalized outcome without parsing text. */
    public boolean success() {
        return success;
    }

    public String content() {
        return content;
    }

    public String toObservation() {
        return (success ? "OK" : "ERROR") + "\n" + content;
    }
}
