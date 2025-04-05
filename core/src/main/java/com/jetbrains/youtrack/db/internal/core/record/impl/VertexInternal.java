package com.jetbrains.youtrack.db.internal.core.record.impl;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.exception.DatabaseException;
import com.jetbrains.youtrack.db.api.exception.RecordNotFoundException;
import com.jetbrains.youtrack.db.api.record.DBRecord;
import com.jetbrains.youtrack.db.api.record.Direction;
import com.jetbrains.youtrack.db.api.record.Edge;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.record.Vertex;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.api.schema.SchemaProperty;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.common.util.Pair;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseRecordThreadLocal;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.record.LinkList;
import com.jetbrains.youtrack.db.internal.core.db.record.RecordOperation;
import com.jetbrains.youtrack.db.internal.core.db.record.ridbag.RidBag;
import com.jetbrains.youtrack.db.internal.core.id.ChangeableRecordId;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.record.RecordInternal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.apache.commons.collections4.IterableUtils;

public interface VertexInternal extends Vertex, EntityInternal {

  @Nonnull
  EntityImpl getBaseDocument();

  @Override
  default Set<String> getPropertyNames() {
    return filterPropertyNames(getBaseDocument().getPropertyNamesInternal());
  }

  @Override
  default Set<String> getPropertyNamesInternal() {
    return getBaseDocument().getPropertyNamesInternal();
  }

  static Set<String> filterPropertyNames(Set<String> propertyNames) {
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

  @Override
  default <RET> RET getProperty(String name) {
    checkPropertyName(name);

    return getBaseDocument().getPropertyInternal(name);
  }

  @Override
  default <RET> RET getPropertyInternal(String name, boolean lazyLoading) {
    return getBaseDocument().getPropertyInternal(name, lazyLoading);
  }

  @Override
  default <RET> RET getPropertyInternal(String name) {
    return getBaseDocument().getPropertyInternal(name);
  }

  @Override
  default <RET> RET getPropertyOnLoadValue(String name) {
    return getBaseDocument().getPropertyOnLoadValue(name);
  }

  @Nullable
  @Override
  default Identifiable getLinkPropertyInternal(String name) {
    return getBaseDocument().getLinkPropertyInternal(name);
  }

  @Nullable
  @Override
  default Identifiable getLinkProperty(String name) {
    checkPropertyName(name);

    return getBaseDocument().getLinkProperty(name);
  }

  static void checkPropertyName(String name) {
    if (name.startsWith(DIRECTION_OUT_PREFIX) || name.startsWith(DIRECTION_IN_PREFIX)) {
      throw new IllegalArgumentException(
          "Property name " + name + " is booked as a name that can be used to manage edges.");
    }
  }

  @Override
  default void setProperty(String name, Object value) {
    checkPropertyName(name);

    getBaseDocument().setPropertyInternal(name, value);
  }

  @Override
  default void setPropertyInternal(String name, Object value) {
    getBaseDocument().setPropertyInternal(name, value);
  }

  @Override
  default boolean hasProperty(final String propertyName) {
    checkPropertyName(propertyName);

    return getBaseDocument().hasProperty(propertyName);
  }

  @Override
  default void setProperty(String name, Object value, PropertyType... fieldType) {
    checkPropertyName(name);

    getBaseDocument().setPropertyInternal(name, value, fieldType);
  }

  @Override
  default void setPropertyInternal(String name, Object value, PropertyType... type) {
    getBaseDocument().setPropertyInternal(name, value, type);
  }

  @Override
  default <RET> RET removeProperty(String name) {
    checkPropertyName(name);

    return getBaseDocument().removePropertyInternal(name);
  }

  @Override
  default <RET> RET removePropertyInternal(String name) {
    return getBaseDocument().removePropertyInternal(name);
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
    var propertyNames = getBaseDocument().getPropertyNamesInternal();
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
      Iterable<Edge> edges = getEdgesInternal(direction, type);
      return new EdgeToVertexIterable(edges, direction);
    }
  }

  @Override
  default Iterable<Vertex> getVertices(Direction direction, SchemaClass... type) {
    List<String> types = new ArrayList<>();
    if (type != null) {
      for (SchemaClass t : type) {
        types.add(t.getName());
      }
    }

    return getVertices(direction, types.toArray(new String[]{}));
  }

