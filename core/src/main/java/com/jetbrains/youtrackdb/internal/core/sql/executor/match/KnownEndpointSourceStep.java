package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.AbstractExecutionStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ExecutionStepInternal;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import java.util.Iterator;
import java.util.List;
import javax.annotation.Nullable;

/** Supplies secured scan-order candidates, or counts the records read by the current scan. */
final class KnownEndpointSourceStep extends AbstractExecutionStep {

  @Nullable private final ExecutionStepInternal scan;
  private List<RecordIdInternal> candidates = List.of();
  private KnownEndpointExistsStep.Counters counters = new KnownEndpointExistsStep.Counters();
  private final String sourceClass;

  KnownEndpointSourceStep(CommandContext ctx, String sourceClass,
      @Nullable ExecutionStepInternal scan, boolean profilingEnabled) {
    super(ctx, profilingEnabled);
    this.sourceClass = sourceClass;
    this.scan = scan;
  }

  void bind(List<RecordIdInternal> candidates, KnownEndpointExistsStep.Counters counters) {
    this.candidates = candidates;
    this.counters = counters;
  }

  @Override
  public ExecutionStream internalStart(CommandContext context) {
    if (scan != null) {
      return scan.start(context).map((row, ctx) -> {
        counters.sourceRecordsRead++;
        return row;
      });
    }
    Iterator<RecordIdInternal> iterator = candidates.iterator();
    // Do not use transaction.load: transaction-local records need the scan's read checks too.
    return ExecutionStream.resultIterator(new Iterator<Result>() {
      private Result buffered;

      @Override
      public boolean hasNext() {
        while (buffered == null && iterator.hasNext()) {
          var rid = iterator.next();
          var session = context.getDatabaseSession();
          var identity = new ResultInternal(session, rid);
          if (!MatchEdgeTraverser.matchesClass(context, sourceClass, identity)) {
            continue;
          }
          var record = session.executeReadRecord(rid, null, false);
          if (record != null) {
            counters.candidates++;
            counters.sourceRecordsRead++;
            buffered = new ResultInternal(session, record);
          }
          // Missing and unreadable records are skipped, not treated as end of stream.
        }
        return buffered != null;
      }

      @Override
      public Result next() {
        if (!hasNext()) {
          throw new java.util.NoSuchElementException();
        }
        var result = buffered;
        buffered = null;
        // Match the class scan's loader before the retained SELECT filters evaluate $current.
        context.setSystemVariable(CommandContext.VAR_CURRENT, result);
        return result;
      }
    }).interruptable();
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    return ExecutionStepInternal.getIndent(depth, indent)
        + (scan == null ? "+ LOAD SECURED CANDIDATES IN SCAN ORDER"
            : scan.prettyPrint(0, indent));
  }

  @Override
  public boolean canBeCached() {
    return scan == null || scan.canBeCached();
  }

  @Override
  public ExecutionStep copy(CommandContext ctx) {
    return new KnownEndpointSourceStep(ctx, sourceClass,
        scan == null ? null : (ExecutionStepInternal) scan.copy(ctx), profilingEnabled);
  }

  @Override
  public List<ExecutionStep> getSubSteps() {
    return scan == null ? List.of() : List.of(scan);
  }
}
