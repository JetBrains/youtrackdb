/*
 *
 *
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *
 *
 */
package com.jetbrains.youtrackdb.internal.core.sql.functions.graph;

import com.jetbrains.youtrackdb.internal.common.collection.MultiValue;
import com.jetbrains.youtrackdb.internal.common.io.IOUtils;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Direction;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Edge;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Entity;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Vertex;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.sql.SQLHelper;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * A*'s algorithm describes how to find the cheapest path from one node to another node in a
 * directed weighted graph with husrestic function.
 *
 * <p>The first parameter is source record. The second parameter is destination record. The third
 * parameter is a name of property that represents 'weight' and fourth represnts the map of
 * options.
 *
 * <p>If property is not defined in edge or is null, distance between vertexes are 0 .
 */
public class SQLFunctionAstar extends SQLFunctionHeuristicPathFinderAbstract {

  public static final String NAME = "astar";

  private String paramWeightFieldName = "weight";
  private long currentDepth = 0;
  protected Set<Vertex> closedSet = new HashSet<Vertex>();
  protected Map<Vertex, Vertex> cameFrom = new HashMap<Vertex, Vertex>();

  protected Map<Vertex, Double> gScore = new HashMap<Vertex, Double>();
  protected Map<Vertex, Double> fScore = new HashMap<Vertex, Double>();
  protected PriorityQueue<Vertex> open =
      new PriorityQueue<Vertex>(
          1,
          new Comparator<Vertex>() {

            public int compare(Vertex nodeA, Vertex nodeB) {
              return Double.compare(fScore.get(nodeA), fScore.get(nodeB));
            }
          });

  public SQLFunctionAstar() {
    super(NAME, 3, 4);
  }

  public LinkedList<Vertex> execute(
      final Object iThis,
      final Result iCurrentRecord,
      final Object iCurrentResult,
      final Object[] iParams,
      final CommandContext iContext) {
    context = iContext;
    final var context = this;

    var db = iContext.getDatabaseSession();
    var record =
        iCurrentRecord != null && iCurrentRecord.isEntity() ? iCurrentRecord.asEntity() : null;

    var source = iParams[0];
    if (MultiValue.isMultiValue(source)) {
      if (MultiValue.getSize(source) > 1) {
        throw new IllegalArgumentException("Only one sourceVertex is allowed");
      }
      source = MultiValue.getFirstValue(source);
      if (source instanceof Result && ((Result) source).isEntity()) {
        source = ((Result) source).asEntity();
      }
    }
    source = SQLHelper.getValue(source, record, iContext);
    if (source instanceof Identifiable) {
      var transaction = db.getActiveTransaction();
      Entity elem = transaction.load(((Identifiable) source));
      if (!elem.isVertex()) {
        throw new IllegalArgumentException("The sourceVertex must be a vertex record");
      }
      paramSourceVertex = elem.asVertexOrNull();
    } else {
      throw new IllegalArgumentException("The sourceVertex must be a vertex record");
    }

    var dest = iParams[1];
    if (MultiValue.isMultiValue(dest)) {
      if (MultiValue.getSize(dest) > 1) {
        throw new IllegalArgumentException("Only one destinationVertex is allowed");
      }
      dest = MultiValue.getFirstValue(dest);
      if (dest instanceof Result && ((Result) dest).isEntity()) {
        dest = ((Result) dest).asEntity();
      }
    }
    dest = SQLHelper.getValue(dest, record, iContext);
    if (dest instanceof Identifiable) {
      var transaction = db.getActiveTransaction();
      Entity elem = transaction.load(((Identifiable) dest));
      if (!elem.isVertex()) {
        throw new IllegalArgumentException("The destinationVertex must be a vertex record");
      }
      paramDestinationVertex = elem.asVertex();
    } else {
      throw new IllegalArgumentException("The destinationVertex must be a vertex record");
    }

    paramWeightFieldName = IOUtils.getStringContent(iParams[2]);

    if (iParams.length > 3) {
      bindAdditionalParams(iParams[3], context);
    }
    iContext.setVariable("getNeighbors", 0);
    if (paramSourceVertex == null || paramDestinationVertex == null) {
      return new LinkedList<>();
    }
    return internalExecute(iContext, iContext.getDatabaseSession());
  }

