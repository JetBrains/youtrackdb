package com.jetbrains.youtrackdb.internal.core.gql.executor;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Entity;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphInternal;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBStatefulEdgeImpl;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBVertexImpl;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nullable;

/**
 * Converts unified MATCH {@link Result} rows into types that Gremlin understands.
 * Used at the GQL→Gremlin boundary (e.g. in GqlService when returning results to a traversal).
 *
 * <p>Per spec: isVertex → Gremlin vertex, isEdge → Gremlin edge, isProjection → Map.
 * Single binding with vertex/edge is returned as that element; multiple bindings as Map.
 * For other value types we throw {@link UnsupportedOperationException}.
 *
 * <p>Values may be Entity or Identifiable (RID); Identifiable is loaded via session to get Entity.
 */
public final class ResultToGremlinConverter {

  private ResultToGremlinConverter() {}

  /**
   * Converts a Result row to a Gremlin-consumable object.
   *
   * @param result  the row from unified MATCH execution (alias → value)
   * @param graph   the graph for building YTDBVertexImpl/YTDBStatefulEdgeImpl
   * @param session session used to load record by RID when value is Identifiable (e.g. ChangeableRecordId)
   * @return Gremlin vertex, Gremlin edge, or Map (for projection / multiple bindings)
   * @throws UnsupportedOperationException if the result shape or value types are not supported
   */
  public static Object toGremlin(
      Result result, YTDBGraphInternal graph, DatabaseSessionEmbedded session) {
    Objects.requireNonNull(result);
    Objects.requireNonNull(graph);
    Objects.requireNonNull(session);

    var names = result.getPropertyNames();
    if (names.isEmpty()) {
      throw new UnsupportedOperationException(
          "GQL result has no properties; cannot convert to Gremlin type.");
    }

    if (names.size() == 1) {
      var name = names.getFirst();
      var value = result.getProperty(name);
      return convertSingleValue(value, graph, session);
    }

    // Projection: multiple bindings → Map
    Map<String, Object> out = new LinkedHashMap<>();
    for (var name : names) {
      var value = result.getProperty(name);
      out.put(name, convertValue(value, graph, session));
    }
    return out;
  }

  /**
   * Single binding: vertex → Gremlin vertex, edge → Gremlin edge; else unsupported.
   * If value is Identifiable (RID), loads the record first.
   */
  private static Object convertSingleValue(
      @Nullable Object value, YTDBGraphInternal graph, DatabaseSessionEmbedded session) {
    if (value == null) {
      throw new UnsupportedOperationException(
          "GQL single-value result is null; cannot convert to Gremlin type.");
    }
    var entity = resolveEntity(value, session);
    if (entity != null) {
      if (entity.isVertex()) {
        return new YTDBVertexImpl(graph, entity.asVertex());
      }
      if (entity.isStatefulEdge()) {
        return new YTDBStatefulEdgeImpl(graph, entity.asStatefulEdge());
      }
      throw new UnsupportedOperationException(
          "GQL result value is Entity but neither vertex nor edge; type not supported for Gremlin.");
    }
    throw new UnsupportedOperationException(
        "GQL single-value result is not a vertex or edge (got " + value.getClass().getName() + "); "
            + "only vertex, edge, and projection (Map) are supported for Gremlin.");
  }

  /**
   * Value in a projection: convert entity to vertex/edge impl, pass through null, else unsupported.
   * If value is Identifiable (RID), loads the record first.
   */
  private static @org.jspecify.annotations.Nullable Object convertValue(
      @Nullable Object value, YTDBGraphInternal graph, DatabaseSessionEmbedded session) {
    if (value == null) {
      return null;
    }
    var entity = resolveEntity(value, session);
    if (entity != null) {
      if (entity.isVertex()) {
        return new YTDBVertexImpl(graph, entity.asVertex());
      }
      if (entity.isStatefulEdge()) {
        return new YTDBStatefulEdgeImpl(graph, entity.asStatefulEdge());
      }
      throw new UnsupportedOperationException(
          "GQL projection value is Entity but neither vertex nor edge; type not supported.");
    }
    throw new UnsupportedOperationException(
        "GQL projection value is not vertex or edge (got " + value.getClass().getName() + "); "
            + "only vertex and edge bindings are supported in projection for Gremlin.");
  }

  /**
   * Returns the Entity for the given value: if value is Entity, return it; if Identifiable (RID),
   * load via session and return the loaded Entity; otherwise null.
   */
  @Nullable
  private static Entity resolveEntity(@Nullable Object value, DatabaseSessionEmbedded session) {
    if (value instanceof Entity entity) {
      return entity;
    }
    if (value instanceof Identifiable identifiable) {
      var tx = session.getActiveTransaction();
      var loaded = tx.load(identifiable);
      return loaded instanceof Entity e ? e : null;
    }
    return null;
  }
}
