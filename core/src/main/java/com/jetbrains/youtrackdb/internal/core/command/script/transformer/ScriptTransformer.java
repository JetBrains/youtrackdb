package com.jetbrains.youtrackdb.internal.core.command.script.transformer;

import com.jetbrains.youtrackdb.internal.core.command.script.transformer.result.ResultTransformer;
import com.jetbrains.youtrackdb.internal.core.command.script.transformer.resultset.ResultSetTransformer;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.query.ResultSet;
import javax.annotation.Nullable;

/**
 *
 */
public interface ScriptTransformer {

  @Nullable
  ResultSet toResultSet(DatabaseSessionInternal db, Object value);

  Result toResult(DatabaseSessionInternal db, Object value);

  boolean doesHandleResult(Object value);

  void registerResultTransformer(Class clazz, ResultTransformer resultTransformer);

  void registerResultSetTransformer(Class clazz, ResultSetTransformer transformer);
}
