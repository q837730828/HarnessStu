package com.ahi.harness.permission;

import com.ahi.harness.core.ToolCall;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Central policy for deciding whether a model-requested tool call may execute.
 *
 * Tools can be registered dynamically, but every mutation or external boundary
 * should pass through this class before side effects happen.
 */
public class PermissionPolicy {
    private final List<String> bashAllowedPrefixes;
    private final List<String> bashBlockedTokens;
    private final List<String> externalToolAllowlist;
    private final String permissionMode;

    public PermissionPolicy(List<String> bashAllowedPrefixes, List<String> bashBlockedTokens, List<String> externalToolAllowlist) {
        this(bashAllowedPrefixes, bashBlockedTokens, externalToolAllowlist, "strict");
    }

    public PermissionPolicy(List<String> bashAllowedPrefixes, List<String> bashBlockedTokens, List<String> externalToolAllowlist, String permissionMode) {
        this.bashAllowedPrefixes = bashAllowedPrefixes;
        this.bashBlockedTokens = bashBlockedTokens;
        this.externalToolAllowlist = externalToolAllowlist;
        this.permissionMode = permissionMode == null ? "strict" : permissionMode.trim().toLowerCase(Locale.ROOT);
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
        // Read-only context tools are always safe because their own
        // implementations still enforce workspace path boundaries.
        if ("list_files".equals(call.name())
                || "read_file".equals(call.name())
                || "grep".equals(call.name())
                || "doc_read".equals(call.name())
                || "skill_load".equals(call.name())) {
            return PermissionDecision.allow("read-only workspace tool");
        }
        if ("todo_read".equals(call.name()) || "todo_write".equals(call.name())) {
            return PermissionDecision.allow("agent planning tool with structured validation");
        }
        if ("subagent_run".equals(call.name())) {
            return PermissionDecision.allow("read-only subagent delegation tool");
        }
        if ("subagent_write".equals(call.name())) {
            return modeDecision("structured project subagent definition writer", "subagent_write");
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
        // External tools are discovered at runtime, so the allowlist is keyed by
        // their exposed name: external__server__tool.
        if (externalToolAllowlist.contains("*")) {
            return PermissionDecision.allow("external tool wildcard allowlist: " + name);
        }
        if (externalToolAllowlist.contains(name)) {
            return PermissionDecision.allow("external tool allowlisted: " + name);
        }
        if ("ask".equals(permissionMode)) {
            return PermissionDecision.ask("external tool is not in external_tool_allowlist: " + name);
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
                || normalized.startsWith(".idea/")
                || normalized.startsWith("target/")
                || normalized.contains("/.git/")
                || normalized.contains("/.idea/")
                || normalized.contains("/target/")) {
            return PermissionDecision.deny("editing generated, private, or VCS metadata paths is blocked");
        }
        if (normalized.startsWith(".harness/") || normalized.contains("/.harness/")) {
            // Runtime state is intentionally not editable through generic text
            // replacement. Dedicated tools own structured .harness writes.
            if (!normalized.equals(".harness/settings.json") && !normalized.endsWith("/.harness/settings.json")) {
                return PermissionDecision.deny("editing harness metadata paths is blocked; only .harness/settings.json is editable");
            }
        }
        return modeDecision("workspace edit with exact replacement, diff, and backup", "edit_file");
    }

    private PermissionDecision checkBash(String command) {
        String normalized = command.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return PermissionDecision.deny("empty command");
        }
        if (hasShellControlOperator(normalized)) {
            // The bash tool executes through PowerShell. Blocking composition
            // keeps validation commands single-purpose and easier to audit.
            return PermissionDecision.deny("shell control operators are blocked; run one validation command at a time");
        }

        for (String token : bashBlockedTokens) {
            if (normalized.contains(token)) {
                return PermissionDecision.deny("blocked shell token: " + token.trim());
            }
        }

        for (String prefix : bashAllowedPrefixes) {
            if ("*".equals(prefix)) {
                return modeDecision("wildcard command allowlist", "bash");
            }
            if (normalized.equals(prefix) || normalized.startsWith(prefix + " ")) {
                return modeDecision("allowlisted command prefix: " + prefix, "bash");
            }
        }

        if ("danger-full-access".equals(permissionMode)) {
            return PermissionDecision.allow("danger-full-access command after blocked-token checks");
        }
        if ("ask".equals(permissionMode)) {
            return PermissionDecision.ask("command is not in the allowlist");
        }
        return PermissionDecision.deny("command is not in the allowlist");
    }

    private PermissionDecision modeDecision(String reason, String tool) {
        if ("ask".equals(permissionMode)
                && ("edit_file".equals(tool) || "bash".equals(tool) || "subagent_write".equals(tool))) {
            return PermissionDecision.ask(reason);
        }
        if ("danger-full-access".equals(permissionMode)) {
            return PermissionDecision.allow("danger-full-access: " + reason);
        }
        return PermissionDecision.allow(reason);
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
