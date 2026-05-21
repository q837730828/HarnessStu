package com.ahi.harness.permission;

public class PermissionDecision {
    public enum Status {
        ALLOW,
        ASK,
        DENY
    }

    private final Status status;
    private final boolean allowed;
    private final String reason;

    private PermissionDecision(Status status, String reason) {
        this.status = status;
        this.allowed = Status.ALLOW.equals(status);
        this.reason = reason;
    }

    public static PermissionDecision allow(String reason) {
        return new PermissionDecision(Status.ALLOW, reason);
    }

    public static PermissionDecision ask(String reason) {
        return new PermissionDecision(Status.ASK, reason);
    }

    public static PermissionDecision deny(String reason) {
        return new PermissionDecision(Status.DENY, reason);
    }

    public Status status() {
        return status;
    }

    public boolean allowed() {
        return allowed;
    }

    public boolean requiresApproval() {
        return Status.ASK.equals(status);
    }

    public String reason() {
        return reason;
    }
}
