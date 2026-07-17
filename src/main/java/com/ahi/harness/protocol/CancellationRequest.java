package com.ahi.harness.protocol;

/** Durable cooperative cancellation request for a running Run. */
public class CancellationRequest {
    private final String id;
    private final String runId;
    private final String reason;
    private final String requestedAt;

    public CancellationRequest(String runId, String reason) {
        this(ProtocolIds.next("cancel"), runId, reason, ProtocolIds.now());
    }

    public CancellationRequest(String id, String runId, String reason, String requestedAt) {
        this.id = id;
        this.runId = runId;
        this.reason = reason;
        this.requestedAt = requestedAt;
    }

    public String getId() { return id; }
    public String getRunId() { return runId; }
    public String getReason() { return reason; }
    public String getRequestedAt() { return requestedAt; }
    public int getSchemaVersion() { return 1; }
}