  @Override
  default Edge addRegularEdge(Vertex to) {
    return addEdge(to, EdgeInternal.CLASS_NAME);
  }

  @Override
  default Edge addEdge(Vertex to, String type) {
    var db = getBaseDocument().getSession();
    if (type == null) {
      return db.newRegularEdge(this, to, EdgeInternal.CLASS_NAME);
    }

    var schemaClass = db.getClass(type);
    if (schemaClass == null) {
      throw new IllegalArgumentException("Schema class for label" + type + " not found");
    }
    if (schemaClass.isAbstract()) {
      return db.newLightweightEdge(this, to, type);
    }

    return db.newRegularEdge(this, to, schemaClass);
  }

  @Override
  default Edge addRegularEdge(Vertex to, String label) {
    var db = getBaseDocument().getSession();

    return db.newRegularEdge(this, to, label == null ? EdgeInternal.CLASS_NAME : label);
  }

  @Override
  default Edge addLightWeightEdge(Vertex to, String label) {
    var db = getBaseDocument().getSession();

    return db.newLightweightEdge(this, to, label);
  }

  @Override
  default Edge addEdge(Vertex to, SchemaClass type) {
    final String className;
    if (type != null) {
      className = type.getName();
    } else {
      className = EdgeInternal.CLASS_NAME;
    }

    return addEdge(to, className);
  }

  @Override
  default Edge addRegularEdge(Vertex to, SchemaClass label) {
    final String className;

    if (label != null) {
      className = label.getName();
    } else {
      className = EdgeInternal.CLASS_NAME;
    }

    return addRegularEdge(to, className);
  }

  @Override
  default Edge addLightWeightEdge(Vertex to, SchemaClass label) {
    final String className;

    if (label != null) {
      className = label.getName();
    } else {
      className = EdgeInternal.CLASS_NAME;
    }

    return addLightWeightEdge(to, className);
  }

  @Override
  default Iterable<Edge> getEdges(Direction direction, SchemaClass... type) {
    List<String> types = new ArrayList<>();
    if (type != null) {
      for (SchemaClass t : type) {
        types.add(t.getName());
      }
    }
    return getEdges(direction, types.toArray(new String[]{}));
  }

  @Override
  default boolean isUnloaded() {
    return getBaseDocument().isUnloaded();
  }

