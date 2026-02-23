package com.jetbrains.youtrackdb.internal.core.db.tool.importer;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;

/**
 * Converts field values of type {@code T} during database import, handling broken links.
 */
public interface ValuesConverter<T> {

  T convert(DatabaseSessionEmbedded session, T value);
}
