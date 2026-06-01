package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal;
import com.jetbrains.youtrackdb.internal.core.sql.executor.RidSet;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLRid;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import java.util.Arrays;
import javax.annotation.Nullable;

/**
 * Lazy, pull-based {@link ExecutionStream} that replaces the eager ArrayList
 * materialization in the recursive (WHILE / maxDepth) branch of
 * {@link MatchEdgeTraverser#executeTraversal}.
 *
 * <p>Uses an explicit parallel-array stack to flatten the recursive DFS into
 * a single iterator with O(1) call-stack depth and — critically — O(1)
 * amortized allocation per visited vertex (one doubling arraycopy amortized
 * across all pushes, instead of one heap-allocated {@code Frame} object per
 * vertex).
 *
 * <p>Each stack slot represents a node being visited at a given depth. A slot
 * goes through two phases:
 * <ol>
 *   <li><b>SELF</b>: yield the node itself if it matches filters
 *   <li><b>NEIGHBORS</b>: expand neighbors via {@code traversePatternEdge()},
 *       pushing a new slot for each neighbor
 * </ol>
 *
 * <p>This eliminates the O(total_results) ArrayList allocation and enables
 * LIMIT propagation: when the downstream consumer stops pulling, unexplored
 * subtrees are never expanded.
 */
final class LazyRecursiveTraversalStream implements ExecutionStream {

  private static final int INITIAL_CAPACITY = 16;

  private final MatchEdgeTraverser traverser;
  private final CommandContext ctx;

  // Filter criteria — extracted once from the item
  @Nullable private final SQLWhereClause filter;
  @Nullable private final SQLWhereClause whileCondition;
  @Nullable private final Integer maxDepth;
  @Nullable private final String className;
  @Nullable private final SQLRid targetRid;
  private final boolean hasPathAlias;
  @Nullable private final RidSet dedupVisited;

  private final DatabaseSessionEmbedded session;

  // Parallel-array DFS stack. Top-of-stack is at index (size - 1). Growing by
  // doubling amortizes allocation cost across all pushes, so the traversal
  // pays one arraycopy per log2(maxDepth-visited) rather than one Frame
  // allocation per visited vertex.
  private Result[] startingPoints = new Result[INITIAL_CAPACITY];
  private int[] depths = new int[INITIAL_CAPACITY];
  private PathNode[] pathsToHere = new PathNode[INITIAL_CAPACITY];
  private Object[] previousMatches = new Object[INITIAL_CAPACITY];

  // SELF phase: non-null if the starting point passes filters and has not yet
  // been yielded.
  private Result[] selfResults = new Result[INITIAL_CAPACITY];

  // NEIGHBORS phase: stream of immediate neighbors. Stays null until expansion
  // is initiated; remains null afterward if shouldExpand() was false.
  private ExecutionStream[] neighborStreams = new ExecutionStream[INITIAL_CAPACITY];

  // Bitset: bit i set iff slot i has had shouldExpand/traversePatternEdge
  // evaluated. Needed to distinguish "never initialized" from "initialized
  // but the neighbor stream is null because shouldExpand was false".
  private long[] neighborsInitialized =
      new long[bitsetWords(INITIAL_CAPACITY)];

  private int size;

  // Buffered result ready for next()
  @Nullable private Result buffered;
  private boolean done;

  LazyRecursiveTraversalStream(
      MatchEdgeTraverser traverser,
      CommandContext ctx,
      Result startingPoint,
      int depth,
      @Nullable PathNode pathToHere,
      @Nullable SQLWhereClause filter,
      @Nullable SQLWhereClause whileCondition,
      @Nullable Integer maxDepth,
      @Nullable String className,
      @Nullable SQLRid targetRid,
      boolean hasPathAlias,
      @Nullable RidSet dedupVisited) {

    this.traverser = traverser;
    this.ctx = ctx;
    this.filter = filter;
    this.whileCondition = whileCondition;
    this.maxDepth = maxDepth;
    this.className = className;
    this.targetRid = targetRid;
    this.hasPathAlias = hasPathAlias;
    this.dedupVisited = dedupVisited;
    this.session = ctx.getDatabaseSession();

    // Push the root slot. alreadyMarked=false because this is the root —
    // no caller has added it to dedupVisited yet.
    pushFrame(startingPoint, depth, pathToHere, false);
  }

  @Override
  public boolean hasNext(CommandContext ctx) {
    if (buffered != null) {
      return true;
    }
    if (done) {
      return false;
    }
    buffered = advance();
    return buffered != null;
  }

