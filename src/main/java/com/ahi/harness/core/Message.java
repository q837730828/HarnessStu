package com.ahi.harness.core;

import java.util.Collections;
import java.util.List;

public class Message {
    private final String role;
    private final String content;
    private final String reasoningContent;
    private final String toolCallId;
    private final List<ToolCall> toolCalls;

    private Message(String role, String content, String reasoningContent, String toolCallId, List<ToolCall> toolCalls) {
        this.role = role;
        this.content = content;
        this.reasoningContent = reasoningContent;
        this.toolCallId = toolCallId;
        this.toolCalls = toolCalls == null ? Collections.<ToolCall>emptyList() : toolCalls;
    }

    public static Message system(String content) {
        return new Message("system", content, null, null, null);
    }

    public static Message user(String content) {
        return new Message("user", content, null, null, null);
    }

    public static Message assistant(String content, String reasoningContent, List<ToolCall> toolCalls) {
        return new Message("assistant", content, reasoningContent, null, toolCalls);
    }

    public static Message tool(String toolCallId, String content) {
        return new Message("tool", content, null, toolCallId, null);
    }

    public String role() {
        return role;
    }

    public String content() {
        return content;
    }

    public String reasoningContent() {
        return reasoningContent;
    }

    public String toolCallId() {
        return toolCallId;
    }

    public List<ToolCall> toolCalls() {
        return toolCalls;
    }
}
