package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.AbstractExecutionStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ExecutionStepInternal;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;

/**
 * Post-processing step that replaces {@link OptionalMatchEdgeTraverser#EMPTY_OPTIONAL}
 * sentinel values with `null` in all result row properties.
 * <p>
 * This step is appended to the plan only when at least one node in the MATCH pattern is
 * marked `optional: true`. During traversal, {@link OptionalMatchEdgeTraverser} stores
 * the sentinel when no match is found, so that the row can be distinguished from a
 * genuine `null` value. Once all traversal steps have completed, this cleanup step
 * normalizes the sentinels back to `null` for the final output.
 *
 * @see OptionalMatchEdgeTraverser
 * @see OptionalMatchStep
 */
public class RemoveEmptyOptionalsStep extends AbstractExecutionStep {
  public RemoveEmptyOptionalsStep(CommandContext ctx, boolean profilingEnabled) {
    super(ctx, profilingEnabled);
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    assert MatchAssertions.checkNotNull(prev, "previous step");

    var upstream = prev.start(ctx);
    return upstream.map(RemoveEmptyOptionalsStep::mapResult);
  }

  /**
   * Scans all properties of the result row and replaces any
   * {@link OptionalMatchEdgeTraverser#EMPTY_OPTIONAL} sentinel with `null`.
   */
  private static Result mapResult(Result result, CommandContext ctx) {
    for (var s : result.getPropertyNames()) {
      if (OptionalMatchEdgeTraverser.isEmptyOptional(result.getProperty(s))) {
        ((ResultInternal) result).setProperty(s, null);
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
    assert depth >= 0 : "depth must be non-negative";
    assert indent >= 0 : "indent must be non-negative";

    var spaces = ExecutionStepInternal.getIndent(depth, indent);
    return spaces + "+ REMOVE EMPTY OPTIONALS";
  }

  @Override
  public ExecutionStep copy(CommandContext ctx) {
    return new RemoveEmptyOptionalsStep(ctx, profilingEnabled);
  }
}
