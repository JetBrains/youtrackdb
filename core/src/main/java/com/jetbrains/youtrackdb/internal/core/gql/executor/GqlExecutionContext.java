package com.jetbrains.youtrackdb.internal.core.gql.executor;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphInternal;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nonnull;

/// Context for GQL query execution.
///
/// Contains the graph, database session, and query parameters.
public class GqlExecutionContext {

  private final YTDBGraphInternal graph;
  private final DatabaseSessionEmbedded session;
  private final Map<String, Object> parameters;

  public GqlExecutionContext(
      @Nonnull YTDBGraphInternal graph,
      @Nonnull DatabaseSessionEmbedded session) {
    this(graph, session, new HashMap<>());
  }

  public GqlExecutionContext(
      @Nonnull YTDBGraphInternal graph,
      @Nonnull DatabaseSessionEmbedded session,
      @Nonnull Map<String, Object> parameters) {
    this.graph = graph;
    this.session = session;
    this.parameters = parameters;
  }

  public YTDBGraphInternal getGraph() {
    return graph;
  }

  public DatabaseSessionEmbedded getSession() {
    return session;
  }

  public Map<String, Object> getParameters() {
    return parameters;
  }

  public Object getParameter(String name) {
    return parameters.get(name);
  }
}
