package com.puppy.service;

import com.puppy.enums.MessageRole;
import com.puppy.model.ChatMessage;
import com.puppy.model.Conversation;
import com.puppy.persistence.Database;
import com.puppy.persistence.RepositoryException;
import com.puppy.repository.sqlite.SQLiteConversationRepository;
import com.puppy.repository.sqlite.SQLiteMessageRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the chat service against a temporary database and a controllable clock.
 */
class ChatServiceTest {

    private static final Instant START = Instant.parse("2026-01-01T10:00:00Z");

    @TempDir
    Path directory;

    private Database database;
    private MutableClock clock;
    private ChatService chat;

    @BeforeEach
    void setUp() {
        database = new Database(directory.resolve("test.db"));
        database.initialize();
        clock = new MutableClock(START);
        chat = new ChatService(
                new SQLiteConversationRepository(database),
                new SQLiteMessageRepository(database),
                new MockChatResponder(),
                clock);
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    @Test
    void startingAConversationStoresTheFirstMessageAndDerivesTheTitle() {
        Conversation conversation = chat.startConversation("  Explain photosynthesis  ");

        List<ChatMessage> messages = chat.messages(conversation.id());

        assertEquals("Explain photosynthesis", conversation.title());
        assertEquals(1, conversation.messageCount());
        assertEquals(START, conversation.createdAt());
        assertEquals(1, messages.size());
        assertEquals(MessageRole.USER, messages.getFirst().role());
        assertEquals("Explain photosynthesis", messages.getFirst().content());
    }

    @Test
    void longFirstMessagesAreShortenedIntoTheTitle() {
        String message = "This message is far too long to be used as a complete conversation title";

        Conversation conversation = chat.startConversation(message);

        assertEquals(32, conversation.title().length());
        assertEquals(message.substring(0, 29) + "...", conversation.title());
    }

    @Test
    void appendingMessagesUpdatesTheConversationAndItsActivity() {
        Conversation conversation = chat.startConversation("hello");
        clock.advance(Duration.ofSeconds(30));

        ChatMessage user = chat.appendUserMessage(conversation.id(), "are you there?");
        clock.advance(Duration.ofSeconds(5));
        ChatMessage assistant = chat.appendAssistantMessage(conversation.id(), "I am!");

        Conversation updated = chat.conversation(conversation.id()).orElseThrow();
        assertEquals(3, updated.messageCount());
        assertEquals(clock.instant(), updated.updatedAt());
        assertEquals("are you there?", user.content());
        assertEquals(MessageRole.ASSISTANT, assistant.role());
        assertEquals(List.of("hello", "are you there?", "I am!"),
                chat.messages(conversation.id()).stream().map(ChatMessage::content).toList());
    }

    @Test
    void blankMessagesAreRejectedWithoutCreatingAConversation() {
        assertThrows(IllegalArgumentException.class, () -> chat.startConversation("  "));
        assertThrows(IllegalArgumentException.class, () -> chat.startConversation(null));

        assertTrue(chat.recentConversations().isEmpty());
    }

    @Test
    void appendingToAnUnknownConversationFails() {
        assertThrows(RepositoryException.class,
                () -> chat.appendUserMessage("missing", "hello"));
    }

    @Test
    void recentConversationsAreNewestFirstAndCanBeDeleted() {
        Conversation first = chat.startConversation("first chat");
        clock.advance(Duration.ofSeconds(60));
        Conversation second = chat.startConversation("second chat");

        assertEquals(List.of(second.id(), first.id()),
                chat.recentConversations().stream().map(Conversation::id).toList());

        chat.deleteConversation(first.id());

        assertEquals(1, chat.recentConversations().size());
        assertTrue(chat.messages(first.id()).isEmpty());
        assertTrue(chat.conversation(first.id()).isEmpty());
    }

    @Test
    void conversationIdsAreUnique() {
        Conversation first = chat.startConversation("one");
        Conversation second = chat.startConversation("two");

        assertNotEquals(first.id(), second.id());
    }

    @Test
    void greetingAndRepliesStillComeFromTheMockResponder() {
        assertEquals(MockChatResponder.GREETING, chat.greeting());
        assertTrue(chat.replyTo("study").contains("Study mode"));
    }

    /** Clock the test can move forward without sleeping. */
    private static final class MutableClock extends Clock {

        private Instant now;

        private MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
