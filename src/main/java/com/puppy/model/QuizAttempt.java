package com.puppy.model;

import java.util.Arrays;
import java.util.Objects;

/** Ephemeral learner state. Only the immutable definition may be persisted. */
public final class QuizAttempt {
    private final QuizDefinition definition;
    private final int[] selections;
    private final boolean[] checked;
    private int index;
    private boolean finished;

    public QuizAttempt(QuizDefinition definition) {
        this.definition = Objects.requireNonNull(definition, "definition");
        selections = new int[definition.questions().size()];
        checked = new boolean[selections.length];
        reset();
    }

    public QuizDefinition definition() { return definition; }
    public int index() { return index; }
    public int selection(int questionIndex) { return selections[questionIndex]; }
    public boolean checked(int questionIndex) { return checked[questionIndex]; }
    public boolean finished() { return finished; }

    public void select(int optionIndex) {
        requireEditable();
        if (optionIndex < 0 || optionIndex >= 4) {
            throw new IllegalArgumentException("Choose one of the four options.");
        }
        selections[index] = optionIndex;
    }

    public void check() {
        requireEditable();
        if (selections[index] < 0) {
            throw new IllegalStateException("Choose an answer before checking it.");
        }
        checked[index] = true;
    }

    public void previous() { if (index > 0) index--; }
    public void next() { if (index < selections.length - 1) index++; }

    public void finish(boolean confirmSkipped) {
        if (!finished && skippedCount() > 0 && !confirmSkipped) {
            throw new IllegalStateException("Confirm skipping " + skippedCount() + " unanswered questions.");
        }
        finished = true;
    }

    public int correctCount() {
        int count = 0;
        for (int i = 0; i < selections.length; i++) {
            if (checked[i] && selections[i] == definition.questions().get(i).correctIndex()) count++;
        }
        return count;
    }

    public int answeredCount() {
        int count = 0;
        for (boolean answerChecked : checked) if (answerChecked) count++;
        return count;
    }

    public int incorrectCount() { return answeredCount() - correctCount(); }
    public int skippedCount() { return selections.length - answeredCount(); }
    public boolean hasSelections() { return Arrays.stream(selections).anyMatch(value -> value >= 0); }

    public void reset() {
        Arrays.fill(selections, -1);
        Arrays.fill(checked, false);
        index = 0;
        finished = false;
    }

    private void requireEditable() {
        if (finished || checked[index]) {
            throw new IllegalStateException("This answer is locked. Retry the quiz to change it.");
        }
    }
}
