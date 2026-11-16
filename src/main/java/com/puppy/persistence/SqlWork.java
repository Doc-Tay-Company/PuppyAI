package com.puppy.persistence;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Work performed inside a {@link Database} call.
 *
 * <p>The connection is owned by {@link Database} and must not be stored or closed by
 * the work itself.</p>
 *
 * @param <T> result type
 */
@FunctionalInterface
public interface SqlWork<T> {

    T apply(Connection connection) throws SQLException;
}
