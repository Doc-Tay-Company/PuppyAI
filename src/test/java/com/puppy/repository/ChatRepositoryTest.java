package com.puppy.repository;

import com.puppy.database.Database;
import com.puppy.model.DocumentAttachment;
import com.puppy.model.StoredConversation;
import com.puppy.model.StoredMessage;
import com.puppy.service.ChatHistoryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ChatRepositoryTest {
    @TempDir Path directory;

    @Test
    void restoresActiveAndArchivedChatsTimingTextAttachmentsAndStableTitles() {
        var message = new StoredMessage("user", "first request", List.of("notes.pdf"), 1234);
        try (Database database = Database.open(directory)) {
            var settings = new SettingsRepository(database);
            try (var history = new ChatHistoryService(new ChatRepository(database), settings)) {
                history.save(chat("one", 1, List.of(message), List.of(new DocumentAttachment(
                        directory.resolve("private").resolve("notes.pdf"), "saved text", 3, true)))).join();
                history.title("one", "AI paraphrase", "COMPLETE").join();
                history.save(chat("two", 2, List.of(new StoredMessage("user", "second", List.of(), 0)), List.of())).join();
                history.activate("one").join();
                history.flush().join();
            }
        }
        try (Database database = Database.open(directory);
             var history = new ChatHistoryService(new ChatRepository(database), new SettingsRepository(database))) {
            assertEquals("one", history.activeId());
            assertEquals(List.of("two", "one"), history.conversations().stream().map(StoredConversation::id).toList());
            var restored = history.conversations().get(1);
            assertEquals("AI paraphrase", restored.title());
            assertEquals(message, restored.messages().getFirst());
            var document = restored.attachments().getFirst();
            assertEquals(Path.of("notes.pdf"), document.source());
            assertEquals("saved text", document.text());
            assertEquals(3, document.pageCount());
            assertTrue(document.truncated());
            history.save(chat("one", 3, List.of(message), List.of())).join();
            assertTrue(new ChatRepository(database).load().stream().filter(c -> c.id().equals("one"))
                    .findFirst().orElseThrow().attachments().isEmpty());
        }
    }

    @Test
    void latestQueuedSnapshotWinsAndFailedWriteCanBeRetried() {
        try (Database database = Database.open(directory);
             var history = new ChatHistoryService(new ChatRepository(database), new SettingsRepository(database))) {
            var first = new StoredMessage("same", "user", "older", List.of(), 0, StoredMessage.Status.COMPLETE, "TEXT");
            history.save(chat("one", 1, List.of(first), List.of()));
            var last = new StoredMessage("same", "user", "newest", List.of(), 0, StoredMessage.Status.COMPLETE, "TEXT");
            history.save(chat("one", 2, List.of(last), List.of())).join();
            history.flush().join();
            assertEquals("newest", new ChatRepository(database).load().getFirst().messages().getFirst().text());

            database.call(connection -> {
                try (var sql = connection.createStatement()) {
                    sql.execute("CREATE TRIGGER fail_save BEFORE UPDATE ON conversations BEGIN SELECT RAISE(ABORT,'injected'); END");
                }
                return null;
            });
            assertThrows(java.util.concurrent.CompletionException.class,
                    () -> history.save(chat("one", 3, List.of(last), List.of())).join());
            database.call(connection -> {
                try (var sql = connection.createStatement()) { sql.execute("DROP TRIGGER fail_save"); }
                return null;
            });
            history.retry().join();
            assertEquals(3, new ChatRepository(database).load().getFirst().updatedAt());
        }
    }

    private StoredConversation chat(String id, long updated, List<StoredMessage> messages,
                                    List<DocumentAttachment> documents) {
        return new StoredConversation(id, "provisional", updated, "PENDING", messages, documents);
    }
}
