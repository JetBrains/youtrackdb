package com.jetbrains.youtrackdb.internal.core.sql.executor.cache;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.InternalExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLOrderBy;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * One slot of the transaction result cache: the frozen output of a single populated query plus the
 * AST metadata the delta builder needs to reconcile that output against later in-transaction
 * mutations.
 *
 * <p><b>Lazy populate.</b> An entry is created at cache-miss time wrapping the live {@link
 * ExecutionStream} (already wrapped in an {@link IdempotentExecutionStream} by the cache) together
 * with its plan and context. {@link #results} grows lazily as views pull rows: the first view that
 * needs a tail row drives the stream one step, appends the row to {@link #results}, and records its
 * RID in {@link #cachedRids}; later views replay from the list. When the stream reports no more rows,
 * the entry flips {@link #exhausted}, closes the stream, and from then on every view is a pure list
 * replay.
 *
 * <p><b>Version anchor.</b> {@link #populateMutationVersion} is the transaction's mutation version at
 * the instant the populating execution began. The delta builder considers only mutations whose
 * version exceeds it, because every earlier mutation is already reflected in {@link #results} by the
 * transaction-aware executor.
 *
 * <p><b>Cross-view delta sharing.</b> Two views built on this entry at the same transaction mutation
 * version compute the identical {@code (skipSet, injectList)} delta. The entry caches the latest such
 * pair ({@link #cachedSkipSet} / {@link #cachedInjectList} tagged by {@link #cachedDeltaVersion}) so
 * a second view at the same version reuses it instead of re-walking the mutation log. The cached pair
 * is overwritten when a view at a fresher version triggers a rebuild; older views keep their own
 * reference to the prior pair until they are done. These fields are written by the delta builder in a
 * later step; this foundation establishes their storage and the entry lifecycle.
 *
 * <p><b>Idempotent close.</b> {@link #close} closes the stream and then the plan, null-guarding and
 * early-returning on a second call. The entry is the sole closer of both today: the consumer-facing
 * {@link CachedResultSetView} never closes the shared stream, and the populating {@code
 * LocalResultSet} whose stream this entry adopted is orphaned (never registered in the session's
 * active-query set), so its own close never runs. The stream is still wrapped in an {@link
 * IdempotentExecutionStream} as a defensive guard against a future second owner.
 *
 * <p>Single-transaction state observed only by the owning thread; no field is synchronised.
 */
public final class CachedEntry {

  private final CacheableShape shape;

  /** Rows pulled from the stream so far, in execution order. Grows lazily as views iterate. */
  private final List<Result> results = new ArrayList<>();

  /** RIDs already present in {@link #results}; the delta builder's cache-membership probe. */
  private final Set<RID> cachedRids = new HashSet<>();

  /**
   * The full subclass closure of every class the query reads from, computed once at construction.
   * Stable for the entry's lifetime because schema is immutable within a transaction. The
   * delta builder uses it as the O(1) class filter on every mutation.
   */
  private final Set<String> effectiveFromClasses;

  /** The query's WHERE clause, re-evaluated per mutation by the delta builder. May be {@code null}. */
  @Nullable private final SQLWhereClause whereClause;

  /** The query's ORDER BY, used to position injected rows in the merged view. May be {@code null}. */
  @Nullable private final SQLOrderBy orderBy;

  /**
   * For an Etap-A single-alias MATCH entry, the transform that turns a raw cached/inject record row
   * (an identifiable {@link Result} wrapping the single bound record) into the RETURN tuple the MATCH
   * executor would emit (e.g. {@code RETURN u, u.name}). The entry stores raw, RID-identifiable
   * records so the RECORD-shape skip-set / sorted-merge stay RID-addressable; the projector is applied
   * as the last transform before each row reaches the consumer, and to both ORDER BY merge heads
   * before comparison so a projected ORDER BY column resolves. {@code null} for every non-MATCH shape
   * (plain RECORD / K0_NONE / AGGREGATE_*), whose rows are emitted as-is.
   */
  @Nullable private Function<Result, Result> returnProjector;

  /** Mutation version captured before the populating execution started. */
  private final long populateMutationVersion;

  /**
   * The aggregate replay state for an {@code AGGREGATE_*} entry, seeded by the side-tap at populate and
   * copied-then-replayed per view by {@link DeltaBuilder#buildForAggregate}. {@code null} for every
   * non-aggregate shape (RECORD / K0_NONE / MATCH), which reconcile through {@link #results} and a
   * {@link TxDeltaCursor} instead. Set once at cache-put by the aggregate eager-drive path.
   */
  @Nullable private AggregateState aggregateState;

  // Lazy-populate machinery: the live stream + its plan/context, nulled on close/exhaustion.
  @Nullable private ExecutionStream stream;

  @Nullable private InternalExecutionPlan plan;

  @Nullable private CommandContext ctx;

  /**
   * A replay command context, built lazily the first time a view needs one after the populate-time
   * {@link #ctx} is gone (released on stream exhaustion, or never set for the streamless aggregate /
   * distinct shapes). Memoized so repeated replays of the same entry do not each allocate a fresh
   * context plus param-map copy. Sound to reuse across replays because the entry's key fixes its
   * {@code :param} bindings for its whole lifetime and the wired delta-path shapes read no non-param
   * context state (see {@code DatabaseSessionEmbedded.buildView} Javadoc); the same reuse-a-context
   * pattern already holds for the live populate context on RECORD entries. Released at {@link #close}.
   */
  @Nullable private CommandContext replayCtx;

  private boolean exhausted;

  // Cross-view delta-pair cache (written by the delta builder in a later step).
  @Nullable private Set<RID> cachedSkipSet;

  @Nullable private List<Result> cachedInjectList;

  private long cachedDeltaVersion = -1;

  // Per-entry record-cap guard. The cache installs the cap and the overflow callback at put time; the
  // view's row append checks the cap so an entry whose populate crosses it removes itself from the
  // cache and routes its key to the non-cacheable set, while the consuming view still receives every
  // row from the live stream. maxRecordsPerEntry stays at MAX_VALUE for an entry never put through the
  // cache (the cap then never fires). overflowed latches the one-shot eviction so it runs exactly once.
  private int maxRecordsPerEntry = Integer.MAX_VALUE;

  @Nullable private Runnable onOverflow;

  private boolean overflowed;

  /**
   * Number of live views iterating this entry. A pinned ({@code > 0}) entry is exempt from every
   * cache-removal path — LRU eviction, the K0_NONE / multi-alias MATCH invalidation gate, bulk-DML
   * {@code invalidateAll}, and per-entry overflow — so a mid-iteration view never loses rows: a removal
   * that finds the entry pinned defers the stream close to {@link #decrementLiveViewCount} via {@link
   * #markCloseWhenUnpinned}. Incremented/decremented by the view.
   */
  private int liveViewCount;

  /**
   * Set by the cache when it removes this entry from the map while a view still pins it. The stream
   * close is then deferred to the last pin release so a removal never truncates a mid-iteration view:
   * {@link #decrementLiveViewCount} closes the entry the moment the pin count reaches zero. The cache
   * also parks the entry in its {@code closePending} set so a transaction-end {@code clear()} still
   * closes it if the view is abandoned and never releases its pin. Never reset — an entry removed from
   * the cache is never re-stored.
   */
  private boolean closeWhenUnpinned;

  public CachedEntry(
      @Nonnull CacheableShape shape,
      @Nonnull Set<String> effectiveFromClasses,
      @Nullable SQLWhereClause whereClause,
      @Nullable SQLOrderBy orderBy,
      @Nullable ExecutionStream stream,
      @Nullable InternalExecutionPlan plan,
      @Nullable CommandContext ctx,
      long populateMutationVersion) {
    this.shape = shape;
    // Defensive copy so a later caller mutation cannot change the frozen class filter.
    this.effectiveFromClasses = Set.copyOf(effectiveFromClasses);
    this.whereClause = whereClause;
    this.orderBy = orderBy;
    this.stream = stream;
    this.plan = plan;
    this.ctx = ctx;
    this.populateMutationVersion = populateMutationVersion;
  }

  /**
   * Computes the {@link #effectiveFromClasses} closure: the named class plus every subclass,
   * each by name. Returns an unmodifiable set. A {@code null} class yields the empty set so a target
   * whose schema class cannot be resolved produces an entry whose delta filter matches nothing rather
   * than throwing.
   */
  public static Set<String> computeEffectiveFromClasses(@Nullable SchemaClass fromClass) {
    if (fromClass == null) {
      return Set.of();
    }
    var names = new HashSet<String>();
    names.add(fromClass.getName());
    for (var sub : fromClass.getAllSubclasses()) {
      names.add(sub.getName());
    }
    return Set.copyOf(names);
  }

  /**
   * Computes the multi-alias MATCH read-class closure for the class-scoped version gate: the union of
   * every alias node class and every traversal-edge class, each expanded to its full subclass closure.
   * A {@code null} class in either collection contributes nothing, because an unresolvable label names
   * no live records and so its mutations cannot change the result. The returned set is the {@link
   * #effectiveFromClasses} a multi-alias MATCH entry carries: a post-populate mutation to any class in
   * it invalidates the entry, since a created, deleted, or changed record of a pattern class may add,
   * drop, or alter a matched tuple. A multi-alias MATCH whose closure would be empty is not cached (the
   * gate could never fire), so an entry that reaches this builder always has a non-empty result.
   */
  public static Set<String> computeMatchEffectiveFromClasses(
      @Nonnull Collection<SchemaClass> aliasClasses,
      @Nonnull Collection<SchemaClass> edgeClasses) {
    var names = new HashSet<String>();
    addSubclassClosure(aliasClasses, names);
    addSubclassClosure(edgeClasses, names);
    return Set.copyOf(names);
  }

  /** Adds each non-null class and its subclass closure (by name) to {@code names}. */
  private static void addSubclassClosure(
      @Nonnull Collection<SchemaClass> classes, @Nonnull Set<String> names) {
    for (var clazz : classes) {
      if (clazz == null) {
        continue;
      }
      names.add(clazz.getName());
      for (var sub : clazz.getAllSubclasses()) {
        names.add(sub.getName());
      }
    }
  }

  public CacheableShape getShape() {
    return shape;
  }

  public List<Result> getResults() {
    return results;
  }

  public Set<RID> getCachedRids() {
    return cachedRids;
  }

  public Set<String> getEffectiveFromClasses() {
    return effectiveFromClasses;
  }

  @Nullable public SQLWhereClause getWhereClause() {
    return whereClause;
  }

  @Nullable public SQLOrderBy getOrderBy() {
    return orderBy;
  }

  /**
   * The Etap-A MATCH RETURN projector, or {@code null} for a non-MATCH entry. Applied by {@link
   * CachedResultSetView} at the emit boundary and before each ORDER BY merge comparison.
   */
  @Nullable public Function<Result, Result> getReturnProjector() {
    return returnProjector;
  }

  /** Sets the Etap-A MATCH RETURN projector at cache-put. {@code null} leaves rows unprojected. */
  public void setReturnProjector(@Nullable Function<Result, Result> returnProjector) {
    this.returnProjector = returnProjector;
  }

  public long getPopulateMutationVersion() {
    return populateMutationVersion;
  }

  /**
   * The aggregate replay state for an {@code AGGREGATE_*} entry, or {@code null} for any other shape.
   * {@link DeltaBuilder#buildForAggregate} copies it and replays the post-populate mutations onto the
   * copy; {@link CachedResultSetView} returns the copy's {@link AggregateState#toResult}.
   */
  @Nullable public AggregateState getAggregateState() {
    return aggregateState;
  }

  /** Sets the aggregate replay state at cache-put for an {@code AGGREGATE_*} entry. */
  public void setAggregateState(@Nullable AggregateState aggregateState) {
    this.aggregateState = aggregateState;
  }

  @Nullable public ExecutionStream getStream() {
    return stream;
  }

  @Nullable public CommandContext getCtx() {
    return ctx;
  }

  /**
   * Returns the memoized {@link #replayCtx}, building it from {@code factory} on first use. Callers use
   * this only when {@link #getCtx} is {@code null} (populate context gone). The factory runs at most
   * once per entry.
   */
  @Nonnull
  public CommandContext getOrCreateReplayCtx(
      @Nonnull java.util.function.Supplier<CommandContext> factory) {
    if (replayCtx == null) {
      replayCtx = factory.get();
    }
    return replayCtx;
  }

  /**
   * The execution plan backing the live stream. The entry holds a strong reference to it for the
   * stream's lifetime (released on {@link #close}) so the plan and its resources stay reachable while
   * any view may still drive the stream.
   */
  @Nullable public InternalExecutionPlan getPlan() {
    return plan;
  }

  public boolean isExhausted() {
    return exhausted;
  }

  public void setExhausted(boolean exhausted) {
    this.exhausted = exhausted;
  }

  @Nullable public Set<RID> getCachedSkipSet() {
    return cachedSkipSet;
  }

  public void setCachedSkipSet(@Nullable Set<RID> cachedSkipSet) {
    this.cachedSkipSet = cachedSkipSet;
  }

  @Nullable public List<Result> getCachedInjectList() {
    return cachedInjectList;
  }

  public void setCachedInjectList(@Nullable List<Result> cachedInjectList) {
    this.cachedInjectList = cachedInjectList;
  }

  public long getCachedDeltaVersion() {
    return cachedDeltaVersion;
  }

  public void setCachedDeltaVersion(long cachedDeltaVersion) {
    this.cachedDeltaVersion = cachedDeltaVersion;
  }

  public int getLiveViewCount() {
    return liveViewCount;
  }

  public void incrementLiveViewCount() {
    liveViewCount++;
  }

  public void decrementLiveViewCount() {
    if (liveViewCount > 0) {
      liveViewCount--;
    }
    if (liveViewCount == 0 && closeWhenUnpinned) {
      // The cache removed this entry from the map while a view still held it, deferring the close to
      // the last pin release. This is that release: close the deferred stream/plan now. Idempotent
      // with the natural drain-close (pullOneFromStream) and the tx-end clear() sweep.
      close();
    }
  }

  /**
   * Marks this entry for deferred close: the cache calls it when it removes a still-pinned entry from
   * the map, so the stream close happens at the last {@link #decrementLiveViewCount} instead of under
   * a mid-iteration view. Idempotent.
   */
  public void markCloseWhenUnpinned() {
    closeWhenUnpinned = true;
  }

  /** Rows cached so far; the LRU eviction decision reads this. */
  public int sizeHint() {
    return results.size();
  }

  /**
   * Installs the per-entry record cap and the overflow callback the cache fires when an append crosses
   * it. Called once by {@link QueryResultCache#put} when the entry is stored. A cap of {@code
   * Integer.MAX_VALUE} (the default for an entry never stored) disables the check.
   */
  public void setOverflowGuard(int maxRecordsPerEntry, @Nonnull Runnable onOverflow) {
    this.maxRecordsPerEntry = maxRecordsPerEntry;
    this.onOverflow = onOverflow;
  }

  /**
   * Appends a freshly pulled stream row to the shared cache ({@link #results} and, for an identifiable
   * row, {@link #cachedRids}) and enforces the per-entry record cap. The append always happens: the
   * already-iterating view positions its cache cursor by index into {@link #results}, so dropping rows
   * mid-stream would break the merge. The cap instead governs cache <i>membership</i>: the first append
   * that pushes the entry past {@code maxRecordsPerEntry} fires the overflow callback exactly once,
   * which removes the entry from the cache and routes its key to the non-cacheable set. The entry is
   * then unreachable for any future query (no second view re-runs the over-cap result) and is released
   * when this view closes, while the consuming view still receives every row. So an over-cap result is
   * served once, end to end, but never retained for reuse, which is what the cap exists to bound.
   */
  public void recordPulledRow(@Nonnull Result r) {
    results.add(r);
    if (r.isIdentifiable()) {
      var rid = r.getIdentity();
      // isIdentifiable() implies a non-null RID; a null would corrupt the cachedRids membership the
      // delta builder probes. Assert it (no-op without -ea) so a broken invariant fails loudly in tests.
      assert rid != null : "isIdentifiable() result has a null getIdentity()";
      cachedRids.add(rid);
    }
    if (!overflowed && results.size() > maxRecordsPerEntry) {
      // The entry just crossed its per-entry record cap. Evict it from the cache once: it stays
      // reachable to this view (which holds it directly) so iteration continues, but no future query
      // can hit or re-populate it. Fired after the append so the boundary is cap+1, matching the knob's
      // documented "when populating crosses this cap" semantics.
      overflowed = true;
      if (onOverflow != null) {
        onOverflow.run();
      }
    }
  }

  /**
   * Releases the live execution stream and its plan. Idempotent: the first call closes the stream
   * then the plan through its context and nulls the stream/plan/context references; a second call
   * sees the nulled stream and returns. Safe to reach from both the cache clear and the
   * consumer-facing result set close.
   *
   * <p>The populating {@code LocalResultSet} whose stream this entry adopted is never registered in
   * the session's active-query set (only the consumer-facing view is), so its own {@code close()} —
   * which would otherwise close the plan — never fires. This entry is therefore the sole closer of
   * the plan, mirroring the uncached path's {@code LocalResultSet.close()} order (stream first, then
   * {@code executionPlan.close()}). Skipping the plan close would leave each cached query's step
   * chain un-closed (the per-step {@code alreadyClosed} flip and any step-level resource release
   * that the stream close does not duplicate would never run).
   */
  public void close() {
    if (stream == null) {
      // Already closed (or never had a live stream); nothing to release.
      plan = null;
      ctx = null;
      replayCtx = null;
      return;
    }
    var planToClose = plan;
    try {
      stream.close(ctx);
    } finally {
      // Close the plan after the stream, mirroring LocalResultSet.close(). Run in a finally so a
      // stream-close failure still releases the plan; null the references afterwards so a second
      // close is a no-op.
      try {
        if (planToClose != null) {
          planToClose.close();
        }
      } finally {
        stream = null;
        plan = null;
        ctx = null;
        replayCtx = null;
      }
    }
  }
}
