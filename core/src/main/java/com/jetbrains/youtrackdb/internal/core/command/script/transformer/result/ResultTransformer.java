package com.jetbrains.youtrackdb.internal.core.command.script.transformer.result;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.query.Result;


public interface ResultTransformer<T> {

  Result transform(DatabaseSessionEmbedded db, T value);
}
