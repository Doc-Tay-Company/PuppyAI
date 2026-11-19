package com.puppy.model;

import java.util.List;

/** One structured AI turn in the isolated task-refinement conversation. */
public record TaskRefinementReply(
        Kind kind,
        String message,
        TodoTask proposal,
        List<String> issues
) {
    public enum Kind { CLARIFY, PROPOSAL }

    public TaskRefinementReply {
        message = message == null ? "" : message.strip();
        issues = issues == null ? List.of() : issues.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::strip)
                .toList();
        if (kind == null) {
            throw new IllegalArgumentException("A refinement reply needs a kind");
        }
        if (message.isEmpty()) {
            throw new IllegalArgumentException("A refinement reply needs a message");
        }
        if (kind == Kind.PROPOSAL && proposal == null) {
            throw new IllegalArgumentException("A proposal reply needs a task");
        }
    }
}
