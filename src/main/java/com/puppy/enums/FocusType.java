package com.puppy.enums;

/**
 * The two kinds of planned focus work.
 *
 * <p>Quick focus is for reviewing lectures and reading material; deep focus is
 * for programming, assignments and research. The default block length is used
 * when a task does not fit in one sitting and has to be split.</p>
 */
public enum FocusType {
    QUICK("Quick focus", 25, "Review lectures and read material"),
    DEEP("Deep focus", 50, "Programming, assignments and research");

    private final String label;
    private final int defaultBlockMinutes;
    private final String description;

    FocusType(String label, int defaultBlockMinutes, String description) {
        this.label = label;
        this.defaultBlockMinutes = defaultBlockMinutes;
        this.description = description;
    }

    public String label() {
        return label;
    }

    public int defaultBlockMinutes() {
        return defaultBlockMinutes;
    }

    public String description() {
        return description;
    }

    @Override
    public String toString() {
        return label;
    }
}
