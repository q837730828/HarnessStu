package com.ahi.harness.tools;

import java.io.File;
import java.io.IOException;

final class WorkspacePaths {
    private WorkspacePaths() {
    }

    static File resolveInside(File workspace, String path) throws IOException {
        if (path == null || path.trim().isEmpty()) {
            throw new IllegalArgumentException("path is required");
        }
        File target = new File(path);
        if (!target.isAbsolute()) {
            target = new File(workspace, path);
        }
        File canonicalWorkspace = workspace.getCanonicalFile();
        File canonicalTarget = target.getCanonicalFile();
        String root = canonicalWorkspace.getPath();
        String candidate = canonicalTarget.getPath();
        if (!candidate.equals(root) && !candidate.startsWith(root + File.separator)) {
            throw new IllegalArgumentException("path escapes workspace: " + path);
        }
        return canonicalTarget;
    }
}
