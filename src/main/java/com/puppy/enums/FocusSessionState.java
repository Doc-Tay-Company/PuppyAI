package com.puppy.enums;

/** Durable lifecycle of a planned Focus session. */
public enum FocusSessionState {
    PENDING("Pending"),
    ACTIVE("Active"),
    PAUSED("Paused"),
    COMPLETED("Completed"),
    SKIPPED("Skipped");

    private final String label;

    FocusSessionState(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public boolean finished() {
        return this == COMPLETED || this == SKIPPED;
    }
}
