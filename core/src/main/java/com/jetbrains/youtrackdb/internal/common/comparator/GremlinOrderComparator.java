package com.jetbrains.youtrackdb.internal.common.comparator;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Entity;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import org.apache.tinkerpop.gremlin.structure.Element;
import org.apache.tinkerpop.gremlin.util.GremlinValueComparator;
import org.apache.tinkerpop.gremlin.util.GremlinValueComparator.Type;

/**
 * Compares values using TinkerPop orderability semantics.
 *
 * <p>Translated property reads expose raw graph entities and identifiers, while native property
 * reads wrap them as TinkerPop elements. Bridge only these top-level values. TinkerPop remains the
 * source of type priorities, identifier ordering, and recursive collection comparison.
 */
public final class GremlinOrderComparator {

  public static final GremlinOrderComparator INSTANCE = new GremlinOrderComparator();

  private GremlinOrderComparator() {
  }

  public int compare(Object first, Object second, DatabaseSessionEmbedded session) {
    var firstGraphType = graphType(first, session);
    var secondGraphType = graphType(second, session);
    if (firstGraphType == null && secondGraphType == null) {
      return GremlinValueComparator.ORDERABILITY.compare(first, second);
    }

    var firstType = firstGraphType == null ? Type.type(first) : firstGraphType;
    var secondType = secondGraphType == null ? Type.type(second) : secondGraphType;
    if (firstType != secondType) {
      return firstType.priority() - secondType.priority();
    }

    // Keep the original RID classes. TinkerPop orders unlike identifier classes differently
    // from numeric RID comparison, including pending against committed identifiers.
    return GremlinValueComparator.ORDERABILITY.compare(identity(first), identity(second));
  }

  private static Type graphType(Object value, DatabaseSessionEmbedded session) {
    if (value instanceof Entity entity) {
      if (entity.isVertex()) {
        return Type.Vertex;
      }
      if (entity.isEdge()) {
        return Type.Edge;
      }
    } else if (value instanceof RID rid) {
      // Classify an unloaded link without loading its record or allocating a graph wrapper.
      var schemaClass = session.getMetadata().getImmutableSchemaSnapshot()
          .getClassByCollectionId(rid.getCollectionId());
      if (schemaClass != null) {
        if (schemaClass.isVertexType()) {
          return Type.Vertex;
        }
        if (schemaClass.isEdgeType()) {
          return Type.Edge;
        }
      }
    }
    return null;
  }

  private static Object identity(Object value) {
    if (value instanceof Entity entity) {
      return entity.getIdentity();
    }
    if (value instanceof RID) {
      return value;
    }
    return ((Element) value).id();
  }
}
