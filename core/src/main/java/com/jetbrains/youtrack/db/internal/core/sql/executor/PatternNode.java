package com.jetbrains.youtrack.db.internal.core.sql.executor;

import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLMatchPathItem;
import java.util.LinkedHashSet;
import java.util.Set;

public class PatternNode {

  public String alias;
  public Set<PatternEdge> out = new LinkedHashSet<PatternEdge>();
  public Set<PatternEdge> in = new LinkedHashSet<PatternEdge>();
  public int centrality = 0;
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
}
