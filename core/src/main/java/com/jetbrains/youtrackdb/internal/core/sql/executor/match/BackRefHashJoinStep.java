package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import com.jetbrains.youtrackdb.api.exception.RecordNotFoundException;
import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
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
 * the step returns {@code null} from the build method, and the caller (the planner's
 * existing {@link MatchStep} path) handles the edge via nested-loop traversal.
 *
 * <p>Pattern A (single-edge semi-join): builds {@code Set<RID>} from the reverse
 * link bag. Probe: {@code set.contains(source.@rid)} → keep on hit (SEMI_JOIN).
 *
 * @see SemiJoinDescriptor
 * @see SingleEdgeSemiJoin
 */
class BackRefHashJoinStep extends AbstractExecutionStep {

  /** Default LRU cache capacity for per-binding hash tables. */
  private static final int CACHE_CAPACITY = 256;

  private final SemiJoinDescriptor descriptor;

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
      boolean profilingEnabled) {
    super(ctx, profilingEnabled);
    assert MatchAssertions.checkNotNull(descriptor, "semi-join descriptor");
    this.descriptor = descriptor;
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

  /**
   * Probes the hash table for a single upstream row. Resolves the back-ref
   * RID from {@code $matched}, looks up or builds the hash table, then probes
   * with the source alias's RID.
   */
  @Nullable @SuppressWarnings("unchecked")
  private Result probeRow(
      Result row,
      LinkedHashMap<RID, Object> lruCache,
      SemiJoinDescriptor desc,
      DatabaseSessionEmbedded session,
      CommandContext probeCtx) {
    // Resolve back-ref alias RID from $matched
    var backRefRid = resolveBackRefRid(row, desc, probeCtx);
    if (backRefRid == null) {
      // Cannot resolve back-ref — conservative: skip row for SEMI, keep for ANTI
      return desc.joinMode() == JoinMode.ANTI_JOIN ? row : null;
    }

    // Look up or build hash table for this binding
    var hashTable = lruCache.get(backRefRid);
    if (hashTable == null && !lruCache.containsKey(backRefRid)) {
      hashTable = buildHashTable(backRefRid, desc, probeCtx);
      lruCache.put(backRefRid, hashTable);
    }

    if (hashTable == null) {
      // Build failed or returned null (threshold exceeded, no link bag, etc.)
      // Conservative: skip row for SEMI, keep for ANTI
      return desc.joinMode() == JoinMode.ANTI_JOIN ? row : null;
    }

    // Probe: extract source RID from upstream row
    var sourceRid = resolveSourceRid(row, desc);
    if (sourceRid == null) {
      return desc.joinMode() == JoinMode.ANTI_JOIN ? row : null;
    }

    var set = (Set<RID>) hashTable;
    var found = set.contains(sourceRid);
    return switch (desc.joinMode()) {
      case SEMI_JOIN -> found
          ? new MatchResultRow(session, row, desc.targetAlias(),
              resolveTargetValue(row, desc, probeCtx, session))
          : null;
      case ANTI_JOIN -> found ? null : row;
      case INNER_JOIN -> throw new IllegalStateException(
          "INNER_JOIN not supported by BackRefHashJoinStep");
    };
  }

  /**
   * Resolves the back-referenced alias's RID from the upstream row's $matched
   * context. For SingleEdgeSemiJoin, evaluates the backRefExpression.
   */
  @Nullable private RID resolveBackRefRid(
      Result row, SemiJoinDescriptor desc, CommandContext ctx) {
    if (desc instanceof SingleEdgeSemiJoin single) {
      var value = single.backRefExpression().execute(row, ctx);
      return toRid(value);
    }
    if (desc instanceof ChainSemiJoin chain) {
      var value = chain.backRefExpression().execute(row, ctx);
      return toRid(value);
    }
    if (desc instanceof AntiSemiJoin anti) {
      // Resolve $matched.X — the anchor alias vertex RID
      var matched = ctx.getVariable("$matched");
      if (matched instanceof Result matchedResult) {
        var value = matchedResult.getProperty(anti.anchorAlias());
        return toRid(value);
      }
      return null;
    }
    return null;
  }

  /**
   * Extracts the source alias RID from the upstream row (the probe key).
   */
  @Nullable private RID resolveSourceRid(Result row, SemiJoinDescriptor desc) {
    if (desc instanceof SingleEdgeSemiJoin single) {
      var value = row.getProperty(single.sourceAlias());
      return toRid(value);
    }
    if (desc instanceof ChainSemiJoin chain) {
      var value = row.getProperty(chain.sourceAlias());
      return toRid(value);
    }
    if (desc instanceof AntiSemiJoin anti) {
      // The probe key is the candidate vertex (target alias) — the vertex
      // just produced by the MatchStep traversal.
      var value = row.getProperty(anti.targetAlias());
      return toRid(value);
    }
    return null;
  }

  /**
   * Resolves the target alias value to attach to the result row on a SEMI_JOIN
   * hit. For Pattern A, the target is the back-referenced vertex itself.
   */
  @Nullable private Object resolveTargetValue(
      Result row, SemiJoinDescriptor desc, CommandContext ctx,
      DatabaseSessionEmbedded session) {
    if (desc instanceof SingleEdgeSemiJoin) {
      var backRefRid = resolveBackRefRid(row, desc, ctx);
      if (backRefRid == null) {
        return null;
      }
      try {
        return session.getActiveTransaction().load(backRefRid);
      } catch (Exception e) {
        return null;
      }
    }
    return null;
  }

  /**
   * Builds the hash table for a given back-ref binding RID. For Pattern A
   * (SingleEdgeSemiJoin), reads the reverse link bag and materializes a
   * {@code Set<RID>} of opposite-side vertex RIDs.
   *
   * @return the hash table, or {@code null} if the build fails or exceeds
   *     the threshold
   */
  @Nullable private Object buildHashTable(
      RID backRefRid,
      SemiJoinDescriptor desc,
      CommandContext ctx) {
    if (desc instanceof SingleEdgeSemiJoin single) {
      return buildSingleEdgeHashTable(backRefRid, single, ctx);
    }
    if (desc instanceof ChainSemiJoin chain) {
      return buildChainHashTable(backRefRid, chain, ctx);
    }
    if (desc instanceof AntiSemiJoin anti) {
      return buildAntiJoinHashTable(backRefRid, anti, ctx);
    }
    return null;
  }

  /**
   * Pattern A build: reads the reverse link bag of the back-referenced vertex
   * and collects opposite-side vertex RIDs into a {@code HashSet<RID>}.
   */
  @Nullable private Set<RID> buildSingleEdgeHashTable(
      RID backRefRid, SingleEdgeSemiJoin single, CommandContext ctx) {
    // Use TraversalPreFilterHelper to read the reverse link bag
    var ridSet = TraversalPreFilterHelper.resolveReverseEdgeLookup(
        backRefRid, single.edgeClass(), single.direction(), ctx);
    if (ridSet == null) {
      return null; // link bag not found or exceeds cap
    }

    // Convert RidSet (bitmap-backed) to HashSet<RID> for O(1) probe
    var maxSize = MatchExecutionPlanner.getHashJoinThreshold();
    var result = new HashSet<RID>();
    for (var rid : ridSet) {
      result.add(rid);
      if (maxSize > 0 && result.size() > maxSize) {
        return null; // threshold exceeded — fall back
      }
    }
    return result;
  }

  /**
   * Pattern B build: reads the reverse link bag of the back-referenced vertex,
   * optionally filters by an index RidSet, loads matching edge records, and
   * groups them by source vertex RID into a {@code Map<RID, List<Result>>}.
   *
   * <p>The map key is the source vertex RID (from where the original outE
   * traversal starts). The value is a list of edge records that connect that
   * source to the back-referenced vertex via the given edge class.
   */
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

    // Read the reverse link bag
    var reversePrefix = "out".equals(chain.direction()) ? "in_" : "out_";
    var fieldName = reversePrefix + chain.edgeClass();
    var fieldValue = targetEntity.getPropertyInternal(fieldName);
    if (!(fieldValue instanceof LinkBag linkBag)) {
      return null;
    }

    var maxSize = MatchExecutionPlanner.getHashJoinThreshold();
    if (linkBag.size() > maxSize) {
      return null; // too large — fall back
    }

    // Optionally resolve index pre-filter for the intermediate edge
    RidSet indexRidSet = null;
    if (chain.indexFilter() != null) {
      indexRidSet = TraversalPreFilterHelper.resolveIndexToRidSet(
          chain.indexFilter(), ctx);
    }

    var result = new HashMap<RID, List<Result>>();
    long count = 0;
    for (var pair : linkBag) {
      var edgeRid = pair.primaryRid();
      var sourceVertexRid = pair.secondaryRid();

      // Apply index filter if present (filters edge RIDs)
      if (indexRidSet != null && !indexRidSet.contains(edgeRid)) {
        continue;
      }

      // Load the edge record
      try {
        var edgeRec = session.getActiveTransaction().load(edgeRid);
        var edgeResult = new ResultInternal(session, edgeRec);
        result.computeIfAbsent(sourceVertexRid, k -> new ArrayList<>())
            .add(edgeResult);
        count++;
        if (maxSize > 0 && count > maxSize) {
          return null; // threshold exceeded
        }
      } catch (RecordNotFoundException e) {
        // Edge record missing — skip
      }
    }
    return result;
  }

  /**
   * Pattern D build: reads the forward link bag of the anchor vertex
   * ({@code X.out('E')}) and collects opposite-side vertex RIDs into a
   * {@code HashSet<RID>} — the exclusion set. The probe discards upstream
   * rows whose candidate RID appears in this set.
   */
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

    // Forward link bag: out_E or in_E depending on traversal direction
    var prefix = "out".equals(anti.traversalDirection()) ? "out_" : "in_";
    var fieldName = prefix + anti.traversalEdgeClass();
    var fieldValue = anchorEntity.getPropertyInternal(fieldName);
    if (!(fieldValue instanceof LinkBag linkBag)) {
      return null;
    }

    var maxSize = MatchExecutionPlanner.getHashJoinThreshold();
    if (linkBag.size() > maxSize) {
      return null; // too large — fall back
    }

    var result = new HashSet<RID>();
    for (var pair : linkBag) {
      // secondaryRid is the opposite-side vertex RID
      result.add(pair.secondaryRid());
      if (maxSize > 0 && result.size() > maxSize) {
        return null;
      }
    }
    return result;
  }

  /**
   * Probe for ChainSemiJoin: returns 0..N rows per upstream row (fan-out).
   * For each matching edge record, emits a result with the intermediate alias
   * bound to the edge and the target alias bound to the back-ref vertex.
   */
  @SuppressWarnings("unchecked")
  private ExecutionStream probeChain(
      Result row,
      LinkedHashMap<RID, Object> lruCache,
      ChainSemiJoin chain,
      DatabaseSessionEmbedded session,
      CommandContext probeCtx) {
    var backRefRid = resolveBackRefRid(row, chain, probeCtx);
    if (backRefRid == null) {
      return ExecutionStream.empty();
    }

    var hashTable = lruCache.get(backRefRid);
    if (hashTable == null && !lruCache.containsKey(backRefRid)) {
      hashTable = buildHashTable(backRefRid, chain, probeCtx);
      lruCache.put(backRefRid, hashTable);
    }

    if (hashTable == null) {
      return ExecutionStream.empty();
    }

    var sourceRid = resolveSourceRid(row, chain);
    if (sourceRid == null) {
      return ExecutionStream.empty();
    }

    var map = (Map<RID, List<Result>>) hashTable;
    var edges = map.get(sourceRid);
    if (edges == null || edges.isEmpty()) {
      return ExecutionStream.empty();
    }

    // Load the back-ref vertex once for the target alias value
    Object backRefVertex;
    try {
      backRefVertex = session.getActiveTransaction().load(backRefRid);
    } catch (Exception e) {
      return ExecutionStream.empty();
    }

    // Emit one row per matching edge, adding intermediate and target aliases
    var results = new ArrayList<Result>(edges.size());
    for (var edgeResult : edges) {
      // Layer 1: add intermediate alias (edge record)
      var withIntermediate = new MatchResultRow(
          session, row, chain.intermediateAlias(), edgeResult);
      // Layer 2: add target alias (back-ref vertex)
      var withTarget = new MatchResultRow(
          session, withIntermediate, chain.targetAlias(), backRefVertex);
      results.add(withTarget);
    }
    return ExecutionStream.resultIterator(results.iterator());
  }

  /**
   * Converts a value to a {@link RID}, handling both direct RID instances
   * and {@link com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable}
   * wrappers.
   */
  @Nullable static RID toRid(@Nullable Object value) {
    if (value instanceof RID rid) {
      return rid;
    }
    if (value instanceof com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable identifiable) {
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
    if (descriptor instanceof SingleEdgeSemiJoin single) {
      return spaces
          + "+ BACK-REF HASH JOIN ("
          + single.sourceAlias()
          + " ⋈ $matched." + single.backRefAlias()
          + " via " + single.direction() + "('" + single.edgeClass() + "'))";
    }
    if (descriptor instanceof ChainSemiJoin chain) {
      return spaces
          + "+ BACK-REF HASH JOIN ("
          + chain.sourceAlias()
          + " ⋈ $matched." + chain.backRefAlias()
          + " via " + chain.direction() + "E('" + chain.edgeClass() + "').inV()"
          + " aliases: " + chain.intermediateAlias() + ", " + chain.targetAlias()
          + ")";
    }
    if (descriptor instanceof AntiSemiJoin anti) {
      return spaces
          + "+ BACK-REF HASH JOIN ANTI (NOT IN $matched."
          + anti.anchorAlias()
          + "." + anti.traversalDirection() + "('" + anti.traversalEdgeClass()
          + "'))";
    }
    return spaces + "+ BACK-REF HASH JOIN (" + descriptor.joinMode() + ")";
  }

  @Override
  public void close() {
    cache = null;
    super.close();
  }

  @Override
  public ExecutionStep copy(CommandContext ctx) {
    return new BackRefHashJoinStep(ctx, descriptor, profilingEnabled);
  }
}
