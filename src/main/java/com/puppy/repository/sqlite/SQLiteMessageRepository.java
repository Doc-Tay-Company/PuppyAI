package com.puppy.repository.sqlite;

import com.puppy.model.ChatMessage;
import com.puppy.persistence.Database;
import com.puppy.repository.MessageRepository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * SQLite-backed {@link MessageRepository}.
 *
 * <p>Ordering falls back to SQLite's implicit rowid when two messages share the same
 * timestamp, which can happen when a reply is stored immediately after the user
 * message.</p>
 */
public final class SQLiteMessageRepository implements MessageRepository {

    private final Database database;

    public SQLiteMessageRepository(Database database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    @Override
    public void save(ChatMessage message) {
        Objects.requireNonNull(message, "message");
        database.transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO messages (id, conversation_id, role, content, created_at) "
                            + "VALUES (?, ?, ?, ?, ?)")) {
                statement.setString(1, message.id());
                statement.setString(2, message.conversationId());
                statement.setString(3, message.role().name());
                statement.setString(4, message.content());
                statement.setString(5, message.createdAt().toString());
                statement.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public List<ChatMessage> findByConversation(String conversationId) {
        Objects.requireNonNull(conversationId, "conversationId");
        return database.query(connection -> {
            List<ChatMessage> messages = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT id, conversation_id, role, content, created_at FROM messages "
                            + "WHERE conversation_id = ? ORDER BY created_at, rowid")) {
                statement.setString(1, conversationId);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        messages.add(RowMappers.message(result));
                    }
                }
            }
            return List.copyOf(messages);
        });
    }

    @Override
    public int count(String conversationId) {
        Objects.requireNonNull(conversationId, "conversationId");
        return database.query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT COUNT(*) FROM messages WHERE conversation_id = ?")) {
                statement.setString(1, conversationId);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? result.getInt(1) : 0;
                }
            }
        });
    }

    @Override
    public void deleteByConversation(String conversationId) {
        Objects.requireNonNull(conversationId, "conversationId");
        database.transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM messages WHERE conversation_id = ?")) {
                statement.setString(1, conversationId);
                statement.executeUpdate();
            }
            return null;
        });
    }
}
