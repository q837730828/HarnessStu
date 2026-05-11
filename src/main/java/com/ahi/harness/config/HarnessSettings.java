package com.ahi.harness.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class HarnessSettings {
    private String model = "deepseek-v4-flash";
    private String baseUrl = "https://api.deepseek.com";
    private int maxSteps = 48;
    private int compactMaxMessages = 40;
    private int compactKeepRecentMessages = 16;
    private boolean logJsonBodies = true;
    private int bashDefaultTimeoutSeconds = 60;
    private int bashMaxTimeoutSeconds = 300;
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

    public static HarnessSettings load(File workspace) throws Exception {
        HarnessSettings settings = new HarnessSettings();
        File file = new File(workspace, ".harness/settings.json");
        if (!file.exists()) {
            return settings;
        }
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        settings.model = text(root, "model", settings.model);
        settings.baseUrl = text(root, "base_url", settings.baseUrl);
        settings.maxSteps = integer(root, "max_steps", settings.maxSteps);
        settings.compactMaxMessages = integer(root, "compact_max_messages", settings.compactMaxMessages);
        settings.compactKeepRecentMessages = integer(root, "compact_keep_recent_messages", settings.compactKeepRecentMessages);
        settings.logJsonBodies = bool(root, "log_json_bodies", settings.logJsonBodies);
        settings.bashDefaultTimeoutSeconds = integer(root, "bash_default_timeout_seconds", settings.bashDefaultTimeoutSeconds);
        settings.bashMaxTimeoutSeconds = integer(root, "bash_max_timeout_seconds", settings.bashMaxTimeoutSeconds);
        settings.bashAllowedPrefixes = strings(root, "bash_allowed_prefixes", settings.bashAllowedPrefixes);
        settings.bashBlockedTokens = strings(root, "bash_blocked_tokens", settings.bashBlockedTokens);
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

    public int compactMaxMessages() {
        return compactMaxMessages;
    }

    public int compactKeepRecentMessages() {
        return compactKeepRecentMessages;
    }

    public boolean logJsonBodies() {
        return logJsonBodies;
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
}
