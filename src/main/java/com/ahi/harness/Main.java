package com.ahi.harness;

import com.ahi.harness.cli.SlashCommandHandler;
import com.ahi.harness.config.HarnessSettings;
import com.ahi.harness.core.AgentLoop;
import com.ahi.harness.core.Conversation;
import com.ahi.harness.hooks.HookBus;
import com.ahi.harness.memory.ProjectMemoryLoader;
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
        HarnessSettings settings = HarnessSettings.load(workspace);
        HookBus hooks = new HookBus(log);

        log.info("HARNESS", "workspace = " + workspace.getAbsolutePath());
        log.info("HARNESS", "model = " + settings.model());
        log.info("HARNESS", "settings = .harness/settings.json"
                + (new File(workspace, ".harness/settings.json").exists() ? " loaded" : " default"));

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
        registry.register(new BashTool(workspace, log, settings.bashDefaultTimeoutSeconds(), settings.bashMaxTimeoutSeconds()));

        Conversation conversation = new Conversation();
        conversation.addSystem(systemPrompt(workspace));

        DeepSeekClient model = new DeepSeekClient(
                settings.baseUrl(),
                apiKey,
                settings.model(),
                log,
                settings.logJsonBodies()
        );

        File sessionDirectory = new File(workspace, ".harness/sessions");
        JsonlSessionStore sessionStore = new JsonlSessionStore(sessionDirectory, log);
        AgentLoop loop = new AgentLoop(
                model,
                registry,
                new PermissionPolicy(settings.bashAllowedPrefixes(), settings.bashBlockedTokens()),
                sessionStore,
                log,
                hooks,
                settings.maxSteps(),
                settings.compactMaxMessages(),
                settings.compactKeepRecentMessages()
        );
        SlashCommandHandler slash = new SlashCommandHandler(
                workspace,
                sessionDirectory,
                registry,
                log,
                settings.compactKeepRecentMessages()
        );

        String oneShot = joinArgs(args);
        if (!oneShot.isEmpty()) {
            if (!slash.handle(oneShot, conversation)) {
                loop.run(conversation, oneShot);
            }
            hooks.emit("SessionEnd", "one-shot");
            return;
        }

        log.info("HARNESS", "interactive mode. Type /help for commands, /exit to quit.");
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        while (true) {
            System.out.print("\nyou> ");
            String line = reader.readLine();
            if (line == null || "/exit".equalsIgnoreCase(line.trim())) {
                hooks.emit("SessionEnd", "interactive");
                break;
            }
            if (line.trim().isEmpty()) {
                continue;
            }
            if (slash.handle(line, conversation)) {
                continue;
            }
            loop.run(conversation, line);
        }
    }

    private static String systemPrompt(File workspace) throws Exception {
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are a coding agent running inside a small Java harness. ");
        prompt.append("Use tools when you need facts from the local workspace. ");
        prompt.append("Prefer list_files, read_file, and grep before answering codebase questions. ");
        prompt.append("Before editing a file, read it first. Use edit_file with exact old_text and new_text. ");
        prompt.append("After code edits, verify with bash using safe commands such as git diff or mvn test/package, then use the results to continue or finish. ");
        prompt.append("When you have enough information, respond with a concise final answer.");

        String memory = new ProjectMemoryLoader().load(workspace);
        if (!memory.trim().isEmpty()) {
            prompt.append("\n\n").append(memory);
        }
        return prompt.toString();
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
