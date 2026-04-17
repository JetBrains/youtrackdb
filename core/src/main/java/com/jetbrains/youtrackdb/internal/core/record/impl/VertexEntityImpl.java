package com.jetbrains.youtrackdb.internal.core.record.impl;

import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.common.util.Pair;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.EntityLinkListImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.EntityLinkSetImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Direction;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Edge;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Vertex;
import com.jetbrains.youtrackdb.internal.core.db.record.ridbag.LinkBag;
import com.jetbrains.youtrackdb.internal.core.exception.DatabaseException;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.apache.commons.collections4.IterableUtils;

public class VertexEntityImpl extends EntityImpl implements Vertex {

  public static final byte RECORD_TYPE = 'v';

  public VertexEntityImpl(DatabaseSessionEmbedded database, RID rid) {
    super(database, (RecordIdInternal) rid);
  }

  public VertexEntityImpl(RecordIdInternal recordId, DatabaseSessionEmbedded session,
      String klass) {
    super(recordId, session, klass);
    if (!getImmutableSchemaClass(session).isVertexType()) {
      throw new IllegalArgumentException(getSchemaClassName() + " is not a vertex class");
    }
  }

  @Override
  protected void validatePropertyName(String propertyName, boolean allowMetadata) {
    super.validatePropertyName(propertyName, allowMetadata);
    checkPropertyName(propertyName);
  }

  public static List<String> filterPropertyNames(List<String> propertyNames) {
    var propertiesToRemove = new ArrayList<String>();

    for (var propertyName : propertyNames) {
      if (isEdgeProperty(propertyName)) {
        propertiesToRemove.add(propertyName);
      }
    }

    if (propertiesToRemove.isEmpty()) {
      return propertyNames;
    }

    for (var propertyToRemove : propertiesToRemove) {
      propertyNames.remove(propertyToRemove);
    }

    return propertyNames;
  }

  public static boolean isEdgeProperty(String propertyName) {
    return isInEdgeProperty(propertyName)
        || isOutEdgeProperty(propertyName);
  }

  public static boolean isOutEdgeProperty(String propertyName) {
    return propertyName.startsWith(DIRECTION_OUT_PREFIX);
  }

  public static boolean isInEdgeProperty(String propertyName) {
    return propertyName.startsWith(DIRECTION_IN_PREFIX);
  }

  public static void checkPropertyName(String name) {
    if (isOutEdgeProperty(name) || isInEdgeProperty(name)) {
      throw new IllegalArgumentException(
          "Property name " + name + " is booked as a name that can be used to manage edges.");
    }
  }

  public static boolean isConnectionToEdge(Direction direction, String propertyName) {
    return switch (direction) {
      case OUT -> isOutEdgeProperty(propertyName);
      case IN -> isInEdgeProperty(propertyName);
      case BOTH -> isOutEdgeProperty(propertyName)
          || isInEdgeProperty(propertyName);
    };
  }

  @Override
  public @Nonnull List<String> getPropertyNames() {
    return filterPropertyNames(super.getPropertyNames());
  }

  @Override
  public Iterable<Vertex> getVertices(Direction direction) {
    return getVertices(direction, (String[]) null);
  }

  @Override
  public Set<String> getEdgeNames() {
    return getEdgeNames(Direction.BOTH);
  }

  @Override
  public Set<String> getEdgeNames(Direction direction) {
    checkForBinding();
    var propertyNames = getPropertyNamesInternal(false, true);
    var edgeNames = new HashSet<String>();

    for (var propertyName : propertyNames) {
      if (VertexEntityImpl.isConnectionToEdge(direction, propertyName)) {
        edgeNames.add(propertyName);
      }
    }

    return edgeNames;
  }

