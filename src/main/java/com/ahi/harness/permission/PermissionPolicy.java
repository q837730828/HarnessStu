package com.ahi.harness.permission;

import com.ahi.harness.core.ToolCall;

import java.util.Locale;

public class PermissionPolicy {
    public PermissionDecision check(ToolCall call) {
        if ("list_files".equals(call.name()) || "read_file".equals(call.name()) || "grep".equals(call.name())) {
            return PermissionDecision.allow("read-only workspace tool");
        }
        if ("bash".equals(call.name())) {
            return checkBash(call.arguments().path("command").asText(""));
        }
        return PermissionDecision.deny("tool is not registered in the permission policy");
    }

    private PermissionDecision checkBash(String command) {
        String normalized = command.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return PermissionDecision.deny("empty command");
        }

        String[] blocked = {
                "rm ", "rm-", "del ", "erase ", "rmdir ", "remove-item", "ri ",
                "format", "shutdown", "restart-computer", "stop-computer",
                "git reset", "git clean", "git checkout", "git switch", "git restore",
                "set-content", "out-file", "new-item", "copy-item", "move-item",
                "invoke-webrequest", "iwr ", "curl ", "wget "
        };
        for (String token : blocked) {
            if (normalized.contains(token)) {
                return PermissionDecision.deny("blocked shell token: " + token.trim());
            }
        }

        String[] allowedPrefixes = {
                "git status", "git diff", "git log", "git show",
                "mvn test", "mvn package", "mvn -q test", "mvn -q package",
                "java -version", "javac -version",
                "npm test", "npm run", "pytest", "python -m pytest",
                "dir", "ls", "get-childitem", "rg "
        };
        for (String prefix : allowedPrefixes) {
            if (normalized.equals(prefix) || normalized.startsWith(prefix + " ")) {
                return PermissionDecision.allow("allowlisted command prefix: " + prefix);
            }
        }

        return PermissionDecision.deny("command is not in the allowlist");
    }
}
