package com.jetbrains.youtrackdb.internal.core.sql.functions.graph;

import com.jetbrains.youtrackdb.internal.common.collection.MultiCollectionIterator;
import com.jetbrains.youtrackdb.internal.common.util.RawPair;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandExecutorAbstract;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSession;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Direction;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Edge;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Entity;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Vertex;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.record.impl.BidirectionalLinksIterable;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.sql.SQLHelper;
import com.jetbrains.youtrackdb.internal.core.sql.functions.math.SQLFunctionMathAbstract;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/**
 * Shortest path algorithm to find the shortest path from one node to another node in a directed
 * graph.
 */
public class SQLFunctionShortestPath extends SQLFunctionMathAbstract {

  public static final String NAME = "shortestPath";
  public static final String PARAM_MAX_DEPTH = "maxDepth";

  protected static final float DISTANCE = 1f;

  public SQLFunctionShortestPath() {
    super(NAME, 2, 5);
  }

  private class ShortestPathContext {

    private Vertex sourceVertex;
    private Vertex destinationVertex;
    private Direction directionLeft = Direction.BOTH;
    private Direction directionRight = Direction.BOTH;

    private String edgeType;
    private String[] edgeTypeParam;

    private ArrayDeque<Vertex> queueLeft = new ArrayDeque<>();
    private ArrayDeque<Vertex> queueRight = new ArrayDeque<>();

    private final Set<RID> leftVisited = new HashSet<RID>();
    private final Set<RID> rightVisited = new HashSet<RID>();

    private final Map<RID, RID> previouses = new HashMap<RID, RID>();
    private final Map<RID, RID> nexts = new HashMap<RID, RID>();

    private Vertex current;
    private Vertex currentRight;
    public Integer maxDepth;

    /**
     * option that decides whether or not to return the edge information
     */
    public Boolean edge;
  }

