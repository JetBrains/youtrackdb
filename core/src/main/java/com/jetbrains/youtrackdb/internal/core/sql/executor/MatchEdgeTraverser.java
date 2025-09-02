package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.api.query.Result;
import com.jetbrains.youtrackdb.api.record.Identifiable;
import com.jetbrains.youtrackdb.api.record.Relation;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchPathItem;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLRid;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import javax.annotation.Nullable;


public class MatchEdgeTraverser {

  protected Result sourceRecord;
  protected EdgeTraversal edge;
  protected SQLMatchPathItem item;
  protected ExecutionStream downstream;

  public MatchEdgeTraverser(Result lastUpstreamRecord, EdgeTraversal edge) {
    this.sourceRecord = lastUpstreamRecord;
    this.edge = edge;
    this.item = edge.edge.item;
  }

  public MatchEdgeTraverser(Result lastUpstreamRecord, SQLMatchPathItem item) {
    this.sourceRecord = lastUpstreamRecord;
    this.item = item;
  }

  public boolean hasNext(CommandContext ctx) {
    init(ctx);
    return downstream.hasNext(ctx);
  }

  @Nullable
  public Result next(CommandContext ctx) {
    init(ctx);
    if (!downstream.hasNext(ctx)) {
      throw new IllegalStateException();
    }
    var endPointAlias = getEndpointAlias();
    var nextR = downstream.next(ctx);
    var session = ctx.getDatabaseSession();
    var prevValue = ResultInternal.toResult(sourceRecord.getProperty(endPointAlias), session);

    if (prevValue != null && !Objects.equals(nextR, prevValue)) {
      return null;
    }

    var result = new ResultInternal(session);
    for (var prop : sourceRecord.getPropertyNames()) {
      result.setProperty(prop, sourceRecord.getProperty(prop));
    }
    result.setProperty(endPointAlias, nextR);
    if (edge.edge.item.getFilter().getDepthAlias() != null) {
      result.setProperty(edge.edge.item.getFilter().getDepthAlias(),
          ((ResultInternal) nextR).getMetadata("$depth"));
    }
    if (edge.edge.item.getFilter().getPathAlias() != null) {
      result.setProperty(
          edge.edge.item.getFilter().getPathAlias(),
          ((ResultInternal) nextR).getMetadata("$matchPath"));
    }
    return result;
  }

  protected static Object toResult(DatabaseSessionInternal db, Identifiable nextElement) {
    return new ResultInternal(db, nextElement);
  }

  protected String getStartingPointAlias() {
    return this.edge.edge.out.alias;
  }

  protected String getEndpointAlias() {
    if (this.item != null) {
      return this.item.getFilter().getAlias();
    }
    return this.edge.edge.in.alias;
  }

  protected void init(CommandContext ctx) {
    if (downstream == null) {
      var startingElem = sourceRecord.getProperty(getStartingPointAlias());
      if (!(startingElem instanceof Result)) {
        startingElem = ResultInternal.toResultInternal(startingElem, ctx.getDatabaseSession());
      }

      downstream = executeTraversal(ctx, this.item, (Result) startingElem, 0, null);
    }
  }


  protected ExecutionStream executeTraversal(
      CommandContext iCommandContext,
      SQLMatchPathItem item,
      Result startingPoint,
      int depth,
      List<Result> pathToHere) {
    SQLWhereClause filter = null;
    SQLWhereClause whileCondition = null;
    Integer maxDepth = null;
    String className = null;
    SQLRid targetRid = null;

    var session = iCommandContext.getDatabaseSession();
    if (item.getFilter() != null) {
      filter = getTargetFilter(item);
      whileCondition = item.getFilter().getWhileCondition();
      maxDepth = item.getFilter().getMaxDepth();
      className = targetClassName(item, iCommandContext);
      targetRid = targetRid(item, iCommandContext);
    }

    if (whileCondition == null
        && maxDepth
        == null) { // in this case starting point is not returned and only one level depth is
      // evaluated

      var queryResult = traversePatternEdge(startingPoint, iCommandContext);
      final var theFilter = filter;
      final var theClassName = className;
      final var theTargetRid = targetRid;
      return queryResult.filter(
          (next, ctx) ->
              filter(
                  iCommandContext, theFilter, theClassName, theTargetRid, next,
                  ctx));
    } else { // in this case also zero level (starting point) is considered and traversal depth is
      // given by the while condition
      List<Result> result = new ArrayList<>();
      iCommandContext.setVariable("$depth", depth);
      var previousMatch = iCommandContext.getVariable("$currentMatch");
      iCommandContext.setVariable("$currentMatch", startingPoint);

      if (matchesFilters(iCommandContext, filter, startingPoint)
          && matchesClass(iCommandContext, className, startingPoint)
          && matchesRid(iCommandContext, targetRid, startingPoint)) {
        // set traversal depth in the metadata
        ResultInternal rs;
        if (startingPoint instanceof ResultInternal resultInternal) {
          rs = resultInternal;
        } else {
          rs = ResultInternal.toResultInternal(startingPoint, session, null);
        }
        rs.setMetadata("$depth", depth);
        // set traversal path in the metadata
        rs.setMetadata("$matchPath",
            pathToHere == null ? Collections.EMPTY_LIST : pathToHere);
        // add the result to the list
        result.add(rs);
      }

      if ((maxDepth == null || depth < maxDepth)
          && (whileCondition == null
          || whileCondition.matchesFilters(startingPoint, iCommandContext))) {

        var queryResult = traversePatternEdge(startingPoint, iCommandContext);

        while (queryResult.hasNext(iCommandContext)) {
          var origin = ResultInternal.toResult(queryResult.next(iCommandContext), session);
          //          if(origin.equals(startingPoint)){
          //            continue;
          //          }
          // TODO consider break strategies (eg. re-traverse nodes)

          List<Result> newPath = new ArrayList<>();
          if (pathToHere != null) {
            newPath.addAll(pathToHere);
          }

          newPath.add(origin);

          var subResult =
              executeTraversal(iCommandContext, item, origin, depth + 1, newPath);
          while (subResult.hasNext(iCommandContext)) {
            var sub = subResult.next(iCommandContext);
            result.add(sub);
          }
        }
      }
      iCommandContext.setVariable("$currentMatch", previousMatch);
      return ExecutionStream.resultIterator(result.iterator());
    }
  }

