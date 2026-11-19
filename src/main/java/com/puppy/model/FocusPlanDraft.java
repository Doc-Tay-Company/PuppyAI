package com.puppy.model;

import java.util.Objects;

/**
 * Validated but uncommitted Focus state shown in Chat before the user accepts it.
 * The base snapshot prevents a stale preview from overwriting newer changes.
 */
public record FocusPlanDraft(FocusState base, FocusState proposed, String summary) {
    public FocusPlanDraft {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(proposed, "proposed");
        summary = summary == null ? "" : summary.strip();
    }

    public FocusPlan plan() {
        return proposed.plan();
    }
}