  @Override
  public List<RID> execute(
      Object iThis,
      final Result iCurrentRecord,
      final Object iCurrentResult,
      final Object[] iParams,
      final CommandContext iContext) {

    var session = iContext.getDatabaseSession();
    var record =
        iCurrentRecord != null && iCurrentRecord.isEntity() ? iCurrentRecord.asEntity() : null;

    final var ctx = new ShortestPathContext();

    var source = iParams[0];
    source = getSingleItem(source);
    if (source == null) {
      throw new IllegalArgumentException("Only one sourceVertex is allowed");
    }
    source = SQLHelper.getValue(source, record, iContext);
    if (source instanceof Identifiable) {
      var transaction = session.getActiveTransaction();
      Entity elem = transaction.load(((Identifiable) source));
      if (!elem.isVertex()) {
        throw new IllegalArgumentException("The sourceVertex must be a vertex record");
      }
      ctx.sourceVertex = elem.asVertex();
    } else {
      throw new IllegalArgumentException("The sourceVertex must be a vertex record");
    }

    var dest = iParams[1];
    dest = getSingleItem(dest);
    if (dest == null) {
      throw new IllegalArgumentException("Only one destinationVertex is allowed");
    }
    dest = SQLHelper.getValue(dest, record, iContext);
    if (dest instanceof Identifiable) {
      var transaction = session.getActiveTransaction();
      Entity elem = transaction.load(((Identifiable) dest));
      if (elem == null || !elem.isVertex()) {
        throw new IllegalArgumentException("The destinationVertex must be a vertex record");
      }
      ctx.destinationVertex = elem.asVertex();
    } else {
      throw new IllegalArgumentException("The destinationVertex must be a vertex record");
    }

    if (ctx.sourceVertex.equals(ctx.destinationVertex)) {
      final List<RID> result = new ArrayList<RID>(1);
      result.add(ctx.destinationVertex.getIdentity());
      return result;
    }

    if (iParams.length > 2 && iParams[2] != null) {
      ctx.directionLeft = Direction.valueOf(iParams[2].toString().toUpperCase(Locale.ENGLISH));
    }
    if (ctx.directionLeft == Direction.OUT) {
      ctx.directionRight = Direction.IN;
    } else if (ctx.directionLeft == Direction.IN) {
      ctx.directionRight = Direction.OUT;
    }

    ctx.edgeType = null;
    if (iParams.length > 3) {

      var param = iParams[3];
      if (param instanceof Collection
          && ((Collection) param).stream().allMatch(x -> x instanceof String)) {
        ctx.edgeType = ((Collection<String>) param).stream().collect(Collectors.joining(","));
        ctx.edgeTypeParam = (String[]) ((Collection) param).toArray(new String[0]);
      } else {
        ctx.edgeType = param == null ? null : "" + param;
        ctx.edgeTypeParam = new String[]{ctx.edgeType};
      }
    } else {
      ctx.edgeTypeParam = new String[]{null};
    }

    if (iParams.length > 4) {
      bindAdditionalParams(session, iParams[4], ctx);
    }

    ctx.queueLeft.add(ctx.sourceVertex);
    ctx.leftVisited.add(ctx.sourceVertex.getIdentity());

    ctx.queueRight.add(ctx.destinationVertex);
    ctx.rightVisited.add(ctx.destinationVertex.getIdentity());

    var depth = 1;
    while (true) {
      if (ctx.maxDepth != null && ctx.maxDepth <= depth) {
        break;
      }
      if (ctx.queueLeft.isEmpty() || ctx.queueRight.isEmpty()) {
        break;
      }

      if (Thread.interrupted()) {
        throw new CommandExecutionException(session,
            "The shortestPath() function has been interrupted");
      }

      if (!CommandExecutorAbstract.checkInterruption(iContext)) {
        break;
      }

      List<RID> neighborIdentity;

      if (ctx.queueLeft.size() <= ctx.queueRight.size()) {
        // START EVALUATING FROM LEFT
        neighborIdentity = walkLeft(ctx);
        if (neighborIdentity != null) {
          return neighborIdentity;
        }
        depth++;
        if (ctx.maxDepth != null && ctx.maxDepth <= depth) {
          break;
        }

        if (ctx.queueLeft.isEmpty()) {
          break;
        }

        neighborIdentity = walkRight(ctx);
        if (neighborIdentity != null) {
          return neighborIdentity;
        }

      } else {

        // START EVALUATING FROM RIGHT
        neighborIdentity = walkRight(ctx);
        if (neighborIdentity != null) {
          return neighborIdentity;
        }

        depth++;
        if (ctx.maxDepth != null && ctx.maxDepth <= depth) {
          break;
        }

        if (ctx.queueRight.isEmpty()) {
          break;
        }

        neighborIdentity = walkLeft(ctx);
        if (neighborIdentity != null) {
          return neighborIdentity;
        }
      }

      depth++;
    }
    return new ArrayList<RID>();
  }

  private void bindAdditionalParams(DatabaseSessionInternal db, Object additionalParams,
      ShortestPathContext ctx) {
    if (additionalParams == null) {
      return;
    }

    Map<String, ?> mapParams = null;
    if (additionalParams instanceof Map) {
      mapParams = (Map) additionalParams;
    } else if (additionalParams instanceof Identifiable) {
      var transaction = db.getActiveTransaction();
      mapParams = ((EntityImpl) transaction.load(((Identifiable) additionalParams))).toMap();
    }
    if (mapParams != null) {
      ctx.maxDepth = integer(mapParams.get("maxDepth"));
      var withEdge = toBoolean(mapParams.get("edge"));
      ctx.edge = Boolean.TRUE.equals(withEdge) ? Boolean.TRUE : Boolean.FALSE;
    }
  }

  @Nullable
  private Integer integer(Object fromObject) {
    if (fromObject == null) {
      return null;
    }
    if (fromObject instanceof Number) {
      return ((Number) fromObject).intValue();
    }
    if (fromObject instanceof String) {
      try {
        return Integer.parseInt(fromObject.toString());
      } catch (NumberFormatException ignore) {
      }
    }
    return null;
  }

  /**
   * @return
   */
  @Nullable
  private Boolean toBoolean(Object fromObject) {
    if (fromObject == null) {
      return null;
    }
    if (fromObject instanceof Boolean) {
      return (Boolean) fromObject;
    }
    if (fromObject instanceof String) {
      try {
        return Boolean.parseBoolean(fromObject.toString());
      } catch (NumberFormatException ignore) {
      }
    }
    return null;
  }

