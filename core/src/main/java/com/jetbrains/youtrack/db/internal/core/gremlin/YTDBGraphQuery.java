package com.jetbrains.youtrack.db.internal.core.gremlin;

import com.jetbrains.youtrack.db.api.query.ExecutionPlan;
import com.jetbrains.youtrack.db.api.query.ResultSet;
import com.jetbrains.youtrack.db.internal.core.sql.executor.FetchFromIndexStep;
import com.jetbrains.youtrack.db.internal.core.sql.executor.GlobalLetQueryStep;
import java.util.Map;

public class YTDBGraphQuery implements YTDBGraphBaseQuery {

  protected final Map<String, Object> params;
  protected final String query;
  private final Integer target;

  public YTDBGraphQuery(String query, Map<String, Object> params, Integer target) {
    this.query = query;
    this.params = params;
    this.target = target;
  }

  public String getQuery() {
    return query;
  }

  @Override
  public ResultSet execute(YTDBGraphInternal graph) {
    var session = graph.getUnderlyingDatabaseSession();
    var transaction = session.getActiveTransaction();
    return transaction.query(this.query, this.params);
  }

  @Override
  public ExecutionPlan explain(YTDBGraphInternal graph) {
    var session = graph.getUnderlyingDatabaseSession();
    var transaction = session.getActiveTransaction();
    try (var resultSet = transaction.query(String.format("EXPLAIN %s", query), params)) {
      return resultSet.getExecutionPlan();
    }
  }

  @Override
  public int usedIndexes(YTDBGraphInternal graph) {
    var executionPlan = this.explain(graph);
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
