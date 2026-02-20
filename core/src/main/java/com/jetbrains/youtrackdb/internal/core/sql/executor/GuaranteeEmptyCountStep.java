package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLProjectionItem;
import java.util.Collections;

/**
 * Intermediate step that guarantees a result row even when the upstream is empty.
 *
 * <p>Used after {@link AggregateProjectionCalculationStep} for bare
 * {@code SELECT count(*) FROM ...} queries (without GROUP BY). In standard SQL,
 * {@code count(*)} over an empty set must return 0 (not zero rows), so this step
 * ensures that behavior.
 *
 * <pre>
 *  Upstream non-empty: pass through the first result, limit to 1
 *  Upstream empty:     produce a synthetic result with { alias: 0 }
 * </pre>
 *
 * @see SelectExecutionPlanner#handleProjections
 */
public class GuaranteeEmptyCountStep extends AbstractExecutionStep {

  /** The projection item whose alias is used for the synthetic zero-count result. */
  private final SQLProjectionItem item;

  public GuaranteeEmptyCountStep(
      SQLProjectionItem oProjectionItem, CommandContext ctx, boolean enableProfiling) {
    super(ctx, enableProfiling);
    this.item = oProjectionItem;
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    if (prev == null) {
      throw new IllegalStateException("GuaranteeEmptyCountStep requires a previous step");
    }

    var upstream = prev.start(ctx);
    if (upstream.hasNext(ctx)) {
      // Pass through, limiting to 1 since count(*) without GROUP BY always yields a single row.
      return upstream.limit(1);
    } else {
      var result = new ResultInternal(ctx.getDatabaseSession());
      // SQL semantics: count(*) over an empty set must return 0, not zero rows.
      result.setProperty(item.getProjectionAliasAsString(), 0L);
      return ExecutionStream.resultIterator(Collections.singleton((Result) result).iterator());
    }
  }

  @Override
  public ExecutionStep copy(CommandContext ctx) {
    return new GuaranteeEmptyCountStep(item.copy(), ctx, profilingEnabled);
  }

  /** Cacheable: projection item is a structural AST node deep-copied per execution. */
  @Override
  public boolean canBeCached() {
    return true;
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    return ExecutionStepInternal.getIndent(depth, indent) + "+ GUARANTEE FOR ZERO COUNT ";
  }
}
