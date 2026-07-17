package com.ahi.harness.protocol;

/**
 * Provider-neutral piece of a protocol Message.
 *
 * A message can mix text, structured data, file references and tool exchange.
 * Keeping Part independent from an OpenAI/DeepSeek payload prevents the public
 * protocol from inheriting one model provider's JSON shape.
 */
public class MessagePart {
    public enum Type {
        TEXT,
        STRUCTURED_DATA,
        FILE_REFERENCE,
        TOOL_CALL,
        TOOL_RESULT
    }

    private final Type type;
    private final String name;
    private final String text;
    private final String data;
    private final String uri;
    private final String mediaType;
    private final String toolCallId;

    public MessagePart(Type type,
                       String name,
                       String text,
                       String data,
                       String uri,
                       String mediaType,
                       String toolCallId) {
        this.type = type;
        this.name = name;
        this.text = text;
        this.data = data;
        this.uri = uri;
        this.mediaType = mediaType;
        this.toolCallId = toolCallId;
    }

    public static MessagePart text(String value) {
        return new MessagePart(Type.TEXT, null, value, null, null, "text/plain", null);
    }

    public static MessagePart toolCall(String id, String name, String argumentsJson) {
        return new MessagePart(Type.TOOL_CALL, name, null, argumentsJson, null, "application/json", id);
    }

    public static MessagePart toolResult(String id, String value) {
        return new MessagePart(Type.TOOL_RESULT, null, value, null, null, "text/plain", id);
    }

    public Type getType() { return type; }
    public String getName() { return name; }
    public String getText() { return text; }
    public String getData() { return data; }
    public String getUri() { return uri; }
    public String getMediaType() { return mediaType; }
    public String getToolCallId() { return toolCallId; }
}
