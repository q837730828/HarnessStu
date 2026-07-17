package com.ahi.harness.protocol;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Small protocol-wide helpers.
 *
 * IDs carry a readable prefix so a JSONL log can be inspected without first
 * looking up the object type. Timestamps are stored as ISO-8601 strings because
 * the project deliberately avoids adding a Jackson Java-Time dependency.
 */
public final class ProtocolIds {
    private ProtocolIds() {
    }

    public static String next(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString();
    }

    public static String now() {
        return DateTimeFormatter.ISO_INSTANT.format(Instant.now());
    }

    public static String afterSeconds(int seconds) {
        return DateTimeFormatter.ISO_INSTANT.format(Instant.now().plusSeconds(seconds));
    }

    public static boolean isPast(String timestamp) {
        return timestamp != null && Instant.now().isAfter(Instant.parse(timestamp));
    }
}
