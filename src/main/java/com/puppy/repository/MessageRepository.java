package com.puppy.repository;

import com.puppy.model.ChatMessage;

import java.util.List;

/**
 * Stores the individual messages of a conversation.
 */
public interface MessageRepository {

    void save(ChatMessage message);

    /** Messages in the order they were written. */
    List<ChatMessage> findByConversation(String conversationId);

    int count(String conversationId);

    void deleteByConversation(String conversationId);
}
