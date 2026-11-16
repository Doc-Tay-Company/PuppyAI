package com.puppy.persistence;

/**
 * Thrown when a database operation fails.
 *
 * <p>Repository and service code catches {@link java.sql.SQLException} and rethrows
 * it as this unchecked exception, so callers above the persistence layer do not
 * depend on JDBC. The message names the operation, never the full file path or SQL
 * parameters.</p>
 */
public class RepositoryException extends RuntimeException {

    public RepositoryException(String message) {
        super(message);
    }

    public RepositoryException(String message, Throwable cause) {
        super(message, cause);
    }
}
