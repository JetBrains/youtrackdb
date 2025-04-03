package com.jetbrains.youtrack.db.internal.core.sql.executor;

import com.jetbrains.youtrack.db.api.exception.CommandExecutionException;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.record.impl.Relation;
import com.jetbrains.youtrack.db.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLMatchFilter;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLMatchPathItemFirst;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLMultiMatchPathItem;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

/**
 *
 */
public class MatchMultiEdgeTraverser extends MatchEdgeTraverser {

  public MatchMultiEdgeTraverser(Result lastUpstreamRecord, EdgeTraversal edge) {
    super(lastUpstreamRecord, edge);
  }

  protected ExecutionStream traversePatternEdge(
      Result startingPoint, CommandContext iCommandContext) {

    Iterable<Identifiable> possibleResults = null;
    //    if (this.edge.edge.item.getFilter() != null) {
    //      String alias = this.edge.edge.item.getFilter().getAlias();
    //      Object matchedNodes =
    // iCommandContext.getVariable(MatchPrefetchStep.PREFETCHED_MATCH_ALIAS_PREFIX + alias);
    //      if (matchedNodes != null) {
    //        if (matchedNodes instanceof Iterable) {
    //          possibleResults = (Iterable) matchedNodes;
    //        } else {
    //          possibleResults = Collections.singleton(matchedNodes);
    //        }
    //      }
    //    }

    var item = (SQLMultiMatchPathItem) this.item;
    List<ResultInternal> result = new ArrayList<>();

    List<Result> nextStep = new ArrayList<>();
    nextStep.add(startingPoint);

    var db = iCommandContext.getDatabaseSession();
    var oldCurrent = iCommandContext.getVariable("$current");
    for (var sub : item.getItems()) {
      List<ResultInternal> rightSide = new ArrayList<>();
      for (var o : nextStep) {
        var whileCond =
            sub.getFilter() == null ? null : sub.getFilter().getWhileCondition();

        var method = sub.getMethod();
        if (sub instanceof SQLMatchPathItemFirst) {
          method = ((SQLMatchPathItemFirst) sub).getFunction().toMethod();
        }

        if (whileCond != null) {
          var subtraverser = new MatchEdgeTraverser(null, sub);
          var rightStream =
              subtraverser.executeTraversal(iCommandContext, sub, o, 0,
                  null);
          while (rightStream.hasNext(iCommandContext)) {
            rightSide.add((ResultInternal) rightStream.next(iCommandContext));
          }

        } else {
          iCommandContext.setVariable("$current", o);
          var nextSteps = method.execute(o, possibleResults, iCommandContext);
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
      //noinspection unchecked,rawtypes
      nextStep = (List) rightSide;
      result = rightSide;
    }

    iCommandContext.setVariable("$current", oldCurrent);
    //    return (qR instanceof Iterable) ? (Iterable) qR : Collections.singleton((Identifiable)
    // qR);
    return ExecutionStream.resultIterator(result.iterator());
  }

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

  private static ResultInternal toOResultInternal(DatabaseSessionInternal session, Object x) {
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
