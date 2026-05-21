package com.ahi.harness.tools;

import com.ahi.harness.ConsoleLog;
import com.ahi.harness.core.AgentLoop;
import com.ahi.harness.core.Conversation;
import com.ahi.harness.core.Message;
import com.ahi.harness.hooks.HookBus;
import com.ahi.harness.memory.ProjectMemoryLoader;
import com.ahi.harness.model.ModelClient;
import com.ahi.harness.permission.PermissionPolicy;
import com.ahi.harness.session.NoopSessionStore;
import com.ahi.harness.subagent.SubagentDefinition;
import com.ahi.harness.subagent.SubagentRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs a focused child agent with its own conversation.
 *
 * The parent receives only a compact summary, so exploratory search and failed
 * attempts do not pollute the parent's active context.
 */
public class SubagentRunTool implements Tool {
    private static final int MAX_TASK_CHARS = 2000;
    private static final int MAX_STEPS = 16;
    private static final int MAX_OBSERVATION_CHARS = 6000;

    private final File workspace;
    private final ModelClient model;
    private final ToolRegistry parentTools;
    private final SubagentRegistry subagents;
    private final ConsoleLog log;
    private final ObjectMapper mapper = new ObjectMapper();

    public SubagentRunTool(File workspace,
                           ModelClient model,
                           ToolRegistry parentTools,
                           SubagentRegistry subagents,
                           ConsoleLog log) {
        this.workspace = workspace;
        this.model = model;
        this.parentTools = parentTools;
        this.subagents = subagents;
        this.log = log;
    }

    @Override
    public String name() {
        return "subagent_run";
    }

    @Override
    public String description() {
        return "Run a focused read-only subagent in an isolated conversation and return its final summary.";
    }

    @Override
    public ObjectNode parameters() {
        ObjectNode schema = JsonSchemas.object();
        JsonSchemas.addRequired(schema, "name", JsonSchemas.stringProperty("Subagent name. Built-ins include explorer and reviewer; project subagents may be loaded from .harness/agents/*.md."));
        JsonSchemas.addRequired(schema, "task", JsonSchemas.stringProperty("Focused task for the subagent."));
        JsonSchemas.addOptional(schema, "max_steps", JsonSchemas.stringProperty("Optional step limit, clamped to 1..16. Defaults to the subagent setting."));
        return schema;
    }

    @Override
    public ToolResult execute(JsonNode arguments) throws Exception {
        String name = arguments.path("name").asText("").trim();
        String task = arguments.path("task").asText("").trim();
        if (name.isEmpty()) {
            return ToolResult.failure("subagent_run requires name.");
        }
        if (task.isEmpty()) {
            return ToolResult.failure("subagent_run requires task.");
        }
        if (task.length() > MAX_TASK_CHARS) {
            return ToolResult.failure("task is too long: " + task.length() + " > " + MAX_TASK_CHARS);
        }

        SubagentDefinition definition = subagents.get(name);
        if (definition == null) {
            return ToolResult.failure("Unknown subagent: " + name + ". Available: " + availableSubagents());
        }

        ToolRegistry childTools = childToolRegistry(definition);
        int maxSteps = parseMaxSteps(arguments.path("max_steps").asText(""), definition.defaultMaxSteps());

        log.section("SUBAGENT RUN / Subagent run: " + definition.name());
        log.info("SUBAGENT", "task=" + task + ", max_steps=" + maxSteps + ", tools=" + definition.allowedTools());
        if (!definition.model().isEmpty() || !definition.mcpServers().isEmpty()) {
            log.info("SUBAGENT", "declared model=" + definition.model() + ", mcp_servers=" + definition.mcpServers() + " (metadata only in MVP11)");
        }

        Conversation childConversation = new Conversation();
        childConversation.addSystem(systemPrompt(definition));
        AgentLoop loop = new AgentLoop(
                model,
                childTools,
                new PermissionPolicy(java.util.Arrays.asList("git status"), java.util.Arrays.asList("rm ", "del ", "git reset", "remove-item"), java.util.Collections.<String>emptyList(), definition.permissionMode()),
                new NoopSessionStore(),
                log,
                new HookBus(log),
                maxSteps,
                24,
                10
        );
        loop.run(childConversation, task);

        String finalAnswer = lastAssistantContent(childConversation);
        if (finalAnswer.trim().isEmpty()) {
            finalAnswer = "Subagent finished without an assistant summary.";
        }

        ObjectNode out = mapper.createObjectNode();
        out.put("name", definition.name());
        out.put("description", definition.description());
        out.put("messages", childConversation.size());
        out.put("max_steps", maxSteps);
        out.put("summary", trim(finalAnswer, MAX_OBSERVATION_CHARS));
        return ToolResult.success(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(out));
    }

    private ToolRegistry childToolRegistry(SubagentDefinition definition) {
        ToolRegistry registry = new ToolRegistry();
        for (String toolName : definition.allowedTools()) {
            // Project subagents can only receive tools that already exist in the
            // parent registry and pass SubagentLoader validation.
            if (definition.disallowedTools().contains(toolName)) {
                continue;
            }
            Tool tool = parentTools.get(toolName);
            if (tool != null) {
                registry.register(tool);
            }
        }
        return registry;
    }

    private String systemPrompt(SubagentDefinition definition) throws Exception {
        StringBuilder prompt = new StringBuilder();
        prompt.append(definition.systemPrompt());
        prompt.append(" You are running as an isolated subagent. ");
        prompt.append("You cannot edit files, run shell commands, or call other subagents in this MVP. ");
        prompt.append("When done, return a concise final summary for the parent agent.");

        if (definition.memory()) {
            // Memory is opt-in because subagents should usually stay narrow and
            // cheap; loading project memory widens their context.
            String memory = new ProjectMemoryLoader().load(workspace);
            if (!memory.trim().isEmpty()) {
                prompt.append("\n\n").append(memory);
            }
        }
        return prompt.toString();
    }

    private String lastAssistantContent(Conversation conversation) {
        List<Message> messages = new ArrayList<Message>(conversation.messages());
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message message = messages.get(i);
            if ("assistant".equals(message.role()) && message.content() != null && !message.content().trim().isEmpty()) {
                return message.content();
            }
        }
        return "";
    }

    private int parseMaxSteps(String text, int fallback) {
        try {
            int value = Integer.parseInt(text);
            return Math.max(1, Math.min(value, MAX_STEPS));
        } catch (Exception ignored) {
            return Math.max(1, Math.min(fallback, MAX_STEPS));
        }
    }

    private String availableSubagents() {
        StringBuilder out = new StringBuilder();
        for (SubagentDefinition definition : subagents.all()) {
            if (out.length() > 0) {
                out.append(", ");
            }
            out.append(definition.name());
        }
        return out.toString();
    }

    private String trim(String value, int maxChars) {
        if (value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, maxChars) + "\n...<truncated " + (value.length() - maxChars) + " chars>";
    }
}
