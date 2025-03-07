package com.jetbrains.youtrack.db.internal.core.sql.executor;

import com.jetbrains.youtrack.db.api.exception.RecordNotFoundException;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.record.Blob;
import com.jetbrains.youtrack.db.api.record.DBRecord;
import com.jetbrains.youtrack.db.api.record.Edge;
import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.record.Vertex;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.record.RecordOperation;
import com.jetbrains.youtrack.db.internal.core.id.ContextualRecordId;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.record.RecordAbstract;
import com.jetbrains.youtrack.db.internal.core.record.RecordInternal;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityInternal;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityInternalUtils;
import java.io.Serializable;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nullable;

/**
 *
 */
public class ResultInternal implements Result {

  protected Map<String, Object> content;
  protected Map<String, Object> temporaryContent;
  protected Map<String, Object> metadata;

  @Nullable
  protected Identifiable identifiable;

  @Nullable
  protected DatabaseSessionInternal session;

  public ResultInternal(@Nullable DatabaseSessionInternal session) {
    content = new LinkedHashMap<>();
    this.session = session;
  }

  public ResultInternal(@Nullable DatabaseSessionInternal session, Identifiable ident) {
    setIdentifiable(ident);
    this.session = session;
  }

  public void setProperty(String name, Object value) {
    assert identifiable == null;
    if (value instanceof Optional) {
      value = ((Optional) value).orElse(null);
    }
    if (content == null) {
      throw new IllegalStateException("Impossible to mutate result set");
    }
    checkType(value);
    if (value instanceof Result && ((Result) value).isEntity()) {
      content.put(name, ((Result) value).getEntity().get());
    } else {
      content.put(name, value);
    }
  }

  @Override
  public boolean isRecord() {
    return identifiable != null;
  }

  @Nullable
  @Override
  public RID getRecordId() {
    if (identifiable == null) {
      return null;
    }

    return identifiable.getIdentity();
  }

  private static void checkType(Object value) {
    if (value == null) {
      return;
    }
    if (PropertyType.isSimpleType(value) || value instanceof Character) {
      return;
    }
    if (value instanceof Identifiable) {
      return;
    }
    if (value instanceof Result) {
      return;
    }
    if (value instanceof Collection || value instanceof Map) {
      return;
    }
    if (value instanceof Serializable) {
      return;
    }
    if(value instanceof Map.Entry<?,?>){
      return;
    }
    throw new IllegalArgumentException(
        "Invalid property value for Result: " + value + " - " + value.getClass().getName());
  }

  public void setTemporaryProperty(String name, Object value) {
    if (temporaryContent == null) {
      temporaryContent = new HashMap<>();
    }
    if (value instanceof Optional) {
      value = ((Optional) value).orElse(null);
    }
    if (value instanceof Result && ((Result) value).isEntity()) {
      temporaryContent.put(name, ((Result) value).getEntity().get());
    } else {
      temporaryContent.put(name, value);
    }
  }

  public Object getTemporaryProperty(String name) {
    if (name == null || temporaryContent == null) {
      return null;
    }
    return temporaryContent.get(name);
  }

  public Set<String> getTemporaryProperties() {
    return temporaryContent == null ? Collections.emptySet() : temporaryContent.keySet();
  }

  public void removeProperty(String name) {
    if (content != null) {
      content.remove(name);
    }
  }

  public <T> T getProperty(String name) {
    loadIdentifiable();

    T result = null;
    if (content != null && content.containsKey(name)) {
      result = (T) wrap(session, content.get(name));
    } else {
      if (isEntity()) {
        result = (T) wrap(session,
            EntityInternalUtils.rawPropertyRead((Entity) identifiable, name));
      }
    }
    if (result instanceof Identifiable && ((Identifiable) result).getIdentity()
        .isPersistent()) {
      result = (T) ((Identifiable) result).getIdentity();
    }
    return result;
  }

