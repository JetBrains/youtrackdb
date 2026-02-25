package com.jetbrains.youtrackdb.internal.core.gremlin.sqlcommand;

import com.jetbrains.youtrackdb.internal.core.db.record.record.Edge;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Entity;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.db.record.record.StatefulEdge;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Vertex;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphInternal;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBStatefulEdgeImpl;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBVertexImpl;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.ImmutableSchema;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.annotation.Nullable;

/// Static utility that converts internal YouTrackDB result types into Gremlin-typed values.
/// Handles recursive mapping of vertices, edges, RIDs, Result objects, maps, and collections.
public final class GremlinResultMapper {

  private GremlinResultMapper() {}

  /// Main entry point — recursively converts any value into its Gremlin representation.
  /// Vertices become [YTDBVertexImpl], edges become [YTDBStatefulEdgeImpl],
  /// Results are unwrapped, and collections/maps are recursively mapped.
  @Nullable
  public static Object toGremlinValue(
      YTDBGraphInternal graph, ImmutableSchema schema, Object value
  ) {
    return switch (value) {
      case null -> null;
      case Vertex vertex -> new YTDBVertexImpl(graph, vertex);
      case StatefulEdge edge -> new YTDBStatefulEdgeImpl(graph, edge);
      case Edge edge -> new YTDBStatefulEdgeImpl(graph, edge.asStatefulEdge());
      case Entity entity -> mapEntity(graph, entity);
      case Identifiable identifiable -> wrapRid(graph, schema, identifiable.getIdentity());
      case Result result -> mapResult(graph, schema, result);
      case Map<?, ?> map -> {
        var mapped = new LinkedHashMap<>();
        for (var entry : map.entrySet()) {
          mapped.put(entry.getKey(), toGremlinValue(graph, schema, entry.getValue()));
        }
        yield mapped;
      }
      case Iterable<?> iterable -> {
        var list = new ArrayList<>();
        for (var element : iterable) {
          list.add(toGremlinValue(graph, schema, element));
        }
        yield list;
      }
      case Object[] array ->
          Arrays.stream(array).map(v -> toGremlinValue(graph, schema, v)).toList();
      default -> value;
    };
  }

  private static Object mapEntity(YTDBGraphInternal graph, Entity entity) {
    if (entity.isVertex()) {
      return new YTDBVertexImpl(graph, entity.asVertex());
    } else if (entity.isStatefulEdge()) {
      return new YTDBStatefulEdgeImpl(graph, entity.asStatefulEdge());
    }

    throw new IllegalStateException(
        "Only vertices and stateful edges are supported in Gremlin results");
  }

  /// Converts a [Result] — dispatches to entity/identifiable/property-map handling.
  private static Object mapResult(
      YTDBGraphInternal graph, ImmutableSchema schema, Result result
  ) {
    if (result.isEntity()) {
      return mapEntity(graph, result.asEntity());
    }

    if (result.isIdentifiable()) {
      return wrapRid(graph, schema, result.getIdentity());
    }

    var mapped = new LinkedHashMap<String, Object>();
    for (var name : result.getPropertyNames()) {
      mapped.put(name, toGremlinValue(graph, schema, (Object) result.getProperty(name)));
    }
    return mapped;
  }

  /// Resolves a [RID] to [YTDBVertexImpl] or [YTDBStatefulEdgeImpl] via the cached schema.
  private static Object wrapRid(YTDBGraphInternal graph, ImmutableSchema schema, RID rid) {
    var cls = schema.getClassByCollectionId(rid.getCollectionId());

    if (cls == null) {
      throw new IllegalStateException(
          "Unsupported schema class for collection " + rid.getCollectionId());
    }

    if (cls.isVertexType()) {
      return new YTDBVertexImpl(graph, rid);
    }

    if (cls.isEdgeType()) {
      return new YTDBStatefulEdgeImpl(graph, rid);
    }

    throw new IllegalStateException("Unsupported schema class " + cls.getName());
  }
}
