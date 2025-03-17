package com.jetbrains.youtrack.db.internal.core.sql.executor;

import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLMatchPathItem;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLRid;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLWhereClause;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 *
 */
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

  public Result next(CommandContext ctx) {
    init(ctx);
    if (!downstream.hasNext(ctx)) {
      throw new IllegalStateException();
    }
    var endPointAlias = getEndpointAlias();
    var nextR = downstream.next(ctx);
    Identifiable nextElement = nextR.asEntity();
    var prevValue = sourceRecord.getProperty(endPointAlias);
    if (prevValue != null && !equals(prevValue, nextElement)) {
      return null;
    }

    var db = ctx.getDatabaseSession();
    var result = new ResultInternal(db);
    for (var prop : sourceRecord.getPropertyNames()) {
      result.setProperty(prop, sourceRecord.getProperty(prop));
    }
    result.setProperty(endPointAlias, toResult(db, nextElement));
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

  protected static boolean equals(Object prevValue, Identifiable nextElement) {
    if (prevValue instanceof Result result) {
      if (result.isEntity()) {
        prevValue = result.asEntity();
      } else {
        prevValue = null;
      }
    }
    if (nextElement instanceof Result result) {
      if (result.isEntity()) {
        nextElement = result.asEntity();
      } else {
        nextElement = null;
      }
    }
    return prevValue != null && prevValue.equals(nextElement);
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
      if (startingElem instanceof Result result) {
        if (result.isEntity()) {
          startingElem = result.asEntity();
        } else {
          startingElem = null;
        }
      }
      downstream = executeTraversal(ctx, this.item, (Identifiable) startingElem, 0, null);
    }
  }

  protected ExecutionStream executeTraversal(
      CommandContext iCommandContext,
      SQLMatchPathItem item,
      Identifiable startingPoint,
      int depth,
      List<Identifiable> pathToHere) {

    SQLWhereClause filter = null;
    SQLWhereClause whileCondition = null;
    Integer maxDepth = null;
    String className = null;
    Integer clusterId = null;
    SQLRid targetRid = null;
    if (item.getFilter() != null) {
      filter = getTargetFilter(item);
      whileCondition = item.getFilter().getWhileCondition();
      maxDepth = item.getFilter().getMaxDepth();
      className = targetClassName(item, iCommandContext);
      var clusterName = targetClusterName(item, iCommandContext);
      if (clusterName != null) {
        clusterId = iCommandContext.getDatabaseSession().getClusterIdByName(clusterName);
      }
      targetRid = targetRid(item, iCommandContext);
    }

    if (whileCondition == null
        && maxDepth
        == null) { // in this case starting point is not returned and only one level depth is
      // evaluated

      var queryResult = traversePatternEdge(startingPoint, iCommandContext);
      final var theFilter = filter;
      final var theClassName = className;
      final var theClusterId = clusterId;
      final var theTargetRid = targetRid;
      return queryResult.filter(
          (next, ctx) ->
              filter(
                  iCommandContext, theFilter, theClassName, theClusterId, theTargetRid, next, ctx));
    } else { // in this case also zero level (starting point) is considered and traversal depth is
      // given by the while condition
      List<Result> result = new ArrayList<>();
      iCommandContext.setVariable("$depth", depth);
      var previousMatch = iCommandContext.getVariable("$currentMatch");
      iCommandContext.setVariable("$currentMatch", startingPoint);

      if (matchesFilters(iCommandContext, filter, startingPoint)
          && matchesClass(iCommandContext, className, startingPoint)
          && matchesCluster(clusterId, startingPoint)
          && matchesRid(iCommandContext, targetRid, startingPoint)) {
        var rs = new ResultInternal(iCommandContext.getDatabaseSession(), startingPoint);
        // set traversal depth in the metadata
        rs.setMetadata("$depth", depth);
        // set traversal path in the metadata
        rs.setMetadata("$matchPath", pathToHere == null ? Collections.EMPTY_LIST : pathToHere);
        // add the result to the list
        result.add(rs);
      }

      if ((maxDepth == null || depth < maxDepth)
          && (whileCondition == null
          || whileCondition.matchesFilters(startingPoint, iCommandContext))) {

        var queryResult = traversePatternEdge(startingPoint, iCommandContext);

        while (queryResult.hasNext(iCommandContext)) {
          var origin = queryResult.next(iCommandContext);
          //          if(origin.equals(startingPoint)){
          //            continue;
          //          }
          // TODO consider break strategies (eg. re-traverse nodes)

          List<Identifiable> newPath = new ArrayList<>();
          if (pathToHere != null) {
            newPath.addAll(pathToHere);
          }

          assert origin.isEntity();
          var elem = origin.asEntity();
          newPath.add(elem);

          var subResult =
              executeTraversal(iCommandContext, item, elem, depth + 1, newPath);
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

  private Result filter(
      CommandContext iCommandContext,
      final SQLWhereClause theFilter,
      final String theClassName,
      final Integer theClusterId,
      final SQLRid theTargetRid,
      Result next,
      CommandContext ctx) {
    var previousMatch = ctx.getVariable("$currentMatch");
    var matched = (ResultInternal) ctx.getVariable("matched");
    if (matched != null) {
      matched.setProperty(
          getStartingPointAlias(), sourceRecord.getProperty(getStartingPointAlias()));
    }
    assert next.isEntity();

    var elem = next.asEntity();
    iCommandContext.setVariable("$currentMatch", elem);
    if (matchesFilters(iCommandContext, theFilter, elem)
        && matchesClass(iCommandContext, theClassName, elem)
        && matchesCluster(theClusterId, elem)
        && matchesRid(iCommandContext, theTargetRid, elem)) {
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

  protected String targetClusterName(SQLMatchPathItem item, CommandContext iCommandContext) {
    return item.getFilter().getClusterName(iCommandContext);
  }

  protected SQLRid targetRid(SQLMatchPathItem item, CommandContext iCommandContext) {
    return item.getFilter().getRid(iCommandContext);
  }

  private static boolean matchesClass(
      CommandContext context, String className, Identifiable origin) {
    if (className == null) {
      return true;
    }

    var session = context.getDatabaseSession();
    EntityImpl entity = null;
    if (origin instanceof EntityImpl EntityImpl) {
      entity = EntityImpl;
    } else if (origin != null) {
      var record = origin.getRecord(session);
      if (record instanceof EntityImpl EntityImpl) {
        entity = EntityImpl;
      }
    }

    if (entity != null) {
      var clazz = entity.getImmutableSchemaClass(session);
      if (clazz == null) {
        return false;
      }
      return clazz.isSubClassOf(className);
    }
    return false;
  }

  private static boolean matchesCluster(
      Integer clusterId, Identifiable origin) {
    if (clusterId == null) {
      return true;
    }
    if (origin == null) {
      return false;
    }

    if (origin.getIdentity() == null) {
      return false;
    }
    return clusterId.equals(origin.getIdentity().getClusterId());
  }

  private boolean matchesRid(CommandContext iCommandContext, SQLRid rid, Identifiable origin) {
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

  protected boolean matchesFilters(
      CommandContext iCommandContext, SQLWhereClause filter, Identifiable origin) {
    return filter == null || filter.matchesFilters(origin, iCommandContext);
  }

  // TODO refactor this method to receive the item.

  protected ExecutionStream traversePatternEdge(
      Identifiable startingPoint, CommandContext iCommandContext) {

    var prevCurrent = iCommandContext.getVariable("$current");
    iCommandContext.setVariable("$current", startingPoint);
    Object qR;
    try {
      qR = this.item.getMethod().execute(startingPoint, iCommandContext);
    } finally {
      iCommandContext.setVariable("$current", prevCurrent);
    }

    if (qR == null) {
      return ExecutionStream.empty();
    }
    if (qR instanceof Identifiable) {
      return ExecutionStream.singleton(new ResultInternal(
          iCommandContext.getDatabaseSession(), (Identifiable) qR));
    }
    if (qR instanceof Iterable) {
      return ExecutionStream.iterator(((Iterable) qR).iterator());
    }
    return ExecutionStream.empty();
  }
}
