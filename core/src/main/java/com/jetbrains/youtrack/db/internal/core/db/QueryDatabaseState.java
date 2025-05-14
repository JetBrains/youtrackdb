package com.jetbrains.youtrack.db.internal.core.db;

import com.jetbrains.youtrack.db.api.common.query.BasicResultSet;

public class QueryDatabaseState<RS extends BasicResultSet<?>> {
  private RS resultSet = null;

  public QueryDatabaseState() {
  }

  public QueryDatabaseState(RS resultSet) {
    this.resultSet = resultSet;
  }

  public void setResultSet(RS resultSet) {
    this.resultSet = resultSet;
  }

  public RS getResultSet() {
    return resultSet;
  }

  public void close() {
    if (resultSet != null) {
      resultSet.close();
    }

  }
}
