package com.puppy.database;

/** An actionable local-storage failure; never includes stored content. */
public final class PersistenceException extends RuntimeException {
    public PersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
