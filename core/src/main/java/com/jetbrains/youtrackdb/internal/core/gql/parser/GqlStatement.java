package com.jetbrains.youtrackdb.internal.core.gql.parser;

import com.jetbrains.youtrackdb.internal.core.gql.executor.GqlExecutionContext;
import com.jetbrains.youtrackdb.internal.core.gql.executor.GqlExecutionPlan;

/// Base interface for GQL statements.
///
/// Each statement type (MATCH, RETURN, CREATE, etc.) implements this interface
/// and provides its own execution plan creation logic.
public interface GqlStatement {

  /// Create an execution plan for this statement.
  GqlExecutionPlan createExecutionPlan(GqlExecutionContext ctx);
}
