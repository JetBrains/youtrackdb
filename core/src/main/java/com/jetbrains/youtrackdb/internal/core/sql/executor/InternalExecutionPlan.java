package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.api.query.ExecutionPlan;
import com.jetbrains.youtrackdb.api.query.Result;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import javax.annotation.Nullable;

/**
 *
 */
public interface InternalExecutionPlan extends ExecutionPlan {

  String JAVA_TYPE = "javaType";

  void close();

  /**
   * if the execution can still return N elements, then the result will contain them all. If the
   * execution contains less than N elements, then the result will contain them all, next result(s)
   * will contain zero elements
   *
   * @return
   */
  ExecutionStream start();

  void reset(CommandContext ctx);

  CommandContext getContext();

  long getCost();

  default Result serialize(DatabaseSessionEmbedded session) {
    throw new UnsupportedOperationException();
  }

  default void deserialize(Result serializedExecutionPlan, DatabaseSessionInternal session) {
    throw new UnsupportedOperationException();
  }

  default InternalExecutionPlan copy(CommandContext ctx) {
    throw new UnsupportedOperationException();
  }

  boolean canBeCached();

  @Nullable
  default String getStatement() {
    return null;
  }

  default void setStatement(String stm) {
  }

  @Nullable
  default String getGenericStatement() {
    return null;
  }

  default void setGenericStatement(String stm) {
  }
}
