package com.ahi.harness.tools;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertTrue;

public class ContextToolTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    public void docReadOnlyReadsProjectDocs() throws Exception {
        File workspace = temp.newFolder("workspace");
        File docs = new File(workspace, "docs");
        assertTrue(docs.mkdirs());
        Files.write(new File(docs, "ARCHITECTURE.md").toPath(), "architecture".getBytes(StandardCharsets.UTF_8));

        DocReadTool tool = new DocReadTool(workspace);
        ObjectNode args = mapper.createObjectNode();
        args.put("path", "docs/ARCHITECTURE.md");

        assertTrue(tool.execute(args).toObservation().contains("architecture"));
    }

    @Test
    public void skillLoadReadsSkillMarkdownOnDemand() throws Exception {
        File workspace = temp.newFolder("workspace2");
        File skillDir = new File(workspace, "skills/deploy");
        assertTrue(skillDir.mkdirs());
        Files.write(new File(skillDir, "SKILL.md").toPath(), "deploy steps".getBytes(StandardCharsets.UTF_8));

        SkillLoadTool tool = new SkillLoadTool(workspace);
        ObjectNode args = mapper.createObjectNode();
        args.put("name", "deploy");

        assertTrue(tool.execute(args).toObservation().contains("deploy steps"));
    }

    @Test
    public void readFileAllowsProtocolRecordsButKeepsOtherHarnessStatePrivate() throws Exception {
        File workspace = temp.newFolder("workspace3");
        File runtime = new File(workspace, ".harness/runtime/runs/run-1");
        assertTrue(runtime.mkdirs());
        Files.write(new File(runtime, "run.json").toPath(), "{\"status\":\"COMPLETED\"}".getBytes(StandardCharsets.UTF_8));
        File privateDir = new File(workspace, ".harness/sessions");
        assertTrue(privateDir.mkdirs());
        Files.write(new File(privateDir, "private.jsonl").toPath(), "secret".getBytes(StandardCharsets.UTF_8));

        ReadFileTool tool = new ReadFileTool(workspace);
        ObjectNode runtimeArgs = mapper.createObjectNode();
        runtimeArgs.put("path", ".harness/runtime/runs/run-1/run.json");
        ObjectNode privateArgs = mapper.createObjectNode();
        privateArgs.put("path", ".harness/sessions/private.jsonl");

        assertTrue(tool.execute(runtimeArgs).toObservation().contains("COMPLETED"));
        assertTrue(tool.execute(privateArgs).toObservation().contains("blocked"));

        ObjectNode listArgs = mapper.createObjectNode();
        listArgs.put("path", ".");
        String listed = new ListFilesTool(workspace).execute(listArgs).toObservation();
        assertTrue(listed.contains(".harness/runtime/runs/run-1/run.json"));
        assertTrue(!listed.contains("private.jsonl"));

        ObjectNode grepArgs = mapper.createObjectNode();
        grepArgs.put("path", ".");
        grepArgs.put("pattern", "COMPLETED");
        assertTrue(new GrepTool(workspace).execute(grepArgs).toObservation().contains("run.json"));
    }
}
