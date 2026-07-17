package com.ahi.harness.session;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;

/**
 * Keeps large tool observations out of active model context while preserving
 * their full text on disk.
 */
public class ObservationArchiveStore {
    private static final int DEFAULT_MAX_ACTIVE_CHARS = 12000;

    private final File workspace;
    private final int maxActiveChars;

    public ObservationArchiveStore(File workspace) {
        this(workspace, DEFAULT_MAX_ACTIVE_CHARS);
    }

    public ObservationArchiveStore(File workspace, int maxActiveChars) {
        this.workspace = workspace;
        this.maxActiveChars = Math.max(1000, maxActiveChars);
    }

    public String archiveIfLarge(String toolName, String observation) throws Exception {
        return archive(toolName, observation).visibleContent();
    }

    /**
     * Returns both the model-visible preview and structured archive metadata.
     *
     * AgentLoop uses the metadata to register an Artifact. The old
     * archiveIfLarge method remains as a compatibility convenience for callers
     * that only need the preview string.
     */
    public ArchiveResult archive(String toolName, String observation) throws Exception {
        if (observation == null || observation.length() <= maxActiveChars) {
            String visible = observation == null ? "" : observation;
            return new ArchiveResult(visible, false, null, visible.length());
        }
        File directory = new File(workspace, ".harness/observations");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException("Cannot create observation archive directory: " + directory.getAbsolutePath());
        }
        String safeTool = safeName(toolName);
        String name = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
                .replace(':', '-')
                .replace('.', '-')
                + "-" + safeTool + ".txt";
        File file = new File(directory, name);
        Writer writer = new OutputStreamWriter(new FileOutputStream(file, false), StandardCharsets.UTF_8);
        try {
            writer.write(observation);
        } finally {
            writer.close();
        }
        String preview = observation.substring(0, maxActiveChars);
        // The returned text is what the model sees: a bounded preview plus a
        // stable recovery path.
        String visible = preview
                + "\n...<observation archived " + (observation.length() - maxActiveChars) + " chars>"
                + "\nArchive path: " + relative(file)
                + "\nUse read_file or grep on the archive path if exact details are needed.";
        return new ArchiveResult(visible, true, relative(file), observation.length());
    }

    private String safeName(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "tool";
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if ((ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9') || ch == '-' || ch == '_') {
                out.append(ch);
            } else {
                out.append('-');
            }
        }
        return out.toString();
    }

    private String relative(File file) {
        String root = workspace.getAbsolutePath();
        String path = file.getAbsolutePath();
        if (path.startsWith(root + File.separator)) {
            return path.substring(root.length() + 1).replace(File.separatorChar, '/');
        }
        return path;
    }

    public static class ArchiveResult {
        private final String visibleContent;
        private final boolean archived;
        private final String archivePath;
        private final int originalChars;

        public ArchiveResult(String visibleContent, boolean archived, String archivePath, int originalChars) {
            this.visibleContent = visibleContent;
            this.archived = archived;
            this.archivePath = archivePath;
            this.originalChars = originalChars;
        }

        public String visibleContent() {
            return visibleContent;
        }

        public boolean archived() {
            return archived;
        }

        public String archivePath() {
            return archivePath;
        }

        public int originalChars() {
            return originalChars;
        }
    }
}
