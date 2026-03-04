package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.AbstractUnrollStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ExecutionStepInternal;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Unrolls a MATCH result row into its **individual elements** for `RETURN $elements`.
 * <p>
 * A MATCH result row is a map-like structure `{alias1: record, alias2: record, …}`.
 * This step extracts each non-default alias (skipping auto-generated aliases that start
 * with {@link MatchExecutionPlanner#DEFAULT_ALIAS_PREFIX}) and emits each record as a
 * separate result. This is useful when the caller wants a flat list of all matched
 * nodes rather than the full row structure.
 * <p>
 * ### Example
 * <p>
 * For input row `{p: Person#1, f: Person#2}`, this step produces two output rows:
 * `Person#1` and `Person#2`.
 *
 * @see ReturnMatchPathElementsStep
 */
public class ReturnMatchElementsStep extends AbstractUnrollStep {

  public ReturnMatchElementsStep(CommandContext context, boolean profilingEnabled) {
    super(context, profilingEnabled);
  }

  /**
   * Extracts all user-visible aliases (non-default) from the result row and collects
   * their values as individual results.
   */
  @Override
  protected Collection<Result> unroll(Result res, CommandContext iContext) {
    List<Result> result = new ArrayList<>();
    for (var s : res.getPropertyNames()) {
      if (!s.startsWith(MatchExecutionPlanner.DEFAULT_ALIAS_PREFIX)) {
        var elem = res.getProperty(s);
        if (elem instanceof Identifiable) {
          elem = new ResultInternal(iContext.getDatabaseSession(),
              (Identifiable) elem);
        }
        if (elem instanceof Result) {
          result.add((Result) elem);
        }
        // Non-Result, non-Identifiable values (e.g. primitives) are silently skipped.
        // This can happen if a user alias resolves to a scalar property rather than a
        // record — such values have no meaningful representation as standalone results.
      }
    }
    return result;
  }

  @Override
  public boolean canBeCached() {
    return true;
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    var spaces = ExecutionStepInternal.getIndent(depth, indent);
    return spaces + "+ UNROLL $elements";
  }

  @Override
  public ExecutionStep copy(CommandContext ctx) {
    return new ReturnMatchElementsStep(ctx, profilingEnabled);
  }
}