  default boolean isNotBound(DatabaseSession session) {
    return getBaseDocument().isNotBound(session);
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

    var entity = getBaseDocument();
    for (var prefix : prefixes) {
      for (String fieldName : entity.calculatePropertyNames()) {
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
  default boolean exists() {
    return getBaseDocument().exists();
  }

  @Override
  default Iterable<Edge> getEdges(Direction direction, String... labels) {
    return getEdgesInternal(direction, labels);
  }

  private Iterable<Edge> getEdgesInternal(Direction direction, String[] labels) {
    var db = getBaseDocument().getSession();
    var schema = db.getMetadata().getImmutableSchemaSnapshot();

    labels = resolveAliases(schema, labels);
    Collection<String> fieldNames = null;
    var entity = getBaseDocument();
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
      final Pair<Direction, String> connection =
          getConnection(db, schema, direction, fieldName, labels);
      if (connection == null)
      // SKIP THIS FIELD
      {
        continue;
      }

      Object fieldValue;

      fieldValue = entity.getPropertyInternal(fieldName);

      if (fieldValue != null) {
        if (fieldValue instanceof Identifiable) {
          var coll = Collections.singleton(fieldValue);
          iterables.add(new EdgeIterator(this, coll, coll.iterator(), connection, labels, 1));
        } else if (fieldValue instanceof Collection<?> coll) {
          // CREATE LAZY Iterable AGAINST COLLECTION FIELD
          iterables.add(new EdgeIterator(this, coll, coll.iterator(), connection, labels, -1));
        } else if (fieldValue instanceof RidBag) {
          iterables.add(
              new EdgeIterator(
                  this,
                  fieldValue,
                  ((RidBag) fieldValue).iterator(),
                  connection,
                  labels,
                  ((RidBag) fieldValue).size()));
        }
      }
    }

    if (iterables.size() == 1) {
      return iterables.get(0);
    } else if (iterables.isEmpty()) {
      return Collections.emptyList();
    }

    //noinspection unchecked
    return IterableUtils.chainedIterable(iterables.toArray(new Iterable[0]));
  }

  private static ArrayList<String> getEdgeFieldNames(
      DatabaseSessionInternal session,
      Schema schema, final Direction iDirection, String... classNames) {
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
    for (String className : classNames) {
      allClassNames.add(className);
      SchemaClass clazz = schema.getClass(className);
      if (clazz != null) {
        allClassNames.add(clazz.getName()); // needed for aliases
        Collection<SchemaClass> subClasses = clazz.getAllSubclasses(session);
        for (SchemaClass subClass : subClasses) {
          allClassNames.add(subClass.getName());
        }
      }
    }

    var result = new ArrayList<String>(2 * allClassNames.size());
    for (String className : allClassNames) {
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
      final DatabaseSession session,
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
      if (fieldName.startsWith(DIRECTION_OUT_PREFIX)) {
        if (classNames == null || classNames.length == 0) {
          return new Pair<>(Direction.OUT, getConnectionClass(Direction.OUT, fieldName));
        }

        // CHECK AGAINST ALL THE CLASS NAMES
        for (String clsName : classNames) {
          if (fieldName.equals(DIRECTION_OUT_PREFIX + clsName)) {
            return new Pair<>(Direction.OUT, clsName);
          }

          // GO DOWN THROUGH THE INHERITANCE TREE
          SchemaClass type = schema.getClass(clsName);
          if (type != null) {
            for (SchemaClass subType : type.getAllSubclasses(session)) {
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
      if (fieldName.startsWith(DIRECTION_IN_PREFIX)) {
        if (classNames == null || classNames.length == 0) {
          return new Pair<>(Direction.IN, getConnectionClass(Direction.IN, fieldName));
        }

        // CHECK AGAINST ALL THE CLASS NAMES
        for (String clsName : classNames) {

          if (fieldName.equals(DIRECTION_IN_PREFIX + clsName)) {
            return new Pair<>(Direction.IN, clsName);
          }

          // GO DOWN THROUGH THE INHERITANCE TREE
          SchemaClass type = schema.getClass(clsName);
          if (type != null) {
            for (SchemaClass subType : type.getAllSubclasses(session)) {
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

  private static void replaceLinks(
      final EntityImpl vertex,
      final String fieldName,
      final Identifiable iVertexToRemove,
      final Identifiable newVertex) {
    if (vertex == null) {
      return;
    }

    final Object fieldValue =
        iVertexToRemove != null
            ? vertex.getPropertyInternal(fieldName)
            : vertex.removePropertyInternal(fieldName);
    if (fieldValue == null) {
      return;
    }

    if (fieldValue instanceof Identifiable) {
      // SINGLE RECORD

      if (iVertexToRemove != null) {
        if (!fieldValue.equals(iVertexToRemove)) {
          return;
        }
        vertex.setPropertyInternal(fieldName, newVertex);
      }

    } else if (fieldValue instanceof RidBag bag) {
      // COLLECTION OF RECORDS: REMOVE THE ENTRY
      boolean found = false;
      final Iterator<Identifiable> it = bag.iterator();
      while (it.hasNext()) {
        if (it.next().equals(iVertexToRemove)) {
          // REMOVE THE OLD ENTRY
          found = true;
          it.remove();
        }
      }
      if (found)
      // ADD THE NEW ONE
      {
        bag.add(newVertex);
      }

    } else if (fieldValue instanceof Collection) {
      @SuppressWarnings("unchecked") final Collection<Identifiable> col = (Collection<Identifiable>) fieldValue;

      if (col.remove(iVertexToRemove)) {
        col.add(newVertex);
      }
    }

    vertex.save();
  }

  static void deleteLinks(Vertex delegate) {
    Iterable<Edge> allEdges = delegate.getEdges(Direction.BOTH);
    List<Edge> items = new ArrayList<>();
    for (Edge edge : allEdges) {
      items.add(edge);
    }
    for (Edge edge : items) {
      edge.delete();
    }
  }

  @Override
  default RID moveTo(final String className, final String clusterName) {

    final EntityImpl baseEntity = getBaseDocument();
    var db = baseEntity.getSession();
    if (!db.getTransaction().isActive()) {
      throw new DatabaseException("This operation is allowed only inside a transaction");
    }
    if (checkDeletedInTx(getIdentity())) {
      throw new RecordNotFoundException(
          getIdentity(), "The vertex " + getIdentity() + " has been deleted");
    }

    var oldIdentity = ((RecordId) getIdentity()).copy();

    final DBRecord oldRecord = oldIdentity.getRecord();
    var entity = baseEntity.copy();
    RecordInternal.setIdentity(entity, new ChangeableRecordId());

    // DELETE THE OLD RECORD FIRST TO AVOID ISSUES WITH UNIQUE CONSTRAINTS
    copyRidBags(db, oldRecord, entity);
    detachRidbags(oldRecord);
    db.delete(oldRecord);

    var delegate = new VertexDelegate(entity);
    final Iterable<Edge> outEdges = delegate.getEdges(Direction.OUT);
    final Iterable<Edge> inEdges = delegate.getEdges(Direction.IN);
    if (className != null) {
      entity.setClassName(className);
    }

    // SAVE THE NEW VERTEX
    entity.setDirty();

    RecordInternal.setIdentity(entity, new ChangeableRecordId());
    db.save(entity, clusterName);
    if (db.getTransaction().getEntryCount() == 2) {
      System.out.println("WTF");
      db.save(entity, clusterName);
    }
    final RID newIdentity = entity.getIdentity();

    // CONVERT OUT EDGES
    for (Edge oe : outEdges) {
      final Identifiable inVLink = oe.getVertexLink(Direction.IN);
      var optSchemaType = oe.getSchemaType();

      String schemaType;
      //noinspection OptionalIsPresent
      if (optSchemaType.isPresent()) {
        schemaType = optSchemaType.get().getName();
      } else {
        schemaType = null;
      }

      final String inFieldName = getEdgeLinkFieldName(Direction.IN, schemaType, true);

      // link to itself
      EntityImpl inRecord;
      if (inVLink.equals(oldIdentity)) {
        inRecord = entity;
      } else {
        inRecord = inVLink.getRecord();
      }
      //noinspection deprecation
      if (oe.isLightweight()) {
        // REPLACE ALL REFS IN inVertex
        replaceLinks(inRecord, inFieldName, oldIdentity, newIdentity);
      } else {
        // REPLACE WITH NEW VERTEX
        ((EntityInternal) oe).setPropertyInternal(EdgeInternal.DIRECTION_OUT, newIdentity);
      }

      db.save(oe);
    }

    for (Edge ine : inEdges) {
      final Identifiable outVLink = ine.getVertexLink(Direction.OUT);

      var optSchemaType = ine.getSchemaType();

      String schemaType;
      //noinspection OptionalIsPresent
      if (optSchemaType.isPresent()) {
        schemaType = optSchemaType.get().getName();
      } else {
        schemaType = null;
      }

      final String outFieldName = getEdgeLinkFieldName(Direction.OUT, schemaType, true);

      EntityImpl outRecord;
      if (outVLink.equals(oldIdentity)) {
        outRecord = entity;
      } else {
        outRecord = outVLink.getRecord();
      }
      //noinspection deprecation
      if (ine.isLightweight()) {
        // REPLACE ALL REFS IN outVertex
        replaceLinks(outRecord, outFieldName, oldIdentity, newIdentity);
      } else {
        // REPLACE WITH NEW VERTEX
        ((EdgeInternal) ine).setPropertyInternal(Edge.DIRECTION_IN, newIdentity);
      }

      db.save(ine);
    }

    // FINAL SAVE
    db.save(entity);
    return newIdentity;
  }

  private static void detachRidbags(DBRecord oldRecord) {
    EntityImpl oldEntity = (EntityImpl) oldRecord;
    for (String field : oldEntity.getPropertyNamesInternal()) {
      if (field.equalsIgnoreCase(EdgeInternal.DIRECTION_OUT)
          || field.equalsIgnoreCase(EdgeInternal.DIRECTION_IN)
          || field.startsWith(DIRECTION_OUT_PREFIX)
          || field.startsWith(DIRECTION_IN_PREFIX)
          || field.startsWith("OUT_")
          || field.startsWith("IN_")) {
        Object val = oldEntity.rawField(field);
        if (val instanceof RidBag) {
          oldEntity.removePropertyInternal(field);
        }
      }
    }
  }

  static boolean checkDeletedInTx(RID id) {
    var db = DatabaseRecordThreadLocal.instance().get();
    if (db == null) {
      return false;
    }

    final RecordOperation oper = db.getTransaction().getRecordEntry(id);
    if (oper == null) {
      return id.isTemporary();
    } else {
      return oper.type == RecordOperation.DELETED;
    }
  }

  private static void copyRidBags(DatabaseSessionInternal db, DBRecord oldRecord,
      EntityImpl newDoc) {
    EntityImpl oldEntity = (EntityImpl) oldRecord;
    for (String field : oldEntity.getPropertyNamesInternal()) {
      if (field.equalsIgnoreCase(EdgeInternal.DIRECTION_OUT)
          || field.equalsIgnoreCase(EdgeInternal.DIRECTION_IN)
          || field.startsWith(DIRECTION_OUT_PREFIX)
          || field.startsWith(DIRECTION_IN_PREFIX)
          || field.startsWith("OUT_")
          || field.startsWith("IN_")) {
        Object val = oldEntity.rawField(field);
        if (val instanceof RidBag bag) {
          if (!bag.isEmbedded()) {
            RidBag newBag = new RidBag(db);
            for (Identifiable identifiable : bag) {
              newBag.add(identifiable);
            }
            newDoc.setPropertyInternal(field, newBag);
          }
        }
      }
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
      final String prefix =
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
      DatabaseSessionInternal session,
      EntityImpl edge,
      Identifiable prevInVertex,
      Identifiable currentInVertex,
      Identifiable prevOutVertex,
      Identifiable currentOutVertex) {
    var edgeClass = edge.getClassName();

    if (currentInVertex != prevInVertex) {
      changeVertexEdgePointersOneDirection(
          session, edge, prevInVertex, currentInVertex, edgeClass, Direction.IN);
    }
    if (currentOutVertex != prevOutVertex) {
      changeVertexEdgePointersOneDirection(
          session, edge, prevOutVertex, currentOutVertex, edgeClass, Direction.OUT);
    }
  }

  private static void changeVertexEdgePointersOneDirection(
      DatabaseSessionInternal session,
      EntityImpl edge,
      Identifiable prevInVertex,
      Identifiable currentInVertex,
      String edgeClass,
      Direction direction) {
    if (prevInVertex != null) {
      var inFieldName = Vertex.getEdgeLinkFieldName(direction, edgeClass);
      var prevRecord = prevInVertex.<EntityImpl>getRecord();

      var prevLink = prevRecord.getPropertyInternal(inFieldName);
      if (prevLink != null) {
        removeVertexLink(prevRecord, inFieldName, prevLink, edgeClass, edge);
      }

      var currentRecord = currentInVertex.<EntityImpl>getRecord();
      createLink(session, currentRecord, edge, inFieldName);

      prevRecord.save();
      currentRecord.save();
    }
  }

  private static String[] resolveAliases(Schema schema, String[] labels) {
    if (labels == null) {
      return null;
    }
    String[] result = new String[labels.length];

    for (int i = 0; i < labels.length; i++) {
      result[i] = resolveAlias(labels[i], schema);
    }

    return result;
  }

  private static String resolveAlias(String label, Schema schema) {
    SchemaClass clazz = schema.getClass(label);
    if (clazz != null) {
      return clazz.getName();
    }

    return label;
  }


  private static void removeVertexLink(
      EntityInternal vertex,
      String fieldName,
      Object link,
      String label,
      Identifiable identifiable) {
    if (link instanceof Collection) {
      ((Collection<?>) link).remove(identifiable);
    } else if (link instanceof RidBag) {
      ((RidBag) link).remove(identifiable);
    } else if (link instanceof Identifiable && link.equals(vertex)) {
      vertex.removePropertyInternal(fieldName);
    } else {
      throw new IllegalArgumentException(
          label + " is not a valid link in vertex with rid " + vertex.getIdentity());
    }
  }

  /**
   * Creates a link between a vertices and a Graph Element.
   */
  static void createLink(
      DatabaseSession session,
      final EntityImpl fromVertex, final Identifiable to, final String fieldName) {
    final Object out;
    PropertyType outType = fromVertex.fieldType(fieldName);
    Object found = fromVertex.getPropertyInternal(fieldName);

    final SchemaClass linkClass = EntityInternalUtils.getImmutableSchemaClass(fromVertex);
    if (linkClass == null) {
      throw new IllegalArgumentException("Class not found in source vertex: " + fromVertex);
    }

    final SchemaProperty prop = linkClass.getProperty(session, fieldName);
    final PropertyType propType =
        prop != null && prop.getType() != PropertyType.ANY ? prop.getType() : null;

    if (found == null) {
      if (propType == PropertyType.LINKLIST
          || (prop != null
          && "true".equalsIgnoreCase(prop.getCustom("ordered")))) { // TODO constant
        var coll = new LinkList(fromVertex);
        coll.add(to);
        out = coll;
        outType = PropertyType.LINKLIST;
      } else if (propType == null || propType == PropertyType.LINKBAG) {
        final RidBag bag = new RidBag(fromVertex.getSession());
        bag.add(to);
        out = bag;
        outType = PropertyType.LINKBAG;
      } else if (propType == PropertyType.LINK) {
        out = to;
        outType = PropertyType.LINK;
      } else {
        throw new DatabaseException(
            "Type of field provided in schema '"
                + prop.getType()
                + "' cannot be used for link creation.");
      }

    } else if (found instanceof Identifiable foundId) {
      if (prop != null && propType == PropertyType.LINK) {
        throw new DatabaseException(
            "Type of field provided in schema '"
                + prop.getType()
                + "' cannot be used for creation to hold several links.");
      }

      if (prop != null && "true".equalsIgnoreCase(prop.getCustom("ordered"))) { // TODO constant
        var coll = new LinkList(fromVertex);
        coll.add(foundId);
        coll.add(to);
        out = coll;
        outType = PropertyType.LINKLIST;
      } else {
        final RidBag bag = new RidBag(fromVertex.getSession());
        bag.add(foundId);
        bag.add(to);
        out = bag;
        outType = PropertyType.LINKBAG;
      }
    } else if (found instanceof RidBag) {
      // ADD THE LINK TO THE COLLECTION
      out = null;

      ((RidBag) found).add(to.getRecord());

    } else if (found instanceof Collection<?>) {
      // USE THE FOUND COLLECTION
      out = null;
      //noinspection unchecked
      ((Collection<Identifiable>) found).add(to);

    } else {
      throw new DatabaseException(
          "Relationship content is invalid on field " + fieldName + ". Found: " + found);
    }

    if (out != null)
    // OVERWRITE IT
    {
      fromVertex.setPropertyInternal(fieldName, out, outType);
    }
  }

  private static void removeLinkFromEdge(EntityImpl vertex, Edge edge, Direction direction) {
    var schemaType = edge.getSchemaType();
    assert schemaType.isPresent();

    String className = schemaType.get().getName();
    Identifiable edgeId = edge.getIdentity();

    removeLinkFromEdge(
        vertex, edge, Vertex.getEdgeLinkFieldName(direction, className), edgeId, direction);
  }

  private static void removeLinkFromEdge(
      EntityImpl vertex, Edge edge, String edgeField, Identifiable edgeId,
      Direction direction) {
    Object edgeProp = vertex.getPropertyInternal(edgeField);
    RID oppositeVertexId = null;
    if (direction == Direction.IN) {
      var fromIdentifiable = edge.getFromIdentifiable();
      if (fromIdentifiable != null) {
        oppositeVertexId = fromIdentifiable.getIdentity();
      }
    } else {
      var toIdentifiable = edge.getToIdentifiable();
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
      ((RidBag) edgeProp).remove(edgeId);
    } else //noinspection deprecation
      if (edgeProp instanceof Identifiable
          && ((Identifiable) edgeProp).getIdentity() != null
          && ((Identifiable) edgeProp).getIdentity().equals(edgeId)
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

  static void removeIncomingEdge(Vertex vertex, Edge edge) {
    removeLinkFromEdge(((VertexInternal) vertex).getBaseDocument(), edge, Direction.IN);
  }

  static void removeOutgoingEdge(Vertex vertex, Edge edge) {
    removeLinkFromEdge(((VertexInternal) vertex).getBaseDocument(), edge, Direction.OUT);
  }
}
