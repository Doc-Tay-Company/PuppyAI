package com.puppy.enums;

/**
 * Who produced a chat message.
 *
 * <p>Stored in the database as the enum name, so the constants must not be
 * renamed without a schema migration.</p>
 */
public enum MessageRole {
    USER,
    ASSISTANT
}
