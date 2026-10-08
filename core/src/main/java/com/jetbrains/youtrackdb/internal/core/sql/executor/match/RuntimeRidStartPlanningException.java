package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

/** Signals that MATCH cannot honor a runtime RID start and needs a literal, uncached plan. */
public final class RuntimeRidStartPlanningException extends RuntimeException {

  public RuntimeRidStartPlanningException(String reason) {
    super(reason);
  }
}