  @Override
  public Iterable<Vertex> getVertices(Direction direction, String... type) {
    checkForBinding();
    if (direction == Direction.BOTH) {
      var outVertices = getVerticesOptimized(Direction.OUT, type);
      var inVertices = getVerticesOptimized(Direction.IN, type);
      // When both directions yield a single PreFilterableLinkBagIterable (i.e. all edge
      // properties for those labels are backed by a single LinkBag), wrap them in a
      // PreFilterableChainedIterable so the MATCH engine can apply index pre-filters without
      // touching disk. Otherwise fall back to the plain Commons chained iterable.
      if (outVertices instanceof PreFilterableLinkBagIterable pfliOut
          && inVertices instanceof PreFilterableLinkBagIterable pfliIn) {
        //noinspection unchecked
        return (Iterable<Vertex>) (Object) new PreFilterableChainedIterable(pfliOut, pfliIn);
      }
      return IterableUtils.chainedIterable(outVertices, inVertices);
    } else {
      return getVerticesOptimized(direction, type);
    }
  }

  /**
   * Optimized vertex traversal that loads vertices directly from LinkBag secondary RIDs
   * when possible, avoiding the intermediate edge record load. Falls back to the
   * edge-based path for non-LinkBag property types (Identifiable, EntityLinkSetImpl,
   * EntityLinkListImpl).
   */
  private Iterable<Vertex> getVerticesOptimized(Direction direction, String[] labels) {
    var resolved = resolveEdgeProperties(direction, labels);
    var schema = resolved.schema();
    labels = resolved.labels();
    var propertyNames = resolved.propertyNames();

    var iterables = new ArrayList<Iterable<Vertex>>(propertyNames.size());
    for (var fieldName : propertyNames) {
      final var connection = getConnection(schema, direction, fieldName, labels);
      if (connection == null) {
        continue;
      }

      var fieldValue = getPropertyInternal(fieldName);
      if (fieldValue != null) {
        switch (fieldValue) {
          case LinkBag bag ->
              iterables.add(new VertexFromLinkBagIterable(bag, session));
          case Identifiable identifiable -> {
            var coll = Collections.singleton(identifiable);
            var edges = new EdgeIterable(session, coll, 1, coll);
            iterables.add(new BidirectionalLinksIterable(edges, connection.getKey()));
          }
          case EntityLinkSetImpl set -> {
            var edges = new EdgeIterable(session, set, -1, set);
            iterables.add(new BidirectionalLinksIterable(edges, connection.getKey()));
          }
          case EntityLinkListImpl list -> {
            var edges = new EdgeIterable(session, list, -1, list);
            iterables.add(new BidirectionalLinksIterable(edges, connection.getKey()));
          }
          default -> throw new IllegalArgumentException(
              "Unsupported property type: " + getPropertyType(fieldName));
        }
      }
    }

    if (iterables.size() == 1) {
      return iterables.getFirst();
    } else if (iterables.isEmpty()) {
      return Collections.emptyList();
    }

    //noinspection unchecked
    return IterableUtils.chainedIterable(iterables.toArray(new Iterable[0]));
  }

  @Override
  public Iterable<Vertex> getVertices(Direction direction, SchemaClass... type) {
    checkForBinding();
    List<String> types = new ArrayList<>();
    if (type != null) {
      for (var t : type) {
        types.add(t.getName());
      }
    }

    return getVertices(direction, types.toArray(new String[] {}));
  }

  @Override
  public Edge addEdge(Vertex to) {
    return addEdge(to, EdgeInternal.CLASS_NAME);
  }

  @Override
  public Edge addEdge(Vertex to, String type) {
    checkForBinding();
    return session.newEdge(this, to, type == null ? EdgeInternal.CLASS_NAME : type);
  }

  @Override
  public Edge addEdge(Vertex to, SchemaClass type) {
    final String className;
    if (type != null) {
      className = type.getName();
    } else {
      className = EdgeInternal.CLASS_NAME;
    }

    return addEdge(to, className);
  }

  @Override
  public Iterable<Edge> getEdges(Direction direction, SchemaClass... type) {
    List<String> types = new ArrayList<>();

    if (type != null) {
      for (var t : type) {
        types.add(t.getName());
      }
    }
    return getEdges(direction, types.toArray(new String[] {}));
  }

