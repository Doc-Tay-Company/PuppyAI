package com.puppy.repository.sqlite;

import com.puppy.enums.MessageRole;
import com.puppy.model.ChatMessage;
import com.puppy.model.Conversation;
import com.puppy.persistence.Database;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises message storage, ordering and per-conversation deletion.
 */
class MessageRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");

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
        conversations.save(new Conversation("c1", "Chat", NOW, NOW, 0));
        conversations.save(new Conversation("c2", "Other", NOW, NOW, 0));
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    @Test
    void savesAndLoadsMessagesInWriteOrder() {
        messages.save(message("m1", "c1", MessageRole.USER, "hello", NOW));
        messages.save(message("m2", "c1", MessageRole.ASSISTANT, "hi there", NOW));
        messages.save(message("m3", "c1", MessageRole.USER, "bye", NOW.plusSeconds(5)));

        List<ChatMessage> loaded = messages.findByConversation("c1");

        assertEquals(List.of("m1", "m2", "m3"), loaded.stream().map(ChatMessage::id).toList());
        assertEquals(MessageRole.ASSISTANT, loaded.get(1).role());
        assertEquals("hi there", loaded.get(1).content());
        assertEquals(NOW, loaded.get(0).createdAt());
    }

    @Test
    void returnsNoMessagesForAnUnknownConversation() {
        assertTrue(messages.findByConversation("missing").isEmpty());
    }

    @Test
    void countsMessagesPerConversation() {
        messages.save(message("m1", "c1", MessageRole.USER, "hello", NOW));
        messages.save(message("m2", "c2", MessageRole.USER, "other", NOW));

        assertEquals(1, messages.count("c1"));
        assertEquals(1, messages.count("c2"));
        assertEquals(0, messages.count("missing"));
    }

    @Test
    void deletesOnlyTheRequestedConversationsMessages() {
        messages.save(message("m1", "c1", MessageRole.USER, "hello", NOW));
        messages.save(message("m2", "c2", MessageRole.USER, "other", NOW));

        messages.deleteByConversation("c1");

        assertEquals(0, messages.count("c1"));
        assertEquals(1, messages.count("c2"));
    }

    private static ChatMessage message(
            String id, String conversationId, MessageRole role, String content, Instant createdAt) {
        return new ChatMessage(id, conversationId, role, content, createdAt);
    }
}
