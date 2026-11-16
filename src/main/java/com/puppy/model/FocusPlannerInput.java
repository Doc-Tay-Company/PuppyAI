package com.puppy.model;

import java.util.List;

/** Data the planner needs: fixed class times and the work that must be done. */
public record FocusPlannerInput(List<ClassSlot> classes, List<StudyTask> tasks) {

    public FocusPlannerInput {
        classes = classes == null ? List.of() : List.copyOf(classes);
        tasks = tasks == null ? List.of() : List.copyOf(tasks);
    }

    public boolean isEmpty() {
        return classes.isEmpty() && tasks.isEmpty();
    }

    /** All subjects mentioned by classes or tasks, in first-seen order. */
    public List<String> subjects() {
        return java.util.stream.Stream.concat(
                        classes.stream().map(ClassSlot::subject),
                        tasks.stream().map(StudyTask::subject))
                .distinct()
                .toList();
    }
}
