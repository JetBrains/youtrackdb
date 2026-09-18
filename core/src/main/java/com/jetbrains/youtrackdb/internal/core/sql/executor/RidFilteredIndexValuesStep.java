package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.common.util.RawPair;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStreamProducer;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.MultipleExecutionStream;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import javax.annotation.Nullable;

/**
 * Ordered index scan filtered by a pre-computed {@link RidSet}. Extends
 * {@link FetchFromIndexValuesStep} (full index scan in ASC/DESC order) and
 * adds a membership filter: only entries whose RID is present in the
 * {@code ridFilter} set are passed downstream.
 *
 * <p>This is the foundational building block for index-ordered MATCH
 * traversals: the MATCH planner resolves an edge's LinkBag into a RidSet,
 * then creates this step to scan a property index in ORDER BY direction,
 * emitting only edge-reachable records.
 *
 * <p>When {@code ridFilter} is null, behaves identically to
 * {@link FetchFromIndexValuesStep} (zero overhead — the filter check is
 * a simple null guard).
 *
 * <p>Inherits {@code canBeCached() = false} from the parent, which is
 * correct: the RidSet is per-execution (specific to one source vertex's
 * edge set) and cannot be serialized.
 */
public class RidFilteredIndexValuesStep extends FetchFromIndexValuesStep {

  @Nullable private final RidSet ridFilter;

  /**
   * Index entries this scan has advanced over, INCLUDING the ones the RidSet filter dropped.
   *
   * <p>A consumer counting the results it receives cannot see the cost of this step: the filter
   * hides every non-member entry, so a scan that walks a million entries to yield ten looks like
   * a ten-entry scan from the outside. The index-ordered MATCH step reads this counter to run
   * its scan budget against real work rather than against delivered rows.
   */
  private final AtomicLong consumedEntries = new AtomicLong();

  /**
   * Entries this scan may advance over before it stops, or {@code -1} for no bound.
   *
   * <p>The bound has to live HERE rather than in the consumer. A membership filter hides every
   * non-member entry, so one {@code hasNext} on the consumer side pulls the underlying stream
   * until a member passes and can walk the whole index before the consumer regains control. A
   * consumer-side check therefore bounds nothing on a filtered scan. Stopping inside the
   * pipeline, one element after the count is reached, is the only placement that bounds the
   * work the scan can do.
   *
   * <p>The configured bound is immutable. The stream reads the live {@link #activeBudget} gate.
   * A successful pre-emission fill can extend that gate by one {@link #scanBudget} window.
   * The extension keeps continuation work finite.
   */
  private final long scanBudget;

  /**
   * Live entry bound read by the stream. It starts as {@link #scanBudget}.
   * A successful prefill can extend a finite bound once.
   */
  private final AtomicLong activeBudget;

  public RidFilteredIndexValuesStep(
      IndexSearchDescriptor desc,
      boolean orderAsc,
      boolean nullsFirst,
      CommandContext ctx,
      boolean profilingEnabled,
      @Nullable RidSet ridFilter) {
    this(desc, orderAsc, nullsFirst, ctx, profilingEnabled, ridFilter, -1);
  }

  /**
   * @param scanBudget entries this scan may advance over before it stops yielding, or a negative
   *                   value for no bound. Read only on the filtered path; an unfiltered scan
   *                   delivers every entry, so its consumer can count for itself.
   */
  public RidFilteredIndexValuesStep(
      IndexSearchDescriptor desc,
      boolean orderAsc,
      boolean nullsFirst,
      CommandContext ctx,
      boolean profilingEnabled,
      @Nullable RidSet ridFilter,
      long scanBudget) {
    super(desc, orderAsc, nullsFirst, ctx, profilingEnabled);
    this.ridFilter = ridFilter;
    this.scanBudget = scanBudget;
    this.activeBudget = new AtomicLong(scanBudget);
  }

  /** Index entries advanced over so far, filtered-out ones included. */
  public long consumedEntryCount() {
    return consumedEntries.get();
  }

  /**
   * Whether this scan stopped because it reached its budget rather than because the index ran
   * out. A consumer that sees the stream end reads this to tell the two apart. Uses the
   * configured {@link #scanBudget}, not the live gate — so a post-prefill
   * {@link #liftScanBudget()} does not rewrite a prior exhaustion as a clean end.
   */
  public boolean scanBudgetExhausted() {
    return scanBudget >= 0 && consumedEntries.get() > scanBudget;
  }

  /**
   * Extends a finite live bound by one configured window after a successful prefill.
   * The extension saturates at {@link Long#MAX_VALUE} and remains finite.
   * Repeated calls keep the same extended bound.
   */
  public void liftScanBudget() {
    if (scanBudget < 0) {
      return;
    }
    var extendedBudget = scanBudget > Long.MAX_VALUE - scanBudget
        ? Long.MAX_VALUE
        : scanBudget + scanBudget;
    activeBudget.set(extendedBudget);
  }

  /** Returns the live bound for budget boundary verification. */
  long activeScanBudget() {
    return activeBudget.get();
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) {
    if (ridFilter == null) {
      // Unfiltered: every entry reaches the consumer, so the consumer's own count is exact and
      // this counter is not needed.
      return super.internalStart(ctx);
    }

    var prev = this.prev;
    if (prev != null) {
      prev.start(ctx).close(ctx);
    }

    var session = ctx.getDatabaseSession();
    var tx = session.getTransactionInternal();
    tx.preProcessRecordsAndExecuteCallCallbacks();

    List<Stream<RawPair<Object, RID>>> streams =
        init(desc, isOrderAsc(), isNullsFirst(), ctx);
    var filter = this.ridFilter;
    var liveBudget = this.activeBudget;
    var res =
        new ExecutionStreamProducer() {
          private final Iterator<Stream<RawPair<Object, RID>>> iter = streams.iterator();

          @Override
          public ExecutionStream next(CommandContext ctx) {
            // peek counts, takeWhile stops. Both sit ahead of the membership filter, so the
            // count covers dropped entries and the stop fires on the entry that breaks the
            // budget rather than on the next delivered row. Overshoot is one entry per
            // sub-stream, because each sub-stream must consume an element to test the bound.
            // Read liveBudget for each entry. A successful prefill can extend the finite gate.
            Stream<RawPair<Object, RID>> s =
                iter.next()
                    .peek(pair -> consumedEntries.incrementAndGet())
                    .takeWhile(pair -> {
                      var budget = liveBudget.get();
                      return budget < 0 || consumedEntries.get() <= budget;
                    })
                    .filter(pair -> filter.contains(pair.second()));
            return ExecutionStream.resultIterator(
                s.map((RawPair<Object, RID> nextEntry) -> {
                  tx.preProcessRecordsAndExecuteCallCallbacks();
                  return readResult(ctx, nextEntry);
                }).iterator());
          }

          @Override
          public boolean hasNext(CommandContext ctx) {
            tx.preProcessRecordsAndExecuteCallCallbacks();
            return iter.hasNext();
          }

          @Override
          public void close(CommandContext ctx) {
            while (iter.hasNext()) {
              iter.next().close();
            }
          }
        };
    return new MultipleExecutionStream(res);
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    var direction = isOrderAsc() ? "ASC" : "DESC";
    var filterInfo = ridFilter != null
        ? " (filtered by RidSet, size=" + ridFilter.size()
            + (scanBudget >= 0 ? ", budget=" + scanBudget : "") + ")"
        : "";
    return ExecutionStepInternal.getIndent(depth, indent)
        + "+ FETCH FROM INDEX VALUES " + direction + " "
        + desc.getIndex().getName() + filterInfo;
  }
}
