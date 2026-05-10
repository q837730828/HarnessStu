package com.ahi.harness.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

public interface Tool {
    String name();

    String description();

    ObjectNode parameters();

    ToolResult execute(JsonNode arguments) throws Exception;
}
