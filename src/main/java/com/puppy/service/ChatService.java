package com.puppy.service;

import com.puppy.enums.MessageRole;
import com.puppy.model.ChatMessage;
import com.puppy.model.Conversation;
import com.puppy.persistence.RepositoryException;
import com.puppy.repository.ConversationRepository;
import com.puppy.repository.MessageRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Persists chat conversations and hides the storage details from the UI.
 *
 * <p>Until milestone 4 the reply text still comes from {@link MockChatResponder}; this
 * service only decides what is stored. A conversation is created with its first user
 * message, so empty chats never appear in the history.</p>
 */
public final class ChatService {

    /** How many conversations the history pane loads. */
    public static final int HISTORY_LIMIT = 50;

    private static final int MAX_TITLE_LENGTH = 32;

    private final ConversationRepository conversations;
    private final MessageRepository messages;
    private final MockChatResponder responder;
    private final Clock clock;

    public ChatService(ConversationRepository conversations,
                       MessageRepository messages,
                       MockChatResponder responder,
                       Clock clock) {
        this.conversations = Objects.requireNonNull(conversations, "conversations");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.responder = Objects.requireNonNull(responder, "responder");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** The welcome bubble shown above an empty chat. */
    public String greeting() {
        return MockChatResponder.GREETING;
    }

    /** A stand-in assistant reply until the AI providers arrive in milestone 4. */
    public String replyTo(String userMessage) {
        return responder.replyTo(userMessage);
    }

    /**
     * Creates a conversation whose title is derived from the first message and stores
     * that message as its first entry.
     */
    public Conversation startConversation(String firstMessage) {
        String text = requireMessage(firstMessage);
        Instant now = clock.instant();
        Conversation conversation = new Conversation(
                UUID.randomUUID().toString(), titleFor(text), now, now, 1);
        conversations.save(conversation);
        messages.save(new ChatMessage(
                UUID.randomUUID().toString(), conversation.id(), MessageRole.USER, text, now));
        return conversation;
    }

    /** Appends a user message and marks the conversation as recently used. */
    public ChatMessage appendUserMessage(String conversationId, String text) {
        return append(conversationId, MessageRole.USER, text);
    }

    /** Appends an assistant message and marks the conversation as recently used. */
    public ChatMessage appendAssistantMessage(String conversationId, String text) {
        return append(conversationId, MessageRole.ASSISTANT, text);
    }

    public Optional<Conversation> conversation(String conversationId) {
        Objects.requireNonNull(conversationId, "conversationId");
        return conversations.findById(conversationId);
    }

    /** Most recently active conversations, for the history pane. */
    public List<Conversation> recentConversations() {
        return conversations.findRecent(HISTORY_LIMIT);
    }

    public List<ChatMessage> messages(String conversationId) {
        Objects.requireNonNull(conversationId, "conversationId");
        return messages.findByConversation(conversationId);
    }

    public void deleteConversation(String conversationId) {
        Objects.requireNonNull(conversationId, "conversationId");
        conversations.delete(conversationId);
    }

    private ChatMessage append(String conversationId, MessageRole role, String text) {
        Objects.requireNonNull(conversationId, "conversationId");
        String content = requireMessage(text);
        if (conversations.findById(conversationId).isEmpty()) {
            throw new RepositoryException("Conversation not found: " + conversationId);
        }
        Instant now = clock.instant();
        ChatMessage message = new ChatMessage(
                UUID.randomUUID().toString(), conversationId, role, content, now);
        messages.save(message);
        conversations.touch(conversationId, now);
        return message;
    }

    private static String requireMessage(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
        return text.trim();
    }

    private static String titleFor(String message) {
        if (message.length() <= MAX_TITLE_LENGTH) {
            return message;
        }
        return message.substring(0, MAX_TITLE_LENGTH - 3) + "...";
    }
}
