package com.ahi.harness.permission;

import com.ahi.harness.ConsoleLog;
import com.ahi.harness.core.ToolCall;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public class PermissionPrompter {
    private final ConsoleLog log;
    private final PermissionStore store;
    private final BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));

    public PermissionPrompter(ConsoleLog log, PermissionStore store) {
        this.log = log;
        this.store = store;
    }

    public PermissionDecision resolve(ToolCall call, PermissionDecision decision) throws Exception {
        if (!decision.requiresApproval()) {
            return decision;
        }
        String rule = ruleFor(call);
        if (store.allows(rule)) {
            return PermissionDecision.allow("persisted permission rule: " + rule);
        }

        log.info("PERMISSION", "approval required for " + call.name() + ": " + decision.reason());
        log.block("PERMISSION", "Tool arguments", call.argumentsJson());
        System.out.print("Allow tool call? [y]es / [a]lways / [n]o: ");
        String answer = reader.readLine();
        String normalized = answer == null ? "" : answer.trim().toLowerCase();
        if ("a".equals(normalized) || "always".equals(normalized)) {
            store.allow(rule);
            return PermissionDecision.allow("approved and persisted: " + rule);
        }
        if ("y".equals(normalized) || "yes".equals(normalized)) {
            return PermissionDecision.allow("approved once by user");
        }
        return PermissionDecision.deny("user denied approval");
    }

    private String ruleFor(ToolCall call) {
        if ("bash".equals(call.name())) {
            return "bash:" + call.arguments().path("command").asText("").trim();
        }
        if (call.name().startsWith("external__")) {
            return "external:" + call.name();
        }
        return "tool:" + call.name();
    }
}
