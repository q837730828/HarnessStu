package com.ahi.harness;

import com.ahi.harness.cli.SlashCommandHandler;
import com.ahi.harness.config.HarnessSettings;
import com.ahi.harness.core.AgentLoop;
import com.ahi.harness.core.Conversation;
import com.ahi.harness.hooks.HookBus;
import com.ahi.harness.memory.ProjectMemoryLoader;
import com.ahi.harness.model.DeepSeekClient;
import com.ahi.harness.permission.PermissionPolicy;
import com.ahi.harness.permission.PermissionPrompter;
import com.ahi.harness.permission.PermissionStore;
import com.ahi.harness.protocol.AgentIdentity;
import com.ahi.harness.protocol.AgentThread;
import com.ahi.harness.runtime.JsonlRuntimeStore;
import com.ahi.harness.runtime.RunRecorder;
import com.ahi.harness.session.CompactionArchiveStore;
import com.ahi.harness.session.JsonlSessionStore;
import com.ahi.harness.session.ObservationArchiveStore;
import com.ahi.harness.session.TraceStore;
import com.ahi.harness.subagent.SubagentRegistry;
import com.ahi.harness.tools.SubagentRunTool;
import com.ahi.harness.tools.Tool;
import com.ahi.harness.tools.ToolRegistry;
import com.ahi.harness.tools.external.McpServerManager;
import com.ahi.harness.tools.provider.BuiltInToolProvider;
import com.ahi.harness.tools.provider.ExternalStdioToolProvider;
import com.ahi.harness.tools.provider.ToolProvider;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Program entrypoint. This class intentionally wires the runtime by hand so the
 * learning path is visible: settings -> tools -> model -> loop -> CLI.
 */
