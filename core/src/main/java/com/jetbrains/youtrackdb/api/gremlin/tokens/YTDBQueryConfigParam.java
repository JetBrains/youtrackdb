package com.jetbrains.youtrackdb.api.gremlin.tokens;

/// YTDB-specific parameters that can be passed to
/// [[com.jetbrains.youtrackdb.api.gremlin.YTDBGraphTraversalSourceDSL#with(YTDBQueryConfigParam, Object)]] and
/// [[com.jetbrains.youtrackdb.api.gremlin.YTDBGraphTraversalSourceDSL#with(YTDBQueryConfigParam)]]
/// methods to configure query behavior.
public enum YTDBQueryConfigParam {

  /// Controls whether the query is polymorphic, i.e., subclasses can be queried by their parent
  /// classes' names.
  polymorphicQuery(Boolean.class),

  /// Controls whether a global-scope `order()` step keeps a record that does not carry the
  /// ordered property. `true` orders such a record as a null key, the way YQL `ORDER BY` does.
  /// `false` restores portable TinkerPop behaviour, where the by-modulator produces nothing and
  /// the record is dropped. Overrides
  /// [[com.jetbrains.youtrackdb.api.config.GlobalConfiguration#QUERY_GREMLIN_ORDER_INCLUDES_MISSING_KEY]]
  /// for one traversal. Local-scope order and the `select`, `values`, `group` and `dedup`
  /// modulators keep their filtering behaviour under either value.
  orderIncludesMissingKey(Boolean.class),

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