  @Override
  public Result next(CommandContext ctx) {
    if (!hasNext(ctx)) {
      throw new IllegalStateException("No more results");
    }
    var result = buffered;
    buffered = null;
    return result;
  }

  @Override
  public void close(CommandContext ctx) {
    // Close all open neighbor streams on the stack and restore context
    while (size > 0) {
      var top = size - 1;
      var stream = neighborStreams[top];
      if (stream != null) {
        stream.close(ctx);
      }
      ctx.setSystemVariable(CommandContext.VAR_CURRENT_MATCH, previousMatches[top]);
      popSlot(top);
    }
    done = true;
  }

  /**
   * Advances the traversal by one result. Processes the stack iteratively:
   * for each slot, first yields the self-result, then expands neighbors by
   * pushing new slots.
   */
  @Nullable private Result advance() {
    while (size > 0) {
      var top = size - 1;

      // Phase 1: yield the starting point if it matches filters
      var selfResult = selfResults[top];
      if (selfResult != null) {
        selfResults[top] = null;
        return selfResult;
      }

      // Phase 2: expand neighbors (once per slot)
      if (!isBitSet(neighborsInitialized, top)) {
        setBit(neighborsInitialized, top);
        if (shouldExpand(top)) {
          // Set context for traversePatternEdge
          ctx.setSystemVariable(CommandContext.VAR_DEPTH, depths[top]);
          ctx.setSystemVariable(
              CommandContext.VAR_CURRENT_MATCH, startingPoints[top]);
          neighborStreams[top] =
              traverser.traversePatternEdge(startingPoints[top], ctx);
        }
      }

      // Try to find the next valid neighbor and push its slot
      var neighborStream = neighborStreams[top];
      if (neighborStream != null) {
        if (pushNextNeighbor(top, neighborStream)) {
          // A new slot was pushed — loop back to process it (its self-result)
          continue;
        }
      }

      // This slot is fully exhausted — pop it and restore context
      if (neighborStream != null) {
        neighborStream.close(ctx);
      }
      ctx.setSystemVariable(CommandContext.VAR_CURRENT_MATCH, previousMatches[top]);
      popSlot(top);
    }

    done = true;
    return null;
  }

  /**
   * Tries to find the next valid (non-null, non-deduped) neighbor from the
   * given slot's neighborStream and pushes a new slot for it.
   *
   * <p>Dedup is performed here via {@code RidSet.add} (returns {@code false} if
   * already present) so a single hash lookup replaces the previous pair of
   * {@code contains} + {@code add}. This matters at scale — IC1 at SF1 visits
   * ~125K neighbors (50 friends × 3 hops) — one hash probe per neighbor
   * instead of two.
   *
   * @return true if a new slot was pushed, false if no more neighbors
   */
  private boolean pushNextNeighbor(int parentIdx, ExecutionStream neighborStream) {
    var parentPath = pathsToHere[parentIdx];
    var parentDepth = depths[parentIdx];
    while (neighborStream.hasNext(ctx)) {
      var origin = ResultInternal.toResult(neighborStream.next(ctx), session);
      if (origin == null) {
        continue;
      }

      // Dedup via add() return value: true = newly added (push it), false =
      // already visited (skip). Guaranteed to have been marked before any
      // later visitor sees this RID, so boundary-depth vertices are deduped
      // consistently with the explicit add in pushFrame() for the root.
      var alreadyMarked = false;
      if (dedupVisited != null) {
        var neighborRid = origin.getIdentity();
        if (neighborRid != null) {
          if (!dedupVisited.add(neighborRid)) {
            continue;
          }
          alreadyMarked = true;
        }
      }

      // Build path if pathAlias is declared
      var newPath =
          hasPathAlias ? new PathNode(origin, parentPath, parentDepth) : null;

      pushFrame(origin, parentDepth + 1, newPath, alreadyMarked);
      return true;
    }
    return false;
  }

