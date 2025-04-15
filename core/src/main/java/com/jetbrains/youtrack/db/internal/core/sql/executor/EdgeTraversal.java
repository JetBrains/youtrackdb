package com.jetbrains.youtrack.db.internal.core.sql.executor;

import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLRid;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLWhereClause;

/**
 *
 */
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
}
