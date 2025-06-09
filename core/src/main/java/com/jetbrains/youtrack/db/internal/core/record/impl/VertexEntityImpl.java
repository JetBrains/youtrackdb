package com.jetbrains.youtrack.db.internal.core.record.impl;

import com.jetbrains.youtrack.db.api.exception.DatabaseException;
import com.jetbrains.youtrack.db.api.record.Direction;
import com.jetbrains.youtrack.db.api.record.Edge;
import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.record.Relation;
import com.jetbrains.youtrack.db.api.record.StatefulEdge;
import com.jetbrains.youtrack.db.api.record.Vertex;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.common.util.Pair;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.record.EntityLinkListImpl;
import com.jetbrains.youtrack.db.internal.core.db.record.EntityLinkSetImpl;
import com.jetbrains.youtrack.db.internal.core.db.record.ridbag.LinkBag;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.PropertyTypeInternal;
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
    super(database, (RecordId) rid);
  }

  public VertexEntityImpl(DatabaseSessionEmbedded session, String klass) {
    super(session, klass);
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
      return IterableUtils.chainedIterable(
          getVertices(Direction.OUT, type), getVertices(Direction.IN, type));
    } else {
      var edges = getEdgesInternal(direction, type);
      return new BidirectionalLinksIterable<>(edges, direction);
    }
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

    return getVertices(direction, types.toArray(new String[]{}));
  }

  @Override
  public StatefulEdge addStateFulEdge(Vertex to) {
    return (StatefulEdge) addEdge(to, EdgeInternal.CLASS_NAME);
  }

  @Override
  public Edge addEdge(Vertex to, String type) {
    checkForBinding();
    if (type == null) {
      return session.newStatefulEdge(this, to, EdgeInternal.CLASS_NAME);
    }

    var schemaClass = session.getClass(type);
    if (schemaClass == null) {
      throw new IllegalArgumentException("Schema class for label " + type + " not found");
    }
    if (schemaClass.isAbstract()) {
      return session.newLightweightEdge(this, to, type);
    }

    return session.newStatefulEdge(this, to, schemaClass);
  }

  @Override
  public StatefulEdge addStateFulEdge(Vertex to, String label) {
    checkForBinding();
    return session.newStatefulEdge(this, to, label == null ? EdgeInternal.CLASS_NAME : label);
  }

  @Override
  public Edge addLightWeightEdge(Vertex to, String label) {
    checkForBinding();
    return session.newLightweightEdge(this, to, label);
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
  public StatefulEdge addStateFulEdge(Vertex to, SchemaClass label) {
    final String className;

    if (label != null) {
      className = label.getName();
    } else {
      className = EdgeInternal.CLASS_NAME;
    }

    return addStateFulEdge(to, className);
  }

  @Override
  public Edge addLightWeightEdge(Vertex to, SchemaClass label) {
    final String className;

    if (label != null) {
      className = label.getName();
    } else {
      className = EdgeInternal.CLASS_NAME;
    }

    return addLightWeightEdge(to, className);
  }

  @Override
  public Iterable<Edge> getEdges(Direction direction, SchemaClass... type) {
    List<String> types = new ArrayList<>();

    if (type != null) {
      for (var t : type) {
        types.add(t.getName());
      }
    }
    return getEdges(direction, types.toArray(new String[]{}));
  }


  @Override
  public Iterable<Edge> getEdges(Direction direction) {
    checkForBinding();

    var prefixes =
        switch (direction) {
          case IN -> new String[]{DIRECTION_IN_PREFIX};
          case OUT -> new String[]{DIRECTION_OUT_PREFIX};
          case BOTH -> new String[]{DIRECTION_IN_PREFIX, DIRECTION_OUT_PREFIX};
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

    return getEdges(direction, candidateClasses.toArray(new String[]{}));
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

  @Override
  protected Iterable<Relation<Entity>> getBidirectionalLinksInternal(
      Direction direction, String... linkNames) {
    //noinspection unchecked,rawtypes
    return IterableUtils.chainedIterable(
        super.getBidirectionalLinksInternal(direction, linkNames),
        (Iterable) getEdgesInternal(direction, linkNames));
  }

  private Iterable<EdgeInternal> getEdgesInternal(Direction direction,
      String[] labels) {
    var schema = session.getMetadata().getImmutableSchemaSnapshot();
    labels = resolveAliases(session, schema, labels);

    Collection<String> propertyNames = null;
    if (labels != null && labels.length > 0) {
      var toLoadPropertyNames = getAllPossibleEdgePropertyNames(schema, direction, EdgeType.BOTH,
          labels);

      if (toLoadPropertyNames != null) {
        deserializeProperties(toLoadPropertyNames.toArray(new String[]{}));
        propertyNames = toLoadPropertyNames;
      }
    }

    if (propertyNames == null) {
      propertyNames = calculatePropertyNames(false, true);
    }

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
                new EdgeIterable(this, connection, labels, session, coll, 1, coll));
          }
          case EntityLinkSetImpl set -> iterables.add(
              new EdgeIterable(this, connection, labels, session,
                  set, -1, set));
          case EntityLinkListImpl list -> iterables.add(
              new EdgeIterable(this, connection, labels, session,
                  list, -1, list));
          case LinkBag bag -> iterables.add(
              new EdgeIterable(
                  this, connection, labels, session, bag, -1, bag));
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
  @Nullable
  public static List<String> getAllPossibleEdgePropertyNames(
      Schema schema, final Direction direction, EdgeType edgeType,
      String... labels) {
    if (labels == null) {
      return null;
    }

    if (labels.length == 1 && labels[0].equalsIgnoreCase(EdgeInternal.CLASS_NAME)
        && edgeType != EdgeType.LIGHTWEIGHT) {
      return switch (direction) {
        case OUT -> List.of(DIRECTION_OUT_PREFIX);
        case IN -> List.of(DIRECTION_IN_PREFIX);
        case BOTH -> List.of(DIRECTION_OUT_PREFIX, DIRECTION_IN_PREFIX);
      };
    }

    Set<String> allClassNames = new HashSet<>(labels.length);

    for (var className : labels) {
      allClassNames.add(className);
      var clazz = schema.getClass(className);

      if (clazz != null) {
        if (!clazz.isEdgeType()) {
          continue;
        }

        if (edgeType == EdgeType.LIGHTWEIGHT && !clazz.isAbstract()) {
          continue;
        } else if (edgeType == EdgeType.STATEFUL && clazz.isAbstract()) {
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
      switch (direction) {
        case OUT:
          result.add(DIRECTION_OUT_PREFIX + className);
          break;
        case IN:
          result.add(DIRECTION_IN_PREFIX + className);
          break;
        case BOTH:
          result.add(DIRECTION_OUT_PREFIX + className);
          result.add(DIRECTION_IN_PREFIX + className);
          break;
      }
    }

    return result;
  }

  @Nullable
  public static Pair<Direction, String> getConnection(
      final Schema schema,
      final Direction direction,
      final String fieldName,
      String... classNames) {
    if (classNames != null
        && classNames.length == 1
        && classNames[0].equalsIgnoreCase(EdgeInternal.CLASS_NAME))
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

  /**
   * updates old and new vertices connected to an edge after out/in update on the edge itself
   */
  public static void changeVertexEdgePointers(
      DatabaseSessionInternal db, EntityImpl edge,
      Identifiable prevInVertex,
      Identifiable currentInVertex,
      Identifiable prevOutVertex,
      Identifiable currentOutVertex) {
    var edgeClass = edge.getSchemaClassName();

    if (currentInVertex != prevInVertex) {
      changeVertexEdgePointersOneDirection(db,
          edge, prevInVertex, currentInVertex, edgeClass, Direction.IN);
    }
    if (currentOutVertex != prevOutVertex) {
      changeVertexEdgePointersOneDirection(db,
          edge, prevOutVertex, currentOutVertex, edgeClass, Direction.OUT);
    }
  }

  private static void changeVertexEdgePointersOneDirection(
      DatabaseSessionInternal db, EntityImpl edge,
      Identifiable prevInVertex,
      Identifiable currentInVertex,
      String edgeClass,
      Direction direction) {
    if (prevInVertex != null) {
      var inFieldName = Vertex.getEdgeLinkFieldName(direction, edgeClass);
      var transaction1 = db.getActiveTransaction();
      var prevRecord = transaction1.<EntityImpl>load(prevInVertex);

      var prevLink = prevRecord.getPropertyInternal(inFieldName);
      if (prevLink != null) {
        removeVertexLink(prevRecord, inFieldName, prevLink, edgeClass, edge);
      }

      var transaction = db.getActiveTransaction();
      var currentRecord = transaction.<EntityImpl>load(currentInVertex);
      createLink(db, currentRecord, edge, inFieldName);

    }
  }

  @Nullable
  private static String[] resolveAliases(DatabaseSessionInternal db, Schema schema,
      String[] labels) {
    if (labels == null) {
      return null;
    }
    var result = new String[labels.length];

    for (var i = 0; i < labels.length; i++) {
      result[i] = resolveAlias(db, labels[i], schema);
    }

    return result;
  }

  private static String resolveAlias(DatabaseSessionInternal db, String label, Schema schema) {
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
   * Creates a link between a vertices and a Graph Element.
   */
  public static void createLink(
      DatabaseSessionInternal session, final EntityImpl fromVertex, final Identifiable to,
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
          coll.add(to);
          out = coll;
          outType = PropertyTypeInternal.LINKLIST;
        } else if (propType == null || propType == PropertyType.LINKBAG) {
          final var bag = new LinkBag(fromVertex.getSession());
          bag.add(to.getIdentity());
          out = bag;
          outType = PropertyTypeInternal.LINKBAG;
        } else if (propType == PropertyType.LINK) {
          out = to;
          outType = PropertyTypeInternal.LINK;
        } else {
          throw new DatabaseException(session.getDatabaseName(),
              "Type of field provided in schema '"
                  + prop.getType()
                  + "' cannot be used for link creation.");
        }
      }
      case Identifiable foundId -> {
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
          coll.add(to);
          out = coll;
          outType = PropertyTypeInternal.LINKLIST;
        } else {
          final var bag = new LinkBag(fromVertex.getSession());
          bag.add(foundId.getIdentity());
          bag.add(to.getIdentity());
          out = bag;
          outType = PropertyTypeInternal.LINKBAG;
        }
      }
      case LinkBag bag -> {
        // ADD THE LINK TO THE COLLECTION
        out = null;
        var transaction = session.getActiveTransaction();
        bag.add(transaction.load(to).getIdentity());
      }
      case Collection<?> ignored -> {
        // USE THE FOUND COLLECTION
        out = null;
        //noinspection unchecked
        ((Collection<Identifiable>) found).add(to);
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

  private static void removeLinkFromEdge(DatabaseSessionInternal db, EntityImpl vertex, Edge edge,
      Direction direction) {
    var className = edge.getSchemaClassName();
    Identifiable edgeId;
    if (edge instanceof StatefulEdge statefulEdge) {
      edgeId = statefulEdge.getIdentity();
    } else {
      edgeId = null;
    }

    removeLinkFromEdge(
        vertex, edge, Vertex.getEdgeLinkFieldName(direction, className), edgeId, direction);
  }

  private static void removeLinkFromEdge(
      EntityImpl vertex, Edge edge, String edgeField, Identifiable edgeId,
      Direction direction) {
    var edgeProp = vertex.getPropertyInternal(edgeField);
    RID oppositeVertexId = null;
    if (direction == Direction.IN) {
      var fromIdentifiable = edge.getFromLink();
      if (fromIdentifiable != null) {
        oppositeVertexId = fromIdentifiable.getIdentity();
      }
    } else {
      var toIdentifiable = edge.getToLink();
      if (toIdentifiable != null) {
        oppositeVertexId = toIdentifiable.getIdentity();
      }
    }

    if (edgeId == null) {
      // lightweight edge
      edgeId = oppositeVertexId;
    }

    removeEdgeLinkFromProperty(vertex, edge, edgeField, edgeId, edgeProp);
  }

  private static void removeEdgeLinkFromProperty(
      EntityImpl vertex, Edge edge, String edgeField, Identifiable edgeId, Object edgeProp) {
    if (edgeProp instanceof Collection) {
      ((Collection<?>) edgeProp).remove(edgeId);
    } else if (edgeProp instanceof LinkBag) {
      ((LinkBag) edgeProp).remove(edgeId.getIdentity());
    } else if (
        edgeProp instanceof Identifiable && ((Identifiable) edgeProp).getIdentity().equals(edgeId)
            || edge.isLightweight()) {
      vertex.removePropertyInternal(edgeField);
    } else {
      LogManager.instance()
          .warn(
              vertex,
              "Error detaching edge: the vertex collection field is of type "
                  + (edgeProp == null ? "null" : edgeProp.getClass()));
    }
  }

  static void removeIncomingEdge(DatabaseSessionInternal db, Vertex vertex, Edge edge) {
    removeLinkFromEdge(db, (EntityImpl) vertex, edge, Direction.IN);
  }

  static void removeOutgoingEdge(DatabaseSessionInternal db, Vertex vertex, Edge edge) {
    removeLinkFromEdge(db, (EntityImpl) vertex, edge, Direction.OUT);
  }

  public enum EdgeType {
    LIGHTWEIGHT,
    STATEFUL,
    BOTH
  }
}
