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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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
   * LRU cache of hash tables keyed by back-referenced alias RID. Values are
   * {@code Set<RID>} for Pattern A/D or {@code Map<RID, List<Result>>} for
   * Pattern B. Access-order {@link LinkedHashMap} with automatic eviction of
   * the eldest entry when capacity is exceeded.
   */
  @Nullable private LinkedHashMap<RID, Object> cache;

  BackRefHashJoinStep(
      CommandContext ctx,
      SemiJoinDescriptor descriptor,
      @Nullable EdgeTraversal fallbackEdge,
      boolean profilingEnabled) {
    super(ctx, profilingEnabled);
    assert MatchAssertions.checkNotNull(descriptor, "semi-join descriptor");
    this.descriptor = descriptor;
    this.fallbackEdge = fallbackEdge;
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

    // ChainSemiJoin needs flatMap (fan-out: one source → multiple edges)
    if (localDescriptor instanceof ChainSemiJoin chainDesc) {
      return upstream.flatMap(
          (row, c) -> probeChain(row, localCache, chainDesc, session, c));
    }

    // SingleEdgeSemiJoin and AntiSemiJoin use filter (0 or 1 output per row)
    return upstream.filter(
        (row, c) -> probeRow(row, localCache, localDescriptor, session, c));
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
      DatabaseSessionEmbedded session,
      CommandContext ctx) {
    var backRefRid = resolveBackRefRid(row, desc, ctx);
    if (backRefRid == null) {
      return null;
    }

    var cached = lruCache.get(backRefRid);
    if (cached == null) {
      var ht = buildHashTable(backRefRid, desc, ctx);
      if (ht == null) {
        lruCache.put(backRefRid, BUILD_FAILED);
        return null;
      }
      Object entity = null;
      if (desc.joinMode() == JoinMode.SEMI_JOIN) {
        try {
          entity = session.getActiveTransaction().load(backRefRid);
        } catch (RecordNotFoundException e) {
          // vertex gone
        }
      }
      cached = new CachedBuild(ht, entity);
      lruCache.put(backRefRid, cached);
    }
    if (cached == BUILD_FAILED) {
      return null;
    }
    return (CachedBuild) cached;
  }

  // ---- Probe methods ----

  /**
   * Probes the hash table for a single upstream row (Patterns A and D).
   * On build failure, falls back to nested-loop traversal (Pattern A) or
   * passes the row through (Pattern D — MatchStep handles the NOT IN).
   */
  @Nullable @SuppressWarnings("unchecked")
  private Result probeRow(
      Result row,
      LinkedHashMap<RID, Object> lruCache,
      SemiJoinDescriptor desc,
      DatabaseSessionEmbedded session,
      CommandContext probeCtx) {
    var build = resolveBuild(row, lruCache, desc, session, probeCtx);
    if (build == null) {
      return handleBuildFailure(row, desc, probeCtx);
    }

    var sourceRid = resolveSourceRid(row, desc);
    if (sourceRid == null) {
      return desc.joinMode() == JoinMode.ANTI_JOIN ? row : null;
    }

    var set = (Set<RID>) build.hashTable();
    var found = set.contains(sourceRid);
    return switch (desc.joinMode()) {
      case SEMI_JOIN -> found
          ? new MatchResultRow(
              session, row, desc.targetAlias(), build.backRefEntity())
          : null;
      case ANTI_JOIN -> found ? null : row;
      case INNER_JOIN -> throw new IllegalStateException(
          "INNER_JOIN not supported by BackRefHashJoinStep");
    };
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
    var build = resolveBuild(row, lruCache, chain, session, probeCtx);
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
   * Handles build failure for probeRow (single-row context).
   * Pattern A: falls back to per-row MatchEdgeTraverser.
   * Pattern D: evaluates the stored NOT IN condition per row (the NOT IN
   * was stripped from the MatchStep's WHERE clause at plan time, so the
   * fallback must evaluate it here).
   */
  @Nullable private Result handleBuildFailure(
      Result row, SemiJoinDescriptor desc, CommandContext ctx) {
    if (desc.joinMode() == JoinMode.ANTI_JOIN) {
      // Pattern D fallback: evaluate the stored NOT IN condition per row.
      // The NOT IN was stripped from the MatchStep's filter at plan time,
      // so BackRefHashJoinStep is the sole evaluator.
      var anti = (AntiSemiJoin) desc;
      if (anti.notInCondition() == null) {
        return row;
      }
      // $currentMatch must be set for the NOT IN expression to resolve
      var candidate = row.getProperty(anti.targetAlias());
      ctx.setSystemVariable(CommandContext.VAR_CURRENT_MATCH, candidate);
      boolean passes = anti.notInCondition().evaluate(row, ctx);
      return passes ? row : null;
    }
    // Pattern A: fall back to per-row nested-loop traversal
    if (fallbackEdge == null) {
      return null;
    }
    var traverser = createFallbackTraverser(row, fallbackEdge);
    if (traverser.hasNext(ctx)) {
      return traverser.next(ctx);
    }
    return null;
  }

  /**
   * Falls back to nested-loop traversal for Pattern B (ChainSemiJoin) when
   * the hash table build fails. Creates a {@link MatchEdgeTraverser} that
   * performs the same per-row link bag traversal as {@link MatchStep}.
   */
  private ExecutionStream nestedLoopFallback(
      Result row, CommandContext ctx) {
    if (fallbackEdge == null) {
      return ExecutionStream.empty();
    }
    var traverser = createFallbackTraverser(row, fallbackEdge);
    var results = new ArrayList<Result>();
    while (traverser.hasNext(ctx)) {
      results.add(traverser.next(ctx));
    }
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

  @Nullable private Object buildHashTable(
      RID backRefRid, SemiJoinDescriptor desc, CommandContext ctx) {
    return switch (desc) {
      case SingleEdgeSemiJoin single -> buildSingleEdgeHashTable(
          backRefRid, single, ctx);
      case ChainSemiJoin chain -> buildChainHashTable(backRefRid, chain, ctx);
      case AntiSemiJoin anti -> buildAntiJoinHashTable(backRefRid, anti, ctx);
    };
  }

  @Nullable private Set<RID> buildSingleEdgeHashTable(
      RID backRefRid, SingleEdgeSemiJoin single, CommandContext ctx) {
    var ridSet = TraversalPreFilterHelper.resolveReverseEdgeLookup(
        backRefRid, single.edgeClass(), single.direction(), ctx);
    if (ridSet == null) {
      return null;
    }
    var maxSize = MatchExecutionPlanner.getHashJoinThreshold();
    if (maxSize > 0 && ridSet.size() > maxSize) {
      return null;
    }
    return ridSet;
  }

  @Nullable private Map<RID, List<Result>> buildChainHashTable(
      RID backRefRid, ChainSemiJoin chain, CommandContext ctx) {
    var session = ctx.getDatabaseSession();
    EntityImpl targetEntity;
    try {
      var rec = session.getActiveTransaction().load(backRefRid);
      if (!(rec instanceof EntityImpl entity)) {
        return null;
      }
      targetEntity = entity;
    } catch (RecordNotFoundException e) {
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
      indexRidSet = TraversalPreFilterHelper.resolveIndexToRidSet(
          chain.indexFilter(), ctx);
    } else if (maxSize > 0 && linkBag.size() > maxSize) {
      // No index filter — the full link bag will be iterated, so we can
      // reject early without loading any edge records.
      return null;
    }

    var initialCapacity = hashCapacity(linkBag.size(), maxSize);
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
        var edgeResult = new ResultInternal(session, edgeRec);
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
    return result;
  }

  @Nullable private Set<RID> buildAntiJoinHashTable(
      RID anchorRid, AntiSemiJoin anti, CommandContext ctx) {
    var session = ctx.getDatabaseSession();
    EntityImpl anchorEntity;
    try {
      var rec = session.getActiveTransaction().load(anchorRid);
      if (!(rec instanceof EntityImpl entity)) {
        return null;
      }
      anchorEntity = entity;
    } catch (RecordNotFoundException e) {
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

    var initialCap = hashCapacity(linkBag.size(), maxSize);
    var result = new HashSet<RID>(initialCap);
    for (var pair : linkBag) {
      result.add(pair.secondaryRid());
      if (maxSize > 0 && result.size() > maxSize) {
        return null;
      }
    }
    return result;
  }

  // ---- Utility ----

  /**
   * Computes a HashMap initial capacity for a load factor of 0.75, clamped
   * to avoid integer overflow.
   */
  private static int hashCapacity(long size, long maxSize) {
    var effective = Math.min(size, maxSize > 0 ? maxSize : size);
    return (int) Math.min(effective * 4 / 3 + 1, Integer.MAX_VALUE);
  }

  @Nullable static RID toRid(@Nullable Object value) {
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
    super.close();
  }

  @Override
  public ExecutionStep copy(CommandContext ctx) {
    return new BackRefHashJoinStep(
        ctx, descriptor, fallbackEdge, profilingEnabled);
  }
}
