package com.jetbrains.youtrackdb.internal.core.gql.executor;

import com.jetbrains.youtrackdb.internal.core.gql.executor.resultset.GqlExecutionStream;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBVertexImpl;
import java.util.Map;
import org.apache.tinkerpop.gremlin.util.iterator.IteratorUtils;

/// Execution step that fetches all vertices of a class.
///
/// Similar to SQL's FetchFromClassExecutionStep, but returns vertices
/// wrapped in variable binding maps for GQL.
///
/// For `MATCH (a:Person)`, this step:
/// - Browses all vertices of class "Person"
/// - Creates Map<String, Object> with {"a": vertex} for each vertex
public class GqlFetchFromClassStep extends GqlAbstractExecutionStep {

  private final String alias;
  private final String className;
  private final boolean polymorphic;

  /// Create a step that fetches vertices from a class.
  ///
  /// @param alias       The variable name to bind vertices to (e.g., "a")
  /// @param className   The class/label to fetch from (e.g., "Person")
  /// @param polymorphic Whether to include subclasses
  public GqlFetchFromClassStep(String alias, String className, boolean polymorphic) {
    this.alias = alias;
    this.className = className;
    this.polymorphic = polymorphic;
  }

  @Override
  protected GqlExecutionStream internalStart(GqlExecutionContext ctx) {
    // Close previous step if any
    if (prev != null) {
      prev.start(ctx).close();
    }

    var session = ctx.getSession();
    var graph = ctx.getGraph();

    // Check if the class exists
    var schema = session.getMetadata().getImmutableSchemaSnapshot();
    if (schema.getClass(className) == null) {
      return GqlExecutionStream.empty();
    }

    // Browse all vertices of the class
    var entityIterator = session.browseClass(className, polymorphic);

    // Convert entities to vertices and wrap in result maps
    var resultIterator = IteratorUtils.map(
        entityIterator,
        entity -> {
          var vertex = new YTDBVertexImpl(graph, entity.asVertex());
          return Map.<String, Object>of(alias, vertex);
        }
    );

    return GqlExecutionStream.fromIterator(resultIterator);
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
    return "  ".repeat(depth * indent) +
        "GqlFetchFromClassStep(" + alias + ":" + className +
        ", polymorphic=" + polymorphic + ")";
  }
}