  private LinkedList<Vertex> internalExecute(
      final CommandContext iContext, DatabaseSessionEmbedded graph) {

    var start = paramSourceVertex;
    var goal = paramDestinationVertex;

    open.add(start);

    // The cost of going from start to start is zero.
    gScore.put(start, 0.0);
    // For the first node, that value is completely heuristic.
    fScore.put(start, getHeuristicCost(start, null, goal, iContext));

    while (!open.isEmpty()) {
      var current = open.poll();

      if (paramEmptyIfMaxDepth && currentDepth >= paramMaxDepth) {
        route.clear(); // to ensure our result is empty
        return getPath();
      }
      // if start and goal vertex is equal so return current path from  cameFrom hash map
      if (current.getIdentity().equals(goal.getIdentity()) || currentDepth >= paramMaxDepth) {

        while (current != null) {
          route.add(0, current);
          current = cameFrom.get(current);
        }
        return getPath();
      }

      closedSet.add(current);
      for (var neighborEdge : getNeighborEdges(current)) {

        var neighbor = getNeighbor(graph, current, neighborEdge, graph);
        // Ignore the neighbor which is already evaluated.
        if (closedSet.contains(neighbor)) {
          continue;
        }
        // The distance from start to a neighbor
        var tentativeGScore = gScore.get(current) + getDistance(neighborEdge);
        var contains = open.contains(neighbor);

        if (!contains || tentativeGScore < gScore.get(neighbor)) {
          gScore.put(neighbor, tentativeGScore);
          fScore.put(
              neighbor, tentativeGScore + getHeuristicCost(neighbor, current, goal, iContext));

          if (contains) {
            open.remove(neighbor);
          }
          open.offer(neighbor);
          cameFrom.put(neighbor, current);
        }
      }

      // Increment Depth Level
      currentDepth++;
    }

    return getPath();
  }

  private static Vertex getNeighbor(DatabaseSessionEmbedded db, Vertex current, Edge neighborEdge,
      DatabaseSessionEmbedded graph) {
    if (neighborEdge.getFrom().equals(current)) {
      return toVertex(neighborEdge.getTo(), db);
    }
    return toVertex(neighborEdge.getFrom(), db);
  }

  @Nullable
  private static Vertex toVertex(Identifiable outVertex, DatabaseSessionEmbedded db) {
    if (outVertex == null) {
      return null;
    }
    if (!(outVertex instanceof Entity)) {
      var transaction = db.getActiveTransaction();
      outVertex = transaction.load(outVertex);
    }
    return ((Entity) outVertex).asVertexOrNull();
  }

  protected Set<Edge> getNeighborEdges(final Vertex node) {
    context.incrementVariable("getNeighbors");

    final Set<Edge> neighbors = new HashSet<Edge>();
    if (node != null) {
      for (var v : node.getEdges(paramDirection, paramEdgeTypeNames)) {
        final var ov = v;
        if (ov != null) {
          neighbors.add(ov);
        }
      }
    }
    return neighbors;
  }

