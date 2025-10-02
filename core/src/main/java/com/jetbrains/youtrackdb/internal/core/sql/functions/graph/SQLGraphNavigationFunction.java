package com.jetbrains.youtrackdb.internal.core.sql.functions.graph;

import com.jetbrains.youtrackdb.api.record.Direction;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.ImmutableSchemaClass;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.record.impl.VertexEntityImpl;
import com.jetbrains.youtrackdb.internal.core.record.impl.VertexEntityImpl.EdgeType;
import com.jetbrains.youtrackdb.internal.core.sql.functions.SQLFunction;
import java.util.ArrayList;
import java.util.Collection;
import javax.annotation.Nullable;

/// Interface that indicates functions that are used to navigate through graph relations.
///
/// Examples of such functions are `outE``,`bothE``, `inE``,`both` and `bothV`.
public interface SQLGraphNavigationFunction extends SQLFunction {

  /// List of property names that are used to navigate over relation.
  ///
  /// SQL engine uses those properties to determine the index that will be used instead of the given
  /// function to return the same result.
  ///
  /// Those properties are returned only if property values are mapped directly to the function
  /// result. For example, the ` out ` function will return `null` in case of usage of stateful
  /// edges.
  ///
  /// SQL engine treats each of those properties in the list as a collection of the `rid`s of some
  /// of the supported types.
  ///
  /// Returned property names are used if a search result can be represented as the series of
  /// contains operations on values of properties, results of which are merged by `or` operation.
  ///
  /// ```
  /// collection1.contains(valueToSearch) || collection2.contains(valueToSearch) || ...
  /// || collectionN.contains(valueToSearch)
  ///```

  @Nullable
  Collection<String> propertyNamesForIndexCandidates(String[] labels,
      ImmutableSchemaClass schemaClass,
      boolean polymorphic, DatabaseSessionEmbedded session);


  @Nullable
  static Collection<String> propertiesForV2ENavigation(ImmutableSchemaClass schemaClass,
      DatabaseSessionEmbedded session, Direction direction,
      String[] labels) {
    //As we support graph function for all relations both graph and non-graph.
    //
    //We should handle two cases:
    // 1. Processed entity is vertex
    // 2. Non-vertex case.
    //
    // In the last one we do not generate property names for indexed edges
    // as they are always lightweight and cannot be fetched from the database.
    //
    // For the first case we fetch class names of for labels and check if they are represented
    // by edges backed by records in the database.
    // In the last case we return related property names.

    if (schemaClass.isVertexType()) {
      var immutableSchema = session.getMetadata().getFastImmutableSchema();

      return VertexEntityImpl.getAllPossibleEdgePropertyNames(
          immutableSchema,
          direction, EdgeType.STATEFUL, labels);
    } else {
      return null;
    }
  }

  @Nullable
  static Collection<String> propertiesForV2VNavigation(ImmutableSchemaClass schemaClass,
      DatabaseSessionEmbedded session, Direction direction,
      String[] labels) {
    //As we support graph function for all relations both graph and non-graph.
    //We should handle two cases:
    //
    // 1. Non-vertex case.
    // 2. Processed entity is vertex.

    if (!schemaClass.isVertexType()) {
      //If an entity is not a vertex:
      // 1. 'Out' direction - we return all labels that are link-based properties.
      // 2. 'In' direction - we return names of system properties that are containers
      // for back references used to track link consistency.
      if (direction == Direction.OUT) {
        var result = new ArrayList<String>(labels.length);

        for (var label : labels) {
          var property = schemaClass.getProperty(label);
          if (property != null && property.getType().isLink()) {
            result.add(label);
          }
        }

        return result;
      } else if (direction == Direction.IN) {
        var result = new ArrayList<String>(labels.length);

        for (var label : labels) {
          var property = schemaClass.getProperty(label);

          if (property != null && property.getType().isLink()) {
            var systemPropertyName = EntityImpl.getOppositeLinkBagPropertyName(label);
            result.add(systemPropertyName);
          }
        }

        return result;
      } else if (direction == Direction.BOTH) {
        var result = new ArrayList<String>(labels.length << 1);

        result.addAll(
            propertiesForV2VNavigation(schemaClass, session, Direction.OUT,
                labels));
        result.addAll(
            propertiesForV2VNavigation(schemaClass, session, Direction.IN,
                labels));
        return result;
      }

      throw new IllegalStateException("Incorrect value for direction: " + direction);
    }

    //if an entity is vertex, we collect property names that are references of lightweight edges,
    // so we return only properties that directly reference opposite vertices.
    var immutableSchema = session.getMetadata().getFastImmutableSchema();
    return VertexEntityImpl.getAllPossibleEdgePropertyNames(
        immutableSchema,
        direction, EdgeType.LIGHTWEIGHT, labels);
  }
}
