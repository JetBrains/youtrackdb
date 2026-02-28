package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.parser.LocalResultSet;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIdentifier;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLStatement;
import java.util.ArrayList;
import java.util.List;

/**
 * Per-record LET step that executes a subquery for each row flowing through the
 * pipeline and attaches the result list as metadata on the record.
 *
 * <pre>
 *  SQL:  SELECT *, $orders FROM Customer
 *        LET $orders = (SELECT FROM Order WHERE customer = $parent.$current)
 *
 *  For each Customer record:
 *    1. Execute the subquery with $parent.$current = current record
 *    2. Collect results into a List
 *    3. Store as record.metadata("orders")
 * </pre>
 *
 * <p>A new child context is created per execution to avoid leaking variables
 * between records. Queries with positional parameters ({@code ?}) bypass plan
 * caching to avoid ordinal conflicts.
 *
 * @see SelectExecutionPlanner#handleLet
 */
public class LetQueryStep extends AbstractExecutionStep {

  /** The variable name to store per-record query results under. */
  private final SQLIdentifier varName;

  /** The subquery AST to execute for each record. */
  private final SQLStatement query;

  public LetQueryStep(
      SQLIdentifier varName, SQLStatement query, CommandContext ctx, boolean profilingEnabled) {
    super(ctx, profilingEnabled);
    this.varName = varName;
    this.query = query;
  }

  private ResultInternal calculate(ResultInternal result, CommandContext ctx) {
    var session = ctx.getDatabaseSession();
    var subCtx = new BasicCommandContext();
    subCtx.setDatabaseSession(session);
    // Set outer context as parent so $parent.$current resolves to the current outer record.
    subCtx.setParentWithoutOverridingChild(ctx);
    InternalExecutionPlan subExecutionPlan;
    if (query.toString().contains("?")) {
      // with positional parameters, you cannot know if a parameter has the same ordinal as the
      // one cached
      subExecutionPlan = query.createExecutionPlanNoCache(subCtx, profilingEnabled);
    } else {
      subExecutionPlan = query.createExecutionPlan(subCtx, profilingEnabled);
    }
    result.setMetadata(varName.getStringValue(),
        toList(new LocalResultSet(session, subExecutionPlan)));
    return result;
  }

  private List<Result> toList(LocalResultSet oLocalResultSet) {
    List<Result> result = new ArrayList<>();
    while (oLocalResultSet.hasNext()) {
      result.add(oLocalResultSet.next());
    }
    oLocalResultSet.close();
    return result;
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    if (prev == null) {
      throw new CommandExecutionException(ctx.getDatabaseSession(),
          "Cannot execute a local LET on a query without a target");
    }
    return prev.start(ctx).map(this::mapResult);
  }

  private Result mapResult(Result result, CommandContext ctx) {
    return calculate((ResultInternal) result, ctx);
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    var spaces = ExecutionStepInternal.getIndent(depth, indent);
    return spaces + "+ LET (for each record)\n" + spaces + "  " + varName + " = (" + query + ")";
  }

  /** Cacheable: subquery AST is deep-copied per execution via {@link #copy}. */
  @Override
  public boolean canBeCached() {
    return true;
  }

  @Override
  public ExecutionStep copy(CommandContext ctx) {
    SQLIdentifier varNameCopy = null;
    SQLStatement queryCopy = null;

    if (varName != null) {
      varNameCopy = varName.copy();
    }
    if (query != null) {
      queryCopy = query.copy();
    }

    return new LetQueryStep(varNameCopy, queryCopy, ctx, profilingEnabled);
  }
}
