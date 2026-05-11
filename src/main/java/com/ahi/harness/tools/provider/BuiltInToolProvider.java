package com.ahi.harness.tools.provider;

import com.ahi.harness.ConsoleLog;
import com.ahi.harness.config.HarnessSettings;
import com.ahi.harness.tools.BashTool;
import com.ahi.harness.tools.EditFileTool;
import com.ahi.harness.tools.GrepTool;
import com.ahi.harness.tools.ListFilesTool;
import com.ahi.harness.tools.ReadFileTool;
import com.ahi.harness.tools.SubagentWriteTool;
import com.ahi.harness.tools.Tool;
import com.ahi.harness.tools.TodoReadTool;
import com.ahi.harness.tools.TodoWriteTool;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class BuiltInToolProvider implements ToolProvider {
    private final File workspace;
    private final ConsoleLog log;
    private final HarnessSettings settings;

    public BuiltInToolProvider(File workspace, ConsoleLog log, HarnessSettings settings) {
        this.workspace = workspace;
        this.log = log;
        this.settings = settings;
    }

    @Override
    public List<Tool> loadTools() {
        List<Tool> tools = new ArrayList<Tool>();
        tools.add(new ListFilesTool(workspace));
        tools.add(new ReadFileTool(workspace));
        tools.add(new GrepTool(workspace));
        tools.add(new TodoReadTool(workspace));
        tools.add(new TodoWriteTool(workspace, log));
        tools.add(new SubagentWriteTool(workspace, log));
        tools.add(new EditFileTool(workspace, log));
        tools.add(new BashTool(workspace, log, settings.bashDefaultTimeoutSeconds(), settings.bashMaxTimeoutSeconds()));
        return tools;
    }
}
