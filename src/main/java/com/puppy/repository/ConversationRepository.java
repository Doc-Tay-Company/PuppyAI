package com.puppy.repository;

import com.puppy.model.Conversation;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Stores chat conversations and their ordering in the history pane.
 */
public interface ConversationRepository {

    /** Inserts a conversation. The message count is derived, not stored. */
    void save(Conversation conversation);

    Optional<Conversation> findById(String id);

    /** Newest first, with the number of messages in each conversation. */
    List<Conversation> findRecent(int limit);

    /** Records new activity, moving the conversation to the top of the list. */
    void touch(String id, Instant updatedAt);

    /** Deletes the conversation and, by cascade, its messages. */
    void delete(String id);
}
