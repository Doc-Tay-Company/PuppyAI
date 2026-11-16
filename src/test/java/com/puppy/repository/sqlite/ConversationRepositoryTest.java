package com.puppy.repository.sqlite;

import com.puppy.enums.MessageRole;
import com.puppy.model.ChatMessage;
import com.puppy.model.Conversation;
import com.puppy.persistence.Database;
import com.puppy.persistence.RepositoryException;
import com.puppy.repository.ConversationRepository;
import com.puppy.repository.MessageRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises conversation storage against a temporary SQLite file.
 */
class ConversationRepositoryTest {

    private static final Instant TEN = Instant.parse("2026-01-01T10:00:00Z");
    private static final Instant ELEVEN = Instant.parse("2026-01-01T11:00:00Z");
    private static final Instant NOON = Instant.parse("2026-01-01T12:00:00Z");

    @TempDir
    Path directory;

    private Database database;
    private ConversationRepository conversations;
    private MessageRepository messages;

    @BeforeEach
    void setUp() {
        database = new Database(directory.resolve("test.db"));
        database.initialize();
        conversations = new SQLiteConversationRepository(database);
        messages = new SQLiteMessageRepository(database);
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    @Test
    void savesAndReadsAConversation() {
        conversations.save(conversation("c1", "First chat", TEN, TEN));

        Conversation stored = conversations.findById("c1").orElseThrow();

        assertEquals("First chat", stored.title());
        assertEquals(TEN, stored.createdAt());
        assertEquals(TEN, stored.updatedAt());
        assertEquals(0, stored.messageCount());
    }

    @Test
    void findByIdReturnsEmptyForUnknownId() {
        assertTrue(conversations.findById("missing").isEmpty());
    }

    @Test
    void findRecentReturnsNewestFirstAndHonoursTheLimit() {
        conversations.save(conversation("old", "Old", TEN, TEN));
        conversations.save(conversation("middle", "Middle", TEN, ELEVEN));
        conversations.save(conversation("new", "New", TEN, NOON));

        List<Conversation> recent = conversations.findRecent(10);
        List<Conversation> limited = conversations.findRecent(2);

        assertEquals(List.of("new", "middle", "old"), recent.stream().map(Conversation::id).toList());
        assertEquals(List.of("new", "middle"), limited.stream().map(Conversation::id).toList());
    }

    @Test
    void messageCountComesFromTheMessagesTable() {
        conversations.save(conversation("c1", "Chat", TEN, TEN));
        messages.save(message("m1", "c1", MessageRole.USER, "hello", TEN));
        messages.save(message("m2", "c1", MessageRole.ASSISTANT, "hi", ELEVEN));

        assertEquals(2, conversations.findById("c1").orElseThrow().messageCount());
        assertEquals(2, conversations.findRecent(1).getFirst().messageCount());
    }

    @Test
    void touchMovesAConversationToTheTop() {
        conversations.save(conversation("c1", "Older", TEN, TEN));
        conversations.save(conversation("c2", "Newer", TEN, ELEVEN));

        conversations.touch("c1", NOON);

        assertEquals(List.of("c1", "c2"),
                conversations.findRecent(10).stream().map(Conversation::id).toList());
        assertEquals(NOON, conversations.findById("c1").orElseThrow().updatedAt());
    }

    @Test
    void touchRejectsAnUnknownConversation() {
        assertThrows(RepositoryException.class, () -> conversations.touch("missing", NOON));
    }

    @Test
    void deleteRemovesTheConversationAndItsMessages() {
        conversations.save(conversation("c1", "Chat", TEN, TEN));
        messages.save(message("m1", "c1", MessageRole.USER, "hello", TEN));

        conversations.delete("c1");

        assertTrue(conversations.findById("c1").isEmpty());
        assertEquals(0, messages.count("c1"));
    }

    @Test
    void rejectsANonPositiveLimit() {
        assertThrows(IllegalArgumentException.class, () -> conversations.findRecent(0));
    }

    private static Conversation conversation(String id, String title, Instant created, Instant updated) {
        return new Conversation(id, title, created, updated, 0);
    }

    private static ChatMessage message(
            String id, String conversationId, MessageRole role, String content, Instant createdAt) {
        return new ChatMessage(id, conversationId, role, content, createdAt);
    }
}