  @Override
  public Entity getEntityProperty(String name) {
    loadIdentifiable();

    Object result = null;
    if (content != null && content.containsKey(name)) {
      result = content.get(name);
    } else {
      if (isEntity()) {
        result = EntityInternalUtils.rawPropertyRead((Entity) identifiable, name);
      }
    }

    if (result instanceof Result) {
      result = ((Result) result).getRecord().orElse(null);
    }

    if (result instanceof RID) {
      result = ((RID) result).getRecord();
    }

    return result instanceof Entity ? (Entity) result : null;
  }

  @Override
  public Vertex getVertexProperty(String name) {
    loadIdentifiable();
    Object result = null;
    if (content != null && content.containsKey(name)) {
      result = content.get(name);
    } else {
      if (isEntity()) {
        result = EntityInternalUtils.rawPropertyRead((Entity) identifiable, name);
      }
    }

    if (result instanceof Result) {
      result = ((Result) result).getRecord().orElse(null);
    }

    if (result instanceof RID) {
      result = ((RID) result).getRecord();
    }

    return result instanceof Entity ? ((Entity) result).asVertex().orElse(null) : null;
  }

  @Override
  public Edge getEdgeProperty(String name) {
    loadIdentifiable();
    Object result = null;
    if (content != null && content.containsKey(name)) {
      result = content.get(name);
    } else {
      if (isEntity()) {
        result = EntityInternalUtils.rawPropertyRead((Entity) identifiable, name);
      }
    }

    if (result instanceof Result) {
      result = ((Result) result).getRecord().orElse(null);
    }

    if (result instanceof RID) {
      result = ((RID) result).getRecord();
    }

    return result instanceof Entity ? ((Entity) result).asEdge().orElse(null) : null;
  }

  @Override
  public Blob getBlobProperty(String name) {
    loadIdentifiable();
    Object result = null;
    if (content != null && content.containsKey(name)) {
      result = content.get(name);
    } else {
      if (isEntity()) {
        result = EntityInternalUtils.rawPropertyRead((Entity) identifiable, name);
      }
    }

    if (result instanceof Result) {
      result = ((Result) result).getRecord().orElse(null);
    }

    if (result instanceof RID) {
      result = ((RID) result).getRecord();
    }

    return result instanceof Blob ? (Blob) result : null;
  }

  private static Object wrap(DatabaseSessionInternal session, Object input) {
    if (input instanceof EntityInternal elem
        && !((RecordId) ((Entity) input).getIdentity()).isValid()) {
      ResultInternal result = new ResultInternal(session);
      for (String prop : elem.getPropertyNamesInternal()) {
        result.setProperty(prop, elem.getPropertyInternal(prop));
      }
      elem.getSchemaType().ifPresent(x -> result.setProperty("@class", x.getName()));
      return result;
    } else {
      if (isEmbeddedList(input)) {
        return ((List) input).stream().map(in -> wrap(session, in)).collect(Collectors.toList());
      } else {
        if (isEmbeddedSet(input)) {
          Stream mappedSet = ((Set) input).stream().map(in -> wrap(session, in));
          if (input instanceof LinkedHashSet<?>) {
            return mappedSet.collect(Collectors.toCollection(LinkedHashSet::new));
          } else {
            return mappedSet.collect(Collectors.toSet());
          }
        } else {
          if (isEmbeddedMap(input)) {
            Map result = new HashMap();
            for (Map.Entry<Object, Object> o : ((Map<Object, Object>) input).entrySet()) {
              result.put(o.getKey(), wrap(session, o.getValue()));
            }
            return result;
          }
        }
      }
    }
    return input;
  }

  private static boolean isEmbeddedSet(Object input) {
    return input instanceof Set && PropertyType.getTypeByValue(input) == PropertyType.EMBEDDEDSET;
  }

  private static boolean isEmbeddedMap(Object input) {
    return input instanceof Map && PropertyType.getTypeByValue(input) == PropertyType.EMBEDDEDMAP;
  }

