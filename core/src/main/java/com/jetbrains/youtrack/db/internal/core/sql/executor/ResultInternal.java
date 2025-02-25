package com.jetbrains.youtrack.db.internal.core.sql.executor;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.exception.DatabaseException;
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
import com.jetbrains.youtrack.db.internal.common.io.IOUtils;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.record.LinkList;
import com.jetbrains.youtrack.db.internal.core.db.record.LinkMap;
import com.jetbrains.youtrack.db.internal.core.db.record.LinkSet;
import com.jetbrains.youtrack.db.internal.core.db.record.RecordOperation;
import com.jetbrains.youtrack.db.internal.core.db.record.ridbag.RidBag;
import com.jetbrains.youtrack.db.internal.core.id.ContextualRecordId;
import com.jetbrains.youtrack.db.internal.core.record.RecordAbstract;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityInternalUtils;
import com.jetbrains.youtrack.db.internal.core.util.DateHelper;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


public class ResultInternal implements Result {

  protected Map<String, Object> content;
  protected Map<String, Object> temporaryContent;
  protected Map<String, Object> metadata;

  @Nullable
  protected Identifiable identifiable;
  @Nullable
  protected DatabaseSessionInternal session;
  @Nullable
  protected Edge lightweightEdge;

  public ResultInternal(@Nullable DatabaseSessionInternal session) {
    content = new LinkedHashMap<>();
    this.session = session;
  }

  public ResultInternal(@Nullable DatabaseSessionInternal session,
      @Nonnull Map<String, ?> data) {
    content = new LinkedHashMap<>();
    this.session = session;

    for (var entry : data.entrySet()) {
      setProperty(entry.getKey(), entry.getValue());
    }
  }

  public ResultInternal(@Nullable DatabaseSessionInternal session, Identifiable ident) {
    setIdentifiable(ident);
    this.session = session;
  }

  public ResultInternal(@Nullable DatabaseSessionInternal session, Edge edge) {
    this.session = session;
    if (edge.isLightweight()) {
      lightweightEdge = edge;
    } else {
      setIdentifiable(edge.castToStatefulEdge());
    }
  }

  public static Object toMapValue(Object value, boolean includeEntityMetadata) {
    return switch (value) {
      case null -> null;

      case Edge edge -> {
        if (edge.isLightweight()) {
          yield edge.toMap();
        } else {
          yield edge.castToStatefulEdge().getIdentity();
        }
      }
      case Blob blob -> blob.toStream();

      case Entity entity -> {
        if (entity.isEmbedded()) {
          yield entity.toMap(includeEntityMetadata);
        } else {
          yield entity.getIdentity();
        }
      }

      case DBRecord record -> {
        yield record.getIdentity();
      }

      case Result result -> result.toMap();

      case LinkList linkList -> {
        List<RID> list = new ArrayList<>(linkList.size());
        for (var item : linkList) {
          list.add(item.getIdentity());
        }
        yield list;
      }

      case LinkSet linkSet -> {
        Set<RID> set = new HashSet<>(linkSet.size());
        for (var item : linkSet) {
          set.add(item.getIdentity());
        }
        yield set;
      }
      case LinkMap linkMap -> {
        Map<Object, RID> map = new HashMap<>(linkMap.size());
        for (var entry : linkMap.entrySet()) {
          map.put(entry.getKey(), entry.getValue().getIdentity());
        }
        yield map;
      }
      case RidBag ridBag -> {
        List<RID> list = new ArrayList<>(ridBag.size());
        for (var rid : ridBag) {
          list.add(rid);
        }
        yield list;
      }
      case List<?> trackedList -> {
        List<Object> list = new ArrayList<>(trackedList.size());
        for (var item : trackedList) {
          list.add(toMapValue(item, true));
        }
        yield list;
      }
      case Set<?> trackedSet -> {
        Set<Object> set = new HashSet<>(trackedSet.size());
        for (var item : trackedSet) {
          set.add(toMapValue(item, true));
        }
        yield set;
      }

      case Map<?, ?> trackedMap -> {
        Map<Object, Object> map = new HashMap<>(trackedMap.size());
        for (var entry : trackedMap.entrySet()) {
          map.put(entry.getKey(), toMapValue(entry.getValue(), true));
        }
        yield map;
      }

      default -> {
        if (PropertyType.getTypeByValue(value) == null) {
          throw new IllegalArgumentException(
              "Unexpected property value :" + value);
        }

        yield value;
      }
    };
  }

