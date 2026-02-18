package com.jetbrains.youtrackdb.internal.core.gql.executor;

import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.gql.executor.resultset.GqlExecutionStream;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBVertexImpl;
import java.util.HashMap;
import java.util.Objects;
import javax.annotation.Nullable;

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

  /// Create a step that fetches vertices from a class.
  ///
  /// @param alias       The variable name to bind vertices to (e.g., "a")
  /// @param className   The class/label to fetch from (e.g., "Person")
  /// @param polymorphic Whether to include subclasses
  public GqlFetchFromClassStep(@Nullable String alias, @Nullable String className, boolean polymorphic) {
    this.alias = alias;
    this.className = className;
    this.polymorphic = polymorphic;
  }

  @Override
  protected GqlExecutionStream internalStart(GqlExecutionContext ctx) {

    com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphInternal graph;
    com.jetbrains.youtrackdb.internal.core.iterator.RecordIteratorClass entityIterator = null;
    var session = Objects.requireNonNull(ctx).session();
    graph = ctx.graph();
    if (prev != null) {
      throw new CommandExecutionException(
          Objects.requireNonNull(Objects.requireNonNull(session).getDatabaseName()),
          "Match can be only start for now");
    }

    var schema = Objects.requireNonNull(Objects.requireNonNull(session).getMetadata())
        .getImmutableSchemaSnapshot();
    assert schema != null;
    if (Objects.requireNonNull(schema).getClass(Objects.requireNonNull(className)) == null) {
      throw new CommandExecutionException(Objects.requireNonNull(session.getDatabaseName()),
          "Class '" + className + "' not found");
    }

    try {
      entityIterator = session.browseClass(Objects.requireNonNull(className), polymorphic);
      return GqlExecutionStream.fromIterator(entityIterator, entity -> {
        var vertex = new YTDBVertexImpl(Objects.requireNonNull(graph), Objects.requireNonNull(
            entity).asVertex());
        var row = new HashMap<String, Object>();
        row.put(alias, vertex);
        return row;
      });
    } catch (Exception e) {
      if (entityIterator != null) {
        entityIterator.close();
      }
      throw e;
    }
  }

  public @Nullable String getAlias() {
    return alias;
  }

  public @Nullable String getClassName() {
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
    var copy = new GqlFetchFromClassStep(alias, className, polymorphic);
    if (prev != null) {
      copy.setPrevious(prev.copy());
    }
    return copy;
  }
}
