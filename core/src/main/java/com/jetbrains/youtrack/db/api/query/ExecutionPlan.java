package com.jetbrains.youtrack.db.api.query;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import java.io.Serializable;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


public interface ExecutionPlan extends Serializable {
  @Nonnull
  List<ExecutionStep> getSteps();

  @Nonnull
  String prettyPrint(int depth, int indent);

  @Nonnull
  Result toResult(@Nullable DatabaseSession db);
}
