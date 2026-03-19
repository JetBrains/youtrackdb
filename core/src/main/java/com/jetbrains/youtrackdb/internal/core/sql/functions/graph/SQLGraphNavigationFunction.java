package com.jetbrains.youtrackdb.internal.core.sql.functions.graph;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Direction;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaPropertyInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.record.impl.VertexEntityImpl;
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
  /// result. For example, `outE` returns edge LinkBag property names for vertex entities,
  /// and `out` returns the same properties since the RidPair's secondaryRid provides direct
  /// access to the opposite vertex without loading the edge record.
  ///
  /// SQL engine treats each of those properties in the list as a collection of the `rid`s of some
  /// of the supported types.
  ///
  /// Returned property names are used if a search result can be represented as the series of contains
  /// operations on values of properties, results of which are merged by `or` operation.
  ///
  /// ```
  /// collection1.contains(valueToSearch) || collection2.contains(valueToSearch) || ...
  /// || collectionN.contains(valueToSearch)
  ///```

  @Nullable Collection<String> propertyNamesForIndexCandidates(String[] labels,
      SchemaClass schemaClass,
      boolean polymorphic, DatabaseSessionEmbedded session);

  /// Returns edge LinkBag property names for vertex-to-edge navigation index optimization.
  ///
  /// For vertex entities, returns the property names of LinkBags that contain edges matching
  /// the given labels and direction. For non-vertex entities, returns `null` (no index candidates).
  @Nullable static Collection<String> propertiesForV2ENavigation(SchemaClass schemaClass,
      DatabaseSessionEmbedded session, Direction direction,
      String[] labels) {
    if (schemaClass.isVertexType()) {
      var immutableSchema = session.getMetadata().getImmutableSchemaSnapshot();

      return VertexEntityImpl.getAllPossibleEdgePropertyNames(
          immutableSchema,
          direction, labels);
    } else {
      return null;
    }
  }

  /// Returns property names for vertex-to-vertex navigation index optimization.
  ///
  /// For non-vertex entities, returns LINK-type property names (OUT direction) or their
  /// back-reference system properties (IN direction). For vertex entities, returns the same
  /// edge LinkBag property names as V2E — the RidPair's secondaryRid stores the opposite
  /// vertex RID, enabling V2V optimization without loading edge records.
  @Nullable static Collection<String> propertiesForV2VNavigation(SchemaClass schemaClass,
      DatabaseSessionEmbedded session, Direction direction,
      String[] labels) {
    if (!schemaClass.isVertexType()) {
      if (direction == Direction.OUT) {
        var result = new ArrayList<String>(labels.length);

        for (var label : labels) {
          var property = (SchemaPropertyInternal) schemaClass.getProperty(label);
          if (property != null && property.getTypeInternal().isLink()) {
            result.add(label);
          }
        }

        return result;
      } else if (direction == Direction.IN) {
        var result = new ArrayList<String>(labels.length);

        for (var label : labels) {
          var property = (SchemaPropertyInternal) schemaClass.getProperty(label);

          if (property != null && property.getTypeInternal().isLink()) {
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

    // For vertex entities, the LinkBag RidPair stores both the edge RID (primaryRid)
    // and the opposite vertex RID (secondaryRid). V2V navigation is optimized via
    // the secondaryRid, so the same edge property names apply.
    var immutableSchema = session.getMetadata().getImmutableSchemaSnapshot();
    return VertexEntityImpl.getAllPossibleEdgePropertyNames(
        immutableSchema,
        direction, labels);
  }
}
