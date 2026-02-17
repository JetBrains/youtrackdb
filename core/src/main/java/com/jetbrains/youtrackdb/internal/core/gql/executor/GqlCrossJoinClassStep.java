package com.jetbrains.youtrackdb.internal.core.gql.executor;

import com.jetbrains.youtrackdb.internal.core.gql.executor.resultset.GqlExecutionStream;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBVertexImpl;
import java.util.LinkedHashMap;
import java.util.Map;

/// Execution step that performs a Cartesian product (Cross Join) between
/// the current results and all vertices of a specified class.
///
/// For each row in the upstream, it iterates over all vertices of the class
/// and produces a new Map containing all previous bindings plus the new one.
public class GqlCrossJoinClassStep extends GqlAbstractExecutionStep {

  private final String alias;
  private final String className;
  private final boolean polymorphic;

  public GqlCrossJoinClassStep(String alias, String className, boolean polymorphic) {
    this.alias = alias;
    this.className = className;
    this.polymorphic = polymorphic;
  }

  @SuppressWarnings("unchecked")
  @Override
  protected GqlExecutionStream internalStart(GqlExecutionContext ctx) {
    if (prev == null) {
      throw new IllegalStateException("CrossJoinStep requires a previous step");
    }

    var upstream = prev.start(ctx);
    var graph = ctx.graph();
    var session = ctx.session();

    var schema = session.getMetadata().getImmutableSchemaSnapshot();
    if (schema.getClass(className) == null) {
      upstream.close();
      throw new com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException(
          session.getDatabaseName(), "Class '" + className + "' not found");
    }

    try {
      return upstream.flatMap(input -> {
        var baseRow = (Map<String, Object>) input;

        com.jetbrains.youtrackdb.internal.core.iterator.RecordIteratorClass entityIterator = null;
        try {
          entityIterator = session.browseClass(className, polymorphic);
          return GqlExecutionStream.fromIterator(entityIterator, entity -> {
            Map<String, Object> newRow = new LinkedHashMap<>(baseRow);
            var vertex = new YTDBVertexImpl(graph, entity.asVertex());
            newRow.put(alias, vertex);
            return newRow;
          });
        } catch (Exception e) {
          if (entityIterator != null) {
            entityIterator.close();
          }
          throw e;
        }
      });
    } catch (Exception e) {
      upstream.close();
      throw e;
    }
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    return "  ".repeat(depth * indent) +
        "GqlCrossJoinClassStep(" + (alias != null ? alias : "_") + ":" + className + ")";
  }

  @Override
  public GqlExecutionStep copy() {
    var copy = new GqlCrossJoinClassStep(alias, className, polymorphic);
    if (prev != null) {
      copy.setPrevious(prev.copy());
    }
    return copy;
  }
}
