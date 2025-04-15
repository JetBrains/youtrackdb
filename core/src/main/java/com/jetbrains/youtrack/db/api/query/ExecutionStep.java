package com.jetbrains.youtrack.db.api.query;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.sql.executor.InternalExecutionPlan;
import com.jetbrains.youtrack.db.internal.core.sql.executor.ResultInternal;
import java.util.List;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 *
 */
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
  default Result toResult(@Nullable DatabaseSession db) {
    var result = new ResultInternal((DatabaseSessionInternal) db);
    result.setProperty("name", getName());
    result.setProperty("type", getType());
    result.setProperty(InternalExecutionPlan.JAVA_TYPE, getClass().getName());
    result.setProperty("cost", getCost());
    getSubSteps();
    result.setProperty(
        "subSteps",
        getSubSteps().stream().map(x -> x.toResult(db)).collect(Collectors.toList()));
    result.setProperty("description", getDescription());
    return result;
  }
}
