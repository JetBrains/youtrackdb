package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchPathItem;
import java.util.LinkedHashSet;
import java.util.Set;

public class PatternNode {

  public String alias;
  public Set<PatternEdge> out = new LinkedHashSet<PatternEdge>();
  public Set<PatternEdge> in = new LinkedHashSet<PatternEdge>();
  public boolean optional = false;

  public int addEdge(SQLMatchPathItem item, PatternNode to) {
    var edge = new PatternEdge();
    edge.item = item;
    edge.out = this;
    edge.in = to;
    this.out.add(edge);
    to.in.add(edge);
    return 1;
  }

  public boolean isOptionalNode() {
    return optional;
  }

  public PatternNode copy() {
    var copy = new PatternNode();
    copy.alias = alias;
    copy.optional = optional;

    for (var edge : out) {
      copy.addEdge(edge.item, edge.in);
    }

    return copy;
  }
}
