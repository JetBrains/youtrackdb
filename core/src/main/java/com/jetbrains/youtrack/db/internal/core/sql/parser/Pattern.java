package com.jetbrains.youtrack.db.internal.core.sql.parser;

import com.jetbrains.youtrack.db.api.exception.CommandSQLParsingException;
import com.jetbrains.youtrack.db.internal.core.sql.executor.PatternEdge;
import com.jetbrains.youtrack.db.internal.core.sql.executor.PatternNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 *
 */
public class Pattern {

  public Map<String, PatternNode> aliasToNode = new LinkedHashMap<String, PatternNode>();
  public int numOfEdges = 0;

  public void addExpression(SQLMatchExpression expression) {
    var originNode = getOrCreateNode(expression.origin);

    for (var item : expression.items) {
      var nextAlias = item.filter.getAlias();
      var nextNode = getOrCreateNode(item.filter);

      numOfEdges += originNode.addEdge(item, nextNode);
      originNode = nextNode;
    }
  }

  private PatternNode getOrCreateNode(SQLMatchFilter origin) {
    var originNode = get(origin.getAlias());
    if (originNode == null) {
      originNode = new PatternNode();
      originNode.alias = origin.getAlias();
      aliasToNode.put(originNode.alias, originNode);
    }
    if (origin.isOptional()) {
      originNode.optional = true;
    }
    return originNode;
  }

  public PatternNode get(String alias) {
    return aliasToNode.get(alias);
  }

  public int getNumOfEdges() {
    return numOfEdges;
  }

  public void validate() {
    for (var node : this.aliasToNode.values()) {
      if (node.isOptionalNode()) {
        if (node.out.size() > 0) {
          throw new CommandSQLParsingException(
              "In current MATCH version, optional nodes are allowed only on right terminal nodes,"
                  + " eg. {} --> {optional:true} is allowed, {optional:true} <-- {} is not. ");
        }
        if (node.in.size() == 0) {
          throw new CommandSQLParsingException(
              "In current MATCH version, optional nodes must have at least one incoming pattern"
                  + " edge");
        }
        //        if (node.in.size() != 1) {
        //          throw new CommandSQLParsingException("In current MATCH version, optional nodes
        // are allowed only as single terminal nodes. ");
        //        }
      }
    }
  }

  /**
   * splits this pattern into multiple
   *
   * @return
   */
  public List<Pattern> getDisjointPatterns() {
    Map<PatternNode, String> reverseMap = new IdentityHashMap<>();
    reverseMap.putAll(
        this.aliasToNode.entrySet().stream()
            .collect(Collectors.toMap(x -> x.getValue(), x -> x.getKey())));

    List<Pattern> result = new ArrayList<>();
    while (!reverseMap.isEmpty()) {
      var pattern = new Pattern();
      result.add(pattern);
      var nextNode = reverseMap.entrySet().iterator().next();
      Set<PatternNode> toVisit = new HashSet<>();
      toVisit.add(nextNode.getKey());
      while (toVisit.size() > 0) {
        var currentNode = toVisit.iterator().next();
        toVisit.remove(currentNode);
        if (reverseMap.containsKey(currentNode)) {
          pattern.aliasToNode.put(reverseMap.get(currentNode), currentNode);
          reverseMap.remove(currentNode);
          for (var x : currentNode.out) {
            toVisit.add(x.in);
          }
          for (var x : currentNode.in) {
            toVisit.add(x.out);
          }
        }
      }
      pattern.recalculateNumOfEdges();
    }
    return result;
  }

  private void recalculateNumOfEdges() {
    Map<PatternEdge, PatternEdge> edges = new IdentityHashMap<>();
    for (var node : this.aliasToNode.values()) {
      for (var edge : node.out) {
        edges.put(edge, edge);
      }
      for (var edge : node.in) {
        edges.put(edge, edge);
      }
    }
    this.numOfEdges = edges.size();
  }

  public Map<String, PatternNode> getAliasToNode() {
    return aliasToNode;
  }

  public void setAliasToNode(Map<String, PatternNode> aliasToNode) {
    this.aliasToNode = aliasToNode;
  }

  public void setNumOfEdges(int numOfEdges) {
    this.numOfEdges = numOfEdges;
  }
}
