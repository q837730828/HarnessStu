package com.ahi.harness.protocol;

import java.util.Collections;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Long-lived context boundary shared by multiple Runs.
 *
 * Conversation remains the provider-facing message window. AgentThread is the
 * protocol resource that gives that window a stable identity across user turns.
 */
public class AgentThread {
    private final String id;
    private final String title;
    private final String createdAt;
    private String updatedAt;
    private final Map<String, String> metadata;
    private final List<String> participants;
    private final List<String> capabilities;

    public AgentThread(String id,
                       String title,
                       String createdAt,
                       String updatedAt,
                       Map<String, String> metadata) {
        this(id, title, createdAt, updatedAt, metadata,
                Collections.<String>emptyList(), Collections.<String>emptyList());
    }

    public AgentThread(String id,
                       String title,
                       String createdAt,
                       String updatedAt,
                       Map<String, String> metadata,
                       List<String> participants,
                       List<String> capabilities) {
        this.id = id;
        this.title = title;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.metadata = metadata == null
                ? new LinkedHashMap<String, String>()
                : new LinkedHashMap<String, String>(metadata);
        this.participants = participants == null
                ? new ArrayList<String>() : new ArrayList<String>(participants);
        this.capabilities = capabilities == null
                ? new ArrayList<String>() : new ArrayList<String>(capabilities);
    }

    public static AgentThread create(String title) {
        String now = ProtocolIds.now();
        return new AgentThread(
                ProtocolIds.next("thread"),
                title,
                now,
                now,
                Collections.<String, String>emptyMap()
        );
    }

    public void touch() {
        updatedAt = ProtocolIds.now();
    }

    public void addParticipant(String participantId) {
        if (participantId != null && !participantId.trim().isEmpty() && !participants.contains(participantId)) {
            participants.add(participantId);
            touch();
        }
    }

    public void addCapability(String capability) {
        if (capability != null && !capability.trim().isEmpty() && !capabilities.contains(capability)) {
            capabilities.add(capability);
            touch();
        }
    }

    public String getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public String getUpdatedAt() {
        return updatedAt;
    }

    public Map<String, String> getMetadata() {
        return Collections.unmodifiableMap(metadata);
    }

    public List<String> getParticipants() {
        return Collections.unmodifiableList(participants);
    }

    public List<String> getCapabilities() {
        return Collections.unmodifiableList(capabilities);
    }

    public int getSchemaVersion() {
        return 1;
    }
}
