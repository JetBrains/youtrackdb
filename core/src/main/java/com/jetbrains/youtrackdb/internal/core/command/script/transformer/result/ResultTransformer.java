package com.jetbrains.youtrackdb.internal.core.command.script.transformer.result;

import com.jetbrains.youtrackdb.api.query.Result;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;


public interface ResultTransformer<T> {

  Result transform(DatabaseSessionInternal db, T value);
}
