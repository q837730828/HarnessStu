package com.ahi.harness.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

final class JsonSchemas {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonSchemas() {
    }

    static ObjectNode object() {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("type", "object");
        root.set("properties", MAPPER.createObjectNode());
        root.set("required", MAPPER.createArrayNode());
        root.put("additionalProperties", false);
        return root;
    }

    static ObjectNode stringProperty(String description) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", "string");
        node.put("description", description);
        return node;
    }

    static void addRequired(ObjectNode schema, String name, ObjectNode property) {
        ((ObjectNode) schema.get("properties")).set(name, property);
        ((ArrayNode) schema.get("required")).add(name);
    }

    static void addOptional(ObjectNode schema, String name, ObjectNode property) {
        ((ObjectNode) schema.get("properties")).set(name, property);
    }
}
