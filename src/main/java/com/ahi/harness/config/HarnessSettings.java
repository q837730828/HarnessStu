package com.ahi.harness.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Runtime configuration loaded from .harness/settings.json with conservative
 * defaults. Environment variables only override model endpoint settings.
 */
public class HarnessSettings {
    private String model = "deepseek-v4-flash";
    private String baseUrl = "https://api.deepseek.com";
    private int maxSteps = 48;
    // 0 means no wall-clock deadline. A positive value is enforced at the
    // AgentLoop's cooperative safe boundaries (before model/tool work).
    private int runTimeoutSeconds = 0;
    private int compactMaxMessages = 40;
    private int compactKeepRecentMessages = 16;
    private int compactMaxTokens = 60000;
    private int observationMaxActiveChars = 12000;
    private boolean logJsonBodies = true;
    private String permissionMode = "strict";
    private int bashDefaultTimeoutSeconds = 60;
    private int bashMaxTimeoutSeconds = 300;
    private List<ExternalToolServer> externalTools = new ArrayList<ExternalToolServer>();
    private List<String> externalToolAllowlist = new ArrayList<String>();
    private List<String> bashAllowedPrefixes = new ArrayList<String>(Arrays.asList(
            "git status", "git diff", "git log", "git show",
            "mvn test", "mvn package", "mvn clean test", "mvn clean package",
            "mvn -q test", "mvn -q package", "mvn -q clean test", "mvn -q clean package",
            "java -version", "javac -version",
            "npm test", "npm run", "pytest", "python -m pytest",
            "gradle test", "gradle build", ".\\gradlew test", ".\\gradlew build",
            "dir", "ls", "get-childitem", "rg "
    ));
    private List<String> bashBlockedTokens = new ArrayList<String>(Arrays.asList(
            "rm ", "rm-", "del ", "erase ", "rmdir ", "remove-item", "ri ",
            "format", "shutdown", "restart-computer", "stop-computer",
            "git reset", "git clean", "git checkout", "git switch", "git restore",
            "set-content", "out-file", "new-item", "copy-item", "move-item",
            "invoke-webrequest", "iwr ", "curl ", "wget ",
            "start-process", "invoke-expression", "iex "
    ));
    private List<HookCommand> hooks = new ArrayList<HookCommand>();

    public static HarnessSettings load(File workspace) throws Exception {
        HarnessSettings settings = new HarnessSettings();
        File file = new File(workspace, ".harness/settings.json");
        if (!file.exists()) {
            return settings;
        }
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        // Each read keeps the previous default if the field is absent or typed
        // differently. That makes partial settings files cheap to maintain.
        settings.model = text(root, "model", settings.model);
        settings.baseUrl = text(root, "base_url", settings.baseUrl);
        settings.maxSteps = integer(root, "max_steps", settings.maxSteps);
        settings.runTimeoutSeconds = integer(root, "run_timeout_seconds", settings.runTimeoutSeconds);
        settings.compactMaxMessages = integer(root, "compact_max_messages", settings.compactMaxMessages);
        settings.compactKeepRecentMessages = integer(root, "compact_keep_recent_messages", settings.compactKeepRecentMessages);
        settings.compactMaxTokens = integer(root, "compact_max_tokens", settings.compactMaxTokens);
        settings.observationMaxActiveChars = integer(root, "observation_max_active_chars", settings.observationMaxActiveChars);
        settings.logJsonBodies = bool(root, "log_json_bodies", settings.logJsonBodies);
        settings.permissionMode = text(root, "permission_mode", settings.permissionMode);
        settings.bashDefaultTimeoutSeconds = integer(root, "bash_default_timeout_seconds", settings.bashDefaultTimeoutSeconds);
        settings.bashMaxTimeoutSeconds = integer(root, "bash_max_timeout_seconds", settings.bashMaxTimeoutSeconds);
        settings.bashAllowedPrefixes = strings(root, "bash_allowed_prefixes", settings.bashAllowedPrefixes);
        settings.bashBlockedTokens = strings(root, "bash_blocked_tokens", settings.bashBlockedTokens);
        settings.externalTools = externalTools(root.path("external_tools"));
        settings.externalToolAllowlist = strings(root, "external_tool_allowlist", settings.externalToolAllowlist);
        settings.hooks = hooks(root.path("hooks"));
        return settings;
    }

    public String model() {
        return env("DEEPSEEK_MODEL", model);
    }

    public String baseUrl() {
        return env("DEEPSEEK_BASE_URL", baseUrl);
    }

    public int maxSteps() {
        return maxSteps;
    }

    public int runTimeoutSeconds() {
        return Math.max(0, runTimeoutSeconds);
    }

    public int compactMaxMessages() {
        return compactMaxMessages;
    }

    public int compactKeepRecentMessages() {
        return compactKeepRecentMessages;
    }

    public int compactMaxTokens() {
        return compactMaxTokens;
    }

    public int observationMaxActiveChars() {
        return observationMaxActiveChars;
    }

    public boolean logJsonBodies() {
        return logJsonBodies;
    }

    public String permissionMode() {
        return permissionMode;
    }

    public int bashDefaultTimeoutSeconds() {
        return bashDefaultTimeoutSeconds;
    }

    public int bashMaxTimeoutSeconds() {
        return bashMaxTimeoutSeconds;
    }

