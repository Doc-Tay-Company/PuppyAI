package com.puppy.model;

import java.time.Instant;
import java.util.Objects;

/**
 * A chat conversation, as listed in the history pane.
 *
 * <p>{@code messageCount} is derived from the messages table, so the history list
 * can show a preview without loading every message.</p>
 */
public record Conversation(
        String id,
        String title,
        Instant createdAt,
        Instant updatedAt,
        int messageCount
) {

    public Conversation {
        id = Validate.text(id, "id");
        title = Validate.text(title, "title");
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        if (messageCount < 0) {
            throw new IllegalArgumentException("messageCount must not be negative");
        }
    }
}
