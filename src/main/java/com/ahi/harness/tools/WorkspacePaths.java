package com.ahi.harness.tools;

import java.io.File;
import java.io.IOException;

/**
 * Shared path guard for workspace tools.
 *
 * Always resolve through canonical paths before reading or writing so relative
 * paths and symlinks cannot escape the configured workspace.
 */
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

    static boolean isCompactionArchivePath(File workspace, File file) {
        return isHarnessArchivePath(workspace, file, "compactions");
    }

    static boolean isObservationArchivePath(File workspace, File file) {
        return isHarnessArchivePath(workspace, file, "observations");
    }

    static boolean isRuntimeProtocolPath(File workspace, File file) {
        return isHarnessArchivePath(workspace, file, "runtime");
    }

    static boolean isReadableHarnessArchivePath(File workspace, File file) {
        // Most .harness files are private runtime state. Recovery archives and
        // explicit protocol records are readable because prompts/docs point the
        // learner back to them for inspection and checkpoint recovery.
        return isCompactionArchivePath(workspace, file)
                || isObservationArchivePath(workspace, file)
                || isRuntimeProtocolPath(workspace, file);
    }

    private static boolean isHarnessArchivePath(File workspace, File file, String directoryName) {
        try {
            File root = new File(new File(workspace, ".harness"), directoryName).getCanonicalFile();
            File target = file.getCanonicalFile();
            String rootPath = root.getPath();
            String targetPath = target.getPath();
            return targetPath.equals(rootPath) || targetPath.startsWith(rootPath + File.separator);
        } catch (Exception ignored) {
            return false;
        }
    }
}
