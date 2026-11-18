package com.puppy.model;

import java.util.List;
import java.util.Objects;

/**
 * Immutable durable planner snapshot; countdowns deliberately remain transient.
 *
 * @param preferencesConfigured true once the user has explicitly chosen a
 *                              free-time window. Chat uses this to reuse the
 *                              saved window instead of asking again.
 * @param planEdited            true when sessions were changed by hand or by
 *                              the plan editor. Generate/Import warn before
 *                              replacing those changes.
 */
public record FocusState(List<ClassEntry> classes, List<TaskEntry> tasks,
                         FocusPreferences preferences, FocusPlan plan,
                         boolean preferencesConfigured, boolean planEdited) {
    public FocusState {
        classes = List.copyOf(classes);
        tasks = List.copyOf(tasks);
        Objects.requireNonNull(preferences, "preferences");
        Objects.requireNonNull(plan, "plan");
    }

    /** Compatibility constructor for states written before the flags existed. */
    public FocusState(List<ClassEntry> classes, List<TaskEntry> tasks,
                      FocusPreferences preferences, FocusPlan plan) {
        this(classes, tasks, preferences, plan, false, false);
    }

    public FocusState withPreferencesConfigured(boolean value) {
        return new FocusState(classes, tasks, preferences, plan, value, planEdited);
    }

    public FocusState withPlanEdited(boolean value) {
        return new FocusState(classes, tasks, preferences, plan, preferencesConfigured, value);
    }

    public record ClassEntry(String id, ClassSlot value) {
        public ClassEntry {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(value, "value");
        }
    }

    public record TaskEntry(String id, StudyTask value) {
        public TaskEntry {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(value, "value");
        }
    }
}
