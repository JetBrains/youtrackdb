package com.jetbrains.youtrackdb.internal.core.db;

import com.jetbrains.youtrackdb.internal.core.exception.AcquireTimeoutException;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraph;

public interface SessionPool extends AutoCloseable {

  DatabaseSessionEmbedded acquire() throws AcquireTimeoutException;

  boolean isClosed();

  @Override
  void close();

  String getDbName();

  String getUserName();

  /// Represents session pool as TinkerPop graph, if you want to achieve the highest level of
  /// performance, it is recommended to use [DatabaseSessionEmbedded#asGraph()] instead.
  ///
  /// This method is implemented only for the embedded version of the database.
  YTDBGraph asGraph();
}
