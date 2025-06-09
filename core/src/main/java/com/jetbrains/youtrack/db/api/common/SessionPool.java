package com.jetbrains.youtrack.db.api.common;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.exception.AcquireTimeoutException;
import com.jetbrains.youtrack.db.api.gremlin.YTDBGraph;

public interface SessionPool<S extends BasicDatabaseSession<?, ?>> extends AutoCloseable {

  S acquire() throws AcquireTimeoutException;

  boolean isClosed();

  @Override
  void close();

  String getDbName();

  String getUserName();

  /// Represents session pool as TinkerPop graph, if you want to achieve the highest level of
  /// performance, it is recommended to use [DatabaseSession#asGraph()] instead.
  ///
  /// This method is implemented only for the embedded version of the database.
  YTDBGraph asGraph();
}