  private static boolean isEmbeddedList(Object input) {
    return input instanceof List && PropertyType.getTypeByValue(input) == PropertyType.EMBEDDEDLIST;
  }

  public Collection<String> getPropertyNames() {
    loadIdentifiable();
    if (isEntity()) {
      return ((Entity) identifiable).getPropertyNames();
    } else {
      if (content != null) {
        return new LinkedHashSet<>(content.keySet());
      } else {
        return Collections.emptySet();
      }
    }
  }

  public boolean hasProperty(String propName) {
    loadIdentifiable();
    if (isEntity() && ((Entity) identifiable).hasProperty(propName)) {
      return true;
    }
    if (content != null) {
      return content.containsKey(propName);
    }
    return false;
  }

  @Override
  public boolean isEntity() {
    if (identifiable == null) {
      return false;
    }

    if (identifiable instanceof Entity) {
      return true;
    }

    try {
      identifiable = identifiable.getRecord();
    } catch (RecordNotFoundException e) {
      identifiable = null;
    }

    return identifiable instanceof Entity;
  }

  public Optional<Entity> getEntity() {
    loadIdentifiable();
    if (isEntity()) {
      return Optional.ofNullable((Entity) identifiable);
    }
    return Optional.empty();
  }

  @Override
  public Entity asEntity() {
    loadIdentifiable();
    if (isEntity()) {
      return (Entity) identifiable;
    }

    return null;
  }

  @Override
  public Entity toEntity() {
    if (isEntity()) {
      return getEntity().get();
    }
    EntityImpl entity = new EntityImpl();
    for (String s : getPropertyNames()) {
      if (s == null) {
        continue;
      } else {
        if (s.equalsIgnoreCase("@rid")) {
          Object newRid = getProperty(s);
          if (newRid instanceof Identifiable) {
            newRid = ((Identifiable) newRid).getIdentity();
          } else {
            continue;
          }
          RecordId oldId = entity.getIdentity();
          oldId.setClusterId(((RID) newRid).getClusterId());
          oldId.setClusterPosition(((RID) newRid).getClusterPosition());
        } else {
          if (s.equalsIgnoreCase("@version")) {
            Object v = getProperty(s);
            if (v instanceof Number) {
              RecordInternal.setVersion(entity, ((Number) v).intValue());
            } else {
              continue;
            }
          } else {
            if (s.equalsIgnoreCase("@class")) {
              entity.setClassName(getProperty(s));
            } else {
              entity.setProperty(s, convertToElement(getProperty(s)));
            }
          }
        }
      }
    }
    return entity;
  }

  @Override
  public Optional<RID> getIdentity() {
    if (identifiable != null) {
      return Optional.of(identifiable.getIdentity());
    }
    return Optional.empty();
  }

  @Override
  public boolean isProjection() {
    return this.identifiable == null;
  }

  @Override
  public Optional<DBRecord> getRecord() {
    loadIdentifiable();
    return Optional.ofNullable((DBRecord) this.identifiable);
  }

  @Override
  public boolean isBlob() {
    loadIdentifiable();
    return this.identifiable instanceof Blob;
  }

  @Override
  public Optional<Blob> getBlob() {
    loadIdentifiable();

    if (isBlob()) {
      return Optional.ofNullable((Blob) this.identifiable);
    }
    return Optional.empty();
  }

  @Override
  public Object getMetadata(String key) {
    if (key == null) {
      return null;
    }
    return metadata == null ? null : metadata.get(key);
  }

  public void setMetadata(String key, Object value) {
    if (key == null) {
      return;
    }
    checkType(value);
    if (metadata == null) {
      metadata = new HashMap<>();
    }
    metadata.put(key, value);
  }

  public void clearMetadata() {
    metadata = null;
  }

  public void removeMetadata(String key) {
    if (key == null || metadata == null) {
      return;
    }
    metadata.remove(key);
  }