  @Override
  public Iterable<Edge> getEdges(Direction direction) {
    checkForBinding();

    var prefixes =
        switch (direction) {
          case IN -> new String[] {DIRECTION_IN_PREFIX};
          case OUT -> new String[] {DIRECTION_OUT_PREFIX};
          case BOTH -> new String[] {DIRECTION_IN_PREFIX, DIRECTION_OUT_PREFIX};
        };

    Set<String> candidateClasses = new HashSet<>();

    for (var prefix : prefixes) {
      for (var fieldName : calculatePropertyNames(false, true)) {
        if (fieldName.startsWith(prefix)) {
          if (fieldName.equals(prefix)) {
            candidateClasses.add(EdgeInternal.CLASS_NAME);
          } else {
            candidateClasses.add(fieldName.substring(prefix.length()));
          }
        }
      }
    }

    return getEdges(direction, candidateClasses.toArray(new String[] {}));
  }

  @Override
  public Iterable<Edge> getEdges(Direction direction, String... labels) {
    checkForBinding();
    //noinspection unchecked,rawtypes
    return (Iterable) getEdgesInternal(direction, labels);
  }

  @Override
  public byte getRecordType() {
    return RECORD_TYPE;
  }

  /**
   * Holds the resolved schema, labels, and property names needed to iterate edges
   * or vertices from a vertex's edge properties.
   */
  private record ResolvedEdgeProperties(
      Schema schema, String[] labels, Collection<String> propertyNames) {
  }

  /**
   * Resolves the schema, alias labels, and property names for edge traversal.
   * This shared logic is used by both {@link #getEdgesInternal} and
   * {@link #getVerticesOptimized}.
   */
  private ResolvedEdgeProperties resolveEdgeProperties(
      Direction direction, String[] labels) {
    var schema = session.getMetadata().getImmutableSchemaSnapshot();
    labels = resolveAliases(schema, labels);

    Collection<String> propertyNames = null;
    if (labels != null && labels.length > 0) {
      var toLoadPropertyNames = getAllPossibleEdgePropertyNames(
          schema, direction, labels);

      if (toLoadPropertyNames != null) {
        deserializeProperties(toLoadPropertyNames.toArray(new String[] {}));
        propertyNames = toLoadPropertyNames;
      }
    }

    if (propertyNames == null) {
      propertyNames = calculatePropertyNames(false, true);
    }

    return new ResolvedEdgeProperties(schema, labels, propertyNames);
  }

  private Iterable<EdgeInternal> getEdgesInternal(Direction direction,
      String[] labels) {
    var resolved = resolveEdgeProperties(direction, labels);
    var schema = resolved.schema();
    labels = resolved.labels();
    var propertyNames = resolved.propertyNames();

    var iterables = new ArrayList<Iterable<EdgeInternal>>(propertyNames.size());
    for (var fieldName : propertyNames) {
      final var connection =
          getConnection(schema, direction, fieldName, labels);
      if (connection == null)
      // SKIP THIS FIELD
      {
        continue;
      }

      Object fieldValue;

      fieldValue = getPropertyInternal(fieldName);

      if (fieldValue != null) {
        switch (fieldValue) {
          case Identifiable identifiable -> {
            var coll = Collections.singleton(identifiable);
            iterables.add(
                new EdgeIterable(session, coll, 1, coll));
          }
          case EntityLinkSetImpl set -> iterables.add(
              new EdgeIterable(session, set, -1, set));
          case EntityLinkListImpl list -> iterables.add(
              new EdgeIterable(session, list, -1, list));
          case LinkBag bag ->
              iterables.add(new EdgeFromLinkBagIterable(bag, session));
          default -> {
            throw new IllegalArgumentException(
                "Unsupported property type: " + getPropertyType(fieldName));
          }
        }
      }
    }

    if (iterables.size() == 1) {
      return iterables.getFirst();
    } else if (iterables.isEmpty()) {
      return Collections.emptyList();
    }

    // When every iterable in the list is backed by a LinkBag (i.e. each implements
    // PreFilterableLinkBagIterable), chain them in a PreFilterableChainedIterable so the MATCH
    // engine can apply index pre-filters across all edge directions without touching disk.
    // Otherwise fall back to the plain Commons chained iterable.
    var allPreFilterable =
        iterables.stream().allMatch(it -> it instanceof PreFilterableLinkBagIterable);
    if (allPreFilterable) {
      var subs = iterables.stream()
          .map(it -> (PreFilterableLinkBagIterable) it)
          .toArray(PreFilterableLinkBagIterable[]::new);
      //noinspection unchecked
      return (Iterable<EdgeInternal>) (Object) new PreFilterableChainedIterable(subs);
    }

    //noinspection unchecked
    return IterableUtils.chainedIterable(iterables.toArray(new Iterable[0]));
  }

