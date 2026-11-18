package com.puppy.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.puppy.database.Database;
import com.puppy.model.QuizDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Storage for immutable definitions, deliberately without attempt/progress APIs. */
public final class QuizRepository {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Database database;

    public QuizRepository(Database database) {
        this.database = database;
    }

    public void save(QuizDefinition quiz) {
        database.transaction(connection -> {
            try (var check = connection.prepareStatement("SELECT role,entry_type FROM messages WHERE id=?")) {
                check.setString(1, quiz.messageId());
                try (var rows = check.executeQuery()) {
                    if (!rows.next() || !"assistant".equals(rows.getString(1)) || !"QUIZ".equals(rows.getString(2))) {
                        throw new IllegalArgumentException("A quiz must reference an assistant quiz entry.");
                    }
                }
            }
            try (var statement = connection.prepareStatement("INSERT INTO quizzes(id,message_id,title) VALUES(?,?,?)")) {
                statement.setString(1, quiz.id());
                statement.setString(2, quiz.messageId());
                statement.setString(3, quiz.title());
                statement.executeUpdate();
            }
            for (int i = 0; i < quiz.questions().size(); i++) {
                var question = quiz.questions().get(i);
                try (var statement = connection.prepareStatement("INSERT INTO quiz_questions(id,quiz_id,position,question,options,correct_index,explanation,difficulty) VALUES(?,?,?,?,?,?,?,?)")) {
                    statement.setString(1, question.id());
                    statement.setString(2, quiz.id());
                    statement.setInt(3, i);
                    statement.setString(4, question.text());
                    statement.setString(5, JSON.writeValueAsString(question.options()));
                    statement.setInt(6, question.correctIndex());
                    statement.setString(7, question.explanation());
                    statement.setString(8, question.difficulty().name());
                    statement.executeUpdate();
                }
            }
            return null;
        });
    }

    public Optional<QuizDefinition> findByMessage(String messageId) {
        return database.call(connection -> {
            try (var statement = connection.prepareStatement("SELECT id,title FROM quizzes WHERE message_id=?")) {
                statement.setString(1, messageId);
                try (var rows = statement.executeQuery()) {
                    if (!rows.next()) {
                        return Optional.empty();
                    }
                    String id = rows.getString(1);
                    String title = rows.getString(2);
                    List<QuizDefinition.Question> questions = new ArrayList<>();
                    try (var query = connection.prepareStatement("SELECT id,question,options,correct_index,explanation,difficulty FROM quiz_questions WHERE quiz_id=? ORDER BY position")) {
                        query.setString(1, id);
                        try (var questionRows = query.executeQuery()) {
                            while (questionRows.next()) {
                                questions.add(new QuizDefinition.Question(questionRows.getString(1), questionRows.getString(2),
                                        JSON.readValue(questionRows.getString(3), new TypeReference<List<String>>() { }),
                                        questionRows.getInt(4), questionRows.getString(5),
                                        QuizDefinition.Difficulty.valueOf(questionRows.getString(6))));
                            }
                        }
                    }
                    return Optional.of(new QuizDefinition(id, messageId, title, questions));
                }
            }
        });
    }
}
