package com.jetbrains.youtrack.db.api.query;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.exception.DatabaseException;
import com.jetbrains.youtrack.db.api.record.Blob;
import com.jetbrains.youtrack.db.api.record.DBRecord;
import com.jetbrains.youtrack.db.api.record.Edge;
import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.record.StatefulEdge;
import com.jetbrains.youtrack.db.api.record.Vertex;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.PropertyTypeInternal;
import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public interface Result {

  /**
   * @param name the property name
   * @return the property value.
   */
  @Nullable
  <T> T getProperty(@Nonnull String name);

  @Nullable
  default Boolean getBoolean(@Nonnull String name) {
    var value = getProperty(name);
    if (value == null) {
      return null;
    }
    if (value instanceof Boolean) {
      return (Boolean) value;
    }

    throw new DatabaseException(
        "Property " + name + " is not a boolean type, but " + value.getClass().getName());
  }

  @Nullable
  default Byte getByte(@Nonnull String name) {
    var value = getProperty(name);
    if (value == null) {
      return null;
    }
    if (value instanceof Byte) {
      return (Byte) value;
    }

    throw new DatabaseException(
        "Property " + name + " is not a byte type, but " + value.getClass().getName());
  }

  @Nullable
  default Short getShort(@Nonnull String name) {
    var value = getProperty(name);
    if (value == null) {
      return null;
    }
    if (value instanceof Short) {
      return (Short) value;
    }

    throw new DatabaseException(
        "Property " + name + " is not a short type, but " + value.getClass().getName());
  }

  @Nullable
  default Integer getInt(@Nonnull String name) {
    var value = getProperty(name);
    if (value == null) {
      return null;
    }
    if (value instanceof Integer) {
      return (Integer) value;
    }

    throw new DatabaseException(
        "Property " + name + " is not an integer type, but " + value.getClass().getName());

  }

  @Nullable
  default Long getLong(@Nonnull String name) {
    var value = getProperty(name);
    if (value == null) {
      return null;
    }

    if (value instanceof Long) {
      return (Long) value;
    }

    throw new DatabaseException(
        "Property " + name + " is not a long type, but " + value.getClass().getName());
  }

  @Nullable
  default Float getFloat(@Nonnull String name) {
    var value = getProperty(name);
    if (value == null) {
      return null;
    }
    if (value instanceof Float) {
      return (Float) value;
    }

    throw new DatabaseException(
        "Property " + name + " is not a float type, but " + value.getClass().getName());
  }

  @Nullable
  default Double getDouble(@Nonnull String name) {
    var value = getProperty(name);
    if (value == null) {
      return null;
    }
    if (value instanceof Double) {
      return (Double) value;
    }

    throw new DatabaseException(
        "Property " + name + " is not a double type, but " + value.getClass().getName());
  }

  @Nullable
  default String getString(@Nonnull String name) {
    var value = getProperty(name);
    if (value == null) {
      return null;
    }
    if (value instanceof String) {
      return (String) value;
    }

    throw new DatabaseException(
        "Property " + name + " is not a string type, but " + value.getClass().getName());
  }

  @Nullable
  default byte[] getBinary(@Nonnull String name) {
    var value = getProperty(name);
    if (value == null) {
      return null;
    }
    if (value instanceof byte[]) {
      return (byte[]) value;
    }

    throw new DatabaseException(
        "Property " + name + " is not a binary type, but " + value.getClass().getName());
  }

  @Nullable
  default Date getDate(@Nonnull String name) {
    var value = getProperty(name);
    if (value == null) {
      return null;
    }
    if (value instanceof Date) {
      return (Date) value;
    }

    throw new DatabaseException(
        "Property " + name + " is not a date type, but " + value.getClass().getName());
  }

  @Nullable
  default Date getDateTime(@Nonnull String name) {
    return getDate(name);
  }

  @Nullable
  default BigDecimal getDecimal(@Nonnull String name) {
    var value = getProperty(name);
    if (value == null) {
      return null;
    }

    if (value instanceof BigDecimal) {
      return (BigDecimal) value;
    }

    throw new DatabaseException(
        "Property " + name + " is not a decimal type, but " + value.getClass().getName());
  }

  @Nullable
  default <T> List<T> getEmbeddedList(@Nonnull String name) {
    if (isEntity()) {
      return asEntity().getEmbeddedList(name);
    }

    var value = getProperty(name);
    if (value == null) {
      return null;
    }

    if (value instanceof List<?> list && !PropertyTypeInternal.checkLinkCollection(list)) {
      //noinspection unchecked
      return (List<T>) list;
    }

    throw new DatabaseException(
        "Property " + name + " is not a embedded list type, but " + value.getClass().getName());
  }

  @Nullable
  default List<Identifiable> getLinkList(@Nonnull String name) {
    if (isEntity()) {
      return asEntity().getLinkList(name);
    }

    var value = getProperty(name);

    if (value == null) {
      return null;
    }

    if (value instanceof List<?> list && PropertyTypeInternal.canBeLinkCollection(list)) {
      //noinspection unchecked
      return (List<Identifiable>) list;
    }

    throw new DatabaseException(
        "Property " + name + " is not a link list type, but " + value.getClass().getName());
  }

  @Nullable
  default <T> Set<T> getEmbeddedSet(@Nonnull String name) {
    if (isEntity()) {
      return asEntity().getEmbeddedSet(name);
    }

    var value = getProperty(name);
    if (value == null) {
      return null;
    }

    if (value instanceof Set<?> set && !PropertyTypeInternal.checkLinkCollection(set)) {
      //noinspection unchecked
      return (Set<T>) set;
    }

    throw new DatabaseException(
        "Property " + name + " is not a embedded set type, but " + value.getClass().getName());
  }

  @Nullable
  default Set<Identifiable> getLinkSet(@Nonnull String name) {
    if (isEntity()) {
      return asEntity().getLinkSet(name);
    }

    var value = getProperty(name);
    if (value == null) {
      return null;
    }

    if (value instanceof Set<?> set && PropertyTypeInternal.canBeLinkCollection(set)) {
      //noinspection unchecked
      return (Set<Identifiable>) set;
    }

    throw new DatabaseException(
        "Property " + name + " is not a link set type, but " + value.getClass().getName());
  }

  @Nullable
  default <T> Map<String, T> getEmbeddedMap(@Nonnull String name) {
    if (isEntity()) {
      return asEntity().getEmbeddedMap(name);
    }

    var value = getProperty(name);
    if (value == null) {
      return null;
    }

    if (value instanceof Map<?, ?> map && !PropertyTypeInternal.checkLinkCollection(map.values())) {
      //noinspection unchecked
      return (Map<String, T>) map;
    }

    throw new DatabaseException(
        "Property " + name + " is not a embedded map type, but " + value.getClass().getName());
  }

  @Nullable
  default Map<String, Identifiable> getLinkMap(@Nonnull String name) {
    if (isEntity()) {
      return asEntity().getLinkMap(name);
    }

    var value = getProperty(name);
    if (value == null) {
      return null;
    }

    if (value instanceof Map<?, ?> map && PropertyTypeInternal.canBeLinkCollection(map.values())) {
      //noinspection unchecked
      return (Map<String, Identifiable>) map;
    }

    throw new DatabaseException(
        "Property " + name + " is not a link map type, but " + value.getClass().getName());
  }

  /**
   * Either loads the property value as an {@link Entity} if it is a
   * {@link com.jetbrains.youtrack.db.api.schema.PropertyType#LINK} type or returns embedded
   * entity.
   *
   * @param name the property name
   * @return the property value. Null if the property is not defined.
   * @throws DatabaseException if the property is not an Entity.
   */
  @Nullable
  Entity getEntity(@Nonnull String name);


  @Nullable
  Result getResult(@Nonnull String name);

  /**
   * Returns the property value as a vertex. If the property is a link, it will be loaded and
   * returned as an Vertex. If the property is not vertex, exception will be thrown.
   *
   * @param propertyName the property name
   * @return the property value as a vertex
   * @throws DatabaseException if the property is not a vertex
   */
  @Nullable
  default Vertex getVertex(@Nonnull String propertyName) {
    var entity = getEntity(propertyName);
    if (entity == null) {
      return null;
    }

    return entity.asVertex();
  }

  /**
   * Returns the property value as an Edge. If the property is a link, it will be loaded and
   * returned as an Edge. If the property is an Edge, exception will be thrown.
   *
   * @param propertyName the property name
   * @return the property value as an Edge
   * @throws DatabaseException if the property is not an Edge
   */
  @Nullable
  default Edge getEdge(@Nonnull String propertyName) {
    var entity = getEntity(propertyName);
    if (entity == null) {
      return null;
    }

    return entity.asEdge();
  }

  /**
   * returns an Blob property from the result
   *
   * @param name the property name
   * @return the property value. Null if the property is not defined or if it's not an Blob
   */
  @Nullable
  Blob getBlob(String name);

  /**
   * This method similar to {@link #getProperty(String)} bun unlike before mentioned method it does
   * not load link automatically.
   *
   * @param name the name of the link property
   * @return the link property value, or null if the property does not exist
   * @throws IllegalArgumentException if requested property is not a link.
   * @see #getProperty(String)
   */
  @Nullable
  RID getLink(@Nonnull String name);

  /**
   * Returns all the names of defined properties
   *
   * @return all the names of defined properties
   */
  @Nonnull
  List<String> getPropertyNames();

  @Nullable
  RID getIdentity();

  boolean isEntity();

  @Nonnull
  Entity asEntity();

  @Nullable
  Entity asEntityOrNull();

  boolean isVertex();

  @Nonnull
  default Vertex asVertex() {
    return asEntity().asVertex();
  }

  @Nullable
  default Vertex asVertexOrNull() {
    var entity = asEntityOrNull();

    if (entity == null) {
      return null;
    }
    return entity.asVertexOrNull();
  }

  boolean isEdge();

  @Nonnull
  Edge asEdge();

  @Nullable
  Edge asEdgeOrNull();

  boolean isStatefulEdge();

  @Nonnull
  default StatefulEdge asStatefulEdge() {
    return asEntity().asStatefulEdge();
  }

  @Nullable
  default StatefulEdge asStatefulEdgeOrNull() {
    var entity = asEntityOrNull();
    if (entity == null) {
      return null;
    }

    return entity.asStatefulEdgeOrNull();
  }

  boolean isBlob();

  @Nonnull
  Blob asBlob();

  @Nullable
  Blob asBlobOrNull();

  @Nonnull
  DBRecord asRecord();

  @Nullable
  DBRecord asRecordOrNull();

  boolean isRecord();

  boolean isProjection();

  /**
   * Returns the result as <code>Map</code>. If the result has identity, then the @rid entry is
   * added. If the result is an entity that has a class, then the @class entry is added. If entity
   * is embedded, then the @embedded entry is added.
   */
  @Nonnull
  Map<String, Object> toMap();

  @Nonnull
  String toJSON();

  boolean hasProperty(@Nonnull String varName);

  /**
   * @return Returns session to which given record is bound or <code>null</code> if record is
   * unloaded.
   */
  @Nullable
  DatabaseSession getBoundedToSession();

  /**
   * Detach the result from the session. If result contained a record, it will be converted into
   * record id.
   */
  @Nonnull
  Result detach();

  @Nonnull
  Identifiable asIdentifiable();

  @Nullable
  Identifiable asIdentifiableOrNull();
}
