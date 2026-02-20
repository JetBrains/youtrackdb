package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ProduceExecutionStream;

/**
 * Source step that generates a specified number of empty result records.
 *
 * <p>Used for queries without a FROM clause (e.g. {@code SELECT 1+1, sysdate()}).
 * A single empty record is produced so that the downstream projection step can
 * evaluate constant expressions exactly once.
 *
 * <p>Each generated record is also set as the {@code $current} context variable.
 *
 * @see SelectExecutionPlanner#handleNoTarget
 */
public class EmptyDataGeneratorStep extends AbstractExecutionStep {

  /** Number of empty records to generate (typically 1). */
  private final int size;

  public EmptyDataGeneratorStep(int size, CommandContext ctx, boolean profilingEnabled) {
    super(ctx, profilingEnabled);
    this.size = size;
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    // Drain predecessor for side effects before generating empty records.
    if (prev != null) {
      prev.start(ctx).close(ctx);
    }

    // ProduceExecutionStream invokes the factory on each next() call (infinite stream).
    // .limit(size) caps the output.
    return new ProduceExecutionStream(EmptyDataGeneratorStep::create).limit(size);
  }

  private static Result create(CommandContext ctx) {
    var result = new ResultInternal(ctx.getDatabaseSession());
    ctx.setVariable("$current", result);
    return result;
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    var spaces = ExecutionStepInternal.getIndent(depth, indent);
    var result = spaces + "+ GENERATE " + size + " EMPTY " + (size == 1 ? "RECORD" : "RECORDS");
    if (profilingEnabled) {
      result += " (" + getCostFormatted() + ")";
    }
    return result;
  }

  /** Cacheable: the step only generates empty records -- no external state dependency. */
  @Override
  public boolean canBeCached() {
    return true;
  }

  @Override
  public ExecutionStep copy(CommandContext ctx) {
    return new EmptyDataGeneratorStep(size, ctx, profilingEnabled);
  }
}
