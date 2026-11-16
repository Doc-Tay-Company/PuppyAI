package com.puppy.repository.sqlite;

import com.puppy.enums.MessageRole;
import com.puppy.enums.SessionStatus;
import com.puppy.model.ChatMessage;
import com.puppy.model.Conversation;
import com.puppy.model.StudySession;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;

/** Builds model records from repository result sets. */
final class RowMappers {

    private RowMappers() {
    }

    static Conversation conversation(ResultSet result) throws SQLException {
        return new Conversation(
                result.getString("id"),
                result.getString("title"),
                instant(result.getString("created_at")),
                instant(result.getString("updated_at")),
                result.getInt("message_count"));
    }

    static ChatMessage message(ResultSet result) throws SQLException {
        return new ChatMessage(
                result.getString("id"),
                result.getString("conversation_id"),
                MessageRole.valueOf(result.getString("role")),
                result.getString("content"),
                instant(result.getString("created_at")));
    }

    static StudySession studySession(ResultSet result) throws SQLException {
        String completedAt = result.getString("completed_at");
        return new StudySession(
                result.getString("id"),
                instant(result.getString("started_at")),
                completedAt == null ? null : instant(completedAt),
                result.getLong("duration_seconds"),
                SessionStatus.valueOf(result.getString("status")));
    }

    private static Instant instant(String value) {
        return Instant.parse(value);
    }
}
