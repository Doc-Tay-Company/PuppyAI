package com.puppy.model;

import java.util.List;
import java.util.Objects;

/**
 * Everything the AI plan editor needs to understand the current plan before
 * changing it: the numbered sessions, the weekly classes that must not be
 * overlapped, and the free-time window the plan lives in.
 *
 * <p>The context is a read-only snapshot. The editor returns
 * {@link FocusEditAction}s and {@link com.puppy.service.FocusService} applies
 * and validates them atomically.</p>
 */
public record FocusContext(
        FocusPlan plan,
        List<ClassSlot> classes,
        FocusPreferences preferences,
        boolean preferencesConfigured
) {

    public FocusContext {
        Objects.requireNonNull(plan, "plan");
        classes = classes == null ? List.of() : List.copyOf(classes);
        Objects.requireNonNull(preferences, "preferences");
    }

    public FocusContext(FocusPlan plan, List<ClassSlot> classes,
                        FocusPreferences preferences) {
        this(plan, classes, preferences, true);
    }

    public List<FocusSession> sessions() {
        return plan.sessions();
    }
}
