package com.jetbrains.youtrack.db.internal.core.command.script.transformer.result;

import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;


public interface ResultTransformer<T> {

  Result transform(DatabaseSessionInternal db, T value);
}
