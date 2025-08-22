package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLRid;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;

public class EdgeTraversal {

  protected boolean out = true;
  public PatternEdge edge;
  private String leftClass;
  private SQLRid leftRid;
  private SQLWhereClause leftFilter;

  public EdgeTraversal(PatternEdge edge, boolean out) {
    this.edge = edge;
    this.out = out;
  }

  public void setLeftClass(String leftClass) {
    this.leftClass = leftClass;
  }

  public void setLeftFilter(SQLWhereClause leftFilter) {
    this.leftFilter = leftFilter;
  }

  public String getLeftClass() {
    return leftClass;
  }

  public SQLRid getLeftRid() {
    return leftRid;
  }

  public void setLeftRid(SQLRid leftRid) {
    this.leftRid = leftRid;
  }

  public SQLWhereClause getLeftFilter() {
    return leftFilter;
  }

  @Override
  public String toString() {
    return edge.toString();
  }

  public EdgeTraversal copy() {
    var copy = new EdgeTraversal(edge, out);

    copy.leftClass = leftClass;
    if (leftFilter != null) {
      copy.leftFilter = leftFilter.copy();
    }
    if (leftRid != null) {
      copy.leftRid = leftRid.copy();
    }
    return copy;
  }
}
