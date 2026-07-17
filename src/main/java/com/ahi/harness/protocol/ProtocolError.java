package com.ahi.harness.protocol;

/** Structured failure that can be audited independently from display text. */
public class ProtocolError {
    public enum Source {
        MODEL,
        TOOL,
        PERMISSION,
        RUNTIME,
        STORAGE
    }

    private final String id;
    private final String traceId;
    private final String runId;
    private final String stepId;
    private final Source source;
    private final String code;
    private final String message;
    private final boolean retryable;
    private final String createdAt;

    public ProtocolError(String traceId,
                         String runId,
                         String stepId,
                         Source source,
                         String code,
                         String message,
                         boolean retryable) {
        this.id = ProtocolIds.next("error");
        this.traceId = traceId;
        this.runId = runId;
        this.stepId = stepId;
        this.source = source;
        this.code = code;
        this.message = message;
        this.retryable = retryable;
        this.createdAt = ProtocolIds.now();
    }

    public String getId() { return id; }
    public String getTraceId() { return traceId; }
    public String getRunId() { return runId; }
    public String getStepId() { return stepId; }
    public Source getSource() { return source; }
    public String getCode() { return code; }
    public String getMessage() { return message; }
    public boolean isRetryable() { return retryable; }
    public String getCreatedAt() { return createdAt; }
    public int getSchemaVersion() { return 1; }
}
