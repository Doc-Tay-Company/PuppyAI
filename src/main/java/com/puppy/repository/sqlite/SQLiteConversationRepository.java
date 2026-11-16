package com.puppy.repository.sqlite;

import com.puppy.model.Conversation;
import com.puppy.persistence.Database;
import com.puppy.persistence.RepositoryException;
import com.puppy.repository.ConversationRepository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * SQLite-backed {@link ConversationRepository}.
 *
 * <p>The message count comes from a join, so it always matches the messages table.</p>
 */
public final class SQLiteConversationRepository implements ConversationRepository {

    private static final String FIND_BY_ID = """
            SELECT c.id, c.title, c.created_at, c.updated_at, COUNT(m.id) AS message_count
            FROM conversations c
            LEFT JOIN messages m ON m.conversation_id = c.id
            WHERE c.id = ?
            GROUP BY c.id, c.title, c.created_at, c.updated_at
            """;

    private static final String FIND_RECENT = """
            SELECT c.id, c.title, c.created_at, c.updated_at, COUNT(m.id) AS message_count
            FROM conversations c
            LEFT JOIN messages m ON m.conversation_id = c.id
            GROUP BY c.id, c.title, c.created_at, c.updated_at
            ORDER BY c.updated_at DESC, c.created_at DESC
            LIMIT ?
            """;

    private final Database database;

    public SQLiteConversationRepository(Database database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    @Override
    public void save(Conversation conversation) {
        Objects.requireNonNull(conversation, "conversation");
        database.transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO conversations (id, title, created_at, updated_at) VALUES (?, ?, ?, ?)")) {
                statement.setString(1, conversation.id());
                statement.setString(2, conversation.title());
                statement.setString(3, conversation.createdAt().toString());
                statement.setString(4, conversation.updatedAt().toString());
                statement.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public Optional<Conversation> findById(String id) {
        Objects.requireNonNull(id, "id");
        return database.query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(FIND_BY_ID)) {
                statement.setString(1, id);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next()
                            ? Optional.of(RowMappers.conversation(result))
                            : Optional.empty();
                }
            }
        });
    }

    @Override
    public List<Conversation> findRecent(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        return database.query(connection -> {
            List<Conversation> conversations = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(FIND_RECENT)) {
                statement.setInt(1, limit);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        conversations.add(RowMappers.conversation(result));
                    }
                }
            }
            return List.copyOf(conversations);
        });
    }

    @Override
    public void touch(String id, Instant updatedAt) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(updatedAt, "updatedAt");
        database.transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE conversations SET updated_at = ? WHERE id = ?")) {
                statement.setString(1, updatedAt.toString());
                statement.setString(2, id);
                if (statement.executeUpdate() != 1) {
                    throw new RepositoryException("Conversation not found: " + id);
                }
            }
            return null;
        });
    }

    @Override
    public void delete(String id) {
        Objects.requireNonNull(id, "id");
        database.transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM conversations WHERE id = ?")) {
                statement.setString(1, id);
                statement.executeUpdate();
            }
            return null;
        });
    }
}
