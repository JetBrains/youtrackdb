package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.api.DatabaseSession;
import com.jetbrains.youtrackdb.api.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.api.exception.DatabaseException;
import com.jetbrains.youtrackdb.api.query.Result;
import com.jetbrains.youtrackdb.api.query.ResultSet;
import com.jetbrains.youtrackdb.api.record.Blob;
import com.jetbrains.youtrackdb.api.record.DBRecord;
import com.jetbrains.youtrackdb.api.record.Edge;
import com.jetbrains.youtrackdb.api.record.Entity;
import com.jetbrains.youtrackdb.api.record.Identifiable;
import com.jetbrains.youtrackdb.api.record.RID;
import com.jetbrains.youtrackdb.api.record.Relation;
import com.jetbrains.youtrackdb.api.record.StatefulEdge;
import com.jetbrains.youtrackdb.api.record.Vertex;
import com.jetbrains.youtrackdb.internal.common.io.IOUtils;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.db.record.EntityLinkListImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.EntityLinkMapIml;
import com.jetbrains.youtrackdb.internal.core.db.record.EntityLinkSetImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.ridbag.LinkBag;
import com.jetbrains.youtrackdb.internal.core.id.ContextualRecordId;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.query.BasicResultInternal;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.EmbeddedListResultImpl;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.EmbeddedMapResultImpl;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.EmbeddedSetResultImpl;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.LinkListResultImpl;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.LinkMapResultImpl;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.LinkSetResultImpl;
import com.jetbrains.youtrackdb.internal.core.util.DateHelper;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


public class ResultInternal implements Result, BasicResultInternal {

  protected Map<String, Object> content;
  protected Map<String, Object> temporaryContent;
  protected Map<String, Object> metadata;

  @Nullable
  protected Identifiable identifiable;
  @Nullable
  protected DatabaseSessionInternal session;
  @Nullable
  protected Relation<?> relation;

  public ResultInternal(@Nullable DatabaseSessionInternal session) {
    content = new HashMap<>();
    this.session = session;
  }

  public ResultInternal(@Nullable DatabaseSessionInternal session,
      @Nonnull Map<String, ?> data) {
    content = new HashMap<>();
    this.session = session;

    for (var entry : data.entrySet()) {
      setProperty(entry.getKey(), entry.getValue());
    }
  }

  public ResultInternal(@Nullable DatabaseSessionInternal session, @Nonnull Identifiable ident) {
    setIdentifiable(ident);
    this.session = session;
  }

  public ResultInternal(@Nullable DatabaseSessionInternal session,
      @Nonnull Relation<?> relation) {
    this.session = session;
    if (relation.isLightweight()) {
      this.relation = relation;
    } else {
      setIdentifiable(relation.asEntity());
    }
  }

  public void refreshNonPersistentRid() {
    if (identifiable instanceof RID rid && !rid.isPersistent()) {
      checkSessionForRecords();
      identifiable = session.refreshRid(rid);
    }
  }

