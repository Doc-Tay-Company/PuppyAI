package com.puppy.database;

import javafx.application.Platform;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Owns one connection and one serialized execution lane per locked data directory. */
public final class Database implements AutoCloseable {
    public static final int SCHEMA_VERSION = 1;

    @FunctionalInterface
    public interface Work<T> {
        T run(Connection connection) throws Exception;
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "puppy-database");
        thread.setDaemon(true);
        return thread;
    });
    private Connection connection;
    private FileChannel lockChannel;
    private FileLock lock;
    private boolean closed;

    private Database() { }

    public static Database open(Path directory) {
        Database database = new Database();
        try {
            Path root = directory.toAbsolutePath().normalize();
            if (root.toString().startsWith("\\\\")) {
                throw new SQLException("Network data directories are not supported.");
            }
            Files.createDirectories(root);
            database.lockChannel = FileChannel.open(root.resolve("puppy.lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            try {
                database.lock = database.lockChannel.tryLock();
            } catch (OverlappingFileLockException e) {
                throw new SQLException("Another Puppy instance is using this data directory.", e);
            }
            if (database.lock == null) {
                throw new SQLException("Another Puppy instance is using this data directory.");
            }
            database.call(connection -> {
                database.connection = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("puppy.db"));
                database.initialize(root);
                return null;
            });
            return database;
        } catch (Exception e) {
            try {
                database.close();
            } catch (RuntimeException closeFailure) {
                e.addSuppressed(closeFailure);
            }
            Throwable reason = e;
            while (reason.getCause() != null) {
                reason = reason.getCause();
            }
            String guidance = reason.getMessage() != null && reason.getMessage().contains("newer Puppy")
                    ? "Install a newer Puppy version to open this database."
                    : "Close other instances, check folder access/disk space, or restore a verified backup if storage is damaged.";
            throw new PersistenceException("Cannot open Puppy storage at " + directory
                    + ". " + guidance + " Existing data was not reset.", e);
        }
    }

    private void initialize(Path directory) throws Exception {
        int version;
        try (var statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout=5000");
            try (var rows = statement.executeQuery("PRAGMA user_version")) {
                version = rows.getInt(1);
            }
            if (version > SCHEMA_VERSION) {
                throw new SQLException("This database requires a newer Puppy version.");
            }
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA secure_delete=ON");
            statement.execute("PRAGMA journal_mode=DELETE");
            statement.execute("PRAGMA synchronous=FULL");
            try (var rows = statement.executeQuery("PRAGMA quick_check")) {
                if (!rows.next() || !"ok".equals(rows.getString(1))) {
                    throw new SQLException("Database integrity check failed.");
                }
            }
        }
        if (version == SCHEMA_VERSION) {
            recoverInterruptedReplies();
            return;
        }
        boolean hasTables;
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT count(*) FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")) {
            hasTables = rows.getInt(1) > 0;
        }
        if (hasTables) {
            Path temporary = directory.resolve("puppy.pre-upgrade.tmp.db");
            Files.deleteIfExists(temporary);
            try (var statement = connection.createStatement()) {
                statement.execute("VACUUM INTO '" + temporary.toString().replace("'", "''") + "'");
            }
            Files.move(temporary, directory.resolve("puppy.pre-upgrade.db"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        connection.setAutoCommit(false);
        try (var statement = connection.createStatement()) {
            for (String sql : SCHEMA) {
                statement.execute(sql);
            }
            statement.execute("PRAGMA user_version=" + SCHEMA_VERSION);
            connection.commit();
        } catch (Exception e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
        recoverInterruptedReplies();
    }

    private void recoverInterruptedReplies() throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE messages SET status='INTERRUPTED' WHERE status='GENERATING'");
        }
    }

    public <T> T call(Work<T> work) {
        if (Platform.isFxApplicationThread()) {
            throw new IllegalStateException("Database work must not run on the JavaFX thread.");
        }
        var future = executor.submit(() -> work.run(connection));
        boolean interrupted = false;
        try {
            for (;;) {
                try {
                    return future.get();
                } catch (InterruptedException e) {
                    // An accepted transaction must finish before its caller can publish state.
                    interrupted = true;
                } catch (ExecutionException e) {
                    throw new PersistenceException("Puppy could not read or save local data. Check disk space and folder access, then retry.", e.getCause());
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public <T> T transaction(Work<T> work) {
        return call(connection -> {
            connection.setAutoCommit(false);
            try {
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (Exception e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        });
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        try {
            call(connection -> {
                if (connection != null) {
                    connection.close();
                }
                return null;
            });
            if (lock != null) {
                lock.release();
            }
            if (lockChannel != null) {
                lockChannel.close();
            }
        } catch (Exception e) {
            throw new PersistenceException("Could not close Puppy storage cleanly.", e);
        } finally {
            closed = true;
            executor.shutdown();
        }
    }

    private static final String[] SCHEMA = {
            "CREATE TABLE settings (key TEXT PRIMARY KEY, value TEXT NOT NULL)",
            "CREATE TABLE conversations (id TEXT PRIMARY KEY, title TEXT NOT NULL, updated_at INTEGER NOT NULL, title_state TEXT NOT NULL DEFAULT 'PENDING')",
            "CREATE TABLE messages (id TEXT PRIMARY KEY, conversation_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE, position INTEGER NOT NULL, role TEXT NOT NULL CHECK(role IN ('user','assistant')), content TEXT NOT NULL, filenames TEXT NOT NULL, elapsed_ms INTEGER NOT NULL CHECK(elapsed_ms>=0), status TEXT NOT NULL CHECK(status IN ('COMPLETE','GENERATING','INTERRUPTED','ERROR')), entry_type TEXT NOT NULL DEFAULT 'TEXT' CHECK(entry_type IN ('TEXT','QUIZ')), UNIQUE(conversation_id,position))",
            "CREATE TABLE attachments (conversation_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE, position INTEGER NOT NULL, filename TEXT NOT NULL, text TEXT NOT NULL, pages INTEGER NOT NULL CHECK(pages>=0), truncated INTEGER NOT NULL CHECK(truncated IN (0,1)), PRIMARY KEY(conversation_id,position))",
            "CREATE TABLE focus_state (singleton INTEGER PRIMARY KEY CHECK(singleton=1), payload TEXT NOT NULL)",
            "CREATE TABLE quizzes (id TEXT PRIMARY KEY, message_id TEXT NOT NULL UNIQUE REFERENCES messages(id) ON DELETE CASCADE, title TEXT NOT NULL)",
            "CREATE TABLE quiz_questions (id TEXT PRIMARY KEY, quiz_id TEXT NOT NULL REFERENCES quizzes(id) ON DELETE CASCADE, position INTEGER NOT NULL CHECK(position BETWEEN 0 AND 19), question TEXT NOT NULL CHECK(length(trim(question))>0), options TEXT NOT NULL, correct_index INTEGER NOT NULL CHECK(correct_index BETWEEN 0 AND 3), explanation TEXT NOT NULL CHECK(length(trim(explanation))>0), difficulty TEXT NOT NULL CHECK(difficulty IN ('EASY','MEDIUM','HARD')), UNIQUE(quiz_id,position))",
            "CREATE INDEX conversations_activity ON conversations(updated_at DESC)",
            "CREATE INDEX messages_conversation ON messages(conversation_id,position)"
    };
}
