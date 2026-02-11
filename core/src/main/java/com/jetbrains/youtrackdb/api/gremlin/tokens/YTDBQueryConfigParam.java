package com.jetbrains.youtrackdb.api.gremlin.tokens;

/// YTDB-specific parameters that can be passed to
/// [[com.jetbrains.youtrackdb.api.gremlin.YTDBGraphTraversalSource#with(YTDBQueryConfigParam,
/// Object)]] and
/// [[com.jetbrains.youtrackdb.api.gremlin.YTDBGraphTraversalSource#with(YTDBQueryConfigParam)]]
/// methods to configure query behavior.
public enum YTDBQueryConfigParam {

  /// Controls whether the query is polymorphic, i.e., subclasses can be queried by their parent
  /// classes' names.
  polymorphicQuery(Boolean.class),

  /// Client-provided query summary for query monitoring purposes.
  querySummary(String.class);

  private final Class<?> type;

  YTDBQueryConfigParam(Class<?> type) {
    this.type = type;
  }

  public Class<?> type() {
    return type;
  }
}
