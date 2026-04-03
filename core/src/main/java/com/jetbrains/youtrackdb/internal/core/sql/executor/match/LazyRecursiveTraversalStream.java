package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal;
import com.jetbrains.youtrackdb.internal.core.sql.executor.RidSet;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLRid;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import java.util.ArrayDeque;
import java.util.Deque;
import javax.annotation.Nullable;

/**
 * Lazy, pull-based {@link ExecutionStream} that replaces the eager ArrayList
 * materialization in the recursive (WHILE / maxDepth) branch of
 * {@link MatchEdgeTraverser#executeTraversal}.
 *
 * <p>Uses an explicit stack of {@link Frame}s to flatten the recursive DFS into
 * a single iterator with O(1) call-stack depth — no recursive {@code hasNext()}
 * chains regardless of traversal depth.
 *
 * <p>Each frame represents a node being visited at a given depth. A frame goes
 * through two phases:
 * <ol>
 *   <li><b>SELF</b>: yield the node itself if it matches filters
 *   <li><b>NEIGHBORS</b>: expand neighbors via {@code traversePatternEdge()},
 *       pushing a new frame for each neighbor
 * </ol>
 *
 * <p>This eliminates the O(total_results) ArrayList allocation and enables future
 * LIMIT propagation: when the downstream consumer stops pulling, unexplored
 * subtrees are never expanded.
 */
final class LazyRecursiveTraversalStream implements ExecutionStream {

  /**
   * A frame on the traversal stack, representing one node being explored.
   * Tracks whether the node itself has been yielded (selfResult) and the
   * neighbor iteration state (neighborStream).
   */
  private static final class Frame {
    final Result startingPoint;
    final int depth;
    @Nullable final PathNode pathToHere;
    final Object previousMatch;

    // SELF phase: non-null if the starting point passes filters and hasn't
    // been yielded yet
    @Nullable Result selfResult;

    // NEIGHBORS phase: the stream of immediate neighbors, null until
    // expansion is initiated
    @Nullable ExecutionStream neighborStream;

    // True once neighbors have been checked (may still be null if
    // shouldExpand was false)
    boolean neighborsInitialized;

    Frame(Result startingPoint, int depth, @Nullable PathNode pathToHere,
        Object previousMatch) {
      this.startingPoint = startingPoint;
      this.depth = depth;
      this.pathToHere = pathToHere;
      this.previousMatch = previousMatch;
    }
  }

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

  // Explicit DFS stack — replaces Java call-stack recursion
  private final Deque<Frame> stack = new ArrayDeque<>();

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

    // Push the root frame
    pushFrame(startingPoint, depth, pathToHere);
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
    // Close all open neighbor streams on the stack
    while (!stack.isEmpty()) {
      var frame = stack.pop();
      if (frame.neighborStream != null) {
        frame.neighborStream.close(ctx);
      }
      // Restore context for each frame
      ctx.setSystemVariable(CommandContext.VAR_CURRENT_MATCH, frame.previousMatch);
    }
    done = true;
  }

  /**
   * Advances the traversal by one result. Processes the stack iteratively:
   * for each frame, first yields the self-result, then expands neighbors
   * by pushing new frames.
   */
  @Nullable private Result advance() {
    while (!stack.isEmpty()) {
      var frame = stack.peek();

      // Phase 1: yield the starting point if it matches filters
      if (frame.selfResult != null) {
        var result = frame.selfResult;
        frame.selfResult = null;
        return result;
      }

      // Phase 2: expand neighbors
      if (!frame.neighborsInitialized) {
        frame.neighborsInitialized = true;

        if (shouldExpand(frame)) {
          // Set context for traversePatternEdge
          ctx.setSystemVariable(CommandContext.VAR_DEPTH, frame.depth);
          ctx.setSystemVariable(
              CommandContext.VAR_CURRENT_MATCH, frame.startingPoint);

          frame.neighborStream =
              traverser.traversePatternEdge(frame.startingPoint, ctx);
        }
      }

      // Try to find the next valid neighbor and push its frame
      if (frame.neighborStream != null) {
        var pushed = pushNextNeighbor(frame);
        if (pushed) {
          // A new frame was pushed — loop back to process it (its self-result)
          continue;
        }
      }

      // This frame is fully exhausted — pop it and restore context
      stack.pop();
      if (frame.neighborStream != null) {
        frame.neighborStream.close(ctx);
      }
      ctx.setSystemVariable(CommandContext.VAR_CURRENT_MATCH, frame.previousMatch);
    }

    done = true;
    return null;
  }

  /**
   * Tries to find the next valid (non-null, non-deduped) neighbor from the
   * frame's neighborStream and pushes a new frame for it.
   *
   * @return true if a new frame was pushed, false if no more neighbors
   */
  private boolean pushNextNeighbor(Frame frame) {
    while (frame.neighborStream.hasNext(ctx)) {
      var origin = ResultInternal.toResult(frame.neighborStream.next(ctx), session);
      if (origin == null) {
        continue;
      }

      // Dedup check
      if (dedupVisited != null) {
        var neighborRid = origin.getIdentity();
        if (neighborRid != null && dedupVisited.contains(neighborRid)) {
          continue;
        }
      }

      // Build path if pathAlias is declared
      var newPath =
          hasPathAlias ? new PathNode(origin, frame.pathToHere, frame.depth) : null;

      pushFrame(origin, frame.depth + 1, newPath);
      return true;
    }
    return false;
  }

  /**
   * Creates a new frame for the given node, evaluates it against filters,
   * sets context variables, and pushes it onto the stack.
   */
  private void pushFrame(Result startingPoint, int depth,
      @Nullable PathNode pathToHere) {
    // Save current context and set for this depth. If filter evaluation or
    // path materialization throws, restore the context to avoid leaving it
    // in an inconsistent state.
    var previousMatch = ctx.getSystemVariable(CommandContext.VAR_CURRENT_MATCH);
    ctx.setSystemVariable(CommandContext.VAR_DEPTH, depth);
    ctx.setSystemVariable(CommandContext.VAR_CURRENT_MATCH, startingPoint);
    try {
      var frame = new Frame(startingPoint, depth, pathToHere, previousMatch);

      // Mark visited immediately when frame is created — not deferred to
      // expansion. This ensures boundary-depth vertices (where shouldExpand
      // returns false) are still deduplicated.
      if (dedupVisited != null && startingPoint != null) {
        var rid = startingPoint.getIdentity();
        if (rid != null) {
          dedupVisited.add(rid);
        }
      }

      // Evaluate self against filters
      if (startingPoint != null
          && MatchEdgeTraverser.matchesFilters(ctx, filter, startingPoint)
          && MatchEdgeTraverser.matchesClass(ctx, className, startingPoint)
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
          frame.selfResult = rs;
        }
      }

      stack.push(frame);
    } catch (Throwable t) {
      ctx.setSystemVariable(CommandContext.VAR_CURRENT_MATCH, previousMatch);
      throw t;
    }
  }

  private boolean shouldExpand(Frame frame) {
    return frame.startingPoint != null
        && (maxDepth == null || frame.depth < maxDepth)
        && (whileCondition == null
            || whileCondition.matchesFilters(frame.startingPoint, ctx));
  }
}
