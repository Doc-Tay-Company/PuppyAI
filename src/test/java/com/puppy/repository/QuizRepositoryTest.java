package com.puppy.repository;

import com.puppy.database.Database;
import com.puppy.model.QuizDefinition;
import com.puppy.model.StoredConversation;
import com.puppy.model.StoredMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class QuizRepositoryTest {
    @TempDir Path directory;

    @Test
    void definitionsRoundTripWithoutAttemptsAndCascadeWithConversation() {
        var quiz = new QuizDefinition("quiz", "entry", "A quiz", List.of(new QuizDefinition.Question(
                "question", "Which?", List.of("A", "B", "C", "D"), 2, "C is correct.", QuizDefinition.Difficulty.HARD)));
        try (Database database = Database.open(directory)) {
            var chat = new ChatRepository(database);
            var entry = new StoredMessage("entry", "assistant", "", List.of(), 0, StoredMessage.Status.COMPLETE, "QUIZ");
            var conversation = new StoredConversation("chat", "title", 1, "COMPLETE", List.of(entry), List.of());
            chat.save(conversation);
            new QuizRepository(database).save(quiz);
            chat.save(conversation);
        }
        try (Database database = Database.open(directory)) {
            var repository = new QuizRepository(database);
            assertEquals(quiz, repository.findByMessage("entry").orElseThrow());
            database.transaction(connection -> {
                try (var sql = connection.createStatement()) { sql.executeUpdate("DELETE FROM conversations WHERE id='chat'"); }
                return null;
            });
            assertTrue(repository.findByMessage("entry").isEmpty());
            assertEquals(0, database.call(connection -> {
                try (var sql = connection.createStatement(); var rows = sql.executeQuery(
                        "SELECT count(*) FROM sqlite_master WHERE type='table' AND (name LIKE '%attempt%' OR name LIKE '%score%' OR name LIKE '%progress%')")) {
                    return rows.getInt(1);
                }
            }).intValue());
        }
    }

    @Test
    void historyCommitsQuizAndTypedEntryAtomicallyAndRetriesWithoutAttemptData() {
        var definition = new QuizDefinition("quiz", "entry", "Stored learning quiz", List.of(
                new QuizDefinition.Question("question", "Which option?", List.of("A", "B", "C", "D"),
                        1, "Option B is correct.", QuizDefinition.Difficulty.MEDIUM)));
        var placeholder = new StoredMessage("entry", "assistant", "Generating quiz...", List.of(),
                0, StoredMessage.Status.GENERATING, "TEXT");
        var typed = new StoredMessage("entry", "assistant", definition.title(), List.of(),
                0, StoredMessage.Status.COMPLETE, "QUIZ");
        try (Database database = Database.open(directory)) {
            var repository = new ChatRepository(database);
            repository.save(new StoredConversation("chat", "title", 1, "COMPLETE", List.of(placeholder), List.of()));
            try (var history = new com.puppy.service.ChatHistoryService(repository, new SettingsRepository(database))) {
                history.registerQuiz(definition);
                database.call(connection -> {
                    try (var sql = connection.createStatement()) {
                        sql.execute("CREATE TRIGGER reject_question BEFORE INSERT ON quiz_questions BEGIN SELECT RAISE(ABORT,'injected'); END");
                    }
                    return null;
                });
                assertThrows(java.util.concurrent.CompletionException.class, () -> history.save(
                        new StoredConversation("chat", "title", 2, "COMPLETE", List.of(typed), List.of())).join());
                assertEquals("TEXT", repository.load().getFirst().messages().getFirst().entryType());
                assertTrue(new QuizRepository(database).findByMessage("entry").isEmpty());
                assertEquals(definition, history.quiz("entry").orElseThrow(), "Failed saving must retain the generated content");
                database.call(connection -> {
                    try (var sql = connection.createStatement()) { sql.execute("DROP TRIGGER reject_question"); }
                    return null;
                });
                history.retry().join();
                var attempt = new com.puppy.model.QuizAttempt(definition);
                attempt.select(1);
                attempt.check();
                attempt.finish(false);
                assertEquals(1, attempt.correctCount());
                history.save(history.conversations().getFirst()).join();
            }
        }
        try (Database database = Database.open(directory);
             var history = new com.puppy.service.ChatHistoryService(new ChatRepository(database), new SettingsRepository(database))) {
            assertEquals("QUIZ", history.conversations().getFirst().messages().getFirst().entryType());
            assertEquals(definition, history.quiz("entry").orElseThrow());
            var fresh = new com.puppy.model.QuizAttempt(history.quiz("entry").orElseThrow());
            assertEquals(-1, fresh.selection(0));
            assertEquals(0, fresh.answeredCount());
            assertFalse(fresh.finished());
            history.save(history.conversations().getFirst()).join();
            assertEquals(1, new QuizRepository(database).findByMessage("entry").orElseThrow().questions().size());
        }
    }

    @Test
    void rejectsDuplicateOptionsAndOutOfBoundsAnswers() {
        assertThrows(IllegalArgumentException.class, () -> new QuizDefinition.Question("q", "text",
                List.of("a", " A ", "b", "c"), 0, "explanation", QuizDefinition.Difficulty.EASY));
        assertThrows(IllegalArgumentException.class, () -> new QuizDefinition.Question("q", "text",
                List.of("a", "b", "c", "d"), 4, "explanation", QuizDefinition.Difficulty.EASY));
    }
}
