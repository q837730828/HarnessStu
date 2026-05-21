package com.ahi.harness.model;

import com.ahi.harness.core.Conversation;
import com.ahi.harness.core.ModelResponse;
import com.ahi.harness.tools.ToolRegistry;

/**
 * Boundary between the harness loop and a concrete model provider.
 */
public interface ModelClient {
    ModelResponse generate(Conversation conversation, ToolRegistry tools) throws Exception;
}
