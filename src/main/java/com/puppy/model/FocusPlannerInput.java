package com.puppy.model;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Data the planner needs: fixed class times and the work that must be done.
 *
 * <p>{@code moreTimeSubjects} lists subjects the user explicitly wants to spend
 * more time on. It is an adjustment, not extra work: the service lengthens the
 * subject's tasks instead of inventing a task for the emphasis.</p>
 */
public record FocusPlannerInput(
        List<ClassSlot> classes,
        List<StudyTask> tasks,
        List<String> moreTimeSubjects
) {

    public FocusPlannerInput {
        classes = classes == null ? List.of() : List.copyOf(classes);
        tasks = tasks == null ? List.of() : List.copyOf(tasks);
        moreTimeSubjects = cleanSubjects(moreTimeSubjects);
    }

    /** Keeps the original two-field call sites readable. */
    public FocusPlannerInput(List<ClassSlot> classes, List<StudyTask> tasks) {
        this(classes, tasks, List.of());
    }

    public boolean isEmpty() {
        return classes.isEmpty() && tasks.isEmpty();
    }

    /** All subjects mentioned by classes, tasks or extra-time requests, in first-seen order. */
    public List<String> subjects() {
        return java.util.stream.Stream.concat(
                        java.util.stream.Stream.concat(
                                classes.stream().map(ClassSlot::subject),
                                tasks.stream().map(StudyTask::subject)),
                        moreTimeSubjects.stream())
                .distinct()
                .toList();
    }

    private static List<String> cleanSubjects(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                unique.add(value.strip());
            }
        }
        return List.copyOf(unique);
    }
}
