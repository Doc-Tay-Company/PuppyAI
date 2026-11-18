package com.puppy.model;

import java.util.List;
import java.util.Objects;

/**
 * The parsed outcome of an AI plan-edit request: the actions to apply and a
 * human-readable summary the AI wrote for the user.
 */
public record FocusEditResult(
        List<FocusEditAction> actions,
        String summary
) {

    public FocusEditResult {
        actions = actions == null ? List.of() : List.copyOf(actions);
        summary = summary == null ? "" : summary.strip();
    }

    public boolean isEmpty() {
        return actions.isEmpty();
    }
}