  /**
   * get adjacent vertices and edges
   */
  private static RawPair<Iterable<Vertex>, Iterable<Edge>> getVerticesAndEdges(
      Vertex srcVertex, Direction direction, String... types) {
    if (direction == Direction.BOTH) {
      var vertexIterator = new MultiCollectionIterator<Vertex>();
      var edgeIterator = new MultiCollectionIterator<Edge>();
      var pair1 =
          getVerticesAndEdges(srcVertex, Direction.OUT, types);
      var pair2 =
          getVerticesAndEdges(srcVertex, Direction.IN, types);
      vertexIterator.add(pair1.first());
      vertexIterator.add(pair2.first());
      edgeIterator.add(pair1.second());
      edgeIterator.add(pair2.second());
      return new RawPair<>(vertexIterator, edgeIterator);
    } else {
      var edges1 = srcVertex.getEdges(direction, types);
      var edges2 = srcVertex.getEdges(direction, types);
      //noinspection unchecked,rawtypes
      return new RawPair<>(new BidirectionalLinksIterable<>((Iterable) edges1, direction), edges2);
    }
  }

  /**
   * get adjacent vertices and edges
   *
   * @param srcVertex
   * @param direction
   * @return
   */
  private static RawPair<Iterable<Vertex>, Iterable<Edge>> getVerticesAndEdges(
      Vertex srcVertex, Direction direction) {
    return getVerticesAndEdges(srcVertex, direction, (String[]) null);
  }

  @Override
  public String getSyntax(DatabaseSession session) {
    return "shortestPath(<sourceVertex>, <destinationVertex>, [<direction>, [ <edgeTypeAsString>"
        + " ]])";
  }

  @Nullable
  protected List<RID> walkLeft(final ShortestPathContext ctx) {
    var nextLevelQueue = new ArrayDeque<Vertex>();
    if (!Boolean.TRUE.equals(ctx.edge)) {
      while (!ctx.queueLeft.isEmpty()) {
        ctx.current = ctx.queueLeft.poll();

        Iterable<Vertex> neighbors;
        if (ctx.edgeType == null) {
          neighbors = ctx.current.getVertices(ctx.directionLeft);
        } else {
          neighbors = ctx.current.getVertices(ctx.directionLeft, ctx.edgeTypeParam);
        }
        for (var neighbor : neighbors) {
          final var v = neighbor;
          final var neighborIdentity = v.getIdentity();

          if (ctx.rightVisited.contains(neighborIdentity)) {
            ctx.previouses.put(neighborIdentity, ctx.current.getIdentity());
            return computePath(ctx.previouses, ctx.nexts, neighborIdentity);
          }
          if (!ctx.leftVisited.contains(neighborIdentity)) {
            ctx.previouses.put(neighborIdentity, ctx.current.getIdentity());

            nextLevelQueue.offer(v);
            ctx.leftVisited.add(neighborIdentity);
          }
        }
      }
    } else {
      while (!ctx.queueLeft.isEmpty()) {
        ctx.current = ctx.queueLeft.poll();

        RawPair<Iterable<Vertex>, Iterable<Edge>> neighbors;
        if (ctx.edgeType == null) {
          neighbors = getVerticesAndEdges(ctx.current, ctx.directionLeft);
        } else {
          neighbors = getVerticesAndEdges(ctx.current, ctx.directionLeft, ctx.edgeTypeParam);
        }
        var vertexIterator = neighbors.first().iterator();
        var edgeIterator = neighbors.second().iterator();
        while (vertexIterator.hasNext() && edgeIterator.hasNext()) {
          var v = vertexIterator.next();
          final var neighborVertexIdentity = v.getIdentity();
          var edge = edgeIterator.next();

          RID neighborEdgeIdentity;
          if (edge.isStateful()) {
            neighborEdgeIdentity = edge.asStatefulEdge().getIdentity();
          } else {
            neighborEdgeIdentity = null;
          }

          if (ctx.rightVisited.contains(neighborVertexIdentity)) {
            ctx.previouses.put(neighborVertexIdentity, neighborEdgeIdentity);
            ctx.previouses.put(neighborEdgeIdentity, ctx.current.getIdentity());
            return computePath(ctx.previouses, ctx.nexts, neighborVertexIdentity);
          }
          if (!ctx.leftVisited.contains(neighborVertexIdentity)) {
            ctx.previouses.put(neighborVertexIdentity, neighborEdgeIdentity);
            ctx.previouses.put(neighborEdgeIdentity, ctx.current.getIdentity());

            nextLevelQueue.offer(v);
            ctx.leftVisited.add(neighborVertexIdentity);
          }
        }
      }
    }
    ctx.queueLeft = nextLevelQueue;
    return null;
  }

