package com.jetbrains.youtrack.db.internal.core.db.tool.importer;

import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;

/**
 *
 */
public interface ValuesConverter<T> {

  T convert(DatabaseSessionInternal session, T value);
}
