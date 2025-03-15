package com.jetbrains.youtrack.db.internal.core.record.impl;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.exception.DatabaseException;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.record.Blob;
import com.jetbrains.youtrack.db.api.record.DBRecord;
import com.jetbrains.youtrack.db.api.record.Edge;
import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.record.StatefulEdge;
import com.jetbrains.youtrack.db.api.record.Vertex;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaImmutableClass;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class StatefulEdgeImpl implements EdgeInternal, StatefulEdge, EntityInternal {

  @Nonnull
  protected final EntityImpl entity;
  @Nonnull
  protected final DatabaseSessionInternal session;

  public StatefulEdgeImpl(@Nonnull EntityImpl entity, @Nonnull DatabaseSessionInternal session) {
    this.entity = entity;
    this.session = session;
  }

  @Nullable
  @Override
  public RID getLink(@Nonnull String fieldName) {

    EdgeInternal.checkPropertyName(fieldName);
    return entity.getLink(fieldName);
  }

  @Override
  public @Nonnull Collection<String> getPropertyNames() {
    return EdgeInternal.filterPropertyNames(entity.getPropertyNames());
  }

  @Override
  public int getPropertiesCount() {
    return getPropertyNames().size();
  }

  @Override
  public <RET> RET getProperty(@Nonnull String name) {
    EdgeInternal.checkPropertyName(name);

    return entity.getProperty(name);
  }

  @Nullable
  @Override
  public Entity getEntity(@Nonnull String name) {
    EdgeInternal.checkPropertyName(name);

    return entity.getEntity(name);
  }

  @Nullable
  @Override
  public Result getResult(@Nonnull String name) {
    EdgeInternal.checkPropertyName(name);

    return entity.getResult(name);
  }

  @Nullable
  @Override
  public Blob getBlob(String propertyName) {
    EdgeInternal.checkPropertyName(propertyName);

    return entity.getBlob(propertyName);
  }


  @Override
  public boolean isUnloaded() {

    return entity.isUnloaded();
  }

  @Override
  public boolean exists() {

    return entity.exists();
  }

  @Override
  public <RET> RET getPropertyOnLoadValue(@Nonnull String name) {
    EdgeInternal.checkPropertyName(name);

    return entity.getPropertyOnLoadValue(name);
  }

  @Override
  public void setProperty(@Nonnull String name, @Nullable Object value) {
    EdgeInternal.checkPropertyName(name);

    entity.setProperty(name, value);
  }

  @Override
  public @Nonnull <T> List<T> getOrCreateEmbeddedList(@Nonnull String name) {
    EdgeInternal.checkPropertyName(name);

    return entity.getOrCreateEmbeddedList(name);
  }

  @Nonnull
  @Override
  public <T> List<T> getOrCreateEmbeddedList(@Nonnull String name,
      @Nonnull PropertyType linkedType) {
    return List.of();
  }

  @Nonnull
  @Override
  public <T> List<T> newEmbeddedList(@Nonnull String name) {
    EdgeInternal.checkPropertyName(name);

    return entity.newEmbeddedList(name);
  }

  @Nonnull
  @Override
  public <T> List<T> newEmbeddedList(@Nonnull String name, PropertyType linkedType) {
    return List.of();
  }

  @Nonnull
  @Override
  public <T> List<T> newEmbeddedList(@Nonnull String name, T[] source) {
    EdgeInternal.checkPropertyName(name);
    return entity.newEmbeddedList(name, source);
  }

  @Nonnull
  @Override
  public List<Byte> newEmbeddedList(@Nonnull String name, byte[] source) {
    EdgeInternal.checkPropertyName(name);
    return entity.newEmbeddedList(name, source);

  }

  @Nonnull
  @Override
  public List<Short> newEmbeddedList(@Nonnull String name, short[] source) {
    EdgeInternal.checkPropertyName(name);
    return entity.newEmbeddedList(name, source);

  }

  @Nonnull
  @Override
  public List<Integer> newEmbeddedList(@Nonnull String name, int[] source) {
    EdgeInternal.checkPropertyName(name);
    return entity.newEmbeddedList(name, source);

  }

  @Nonnull
  @Override
  public List<Long> newEmbeddedList(@Nonnull String name, long[] source) {
    EdgeInternal.checkPropertyName(name);
    return entity.newEmbeddedList(name, source);

  }

  @Nonnull
  @Override
  public List<Boolean> newEmbeddedList(@Nonnull String name, boolean[] source) {
    EdgeInternal.checkPropertyName(name);
    return entity.newEmbeddedList(name, source);
  }

  @Nonnull
  @Override
  public List<Float> newEmbeddedList(@Nonnull String name, float[] source) {
    EdgeInternal.checkPropertyName(name);
    return entity.newEmbeddedList(name, source);

  }

  @Nonnull
  @Override
  public List<Double> newEmbeddedList(@Nonnull String name, double[] source) {
    EdgeInternal.checkPropertyName(name);
    return entity.newEmbeddedList(name, source);
  }

  @Override
  public @Nonnull <T> Set<T> getOrCreateEmbeddedSet(@Nonnull String name) {
    EdgeInternal.checkPropertyName(name);
    return entity.getOrCreateEmbeddedSet(name);
  }

  @Nonnull
  @Override
  public <T> Set<T> getOrCreateEmbeddedSet(@Nonnull String name, @Nonnull PropertyType linkedType) {
    return Set.of();
  }

  @Nonnull
  @Override
  public <T> Set<T> newEmbeddedSet(@Nonnull String name) {
    EdgeInternal.checkPropertyName(name);

    return entity.newEmbeddedSet(name);
  }

  @Nonnull
  @Override
  public <T> Set<T> newEmbeddedSet(@Nonnull String name, @Nonnull PropertyType linkedType) {
    return Set.of();
  }

  @Override
  public @Nonnull <T> Map<String, T> getOrCreateEmbeddedMap(@Nonnull String name) {
    EdgeInternal.checkPropertyName(name);

    return entity.getOrCreateEmbeddedMap(name);
  }

  @Nonnull
  @Override
  public <T> Map<String, T> getOrCreateEmbeddedMap(@Nonnull String name,
      @Nonnull PropertyType linkedType) {
    return Map.of();
  }

  @Nonnull
  @Override
  public <T> Map<String, T> newEmbeddedMap(@Nonnull String name) {
    EdgeInternal.checkPropertyName(name);
    return entity.newEmbeddedMap(name);
  }

  @Nonnull
  @Override
  public <T> Map<String, T> newEmbeddedMap(@Nonnull String name, @Nonnull PropertyType linkedType) {
    return Map.of();
  }

  @Override
  public @Nonnull List<Identifiable> getOrCreateLinkList(@Nonnull String name) {
    EdgeInternal.checkPropertyName(name);
    return entity.getOrCreateLinkList(name);
  }

  @Nonnull
  @Override
  public List<Identifiable> newLinkList(@Nonnull String name) {
    EdgeInternal.checkPropertyName(name);
    return entity.newLinkList(name);
  }

  @Nonnull
  @Override
  public Set<Identifiable> getOrCreateLinkSet(@Nonnull String name) {
    EdgeInternal.checkPropertyName(name);

    return entity.getOrCreateLinkSet(name);
  }

  @Nonnull
  @Override
  public Set<Identifiable> newLinkSet(@Nonnull String name) {
    EdgeInternal.checkPropertyName(name);
    return entity.newLinkSet(name);
  }

  @Nonnull
  @Override
  public Map<String, Identifiable> getOrCreateLinkMap(@Nonnull String name) {
    EdgeInternal.checkPropertyName(name);
    return entity.getOrCreateLinkMap(name);
  }

  @Nonnull
  @Override
  public Map<String, Identifiable> newLinkMap(@Nonnull String name) {
    EdgeInternal.checkPropertyName(name);
    return entity.newLinkMap(name);
  }

  @Override
  public boolean hasProperty(final @Nonnull String propertyName) {
    EdgeInternal.checkPropertyName(propertyName);

    return entity.hasProperty(propertyName);
  }

  @Override
  public void setProperty(@Nonnull String propertyName, Object value,
      @Nonnull PropertyType fieldType) {
    EdgeInternal.checkPropertyName(propertyName);

    entity.setProperty(propertyName, value, fieldType);
  }

  @Override
  public void setProperty(@Nonnull String propertyName, @Nullable Object value,
      @Nonnull PropertyType propertyType, @Nonnull PropertyType linkedType) {
    EdgeInternal.checkPropertyName(propertyName);
    entity.setProperty(propertyName, value, propertyType, linkedType);
  }

  @Override
  public <RET> RET removeProperty(@Nonnull String name) {
    EdgeInternal.checkPropertyName(name);

    return entity.removeProperty(name);
  }


  @Override
  public Collection<String> getPropertyNamesInternal(boolean includeSystemProperties,
      boolean checkAccess) {
    return entity.getPropertyNamesInternal(includeSystemProperties, checkAccess);
  }

  public <RET> RET getPropertyInternal(String name) {
    return entity.getPropertyInternal(name);
  }

  @Override
  public <RET> RET getPropertyInternal(String name, boolean lazyLoading) {
    return entity.getPropertyInternal(name, lazyLoading);
  }

  @Override
  public <RET> RET getPropertyOnLoadValueInternal(@Nonnull String name) {
    EdgeInternal.checkPropertyName(name);
    return entity.getPropertyOnLoadValueInternal(name);
  }

  @Override
  public Collection<String> getDirtyPropertiesInternal(boolean includeSystemProperties,
      boolean checkAccess) {
    return entity.getDirtyPropertiesInternal(includeSystemProperties, checkAccess);
  }

  @Override
  public Collection<String> getDirtyPropertiesBetweenCallbacksInternal(boolean includeSystemFields,
      boolean checkAccess) {
    return entity.getDirtyPropertiesBetweenCallbacksInternal(includeSystemFields, checkAccess);
  }

  @Nullable
  @Override
  public RID getLinkPropertyInternal(String name) {
    return entity.getLinkPropertyInternal(name);
  }

  @Nullable
  @Override
  public SchemaImmutableClass getImmutableSchemaClass(@Nonnull DatabaseSessionInternal session) {
    return entity.getImmutableSchemaClass(session);
  }

  @Override
  public void setPropertyInternal(String name, Object value) {
    entity.setPropertyInternal(name, value);
  }

  @Nonnull
  @Override
  public <T> List<T> newEmbeddedList(@Nonnull String name, List<T> source) {
    EdgeInternal.checkPropertyName(name);
    return entity.newEmbeddedList(name, source);
  }

  @Nonnull
  @Override
  public <T> List<T> newEmbeddedList(@Nonnull String name, List<T> source,
      PropertyType linkedType) {
    return List.of();
  }

  @Nonnull
  @Override
  public <T> Set<T> newEmbeddedSet(@Nonnull String name, @Nonnull Set<T> source) {
    EdgeInternal.checkPropertyName(name);
    return entity.newEmbeddedSet(name, source);
  }

  @Nonnull
  @Override
  public <T> Set<T> newEmbeddedSet(@Nonnull String name, Set<T> source,
      @Nonnull PropertyType linkedType) {
    return Set.of();
  }

  @Nonnull
  @Override
  public <T> Map<String, T> newEmbeddedMap(@Nonnull String name, Map<String, T> source) {
    EdgeInternal.checkPropertyName(name);
    return entity.newEmbeddedMap(name, source);
  }

  @Nonnull
  @Override
  public <T> Map<String, T> newEmbeddedMap(@Nonnull String name, Map<String, T> source,
      @Nonnull PropertyType linkedType) {
    return Map.of();
  }

  @Nonnull
  @Override
  public List<Identifiable> newLinkList(@Nonnull String name, List<Identifiable> source) {
    EdgeInternal.checkPropertyName(name);
    return entity.newLinkList(name, source);
  }

  @Nonnull
  @Override
  public Set<Identifiable> newLinkSet(@Nonnull String name, Set<Identifiable> source) {
    EdgeInternal.checkPropertyName(name);
    return entity.newLinkSet(name, source);
  }

  @Nonnull
  @Override
  public Map<String, Identifiable> newLinkMap(@Nonnull String name,
      Map<String, Identifiable> source) {
    EdgeInternal.checkPropertyName(name);
    return entity.newLinkMap(name, source);
  }

  @Override
  public void setPropertyInternal(String name, Object value, PropertyType type) {
    entity.setPropertyInternal(name, value, type);
  }

  @Override
  public void setPropertyInternal(String name, Object value, PropertyType type,
      PropertyType linkedType) {
    entity.setPropertyInternal(name, value, type, linkedType);
  }

  @Override
  public <RET> RET removePropertyInternal(String name) {
    return entity.removePropertyInternal(name);
  }

  @Override
  public boolean isVertex() {
    return false;
  }

  @Override
  public boolean isEdge() {
    return true;
  }

  @Nonnull
  @Override
  public Edge asEdge() {
    return this;
  }

  @Nullable
  @Override
  public Edge asEdgeOrNull() {
    return this;
  }

  @Nonnull
  @Override
  public Vertex asVertex() {
    return null;
  }

  @Override
  public boolean isStatefulEdge() {
    return true;
  }

  @Nonnull
  @Override
  public DBRecord asRecord() {
    return entity;
  }


  @Override
  public boolean isProjection() {
    return false;
  }

  @Override
  public boolean isEmbedded() {
    return false;
  }


  @Nonnull
  @Override
  public RID getIdentity() {
    return entity.getIdentity();
  }

  @Nonnull
  @Override
  public Entity asEntity() {
    return entity;
  }

  @Nonnull
  @Override
  public <T extends DBRecord> T getRecord(@Nonnull DatabaseSession session) {
    //noinspection unchecked
    return (T) entity;
  }

  @Override
  public int compare(Identifiable o1, Identifiable o2) {
    return o1.compareTo(o2);
  }

  @Override
  public int compareTo(@Nonnull Identifiable o) {
    return entity.getIdentity().compareTo(o.getIdentity());
  }

  @Override
  public void clear() {
    entity.clear();
  }

  @Override
  public int getVersion() {
    return entity.getVersion();
  }

  @Override
  public boolean isDirty() {
    return entity.isDirty();
  }

  @Override
  public void updateFromJSON(@Nonnull String iJson) {
    entity.updateFromJSON(iJson);
  }

  @Override
  public @Nonnull String toJSON() {
    return entity.toJSON();
  }

  @Nonnull
  @Override
  public String toJSON(@Nonnull String iFormat) {
    return entity.toJSON(iFormat);
  }

  @Override
  public void updateFromMap(@Nonnull Map<String, ?> map) {
    entity.updateFromMap(map);
  }

  @Override
  public void updateFromResult(@Nonnull Result result) {
    entity.updateFromResult(result);
  }


  @Nonnull
  @Override
  public Map<String, Object> toMap(boolean includeMetadata) {
    return entity.toMap(includeMetadata);
  }

  @Override
  public Collection<String> getDirtyProperties() {
    return EdgeInternal.filterPropertyNames(entity.getDirtyProperties());
  }

  @Override
  public Collection<String> getDirtyPropertiesBetweenCallbacks() {
    return EdgeInternal.filterPropertyNames(entity.getDirtyPropertiesBetweenCallbacks());
  }

  @Override
  public boolean isNotBound(@Nonnull DatabaseSession session) {
    return entity.isNotBound(session);
  }

  @Nullable
  @Override
  public DatabaseSession getBoundedToSession() {
    return session;
  }

  @Override
  public @Nonnull Result detach() {
    return entity.detach();
  }

  @Nonnull
  @Override
  public Identifiable asIdentifiable() {
    return entity;
  }

  @Nullable
  @Override
  public Identifiable asIdentifiableOrNull() {
    return entity;
  }

  @Nullable
  @Override
  public Entity asEntityOrNull() {
    return entity;
  }

  @Nullable
  @Override
  public Blob asBlobOrNull() {
    throw new DatabaseException("Not a blob");
  }

  @Nullable
  @Override
  public Vertex asVertexOrNull() {
    return null;
  }

  @Nullable
  @Override
  public DBRecord asRecordOrNull() {
    return entity;
  }


  @Nullable
  @Override
  public Vertex getFrom() {
    var result = entity.getProperty(DIRECTION_OUT);
    if (!(result instanceof Entity v)) {
      return null;
    }

    if (!v.isVertex()) {
      return null;
    }

    return v.asVertex();
  }

  @Nullable
  @Override
  public Identifiable getFromLink() {
    var result = entity.getLink(DIRECTION_OUT);
    assert result != null;

    var id = result.getIdentity();
    var schema = session.getMetadata().getSchema();

    if (schema.getClassByClusterId(id.getClusterId()).isVertexType()) {
      return id;
    }

    return null;
  }

  @Nullable
  @Override
  public Vertex getTo() {
    var result = entity.getProperty(DIRECTION_IN);
    if (!(result instanceof Entity v)) {
      return null;
    }

    if (!v.isVertex()) {
      return null;
    }

    return v.asVertex();
  }

  @Nullable
  @Override
  public Identifiable getToLink() {
    var result = entity.getLink(DIRECTION_IN);
    assert result != null;

    var id = result.getIdentity();
    var schema = entity.getSession().getMetadata().getSchema();

    if (schema.getClassByClusterId(id.getClusterId()).isVertexType()) {
      return id;
    }

    return null;
  }

  @Override
  public boolean isLightweight() {
    return false;
  }

  public void delete() {
    entity.delete();
  }

  @Nonnull
  @Override
  public SchemaClass getSchemaClass() {
    return entity.getSchemaClass();
  }

  @Nonnull
  @Override
  public String getSchemaClassName() {
    return entity.getSchemaClassName();
  }

  public boolean isLabeled(String[] labels) {
    if (labels == null) {
      return true;
    }
    if (labels.length == 0) {
      return true;
    }
    Set<String> types = new HashSet<>();

    var typeClass = getImmutableSchemaClass(session);

    types.add(typeClass.getName());
    typeClass.getAllSuperClasses().stream()
        .map(SchemaClass::getName)
        .forEach(types::add);
    for (var s : labels) {
      for (var type : types) {
        if (type.equalsIgnoreCase(s)) {
          return true;
        }
      }
    }

    return false;
  }

  @Override
  public boolean equals(Object obj) {
    if (!(obj instanceof Identifiable)) {
      return false;
    }

    var session = entity.getSession();
    if (!(obj instanceof Entity)) {
      obj = ((Identifiable) obj).getRecord(session);
    }

    return entity.equals(((Entity) obj).getRecord(session));
  }

  @Override
  public int hashCode() {
    return entity.hashCode();
  }


  @Nonnull
  @Override
  public Map<String, Object> toMap() {
    return entity.toMap();
  }

  @Nonnull
  @Override
  public StatefulEdge asStatefulEdge() {
    return this;
  }

  @Nullable
  @Override
  public StatefulEdge asStatefulEdgeOrNull() {
    return this;
  }
}

