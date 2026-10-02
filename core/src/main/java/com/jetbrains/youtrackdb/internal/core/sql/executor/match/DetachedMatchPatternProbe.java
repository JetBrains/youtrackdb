package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.AbstractExecutionStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal;
import com.jetbrains.youtrackdb.internal.core.sql.executor.SelectExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import java.util.List;

/** Runs a detached MATCH check without leaving its internal aliases in the outer $matched row. */
final class DetachedMatchPatternProbe {

  private DetachedMatchPatternProbe() {
  }

  static boolean matches(
      Result row, List<AbstractExecutionStep> subSteps, CommandContext outer,
      boolean profilingEnabled) {
    var saved = outer.getSystemVariable(CommandContext.VAR_MATCHED);
    var copy = new ResultInternal(outer.getDatabaseSession());
    for (var property : row.getPropertyNames()) {
      copy.setProperty(property, row.getProperty(property));
    }
    if (row instanceof ResultInternal internal) {
      for (var key : internal.getMetadataKeys()) {
        copy.setMetadata(key, internal.getMetadata(key));
      }
    }

    // A context without $matched cannot be restored by writing null: system variables must
    // never contain null. Seed a child before linking it to the parent so all probe writes
    // stay in the child. With an existing binding, use the outer context and restore it.
    CommandContext probeCtx = outer;
    if (saved == null) {
      var child = new BasicCommandContext();
      child.setSystemVariable(CommandContext.VAR_MATCHED, copy);
      child.setParentWithoutOverridingChild(outer);
      probeCtx = child;
    }
    try {
      probeCtx.setSystemVariable(CommandContext.VAR_MATCHED, copy);
      var plan = new SelectExecutionPlan(probeCtx);
      plan.chain(new RowSourceStep(probeCtx, copy, profilingEnabled));
      subSteps.forEach(plan::chain);
      var stream = plan.start();
      try {
        return stream.hasNext(probeCtx);
      } finally {
        stream.close(probeCtx);
      }
    } finally {
      if (saved != null) {
        outer.setSystemVariable(CommandContext.VAR_MATCHED, saved);
      }
    }
  }

  /** Supplies the copied row already published as $matched when traversal begins. */
  private static final class RowSourceStep extends AbstractExecutionStep {

    private final Result row;

    RowSourceStep(CommandContext ctx, Result row, boolean profilingEnabled) {
      super(ctx, profilingEnabled);
      this.row = row;
    }

    @Override
    public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
      return ExecutionStream.singleton(row);
    }

    @Override
    public ExecutionStep copy(CommandContext ctx) {
      return new RowSourceStep(ctx, row, profilingEnabled);
    }
  }
}