  private void bindAdditionalParams(Object additionalParams, SQLFunctionAstar ctx) {
    if (additionalParams == null) {
      return;
    }
    var db = context.getDatabaseSession();
    Map<String, ?> mapParams = null;
    if (additionalParams instanceof Map m) {
      mapParams = m;
    } else if (additionalParams instanceof Identifiable id) {
      var transaction = db.getActiveTransaction();
      mapParams = ((EntityImpl) transaction.load(id)).toMap();
    }
    if (mapParams != null) {
      ctx.paramEdgeTypeNames = stringArray(mapParams.get(SQLFunctionAstar.PARAM_EDGE_TYPE_NAMES));
      ctx.paramVertexAxisNames =
          stringArray(mapParams.get(SQLFunctionAstar.PARAM_VERTEX_AXIS_NAMES));
      if (mapParams.get(SQLFunctionAstar.PARAM_DIRECTION) != null) {
        if (mapParams.get(SQLFunctionAstar.PARAM_DIRECTION) instanceof String) {
          ctx.paramDirection =
              Direction.valueOf(
                  stringOrDefault(mapParams.get(SQLFunctionAstar.PARAM_DIRECTION), "OUT")
                      .toUpperCase(Locale.ENGLISH));
        } else {
          ctx.paramDirection = (Direction) mapParams.get(SQLFunctionAstar.PARAM_DIRECTION);
        }
      }

      ctx.paramParallel = booleanOrDefault(mapParams.get(SQLFunctionAstar.PARAM_PARALLEL), false);
      ctx.paramMaxDepth =
          longOrDefault(mapParams.get(SQLFunctionAstar.PARAM_MAX_DEPTH), ctx.paramMaxDepth);
      ctx.paramEmptyIfMaxDepth =
          booleanOrDefault(
              mapParams.get(SQLFunctionAstar.PARAM_EMPTY_IF_MAX_DEPTH), ctx.paramEmptyIfMaxDepth);
      ctx.paramTieBreaker =
          booleanOrDefault(mapParams.get(SQLFunctionAstar.PARAM_TIE_BREAKER), ctx.paramTieBreaker);
      ctx.paramDFactor =
          doubleOrDefault(mapParams.get(SQLFunctionAstar.PARAM_D_FACTOR), ctx.paramDFactor);
      if (mapParams.get(SQLFunctionAstar.PARAM_HEURISTIC_FORMULA) != null) {
        if (mapParams.get(SQLFunctionAstar.PARAM_HEURISTIC_FORMULA) instanceof String) {
          ctx.paramHeuristicFormula =
              HeuristicFormula.valueOf(
                  stringOrDefault(
                      mapParams.get(SQLFunctionAstar.PARAM_HEURISTIC_FORMULA), "MANHATAN")
                      .toUpperCase(Locale.ENGLISH));
        } else {
          ctx.paramHeuristicFormula =
              (HeuristicFormula) mapParams.get(SQLFunctionAstar.PARAM_HEURISTIC_FORMULA);
        }
      }

      ctx.paramCustomHeuristicFormula =
          stringOrDefault(mapParams.get(SQLFunctionAstar.PARAM_CUSTOM_HEURISTIC_FORMULA), "");
    }
  }

  public String getSyntax(DatabaseSessionEmbedded session) {
    return "astar(<sourceVertex>, <destinationVertex>, <weightEdgeFieldName>, [<options>]) \n"
        + " // options  : {direction:\"OUT\",edgeTypeNames:[] , vertexAxisNames:[] ,"
        + " parallel : false ,"
        + " tieBreaker:true,maxDepth:99999,dFactor:1.0,customHeuristicFormula:'custom_Function_Name_here'"
        + "  }";
  }

  @Override
  public Object getResult() {
    return getPath();
  }

  @Override
  protected double getDistance(final Vertex node, final Vertex parent, final Vertex target) {
    final var edges = node.getEdges(paramDirection).iterator();
    Edge e = null;
    while (edges.hasNext()) {
      var next = edges.next();
      if (next.getFrom().equals(target) || next.getTo().equals(target)) {
        e = next;
        break;
      }
    }
    if (e != null && e.isStateful()) {
      final var fieldValue = e.asStatefulEdge().getProperty(paramWeightFieldName);
      if (fieldValue != null) {
        if (fieldValue instanceof Float f) {
          return f;
        } else if (fieldValue instanceof Number n) {
          return n.doubleValue();
        }
      }
    }

    return MIN;
  }

  protected double getDistance(final Edge edge) {
    if (edge != null && edge.isStateful()) {
      final var fieldValue = edge.asStatefulEdge().getProperty(paramWeightFieldName);
      if (fieldValue != null) {
        if (fieldValue instanceof Float f) {
          return f;
        } else if (fieldValue instanceof Number n) {
          return n.doubleValue();
        }
      }
    }

    return MIN;
  }

  @Override
  public boolean aggregateResults() {
    return false;
  }

