package com.jetbrains.youtrack.db.internal.core.sql.executor;

import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.sql.functions.SQLFunction;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLExpression;
import java.util.ArrayList;
import java.util.List;

/**
 * Delegates to an aggregate function for aggregation calculation
 */
public class FuncitonAggregationContext implements AggregationContext {

  private final SQLFunction aggregateFunction;
  private List<SQLExpression> params;

  public FuncitonAggregationContext(SQLFunction function, List<SQLExpression> params) {
    this.aggregateFunction = function;
    this.params = params;
    if (this.params == null) {
      this.params = new ArrayList<>();
    }
  }

  @Override
  public Object getFinalValue() {
    return aggregateFunction.getResult();
  }

  @Override
  public void apply(Result next, CommandContext ctx) {
    List<Object> paramValues = new ArrayList<>();
    for (var expr : params) {
      paramValues.add(expr.execute(next, ctx));
    }
    ctx.setVariable("aggregation", true);
    aggregateFunction.execute(next, null, null, paramValues.toArray(), ctx);
  }
}
