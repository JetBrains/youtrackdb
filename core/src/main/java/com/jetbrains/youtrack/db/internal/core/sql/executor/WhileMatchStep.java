package com.jetbrains.youtrack.db.internal.core.sql.executor;

import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLWhereClause;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 *
 */
public class WhileMatchStep extends AbstractUnrollStep {

  private final InternalExecutionPlan body;
  private final SQLWhereClause condition;

  public WhileMatchStep(
      CommandContext ctx,
      SQLWhereClause condition,
      InternalExecutionPlan body,
      boolean profilingEnabled) {
    super(ctx, profilingEnabled);
    this.body = body;
    this.condition = condition;
  }

  @Override
  protected Collection<Result> unroll(Result res, CommandContext iContext) {
    body.reset(iContext);
    List<Result> result = new ArrayList<>();
    var block = body.start();
    while (block.hasNext(iContext)) {
      result.add(block.next(iContext));
    }
    block.close(iContext);
    return result;
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    var indentStep = ExecutionStepInternal.getIndent(1, indent);
    var spaces = ExecutionStepInternal.getIndent(depth, indent);

    var result =
        spaces
            + "+ WHILE\n"
            + spaces
            + indentStep
            + condition.toString()
            + "\n"
            + spaces
            + "  DO\n"
            + body.prettyPrint(depth + 1, indent)
            + "\n"
            + spaces
            + "  END\n";

    return result;
  }
}
