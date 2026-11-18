package com.puppy.model;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record StoredMessage(String id, String role, String text, List<String> attachments,
                            long elapsedMillis, Status status, String entryType) {
    public enum Status { COMPLETE, GENERATING, INTERRUPTED, ERROR }

    public StoredMessage {
        Objects.requireNonNull(id);
        if (!"user".equals(role) && !"assistant".equals(role)) {
            throw new IllegalArgumentException("Invalid message role");
        }
        Objects.requireNonNull(text);
        attachments = List.copyOf(attachments);
        Objects.requireNonNull(status);
        if (elapsedMillis < 0 || !("TEXT".equals(entryType) || "QUIZ".equals(entryType))) {
            throw new IllegalArgumentException("Invalid message metadata");
        }
    }

    public StoredMessage(String role, String text, List<String> attachments, long elapsedMillis) {
        this(UUID.randomUUID().toString(), role, text, attachments, elapsedMillis, Status.COMPLETE, "TEXT");
    }
}