  public void setProperty(String name, Object value) {
    assert session == null || session.assertIfNotActive();

    if (content == null) {
      if (identifiable != null) {
        throw new IllegalStateException("Impossible to mutate result set containing entity");
      }
      if (lightweightEdge != null) {
        throw new IllegalStateException(
            "Impossible to mutate result set containing lightweight edge");
      }
      throw new IllegalStateException("Impossible to mutate result set");
    }

    value = convertPropertyValue(value);
    content.put(name, value);
  }

  @Override
  public boolean isRecord() {
    assert session == null || session.assertIfNotActive();
    return identifiable != null;
  }

  private Object convertPropertyValue(Object value) {
    if (value == null) {
      return value;
    }

    if (PropertyType.isSingleValueType(value)) {
      return value;
    }

    switch (value) {
      case RidBag ridBag -> {
        var list = new ArrayList<RID>(ridBag.size());
        for (var rid : ridBag) {
          list.add(rid);
        }

        return list;
      }
      case Blob blob -> {
        if (!blob.getIdentity().isPersistent()) {
          return blob.toStream();
        }
        return blob.getIdentity();
      }
      case Entity entity -> {
        if (entity.isEmbedded() || !entity.getIdentity().isPersistent()) {
          return entity.detach();
        }
        return entity.getIdentity();
      }

      case Identifiable id -> {
        var res = id.getIdentity();
        if (session != null) {
          res = session.refreshRid(res);
        }

        return res;
      }
      case Result result -> {
        if (result.isEntity()) {
          return convertPropertyValue(result.castToEntity());
        } else if (result.isBlob()) {
          return convertPropertyValue(result.castToBlob());
        }
        if (result.isEdge() && !result.isStatefulEdge()) {
          throw new IllegalStateException("Lightweight edges are not supported in properties");
        }

        return result;
      }
      case List<?> collection -> {
        var listCopy = new ArrayList<>(collection.size());
        var allIdentifiable = false;

        for (var o : collection) {
          var res = convertPropertyValue(o);

          if (res instanceof Identifiable) {
            allIdentifiable = true;
          } else if (allIdentifiable) {
            throw new IllegalArgumentException(
                "Invalid property value, if list contains identifiables, it should contain only them");
          }

          listCopy.add(res);
        }

        return listCopy;
      }
      case Set<?> set -> {
        var setCopy = new HashSet<>(set.size());
        var allIdentifiable = false;

        for (var o : set) {
          var res = convertPropertyValue(o);

          if (res instanceof Identifiable) {
            allIdentifiable = true;
          } else if (allIdentifiable) {
            throw new IllegalArgumentException(
                "Invalid property value, if set contains identifiables, it should contain only them");
          }

          setCopy.add(res);
        }

        return setCopy;
      }

      case Map<?, ?> map -> {
        var mapCopy = new HashMap<String, Object>(map.size());
        var allIdentifiable = false;

        for (var entry : map.entrySet()) {
          var key = entry.getKey();

          if (!(key instanceof String stringKey)) {
            throw new IllegalArgumentException(
                "Invalid property value, only maps with key types of String are supported : " +
                    key);
          }

          var res = convertPropertyValue(entry.getValue());
          if (res instanceof Identifiable) {
            allIdentifiable = true;
          } else if (allIdentifiable) {
            throw new IllegalArgumentException(
                "Invalid property value, if map contains identifiables, it should contain only them");
          }

          mapCopy.put(stringKey, res);
        }

        return mapCopy;
      }

      default -> {
        throw new IllegalArgumentException(
            "Invalid property value for Result: " + value + " - " + value.getClass().getName());
      }
    }
  }