  /// Returns names of properties that may be used for navigation to edges.
  ///
  /// Each label should be represented by a class in the database schema, and this class should
  /// extend the [Edge#CLASS_NAME] class. If this condition is not satisfied, the label is filtered
  /// out.
  ///
  /// As each label is represented by a class and the class may have subclasses, those subclasses
  /// are also considered and added to the list of possible edge labels.
  @Nullable public static List<String> getAllPossibleEdgePropertyNames(
      Schema schema, final Direction direction,
      String... labels) {
    if (labels == null) {
      return null;
    }

    Set<String> allClassNames = new HashSet<>(labels.length);
    for (var className : labels) {
      allClassNames.add(className);
      var clazz = schema.getClass(className);

      if (clazz != null) {
        if (!clazz.isEdgeType()) {
          continue;
        }

        allClassNames.add(clazz.getName());

        var subClasses = clazz.getAllSubclasses();
        for (var subClass : subClasses) {
          allClassNames.add(subClass.getName());
        }
      }
    }

    var result = new ArrayList<String>(2 * allClassNames.size());
    for (var className : allClassNames) {
      if (className.equals(EdgeInternal.CLASS_NAME)) {
        var prefix = switch (direction) {
          case OUT -> List.of(DIRECTION_OUT_PREFIX);
          case IN -> List.of(DIRECTION_IN_PREFIX);
          case BOTH -> List.of(DIRECTION_OUT_PREFIX, DIRECTION_IN_PREFIX);
        };
        result.addAll(prefix);
        continue;
      }

      switch (direction) {
        case OUT -> result.add(DIRECTION_OUT_PREFIX + className);
        case IN -> result.add(DIRECTION_IN_PREFIX + className);
        case BOTH -> {
          result.add(DIRECTION_OUT_PREFIX + className);
          result.add(DIRECTION_IN_PREFIX + className);
        }
      }
    }

    return result;
  }

