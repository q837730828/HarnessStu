package com.ahi.harness.core;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * One function/tool call requested by the model.
 *
 * arguments is parsed JSON for tool execution; argumentsJson preserves the exact
 * provider payload for logging and session replay.
 */
public class ToolCall {
    private final String id;
    private final String name;
    private final JsonNode arguments;
    private final String argumentsJson;

    public ToolCall(String id, String name, JsonNode arguments, String argumentsJson) {
        this.id = id;
        this.name = name;
        this.arguments = arguments;
        this.argumentsJson = argumentsJson;
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public JsonNode arguments() {
        return arguments;
    }

    public String argumentsJson() {
        return argumentsJson;
    }
}
