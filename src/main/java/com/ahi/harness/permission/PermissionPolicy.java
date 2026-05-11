package com.ahi.harness.permission;

import com.ahi.harness.core.ToolCall;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public class PermissionPolicy {
    private final List<String> bashAllowedPrefixes;
    private final List<String> bashBlockedTokens;
    private final List<String> externalToolAllowlist;

    public PermissionPolicy(List<String> bashAllowedPrefixes, List<String> bashBlockedTokens, List<String> externalToolAllowlist) {
        this.bashAllowedPrefixes = bashAllowedPrefixes;
        this.bashBlockedTokens = bashBlockedTokens;
        this.externalToolAllowlist = externalToolAllowlist;
    }

    public PermissionPolicy() {
        this(Arrays.asList(
                "git status", "git diff", "git log", "git show",
                "mvn test", "mvn package", "mvn clean test", "mvn clean package",
                "mvn -q test", "mvn -q package", "mvn -q clean test", "mvn -q clean package",
                "java -version", "javac -version",
                "npm test", "npm run", "pytest", "python -m pytest",
                "gradle test", "gradle build", ".\\gradlew test", ".\\gradlew build",
                "dir", "ls", "get-childitem", "rg "
        ), Arrays.asList(
                "rm ", "rm-", "del ", "erase ", "rmdir ", "remove-item", "ri ",
                "format", "shutdown", "restart-computer", "stop-computer",
                "git reset", "git clean", "git checkout", "git switch", "git restore",
                "set-content", "out-file", "new-item", "copy-item", "move-item",
                "invoke-webrequest", "iwr ", "curl ", "wget ",
                "start-process", "invoke-expression", "iex "
        ), Arrays.<String>asList());
    }

    public PermissionDecision check(ToolCall call) {
        if ("list_files".equals(call.name()) || "read_file".equals(call.name()) || "grep".equals(call.name())) {
            return PermissionDecision.allow("read-only workspace tool");
        }
        if ("todo_read".equals(call.name()) || "todo_write".equals(call.name())) {
            return PermissionDecision.allow("agent planning tool with structured validation");
        }
        if ("edit_file".equals(call.name())) {
            return checkEditFile(call);
        }
        if ("bash".equals(call.name())) {
            return checkBash(call.arguments().path("command").asText(""));
        }
        if (call.name().startsWith("external__")) {
            return checkExternal(call.name());
        }
        return PermissionDecision.deny("tool is not registered in the permission policy");
    }

    private PermissionDecision checkExternal(String name) {
        if (externalToolAllowlist.contains(name)) {
            return PermissionDecision.allow("external tool allowlisted: " + name);
        }
        return PermissionDecision.deny("external tool is not in external_tool_allowlist: " + name);
    }

    private PermissionDecision checkEditFile(ToolCall call) {
        String path = call.arguments().path("path").asText("");
        String normalized = path.replace('\\', '/').toLowerCase(Locale.ROOT);
        if (normalized.trim().isEmpty()) {
            return PermissionDecision.deny("edit_file path is required");
        }
        if (!call.arguments().has("old_text") || !call.arguments().has("new_text")) {
            return PermissionDecision.deny("edit_file requires old_text and new_text");
        }
        if (normalized.startsWith(".git/")
                || normalized.startsWith(".harness/")
                || normalized.startsWith(".idea/")
                || normalized.startsWith("target/")
                || normalized.contains("/.git/")
                || normalized.contains("/.harness/")
                || normalized.contains("/.idea/")
                || normalized.contains("/target/")) {
            return PermissionDecision.deny("editing generated, private, or VCS metadata paths is blocked");
        }
        return PermissionDecision.allow("workspace edit with exact replacement, diff, and backup");
    }

    private PermissionDecision checkBash(String command) {
        String normalized = command.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return PermissionDecision.deny("empty command");
        }
        if (hasShellControlOperator(normalized)) {
            return PermissionDecision.deny("shell control operators are blocked; run one validation command at a time");
        }

        for (String token : bashBlockedTokens) {
            if (normalized.contains(token)) {
                return PermissionDecision.deny("blocked shell token: " + token.trim());
            }
        }

        for (String prefix : bashAllowedPrefixes) {
            if (normalized.equals(prefix) || normalized.startsWith(prefix + " ")) {
                return PermissionDecision.allow("allowlisted command prefix: " + prefix);
            }
        }

        return PermissionDecision.deny("command is not in the allowlist");
    }

    private boolean hasShellControlOperator(String command) {
        return command.contains(";")
                || command.contains("&&")
                || command.contains("||")
                || command.contains("|")
                || command.contains(">")
                || command.contains("<")
                || command.contains("`")
                || command.contains("\n")
                || command.contains("\r");
    }
}
