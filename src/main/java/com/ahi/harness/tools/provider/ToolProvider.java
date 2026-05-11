package com.ahi.harness.tools.provider;

import com.ahi.harness.tools.Tool;

import java.util.List;

public interface ToolProvider {
    List<Tool> loadTools() throws Exception;
}