  @Override
  protected double getHeuristicCost(
      final Vertex node, Vertex parent, final Vertex target, CommandContext iContext) {
    var hresult = 0.0;

    if (paramVertexAxisNames.length == 0) {
      return hresult;
    } else if (paramVertexAxisNames.length == 1) {
      double n = doubleOrDefault(node.getProperty(paramVertexAxisNames[0]), 0.0);
      double g = doubleOrDefault(target.getProperty(paramVertexAxisNames[0]), 0.0);
      hresult = getSimpleHeuristicCost(n, g, paramDFactor);
    } else if (paramVertexAxisNames.length == 2) {
      if (parent == null) {
        parent = node;
      }
      double sx = doubleOrDefault(paramSourceVertex.getProperty(paramVertexAxisNames[0]), 0);
      double sy = doubleOrDefault(paramSourceVertex.getProperty(paramVertexAxisNames[1]), 0);
      double nx = doubleOrDefault(node.getProperty(paramVertexAxisNames[0]), 0);
      double ny = doubleOrDefault(node.getProperty(paramVertexAxisNames[1]), 0);
      double px = doubleOrDefault(parent.getProperty(paramVertexAxisNames[0]), 0);
      double py = doubleOrDefault(parent.getProperty(paramVertexAxisNames[1]), 0);
      double gx = doubleOrDefault(target.getProperty(paramVertexAxisNames[0]), 0);
      double gy = doubleOrDefault(target.getProperty(paramVertexAxisNames[1]), 0);

      switch (paramHeuristicFormula) {
        case MANHATAN:
          hresult = getManhatanHeuristicCost(nx, ny, gx, gy, paramDFactor);
          break;
        case MAXAXIS:
          hresult = getMaxAxisHeuristicCost(nx, ny, gx, gy, paramDFactor);
          break;
        case DIAGONAL:
          hresult = getDiagonalHeuristicCost(nx, ny, gx, gy, paramDFactor);
          break;
        case EUCLIDEAN:
          hresult = getEuclideanHeuristicCost(nx, ny, gx, gy, paramDFactor);
          break;
        case EUCLIDEANNOSQR:
          hresult = getEuclideanNoSQRHeuristicCost(nx, ny, gx, gy, paramDFactor);
          break;
        case CUSTOM:
          hresult =
              getCustomHeuristicCost(
                  paramCustomHeuristicFormula,
                  paramVertexAxisNames,
                  paramSourceVertex,
                  paramDestinationVertex,
                  node,
                  parent,
                  currentDepth,
                  paramDFactor,
                  iContext);
          break;
      }
      if (paramTieBreaker) {
        hresult = getTieBreakingHeuristicCost(px, py, sx, sy, gx, gy, hresult);
      }

    } else {
      Map<String, Double> sList = new HashMap<String, Double>();
      Map<String, Double> cList = new HashMap<String, Double>();
      Map<String, Double> pList = new HashMap<String, Double>();
      Map<String, Double> gList = new HashMap<String, Double>();
      parent = parent == null ? node : parent;
      for (var i = 0; i < paramVertexAxisNames.length; i++) {
        var s = doubleOrDefault(paramSourceVertex.getProperty(paramVertexAxisNames[i]), 0);
        var c = doubleOrDefault(node.getProperty(paramVertexAxisNames[i]), 0);
        var g = doubleOrDefault(target.getProperty(paramVertexAxisNames[i]), 0);
        var p = doubleOrDefault(parent.getProperty(paramVertexAxisNames[i]), 0);
        if (s != null) {
          sList.put(paramVertexAxisNames[i], s);
        }
        if (c != null) {
          cList.put(paramVertexAxisNames[i], s);
        }
        if (g != null) {
          gList.put(paramVertexAxisNames[i], g);
        }
        if (p != null) {
          pList.put(paramVertexAxisNames[i], p);
        }
      }
      switch (paramHeuristicFormula) {
        case MANHATAN:
          hresult =
              getManhatanHeuristicCost(
                  paramVertexAxisNames, sList, cList, pList, gList, currentDepth, paramDFactor);
          break;
        case MAXAXIS:
          hresult =
              getMaxAxisHeuristicCost(
                  paramVertexAxisNames, sList, cList, pList, gList, currentDepth, paramDFactor);
          break;
        case DIAGONAL:
          hresult =
              getDiagonalHeuristicCost(
                  paramVertexAxisNames, sList, cList, pList, gList, currentDepth, paramDFactor);
          break;
        case EUCLIDEAN:
          hresult =
              getEuclideanHeuristicCost(
                  paramVertexAxisNames, sList, cList, pList, gList, currentDepth, paramDFactor);
          break;
        case EUCLIDEANNOSQR:
          hresult =
              getEuclideanNoSQRHeuristicCost(
                  paramVertexAxisNames, sList, cList, pList, gList, currentDepth, paramDFactor);
          break;
        case CUSTOM:
          hresult =
              getCustomHeuristicCost(
                  paramCustomHeuristicFormula,
                  paramVertexAxisNames,
                  paramSourceVertex,
                  paramDestinationVertex,
                  node,
                  parent,
                  currentDepth,
                  paramDFactor,
                  iContext);
          break;
      }
      if (paramTieBreaker) {
        hresult =
            getTieBreakingHeuristicCost(
                paramVertexAxisNames, sList, cList, pList, gList, currentDepth, hresult);
      }
    }

    return hresult;
  }

  @Override
  protected boolean isVariableEdgeWeight() {
    return true;
  }
}
