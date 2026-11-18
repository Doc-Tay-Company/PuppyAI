package com.puppy.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class QuizAttemptTest {
    private static QuizAttempt attempt() {
        return new QuizAttempt(new QuizDefinition("quiz", "message", "Science", List.of(
                question("one", 0), question("two", 1), question("three", 2))));
    }

    private static QuizDefinition.Question question(String id, int answer) {
        return new QuizDefinition.Question(id, id, List.of("A", "B", "C", "D"),
                answer, "Explanation", QuizDefinition.Difficulty.MEDIUM);
    }

    @Test
    void navigationNeverSubmitsAndStopsAtBounds() {
        QuizAttempt attempt = attempt();
        attempt.previous();
        assertEquals(0, attempt.index());
        assertEquals(-1, attempt.selection(0));
        assertThrows(IllegalStateException.class, attempt::check);
        assertThrows(IllegalArgumentException.class, () -> attempt.select(-1));
        assertThrows(IllegalArgumentException.class, () -> attempt.select(4));
        attempt.select(0);
        attempt.next();
        attempt.next();
        attempt.next();
        assertEquals(2, attempt.index());
        assertEquals(0, attempt.answeredCount());
        assertEquals(0, attempt.correctCount());
        assertEquals(3, attempt.skippedCount());
        attempt.previous();
        attempt.previous();
        assertEquals(0, attempt.selection(0));
        assertFalse(attempt.checked(0));
    }

    @Test
    void checkingLocksAnswersAndScoresOnlyCheckedQuestions() {
        QuizAttempt attempt = attempt();
        attempt.select(0);
        attempt.check();
        assertTrue(attempt.checked(0));
        assertThrows(IllegalStateException.class, attempt::check);
        assertThrows(IllegalStateException.class, () -> attempt.select(1));
        attempt.next();
        attempt.select(3);
        attempt.check();
        attempt.next();
        attempt.select(2);
        assertEquals(1, attempt.correctCount());
        assertEquals(1, attempt.incorrectCount());
        assertEquals(2, attempt.answeredCount());
        assertEquals(1, attempt.skippedCount());
        assertThrows(IllegalStateException.class, () -> attempt.finish(false));
        assertFalse(attempt.finished());
        attempt.finish(true);
        assertTrue(attempt.finished());
        assertEquals(3, attempt.correctCount() + attempt.incorrectCount() + attempt.skippedCount());
        assertThrows(IllegalStateException.class, attempt::check);
        assertThrows(IllegalStateException.class, () -> attempt.select(0));
        attempt.previous();
        assertEquals(3, attempt.selection(1));
        assertEquals(1, attempt.index());
        attempt.finish(false);
        assertEquals(1, attempt.correctCount());
    }

    @Test
    void fullyCheckedAttemptFinishesWithoutConfirmationAndResetKeepsDefinition() {
        QuizAttempt attempt = attempt();
        QuizDefinition definition = attempt.definition();
        assertFalse(attempt.hasSelections());
        for (int i = 0; i < 3; i++) {
            attempt.select(i);
            attempt.check();
            attempt.next();
        }
        attempt.finish(false);
        assertEquals(3, attempt.correctCount());
        assertEquals(0, attempt.skippedCount());
        assertTrue(attempt.hasSelections());
        attempt.reset();
        assertSame(definition, attempt.definition());
        assertFalse(attempt.finished());
        assertFalse(attempt.hasSelections());
        assertEquals(0, attempt.index());
        assertEquals(0, attempt.answeredCount());
        assertEquals(0, attempt.correctCount());
        assertEquals(0, attempt.incorrectCount());
        assertEquals(3, attempt.skippedCount());
        for (int i = 0; i < 3; i++) {
            assertEquals(-1, attempt.selection(i));
            assertFalse(attempt.checked(i));
        }
        attempt.select(2);
        attempt.check();
        assertEquals(1, attempt.incorrectCount());
        assertEquals(-1, new QuizAttempt(definition).selection(0));
    }

    @Test
    void entirelySkippedAttemptNeedsConfirmation() {
        QuizAttempt attempt = attempt();
        assertThrows(IllegalStateException.class, () -> attempt.finish(false));
        attempt.finish(true);
        assertEquals(0, attempt.answeredCount());
        assertEquals(3, attempt.skippedCount());
        assertThrows(IllegalStateException.class, () -> attempt.select(0));
    }
}
