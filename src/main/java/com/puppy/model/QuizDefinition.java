package com.puppy.model;

import java.util.List;
import java.util.Objects;

/** Reusable learning content only; answers given by the learner are never stored. */
public record QuizDefinition(String id, String messageId, String title, List<Question> questions) {
    public enum Difficulty { EASY, MEDIUM, HARD }

    public QuizDefinition {
        requireText(id);
        requireText(messageId);
        requireText(title);
        questions = List.copyOf(questions);
        if (questions.isEmpty() || questions.size() > 20
                || questions.stream().map(Question::id).distinct().count() != questions.size()) {
            throw new IllegalArgumentException("A quiz needs 1-20 uniquely identified questions.");
        }
    }

    public record Question(String id, String text, List<String> options, int correctIndex,
                           String explanation, Difficulty difficulty) {
        public Question {
            requireText(id);
            requireText(text);
            requireText(explanation);
            Objects.requireNonNull(difficulty);
            options = List.copyOf(options);
            options.forEach(QuizDefinition::requireText);
            if (options.size() != 4 || options.stream().map(String::strip).map(String::toLowerCase)
                    .distinct().count() != 4 || correctIndex < 0 || correctIndex >= 4) {
                throw new IllegalArgumentException("A question needs four distinct options and one correct index.");
            }
        }
    }

    private static void requireText(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Quiz text and identifiers must not be blank.");
        }
    }
}
