package com.puppy.enums;

/**
 * Legacy kinds of planned focus work.
 *
 * <p>Focus-type classification is temporarily hidden from the Study UI:
 * there are no type selectors or badges, and new work uses {@link #QUICK} as a
 * neutral stored value. The enum and the persisted field remain so plans saved
 * by older versions still load without losing data.</p>
 */
public enum FocusType {
    QUICK("Quick focus", 25, "Review lectures and read material"),
    DEEP("Deep focus", 50,
            "Important or difficult work, low confidence, programming, assignments and research"),
    EXAM("Exam preparation", 45,
            "Timed practice, retrieval, mock questions and targeted exam review");

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