  public void setTemporaryProperty(String name, Object value) {
    if (temporaryContent == null) {
      temporaryContent = new HashMap<>();
    }

    if (value instanceof Result && ((Result) value).isEntity()) {
      temporaryContent.put(name, ((Result) value).castToEntity());
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
    assert session == null || session.assertIfNotActive();

    if (content != null) {
      content.remove(name);
    }
  }

  public <T> T getProperty(@Nonnull String name) {
    assert session == null || session.assertIfNotActive();

    T result = null;
    if (content != null && content.containsKey(name)) {
      //noinspection unchecked
      result = (T) content.get(name);
    } else {
      if (isEntity()) {
        result = EntityInternalUtils.rawPropertyRead(castToEntity(), name);
      }
    }

    return result;
  }

  @Override
  public Entity getEntity(@Nonnull String name) {
    assert session == null || session.assertIfNotActive();
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
      result = ((Result) result).castToRecord();
    }

    if (result instanceof RID) {
      assert session != null && session.assertIfNotActive();
      result = ((RID) result).getRecord(session);
    }

    return result instanceof Entity ? (Entity) result : null;
  }

  @Override
  public Vertex getVertex(@Nonnull String name) {
    assert session == null || session.assertIfNotActive();

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
      result = ((Result) result).castToRecord();
    }

    if (result instanceof RID) {
      assert session != null && session.assertIfNotActive();
      result = ((RID) result).getRecord(session);
    }

    if (result instanceof Entity entity) {
      return entity.castToVertex();
    }

