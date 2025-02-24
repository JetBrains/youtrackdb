package com.jetbrains.youtrack.db.internal.core.record.impl;

import com.jetbrains.youtrack.db.api.exception.DatabaseException;
import com.jetbrains.youtrack.db.api.record.Direction;
import com.jetbrains.youtrack.db.api.record.Edge;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.record.StatefulEdge;
import com.jetbrains.youtrack.db.api.record.Vertex;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.common.util.Pair;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.record.LinkList;
import com.jetbrains.youtrack.db.internal.core.db.record.RecordOperation;
import com.jetbrains.youtrack.db.internal.core.db.record.ridbag.RidBag;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.commons.collections4.IterableUtils;

public interface VertexInternal extends Vertex, EntityInternal {

  static Collection<String> filterPropertyNames(Collection<String> propertyNames) {
    var propertiesToRemove = new ArrayList<String>();

    for (var propertyName : propertyNames) {
      if (propertyName.startsWith(DIRECTION_IN_PREFIX)
          || propertyName.startsWith(DIRECTION_OUT_PREFIX)) {
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

  static void checkPropertyName(String name) {
    if (name.startsWith(DIRECTION_OUT_PREFIX) || name.startsWith(DIRECTION_IN_PREFIX)) {
      throw new IllegalArgumentException(
          "Property name " + name + " is booked as a name that can be used to manage edges.");
    }
  }


  @Override
  default Iterable<Vertex> getVertices(Direction direction) {
    return getVertices(direction, (String[]) null);
  }

  @Override
  default Set<String> getEdgeNames() {
    return getEdgeNames(Direction.BOTH);
  }

  @Override
  default Set<String> getEdgeNames(Direction direction) {
    var propertyNames = getBaseEntity().getPropertyNamesInternal();
    var edgeNames = new HashSet<String>();

    for (var propertyName : propertyNames) {
      if (isConnectionToEdge(direction, propertyName)) {
        edgeNames.add(propertyName);
      }
    }

    return edgeNames;
  }

  static boolean isConnectionToEdge(Direction direction, String propertyName) {
    return switch (direction) {
      case OUT -> propertyName.startsWith(DIRECTION_OUT_PREFIX);
      case IN -> propertyName.startsWith(DIRECTION_IN_PREFIX);
      case BOTH -> propertyName.startsWith(DIRECTION_OUT_PREFIX)
          || propertyName.startsWith(DIRECTION_IN_PREFIX);
    };
  }

  @Override
  default Iterable<Vertex> getVertices(Direction direction, String... type) {
    if (direction == Direction.BOTH) {
      return IterableUtils.chainedIterable(
          getVertices(Direction.OUT, type), getVertices(Direction.IN, type));
    } else {
      var edges = getEdgesInternal(direction, type);
      return new EdgeToVertexIterable(edges, direction);
    }
  }

  @Override
  default Iterable<Vertex> getVertices(Direction direction, SchemaClass... type) {
    var baseEntity = getBaseEntity();
    var session = baseEntity.getSession();

    List<String> types = new ArrayList<>();
    if (type != null) {
      for (var t : type) {
        types.add(t.getName(session));
      }
    }

    return getVertices(direction, types.toArray(new String[]{}));
  }

  @Override
  default StatefulEdge addStateFulEdge(Vertex to) {
    return (StatefulEdge) addEdge(to, EdgeInternal.CLASS_NAME);
  }

  @Override
  default Edge addEdge(Vertex to, String type) {
    var db = getBaseEntity().getSession();
    if (type == null) {
      return db.newStatefulEdge(this, to, EdgeInternal.CLASS_NAME);
    }

    var schemaClass = db.getClass(type);
    if (schemaClass == null) {
      throw new IllegalArgumentException("Schema class for label" + type + " not found");
    }
    if (schemaClass.isAbstract(db)) {
      return db.newLightweightEdge(this, to, type);
    }

    return db.newStatefulEdge(this, to, schemaClass);
  }

  @Override
  default StatefulEdge addStateFulEdge(Vertex to, String label) {
    var db = getBaseEntity().getSession();

    return db.newStatefulEdge(this, to, label == null ? EdgeInternal.CLASS_NAME : label);
  }

  @Override
  default Edge addLightWeightEdge(Vertex to, String label) {
    var db = getBaseEntity().getSession();

    return db.newLightweightEdge(this, to, label);
  }

  @Override
  default Edge addEdge(Vertex to, SchemaClass type) {
    final String className;
    var session = getBaseEntity().getSession();
    if (type != null) {
      className = type.getName(session);
    } else {
      className = EdgeInternal.CLASS_NAME;
    }

    return addEdge(to, className);
  }

  @Override
  default StatefulEdge addStateFulEdge(Vertex to, SchemaClass label) {
    final String className;

    var session = getBaseEntity().getSession();
    if (label != null) {
      className = label.getName(session);
    } else {
      className = EdgeInternal.CLASS_NAME;
    }

    return addStateFulEdge(to, className);
  }

  @Override
  default Edge addLightWeightEdge(Vertex to, SchemaClass label) {
    final String className;
    var session = getBaseEntity().getSession();

    if (label != null) {
      className = label.getName(session);
    } else {
      className = EdgeInternal.CLASS_NAME;
    }

    return addLightWeightEdge(to, className);
  }

  @Override
  default Iterable<Edge> getEdges(Direction direction, SchemaClass... type) {
    List<String> types = new ArrayList<>();
    var baseEntity = getBaseEntity();
    var session = baseEntity.getSession();

    if (type != null) {
      for (var t : type) {
        types.add(t.getName(session));
      }
    }
    return getEdges(direction, types.toArray(new String[]{}));
  }


  @Override
  default Iterable<Edge> getEdges(Direction direction) {
    var prefixes =
        switch (direction) {
          case IN -> new String[]{DIRECTION_IN_PREFIX};
          case OUT -> new String[]{DIRECTION_OUT_PREFIX};
          case BOTH -> new String[]{DIRECTION_IN_PREFIX, DIRECTION_OUT_PREFIX};
        };

    Set<String> candidateClasses = new HashSet<>();

    var entity = getBaseEntity();
    for (var prefix : prefixes) {
      for (var fieldName : entity.calculatePropertyNames()) {
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
  default Iterable<Edge> getEdges(Direction direction, String... labels) {
    return getEdgesInternal(direction, labels);
  }

  private Iterable<Edge> getEdgesInternal(Direction direction, String[] labels) {
    var db = getBaseEntity().getSession();
    var schema = db.getMetadata().getImmutableSchemaSnapshot();

    labels = resolveAliases(db, schema, labels);
    Collection<String> fieldNames = null;
    var entity = getBaseEntity();
    if (labels != null && labels.length > 0) {
      // EDGE LABELS: CREATE FIELD NAME TABLE (FASTER THAN EXTRACT FIELD NAMES FROM THE DOCUMENT)
      var toLoadFieldNames = getEdgeFieldNames(db, schema, direction, labels);

      if (toLoadFieldNames != null) {
        // EARLY FETCH ALL THE FIELDS THAT MATTERS
        entity.deserializeFields(toLoadFieldNames.toArray(new String[]{}));
        fieldNames = toLoadFieldNames;
      }
    }

    if (fieldNames == null) {
      fieldNames = entity.calculatePropertyNames();
    }

    var iterables = new ArrayList<Iterable<Edge>>(fieldNames.size());
    for (var fieldName : fieldNames) {
      final var connection =
          getConnection(db, schema, direction, fieldName, labels);
      if (connection == null)
      // SKIP THIS FIELD
      {
        continue;
      }

      Object fieldValue;

      fieldValue = entity.getPropertyInternal(fieldName);

      if (fieldValue != null) {
        switch (fieldValue) {
          case Identifiable identifiable -> {
            var coll = Collections.singleton(fieldValue);
            iterables.add(new EdgeIterator(this, coll, coll.iterator(), connection, labels, 1, db));
          }
          case Collection<?> coll ->
            // CREATE LAZY Iterable AGAINST COLLECTION FIELD
              iterables.add(
                  new EdgeIterator(this, coll, coll.iterator(), connection, labels, -1, db));
          case RidBag bag -> iterables.add(
              new EdgeIterator(
                  this,
                  fieldValue,
                  bag.iterator(),
                  connection,
                  labels,
                  bag.size(), db));
          default -> {
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

  private static ArrayList<String> getEdgeFieldNames(
      DatabaseSessionInternal db, Schema schema, final Direction iDirection, String... classNames) {
    if (classNames == null)
    // FALL BACK TO LOAD ALL FIELD NAMES
    {
      return null;
    }

    if (classNames.length == 1 && classNames[0].equalsIgnoreCase(EdgeInternal.CLASS_NAME))
    // DEFAULT CLASS, TREAT IT AS NO CLASS/LABEL
    {
      return null;
    }

    Set<String> allClassNames = new HashSet<>();
    for (var className : classNames) {
      allClassNames.add(className);
      var clazz = schema.getClass(className);
      if (clazz != null) {
        allClassNames.add(clazz.getName(db)); // needed for aliases
        var subClasses = clazz.getAllSubclasses(db);
        for (var subClass : subClasses) {
          allClassNames.add(subClass.getName(db));
        }
      }
    }

    var result = new ArrayList<String>(2 * allClassNames.size());
    for (var className : allClassNames) {
      switch (iDirection) {
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

  static Pair<Direction, String> getConnection(
      DatabaseSessionInternal db, final Schema schema,
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
      if (fieldName.startsWith(DIRECTION_OUT_PREFIX)) {
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
            for (var subType : type.getAllSubclasses(db)) {
              clsName = subType.getName(db);

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
      if (fieldName.startsWith(DIRECTION_IN_PREFIX)) {
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
            for (var subType : type.getAllSubclasses(db)) {
              clsName = subType.getName(db);
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

  private static void replaceLinks(
      final EntityImpl vertex,
      final String fieldName,
      final Identifiable iVertexToRemove,
      final Identifiable newVertex) {
    if (vertex == null) {
      return;
    }

    final var fieldValue =
        iVertexToRemove != null
            ? vertex.getPropertyInternal(fieldName)
            : vertex.removePropertyInternal(fieldName);
    switch (fieldValue) {
      case null -> {
      }
      case Identifiable identifiable -> {
        // SINGLE RECORD

        if (iVertexToRemove != null) {
          if (!fieldValue.equals(iVertexToRemove)) {
            return;
          }
          vertex.setPropertyInternal(fieldName, newVertex);
        }
      }
      case RidBag bag -> {
        // COLLECTION OF RECORDS: REMOVE THE ENTRY
        var found = false;
        final var it = bag.iterator();
        while (it.hasNext()) {
          if (it.next().equals(iVertexToRemove.getIdentity())) {
            // REMOVE THE OLD ENTRY
            found = true;
            it.remove();
          }
        }
        if (found)
        // ADD THE NEW ONE
        {
          bag.add(newVertex.getIdentity());
        }
      }
      case Collection<?> collection -> {
        @SuppressWarnings("unchecked") final var col = (Collection<Identifiable>) fieldValue;

        if (col.remove(iVertexToRemove)) {
          col.add(newVertex);
        }
      }
      default -> {
      }
    }

  }

  static void deleteLinks(Vertex delegate) {
    var allEdges = delegate.getEdges(Direction.BOTH);
    List<Edge> items = new ArrayList<>();
    for (var edge : allEdges) {
      items.add(edge);
    }
    for (var edge : items) {
      edge.delete();
    }
  }

  @Override
  default RID moveTo(final String className) {

    final var baseEntity = getBaseEntity();
    var session = baseEntity.getSession();

    var oldIdentity = ((RecordId) getIdentity()).copy();
    final var oldRecord = (EntityImpl) oldIdentity.getEntity(session);

    var newEntity = (EntityImpl) session.newEntity(className);
    newEntity.copyPropertiesFromOtherEntity(oldRecord);

    // DELETE THE OLD RECORD FIRST TO AVOID ISSUES WITH UNIQUE CONSTRAINTS
    session.delete(oldRecord);

    var delegate = new VertexDelegate(newEntity);
    final var outEdges = delegate.getEdges(Direction.OUT);
    final var inEdges = delegate.getEdges(Direction.IN);

    final RID newIdentity = newEntity.getIdentity();

    // CONVERT OUT EDGES
    for (var oe : outEdges) {
      final var inVLink = oe.getVertexLink(Direction.IN);

      var schemaType = oe.getSchemaClassName();
      final var inFieldName = getEdgeLinkFieldName(Direction.IN, schemaType, true);

      // link to itself
      EntityImpl inRecord;
      if (inVLink.equals(oldIdentity)) {
        inRecord = newEntity;
      } else {
        inRecord = inVLink.getRecord(session);
      }
      //noinspection deprecation
      if (oe.isLightweight()) {
        // REPLACE ALL REFS IN inVertex
        replaceLinks(inRecord, inFieldName, oldIdentity, newIdentity);
      } else {
        // REPLACE WITH NEW VERTEX
        ((EntityInternal) oe).setPropertyInternal(EdgeInternal.DIRECTION_OUT, newIdentity);
      }
    }

    for (var ine : inEdges) {
      final var outVLink = ine.getVertexLink(Direction.OUT);

      var schemaType = ine.getSchemaClassName();
      final var outFieldName = getEdgeLinkFieldName(Direction.OUT, schemaType, true);

      EntityImpl outRecord;
      if (outVLink.equals(oldIdentity)) {
        outRecord = newEntity;
      } else {
        outRecord = outVLink.getRecord(session);
      }
      //noinspection deprecation
      if (ine.isLightweight()) {
        // REPLACE ALL REFS IN outVertex
        replaceLinks(outRecord, outFieldName, oldIdentity, newIdentity);
      } else {
        // REPLACE WITH NEW VERTEX
        ((EntityImpl) ine.castToStatefulEdge().castToEntity()).setPropertyInternal(
            Edge.DIRECTION_IN, newIdentity);
      }
    }

    // FINAL SAVE
    return newIdentity;
  }

  EntityImpl getBaseEntity();

  static boolean checkDeletedInTx(DatabaseSessionInternal session, RID id) {
    final var oper = session.getTransaction().getRecordEntry(id);
    if (oper == null) {
      return id.isTemporary();
    } else {
      return oper.type == RecordOperation.DELETED;
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

  static String getEdgeLinkFieldName(
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
  static void changeVertexEdgePointers(
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
      var prevRecord = prevInVertex.<EntityImpl>getRecord(db);

      var prevLink = prevRecord.getPropertyInternal(inFieldName);
      if (prevLink != null) {
        removeVertexLink(prevRecord, inFieldName, prevLink, edgeClass, edge);
      }

      var currentRecord = currentInVertex.<EntityImpl>getRecord(db);
      createLink(db, currentRecord, edge, inFieldName);

    }
  }

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
      return clazz.getName(db);
    }

    return label;
  }


  private static void removeVertexLink(
      EntityInternal vertex,
      String fieldName,
      Object link,
      String label,
      Identifiable identifiable) {
    switch (link) {
      case Collection<?> collection -> collection.remove(identifiable);
      case RidBag bag -> bag.remove(identifiable.getIdentity());
      case Identifiable ignored when link.equals(vertex) ->
          vertex.removePropertyInternal(fieldName);
      case null, default -> throw new IllegalArgumentException(
          label + " is not a valid link in vertex with rid " + vertex.getIdentity());
    }
  }

  /**
   * Creates a link between a vertices and a Graph Element.
   */
  static void createLink(
      DatabaseSessionInternal session, final EntityImpl fromVertex, final Identifiable to,
      final String fieldName) {
    final Object out;
    var outType = fromVertex.getPropertyType(fieldName);
    var found = fromVertex.getPropertyInternal(fieldName);

    var result = fromVertex.getImmutableSchemaClass(session);
    if (result == null) {
      throw new IllegalArgumentException("Class not found in source vertex: " + fromVertex);
    }

    final var prop = result.getProperty(session, fieldName);
    final var propType =
        prop != null && prop.getType(session) != PropertyType.ANY ? prop.getType(session) : null;

    switch (found) {
      case null -> {
        if (propType == PropertyType.LINKLIST
            || (prop != null
            && "true".equalsIgnoreCase(prop.getCustom(session, "ordered")))) { // TODO constant
          var coll = new LinkList(fromVertex);
          coll.add(to);
          out = coll;
          outType = PropertyType.LINKLIST;
        } else if (propType == null || propType == PropertyType.LINKBAG) {
          final var bag = new RidBag(fromVertex.getSession());
          bag.add(to.getIdentity());
          out = bag;
          outType = PropertyType.LINKBAG;
        } else if (propType == PropertyType.LINK) {
          out = to;
          outType = PropertyType.LINK;
        } else {
          throw new DatabaseException(session.getDatabaseName(),
              "Type of field provided in schema '"
                  + prop.getType(session)
                  + "' cannot be used for link creation.");
        }
      }
      case Identifiable foundId -> {
        if (prop != null && propType == PropertyType.LINK) {
          throw new DatabaseException(session.getDatabaseName(),
              "Type of field provided in schema '"
                  + prop.getType(session)
                  + "' cannot be used for creation to hold several links.");
        }

        if (prop != null && "true".equalsIgnoreCase(
            prop.getCustom(session, "ordered"))) { // TODO constant
          var coll = new LinkList(fromVertex);
          coll.add(foundId);
          coll.add(to);
          out = coll;
          outType = PropertyType.LINKLIST;
        } else {
          final var bag = new RidBag(fromVertex.getSession());
          bag.add(foundId.getIdentity());
          bag.add(to.getIdentity());
          out = bag;
          outType = PropertyType.LINKBAG;
        }
      }
      case RidBag bag -> {
        // ADD THE LINK TO THE COLLECTION
        out = null;
        bag.add(to.getRecord(session).getIdentity());
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
    } else if (edgeProp instanceof RidBag) {
      ((RidBag) edgeProp).remove(edgeId.getIdentity());
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
    removeLinkFromEdge(db, ((VertexInternal) vertex).getBaseEntity(), edge, Direction.IN);
  }

  static void removeOutgoingEdge(DatabaseSessionInternal db, Vertex vertex, Edge edge) {
    removeLinkFromEdge(db, ((VertexInternal) vertex).getBaseEntity(), edge, Direction.OUT);
  }
}
