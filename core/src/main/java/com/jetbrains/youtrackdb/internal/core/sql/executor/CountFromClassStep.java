package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaClassInternal;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ProduceExecutionStream;

/**
 * Hardwired optimization step for {@code SELECT count(*) FROM ClassName} (no WHERE).
 *
 * <p>Instead of scanning all records and counting, this step reads the record count
 * directly from the class metadata in O(1) time, producing a single result with
 * the count value.
 *
 * <p>Cannot be cached because the count may change between executions (and security
 * policies may require per-record filtering in a different session).
 *
 * <pre>
 *  Normal:    [FetchFromClass] --&gt; [CountStep]      (scans all records, O(N))
 *  Optimized: [CountFromClassStep]                   (reads metadata, O(1))
 * </pre>
 *
 * @see SelectExecutionPlanner#handleHardwiredCountOnClass
 */
public class CountFromClassStep extends AbstractExecutionStep {

  private final SchemaClassInternal target;
  private final String alias;

  /**
   * @param targetClass      the schema class whose record count is read from metadata
   * @param alias            the name of the property returned in the result-set
   * @param ctx              the query context
   * @param profilingEnabled true to enable the profiling of the execution (for SQL PROFILE)
   */
  public CountFromClassStep(
      SchemaClassInternal targetClass, String alias, CommandContext ctx, boolean profilingEnabled) {
    super(ctx, profilingEnabled);
    this.target = targetClass;
    this.alias = alias;
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    // Drain predecessor for side effects (this step is a self-contained source).
    if (prev != null) {
      prev.start(ctx).close(ctx);
    }

    return new ProduceExecutionStream(this::produce).limit(1);
  }

  private Result produce(CommandContext ctx) {
    var session = ctx.getDatabaseSession();
    var size = session.computeInTxInternal(tx -> target.count(session));
    var result = new ResultInternal(session);
    result.setProperty(alias, size);
    return result;
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    var spaces = ExecutionStepInternal.getIndent(depth, indent);
    var result = spaces + "+ CALCULATE CLASS SIZE: " + target;
    if (profilingEnabled) {
      result += " (" + getCostFormatted() + ")";
    }
    return result;
  }

  /**
   * Not cacheable: the record count may change between executions, and security
   * policies may require per-record filtering in a different session context.
   */
  @Override
  public boolean canBeCached() {
    return false;
  }

  @Override
  public ExecutionStep copy(CommandContext ctx) {
    return new CountFromClassStep(target, alias, ctx, profilingEnabled);
  }
}