  @Nullable
  protected List<RID> walkRight(final ShortestPathContext ctx) {
    final var nextLevelQueue = new ArrayDeque<Vertex>();
    if (!Boolean.TRUE.equals(ctx.edge)) {
      while (!ctx.queueRight.isEmpty()) {
        ctx.currentRight = ctx.queueRight.poll();

        Iterable<Vertex> neighbors;
        if (ctx.edgeType == null) {
          neighbors = ctx.currentRight.getVertices(ctx.directionRight);
        } else {
          neighbors = ctx.currentRight.getVertices(ctx.directionRight, ctx.edgeTypeParam);
        }
        for (var neighbor : neighbors) {
          final var v = neighbor;
          final var neighborIdentity = v.getIdentity();

          if (ctx.leftVisited.contains(neighborIdentity)) {
            ctx.nexts.put(neighborIdentity, ctx.currentRight.getIdentity());
            return computePath(ctx.previouses, ctx.nexts, neighborIdentity);
          }
          if (!ctx.rightVisited.contains(neighborIdentity)) {

            ctx.nexts.put(neighborIdentity, ctx.currentRight.getIdentity());

            nextLevelQueue.offer(v);
            ctx.rightVisited.add(neighborIdentity);
          }
        }
      }
    } else {
      while (!ctx.queueRight.isEmpty()) {
        ctx.currentRight = ctx.queueRight.poll();

        RawPair<Iterable<Vertex>, Iterable<Edge>> neighbors;
        if (ctx.edgeType == null) {
          neighbors = getVerticesAndEdges(ctx.currentRight, ctx.directionRight);
        } else {
          neighbors = getVerticesAndEdges(ctx.currentRight, ctx.directionRight, ctx.edgeTypeParam);
        }

        var vertexIterator = neighbors.first().iterator();
        var edgeIterator = neighbors.second().iterator();
        while (vertexIterator.hasNext() && edgeIterator.hasNext()) {
          final var v = vertexIterator.next();
          final var neighborVertexIdentity = v.getIdentity();
          var edge = edgeIterator.next();

          RID neighborEdgeIdentity;
          if (edge.isStateful()) {
            neighborEdgeIdentity = edge.asStatefulEdge().getIdentity();
          } else {
            neighborEdgeIdentity = null;
          }

          if (ctx.leftVisited.contains(neighborVertexIdentity)) {
            ctx.nexts.put(neighborVertexIdentity, neighborEdgeIdentity);
            ctx.nexts.put(neighborEdgeIdentity, ctx.currentRight.getIdentity());
            return computePath(ctx.previouses, ctx.nexts, neighborVertexIdentity);
          }
          if (!ctx.rightVisited.contains(neighborVertexIdentity)) {
            ctx.nexts.put(neighborVertexIdentity, neighborEdgeIdentity);
            ctx.nexts.put(neighborEdgeIdentity, ctx.currentRight.getIdentity());

            nextLevelQueue.offer(v);
            ctx.rightVisited.add(neighborVertexIdentity);
          }
        }
      }
    }
    ctx.queueRight = nextLevelQueue;
    return null;
  }

  private List<RID> computePath(
      final Map<RID, RID> leftDistances,
      final Map<RID, RID> rightDistances,
      final RID neighbor) {
    final List<RID> result = new ArrayList<RID>();

    var current = neighbor;
    while (current != null) {
      result.add(0, current);
      current = leftDistances.get(current);
    }

    current = neighbor;
    while (current != null) {
      current = rightDistances.get(current);
      if (current != null) {
        result.add(current);
      }
    }

    return result;
  }
}
