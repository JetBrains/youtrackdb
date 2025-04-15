package com.jetbrains.youtrack.db.internal.core.command.script.transformer;

import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.query.ResultSet;
import com.jetbrains.youtrack.db.internal.core.command.script.transformer.result.ResultTransformer;
import com.jetbrains.youtrack.db.internal.core.command.script.transformer.resultset.ResultSetTransformer;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
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
