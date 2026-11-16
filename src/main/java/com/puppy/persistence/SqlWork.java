package com.puppy.persistence;

import java.sql.Connection;![Use this image as the character identity and style reference](assets/images/imageedit-1794799981736-7db3e6.webp)![Use this image as the character identity and style reference](assets/images/imageedit-1794800277995-a75435.webp)
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
