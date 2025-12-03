package com.jetbrains.youtrackdb.internal.core.gremlin;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.query.ResultSet;
import com.jetbrains.youtrackdb.internal.core.sql.executor.FetchFromIndexStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.GlobalLetQueryStep;
import java.util.Map;

public class YTDBGraphQuery implements YTDBGraphBaseQuery {

  protected final Map<String, Object> params;
  protected final String query;
  private final int target;

  public YTDBGraphQuery(String query, Map<String, Object> params, Integer target) {
    this.query = query;
    this.params = params;
    this.target = target;
  }

  @Override
  public ResultSet execute(DatabaseSessionEmbedded session) {
    var transaction = session.getActiveTransaction();
    return transaction.query(this.query, this.params);
  }

  @Override
  public ExecutionPlan explain(DatabaseSessionEmbedded session) {
    var transaction = session.getActiveTransaction();
    try (var resultSet = transaction.query(String.format("EXPLAIN %s", query), params)) {
      return resultSet.getExecutionPlan();
    }
  }

  @Override
  public int usedIndexes(DatabaseSessionEmbedded session) {
    var executionPlan = this.explain(session);
    if (executionPlan == null) {
      return 0;
    }

    if (target > 1) {
      return executionPlan.getSteps().stream()
          .filter(step -> (step instanceof GlobalLetQueryStep))
          .map(
              s -> {
                var subStep = (GlobalLetQueryStep) s;
                return (int)
                    subStep.getSubExecutionPlans().stream()
                        .filter(
                            plan ->
                                plan.getSteps().stream()
                                    .anyMatch((step) -> step instanceof FetchFromIndexStep))
                        .count();
              })
          .reduce(0, Integer::sum);
    } else {
      return (int)
          executionPlan.getSteps().stream()
              .filter((step) -> step instanceof FetchFromIndexStep)
              .count();
    }
  }
}
