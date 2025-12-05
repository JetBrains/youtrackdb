package com.jetbrains.youtrackdb.internal.core.query;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSession;
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
  BasicResult toResult(@Nullable DatabaseSession session);
}
