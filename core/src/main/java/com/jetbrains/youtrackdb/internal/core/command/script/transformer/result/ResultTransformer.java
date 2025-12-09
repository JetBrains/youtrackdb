package com.jetbrains.youtrackdb.internal.core.command.script.transformer.result;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.query.Result;


public interface ResultTransformer<T> {

  Result transform(DatabaseSessionInternal db, T value);
}
