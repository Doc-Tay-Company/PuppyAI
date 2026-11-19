package com.puppy.model;

import com.puppy.enums.Priority;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** A user-owned to-do item, independent from Focus scheduling. */
public record TodoTask(
        String id,
        String title,
        String description,
        String category,
        Priority priority,
        String completionCriteria,
        List<String> checklist,
        List<String> tags,
        boolean completed
) {
    public TodoTask {
        id = clean(id);
        title = clean(title);
        description = clean(description);
        category = clean(category);
        completionCriteria = clean(completionCriteria);
        checklist = cleanList(checklist);
        tags = cleanList(tags);
        Objects.requireNonNull(priority, "priority");
        if (id.isEmpty()) {
            throw new IllegalArgumentException("A to-do needs an id");
        }
        if (title.isEmpty()) {
            throw new IllegalArgumentException("A to-do needs a title");
        }
    }

    public static TodoTask draft(String title) {
        return new TodoTask(UUID.randomUUID().toString(), title, "", "General",
                Priority.MEDIUM, "", List.of(), List.of(), false);
    }

    public TodoTask withCompleted(boolean value) {
        return new TodoTask(id, title, description, category, priority,
                completionCriteria, checklist, tags, value);
    }

    private static String clean(String value) {
        return value == null ? "" : value.strip();
    }

    private static List<String> cleanList(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(Objects::nonNull)
                .map(String::strip)
                .filter(value -> !value.isEmpty())
                .distinct()
                .toList();
    }
}
