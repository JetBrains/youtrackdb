package com.jetbrains.youtrack.db.api.query;

import com.jetbrains.youtrack.db.api.common.query.BasicResult;
import com.jetbrains.youtrack.db.api.common.query.collection.embedded.EmbeddedSet;
import com.jetbrains.youtrack.db.api.common.query.collection.links.LinkMap;
import com.jetbrains.youtrack.db.api.common.query.collection.links.LinkSet;
import com.jetbrains.youtrack.db.api.record.Blob;
import com.jetbrains.youtrack.db.api.record.DBRecord;
import com.jetbrains.youtrack.db.api.record.Edge;
import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.record.Relation;
import com.jetbrains.youtrack.db.api.record.StatefulEdge;
import com.jetbrains.youtrack.db.api.record.Vertex;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.exception.DatabaseException;
import com.jetbrains.youtrack.db.api.common.query.collection.embedded.EmbeddedList;
import com.jetbrains.youtrack.db.api.common.query.collection.links.LinkList;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.PropertyTypeInternal;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

@SuppressWarnings("unused")
public interface Result extends BasicResult {
  /**
   * Either loads the property value as an {@link Entity} if it is a {@link PropertyType#LINK} type
   * or returns embedded entity.
   *
   * @param name the property name
   * @return the property value. Null if the property is not defined.
   * @throws DatabaseException if the property is not an Entity.
   */
  @Nullable
  Entity getEntity(@Nonnull String name);

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
  Blob getBlob(@Nonnull String name);

  @Nullable
  @Override
  Result getResult(@Nonnull String name);

  @Nullable
  default <T> EmbeddedList<T> getEmbeddedList(@Nonnull String name) {
    if (isEntity()) {
      return asEntity().getEmbeddedList(name);
    }

    var value = getProperty(name);
    if (value == null) {
      return null;
    }

    if (value instanceof EmbeddedList<?> embeddedList) {
      //noinspection unchecked
      return (EmbeddedList<T>) embeddedList;
    }

    throw new DatabaseException(
        "Property " + name + " is not a embedded list type, but " + value.getClass().getName());
  }

  @Nullable
  default LinkList getLinkList(@Nonnull String name) {
    if (isEntity()) {
      return asEntity().getLinkList(name);
    }

    var value = getProperty(name);

    if (value == null) {
      return null;
    }

    if (value instanceof LinkList list) {
      return list;
    }

    throw new DatabaseException(
        "Property " + name + " is not a link list type, but " + value.getClass().getName());
  }


  @Nullable
  default <T> EmbeddedSet<T> getEmbeddedSet(@Nonnull String name) {
    if (isEntity()) {
      return asEntity().getEmbeddedSet(name);
    }

    var value = getProperty(name);
    if (value == null) {
      return null;
    }

    if (value instanceof EmbeddedSet<?> set) {
      //noinspection unchecked
      return (EmbeddedSet<T>) set;
    }

    throw new DatabaseException(
        "Property " + name + " is not a embedded set type, but " + value.getClass().getName());
  }

  @Nullable
  default LinkSet getLinkSet(@Nonnull String name) {
    if (isEntity()) {
      return asEntity().getLinkSet(name);
    }

    var value = getProperty(name);
    if (value == null) {
      return null;
    }

    if (value instanceof LinkSet linkSet) {
      return linkSet;
    }

    throw new DatabaseException(
        "Property " + name + " is not a link set type, but " + value.getClass().getName());
  }

  @Nullable
  default   <T> Map<String, T> getEmbeddedMap(@Nonnull String name) {
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
  default LinkMap getLinkMap(@Nonnull String name) {
    if (isEntity()) {
      return asEntity().getLinkMap(name);
    }

    var value = getProperty(name);
    if (value == null) {
      return null;
    }

    if (value instanceof LinkMap linkMap) {
      return linkMap;
    }

    throw new DatabaseException(
        "Property " + name + " is not a link map type, but " + value.getClass().getName());
  }


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

  boolean isRelation();

  Relation<?> asRelation();

  @Nullable
  Relation<?> asRelationOrNull();

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

  @Nonnull
  Identifiable asIdentifiable();

  @Nullable
  Identifiable asIdentifiableOrNull();

  @Nonnull
  @Override
  Result detach();
}