  @Nullable
  private Result filter(
      CommandContext iCommandContext,
      final SQLWhereClause theFilter,
      final String theClassName,
      final SQLRid theTargetRid,
      Result next,
      CommandContext ctx) {
    var previousMatch = ctx.getVariable("$currentMatch");
    var matched = (ResultInternal) ctx.getVariable("matched");
    if (matched != null) {
      matched.setProperty(
          getStartingPointAlias(), sourceRecord.getProperty(getStartingPointAlias()));
    }
    iCommandContext.setVariable("$currentMatch", next);
    if (matchesFilters(iCommandContext, theFilter, next)
        && matchesClass(iCommandContext, theClassName, next)
        && matchesRid(iCommandContext, theTargetRid, next)) {
      ctx.setVariable("$currentMatch", previousMatch);
      return next;
    } else {
      ctx.setVariable("$currentMatch", previousMatch);
      return null;
    }
  }

  protected SQLWhereClause getTargetFilter(SQLMatchPathItem item) {
    return item.getFilter().getFilter();
  }

  protected String targetClassName(SQLMatchPathItem item, CommandContext iCommandContext) {
    return item.getFilter().getClassName(iCommandContext);
  }


  protected SQLRid targetRid(SQLMatchPathItem item, CommandContext iCommandContext) {
    return item.getFilter().getRid(iCommandContext);
  }

  private static boolean matchesClass(
      CommandContext context, String className, Result origin) {
    if (className == null) {
      return true;
    }

    var session = context.getDatabaseSession();
    var entity = (EntityImpl) origin.asEntityOrNull();
    if (entity != null) {
      var clazz = entity.getImmutableSchemaClass(session);
      if (clazz == null) {
        return false;
      }
      return clazz.isSubClassOf(className);
    }

    return false;
  }

  private static boolean matchesRid(CommandContext iCommandContext, SQLRid rid,
      Result origin) {
    if (rid == null) {
      return true;
    }
    if (origin == null) {
      return false;
    }

    if (origin.getIdentity() == null) {
      return false;
    }

    return origin.getIdentity().equals(rid.toRecordId(origin, iCommandContext));
  }

  protected static boolean matchesFilters(
      CommandContext iCommandContext, SQLWhereClause filter, Result origin) {
    return filter == null || filter.matchesFilters(origin, iCommandContext);
  }

  // TODO refactor this method to receive the item.

  protected ExecutionStream traversePatternEdge(
      Result startingPoint, CommandContext iCommandContext) {

    var prevCurrent = iCommandContext.getVariable("$current");
    iCommandContext.setVariable("$current", startingPoint);
    Object qR;
    try {
      qR = this.item.getMethod().execute(startingPoint, iCommandContext);
    } finally {
      iCommandContext.setVariable("$current", prevCurrent);
    }

    return switch (qR) {
      case null -> ExecutionStream.empty();
      case Identifiable identifiable -> ExecutionStream.singleton(new ResultInternal(
          iCommandContext.getDatabaseSession(), identifiable));
      case Relation<?> bidirectionalLink -> ExecutionStream.singleton(new ResultInternal(
          iCommandContext.getDatabaseSession(), bidirectionalLink));
      case Iterable<?> iterable -> ExecutionStream.iterator(iterable.iterator());
      default -> ExecutionStream.empty();
    };
  }
}