  public void addMetadata(Map<String, Object> values) {
    if (values == null) {
      return;
    }
    if (this.metadata == null) {
      this.metadata = new HashMap<>();
    }
    this.metadata.putAll(values);
  }

  @Override
  public Set<String> getMetadataKeys() {
    return metadata == null ? Collections.emptySet() : metadata.keySet();
  }

  private Object convertToElement(Object property) {
    if (property instanceof Result) {
      return ((Result) property).toEntity();
    }
    if (property instanceof List) {
      return ((List) property).stream().map(x -> convertToElement(x)).collect(Collectors.toList());
    }

    if (property instanceof Set) {
      return ((Set) property).stream().map(x -> convertToElement(x)).collect(Collectors.toSet());
    }

    if (property instanceof Map) {
      Map<Object, Object> result = new HashMap<>();
      Map<Object, Object> prop = ((Map) property);
      for (Map.Entry<Object, Object> o : prop.entrySet()) {
        result.put(o.getKey(), convertToElement(o.getValue()));
      }
    }

    return property;
  }

  public void loadIdentifiable() {
    if (identifiable == null) {
      return;
    }

    if (identifiable instanceof Entity elem) {
      if (elem.isUnloaded()) {
        try {
          if (session == null) {
            throw new IllegalStateException("There is no active session to load entity");
          }

          identifiable = session.bindToSession(elem);
        } catch (RecordNotFoundException rnf) {
          identifiable = null;
        }
      }

      return;
    }

    if (identifiable instanceof ContextualRecordId) {
      this.addMetadata(((ContextualRecordId) identifiable).getContext());
    }

    try {
      if (session == null) {
        throw new IllegalStateException("There is no active session to load entity");
      }

      this.identifiable = session.load(identifiable.getIdentity());
    } catch (RecordNotFoundException rnf) {
      identifiable = null;
    }
  }

  public void setIdentifiable(Identifiable identifiable) {
    this.identifiable = identifiable;
    this.content = null;
  }

  @Override
  public String toString() {
    if (identifiable != null) {
      return identifiable.toString();
    }
    return "{\n"
        + content.entrySet().stream()
        .map(x -> x.getKey() + ": " + x.getValue())
        .reduce("", (a, b) -> a + b + "\n")
        + "}\n";
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (!(obj instanceof ResultInternal resultObj)) {
      return false;
    }
    if (identifiable != null) {
      if (!resultObj.getEntity().isPresent()) {
        return false;
      }
      return identifiable.equals(resultObj.getEntity().get());
    } else {
      if (resultObj.getEntity().isPresent()) {
        return false;
      }
      if (content != null) {
        return this.content.equals(resultObj.content);
      } else {
        return resultObj.content == null;
      }
    }
  }

  @Override
  public int hashCode() {
    if (identifiable != null) {
      return identifiable.hashCode();
    }
    if (content != null) {
      return content.hashCode();
    } else {
      return super.hashCode();
    }
  }


  public void bindToCache(DatabaseSessionInternal db) {
    if (identifiable instanceof RecordAbstract record) {
      var identity = record.getIdentity();
      var tx = db.getTransaction();

      if (tx.isActive()) {
        var recordEntry = tx.getRecordEntry(identity);
        if (recordEntry != null) {
          if (recordEntry.type == RecordOperation.DELETED) {
            identifiable = identity;
            return;
          }

          identifiable = recordEntry.record;
          return;
        }
      }

      RecordAbstract cached = db.getLocalCache().findRecord(identity);

      if (cached == record) {
        return;
      }

      if (cached != null) {
        if (!cached.isDirty()) {
          cached.fromStream(record.toStream());
          cached.setIdentity(record.getIdentity());
          cached.setVersion(record.getVersion());

          assert !cached.isDirty();
        }
        identifiable = cached;
      } else {
        if (!identity.isPersistent()) {
          return;
        }

        db.getLocalCache().updateRecord(record);
      }
    }
  }
}
