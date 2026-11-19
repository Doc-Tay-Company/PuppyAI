package com.puppy.model;

/** A small immutable transcript entry used only by task refinement. */
public record TaskRefinementTurn(Role role, String text) {
    public enum Role { USER, ASSISTANT }

    public TaskRefinementTurn {
        if (role == null) {
            throw new IllegalArgumentException("A turn needs a role");
        }
        text = text == null ? "" : text.strip();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("A turn needs text");
        }
    }
}
