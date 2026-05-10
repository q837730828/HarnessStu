package com.ahi.harness.permission;

public class PermissionDecision {
    private final boolean allowed;
    private final String reason;

    private PermissionDecision(boolean allowed, String reason) {
        this.allowed = allowed;
        this.reason = reason;
    }

    public static PermissionDecision allow(String reason) {
        return new PermissionDecision(true, reason);
    }

    public static PermissionDecision deny(String reason) {
        return new PermissionDecision(false, reason);
    }

    public boolean allowed() {
        return allowed;
    }

    public String reason() {
        return reason;
    }
}
