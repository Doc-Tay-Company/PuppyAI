package com.puppy.enums;

/**
 * Lifecycle of a recorded study session.
 *
 * <p>{@code RUNNING} and {@code PAUSED} are live states; {@code COMPLETED} means the
 * countdown reached zero; {@code ABANDONED} means the user reset the timer or quit
 * the application before it finished.</p>
 */
public enum SessionStatus {
    RUNNING,
    PAUSED,
    COMPLETED,
    ABANDONED;

    /** True while the session has not reached a final state. */
    public boolean isLive() {
        return this == RUNNING || this == PAUSED;
    }
}
