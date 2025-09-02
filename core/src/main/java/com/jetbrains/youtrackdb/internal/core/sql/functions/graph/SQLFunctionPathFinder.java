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

import com.jetbrains.youtrackdb.api.record.Direction;
import com.jetbrains.youtrackdb.api.record.RID;
import com.jetbrains.youtrackdb.api.record.Vertex;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandExecutorAbstract;
import com.jetbrains.youtrackdb.internal.core.sql.functions.math.SQLFunctionMathAbstract;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Abstract class to find paths between nodes.
 */
public abstract class SQLFunctionPathFinder extends SQLFunctionMathAbstract {

  protected Set<Vertex> unSettledNodes;
  protected Map<RID, Vertex> predecessors;
  protected Map<RID, Float> distance;

  protected Vertex paramSourceVertex;
  protected Vertex paramDestinationVertex;
  protected Direction paramDirection = Direction.OUT;
  protected CommandContext context;

  protected static final float MIN = 0f;

  public SQLFunctionPathFinder(final String iName, final int iMinParams, final int iMaxParams) {
    super(iName, iMinParams, iMaxParams);
  }

  protected LinkedList<Vertex> execute(final CommandContext iContext) {
    context = iContext;
    unSettledNodes = new HashSet<Vertex>();
    distance = new HashMap<RID, Float>();
    predecessors = new HashMap<RID, Vertex>();
    distance.put(paramSourceVertex.getIdentity(), MIN);
    unSettledNodes.add(paramSourceVertex);

    var maxDistances = 0;
    var maxSettled = 0;
    var maxUnSettled = 0;
    var maxPredecessors = 0;

    while (continueTraversing()) {
      final var node = getMinimum(unSettledNodes);
      unSettledNodes.remove(node);
      findMinimalDistances(node);

      if (distance.size() > maxDistances) {
        maxDistances = distance.size();
      }
      if (unSettledNodes.size() > maxUnSettled) {
        maxUnSettled = unSettledNodes.size();
      }
      if (predecessors.size() > maxPredecessors) {
        maxPredecessors = predecessors.size();
      }

      if (!isVariableEdgeWeight() && distance.containsKey(paramDestinationVertex.getIdentity()))
      // FOUND
      {
        break;
      }

      if (!CommandExecutorAbstract.checkInterruption(context)) {
        break;
      }
    }

    context.setVariable("maxDistances", maxDistances);
    context.setVariable("maxSettled", maxSettled);
    context.setVariable("maxUnSettled", maxUnSettled);
    context.setVariable("maxPredecessors", maxPredecessors);

    distance = null;

    return getPath();
  }

  protected boolean isVariableEdgeWeight() {
    return false;
  }

  /*
   * This method returns the path from the source to the selected target and NULL if no path exists
   */
  @Nullable
  public LinkedList<Vertex> getPath() {
    final var path = new LinkedList<Vertex>();
    var step = paramDestinationVertex;
    // Check if a path exists
    if (predecessors.get(step.getIdentity()) == null) {
      return null;
    }

    path.add(step);
    while (predecessors.get(step.getIdentity()) != null) {
      step = predecessors.get(step.getIdentity());
      path.add(step);
    }
    // Put it into the correct order
    Collections.reverse(path);
    return path;
  }

  public boolean aggregateResults() {
    return false;
  }

  @Override
  public Object getResult() {
    return getPath();
  }

  protected void findMinimalDistances(final Vertex node) {
    for (var neighbor : getNeighbors(node)) {
      final var d = sumDistances(getShortestDistance(node), getDistance(node, neighbor));

      if (getShortestDistance(neighbor) > d) {
        distance.put(neighbor.getIdentity(), d);
        predecessors.put(neighbor.getIdentity(), node);
        unSettledNodes.add(neighbor);
      }
    }
  }

  protected Set<Vertex> getNeighbors(final Vertex node) {
    context.incrementVariable("getNeighbors");

    final Set<Vertex> neighbors = new HashSet<Vertex>();
    if (node != null) {
      for (var v : node.getVertices(paramDirection)) {
        final var ov = v;
        if (ov != null && isNotSettled(ov)) {
          neighbors.add(ov);
        }
      }
    }
    return neighbors;
  }

  protected Vertex getMinimum(final Set<Vertex> vertexes) {
    Vertex minimum = null;
    Float minimumDistance = null;
    for (var vertex : vertexes) {
      if (minimum == null || getShortestDistance(vertex) < minimumDistance) {
        minimum = vertex;
        minimumDistance = getShortestDistance(minimum);
      }
    }
    return minimum;
  }

  protected boolean isNotSettled(final Vertex vertex) {
    return unSettledNodes.contains(vertex) || !distance.containsKey(vertex.getIdentity());
  }

  protected boolean continueTraversing() {
    return unSettledNodes.size() > 0;
  }

  protected float getShortestDistance(final Vertex destination) {
    if (destination == null) {
      return Float.MAX_VALUE;
    }

    final var d = distance.get(destination.getIdentity());
    return d == null ? Float.MAX_VALUE : d;
  }

  protected float sumDistances(final float iDistance1, final float iDistance2) {
    return iDistance1 + iDistance2;
  }

  protected abstract float getDistance(final Vertex node, final Vertex target);
}
