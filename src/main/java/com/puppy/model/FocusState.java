package com.puppy.model;

import java.util.List;
import java.util.Objects;

/** Immutable durable planner snapshot; countdowns deliberately remain transient. */
public record FocusState(List<ClassEntry> classes, List<TaskEntry> tasks,
                         FocusPreferences preferences, FocusPlan plan) {
    public FocusState {
        classes = List.copyOf(classes);
        tasks = List.copyOf(tasks);
        Objects.requireNonNull(preferences, "preferences");
        Objects.requireNonNull(plan, "plan");
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
