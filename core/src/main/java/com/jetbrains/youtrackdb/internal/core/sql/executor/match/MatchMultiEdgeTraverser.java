package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Relation;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchFilter;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchPathItemFirst;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMultiMatchPathItem;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

/**
 * Edge traverser for **multi-step** (compound) path items in a MATCH pattern.
 * <p>
 * This traverser handles the `.(sub1)(sub2)…` syntax represented by
 * {@link SQLMultiMatchPathItem}, where a single pattern edge is actually a **pipeline**
 * of multiple sub-traversals that are executed sequentially.
 * <p>
 * ### Example
 * <p>
 * ```sql
 * MATCH {class: Person, as: p}.(out('Knows'){where: (age > 25)}.out('Lives')){as: city}
 * ```
 * <p>
 * Here, `(out('Knows'){...}.out('Lives'))` is a multi-path item with two sub-items.
 * The traverser first expands `out('Knows')` with the filter, then feeds the results
 * into `out('Lives')`.
 * <p>
 * ### Execution model
 * <p>
 * The traversal works like a multi-stage pipeline:
 *
 * <pre>
 *   [startingPoint] ──sub1──→ [results1] ──sub2──→ [results2] ──…──→ [final results]
 *     (left side)               (right → left)       (right → left)    (returned)
 * </pre>
 * <p>
 * 1. Start with the input record as the initial "left side".
 * 2. For each sub-item in the multi-path:
 *    a. Execute the sub-item's method on every record in the current left side.
 *    b. Collect all results that pass the sub-item's `WHERE` filter into the "right side".
 *    c. The right side becomes the new left side for the next sub-item.
 * 3. The final right side is the traversal result.
 * <p>
 * If any sub-item has a `WHILE` condition, a recursive {@link MatchEdgeTraverser} is
 * used for that sub-item.
 *
 * @see SQLMultiMatchPathItem
 * @see MatchEdgeTraverser
 */
public class MatchMultiEdgeTraverser extends MatchEdgeTraverser {

  public MatchMultiEdgeTraverser(Result lastUpstreamRecord, EdgeTraversal edge) {
    super(lastUpstreamRecord, edge);
  }

  /**
   * Executes the multi-step traversal pipeline. Each sub-item in the
   * {@link SQLMultiMatchPathItem} is applied sequentially, with each stage's output
   * feeding the next stage's input.
   *
   * @param startingPoint    the record to start the multi-step traversal from
   * @param iCommandContext  the command execution context
   * @return a stream of records produced by the final stage of the pipeline
   */
  @Override
  protected ExecutionStream traversePatternEdge(
      Result startingPoint, CommandContext iCommandContext) {

    Iterable<Identifiable> possibleResults = null;

    var item = (SQLMultiMatchPathItem) this.item;
    List<ResultInternal> result = new ArrayList<>();

    // The "pipeline" — starts with the single starting point
    List<Result> nextStep = new ArrayList<>();
    nextStep.add(startingPoint);

    var db = iCommandContext.getDatabaseSession();
    var oldCurrent = iCommandContext.getVariable("$current");

    // Process each sub-item in sequence
    for (var sub : item.getItems()) {
      List<ResultInternal> rightSide = new ArrayList<>();
      for (var o : nextStep) {
        var whileCond =
            sub.getFilter() == null ? null : sub.getFilter().getWhileCondition();

        var method = sub.getMethod();
        // SQLMatchPathItemFirst uses a function instead of a method
        if (sub instanceof SQLMatchPathItemFirst) {
          method = ((SQLMatchPathItemFirst) sub).getFunction().toMethod();
        }

        if (whileCond != null) {
          // Delegate to a standard MatchEdgeTraverser for recursive WHILE expansion
          var subtraverser = new MatchEdgeTraverser(null, sub);
          var rightStream =
              subtraverser.executeTraversal(iCommandContext, sub, o, 0,
                  null);
          while (rightStream.hasNext(iCommandContext)) {
            rightSide.add((ResultInternal) rightStream.next(iCommandContext));
          }

        } else {
          // Simple single-hop expansion via the sub-item's method
          iCommandContext.setVariable("$current", o);
          var nextSteps = method.execute(o, possibleResults, iCommandContext);

          // Normalize the heterogeneous return types into ResultInternal, applying
          // the sub-item's WHERE filter to each candidate
          if (nextSteps instanceof Collection) {
            ((Collection<?>) nextSteps)
                .stream()
                .map(obj -> toOResultInternal(db, obj))
                .filter(
                    x ->
                        matchesCondition(x, sub.getFilter(), iCommandContext))
                .forEach(rightSide::add);
          } else if (nextSteps instanceof Identifiable) {
            var res = new ResultInternal(db, (Identifiable) nextSteps);
            if (matchesCondition(res, sub.getFilter(), iCommandContext)) {
              rightSide.add(res);
            }
          } else if (nextSteps instanceof Relation<?> bidirectionalLink) {
            var res = new ResultInternal(db, bidirectionalLink);
            if (matchesCondition(res, sub.getFilter(), iCommandContext)) {
              rightSide.add(res);
            }
          } else if (nextSteps instanceof ResultInternal) {
            if (matchesCondition((ResultInternal) nextSteps, sub.getFilter(), iCommandContext)) {
              rightSide.add((ResultInternal) nextSteps);
            }
          } else if (nextSteps instanceof Iterable) {
            for (var step : (Iterable<?>) nextSteps) {
              var converted = toOResultInternal(db, step);
              if (matchesCondition(converted, sub.getFilter(), iCommandContext)) {
                rightSide.add(converted);
              }
            }
          } else if (nextSteps instanceof Iterator<?> iterator) {
            while (iterator.hasNext()) {
              var converted = toOResultInternal(db, iterator.next());
              if (matchesCondition(converted, sub.getFilter(), iCommandContext)) {
                rightSide.add(converted);
              }
            }
          }
        }
      }
      // The right side of this stage becomes the left side of the next stage
      //noinspection unchecked,rawtypes
      nextStep = (List) rightSide;
      result = rightSide;
    }

    iCommandContext.setVariable("$current", oldCurrent);
    return ExecutionStream.resultIterator(result.iterator());
  }

  /**
   * Evaluates the sub-item's WHERE filter against a candidate record.
   *
   * @return `true` if no filter is defined or the record passes the filter
   */
  private static boolean matchesCondition(ResultInternal x, SQLMatchFilter filter,
      CommandContext ctx) {
    if (filter == null) {
      return true;
    }
    var where = filter.getFilter();
    if (where == null) {
      return true;
    }
    return where.matchesFilters(x, ctx);
  }

  /**
   * Converts a raw traversal result object into a {@link ResultInternal}.
   *
   * @throws CommandExecutionException if the object type is unrecognized
   */
  private static ResultInternal toOResultInternal(DatabaseSessionEmbedded session, Object x) {
    if (x instanceof ResultInternal resultInternal) {
      return resultInternal;
    } else if (x instanceof Identifiable identifiable) {
      return new ResultInternal(session, identifiable);
    } else if (x instanceof Relation<?> bidirectionalLink) {
      return new ResultInternal(session, bidirectionalLink);
    }
    throw new CommandExecutionException(session, "Cannot execute traversal on " + x);
  }
}
