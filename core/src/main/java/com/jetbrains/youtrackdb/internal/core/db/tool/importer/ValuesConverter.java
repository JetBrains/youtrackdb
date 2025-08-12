package com.jetbrains.youtrackdb.internal.core.db.tool.importer;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;

/**
 *
 */
public interface ValuesConverter<T> {

  T convert(DatabaseSessionInternal session, T value);
}
