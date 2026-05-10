package com.ahi.harness;

import com.ahi.harness.core.AgentLoop;
import com.ahi.harness.core.Conversation;
import com.ahi.harness.model.DeepSeekClient;
import com.ahi.harness.permission.PermissionPolicy;
import com.ahi.harness.session.JsonlSessionStore;
import com.ahi.harness.tools.BashTool;
import com.ahi.harness.tools.EditFileTool;
import com.ahi.harness.tools.GrepTool;
import com.ahi.harness.tools.ListFilesTool;
import com.ahi.harness.tools.ReadFileTool;
import com.ahi.harness.tools.ToolRegistry;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public class Main {
    public static void main(String[] args) throws Exception {
        File workspace = new File(".").getCanonicalFile();
        ConsoleLog log = new ConsoleLog();

        log.info("HARNESS", "workspace = " + workspace.getAbsolutePath());
        log.info("HARNESS", "model = " + env("DEEPSEEK_MODEL", "deepseek-v4-flash"));

        String apiKey = System.getenv("DEEPSEEK_API_KEY");
        if (apiKey == null || apiKey.trim().isEmpty()) {
            log.error("HARNESS", "Missing DEEPSEEK_API_KEY. Set it before running.");
            return;
        }

        ToolRegistry registry = new ToolRegistry();
        registry.register(new ListFilesTool(workspace));
        registry.register(new ReadFileTool(workspace));
        registry.register(new GrepTool(workspace));
        registry.register(new EditFileTool(workspace, log));
        registry.register(new BashTool(workspace));

        Conversation conversation = new Conversation();
        conversation.addSystem("You are a coding agent running inside a small Java harness. "
                + "Use tools when you need facts from the local workspace. "
                + "Prefer list_files, read_file, and grep before answering codebase questions. "
                + "Before editing a file, read it first. Use edit_file with exact old_text and new_text. "
                + "When you have enough information, respond with a concise final answer.");

        DeepSeekClient model = new DeepSeekClient(
                env("DEEPSEEK_BASE_URL", "https://api.deepseek.com"),
                apiKey,
                env("DEEPSEEK_MODEL", "deepseek-v4-flash"),
                log
        );

        AgentLoop loop = new AgentLoop(
                model,
                registry,
                new PermissionPolicy(),
                new JsonlSessionStore(new File(workspace, ".harness/sessions"), log),
                log
        );

        String oneShot = joinArgs(args);
        if (!oneShot.isEmpty()) {
            loop.run(conversation, oneShot);
            return;
        }

        log.info("HARNESS", "interactive mode. Type /exit to quit.");
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        while (true) {
            System.out.print("\n你> ");
            String line = reader.readLine();
            if (line == null || "/exit".equalsIgnoreCase(line.trim())) {
                break;
            }
            if (line.trim().isEmpty()) {
                continue;
            }
            loop.run(conversation, line);
        }
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private static String joinArgs(String[] args) {
        if (args == null || args.length == 0) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (String arg : args) {
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(arg);
        }
        return builder.toString();
    }
}
