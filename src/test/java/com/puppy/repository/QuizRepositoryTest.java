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
    void rejectsDuplicateOptionsAndOutOfBoundsAnswers() {
        assertThrows(IllegalArgumentException.class, () -> new QuizDefinition.Question("q", "text",
                List.of("a", " A ", "b", "c"), 0, "explanation", QuizDefinition.Difficulty.EASY));
        assertThrows(IllegalArgumentException.class, () -> new QuizDefinition.Question("q", "text",
                List.of("a", "b", "c", "d"), 4, "explanation", QuizDefinition.Difficulty.EASY));
    }
}
