package com.ahi.harness.protocol;

/**
 * OpenTelemetry-like causal unit without depending on an observability SDK.
 *
 * JSONL is the current binding. The IDs and parent relationship can later be
 * mapped directly to OpenTelemetry spans by an adapter.
 */
public class TraceSpan {
    public enum Kind {
        AGENT_RUN,
        MODEL,
        TOOL,
        GUARDRAIL,
        SUBAGENT,
        INTERNAL
    }

    public enum Status {
        RUNNING,
        OK,
        ERROR,
        CANCELLED
    }

    private final String id;
    private final String traceId;
    private final String parentSpanId;
    private final String runId;
    private final String stepId;
    private final Kind kind;
    private final String name;
    private final String startedAt;
    private final long startedEpochMillis;
    private Status status;
    private String completedAt;
    private long durationMillis;
    private String error;

    public TraceSpan(String traceId,
                     String parentSpanId,
                     String runId,
                     String stepId,
                     Kind kind,
                     String name) {
        this.id = ProtocolIds.next("span");
        this.traceId = traceId;
        this.parentSpanId = parentSpanId;
        this.runId = runId;
        this.stepId = stepId;
        this.kind = kind;
        this.name = name;
        this.startedAt = ProtocolIds.now();
        this.startedEpochMillis = System.currentTimeMillis();
        this.status = Status.RUNNING;
    }

    public void succeed() {
        finish(Status.OK, null);
    }

    public void fail(String message) {
        finish(Status.ERROR, message);
    }

    public void cancel(String reason) {
        finish(Status.CANCELLED, reason);
    }

    private void finish(Status terminal, String message) {
        if (status != Status.RUNNING) {
            return;
        }
        status = terminal;
        error = message;
        completedAt = ProtocolIds.now();
        durationMillis = Math.max(0L, System.currentTimeMillis() - startedEpochMillis);
    }

    public String getId() { return id; }
    public String getTraceId() { return traceId; }
    public String getParentSpanId() { return parentSpanId; }
    public String getRunId() { return runId; }
    public String getStepId() { return stepId; }
    public Kind getKind() { return kind; }
    public String getName() { return name; }
    public String getStartedAt() { return startedAt; }
    public Status getStatus() { return status; }
    public String getCompletedAt() { return completedAt; }
    public long getDurationMillis() { return durationMillis; }
    public String getError() { return error; }
    public int getSchemaVersion() { return 1; }
}
