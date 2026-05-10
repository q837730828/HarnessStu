package com.ahi.harness.model;

import com.ahi.harness.ConsoleLog;
import com.ahi.harness.core.Conversation;
import com.ahi.harness.core.Message;
import com.ahi.harness.core.ModelResponse;
import com.ahi.harness.core.ToolCall;
import com.ahi.harness.tools.Tool;
import com.ahi.harness.tools.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class DeepSeekClient implements ModelClient {
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final ConsoleLog log;
    private final ObjectMapper mapper = new ObjectMapper();

    public DeepSeekClient(String baseUrl, String apiKey, String model, ConsoleLog log) {
        this.baseUrl = trimTrailingSlash(baseUrl);
        this.apiKey = apiKey;
        this.model = model;
        this.log = log;
    }

    @Override
    public ModelResponse generate(Conversation conversation, ToolRegistry tools) throws Exception {
        ObjectNode request = mapper.createObjectNode();
        ArrayNode messages = buildMessages(conversation);
        ArrayNode toolDefs = buildTools(tools);
        request.put("model", model);
        request.put("temperature", 0.2);
        request.set("messages", messages);
        request.set("tools", toolDefs);
        request.put("tool_choice", "auto");

        String body = mapper.writeValueAsString(request);
        logModelInput(messages, toolDefs, body.length());
        logJsonBody("MODEL REQUEST JSON", "Sanitized HTTP request body / 脱敏后的 HTTP 请求体", request);
        log.info("MODEL HTTP", "POST " + baseUrl + "/chat/completions");

        HttpURLConnection connection = (HttpURLConnection) new URL(baseUrl + "/chat/completions").openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(30000);
        connection.setReadTimeout(120000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Authorization", "Bearer " + apiKey);

        OutputStream out = connection.getOutputStream();
        out.write(body.getBytes(StandardCharsets.UTF_8));
        out.close();

        int status = connection.getResponseCode();
        String responseText = readAll(status >= 400 ? connection.getErrorStream() : connection.getInputStream());
        log.info("MODEL HTTP", "status=" + status + ", response_chars=" + responseText.length());
        logJsonText("MODEL RESPONSE JSON", "Sanitized HTTP response body / 脱敏后的 HTTP 响应体", responseText);
        if (status >= 400) {
            throw new IllegalStateException("Model API error " + status + ": " + responseText);
        }

        return parseResponse(responseText);
    }

    private ArrayNode buildMessages(Conversation conversation) {
        ArrayNode array = mapper.createArrayNode();
        for (Message message : conversation.messages()) {
            ObjectNode node = mapper.createObjectNode();
            node.put("role", message.role());
            if ("tool".equals(message.role())) {
                node.put("tool_call_id", message.toolCallId());
                node.put("content", message.content());
            } else if ("assistant".equals(message.role())) {
                if (message.content() == null) {
                    node.putNull("content");
                } else {
                    node.put("content", message.content());
                }
                if (message.reasoningContent() != null && !message.reasoningContent().isEmpty()) {
                    node.put("reasoning_content", message.reasoningContent());
                }
                if (!message.toolCalls().isEmpty()) {
                    ArrayNode calls = mapper.createArrayNode();
                    for (ToolCall call : message.toolCalls()) {
                        ObjectNode callNode = mapper.createObjectNode();
                        callNode.put("id", call.id());
                        callNode.put("type", "function");
                        ObjectNode function = mapper.createObjectNode();
                        function.put("name", call.name());
                        function.put("arguments", call.argumentsJson());
                        callNode.set("function", function);
                        calls.add(callNode);
                    }
                    node.set("tool_calls", calls);
                }
            } else {
                node.put("content", message.content());
            }
            array.add(node);
        }
        return array;
    }

    private ArrayNode buildTools(ToolRegistry registry) {
        ArrayNode array = mapper.createArrayNode();
        for (Tool tool : registry.all()) {
            ObjectNode root = mapper.createObjectNode();
            root.put("type", "function");
            ObjectNode function = mapper.createObjectNode();
            function.put("name", tool.name());
            function.put("description", tool.description());
            function.set("parameters", tool.parameters());
            root.set("function", function);
            array.add(root);
        }
        return array;
    }

    private ModelResponse parseResponse(String responseText) throws Exception {
        JsonNode root = mapper.readTree(responseText);
        JsonNode message = root.path("choices").path(0).path("message");
        String content = message.path("content").isMissingNode() || message.path("content").isNull()
                ? null
                : message.path("content").asText();
        String reasoningContent = message.path("reasoning_content").isMissingNode() || message.path("reasoning_content").isNull()
                ? null
                : message.path("reasoning_content").asText();

        List<ToolCall> calls = new ArrayList<ToolCall>();
        JsonNode toolCalls = message.path("tool_calls");
        if (toolCalls.isArray()) {
            for (JsonNode item : toolCalls) {
                String id = item.path("id").asText();
                String name = item.path("function").path("name").asText();
                String argsText = item.path("function").path("arguments").asText("{}");
                JsonNode args = mapper.readTree(argsText == null || argsText.trim().isEmpty() ? "{}" : argsText);
                calls.add(new ToolCall(id, name, args, argsText));
            }
        }
        log.info("MODEL", "assistant_content_chars=" + (content == null ? 0 : content.length())
                + ", reasoning_content_chars=" + (reasoningContent == null ? 0 : reasoningContent.length())
                + ", tool_calls=" + calls.size());
        logModelOutput(root, content, reasoningContent, calls);
        return new ModelResponse(content, reasoningContent, calls);
    }

    private void logModelInput(ArrayNode messages, ArrayNode toolDefs, int requestChars) {
        StringBuilder body = new StringBuilder();
        body.append("model: ").append(model).append('\n');
        body.append("temperature: 0.2").append('\n');
        body.append("tool_choice: auto").append('\n');
        body.append("request_chars: ").append(requestChars).append('\n');
        body.append('\n');
        body.append("messages sent to model:\n");
        for (int i = 0; i < messages.size(); i++) {
            JsonNode message = messages.get(i);
            body.append("  #").append(i + 1).append(" role=").append(message.path("role").asText()).append('\n');
            if (message.has("tool_call_id")) {
                body.append("     tool_call_id=").append(message.path("tool_call_id").asText()).append('\n');
            }
            if (message.has("reasoning_content")) {
                body.append("     reasoning_content=<hidden, ")
                        .append(message.path("reasoning_content").asText().length())
                        .append(" chars, replayed for provider protocol>\n");
            }
            if (message.has("tool_calls")) {
                body.append("     tool_calls:\n");
                for (JsonNode call : message.path("tool_calls")) {
                    body.append("       - ").append(call.path("function").path("name").asText())
                            .append(" ").append(call.path("function").path("arguments").asText()).append('\n');
                }
            }
            if (message.has("content") && !message.path("content").isNull()) {
                body.append(indent(trimForLog(message.path("content").asText(), 1200), "     content: ")).append('\n');
            }
        }
        body.append('\n');
        body.append("tools exposed to model:\n");
        for (JsonNode tool : toolDefs) {
            JsonNode fn = tool.path("function");
            body.append("  - ").append(fn.path("name").asText())
                    .append(": ").append(fn.path("description").asText()).append('\n');
        }
        log.block("MODEL INPUT", "Request assembled for model / 已组装模型请求", body.toString());
    }

    private void logModelOutput(JsonNode root, String content, String reasoningContent, List<ToolCall> calls) {
        StringBuilder body = new StringBuilder();
        JsonNode choice = root.path("choices").path(0);
        body.append("finish_reason: ").append(choice.path("finish_reason").asText("")).append('\n');
        JsonNode usage = root.path("usage");
        if (!usage.isMissingNode()) {
            body.append("usage: prompt_tokens=").append(usage.path("prompt_tokens").asText("?"))
                    .append(", completion_tokens=").append(usage.path("completion_tokens").asText("?"))
                    .append(", total_tokens=").append(usage.path("total_tokens").asText("?"))
                    .append('\n');
        }
        body.append("assistant_content_chars: ").append(content == null ? 0 : content.length()).append('\n');
        body.append("reasoning_content_chars: ").append(reasoningContent == null ? 0 : reasoningContent.length())
                .append(" (content hidden; only replayed to satisfy provider protocol)").append('\n');
        if (content != null && !content.trim().isEmpty()) {
            body.append(indent(trimForLog(content, 1500), "assistant_content: ")).append('\n');
        }
        if (calls.isEmpty()) {
            body.append("tool_calls: none\n");
        } else {
            body.append("tool_calls:\n");
            for (int i = 0; i < calls.size(); i++) {
                ToolCall call = calls.get(i);
                body.append("  #").append(i + 1).append(" id=").append(call.id()).append('\n');
                body.append("     name=").append(call.name()).append('\n');
                body.append("     arguments=").append(call.argumentsJson()).append('\n');
            }
        }
        log.block("MODEL OUTPUT", "Response parsed from model / 已解析模型响应", body.toString());
    }

    private void logJsonBody(String stage, String title, JsonNode body) throws Exception {
        JsonNode sanitized = sanitizeJson(body);
        log.block(stage, title, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(sanitized));
    }

    private void logJsonText(String stage, String title, String jsonText) {
        try {
            JsonNode parsed = mapper.readTree(jsonText);
            logJsonBody(stage, title, parsed);
        } catch (Exception e) {
            log.block(stage, title + " (non-JSON or parse failed / 非 JSON 或解析失败)", trimForLog(jsonText, 4000));
        }
    }

    private JsonNode sanitizeJson(JsonNode node) {
        if (node == null || node.isNull()) {
            return mapper.getNodeFactory().nullNode();
        }
        if (node.isObject()) {
            ObjectNode out = mapper.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String key = field.getKey();
                JsonNode value = field.getValue();
                if ("reasoning_content".equals(key)) {
                    String text = value == null || value.isNull() ? "" : value.asText("");
                    out.put(key, "<hidden reasoning_content, " + text.length() + " chars>");
                } else if ("content".equals(key) && value != null && value.isTextual()) {
                    out.put(key, trimForLog(value.asText(), 1800));
                } else if ("arguments".equals(key) && value != null && value.isTextual()) {
                    out.put(key, trimForLog(value.asText(), 1200));
                } else {
                    out.set(key, sanitizeJson(value));
                }
            }
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = mapper.createArrayNode();
            for (JsonNode item : node) {
                out.add(sanitizeJson(item));
            }
            return out;
        }
        if (node.isTextual()) {
            return mapper.getNodeFactory().textNode(trimForLog(node.asText(), 1800));
        }
        return node;
    }

    private static String indent(String text, String firstLinePrefix) {
        String normalized = text == null ? "" : text.replace("\r\n", "\n");
        String[] lines = normalized.split("\n", -1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i == 0) {
                out.append(firstLinePrefix).append(lines[i]);
            } else {
                out.append('\n').append(repeat(' ', firstLinePrefix.length())).append(lines[i]);
            }
        }
        return out.toString();
    }

    private static String repeat(char ch, int count) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < count; i++) {
            builder.append(ch);
        }
        return builder.toString();
    }

    private static String trimForLog(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        String normalized = value.replace("\r\n", "\n");
        if (normalized.length() <= maxChars) {
            return normalized;
        }
        return normalized.substring(0, maxChars) + "\n...<truncated " + (normalized.length() - maxChars) + " chars>";
    }

    private static String readAll(InputStream stream) throws Exception {
        if (stream == null) {
            return "";
        }
        BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
        StringBuilder builder = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(line);
        }
        return builder.toString();
    }

    private static String trimTrailingSlash(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "https://api.deepseek.com";
        }
        String trimmed = value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
