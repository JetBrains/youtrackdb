package com.jetbrains.youtrackdb.internal.core.sql.executor.resultset;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.MatchEdgeTraverser;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.MatchStep;

/**
 * Adapts a {@link MatchEdgeTraverser} into the {@link ExecutionStream} interface so it
 * can be used in the execution plan pipeline.
 *
 * The traverser's {@link MatchEdgeTraverser#next(CommandContext)} method may return
 * `null` to signal that a particular candidate was rejected (e.g. consistency check
 * failure or filter mismatch). This adapter skips `null` results internally, only
 * exposing non-null results to the consumer.
 *
 * After each result is consumed via {@link #next}, the context variable `$matched` is
 * updated to point to the current result row, making it available for downstream
 * `WHERE` predicates that reference `$matched.<alias>`.
 *
 * <pre>
 * Runtime data flow for a single upstream row:
 *
 *   upstream row {p: Person#1}
 *        │
 *        ▼
 *   MatchStep.createNextResultSet()
 *        │
 *        ▼
 *   MatchEdgeTraverser(sourceRecord=row, edge=...)
 *        │
 *        ├─ init() → traversePatternEdge() → ExecutionStream of neighbors
 *        │
 *        ▼
 *   ResultSetEdgeTraverser (this class — adapts traverser → ExecutionStream)
 *        │
 *        ├─ fetchNext(): skips null results (rejected candidates)
 *        ├─ next(): sets ctx.$matched = current row
 *        │
 *        ▼
 *   downstream row {p: Person#1, f: Person#2}
 * </pre>
 *
 * @see MatchStep#createNextResultSet
 * @see MatchEdgeTraverser
 */
public final class ResultSetEdgeTraverser implements ExecutionStream {
  private final MatchEdgeTraverser trav;

  /** Buffered next result. `null` means we need to fetch the next non-null result. */
  private Result nextResult;

  public ResultSetEdgeTraverser(MatchEdgeTraverser trav) {
    this.trav = trav;
  }

  @Override
  public boolean hasNext(CommandContext ctx) {
    fetchNext(ctx);
    return nextResult != null;
  }

  /**
   * Returns the next non-null result and updates the `$matched` context variable.
   */
  @Override
  public Result next(CommandContext ctx) {
    if (!hasNext(ctx)) {
      throw new IllegalStateException();
    }
    var result = nextResult;
    // Update $matched so downstream WHERE clauses can reference previously matched aliases
    ctx.setVariable("$matched", result);
    nextResult = null;
    return result;
  }

  @Override
  public void close(CommandContext ctx) {
  }

  /**
   * Advances the underlying traverser, skipping any `null` results (which indicate
   * rejected candidates), until a valid result is found or the traverser is exhausted.
   */
  private void fetchNext(CommandContext ctx) {
    if (nextResult == null) {
      while (trav.hasNext(ctx)) {
        nextResult = trav.next(ctx);
        if (nextResult != null) {
          break;
        }
      }
    }
  }
}
