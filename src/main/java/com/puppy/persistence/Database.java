package com.puppy.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;

/**
 * Owns the application's single SQLite connection.
 *
 * <p>One connection is enough for a desktop app with low write volume, and
 * serialising access through this class removes the usual race between a read and a
 * write in the same process. Callers use {@link #query(SqlWork)} for reads and
 * {@link #transaction(SqlWork)} for writes; both run on the calling thread, so the
 * JavaFX thread must not be blocked by anything expensive (nothing here is).</p>
 *
 * <p>The database lives in a single file; see {@link Schema} for the tables.</p>
 */
public final class Database implements AutoCloseable {

    private static final int BUSY_TIMEOUT_MILLIS = 5_000;

    private final Path file;
    private final Connection connection;
    private final Object lock = new Object();

    private boolean closed;

    /**
     * Opens (or creates) the database file. Parent directories are created when
     * missing.
     *
     * @param file path to the SQLite file
     */
    public Database(Path file) {
        this.file = Objects.requireNonNull(file, "file").toAbsolutePath();
        Path parent = this.file.getParent();
        try {
            if (parent != null) {
                Files.createDirectories(parent);
            }
            this.connection = DriverManager.getConnection("jdbc:sqlite:" + this.file);
        } catch (IOException | SQLException e) {
            throw new RepositoryException("Could not open the database at " + this.file, e);
        }
    }

    /**
     * Applies the connection pragmas and creates or migrates the schema.
     *
     * <p>Safe to call more than once; a database already at the current schema
     * version is left untouched.</p>
     */
    public void initialize() {
        synchronized (lock) {
            ensureOpen();
            try {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("PRAGMA foreign_keys = ON");
                    statement.execute("PRAGMA journal_mode = WAL");
                    statement.execute("PRAGMA busy_timeout = " + BUSY_TIMEOUT_MILLIS);
                }
                Schema.migrate(connection);
            } catch (SQLException e) {
                throw new RepositoryException("Could not initialize the database schema", e);
            }
        }
    }

    /** The database file, resolved to an absolute path. */
    public Path file() {
        return file;
    }

    /** Runs a read on the shared connection. */
    public <T> T query(SqlWork<T> work) {
        Objects.requireNonNull(work, "work");
        synchronized (lock) {
            ensureOpen();
            try {
                return work.apply(connection);
            } catch (SQLException e) {
                throw new RepositoryException("Database query failed", e);
            }
        }
    }

    /**
     * Runs a write inside a transaction and commits it, or rolls it back when the
     * work throws.
     */
    public <T> T transaction(SqlWork<T> work) {
        Objects.requireNonNull(work, "work");
        synchronized (lock) {
            ensureOpen();
            boolean autoCommit;
            try {
                autoCommit = connection.getAutoCommit();
                connection.setAutoCommit(false);
            } catch (SQLException e) {
                throw new RepositoryException("Could not start a transaction", e);
            }
            try {
                T result = work.apply(connection);
                connection.commit();
                return result;
            } catch (SQLException e) {
                rollbackQuietly();
                throw new RepositoryException("Database transaction failed", e);
            } catch (RuntimeException e) {
                rollbackQuietly();
                throw e;
            } finally {
                try {
                    connection.setAutoCommit(autoCommit);
                } catch (SQLException ignored) {
                    // The connection is closing or already broken; close() reports it.
                }
            }
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            try {
                connection.close();
            } catch (SQLException e) {
                throw new RepositoryException("Could not close the database at " + file, e);
            }
        }
    }

    private void rollbackQuietly() {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // Keep the original failure as the reported cause.
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Database is closed");
        }
    }
}
