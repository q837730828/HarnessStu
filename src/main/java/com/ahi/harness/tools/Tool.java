package com.ahi.harness.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Common interface for anything the model can call.
 *
 * Implementations handle argument validation and side effects; AgentLoop handles
 * permission checks before invoking execute.
 */
public interface Tool {
    String name();

    String description();

    ObjectNode parameters();

    ToolResult execute(JsonNode arguments) throws Exception;
}
