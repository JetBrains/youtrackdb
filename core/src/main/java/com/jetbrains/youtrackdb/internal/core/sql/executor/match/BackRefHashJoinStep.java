package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import com.jetbrains.youtrackdb.api.exception.RecordNotFoundException;
import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.db.record.ridbag.LinkBag;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.sql.executor.AbstractExecutionStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ExecutionStepInternal;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal;
import com.jetbrains.youtrackdb.internal.core.sql.executor.RidSet;
import com.jetbrains.youtrackdb.internal.core.sql.executor.TraversalPreFilterHelper;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Execution step that replaces one or two {@link MatchStep}s for back-reference
 * semi-join edges. Instead of per-row link bag traversal, this step builds a hash
 * table from the back-referenced alias's reverse (or forward) link bag and probes
 * it per upstream row in O(1).
 *
 * <p>The hash table is cached per distinct back-referenced alias binding (RID) in an
 * LRU cache. When the binding changes (e.g., different person in IC5), a new hash
 * table is built. If the build side exceeds the configurable threshold at runtime,
 * the step falls back to per-row nested-loop traversal via {@link MatchEdgeTraverser}
 * (Patterns A/B) or evaluates the stored NOT IN condition per row (Pattern D — the
 * NOT IN is stripped from the MatchStep's WHERE clause at plan time).
 *
 * @see SemiJoinDescriptor
 * @see SingleEdgeSemiJoin
 * @see ChainSemiJoin
 * @see AntiSemiJoin
 */
class BackRefHashJoinStep extends AbstractExecutionStep {

  /** Default LRU cache capacity for per-binding hash tables. */
  private static final int CACHE_CAPACITY = 256;

  /** Sentinel value for cache entries where the build phase failed. */
  private static final Object BUILD_FAILED = new Object();

  /**
   * Pairs a hash table with the loaded back-ref entity, avoiding redundant
   * {@code load()} calls on the hot path. For Pattern A (SEMI_JOIN), the
   * entity is used as the target alias value on probe hits.
   */
  private record CachedBuild(Object hashTable, @Nullable Object backRefEntity) {
  }

  private final SemiJoinDescriptor descriptor;

  /**
   * The edge traversal for runtime fallback when the hash table build fails
   * (Patterns A/B only). Null for Pattern D where the preceding MatchStep
   * handles fallback via its intact AST filter.
   */
  @Nullable private final EdgeTraversal fallbackEdge;

  /**
   * For Pattern B (ChainSemiJoin): the consumed predecessor edge (the
   * {@code .outE('E')} part). When the hash table build fails at runtime,
   * the fallback must traverse both the consumed edge and the main
   * {@link #fallbackEdge} sequentially. Null for Patterns A and D.
   */
  @Nullable private final EdgeTraversal consumedEdge;

  /**
   * LRU cache keyed by back-referenced alias RID. Values are
   * {@link CachedBuild} wrappers (containing the pattern-specific hash table
   * and loaded entity) or the {@link #BUILD_FAILED} sentinel when the build
   * phase failed. Access-order {@link LinkedHashMap} with automatic eviction
   * of the eldest entry when capacity is exceeded.
   */
  @Nullable private LinkedHashMap<RID, Object> cache;

  /**
   * Cache for the {@link ChainSemiJoin#indexFilter()} RidSet. The index
   * query is a pure function of the descriptor and query parameters, so
   * its result is constant across all per-back-ref builds within one
   * query execution. Without this cache, the index would be re-scanned
   * for every distinct back-ref RID (e.g. every friend in IC5) — an
   * O(N_friends × M_index) regression where M can reach hundreds of
   * thousands of entries for range filters like {@code joinDate >= X}.
   */
  @Nullable private RidSet cachedIndexRidSet;

  /**
   * Flag distinguishing "index not yet resolved" (initial state) from
   * "resolved to null" (e.g. cap exceeded in
   * {@link TraversalPreFilterHelper#resolveIndexToRidSet}). When this
   * flag is true and {@link #cachedIndexRidSet} is null, the build
   * proceeds without the index filter.
   */
  private boolean indexRidSetResolved;

  BackRefHashJoinStep(
      CommandContext ctx,
      SemiJoinDescriptor descriptor,
      @Nullable EdgeTraversal fallbackEdge,
      @Nullable EdgeTraversal consumedEdge,
      boolean profilingEnabled) {
    super(ctx, profilingEnabled);
    assert MatchAssertions.checkNotNull(descriptor, "semi-join descriptor");
    this.descriptor = descriptor;
    this.fallbackEdge = fallbackEdge;
    this.consumedEdge = consumedEdge;
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    if (prev == null) {
      throw new IllegalStateException(
          "back-ref hash join step requires a previous step");
    }

    var session = ctx.getDatabaseSession();
    var upstream = prev.start(ctx);

    // Initialize LRU cache lazily
    if (cache == null) {
      cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<RID, Object> eldest) {
          return size() > CACHE_CAPACITY;
        }
      };
    }

    // Capture for null-safety in lambdas (close() may null the field)
    var localCache = cache;
    var localDescriptor = descriptor;

    // ChainSemiJoin needs flatMap (fan-out: one source → multiple edges).
    // Each emitted row binds new aliases (intermediate + target), so
    // $matched must be republished to match MatchEdgeTraverser.next()
    // contract — downstream WHERE clauses resolving $matched.<alias>
    // depend on it.
    if (localDescriptor instanceof ChainSemiJoin chainDesc) {
      return upstream
          .flatMap((row, c) -> probeChain(row, localCache, chainDesc, session, c))
          .map((result, c) -> {
            c.setSystemVariable(CommandContext.VAR_MATCHED, result);
            return result;
          });
    }

    // SingleEdgeSemiJoin uses flatMap to preserve duplicate edges between
    // the same vertex pair (e.g. 3 "Knows" edges from A→B emit 3 rows).
    // The emitted row binds the target alias, so $matched must be
    // republished (see ChainSemiJoin branch above).
    if (localDescriptor instanceof SingleEdgeSemiJoin semiJoin) {
      return upstream
          .flatMap((row, c) -> probeSingleEdge(
              row, localCache, semiJoin, session, c))
          .map((result, c) -> {
            c.setSystemVariable(CommandContext.VAR_MATCHED, result);
            return result;
          });
    }

    // AntiSemiJoin uses filter (0 or 1 output per row). The emitted row is
    // the unchanged upstream row, so $matched (already published by the
    // upstream step for this exact row object) remains correct — no
    // republication needed.
    return upstream.filter(
        (row, c) -> probeAntiJoin(row, localCache, localDescriptor, c));
  }

  // ---- Cache resolution (shared by probeRow and probeChain) ----

  /**
   * Resolves or builds the cached hash table for the given upstream row.
   * Returns a {@link CachedBuild} on success, or {@code null} on build failure.
   */
  @Nullable private CachedBuild resolveBuild(
      Result row,
      LinkedHashMap<RID, Object> lruCache,
      SemiJoinDescriptor desc,
      CommandContext ctx) {
    var backRefRid = resolveBackRefRid(row, desc, ctx);
    if (backRefRid == null) {
      return null;
    }

    var cached = lruCache.get(backRefRid);
    if (cached == null) {
      var build = buildHashTable(backRefRid, desc, ctx);
      if (build == null) {
        lruCache.put(backRefRid, BUILD_FAILED);
        return null;
      }
      lruCache.put(backRefRid, build);
      return build;
    }
    if (cached == BUILD_FAILED) {
      return null;
    }
    return (CachedBuild) cached;
  }

  // ---- Probe methods ----

  /**
   * Probes the hash table for a single upstream row (Pattern A).
   * Returns 0..N rows: N is the number of edges between the source vertex
   * and the back-referenced vertex, preserving semantics when multiple
   * edges of the same class connect the same vertex pair.
   * On build failure, falls back to nested-loop traversal.
   */
  @SuppressWarnings("unchecked")
  private ExecutionStream probeSingleEdge(
      Result row,
      LinkedHashMap<RID, Object> lruCache,
      SingleEdgeSemiJoin desc,
      DatabaseSessionEmbedded session,
      CommandContext probeCtx) {
    var build = resolveBuild(row, lruCache, desc, probeCtx);
    if (build == null) {
      // Fall back to per-row nested-loop traversal, draining all results
      // (not just the first) to preserve multi-edge fan-out semantics.
      return nestedLoopFallback(row, probeCtx);
    }

    var sourceRid = resolveSourceRid(row, desc);
    if (sourceRid == null) {
      return ExecutionStream.empty();
    }

    var map = (Object2IntOpenHashMap<RID>) build.hashTable();
    var edgeCount = map.getInt(sourceRid);
    if (edgeCount == 0) {
      return ExecutionStream.empty();
    }

    if (edgeCount == 1) {
      return ExecutionStream.singleton(new MatchResultRow(
          session, row, desc.targetAlias(), build.backRefEntity()));
    }
    // Create independent row instances for each edge to avoid mutation
    // of a shared mutable MatchResultRow propagating across results.
    var results = new ArrayList<Result>(edgeCount);
    for (int i = 0; i < edgeCount; i++) {
      results.add(new MatchResultRow(
          session, row, desc.targetAlias(), build.backRefEntity()));
    }
    return ExecutionStream.resultIterator(results.iterator());
  }

  /**
   * Probes the hash table for a single upstream row (Pattern D — anti-join).
   * On build failure, evaluates the stored NOT IN condition per row.
   */
  @SuppressWarnings("unchecked")
  @Nullable private Result probeAntiJoin(
      Result row,
      LinkedHashMap<RID, Object> lruCache,
      SemiJoinDescriptor desc,
      CommandContext probeCtx) {
    var build = resolveBuild(row, lruCache, desc, probeCtx);
    if (build == null) {
      return handleAntiJoinBuildFailure(row, (AntiSemiJoin) desc, probeCtx);
    }

    var sourceRid = resolveSourceRid(row, desc);
    if (sourceRid == null) {
      return row; // no source → pass through (anti-join)
    }

    var set = (Set<RID>) build.hashTable();
    return set.contains(sourceRid) ? null : row;
  }

  /**
   * Probe for ChainSemiJoin: returns 0..N rows per upstream row (fan-out).
   * On build failure, falls back to nested-loop traversal via
   * {@link MatchEdgeTraverser}.
   */
  @SuppressWarnings("unchecked")
  private ExecutionStream probeChain(
      Result row,
      LinkedHashMap<RID, Object> lruCache,
      ChainSemiJoin chain,
      DatabaseSessionEmbedded session,
      CommandContext probeCtx) {
    var build = resolveBuild(row, lruCache, chain, probeCtx);
    if (build == null) {
      return nestedLoopFallback(row, probeCtx);
    }

    var sourceRid = resolveSourceRid(row, chain);
    if (sourceRid == null) {
      return ExecutionStream.empty();
    }

    var map = (Map<RID, List<Result>>) build.hashTable();
    var edges = map.get(sourceRid);
    if (edges == null || edges.isEmpty()) {
      return ExecutionStream.empty();
    }

    var results = new ArrayList<Result>(edges.size());
    for (var edgeResult : edges) {
      var withIntermediate = new MatchResultRow(
          session, row, chain.intermediateAlias(), edgeResult);
      var withTarget = new MatchResultRow(
          session, withIntermediate, chain.targetAlias(), build.backRefEntity());
      results.add(withTarget);
    }
    return ExecutionStream.resultIterator(results.iterator());
  }

  // ---- Fallback ----

  /**
   * Handles build failure for Pattern D (anti-join): evaluates the stored
   * NOT IN condition per row. The NOT IN was stripped from the MatchStep's
   * WHERE clause at plan time, so BackRefHashJoinStep is the sole evaluator.
   */
  @Nullable private Result handleAntiJoinBuildFailure(
      Result row, AntiSemiJoin anti, CommandContext ctx) {
    if (anti.notInCondition() == null) {
      return row;
    }
    // $currentMatch must be set for the NOT IN expression to resolve
    var candidate = row.getProperty(anti.targetAlias());
    ctx.setSystemVariable(CommandContext.VAR_CURRENT_MATCH, candidate);
    boolean passes = anti.notInCondition().evaluate(row, ctx);
    return passes ? row : null;
  }

  /**
   * Falls back to nested-loop traversal when the hash table build fails.
   *
   * <p>For Pattern A (SingleEdgeSemiJoin), traverses the single fallback edge.
   * For Pattern B (ChainSemiJoin), traverses the consumed predecessor edge
   * first (outE), then the main fallback edge (inV) for each intermediate
   * result, reproducing the two-edge chain.
   */
  private ExecutionStream nestedLoopFallback(
      Result row, CommandContext ctx) {
    if (fallbackEdge == null) {
      return ExecutionStream.empty();
    }

    // Pattern B: two-edge chain fallback — traverse consumed outE edge
    // first, then inV edge for each intermediate result
    if (consumedEdge != null) {
      var firstTraverser = createFallbackTraverser(row, consumedEdge);
      var results = new ArrayList<Result>();
      while (firstTraverser.hasNext(ctx)) {
        var intermediate = firstTraverser.next(ctx);
        drainTraverser(
            createFallbackTraverser(intermediate, fallbackEdge),
            ctx, results);
      }
      return results.isEmpty()
          ? ExecutionStream.empty()
          : ExecutionStream.resultIterator(results.iterator());
    }

    // Pattern A: single-edge fallback
    return drainToStream(
        createFallbackTraverser(row, fallbackEdge), ctx);
  }

  /** Drains all results from a traverser into the given list. */
  private static void drainTraverser(
      MatchEdgeTraverser traverser, CommandContext ctx,
      List<Result> results) {
    while (traverser.hasNext(ctx)) {
      results.add(traverser.next(ctx));
    }
  }

  /** Drains a traverser into a stream. */
  private static ExecutionStream drainToStream(
      MatchEdgeTraverser traverser, CommandContext ctx) {
    var results = new ArrayList<Result>();
    drainTraverser(traverser, ctx, results);
    return results.isEmpty()
        ? ExecutionStream.empty()
        : ExecutionStream.resultIterator(results.iterator());
  }

  private static MatchEdgeTraverser createFallbackTraverser(
      Result row, EdgeTraversal edge) {
    return edge.out
        ? new MatchEdgeTraverser(row, edge)
        : new MatchReverseEdgeTraverser(row, edge);
  }

  // ---- Back-ref RID resolution ----

  @Nullable private RID resolveBackRefRid(
      Result row, SemiJoinDescriptor desc, CommandContext ctx) {
    return switch (desc) {
      case SingleEdgeSemiJoin single ->
          toRid(single.backRefExpression().execute(row, ctx));
      case ChainSemiJoin chain ->
          toRid(chain.backRefExpression().execute(row, ctx));
      case AntiSemiJoin anti -> {
        var matched = ctx.getVariable("$matched");
        if (matched instanceof Result matchedResult) {
          yield toRid(matchedResult.getProperty(anti.anchorAlias()));
        }
        yield null;
      }
    };
  }

  @Nullable private RID resolveSourceRid(Result row, SemiJoinDescriptor desc) {
    return switch (desc) {
      case SingleEdgeSemiJoin single ->
          toRid(row.getProperty(single.sourceAlias()));
      case ChainSemiJoin chain ->
          toRid(row.getProperty(chain.sourceAlias()));
      case AntiSemiJoin anti ->
          toRid(row.getProperty(anti.targetAlias()));
    };
  }

  // ---- Build methods ----

  @Nullable private CachedBuild buildHashTable(
      RID backRefRid, SemiJoinDescriptor desc, CommandContext ctx) {
    return switch (desc) {
      case SingleEdgeSemiJoin single -> buildSingleEdgeHashTable(
          backRefRid, single, ctx);
      case ChainSemiJoin chain -> buildChainHashTable(backRefRid, chain, ctx);
      case AntiSemiJoin anti -> buildAntiJoinHashTable(backRefRid, anti, ctx);
    };
  }

  /**
   * Loads an entity by RID. Returns {@code null} if the record is missing
   * or is not an {@link EntityImpl}.
   */
  @Nullable private static EntityImpl loadEntity(RID rid, CommandContext ctx) {
    try {
      var rec = ctx.getDatabaseSession().getActiveTransaction().load(rid);
      return rec instanceof EntityImpl entity ? entity : null;
    } catch (RecordNotFoundException e) {
      return null;
    }
  }

  /**
   * Builds a hash table for Pattern A: source vertex RID → edge count.
   * Unlike a plain RidSet, this preserves the number of edges per source
   * vertex, so that multiple edges of the same class between the same
   * vertex pair produce the correct number of result rows.
   */
  @Nullable private CachedBuild buildSingleEdgeHashTable(
      RID backRefRid, SingleEdgeSemiJoin single, CommandContext ctx) {
    var targetEntity = loadEntity(backRefRid, ctx);
    if (targetEntity == null) {
      return null;
    }

    var reversePrefix = "out".equals(single.direction()) ? "in_" : "out_";
    var fieldName = reversePrefix + single.edgeClass();
    var fieldValue = targetEntity.getPropertyInternal(fieldName);
    if (!(fieldValue instanceof LinkBag linkBag)) {
      return null;
    }

    var maxSize = MatchExecutionPlanner.getHashJoinThreshold();
    if (maxSize > 0 && linkBag.size() > maxSize) {
      return null;
    }

    var initialCapacity = hashCapacity(linkBag.size(), maxSize);
    var result = new Object2IntOpenHashMap<RID>(initialCapacity);
    long count = 0;
    for (var pair : linkBag) {
      result.addTo(pair.secondaryRid(), 1);
      count++;
      if (maxSize > 0 && count > maxSize) {
        return null;
      }
    }
    return new CachedBuild(result, targetEntity);
  }

  @Nullable private CachedBuild buildChainHashTable(
      RID backRefRid, ChainSemiJoin chain, CommandContext ctx) {
    var targetEntity = loadEntity(backRefRid, ctx);
    if (targetEntity == null) {
      return null;
    }

    var reversePrefix = "out".equals(chain.direction()) ? "in_" : "out_";
    var fieldName = reversePrefix + chain.edgeClass();
    var fieldValue = targetEntity.getPropertyInternal(fieldName);
    if (!(fieldValue instanceof LinkBag linkBag)) {
      return null;
    }

    var maxSize = MatchExecutionPlanner.getHashJoinThreshold();

    RidSet indexRidSet = null;
    if (chain.indexFilter() != null) {
      // When an index filter is present, the effective entry count after
      // filtering may be well below the threshold — skip early pre-check.
      // The index result is query-level constant, so resolve once and
      // reuse across all per-back-ref builds (YTDB-650 regression fix).
      if (!indexRidSetResolved) {
        cachedIndexRidSet = TraversalPreFilterHelper.resolveIndexToRidSet(
            chain.indexFilter(), ctx);
        indexRidSetResolved = true;
      }
      indexRidSet = cachedIndexRidSet;
    } else if (maxSize > 0 && linkBag.size() > maxSize) {
      // No index filter — the full link bag will be iterated, so we can
      // reject early without loading any edge records.
      return null;
    }

    var session = ctx.getDatabaseSession();
    // When an index filter is present, use its size as a tighter estimate
    // to avoid over-allocating the map for a highly selective filter.
    var sizeEstimate = indexRidSet != null
        ? Math.min(linkBag.size(), indexRidSet.size())
        : linkBag.size();
    var initialCapacity = hashCapacity(sizeEstimate, maxSize);
    var result = new HashMap<RID, List<Result>>(initialCapacity);
    long count = 0;
    for (var pair : linkBag) {
      var edgeRid = pair.primaryRid();
      var sourceVertexRid = pair.secondaryRid();

      if (indexRidSet != null && !indexRidSet.contains(edgeRid)) {
        continue;
      }

      try {
        var edgeRec = session.getActiveTransaction().load(edgeRid);
        if (!(edgeRec instanceof EntityImpl edgeEntity)) {
          continue;
        }
        var edgeResult = new ResultInternal(session, edgeEntity);
        result.computeIfAbsent(sourceVertexRid, k -> new ArrayList<>())
            .add(edgeResult);
        count++;
        if (maxSize > 0 && count > maxSize) {
          return null;
        }
      } catch (RecordNotFoundException e) {
        // Edge record missing — skip
      }
    }
    return new CachedBuild(result, targetEntity);
  }

  @Nullable private CachedBuild buildAntiJoinHashTable(
      RID anchorRid, AntiSemiJoin anti, CommandContext ctx) {
    var anchorEntity = loadEntity(anchorRid, ctx);
    if (anchorEntity == null) {
      return null;
    }

    var prefix = "out".equals(anti.traversalDirection()) ? "out_" : "in_";
    var fieldName = prefix + anti.traversalEdgeClass();
    var fieldValue = anchorEntity.getPropertyInternal(fieldName);
    if (!(fieldValue instanceof LinkBag linkBag)) {
      return null;
    }

    var maxSize = MatchExecutionPlanner.getHashJoinThreshold();
    if (maxSize > 0 && linkBag.size() > maxSize) {
      return null;
    }

    var result = new RidSet();
    long count = 0;
    for (var pair : linkBag) {
      result.add(pair.secondaryRid());
      count++;
      if (maxSize > 0 && count > maxSize) {
        return null;
      }
    }
    return new CachedBuild(result, null);
  }

  // ---- Utility ----

  /**
   * Computes a HashMap initial capacity for a load factor of 0.75, clamped
   * to avoid integer overflow.
   */
  private static int hashCapacity(long size, long maxSize) {
    var effective = Math.min(size, maxSize > 0 ? maxSize : size);
    // Cap before multiplication to prevent long overflow
    effective = Math.min(effective, Integer.MAX_VALUE);
    return (int) (effective * 4 / 3 + 1);
  }

  @Nullable private static RID toRid(@Nullable Object value) {
    if (value instanceof RID rid) {
      return rid;
    }
    if (value instanceof Identifiable identifiable) {
      return identifiable.getIdentity();
    }
    if (value instanceof Result result && result.isEntity()) {
      return result.getIdentity();
    }
    return null;
  }

  @Override
  public boolean canBeCached() {
    return true;
  }

  @Nonnull
  @Override
  public List<ExecutionStep> getSubSteps() {
    return List.of();
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    var spaces = ExecutionStepInternal.getIndent(depth, indent);
    return switch (descriptor) {
      case SingleEdgeSemiJoin single -> spaces
          + "+ BACK-REF HASH JOIN ("
          + single.sourceAlias()
          + " ⋈ $matched." + single.backRefAlias()
          + " via " + single.direction() + "('" + single.edgeClass() + "'))";
      case ChainSemiJoin chain -> spaces
          + "+ BACK-REF HASH JOIN ("
          + chain.sourceAlias()
          + " ⋈ $matched." + chain.backRefAlias()
          + " via " + chain.direction() + "E('" + chain.edgeClass()
          + "').inV()"
          + " aliases: " + chain.intermediateAlias() + ", "
          + chain.targetAlias() + ")";
      case AntiSemiJoin anti -> spaces
          + "+ BACK-REF HASH JOIN ANTI (NOT IN $matched."
          + anti.anchorAlias()
          + "." + anti.traversalDirection() + "('"
          + anti.traversalEdgeClass() + "'))";
    };
  }

  @Override
  public void close() {
    cache = null;
    cachedIndexRidSet = null;
    indexRidSetResolved = false;
    super.close();
  }

  @Override
  public ExecutionStep copy(CommandContext ctx) {
    return new BackRefHashJoinStep(
        ctx, descriptor, fallbackEdge, consumedEdge, profilingEnabled);
  }
}