  @Nullable
  public static Object toMapValue(Object value, boolean includeEntityMetadata) {
    return switch (value) {
      case null -> null;

      case Edge edge -> {
        if (edge.isLightweight()) {
          yield edge.toMap();
        } else {
          yield edge.asStatefulEdge().getIdentity();
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

      case EntityLinkListImpl linkList -> {
        List<RID> list = new ArrayList<>(linkList.size());
        for (var item : linkList) {
          list.add(item.getIdentity());
        }
        yield list;
      }

      case EntityLinkSetImpl linkSet -> {
        Set<RID> set = new HashSet<>(linkSet.size());
        for (var item : linkSet) {
          set.add(item.getIdentity());
        }
        yield set;
      }
      case EntityLinkMapIml linkMap -> {
        Map<Object, RID> map = new HashMap<>(linkMap.size());
        for (var entry : linkMap.entrySet()) {
          map.put(entry.getKey(), entry.getValue().getIdentity());
        }
        yield map;
      }
      case LinkBag linkBag -> {
        List<RID> list = new ArrayList<>(linkBag.size());
        for (var rid : linkBag) {
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
        if (PropertyTypeInternal.getTypeByValue(value) == null) {
          throw new IllegalArgumentException(
              "Unexpected property value :" + value);
        }

        yield value;
      }
    };
  }

  @Override
  public void setProperty(@Nonnull String name, Object value) {
    assert checkSession();

    if (content == null) {
      if (identifiable != null) {
        throw new IllegalStateException("Impossible to mutate result set containing entity");
      }
      if (relation != null) {
        throw new IllegalStateException(
            "Impossible to mutate result set containing lightweight edge");
      }
      throw new IllegalStateException("Impossible to mutate result set");
    }

    value = convertPropertyValue(value);
    content.put(name, value);
  }

  @Override
  public boolean isIdentifiable() {
    assert checkSession();

    return identifiable != null;
  }

  @Nullable
  private Object convertPropertyValue(Object value) {
    if (value == null) {
      return null;
    }

    if (PropertyTypeInternal.isSingleValueType(value)) {
      return value;
    }

    switch (value) {
      case LinkBag linkBag -> {
        var list = new ArrayList<RID>(linkBag.size());
        for (var rid : linkBag) {
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
        if (entity.isEmbedded()) {
          return entity.detach();
        }
        final var rid = entity.getIdentity();
        return session.isTxActive() && !rid.isPersistent() ?
            session.refreshRid(rid) :
            rid;
      }

      case ContextualRecordId contextualRecordId -> {
        addMetadata(contextualRecordId.getContext());
        return new RecordId(contextualRecordId);
      }

      case Identifiable id -> {
        var res = id.getIdentity();
        if (session != null) {
          res = session.refreshRid(res);
        }

        return res;
      }
      case Result result -> {
        var resultSession = result.getBoundedToSession();
        if (resultSession != null && resultSession != session) {
          throw new DatabaseException(
              "Result is bound to different session, cannot use it as property value");
        }

        if (result.isEntity()) {
          return convertPropertyValue(result.asEntity());
        } else if (result.isBlob()) {
          return convertPropertyValue(result.asBlob());
        }
        if (result.isEdge()) {
          if (!result.isStatefulEdge()) {
            return result.asEdge();
          }

          return convertPropertyValue(result.asStatefulEdge());
        }
        if (result.isRelation()) {
          return result.asRelation();
        }

        return result;
      }
      case Object[] array -> {
        List<Object> listCopy = null;
        var allIdentifiable = false;

        for (var o : array) {
          var res = convertPropertyValue(o);

          if (res instanceof Identifiable) {
            allIdentifiable = true;
            if (listCopy == null) {
              //noinspection unchecked,rawtypes
              listCopy = (List<Object>) (List) new LinkListResultImpl(array.length);
            }
          } else {
            if (allIdentifiable) {
              var lst = new EmbeddedListResultImpl<>(array.length);
              lst.addAll(listCopy);

              listCopy = lst;
              allIdentifiable = false;
            }
            if (listCopy == null) {
              listCopy = new EmbeddedListResultImpl<>(array.length);
            }
          }

          listCopy.add(res);
        }
        if (listCopy == null) {
          listCopy = new EmbeddedListResultImpl<>();
        }
        return listCopy;
      }
      case List<?> collection -> {
        List<Object> listCopy = null;
        var allIdentifiable = false;

        for (var o : collection) {
          var res = convertPropertyValue(o);

          if (res instanceof Identifiable) {
            allIdentifiable = true;
            if (listCopy == null) {
              //noinspection unchecked,rawtypes
              listCopy = (List<Object>) (List) new LinkListResultImpl(collection.size());
            }
          } else {
            if (allIdentifiable) {
              var lst = new EmbeddedListResultImpl<>(collection.size());
              lst.addAll(listCopy);

              listCopy = lst;
              allIdentifiable = false;
            }
            if (listCopy == null) {
              listCopy = new EmbeddedListResultImpl<>(collection.size());
            }
          }

          listCopy.add(res);
        }

        if (listCopy == null) {
          listCopy = new EmbeddedListResultImpl<>();
        }
        return listCopy;
      }
      case ResultSet resultSet -> {
        var resultList = new ArrayList<>();
        resultSet.forEachRemaining(result -> resultList.add(convertPropertyValue(result)));
        return resultList;
      }
      case Set<?> set -> {
        Set<Object> setCopy = null;

        var allIdentifiable = false;

        for (var o : set) {
          var res = convertPropertyValue(o);
          if (res instanceof Identifiable) {
            if (setCopy == null) {
              //noinspection unchecked,rawtypes
              setCopy = (Set<Object>) (Set) new LinkSetResultImpl(set.size());
            }
            allIdentifiable = true;
          } else {
            if (allIdentifiable) {
              throw new IllegalArgumentException(
                  "Invalid property value, if set contains identifiables, it should contain only them");
            }
            if (setCopy == null) {
              setCopy = new EmbeddedSetResultImpl<>(set.size());
            }
          }

          setCopy.add(res);
        }

        if (setCopy == null) {
          setCopy = new EmbeddedSetResultImpl<>();
        }

        return setCopy;
      }

      case Map<?, ?> map -> {
        Map<String, Object> mapCopy = null;
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
            if (mapCopy == null) {
              //noinspection unchecked,rawtypes
              mapCopy = (Map<String, Object>) (Map) new LinkMapResultImpl(map.size());
            }
          } else {
            if (allIdentifiable) {
              throw new IllegalArgumentException(
                  "Invalid property value, if map contains identifiables, it should contain only them");

            }
            if (mapCopy == null) {
              mapCopy = new EmbeddedMapResultImpl<>(map.size());
            }
          }

          mapCopy.put(stringKey, res);
        }

        if (mapCopy == null) {
          mapCopy = new EmbeddedMapResultImpl<>();
        }

        return mapCopy;
      }
      case Relation<?> biLink -> {
        if (biLink.isLightweight()) {
          return biLink;
        } else {
          return convertPropertyValue(biLink.asEntity());
        }
      }
      default -> {
        throw new CommandExecutionException(
            "Invalid property value for Result: " + value + " - " + value.getClass().getName());
      }
    }
  }

  public void setTemporaryProperty(String name, Object value) {
    assert checkSession();
    if (temporaryContent == null) {
      temporaryContent = new HashMap<>();
    }

    if (value instanceof Result && ((Result) value).isEntity()) {
      temporaryContent.put(name, ((Result) value).asEntity());
    } else {
      temporaryContent.put(name, value);
    }
  }

  @Nullable
  public Object getTemporaryProperty(String name) {
    assert checkSession();
    if (name == null || temporaryContent == null) {
      return null;
    }
    return temporaryContent.get(name);
  }

  public Set<String> getTemporaryProperties() {
    return temporaryContent == null ? Collections.emptySet() : temporaryContent.keySet();
  }

  public void removeProperty(String name) {
    assert checkSession();

    if (content != null) {
      content.remove(name);
    }
  }

  @Override
  public <T> T getProperty(@Nonnull String name) {
    assert checkSession();

    T result = null;
    if (content != null && content.containsKey(name)) {
      //noinspection unchecked
      result = (T) content.get(name);
    } else {
      if (isEntity()) {
        result = asEntity().getProperty(name);
      }
    }

    return result;
  }

  @Override
  public Entity getEntity(@Nonnull String name) {
    assert checkSession();

    Object result = null;
    if (content != null && content.containsKey(name)) {
      result = content.get(name);
    } else {
      if (isEntity()) {
        result = asEntity().getEntity(name);
      }
    }

    if (result instanceof Identifiable id) {
      var transaction = session.getActiveTransaction();
      result = transaction.loadEntity(id);
    }

    if (result instanceof Entity entity) {
      return entity;
    }

    if (result == null) {
      return null;
    }

    throw new DatabaseException("Property " + name + " is not an entity");
  }

  @Nullable
  @Override
  public Result getResult(@Nonnull String name) {
    assert checkSession();

    Object result = null;
    if (content != null && content.containsKey(name)) {
      result = content.get(name);
    } else {
      if (isEntity()) {
        result = asEntity().getResult(name);
      }
    }

    if (result instanceof Result res) {
      return res;
    }

    if (result == null) {
      return null;
    }

    throw new DatabaseException("Property " + name + " is not a result.");
  }

  @Override
  public Vertex getVertex(@Nonnull String name) {
    checkSessionForRecords();

    Object result = null;
    if (content != null && content.containsKey(name)) {
      result = content.get(name);
    } else {
      if (isEntity()) {
        result = asEntity().getVertex(name);
      }
    }

    if (result instanceof Identifiable id) {
      var transaction = session.getActiveTransaction();
      return transaction.loadVertex(id);
    }

    if (result == null) {
      return null;
    }

    throw new DatabaseException("Property " + name + " is not a vertex");
  }

  @Override
  public Edge getEdge(@Nonnull String name) {
    checkSessionForRecords();

    Object result = null;
    if (content != null && content.containsKey(name)) {
      result = content.get(name);
    } else {
      if (isEntity()) {
        result = asEntity().getEdge(name);
      }
    }

    if (result instanceof Identifiable id) {
      var transaction = session.getActiveTransaction();
      return transaction.loadEdge(id);
    }

    if (result instanceof Edge edge) {
      return edge;
    }

    if (result == null) {
      return null;
    }

    throw new DatabaseException("Property " + name + " is not an edge");
  }

  @Override
  public Blob getBlob(@Nonnull String name) {
    checkSessionForRecords();

    Object result = null;
    if (content != null && content.containsKey(name)) {
      result = content.get(name);
    } else {
      if (isEntity()) {
        result = asEntity().getProperty(name);
      }
    }

    if (result instanceof Identifiable id) {
      var transaction = session.getActiveTransaction();
      return transaction.loadBlob(id);
    }

    if (result == null) {
      return null;
    }

    throw new DatabaseException("Property " + name + " is not a blob");
  }

  @Nullable
  @Override
  public RID getLink(@Nonnull String name) {
    assert checkSession();

    Object result = null;
    if (content != null && content.containsKey(name)) {
      result = content.get(name);
    } else {
      if (isEntity()) {
        result = asEntity().getLink(name);
      }
    }

    return switch (result) {
      case null -> null;
      case Identifiable id -> id.getIdentity();
      default -> throw new IllegalStateException("Property " + name + " is not a link");
    };

  }

  @Override
  public @Nonnull List<String> getPropertyNames() {
    assert checkSession();
    if (content != null) {
      return new ArrayList<>(content.keySet());
    }

    if (isEntity()) {
      return asEntity().getPropertyNames();
    }

    return Collections.emptyList();
  }

  @Override
  public boolean hasProperty(@Nonnull String propName) {
    assert checkSession();
    if (isEntity() && asEntity().hasProperty(propName)) {
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

  public void setSession(@Nullable DatabaseSessionInternal session) {
    this.session = session;
  }

  @Override
  public @Nonnull Result detach() {
    assert checkSession();
    if (relation != null) {
      throw new DatabaseException("Cannot detach lightweight edge");
    }

    var detached = new ResultInternal(null);

    if (content != null) {
      var detachedMap = new HashMap<String, Object>(content.size());

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

  @Nonnull
  @Override
  public Identifiable asIdentifiable() {
    if (identifiable != null) {
      return identifiable;
    }

    throw new IllegalStateException("Result is not an identifiable");
  }

  @Nullable
  @Override
  public Identifiable asIdentifiableOrNull() {
    return identifiable;
  }

  @Override
  public boolean isEntity() {
    assert checkSession();
    if (identifiable == null) {
      return false;
    }
    if (identifiable instanceof Entity) {
      return true;
    }

    return !isBlob();
  }

  protected void checkSessionForRecords() {
    if (session == null) {
      throw new DatabaseException(
          "There is no active session to process the record related operations.");
    }
  }

  @Override
  @Nonnull
  public Entity asEntity() {
    assert checkSession();

    if (identifiable instanceof Entity) {
      return (Entity) identifiable;
    }

    if (isEntity()) {
      var transaction = session.getActiveTransaction();
      this.identifiable = transaction.loadEntity(identifiable);
      return asEntity();
    }

    throw new IllegalStateException("Result is not an entity");
  }

  @Nullable
  @Override
  public Entity asEntityOrNull() {
    assert checkSession();
    if (identifiable instanceof Entity) {
      return (Entity) identifiable;
    }

    if (isEntity()) {
      var transaction = session.getActiveTransactionOrNull();

      if (transaction == null) {
        if (identifiable instanceof Entity entity) {
          return entity;
        }

        return null;
      }

      this.identifiable = transaction.loadEntity(identifiable);
      return asEntityOrNull();
    }

    return null;
  }

  @Override
  public RID getIdentity() {
    assert checkSession();

    if (identifiable == null) {
      return null;
    }

    return identifiable.getIdentity();
  }

  @Override
  public boolean isProjection() {
    assert checkSession();

    return this.content != null;
  }

  @Nonnull
  @Override
  public DBRecord asRecord() {
    assert checkSession();

    if (identifiable == null) {
      throw new IllegalStateException("Result is not a record");
    }

    if (identifiable instanceof DBRecord) {
      return (DBRecord) identifiable;
    }

    var transaction = session.getActiveTransaction();
    this.identifiable = transaction.load(identifiable);
    return asRecord();
  }

  @Nullable
  @Override
  public DBRecord asRecordOrNull() {
    assert checkSession();
    if (identifiable == null) {
      return null;
    }

    if (identifiable instanceof DBRecord) {
      return (DBRecord) identifiable;
    }

    var transaction = session.getActiveTransactionOrNull();
    if (transaction == null) {
      if (identifiable instanceof DBRecord record) {
        return record;
      }

      return null;
    }

    this.identifiable = transaction.load(this.identifiable);
    return asRecordOrNull();
  }

  @Override
  public boolean isBlob() {
    assert checkSession();
    if (identifiable == null) {
      return false;
    }
    if (identifiable instanceof Blob) {
      return true;
    }

    checkSessionForRecords();

    var schemaSnapshot = session.getMetadata().getImmutableSchemaSnapshot();
    var blobCollections = schemaSnapshot.getBlobCollections();
    return blobCollections.contains(identifiable.getIdentity().getCollectionId());
  }

  @Nonnull
  @Override
  public Blob asBlob() {
    assert checkSession();

    if (identifiable instanceof Blob) {
      return (Blob) identifiable;
    }

    if (isBlob()) {
      var transaction = session.getActiveTransaction();
      this.identifiable = transaction.loadBlob(this.identifiable);
      return asBlob();
    }

    throw new IllegalStateException("Result is not a blob");
  }

  protected final boolean checkSession() {
    assert session == null || session.assertIfNotActive();
    return true;
  }

  @Nullable
  @Override
  public Blob asBlobOrNull() {
    assert checkSession();
    if (identifiable instanceof Blob) {
      return (Blob) identifiable;
    }

    if (isBlob()) {
      var transaction = session.getActiveTransactionOrNull();

      if (transaction == null) {
        if (identifiable instanceof Blob blob) {
          return blob;
        }

        return null;
      }

      this.identifiable = transaction.loadBlob(this.identifiable);
      return asBlobOrNull();
    }

    return null;
  }

  @Nullable
  public Object getMetadata(String key) {
    assert checkSession();
    if (key == null) {
      return null;
    }
    return metadata == null ? null : metadata.get(key);
  }

  @Override
  public void setMetadata(String key, Object value) {
    assert checkSession();
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
    assert checkSession();
    if (values == null) {
      return;
    }
    if (this.metadata == null) {
      this.metadata = new HashMap<>();
    }
    this.metadata.putAll(values);
  }

  public Set<String> getMetadataKeys() {
    assert checkSession();
    return metadata == null ? Collections.emptySet() : metadata.keySet();
  }

  @Override
  public void setIdentity(@Nonnull RID identity) {
    setIdentifiable(identity);
  }

  public void setIdentifiable(Identifiable identifiable) {
    assert checkSession();

    this.relation = null;
    if (identifiable instanceof Entity entity && entity.isEmbedded()) {
      content = new HashMap<>();
      this.identifiable = null;

      var map = entity.toMap();
      for (var entry : map.entrySet()) {
        setProperty(entry.getKey(), entry.getValue());
      }

      return;
    }

    this.content = null;

    if (identifiable instanceof ContextualRecordId contextualRecordId) {
      this.identifiable = new RecordId(contextualRecordId);
      addMetadata(contextualRecordId.getContext());
    } else {
      this.identifiable = identifiable;
    }
  }

  public void setRelation(Relation<?> relation) {
    assert checkSession();

    this.identifiable = null;
    this.relation = relation;
    this.content = null;
  }

  @Nonnull
  @Override
  public Map<String, Object> toMap() {
    assert checkSession();

    if (relation != null) {
      return relation.toMap();
    }

    if (isEntity()) {
      return asEntity().toMap();
    }

    var map = new HashMap<String, Object>();
    for (var prop : getPropertyNames()) {
      var propVal = getProperty(prop);
      map.put(prop, toMapValue(propVal, false));
    }

    return map;
  }

  @Override
  public boolean isRelation() {
    assert checkSession();
    return relation != null || isEdge();
  }

  @Override
  public boolean isEdge() {
    assert checkSession();
    if (content != null) {
      return false;
    }

    if (relation instanceof Edge) {
      return true;
    }

    return isStatefulEdge();
  }

  @Override
  public boolean isStatefulEdge() {
    assert checkSession();
    switch (identifiable) {
      case null -> {
        return false;
      }
      case StatefulEdge statefulEdge -> {
        return true;
      }
      default -> {
        checkSessionForRecords();

        var schemaSnapshot = session.getMetadata().getImmutableSchemaSnapshot();
        var cls = schemaSnapshot.getClassByCollectionId(
            identifiable.getIdentity().getCollectionId());

        return cls != null && !cls.isAbstract() && cls.isEdgeType();
      }
    }
  }

  @Override
  public boolean isVertex() {
    assert checkSession();

    switch (identifiable) {
      case null -> {
        return false;
      }
      case Vertex vertex -> {
        return true;
      }
      default -> {
        checkSessionForRecords();

        var schemaSnapshot = session.getMetadata().getImmutableSchemaSnapshot();
        var cls = schemaSnapshot.getClassByCollectionId(
            identifiable.getIdentity().getCollectionId());

        return cls != null && !cls.isAbstract() && cls.isVertexType();
      }
    }
  }

  @Nonnull
  @Override
  public Edge asEdge() {
    assert checkSession();
    if (relation instanceof Edge edge) {
      return edge;
    }

    if (isStatefulEdge()) {
      return asStatefulEdge();
    }

    throw new DatabaseException("Result is not an edge");
  }

  @Nullable
  @Override
  public Edge asEdgeOrNull() {
    assert checkSession();

    if (relation instanceof Edge edge) {
      return edge;
    }

    if (isStatefulEdge()) {
      return asStatefulEdge();
    }

    return null;
  }

  @Override
  @Nonnull
  public Relation<?> asRelation() {
    assert checkSession();
    if (relation != null) {
      return relation;
    }

    if (isStatefulEdge()) {
      return asStatefulEdge();
    }

    throw new DatabaseException("Result is not an relation");
  }

  @Override
  @Nullable
  public Relation<?> asRelationOrNull() {
    assert checkSession();

    if (relation != null) {
      return relation;
    }

    if (isStatefulEdge()) {
      return asStatefulEdge();
    }

    return null;
  }

  @Override
  public @Nonnull String toJSON() {
    assert checkSession();

    if (relation != null) {
      return relation.toJSON();
    }

    if (isEntity()) {
      var entity = asEntity();
      if (entity.isUnloaded() & session != null) {
        entity = session.getActiveTransaction().loadEntity(entity);
      }

      return entity.toJSON();
    }

    var propNames = new ArrayList<>(getPropertyNames());
    //record metadata properties has to be sorted first
    propNames.sort((v1, v2) -> {
      if (v1 == null) {
        return -1;
      }
      if (v2 == null) {
        return 1;
      }

      if (!v1.isEmpty() && v1.charAt(0) == '@') {
        if (!v2.isEmpty() && v2.charAt(0) == '@') {
          return v1.compareTo(v2);
        }

        return -1;
      }

      return v1.compareTo(v2);
    });
    var result = new StringBuilder();
    result.append("{");
    var first = true;

    for (var prop : propNames) {
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
    String jsonVal;
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
      return "identifiable:" + identifiable;
    }
    if (relation != null) {
      return "relation:" + relation.toJSON();
    }
    return "content:{\n"
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
      if (obj instanceof Result result) {
        if (result.isIdentifiable() && identifiable != null) {
          return identifiable.equals(result.getIdentity());
        } else if (result.isEdge() && relation instanceof Edge edge) {
          return edge.equals(result.getIdentity());
        } else if (result.isProjection() && content != null) {
          var propNames = result.getPropertyNames();

          if (propNames.size() != content.size()) {
            return false;
          }

          //noinspection ObjectInstantiationInEqualsHashCode
          for (var prop : propNames) {
            var thisValue = content.get(prop);
            var otherValue = result.getProperty(prop);
            if (thisValue == null && otherValue == null) {
              continue;
            }
            if (thisValue == null || otherValue == null) {
              return false;
            }
            if (!thisValue.equals(otherValue)) {
              return false;
            }
          }

          return true;
        }
      }

      return false;
    }

    if (session != resultObj.session) {
      return false;
    }

    if (relation != null) {
      return relation.equals(resultObj.relation);
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
    if (relation != null) {
      return relation.hashCode();
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

  @Nullable
  public static Result toResult(@Nullable Object value, @Nonnull DatabaseSessionInternal session) {
    return toResult(value, session, null);
  }

  @Nullable
  public static Result toResult(@Nullable Object value,
      @Nonnull DatabaseSessionInternal session, @Nullable String alias) {
    if (value instanceof Result result) {
      return result;
    }

    return toResultInternal(value, session, alias);
  }

  @Nullable
  public static ResultInternal toResultInternal(@Nullable Object value,
      @Nonnull DatabaseSessionInternal session) {
    return toResultInternal(value, session, null);
  }

  @Nullable
  public static ResultInternal toResultInternal(@Nullable Object value,
      @Nonnull DatabaseSessionInternal session, @Nullable String alias) {
    switch (value) {
      case null -> {
        return null;
      }
      case ResultInternal result -> {
        return result;
      }
      case Identifiable identifiable -> {
        if (alias != null) {
          throw new CommandExecutionException(
              "Alias '" + alias + "' is not supported for identifiable values");
        }
        return new ResultInternal(session, identifiable);
      }
      case Relation<?> bidirectionalLink -> {
        return new ResultInternal(session, bidirectionalLink);
      }
      case Map<?, ?> map -> {
        if (alias != null) {
          throw new CommandExecutionException(
              "Alias '" + alias + "' is not supported for map values");
        }

        var result = new ResultInternal(session);
        for (var entry : map.entrySet()) {
          if (entry.getKey() instanceof String key) {
            result.setProperty(key, entry.getValue());
          } else {
            throw new CommandExecutionException(
                "Invalid value, only maps with key types of String are supported : " +
                    entry.getKey());
          }
        }
        return result;
      }
      case Map.Entry<?, ?> entry -> {
        var result = new ResultInternal(session);
        var key = entry.getKey();
        if (!(key instanceof String stringKey)) {
          throw new CommandExecutionException(
              "Invalid property value, only maps with key types of String are supported : " +
                  key);
        }
        result.setProperty(stringKey, entry.getValue());
        return result;
      }
      default -> {
        var result = new ResultInternal(session);
        if (alias != null) {
          result.setProperty(alias, value);
        } else {
          result.setProperty("value", value);
        }
        return result;
      }
    }
  }

  private static String encode(String s) {
    return IOUtils.encodeJsonString(s);
  }
}
