package com.jetbrains.youtrackdb.internal.core.gql.executor;

import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.gql.executor.resultset.GqlExecutionStream;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBVertexImpl;
import java.util.Map;

/// Execution step that fetches all vertices of a class.
///
/// Similar to SQL's FetchFromClassExecutionStep, but returns vertices
/// wrapped in variable binding maps for GQL.
///
/// Result type depends on whether alias was provided:
/// - With alias (MATCH (a:Person)): Map<String, Object> with {"a": vertex}
/// - Without alias (MATCH (:Person)): just the Vertex (no side effects)
///
@SuppressWarnings("unused")
public class GqlFetchFromClassStep extends GqlAbstractExecutionStep {

  private final String alias;
  private final String className;
  private final boolean polymorphic;
  private final boolean hasAlias;

  /// Create a step that fetches vertices from a class.
  ///
  /// @param alias       The variable name to bind vertices to (e.g., "a"), or null if no alias in query
  /// @param className   The class/label to fetch from (e.g., "Person")
  /// @param polymorphic Whether to include subclasses
  /// @param hasAlias    Whether alias was explicitly provided (if false, returns Vertex directly)
  public GqlFetchFromClassStep(String alias, String className, boolean polymorphic,
      boolean hasAlias) {
    this.alias = alias;
    this.className = className;
    this.polymorphic = polymorphic;
    this.hasAlias = hasAlias;
  }

  @Override
  protected GqlExecutionStream internalStart(GqlExecutionContext ctx) {

    com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphInternal graph;
    com.jetbrains.youtrackdb.internal.core.iterator.RecordIteratorClass entityIterator;
    try (var session = ctx.session()) {
      graph = ctx.graph();
      if (prev != null) {
        throw new CommandExecutionException(session.getDatabaseName(),
            "Match can be only start for now");
      }

      var schema = session.getMetadata().getImmutableSchemaSnapshot();
      if (schema.getClass(className) == null) {
        throw new CommandExecutionException(session.getDatabaseName(),
            "Class '" + className + "' not found");
      }

      entityIterator = session.browseClass(className, polymorphic);
    }

    if (hasAlias) {
      // With alias: return Map with binding (side effect)
      return GqlExecutionStream.fromIterator(entityIterator, entity -> {
        var vertex = new YTDBVertexImpl(graph, entity.asVertex());
        return Map.of(alias, vertex);
      });
    } else {
      // Without alias: return just the vertex (no side effects)
      return GqlExecutionStream.fromIterator(entityIterator,
          entity -> new YTDBVertexImpl(graph, entity.asVertex()));
    }
  }

  public String getAlias() {
    return alias;
  }

  public String getClassName() {
    return className;
  }

  public boolean isPolymorphic() {
    return polymorphic;
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    var aliasDisplay = alias != null ? alias : "_";
    return "  ".repeat(depth * indent) +
        "GqlFetchFromClassStep(" + aliasDisplay + ":" + className +
        ", polymorphic=" + polymorphic + ")";
  }

  @Override
  public GqlExecutionStep copy() {
    var copy = new GqlFetchFromClassStep(alias, className, polymorphic, hasAlias);
    if (prev != null) {
      copy.setPrevious(prev.copy());
    }
    return copy;
  }
}
