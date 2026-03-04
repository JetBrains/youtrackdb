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
 * Unrolls a MATCH result row into **all** path elements for `RETURN $pathElements`.
 * <p>
 * Unlike {@link ReturnMatchElementsStep}, which skips auto-generated aliases, this step
 * extracts **every** alias (including those with the
 * {@link MatchExecutionPlanner#DEFAULT_ALIAS_PREFIX}) and emits each as a separate
 * result. This gives a complete list of all records along the matched path, including
 * intermediate nodes that the user did not explicitly name.
 *
 * @see ReturnMatchElementsStep
 */
public class ReturnMatchPathElementsStep extends AbstractUnrollStep {
  public ReturnMatchPathElementsStep(CommandContext context, boolean profilingEnabled) {
    super(context, profilingEnabled);
  }

  /**
   * Extracts all aliases (including auto-generated) from the result row and collects
   * their values as individual results.
   */
  @Override
  protected Collection<Result> unroll(Result res, CommandContext iContext) {
    assert MatchAssertions.checkNotNull(res, "result");
    assert MatchAssertions.checkNotNull(iContext, "command context");

    List<Result> result = new ArrayList<>();
    for (var s : res.getPropertyNames()) {
      var elem = res.getProperty(s);
      if (elem instanceof Identifiable) {
        elem = new ResultInternal(iContext.getDatabaseSession(),
            (Identifiable) elem);
      }
      if (elem instanceof Result) {
        result.add((Result) elem);
      }
      // Non-Result, non-Identifiable values (e.g. primitives) are silently skipped.
      // This can happen if an auto-generated alias resolves to a scalar property
      // rather than a record — such values cannot be emitted as standalone results.
    }
    return result;
  }

  @Override
  public boolean canBeCached() {
    return true;
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    assert depth >= 0 : "depth must be non-negative";
    assert indent >= 0 : "indent must be non-negative";
    var spaces = ExecutionStepInternal.getIndent(depth, indent);
    return spaces + "+ UNROLL $pathElements";
  }

  @Override
  public ExecutionStep copy(CommandContext ctx) {
    return new ReturnMatchPathElementsStep(ctx, profilingEnabled);
  }
}
