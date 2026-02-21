package com.jetbrains.youtrackdb.internal.core.gremlin.service;

import com.jetbrains.youtrackdb.internal.core.db.record.record.Edge;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Entity;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.db.record.record.StatefulEdge;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Vertex;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphInternal;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBStatefulEdgeImpl;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBVertexImpl;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.query.ResultSet;
import com.jetbrains.youtrackdb.internal.core.util.CloseableIteratorWithCallback;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal.Admin;
import org.apache.tinkerpop.gremlin.process.traversal.Traverser;
import org.apache.tinkerpop.gremlin.process.traversal.traverser.TraverserRequirement;
import org.apache.tinkerpop.gremlin.structure.service.Service;
import org.apache.tinkerpop.gremlin.structure.util.CloseableIterator;
import org.apache.tinkerpop.gremlin.util.iterator.IteratorUtils;
import org.jspecify.annotations.Nullable;

/// TinkerPop service that allows running any YouTrackDB non-idempotent command via GraphTraversal.
///
/// Supports both Start and Streaming execution modes to allow chaining:
/// g.sqlCommand("BEGIN").sqlCommand("INSERT")
public class YTDBCommandService implements Service<Object, Object> {

  public static final String NAME = "command";
  public static final String SQL_COMMAND_NAME = "sqlCommand";
  public static final String COMMAND = "command";
  public static final String ARGUMENTS = "args";

  private final String command;
  private final Map<?, ?> commandParams;
  private final Service.Type type;

  public YTDBCommandService(String command, Map<?, ?> commandParams, Service.Type type) {
    this.command = command;
    this.commandParams = commandParams;
    this.type = type;
  }

  public static class Factory implements ServiceFactory<Object, Object> {

    private final String name;

    public Factory() {
      this(NAME);
    }

    protected Factory(String name) {
      this.name = name;
    }

    @Override
    public String getName() {
      return name;
    }

    @Override
    public Set<Type> getSupportedTypes() {
      // Support both Start and Streaming to allow chaining: g.sqlCommand("BEGIN").sqlCommand("INSERT")
      return Set.of(Type.Start, Type.Streaming);
    }

    @Override
    public Map<Type, Set<TraverserRequirement>> getRequirementsByType() {
      // Return empty requirements for all types - no special requirements needed
      return Map.of(Type.Start, Set.of(), Type.Streaming, Set.of());
    }

    @Override
    public Service<Object, Object> createService(boolean isStart, Map params) {
      final Map<?, ?> safeParams = (params == null) ? Map.of() : params;
      var finalCommand = "";
      Map<?, ?> finalCommandParams = Map.of();

      if (safeParams.get(COMMAND) instanceof String cmd) {
        finalCommand = cmd;
        if (safeParams.get(ARGUMENTS) instanceof Map<?, ?> m) {
          finalCommandParams = m;
        }
      } else if (safeParams.get(ARGUMENTS) instanceof java.util.List<?> argsList
          && !argsList.isEmpty()) {
        if (argsList.getFirst() instanceof String cmd) {
          finalCommand = cmd;
          if (argsList.size() > 1) {
            var rest = argsList.size() - 1;
            if (rest % 2 != 0) {
              throw new IllegalArgumentException(
                  "Arguments must be provided in key-value pairs");
            }
            var map = new java.util.LinkedHashMap<>();
            for (var i = 1; i + 1 < argsList.size(); i += 2) {
              map.put(argsList.get(i), argsList.get(i + 1));
            }
            finalCommandParams = map;
          }
        }
      }

      return new YTDBCommandService(finalCommand, finalCommandParams,
          isStart ? Type.Start : Type.Streaming);
    }
  }

  /// Factory for the sqlCommand service - an alias for command that can be used for chaining.
  public static class SqlCommandFactory extends Factory {

    public SqlCommandFactory() {
      super(SQL_COMMAND_NAME);
    }
  }

  @Override
  public Type getType() {
    return type;
  }

  @Override
  public Set<TraverserRequirement> getRequirements() {
    return Set.of();
  }

  @Override
  public CloseableIterator<Object> execute(ServiceCallContext ctx, Map params) {
    if (command.isEmpty()) {
      return CloseableIterator.of(IteratorUtils.of(true));
    }

    final var graph = ((Admin<?, ?>) ctx.getTraversal())
        .getGraph()
        .orElseThrow(() -> new IllegalStateException("Graph is not available"));

    var resultSet = ((YTDBGraphInternal) graph).executeCommand(command, commandParams);
    if (resultSet == null) {
      // Emit a single placeholder so chaining works (Streaming needs an input traverser).
      return CloseableIterator.of(IteratorUtils.of(true));
    }

    return toResultIterator((YTDBGraphInternal) graph, resultSet);
  }

