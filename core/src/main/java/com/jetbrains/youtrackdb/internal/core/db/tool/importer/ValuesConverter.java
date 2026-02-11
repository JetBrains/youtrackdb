package com.jetbrains.youtrackdb.internal.core.db.tool.importer;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;

/**
 *
 */
public interface ValuesConverter<T> {

  T convert(DatabaseSessionEmbedded session, T value);
}
