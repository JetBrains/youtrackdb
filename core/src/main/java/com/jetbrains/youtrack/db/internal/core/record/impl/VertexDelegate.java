/*
 *
 *
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *
 *
 */
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 *
 */
public final class VertexDelegate implements VertexInternal {

  private final EntityImpl entity;

  public VertexDelegate(EntityImpl entry) {
    this.entity = entry;
  }

  @Override
  public void delete() {
    entity.delete();
  }

  @Override
  public Vertex asVertex() {
    return this;
  }

  @Override
  public boolean isEdge() {
    return false;
  }

  @Nonnull
  @Override
  public Edge castToEdge() {
    throw new DatabaseException("Not an edge");
  }

  @Nullable
  @Override
  public Edge asEdge() {
    return null;
  }

  @Override
  @Nullable
  public StatefulEdge asStatefulEdge() {
    return null;
  }

  @Override
  public boolean isBlob() {
    return false;
  }

  @Nonnull
  @Override
  public Blob castToBlob() {
    throw new DatabaseException("Not a blob");
  }

  @Nullable
  @Override
  public Blob asBlob() {
    return null;
  }

  @Nonnull
  @Override
  public DBRecord castToRecord() {
    return entity;
  }

  @Nullable
  @Override
  public DBRecord asRecord() {
    return entity;
  }

  @Override
  public boolean isRecord() {
    return true;
  }

  @Override
  public boolean isProjection() {
    return false;
  }

  @Override
  public boolean isVertex() {
    return true;
  }

  @Override
  public boolean isStatefulEdge() {
    return false;
  }

  @Nullable
  @Override
  public SchemaClass getSchemaClass() {
    return entity.getSchemaClass();
  }

  @Nullable
  @Override
  public String getSchemaClassName() {
    return entity.getSchemaClassName();
  }

  @Nonnull
  @SuppressWarnings("unchecked")
  @Override
  public EntityImpl getRecord(@Nonnull DatabaseSession session) {
    return entity;
  }

  @Override
  public int compareTo(@Nonnull Identifiable o) {
    return entity.compareTo(o);
  }

  @Override
  public int compare(Identifiable o1, Identifiable o2) {
    return entity.compare(o1, o2);
  }

  @Override
  public boolean equals(Object obj) {
    if (!(obj instanceof Identifiable)) {
      return false;
    }
    if (!(obj instanceof Entity)) {
      obj = ((Identifiable) obj).getRecordSilently(entity.getSession());
    }

    if (obj == null) {
      return false;
    }

    return entity.equals(((Entity) obj).getRecordSilently(entity.getSession()));
  }

  @Override
  public @Nonnull Collection<String> getPropertyNames() {
    return VertexInternal.filterPropertyNames(getPropertyNamesInternal());
  }


  @Override
  public int hashCode() {
    return entity.hashCode();
  }

