package com.jetbrains.youtrackdb.internal.core.command.script.transformer;

import com.jetbrains.youtrackdb.internal.core.command.script.transformer.result.ResultTransformer;
import com.jetbrains.youtrackdb.internal.core.command.script.transformer.resultset.ResultSetTransformer;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.query.ResultSet;
import javax.annotation.Nullable;

/**
 *
 */
public interface ScriptTransformer {

  @Nullable
  ResultSet toResultSet(DatabaseSessionEmbedded db, Object value);

  Result toResult(DatabaseSessionEmbedded db, Object value);

  boolean doesHandleResult(Object value);

  void registerResultTransformer(Class clazz, ResultTransformer resultTransformer);

  void registerResultSetTransformer(Class clazz, ResultSetTransformer transformer);
}
