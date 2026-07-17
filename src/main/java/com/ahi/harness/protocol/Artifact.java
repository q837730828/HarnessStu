package com.ahi.harness.protocol;

/**
 * Durable output produced by a Run or Step.
 *
 * Inline content is appropriate for a final text answer. URI points at a
 * workspace file when the full payload is too large for active model context.
 */
public class Artifact {
    public enum Type {
        FINAL_RESPONSE,
        TOOL_OBSERVATION,
        WORKSPACE_FILE,
        COMPACTION_ARCHIVE
    }

    private final String id;
    private final String runId;
    private final String stepId;
    private final Type type;
    private final String name;
    private final String mediaType;
    private final String uri;
    private final String content;
    private final long contentLength;
    private final String createdAt;

    public Artifact(String runId,
                    String stepId,
                    Type type,
                    String name,
                    String mediaType,
                    String uri,
                    String content,
                    long contentLength) {
        this.id = ProtocolIds.next("artifact");
        this.runId = runId;
        this.stepId = stepId;
        this.type = type;
        this.name = name;
        this.mediaType = mediaType;
        this.uri = uri;
        this.content = content;
        this.contentLength = contentLength;
        this.createdAt = ProtocolIds.now();
    }

    public String getId() { return id; }
    public String getRunId() { return runId; }
    public String getStepId() { return stepId; }
    public Type getType() { return type; }
    public String getName() { return name; }
    public String getMediaType() { return mediaType; }
    public String getUri() { return uri; }
    public String getContent() { return content; }
    public long getContentLength() { return contentLength; }
    public String getCreatedAt() { return createdAt; }
    public int getSchemaVersion() { return 1; }
}