  @Override
  public CloseableIterator<Object> execute(ServiceCallContext ctx, Traverser.Admin<Object> in,
      Map params) {
    // If command is empty (from getRequirements() call), don't execute
    if (command.isEmpty()) {
      return CloseableIterator.of(IteratorUtils.of(in.get()));
    }

    final var graph = ((Admin<?, ?>) ctx.getTraversal())
        .getGraph()
        .orElseThrow(() -> new IllegalStateException("Graph is not available"));

    var resultSet = ((YTDBGraphInternal) graph).executeCommand(command, commandParams);
    if (resultSet == null) {
      // Pass through the input traverser for Streaming mode
      return CloseableIterator.of(IteratorUtils.of(in.get()));
    }

    return toResultIterator((YTDBGraphInternal) graph, resultSet);
  }

  private static CloseableIterator<Object> toResultIterator(
      YTDBGraphInternal graph,
      ResultSet resultSet
  ) {
    // Close eagerly on last element so the ResultSet doesn't survive past transaction teardown.
    var iterator = new EagerCloseResultSetIterator(graph, resultSet);
    return new CloseableIteratorWithCallback<>(iterator, iterator::closeOnce);
  }

  private static final class EagerCloseResultSetIterator implements java.util.Iterator<Object> {

    private final YTDBGraphInternal graph;
    private final ResultSet resultSet;
    private boolean closed;

    private EagerCloseResultSetIterator(YTDBGraphInternal graph, ResultSet resultSet) {
      this.graph = graph;
      this.resultSet = resultSet;
    }

    @Override
    public boolean hasNext() {
      if (closed) {
        return false;
      }

      var hasNext = resultSet.hasNext();
      if (!hasNext) {
        closeOnce();
      }
      return hasNext;
    }

    @Override
    public Object next() {
      if (closed) {
        throw new java.util.NoSuchElementException();
      }

      var value = toGremlinValue(graph, (Object) resultSet.next());
      if (!resultSet.hasNext()) {
        closeOnce();
      }
      return value;
    }

    private void closeOnce() {
      if (!closed) {
        closed = true;
        resultSet.close();
      }
    }
  }

  @Nullable
  private static Object toGremlinValue(YTDBGraphInternal graph, Object value) {
    return switch (value) {
      case null -> null;
      case Vertex vertex -> new YTDBVertexImpl(graph, vertex);
      case StatefulEdge edge -> new YTDBStatefulEdgeImpl(graph, edge);
      case Edge edge -> new YTDBStatefulEdgeImpl(graph, edge.asStatefulEdge());
      case Entity entity -> {
        if (entity.isVertex()) {
          yield new YTDBVertexImpl(graph, entity.asVertex());
        }
        if (entity.isStatefulEdge()) {
          yield new YTDBStatefulEdgeImpl(graph, entity.asStatefulEdge());
        }
        yield entity;
      }
      case Identifiable identifiable -> wrapIdentifiable(graph, identifiable);
      case Result result -> toGremlinValue(graph, result);
      case Map<?, ?> map -> {
        var mapped = new LinkedHashMap<>();
        for (var entry : map.entrySet()) {
          mapped.put(entry.getKey(), toGremlinValue(graph, entry.getValue()));
        }
        yield mapped;
      }
      case Iterable<?> iterable -> {
        var list = new ArrayList<>();
        for (var element : iterable) {
          list.add(toGremlinValue(graph, element));
        }
        yield list;
      }
      case Object[] array -> Arrays.stream(array).map(v -> toGremlinValue(graph, v)).toList();
      default -> value;
    };
  }

  private static Object toGremlinValue(YTDBGraphInternal graph, Result result) {
    if (result.isEntity()) {
      var entity = result.asEntity();
      if (entity.isVertex()) {
        return new YTDBVertexImpl(graph, entity.asVertex());
      }
      if (entity.isStatefulEdge()) {
        return new YTDBStatefulEdgeImpl(graph, entity.asStatefulEdge());
      }
    }

    if (result.isIdentifiable()) {
      var identity = result.getIdentity();
      if (identity != null) {
        return wrapRid(graph, identity);
      }
    }

    var mapped = new LinkedHashMap<String, Object>();
    for (var name : result.getPropertyNames()) {
      mapped.put(name, toGremlinValue(graph, (Object) result.getProperty(name)));
    }
    return mapped;
  }

  private static Object wrapIdentifiable(YTDBGraphInternal graph, Identifiable identifiable) {
    var identity = identifiable.getIdentity();
    if (identity == null) {
      return identifiable;
    }

    return wrapRid(graph, identity);
  }

  private static Object wrapRid(YTDBGraphInternal graph, RID rid) {
    graph.tx().readWrite();
    var session = graph.tx().getDatabaseSession();
    var immutableSchema = session.getMetadata().getImmutableSchemaSnapshot();
    var cls = immutableSchema.getClassByCollectionId(rid.getCollectionId());
    if (cls == null) {
      return rid;
    }
    if (cls.isVertexType()) {
      return new YTDBVertexImpl(graph, rid);
    }
    if (cls.isEdgeType()) {
      return new YTDBStatefulEdgeImpl(graph, rid);
    }
    return rid;
  }
}