  @Nullable public static Pair<Direction, String> getConnection(
      final Schema schema,
      final Direction direction,
      final String fieldName,
      String... classNames) {
    if (classNames != null
        && classNames.length == 1
        && classNames[0].equals(EdgeInternal.CLASS_NAME))
    // DEFAULT CLASS, TREAT IT AS NO CLASS/LABEL
    {
      classNames = null;
    }

    if (direction == Direction.OUT || direction == Direction.BOTH) {
      // FIELDS THAT STARTS WITH "out_"
      if (VertexEntityImpl.isOutEdgeProperty(fieldName)) {
        if (classNames == null || classNames.length == 0) {
          return new Pair<>(Direction.OUT, getConnectionClass(Direction.OUT, fieldName));
        }

        // CHECK AGAINST ALL THE CLASS NAMES
        for (var clsName : classNames) {
          if (fieldName.equals(DIRECTION_OUT_PREFIX + clsName)) {
            return new Pair<>(Direction.OUT, clsName);
          }

          // GO DOWN THROUGH THE INHERITANCE TREE
          var type = schema.getClass(clsName);
          if (type != null) {
            for (var subType : type.getAllSubclasses()) {
              clsName = subType.getName();

              if (fieldName.equals(DIRECTION_OUT_PREFIX + clsName)) {
                return new Pair<>(Direction.OUT, clsName);
              }
            }
          }
        }
      }
    }

    if (direction == Direction.IN || direction == Direction.BOTH) {
      // FIELDS THAT STARTS WITH "in_"
      if (VertexEntityImpl.isInEdgeProperty(fieldName)) {
        if (classNames == null || classNames.length == 0) {
          return new Pair<>(Direction.IN, getConnectionClass(Direction.IN, fieldName));
        }

        // CHECK AGAINST ALL THE CLASS NAMES
        for (var clsName : classNames) {

          if (fieldName.equals(DIRECTION_IN_PREFIX + clsName)) {
            return new Pair<>(Direction.IN, clsName);
          }

          // GO DOWN THROUGH THE INHERITANCE TREE
          var type = schema.getClass(clsName);
          if (type != null) {
            for (var subType : type.getAllSubclasses()) {
              clsName = subType.getName();
              if (fieldName.equals(DIRECTION_IN_PREFIX + clsName)) {
                return new Pair<>(Direction.IN, clsName);
              }
            }
          }
        }
      }
    }

    // NOT FOUND
    return null;
  }

  public static void deleteLinks(Vertex vertex) {
    var allEdges = vertex.getEdges(Direction.BOTH);

    //remove self-references when in and out is the same relation.
    var items = Collections.newSetFromMap(new IdentityHashMap<Edge, Boolean>());

    for (var edge : allEdges) {
      items.add(edge);
    }

    for (var edge : items) {
      edge.delete();
    }
  }

  private static String getConnectionClass(final Direction iDirection, final String iFieldName) {
    if (iDirection == Direction.OUT) {
      if (iFieldName.length() > DIRECTION_OUT_PREFIX.length()) {
        return iFieldName.substring(DIRECTION_OUT_PREFIX.length());
      }
    } else if (iDirection == Direction.IN) {
      if (iFieldName.length() > DIRECTION_IN_PREFIX.length()) {
        return iFieldName.substring(DIRECTION_IN_PREFIX.length());
      }
    }
    return EdgeInternal.CLASS_NAME;
  }

  public static String getEdgeLinkFieldName(
      final Direction direction,
      final String className,
      final boolean useVertexFieldsForEdgeLabels) {
    if (direction == null || direction == Direction.BOTH) {
      throw new IllegalArgumentException("Direction not valid");
    }

    if (useVertexFieldsForEdgeLabels) {
      // PREFIX "out_" or "in_" TO THE FIELD NAME
      final var prefix =
          direction == Direction.OUT ? DIRECTION_OUT_PREFIX : DIRECTION_IN_PREFIX;
      if (className == null || className.isEmpty() || className.equals(EdgeInternal.CLASS_NAME)) {
        return prefix;
      }

      return prefix + className;
    } else
    // "out" or "in"
    {
      return direction == Direction.OUT ? EdgeInternal.DIRECTION_OUT
          : EdgeInternal.DIRECTION_IN;
    }
  }

  @Nullable private static String[] resolveAliases(Schema schema, String[] labels) {
    if (labels == null) {
      return null;
    }
    var result = new String[labels.length];

    for (var i = 0; i < labels.length; i++) {
      result[i] = resolveAlias(labels[i], schema);
    }

    return result;
  }

  private static String resolveAlias(String label, Schema schema) {
    var clazz = schema.getClass(label);
    if (clazz != null) {
      return clazz.getName();
    }

    return label;
  }