public class Main {
    public static void main(String[] args) throws Exception {
        File workspace = new File(".").getCanonicalFile();
        ConsoleLog log = new ConsoleLog();
        HarnessSettings settings = HarnessSettings.load(workspace);
        HookBus hooks = new HookBus(workspace, log, settings.hooks());

        log.info("HARNESS", "workspace = " + workspace.getAbsolutePath());
        log.info("HARNESS", "model = " + settings.model());
        log.info("HARNESS", "permission_mode = " + settings.permissionMode());
        log.info("HARNESS", "settings = .harness/settings.json"
                + (new File(workspace, ".harness/settings.json").exists() ? " loaded" : " default"));

        String apiKey = System.getenv("DEEPSEEK_API_KEY");
        if (apiKey == null || apiKey.trim().isEmpty()) {
            log.error("HARNESS", "Missing DEEPSEEK_API_KEY. Set it before running.");
            return;
        }

        ToolRegistry registry = new ToolRegistry();
        DeepSeekClient model = new DeepSeekClient(
                settings.baseUrl(),
                apiKey,
                settings.model(),
                log,
                settings.logJsonBodies()
        );

        // Built-ins are registered first so subagents can receive a safe subset
        // of the same tool instances that the parent agent sees.
        registerTools(registry, new BuiltInToolProvider(workspace, log, settings), log);
        registerTool(registry, new SubagentRunTool(workspace, model, registry, new SubagentRegistry(workspace, log), log), log);

        // MCP servers are managed as runtime resources instead of throwaway
        // clients. This preserves stdio processes and HTTP session ids.
        McpServerManager mcpManager = new McpServerManager(workspace, settings.externalTools(), log);
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                mcpManager.closeAll();
            }
        }));
        for (HarnessSettings.ExternalToolServer server : settings.externalTools()) {
            try {
                registerTools(registry, new ExternalStdioToolProvider(workspace, server, log, mcpManager), log);
            } catch (Exception e) {
                log.error("EXTERNAL", "Failed to load external tool server " + server.name() + ": " + e.getMessage());
            }
        }

        Conversation conversation = new Conversation();
        conversation.addSystem(systemPrompt(workspace));

        // Session logs, compaction archives, observation archives, and traces are
        // separate stores so each concern can evolve without complicating the loop.
        File sessionDirectory = new File(workspace, ".harness/sessions");
        JsonlSessionStore sessionStore = new JsonlSessionStore(sessionDirectory, log);
        TraceStore traceStore = new TraceStore(workspace);
        log.info("HARNESS", "trace = " + traceStore.path());

        // Agent and Thread used to be implicit in the process and Conversation.
        // They are now stable protocol resources shared by every Run created in
        // this interactive session.
        AgentIdentity agent = AgentIdentity.create("HarnessStu", settings.model(), toolNames(registry));
        AgentThread thread = AgentThread.create("HarnessStu interactive session");
        JsonlRuntimeStore runtimeStore = new JsonlRuntimeStore(workspace);
        RunRecorder runRecorder = new RunRecorder(agent, thread, runtimeStore);
        log.info("PROTOCOL", "agent_id=" + agent.getId() + ", thread_id=" + thread.getId());
        AgentLoop loop = new AgentLoop(
                model,
                registry,
                new PermissionPolicy(settings.bashAllowedPrefixes(), settings.bashBlockedTokens(), settings.externalToolAllowlist(), settings.permissionMode()),
                sessionStore,
                log,
                hooks,
                new PermissionPrompter(log, new PermissionStore(workspace)),
                new CompactionArchiveStore(workspace),
                new ObservationArchiveStore(workspace, settings.observationMaxActiveChars()),
                traceStore,
                settings.maxSteps(),
                settings.compactMaxMessages(),
                settings.compactKeepRecentMessages(),
                settings.compactMaxTokens(),
                runRecorder,
                settings.runTimeoutSeconds()
        );
        SlashCommandHandler slash = new SlashCommandHandler(
                workspace,
                sessionDirectory,
                registry,
                log,
                settings.compactKeepRecentMessages(),
                settings.externalTools(),
                mcpManager,
                runtimeStore
        );

        String oneShot = joinArgs(args);
        if (!oneShot.isEmpty()) {
            if (!slash.handle(oneShot, conversation)) {
                loop.run(conversation, oneShot);
            }
            mcpManager.closeAll();
            hooks.emit("SessionEnd", "one-shot");
            return;
        }

        log.info("HARNESS", "interactive mode. Type /help for commands, /exit to quit.");
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        while (true) {
            System.out.print("\nyou> ");
            String line = reader.readLine();
            if (line == null || "/exit".equalsIgnoreCase(line.trim())) {
                mcpManager.closeAll();
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
        // Keep the always-on prompt short. Detailed project knowledge should be
        // pulled through doc_read or skill_load so the stable prefix stays small.
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are a coding agent running inside a small Java harness. ");
        prompt.append("Use tools when you need facts from the local workspace. ");
        prompt.append("Prefer list_files, read_file, grep, doc_read, and skill_load before answering codebase questions. ");
        prompt.append("Keep stable instructions short: use doc_read for detailed project docs and skill_load for task-specific Markdown skills instead of assuming all context is already loaded. ");
        prompt.append("For multi-step tasks, use todo_write to maintain a visible plan, keep exactly one active step in_progress, and update it as work completes. ");
        prompt.append("Use subagent_run to delegate focused read-only exploration or review work to explorer, reviewer, or project subagents loaded from .harness/agents/*.md when it helps. ");
        prompt.append("Use subagent_write, not edit_file, when the user asks to create or update a project subagent definition. ");
        prompt.append("Before editing a file, read it first. Use edit_file with exact old_text and new_text. ");
        prompt.append("After code edits, verify with bash using safe commands such as git diff or mvn test/package, then use the results to continue or finish. ");
        prompt.append("External tools are exposed with names like external__provider__tool and are subject to the same permission checks. ");
        prompt.append("When you have enough information, respond with a concise final answer.");

        String memory = new ProjectMemoryLoader().load(workspace);
        if (!memory.trim().isEmpty()) {
            prompt.append("\n\n").append(memory);
        }
        return prompt.toString();
    }

    private static void registerTools(ToolRegistry registry, ToolProvider provider, ConsoleLog log) throws Exception {
        for (Tool tool : provider.loadTools()) {
            registerTool(registry, tool, log);
        }
    }

    private static void registerTool(ToolRegistry registry, Tool tool, ConsoleLog log) {
        registry.register(tool);
        log.info("HARNESS", "registered tool: " + tool.name());
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

    private static List<String> toolNames(ToolRegistry registry) {
        List<String> names = new ArrayList<String>();
        for (Tool tool : registry.all()) {
            names.add(tool.name());
        }
        return names;
    }
}