  @Override
  public void clear() {
    entity.clear();
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

  @Override
  public boolean isEntity() {
    return true;
  }

  @Nonnull
  @Override
  public Entity castToEntity() {
    return entity;
  }

  @Nullable
  @Override
  public Entity asEntity() {
    return entity;
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
  public String toString() {
    if (entity != null) {
      return entity.toString();
    }
    return super.toString();
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
  public Map<String, Object> toMap() {
    return entity.toMap();
  }

  @Nonnull
  @Override
  public Map<String, Object> toMap(boolean includeMetadata) {
    return entity.toMap(includeMetadata);
  }

  @Override
  public DatabaseSession getBoundedToSession() {
    return entity.getBoundedToSession();
  }

  @Nonnull
  @Override
  public Result detach() {
    return entity.detach();
  }

  @Nonnull
  @Override
  public Identifiable castToIdentifiable() {
    return entity;
  }

  @Nullable
  @Override
  public Identifiable asIdentifiable() {
    return entity;
  }

  @Nullable
  @Override
  public SchemaImmutableClass getImmutableSchemaClass(@Nonnull DatabaseSessionInternal session) {
    return entity.getImmutableSchemaClass(session);
  }

  @Override
  public Collection<String> getDirtyProperties() {
    return VertexInternal.filterPropertyNames(entity.getDirtyProperties());
  }

  @Nonnull
  @Override
  public <T> List<T> newEmbeddedList(@Nonnull String name, List<T> source) {
    VertexInternal.checkPropertyName(name);
    return entity.newEmbeddedList(name, source);
  }

  @Override
  public <T> Set<T> newEmbeddedSet(@Nonnull String name, Set<T> source) {
    VertexInternal.checkPropertyName(name);
    return entity.newEmbeddedSet(name, source);
  }

  @Override
  public <T> Map<String, T> newEmbeddedMap(@Nonnull String name, Map<String, T> source) {
    VertexInternal.checkPropertyName(name);
    return entity.newEmbeddedMap(name, source);
  }

  @Override
  public List<Identifiable> newLinkList(@Nonnull String name, List<Identifiable> source) {
    VertexInternal.checkPropertyName(name);
    return entity.newLinkList(name, source);
  }

  @Override
  public Set<Identifiable> newLinkSet(@Nonnull String name, Set<Identifiable> source) {
    VertexInternal.checkPropertyName(name);
    return entity.newLinkSet(name, source);
  }

  @Override
  public Map<String, Identifiable> newLinkMap(@Nonnull String name,
      Map<String, Identifiable> source) {
    VertexInternal.checkPropertyName(name);
    return entity.newLinkMap(name, source);
  }

  @Override
  public <RET> RET getProperty(@Nonnull String name) {
    VertexInternal.checkPropertyName(name);
    return entity.getProperty(name);
  }

  @Override
  public @Nonnull <T> List<T> getOrCreateEmbeddedList(@Nonnull String name) {
    VertexInternal.checkPropertyName(name);

    return entity.getOrCreateEmbeddedList(name);
  }

  @Nonnull
  @Override
  public <T> List<T> newEmbeddedList(@Nonnull String name) {
    VertexInternal.checkPropertyName(name);

    return entity.newEmbeddedList(name);
  }

  @Nonnull
  @Override
  public <T> List<T> newEmbeddedList(@Nonnull String name, T[] source) {
    VertexInternal.checkPropertyName(name);
    return entity.newEmbeddedList(name, source);
  }

  @Nonnull
  @Override
  public List<Byte> newEmbeddedList(@Nonnull String name, byte[] source) {
    VertexInternal.checkPropertyName(name);
    return entity.newEmbeddedList(name, source);
  }

  @Nonnull
  @Override
  public List<Short> newEmbeddedList(@Nonnull String name, short[] source) {
    VertexInternal.checkPropertyName(name);
    return entity.newEmbeddedList(name, source);
  }

  @Nonnull
  @Override
  public List<Integer> newEmbeddedList(@Nonnull String name, int[] source) {
    VertexInternal.checkPropertyName(name);
    return entity.newEmbeddedList(name, source);
  }

  @Nonnull
  @Override
  public List<Long> newEmbeddedList(@Nonnull String name, long[] source) {
    VertexInternal.checkPropertyName(name);
    return entity.newEmbeddedList(name, source);
  }

  @Nonnull
  @Override
  public List<Boolean> newEmbeddedList(@Nonnull String name, boolean[] source) {
    VertexInternal.checkPropertyName(name);
    return entity.newEmbeddedList(name, source);
  }

  @Nonnull
  @Override
  public List<Float> newEmbeddedList(@Nonnull String name, float[] source) {
    VertexInternal.checkPropertyName(name);
    return entity.newEmbeddedList(name, source);
  }

  @Nonnull
  @Override
  public List<Double> newEmbeddedList(@Nonnull String name, double[] source) {
    VertexInternal.checkPropertyName(name);
    return entity.newEmbeddedList(name, source);
  }

  @Override
  public @Nonnull <T> Set<T> getOrCreateEmbeddedSet(@Nonnull String name) {
    VertexInternal.checkPropertyName(name);

    return entity.getOrCreateEmbeddedSet(name);
  }

  @Nonnull
  @Override
  public <T> Set<T> newEmbeddedSet(@Nonnull String name) {
    VertexInternal.checkPropertyName(name);

    return entity.newEmbeddedSet(name);
  }

  @Override
  public @Nonnull <T> Map<String, T> getOrCreateEmbeddedMap(@Nonnull String name) {
    VertexInternal.checkPropertyName(name);

    return entity.getOrCreateEmbeddedMap(name);
  }

  @Nonnull
  @Override
  public <T> Map<String, T> newEmbeddedMap(@Nonnull String name) {
    VertexInternal.checkPropertyName(name);

    return entity.newEmbeddedMap(name);
  }

  @Override
  public @Nonnull List<Identifiable> getOrCreateLinkList(@Nonnull String name) {
    VertexInternal.checkPropertyName(name);

    return entity.getOrCreateLinkList(name);
  }

  @Nonnull
  @Override
  public List<Identifiable> newLinkList(@Nonnull String name) {
    VertexInternal.checkPropertyName(name);

    return entity.newLinkList(name);
  }

  @Nonnull
  @Override
  public Set<Identifiable> getOrCreateLinkSet(@Nonnull String name) {
    VertexInternal.checkPropertyName(name);

    return entity.getOrCreateLinkSet(name);
  }

  @Nonnull
  @Override
  public Set<Identifiable> newLinkSet(@Nonnull String name) {
    VertexInternal.checkPropertyName(name);

    return entity.newLinkSet(name);
  }

  @Nonnull
  @Override
  public Map<String, Identifiable> getOrCreateLinkMap(@Nonnull String name) {
    VertexInternal.checkPropertyName(name);

    return entity.getOrCreateLinkMap(name);
  }

  @Nonnull
  @Override
  public Map<String, Identifiable> newLinkMap(@Nonnull String name) {
    VertexInternal.checkPropertyName(name);

    return entity.newLinkMap(name);
  }

  @Nullable
  @Override
  public Entity getEntity(@Nonnull String name) {
    VertexInternal.checkPropertyName(name);
    return entity.getEntity(name);
  }

  @Nullable
  @Override
  public Result getResult(@Nonnull String name) {
    VertexInternal.checkPropertyName(name);
    return entity.getResult(name);
  }

  @Nullable
  @Override
  public Blob getBlob(String propertyName) {
    VertexInternal.checkPropertyName(propertyName);

    return entity.getBlob(propertyName);
  }

  @Override
  public <RET> RET getPropertyInternal(String name, boolean lazyLoading) {
    return entity.getPropertyInternal(name, lazyLoading);
  }

  @Override
  public <RET> RET getPropertyInternal(String name) {
    return entity.getPropertyInternal(name);
  }

  @Override
  public <RET> RET getPropertyOnLoadValue(@Nonnull String name) {
    return entity.getPropertyOnLoadValue(name);
  }

  @Nullable
  @Override
  public RID getLinkPropertyInternal(String name) {
    return entity.getLinkPropertyInternal(name);
  }

  @Nullable
  @Override
  public RID getLink(@Nonnull String name) {
    VertexInternal.checkPropertyName(name);

    return entity.getLink(name);
  }


  @Override
  public void setProperty(@Nonnull String name, @Nullable Object value) {
    VertexInternal.checkPropertyName(name);

    entity.setProperty(name, value);
  }

  @Override
  public void setPropertyInternal(String name, Object value) {
    entity.setPropertyInternal(name, value);
  }

  @Override
  public boolean hasProperty(final @Nonnull String propertyName) {
    VertexInternal.checkPropertyName(propertyName);

    return entity.hasProperty(propertyName);
  }

  @Override
  public void setProperty(@Nonnull String propertyName, Object value,
      @Nonnull PropertyType fieldType) {
    VertexInternal.checkPropertyName(propertyName);

    entity.setProperty(propertyName, value, fieldType);
  }

  @Override
  public void setPropertyInternal(String name, Object value, PropertyType type) {
    entity.setPropertyInternal(name, value, type);
  }

  @Override
  public <RET> RET removeProperty(@Nonnull String name) {
    VertexInternal.checkPropertyName(name);

    return entity.removePropertyInternal(name);
  }

  @Override
  public <RET> RET removePropertyInternal(String name) {
    return entity.removePropertyInternal(name);
  }

  @Override
  public boolean isUnloaded() {
    return entity.isUnloaded();
  }

  public boolean isNotBound(@Nonnull DatabaseSession session) {
    return entity.isNotBound(session);
  }

  @Override
  public Collection<String> getPropertyNamesInternal() {
    return entity.getPropertyNamesInternal();
  }

  @Override
  public boolean exists() {
    return entity.exists();
  }

  @Override
  public EntityImpl getBaseEntity() {
    return entity;
  }
}