    public List<String> bashAllowedPrefixes() {
        return bashAllowedPrefixes;
    }

    public List<String> bashBlockedTokens() {
        return bashBlockedTokens;
    }

    public List<ExternalToolServer> externalTools() {
        return externalTools;
    }

    public List<String> externalToolAllowlist() {
        return externalToolAllowlist;
    }

    public List<HookCommand> hooks() {
        return hooks;
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private static String text(JsonNode root, String name, String fallback) {
        JsonNode node = root.path(name);
        return node.isTextual() ? node.asText() : fallback;
    }

    private static int integer(JsonNode root, String name, int fallback) {
        JsonNode node = root.path(name);
        return node.canConvertToInt() ? node.asInt() : fallback;
    }

    private static boolean bool(JsonNode root, String name, boolean fallback) {
        JsonNode node = root.path(name);
        return node.isBoolean() ? node.asBoolean() : fallback;
    }

    private static List<String> strings(JsonNode root, String name, List<String> fallback) {
        JsonNode node = root.path(name);
        if (!node.isArray()) {
            return fallback;
        }
        List<String> values = new ArrayList<String>();
        for (JsonNode item : node) {
            if (item.isTextual()) {
                values.add(item.asText());
            }
        }
        return values.isEmpty() ? fallback : values;
    }

    private static List<ExternalToolServer> externalTools(JsonNode node) {
        List<ExternalToolServer> servers = new ArrayList<ExternalToolServer>();
        if (!node.isArray()) {
            return servers;
        }
        for (JsonNode item : node) {
            String name = text(item, "name", "");
            String type = text(item, "type", "stdio");
            String command = text(item, "command", "");
            String url = text(item, "url", "");
            String protocol = text(item, "protocol", "simple");
            List<String> args = strings(item, "args", new ArrayList<String>());
            // Stdio servers need a command; Streamable HTTP servers need a URL.
            // Both are normalized into the same ExternalToolServer shape.
            if (!name.trim().isEmpty() && "stdio".equals(type) && !command.trim().isEmpty()) {
                servers.add(new ExternalToolServer(name, type, protocol, command, args, url));
            }
            if (!name.trim().isEmpty() && "streamable_http".equals(type) && !url.trim().isEmpty()) {
                servers.add(new ExternalToolServer(name, type, "mcp", command, args, url));
            }
        }
        return servers;
    }

    private static List<HookCommand> hooks(JsonNode node) {
        List<HookCommand> hooks = new ArrayList<HookCommand>();
        if (!node.isArray()) {
            return hooks;
        }
        for (JsonNode item : node) {
            String event = text(item, "event", "");
            String command = text(item, "command", "");
            String script = text(item, "script", "");
            List<String> args = strings(item, "args", new ArrayList<String>());
            List<String> tools = hookTools(item);
            boolean blocking = bool(item, "blocking", false);
            int timeoutSeconds = integer(item, "timeout_seconds", 30);
            // A hook may be an inline command or a workspace-relative script.
            if (!event.trim().isEmpty() && (!command.trim().isEmpty() || !script.trim().isEmpty())) {
                hooks.add(new HookCommand(event, command, script, args, tools, blocking, Math.max(1, Math.min(timeoutSeconds, 120))));
            }
        }
        return hooks;
    }

    private static List<String> hookTools(JsonNode item) {
        List<String> tools = strings(item, "tools", new ArrayList<String>());
        String tool = text(item, "tool", "");
        if (!tool.trim().isEmpty() && !tools.contains(tool.trim())) {
            tools.add(tool.trim());
        }
        return tools;
    }

    public static class ExternalToolServer {
        private final String name;
        private final String type;
        private final String protocol;
        private final String command;
        private final List<String> args;
        private final String url;

        public ExternalToolServer(String name, String type, String protocol, String command, List<String> args, String url) {
            this.name = name;
            this.type = type;
            this.protocol = protocol;
            this.command = command;
            this.args = args;
            this.url = url;
        }

        public String name() {
            return name;
        }

        public String type() {
            return type;
        }

        public String protocol() {
            return protocol;
        }

        public String command() {
            return command;
        }

        public List<String> args() {
            return args;
        }

        public String url() {
            return url;
        }
    }

    public static class HookCommand {
        private final String event;
        private final String command;
        private final String script;
        private final List<String> args;
        private final List<String> tools;
        private final boolean blocking;
        private final int timeoutSeconds;

        public HookCommand(String event, String command, boolean blocking, int timeoutSeconds) {
            this(event, command, "", new ArrayList<String>(), new ArrayList<String>(), blocking, timeoutSeconds);
        }

        public HookCommand(String event, String command, String script, List<String> args, List<String> tools, boolean blocking, int timeoutSeconds) {
            this.event = event;
            this.command = command;
            this.script = script;
            this.args = args == null ? new ArrayList<String>() : args;
            this.tools = tools == null ? new ArrayList<String>() : tools;
            this.blocking = blocking;
            this.timeoutSeconds = timeoutSeconds;
        }

        public String event() {
            return event;
        }

        public String command() {
            return command;
        }

        public String script() {
            return script;
        }

        public List<String> args() {
            return args;
        }

        public List<String> tools() {
            return tools;
        }

        public boolean blocking() {
            return blocking;
        }

        public int timeoutSeconds() {
            return timeoutSeconds;
        }
    }
}
