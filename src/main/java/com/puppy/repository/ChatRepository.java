package com.puppy.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.puppy.database.Database;
import com.puppy.model.DocumentAttachment;
import com.puppy.model.StoredConversation;
import com.puppy.model.StoredMessage;
import com.puppy.model.QuizDefinition;

import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;

public final class ChatRepository {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Database database;

    public ChatRepository(Database database) {
        this.database = database;
    }

    public List<StoredConversation> load() {
        return database.call(connection -> {
            List<StoredConversation> result = new ArrayList<>();
            try (var statement = connection.createStatement();
                 var rows = statement.executeQuery("SELECT id,title,updated_at,title_state FROM conversations ORDER BY updated_at DESC,id")) {
                while (rows.next()) {
                    String id = rows.getString(1);
                    result.add(new StoredConversation(id, rows.getString(2), rows.getLong(3), rows.getString(4),
                            messages(connection, id), attachments(connection, id)));
                }
            }
            return List.copyOf(result);
        });
    }

    private List<StoredMessage> messages(Connection connection, String id) throws Exception {
        List<StoredMessage> messages = new ArrayList<>();
        try (var statement = connection.prepareStatement("SELECT id,role,content,filenames,elapsed_ms,status,entry_type FROM messages WHERE conversation_id=? ORDER BY position")) {
            statement.setString(1, id);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    messages.add(new StoredMessage(rows.getString(1), rows.getString(2), rows.getString(3),
                            JSON.readValue(rows.getString(4), new TypeReference<List<String>>() { }),
                            rows.getLong(5), StoredMessage.Status.valueOf(rows.getString(6)), rows.getString(7)));
                }
            }
        }
        return messages;
    }

    private List<DocumentAttachment> attachments(Connection connection, String id) throws Exception {
        List<DocumentAttachment> attachments = new ArrayList<>();
        try (var statement = connection.prepareStatement("SELECT filename,text,pages,truncated FROM attachments WHERE conversation_id=? ORDER BY position")) {
            statement.setString(1, id);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    String name = rows.getString(1);
                    if (!Path.of(name).getFileName().toString().equals(name)) {
                        throw new IllegalStateException("Invalid saved attachment filename");
                    }
                    attachments.add(new DocumentAttachment(Path.of(name), rows.getString(2), rows.getInt(3), rows.getBoolean(4)));
                }
            }
        }
        return attachments;
    }

    public void save(StoredConversation conversation) {
        saveWithQuizzes(conversation, List.of());
    }

    public List<QuizDefinition> quizzes() {
        return database.call(QuizRepository::loadAll);
    }

    public void saveWithQuizzes(StoredConversation conversation, List<QuizDefinition> quizzes) {
        database.transaction(connection -> {
            try (var statement = connection.prepareStatement("INSERT INTO conversations(id,title,updated_at,title_state) VALUES(?,?,?,?) ON CONFLICT(id) DO UPDATE SET updated_at=excluded.updated_at")) {
                statement.setString(1, conversation.id());
                statement.setString(2, conversation.title());
                statement.setLong(3, conversation.updatedAt());
                statement.setString(4, conversation.titleState());
                statement.executeUpdate();
            }
            // Upsert rather than replacing messages: quiz foreign keys remain intact.
            for (int i = 0; i < conversation.messages().size(); i++) {
                StoredMessage message = conversation.messages().get(i);
                try (var statement = connection.prepareStatement("INSERT INTO messages(id,conversation_id,position,role,content,filenames,elapsed_ms,status,entry_type) VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET content=excluded.content,filenames=excluded.filenames,elapsed_ms=excluded.elapsed_ms,status=excluded.status,entry_type=excluded.entry_type")) {
                    statement.setString(1, message.id());
                    statement.setString(2, conversation.id());
                    statement.setInt(3, i);
                    statement.setString(4, message.role());
                    statement.setString(5, message.text());
                    statement.setString(6, JSON.writeValueAsString(message.attachments()));
                    statement.setLong(7, message.elapsedMillis());
                    statement.setString(8, message.status().name());
                    statement.setString(9, message.entryType());
                    statement.executeUpdate();
                }
            }
            try (var statement = connection.prepareStatement("DELETE FROM attachments WHERE conversation_id=?")) {
                statement.setString(1, conversation.id());
                statement.executeUpdate();
            }
            for (int i = 0; i < conversation.attachments().size(); i++) {
                DocumentAttachment document = conversation.attachments().get(i);
                try (var statement = connection.prepareStatement("INSERT INTO attachments(conversation_id,position,filename,text,pages,truncated) VALUES(?,?,?,?,?,?)")) {
                    statement.setString(1, conversation.id());
                    statement.setInt(2, i);
                    statement.setString(3, document.fileName());
                    statement.setString(4, document.text());
                    statement.setInt(5, document.pageCount());
                    statement.setBoolean(6, document.truncated());
                    statement.executeUpdate();
                }
            }
            for (QuizDefinition quiz : quizzes) {
                if (conversation.messages().stream().noneMatch(message -> message.id().equals(quiz.messageId())
                        && message.entryType().equals("QUIZ"))) {
                    throw new IllegalArgumentException("A quiz must belong to this conversation's quiz entry.");
                }
                QuizRepository.save(connection, quiz);
            }
            return null;
        });
    }

    public void setTitle(String id, String title, String state) {
        database.transaction(connection -> {
            try (var statement = connection.prepareStatement("UPDATE conversations SET title=?,title_state=? WHERE id=?")) {
                statement.setString(1, title);
                statement.setString(2, state);
                statement.setString(3, id);
                statement.executeUpdate();
            }
            return null;
        });
    }
}