  private static void removeVertexLink(
      EntityImpl vertex,
      String fieldName,
      Object link,
      String label,
      Identifiable identifiable) {
    switch (link) {
      case Collection<?> collection -> collection.remove(identifiable);
      case LinkBag bag -> bag.remove(identifiable.getIdentity());
      case Identifiable ignored when link.equals(vertex) ->
          vertex.removePropertyInternal(fieldName);
      case null, default -> throw new IllegalArgumentException(
          label + " is not a valid link in vertex with rid " + vertex.getIdentity());
    }
  }

  /**
   * Creates a link between a vertex and a graph element, storing both primary and secondary
   * RIDs in the LinkBag. The primary RID is the edge record and the secondary RID is the
   * opposite vertex (i.e. RidPair(edgeRid, oppositeVertexRid)).
   *
   * @param session the database session
   * @param fromVertex the vertex to add the link to
   * @param primaryIdentifiable the primary identifiable to store (edge record)
   * @param secondaryIdentifiable the secondary identifiable (opposite vertex)
   * @param fieldName the edge field name (e.g. "out_E" or "in_E")
   */
  public static void createLink(
      DatabaseSessionEmbedded session, final EntityImpl fromVertex,
      final Identifiable primaryIdentifiable,
      final Identifiable secondaryIdentifiable,
      final String fieldName) {
    final Object out;
    var outType = fromVertex.getPropertyTypeInternal(fieldName);
    var found = fromVertex.getPropertyInternal(fieldName);

    var result = fromVertex.getImmutableSchemaClass(session);
    if (result == null) {
      throw new IllegalArgumentException("Class not found in source vertex: " + fromVertex);
    }

    final var prop = result.getProperty(fieldName);
    final var propType =
        prop != null ? prop.getType() : null;

    switch (found) {
      case null -> {
        if (propType == PropertyType.LINKLIST
            || (prop != null
                && "true".equalsIgnoreCase(prop.getCustom("ordered")))) { // TODO constant
          var coll = new EntityLinkListImpl(fromVertex);
          coll.add(primaryIdentifiable);
          out = coll;
          outType = PropertyTypeInternal.LINKLIST;
        } else if (propType == null || propType == PropertyType.LINKBAG) {
          final var bag = new LinkBag(fromVertex.getSession());
          bag.add(primaryIdentifiable.getIdentity(), secondaryIdentifiable.getIdentity());
          out = bag;
          outType = PropertyTypeInternal.LINKBAG;
        } else if (propType == PropertyType.LINK) {
          out = primaryIdentifiable;
          outType = PropertyTypeInternal.LINK;
        } else {
          throw new DatabaseException(session.getDatabaseName(),
              "Type of field provided in schema '"
                  + prop.getType()
                  + "' cannot be used for link creation.");
        }
      }
      case Identifiable foundId -> {
        // This case handles conversion from a single Identifiable to a collection.
        // In normal edge creation flow this branch is not reached because the first edge
        // creates a LinkBag directly (when propType is null or LINKBAG). It can only be
        // reached if the property was manually set to a single Identifiable externally.
        if (prop != null && propType == PropertyType.LINK) {
          throw new DatabaseException(session.getDatabaseName(),
              "Type of field provided in schema '"
                  + prop.getType()
                  + "' cannot be used for creation to hold several links.");
        }

        if (prop != null && "true".equalsIgnoreCase(
            prop.getCustom("ordered"))) { // TODO constant
          var coll = new EntityLinkListImpl(fromVertex);
          coll.add(foundId);
          coll.add(primaryIdentifiable);
          out = coll;
          outType = PropertyTypeInternal.LINKLIST;
        } else {
          final var bag = new LinkBag(fromVertex.getSession());
          // Load the pre-existing edge record to determine the opposite vertex RID.
          // The fieldName prefix determines the direction: out_ means the secondaryRid
          // is the "in" vertex, in_ means it's the "out" vertex.
          EntityImpl existingEdge =
              session.getActiveTransaction().load(foundId);
          var existingSecondaryRid = resolveSecondaryRid(existingEdge, fieldName);
          bag.add(foundId.getIdentity(), existingSecondaryRid);
          bag.add(primaryIdentifiable.getIdentity(), secondaryIdentifiable.getIdentity());
          out = bag;
          outType = PropertyTypeInternal.LINKBAG;
        }
      }
      case LinkBag bag -> {
        // ADD THE LINK TO THE COLLECTION
        out = null;
        var transaction = session.getActiveTransaction();
        bag.add(
            transaction.load(primaryIdentifiable).getIdentity(),
            secondaryIdentifiable.getIdentity());
      }
      case Collection<?> ignored -> {
        // USE THE FOUND COLLECTION
        out = null;
        //noinspection unchecked
        ((Collection<Identifiable>) found).add(primaryIdentifiable);
      }
      default -> throw new DatabaseException(session.getDatabaseName(),
          "Relationship content is invalid on field " + fieldName + ". Found: " + found);
    }

    if (out != null)
    // OVERWRITE IT
    {
      fromVertex.setPropertyInternal(fieldName, out, outType);
    }
  }

