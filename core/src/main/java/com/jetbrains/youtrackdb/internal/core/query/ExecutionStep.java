package com.jetbrains.youtrackdb.internal.core.query;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.sql.executor.InternalExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal;
import java.util.List;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public interface ExecutionStep {

  @Nonnull
  String getName();

  @Nonnull
  String getType();

  @Nullable
  String getDescription();

  @Nonnull
  List<ExecutionStep> getSubSteps();

  /**
   * returns the absolute cost (in nanoseconds) of the execution of this step
   *
   * @return the absolute cost (in nanoseconds) of the execution of this step, -1 if not calculated
   */
  default long getCost() {
    return -1L;
  }

  @Nonnull
  default Result toResult(@Nullable DatabaseSessionEmbedded session) {
    var result = new ResultInternal((DatabaseSessionEmbedded) session);
    result.setProperty("name", getName());
    result.setProperty("type", getType());
    result.setProperty(InternalExecutionPlan.JAVA_TYPE, getClass().getName());
    result.setProperty("cost", getCost());
    getSubSteps();
    result.setProperty(
        "subSteps",
        getSubSteps().stream().map(x -> x.toResult(session)).collect(Collectors.toList()));
    result.setProperty("description", getDescription());
    return result;
  }
}
