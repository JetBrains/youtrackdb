package com.jetbrains.youtrackdb.internal.core.gql.executor;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphInternal;
import java.util.Map;
import javax.annotation.Nonnull;

/// Immutable context for GQL query execution.
///
/// Contains the graph, database session, and query parameters.
public record GqlExecutionContext(YTDBGraphInternal graph, DatabaseSessionEmbedded session,
                                  Map<String, Object> parameters) {

  public GqlExecutionContext(
      @Nonnull YTDBGraphInternal graph,
      @Nonnull DatabaseSessionEmbedded session) {
    this(graph, session, Map.of());
  }

  public GqlExecutionContext(
      @Nonnull YTDBGraphInternal graph,
      @Nonnull DatabaseSessionEmbedded session,
      @Nonnull Map<String, Object> parameters) {
    this.graph = graph;
    this.session = session;
    this.parameters = parameters.isEmpty() ? Map.of() : Map.copyOf(parameters);
  }

  @SuppressWarnings("unused")
  public Object getParameter(String name) {
    return parameters.get(name);
  }
}