  /**
   * Creates a new slot for the given node, evaluates it against filters, sets
   * context variables, and pushes it onto the stack.
   *
   * @param alreadyMarked if {@code true}, the caller has already added the
   *     RID to {@code dedupVisited} — we skip the redundant add. This is the
   *     common case from {@link #pushNextNeighbor}, which uses the {@code add}
   *     return value to perform dedup and marking in one hash probe.
   */
  private void pushFrame(Result startingPoint, int depth,
      @Nullable PathNode pathToHere, boolean alreadyMarked) {
    // Save current context and set for this depth. If filter evaluation or
    // path materialization throws, restore the context to avoid leaving it
    // in an inconsistent state.
    var previousMatch = ctx.getSystemVariable(CommandContext.VAR_CURRENT_MATCH);
    ctx.setSystemVariable(CommandContext.VAR_DEPTH, depth);
    ctx.setSystemVariable(CommandContext.VAR_CURRENT_MATCH, startingPoint);
    try {
      ensureCapacity(size + 1);
      var slot = size;

      startingPoints[slot] = startingPoint;
      depths[slot] = depth;
      pathsToHere[slot] = pathToHere;
      previousMatches[slot] = previousMatch;
      // selfResults[slot], neighborStreams[slot] and the neighborsInitialized
      // bit are guaranteed clear — popSlot() zeroes them on pop, and newly
      // grown slots are zero-filled by Arrays.copyOf.

      // Mark visited immediately — not deferred to expansion — so boundary
      // depth vertices (where shouldExpand returns false) are still deduped.
      // Skipped when the caller (pushNextNeighbor) already added via its
      // combined contains-or-add probe.
      if (!alreadyMarked && dedupVisited != null && startingPoint != null) {
        var rid = startingPoint.getIdentity();
        if (rid != null) {
          dedupVisited.add(rid);
        }
      }

      // Evaluate self against filters. matchesClassCached reuses the owning
      // traverser's per-edge schema-snapshot cache and (className, collectionId)
      // memo, so recursive WHILE expansions also benefit from the zero-I/O
      // class check without re-resolving the schema on every hop.
      if (startingPoint != null
          && MatchEdgeTraverser.matchesFilters(ctx, filter, startingPoint)
          && traverser.matchesClassCached(ctx, className, startingPoint)
          && MatchEdgeTraverser.matchesRid(ctx, targetRid, startingPoint)) {
        ResultInternal rs;
        if (startingPoint instanceof ResultInternal resultInternal) {
          rs = resultInternal;
        } else {
          rs = ResultInternal.toResultInternal(startingPoint, session, null);
        }
        if (rs != null) {
          rs.setMetadata("$depth", depth);
          if (hasPathAlias) {
            rs.setMetadata("$matchPath",
                pathToHere == null ? PathNode.emptyPath() : pathToHere.toList());
          }
          selfResults[slot] = rs;
        }
      }

      // Publish the slot last so any exception above leaves size untouched —
      // no half-written slot becomes visible on the stack.
      size++;
    } catch (Throwable t) {
      ctx.setSystemVariable(CommandContext.VAR_CURRENT_MATCH, previousMatch);
      throw t;
    }
  }

  private boolean shouldExpand(int idx) {
    var sp = startingPoints[idx];
    return sp != null
        && (maxDepth == null || depths[idx] < maxDepth)
        && (whileCondition == null || whileCondition.matchesFilters(sp, ctx));
  }

  private void popSlot(int slot) {
    // Clear object references for GC and reset the neighbors-initialized bit
    startingPoints[slot] = null;
    pathsToHere[slot] = null;
    previousMatches[slot] = null;
    selfResults[slot] = null;
    neighborStreams[slot] = null;
    clearBit(neighborsInitialized, slot);
    size = slot;
  }

  private void ensureCapacity(int required) {
    var current = startingPoints.length;
    if (required <= current) {
      return;
    }
    var newCapacity = Math.max(current << 1, required);
    startingPoints = Arrays.copyOf(startingPoints, newCapacity);
    depths = Arrays.copyOf(depths, newCapacity);
    pathsToHere = Arrays.copyOf(pathsToHere, newCapacity);
    previousMatches = Arrays.copyOf(previousMatches, newCapacity);
    selfResults = Arrays.copyOf(selfResults, newCapacity);
    neighborStreams = Arrays.copyOf(neighborStreams, newCapacity);
    var requiredWords = bitsetWords(newCapacity);
    if (neighborsInitialized.length < requiredWords) {
      neighborsInitialized = Arrays.copyOf(neighborsInitialized, requiredWords);
    }
  }

  private static int bitsetWords(int capacity) {
    return (capacity + 63) >>> 6;
  }

  private static boolean isBitSet(long[] bitset, int idx) {
    return (bitset[idx >>> 6] & (1L << (idx & 63))) != 0;
  }

  private static void setBit(long[] bitset, int idx) {
    bitset[idx >>> 6] |= 1L << (idx & 63);
  }

  private static void clearBit(long[] bitset, int idx) {
    bitset[idx >>> 6] &= ~(1L << (idx & 63));
  }
}