    throw new IllegalStateException("Result is not a vertex");
  }

  @Override
  public Edge getEdge(@Nonnull String name) {
    assert session == null || session.assertIfNotActive();

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
      result = ((Result) result).castToRecord();
    }

    if (result instanceof RID) {
      assert session != null && session.assertIfNotActive();
      result = ((RID) result).getRecord(session);
    }

    if (result instanceof Entity entity) {
      return entity.castToStatefulEdge();
    }

    throw new IllegalStateException("Result is not an edge");
  }

  @Override
  public Blob getBlob(String name) {
    assert session == null || session.assertIfNotActive();
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
      result = ((Result) result).castToRecord();
    }

    if (result instanceof RID) {
      assert session != null && session.assertIfNotActive();
      result = ((RID) result).getRecord(session);
    }

    return result instanceof Blob ? (Blob) result : null;
  }

  @Nullable
  @Override
  public RID getLink(@Nonnull String name) {
    assert session == null || session.assertIfNotActive();

    Object result = null;
    if (content != null && content.containsKey(name)) {
      result = content.get(name);
    } else {
      if (isEntity()) {
        result = EntityInternalUtils.rawPropertyRead(identifiable.getEntity(session), name);
      }
    }

    if (result == null) {
      return null;
    }

    if (result instanceof Result res && res.isRecord()) {
      result = res.castToRecord().getIdentity();
    }

    if (result instanceof RID rid) {
      return rid;
    }

    if (result instanceof Identifiable id) {
      return id.getIdentity();
    }

    throw new IllegalStateException("Property " + name + " is not a link");
  }


  public @Nonnull Collection<String> getPropertyNames() {
    assert session == null || session.assertIfNotActive();
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

  public boolean hasProperty(@Nonnull String propName) {
    assert session == null || session.assertIfNotActive();
    loadIdentifiable();
    if (isEntity() && ((Entity) identifiable).hasProperty(propName)) {
      return true;
    }
    if (content != null) {
      return content.containsKey(propName);
    }
    return false;
  }

  @Nullable
  @Override
  public DatabaseSession getBoundedToSession() {
    return session;
  }

  @Override
  public @Nonnull Result detach() {
    var detached = new ResultInternal(null);
    if (content != null) {
      var detachedMap = new LinkedHashMap<String, Object>(content.size());

      for (var entry : content.entrySet()) {
        detachedMap.put(entry.getKey(), toMapValue(entry.getValue(), false));
      }

      detached.content = detachedMap;
    }

    if (identifiable != null) {
      detached.identifiable = identifiable.getIdentity();
    }

    return detached;
  }


  @Override
  public boolean isEntity() {
    assert session == null || session.assertIfNotActive();
    if (identifiable == null) {
      return false;
    }

    if (identifiable instanceof Entity) {
      return true;
    }

    try {
      assert session != null && session.assertIfNotActive();
      identifiable = identifiable.getRecord(session);
    } catch (RecordNotFoundException e) {
      identifiable = null;
    }

    return identifiable instanceof Entity;
  }

  @Nonnull
  public Entity castToEntity() {
    assert session == null || session.assertIfNotActive();

    if (isEntity()) {
      loadIdentifiable();
      return identifiable.getEntity(session);
    }

    throw new IllegalStateException("Result is not an entity");
  }

  @Nullable
  @Override
  public Entity asEntity() {
    assert session == null || session.assertIfNotActive();

    if (isEntity()) {
      loadIdentifiable();
      return identifiable.getEntitySilently(session);
    }

    return null;
  }


  @Override
  public RID getIdentity() {
    assert session == null || session.assertIfNotActive();

    return identifiable.getIdentity();
  }

  @Override
  public boolean isProjection() {
    assert session == null || session.assertIfNotActive();

    return this.identifiable == null;
  }

  @Nonnull
  @Override
  public DBRecord castToRecord() {
    assert session == null || session.assertIfNotActive();
    loadIdentifiable();

    if (identifiable == null) {
      throw new IllegalStateException("Result is not a record");
    }

    return this.identifiable.getRecord(session);
  }

  @Nullable
  @Override
  public DBRecord asRecord() {
    assert session == null || session.assertIfNotActive();
    loadIdentifiable();

    if (identifiable == null) {
      return null;
    }

    return this.identifiable.getRecordSilently(session);
  }

  @Override
  public boolean isBlob() {
    assert session == null || session.assertIfNotActive();
    loadIdentifiable();

    return this.identifiable instanceof Blob;
  }

  @Nonnull
  @Override
  public Blob castToBlob() {
    assert session == null || session.assertIfNotActive();
    loadIdentifiable();

    if (isBlob()) {
      return this.identifiable.getBlob(session);
    }

    throw new IllegalStateException("Result is not a blob");
  }

  @Nullable
  @Override
  public Blob asBlob() {
    assert session == null || session.assertIfNotActive();
    loadIdentifiable();

    if (isBlob()) {
      return this.identifiable.getBlobSilently(session);
    }

    return null;
  }

  public Object getMetadata(String key) {
    assert session == null || session.assertIfNotActive();
    if (key == null) {
      return null;
    }
    return metadata == null ? null : metadata.get(key);
  }

  public void setMetadata(String key, Object value) {
    assert session == null || session.assertIfNotActive();
    if (key == null) {
      return;
    }
    value = convertPropertyValue(value);
    if (metadata == null) {
      metadata = new HashMap<>();
    }
    metadata.put(key, value);
  }

  public void addMetadata(Map<String, Object> values) {
    assert session == null || session.assertIfNotActive();
    if (values == null) {
      return;
    }
    if (this.metadata == null) {
      this.metadata = new HashMap<>();
    }
    this.metadata.putAll(values);
  }

  public Set<String> getMetadataKeys() {
    assert session == null || session.assertIfNotActive();
    return metadata == null ? Collections.emptySet() : metadata.keySet();
  }

  public void loadIdentifiable() {
    assert session == null || session.assertIfNotActive();
    switch (identifiable) {
      case null -> {
        return;
      }
      case Entity elem -> {
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
      case ContextualRecordId contextualRecordId ->
          this.addMetadata(contextualRecordId.getContext());
      default -> {
      }
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
    assert session == null || session.assertIfNotActive();

    this.lightweightEdge = null;
    this.identifiable = identifiable;
    this.content = null;
  }

  public void setLightweightEdge(Edge edge) {
    assert session == null || session.assertIfNotActive();

    this.identifiable = null;
    this.lightweightEdge = edge;
    this.content = null;
  }

  @Nonnull
  @Override
  public Map<String, Object> toMap() {
    assert session == null || session.assertIfNotActive();

    if (isEntity()) {
      return castToEntity().toMap();
    }

    var map = new HashMap<String, Object>();
    for (var prop : getPropertyNames()) {
      var propVal = getProperty(prop);
      map.put(prop, toMapValue(propVal, false));
    }

    return map;
  }

  @Override
  public boolean isEdge() {
    assert session == null || session.assertIfNotActive();
    loadIdentifiable();

    if (isStatefulEdge()) {
      return true;
    }

    return lightweightEdge != null;
  }

  @Nonnull
  @Override
  public Edge castToEdge() {
    assert session == null || session.assertIfNotActive();
    loadIdentifiable();

    if (isStatefulEdge()) {
      return castToStatefulEdge();
    }

    if (lightweightEdge != null) {
      return lightweightEdge;
    }

    throw new DatabaseException("Result is not an edge");
  }

  @Nullable
  @Override
  public Edge asEdge() {
    assert session == null || session.assertIfNotActive();
    loadIdentifiable();

    if (isStatefulEdge()) {
      return castToStatefulEdge();
    }

    if (lightweightEdge != null) {
      return lightweightEdge;
    }

    return null;
  }

  public @Nonnull String toJSON() {
    assert session == null || session.assertIfNotActive();
    if (isEntity()) {
      return castToEntity().toJSON();
    }
    var result = new StringBuilder();
    result.append("{");
    var first = true;
    for (var prop : getPropertyNames()) {
      if (!first) {
        result.append(", ");
      }
      result.append(toJson(prop));
      result.append(": ");
      result.append(toJson(getProperty(prop)));
      first = false;
    }
    result.append("}");
    return result.toString();
  }

  private String toJson(Object val) {
    String jsonVal = null;
    if (val == null) {
      jsonVal = "null";
    } else if (val instanceof String) {
      jsonVal = "\"" + encode(val.toString()) + "\"";
    } else if (val instanceof Number || val instanceof Boolean) {
      jsonVal = val.toString();
    } else if (val instanceof Result) {
      jsonVal = ((Result) val).toJSON();
    } else if (val instanceof RID) {
      jsonVal = "\"" + val + "\"";
    } else if (val instanceof Iterable) {
      var builder = new StringBuilder();
      builder.append("[");
      var first = true;
      for (var o : (Iterable<?>) val) {
        if (!first) {
          builder.append(", ");
        }
        builder.append(toJson(o));
        first = false;
      }
      builder.append("]");
      jsonVal = builder.toString();
    } else if (val instanceof Iterator<?> iterator) {
      var builder = new StringBuilder();
      builder.append("[");
      var first = true;
      while (iterator.hasNext()) {
        if (!first) {
          builder.append(", ");
        }
        builder.append(toJson(iterator.next()));
        first = false;
      }
      builder.append("]");
      jsonVal = builder.toString();
    } else if (val instanceof Map) {
      var builder = new StringBuilder();
      builder.append("{");
      var first = true;
      @SuppressWarnings("unchecked")
      var map = (Map<Object, Object>) val;
      for (var entry : map.entrySet()) {
        if (!first) {
          builder.append(", ");
        }
        builder.append(toJson(entry.getKey()));
        builder.append(": ");
        builder.append(toJson(entry.getValue()));
        first = false;
      }
      builder.append("}");
      jsonVal = builder.toString();
    } else if (val instanceof byte[]) {
      jsonVal = "\"" + Base64.getEncoder().encodeToString((byte[]) val) + "\"";
    } else if (val instanceof Date) {
      jsonVal = "\"" + DateHelper.getDateTimeFormatInstance(session).format(val) + "\"";
    } else if (val.getClass().isArray()) {
      var builder = new StringBuilder();
      builder.append("[");
      for (var i = 0; i < Array.getLength(val); i++) {
        if (i > 0) {
          builder.append(", ");
        }
        builder.append(toJson(Array.get(val, i)));
      }
      builder.append("]");
      jsonVal = builder.toString();
    } else {
      throw new UnsupportedOperationException(
          "Cannot convert " + val + " - " + val.getClass() + " to JSON");
    }
    return jsonVal;
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

    if (lightweightEdge != null) {
      return lightweightEdge.equals(resultObj.lightweightEdge);
    } else if (identifiable != null) {
      return identifiable.equals(resultObj.identifiable);
    }

    if (content != null) {
      return this.content.equals(resultObj.content);
    } else {
      return resultObj.content == null;
    }
  }

  @Override
  public int hashCode() {
    if (lightweightEdge != null) {
      return lightweightEdge.hashCode();
    }
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

      var cached = db.getLocalCache().findRecord(identity);

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

  private static String encode(String s) {
    return IOUtils.encodeJsonString(s);
  }
}
