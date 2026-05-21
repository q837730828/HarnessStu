package com.ahi.harness.core;

import java.util.Collections;
import java.util.List;

/**
 * Parsed assistant response returned by a ModelClient.
 */
public class ModelResponse {
    private final String content;
    private final String reasoningContent;
    private final List<ToolCall> toolCalls;

    public ModelResponse(String content, String reasoningContent, List<ToolCall> toolCalls) {
        this.content = content;
        this.reasoningContent = reasoningContent;
        this.toolCalls = toolCalls == null ? Collections.<ToolCall>emptyList() : toolCalls;
    }

    public String content() {
        return content;
    }

    public String reasoningContent() {
        return reasoningContent;
    }

    public List<ToolCall> toolCalls() {
        return toolCalls;
    }

    public String summaryForLog() {
        return "content=" + (content == null ? "" : content)
                + ", tool_calls=" + toolCalls.size();
    }
}
