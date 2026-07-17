package com.ahi.harness.permission;

import com.ahi.harness.core.ToolCall;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PermissionPolicyTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    public void allowsReadOnlyContextTools() {
        PermissionPolicy policy = new PermissionPolicy();

        assertTrue(policy.check(call("doc_read", mapper.createObjectNode())).allowed());
        assertTrue(policy.check(call("skill_load", mapper.createObjectNode())).allowed());
    }

    @Test
    public void blocksShellControlOperatorsEvenWhenPrefixIsAllowed() {
        PermissionPolicy policy = new PermissionPolicy(
                Arrays.asList("mvn -q test"),
                Collections.<String>emptyList(),
                Collections.<String>emptyList(),
                "danger-full-access"
        );
        ObjectNode args = mapper.createObjectNode();
        args.put("command", "mvn -q test; git status");

        assertFalse(policy.check(call("bash", args)).allowed());
    }

    @Test
    public void blocksPrivateHarnessEditsExceptSettings() {
        PermissionPolicy policy = new PermissionPolicy();
        ObjectNode denied = mapper.createObjectNode();
        denied.put("path", ".harness/sessions/session.jsonl");
        denied.put("old_text", "a");
        denied.put("new_text", "b");

        ObjectNode allowed = mapper.createObjectNode();
        allowed.put("path", ".harness/settings.json");
        allowed.put("old_text", "a");
        allowed.put("new_text", "b");

        assertFalse(policy.check(call("edit_file", denied)).allowed());
        assertTrue(policy.check(call("edit_file", allowed)).allowed());
    }

    private ToolCall call(String name, ObjectNode args) {
        return new ToolCall("id", name, args, args.toString());
    }
}
