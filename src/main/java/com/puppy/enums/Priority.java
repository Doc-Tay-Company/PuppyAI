package com.puppy.enums;

/** Priority attached to a study task and carried into every planned session. */
public enum Priority {
    LOW("Low", 1),
    MEDIUM("Medium", 2),
    HIGH("High", 3);

    private final String label;
    private final int rank;

    Priority(String label, int rank) {
        this.label = label;
        this.rank = rank;
    }

    public String label() {
        return label;
    }

    /** Higher means more urgent; used by the planner's task ordering. */
    public int rank() {
        return rank;
    }

    @Override
    public String toString() {
        return label;
    }
}