  /// Resolves the secondary RID (opposite vertex) for a pre-existing edge record
  /// stored in a vertex LinkBag field. The fieldName prefix determines direction:
  /// "out_" fields store the target vertex ("in"), "in_" fields store the source ("out").
  private static RID resolveSecondaryRid(EntityImpl edgeRecord, String fieldName) {
    String oppositeDirection;
    if (fieldName.startsWith(DIRECTION_OUT_PREFIX)) {
      oppositeDirection = Edge.DIRECTION_IN;
    } else if (fieldName.startsWith(DIRECTION_IN_PREFIX)) {
      oppositeDirection = Edge.DIRECTION_OUT;
    } else {
      throw new IllegalArgumentException("Unexpected edge field name: " + fieldName);
    }
    Identifiable oppositeVertex = edgeRecord.getPropertyInternal(oppositeDirection);
    if (oppositeVertex == null) {
      throw new IllegalStateException(
          "Edge record " + edgeRecord.getIdentity() + " has no '"
              + oppositeDirection + "' vertex; cannot resolve secondary RID for field "
              + fieldName);
    }
    return oppositeVertex.getIdentity();
  }

  private static void removeLinkFromEdge(EntityImpl vertex, Edge edge,
      Direction direction) {
    var className = edge.getSchemaClassName();
    var edgeId = edge.getIdentity();

    removeLinkFromEdge(
        vertex, Vertex.getEdgeLinkFieldName(direction, className), edgeId);
  }

  private static void removeLinkFromEdge(
      EntityImpl vertex, String edgeField, Identifiable edgeId) {
    var edgeProp = vertex.getPropertyInternal(edgeField);

    removeEdgeLinkFromProperty(vertex, edgeField, edgeId, edgeProp);
  }

  private static void removeEdgeLinkFromProperty(
      EntityImpl vertex, String edgeField, Identifiable edgeId, Object edgeProp) {
    if (edgeProp instanceof Collection) {
      ((Collection<?>) edgeProp).remove(edgeId);
    } else if (edgeProp instanceof LinkBag linkBag) {
      linkBag.remove(edgeId.getIdentity());
    } else if (edgeProp instanceof Identifiable identifiable
        && identifiable.getIdentity().equals(edgeId)) {
      vertex.removePropertyInternal(edgeField);
    } else {
      LogManager.instance()
          .warn(
              vertex,
              "Error detaching edge: the vertex collection field is of type "
                  + (edgeProp == null ? "null" : edgeProp.getClass()));
    }
  }

  static void removeIncomingEdge(DatabaseSessionEmbedded db, Vertex vertex, Edge edge) {
    removeLinkFromEdge((EntityImpl) vertex, edge, Direction.IN);
  }

  static void removeOutgoingEdge(DatabaseSessionEmbedded db, Vertex vertex, Edge edge) {
    removeLinkFromEdge((EntityImpl) vertex, edge, Direction.OUT);
  }

}
