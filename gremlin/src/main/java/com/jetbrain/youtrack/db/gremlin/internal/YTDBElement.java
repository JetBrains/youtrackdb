package com.jetbrain.youtrack.db.gremlin.internal;

import static com.google.common.base.Preconditions.checkNotNull;

import com.jetbrain.youtrack.db.gremlin.api.YTDBGraph;
import com.jetbrains.youtrack.db.api.record.Entity;

import java.util.Arrays;
import java.util.Iterator;

import com.jetbrains.youtrack.db.api.record.RID;
import org.apache.tinkerpop.gremlin.structure.*;
import org.apache.tinkerpop.gremlin.structure.util.ElementHelper;

public abstract class YTDBElement implements Element {

  protected YTDBGraphInternal graph;

  private final ThreadLocal<Entity> threadLocalEntity = new ThreadLocal<>();
  private final RID rid;

  public YTDBElement(final YTDBGraphInternal graph, final Entity rawEntity) {
    if (rawEntity == null) {
      throw new IllegalArgumentException("rawEntity must not be null!");
    }
    this.graph = checkNotNull(graph);

    var entity = checkNotNull(rawEntity);
    threadLocalEntity.set(entity);

    this.rid = entity.getIdentity();
  }

  @Override
  public RID id() {
    return rid;
  }

  @Override
  public String label() {
    this.graph.tx().readWrite();
    return getRawEntity().getSchemaClassName();
  }

  @Override
  public Graph graph() {
    return graph;
  }

  @Override
  public <V> Property<V> property(final String key, final V value) {
    if (key == null) {
      throw Property.Exceptions.propertyKeyCanNotBeNull();
    }
    if (Graph.Hidden.isHidden(key)) {
      throw Property.Exceptions.propertyKeyCanNotBeAHiddenKey(key);
    }

    this.graph.tx().readWrite();

    var entity = getRawEntity();
    entity.setProperty(key, value);

    return new YTDBProperty<>(key, value, this);
  }

  @Override
  public <V> Property<V> property(String key) {
    var entity = getRawEntity();
    if (entity.hasProperty(key)) {
      return new YTDBProperty<>(key, getRawEntity().getProperty(key), this);
    }
    return Property.empty();
  }

  public void property(Object... keyValues) {
    ElementHelper.legalPropertyKeyValueArray(keyValues);
    if (ElementHelper.getIdValue(keyValues).isPresent()) {
      throw Vertex.Exceptions.userSuppliedIdsNotSupported();
    }

    // copied from ElementHelper.attachProperties
    // can't use ElementHelper here because we only want to save the
    // document at the very end
    for (var i = 0; i < keyValues.length; i = i + 2) {
      if (!keyValues[i].equals(T.id) && !keyValues[i].equals(T.label)) {
        property((String) keyValues[i], keyValues[i + 1]);
      }
    }
  }

  @Override
  public <V> Iterator<? extends Property<V>> properties(final String... propertyKeys) {
    this.graph.tx().readWrite();
    var entity = getRawEntity();

    if (propertyKeys.length > 0) {
      return Arrays.stream(propertyKeys)
          .filter(entity::hasProperty)
          .map(entry -> new YTDBProperty<V>(entry, entity.getProperty(entry), this))
          .iterator();
    } else {
      return entity.getPropertyNames().stream()
          .map(entry -> new YTDBProperty<V>(entry, entity.getProperty(entry), this))
          .iterator();
    }
  }

  @Override
  public void remove() {
    this.graph.tx().readWrite();
    getRawEntity().delete();
  }

  public YTDBGraphInternal getGraph() {
    return graph;
  }

  public Entity getRawEntity() {
    var session = graph.getUnderlyingSession();
    var tx = session.getActiveTransaction();

    var entity = threadLocalEntity.get();
    if (entity == null) {
      entity = tx.loadEntity(rid);
      threadLocalEntity.set(entity);

      return entity;
    }

    if (entity.isNotBound(session)) {
      entity = tx.load(entity);
      threadLocalEntity.set(entity);
    }

    return entity;
  }

  @Override
  public final int hashCode() {
    return ElementHelper.hashCode(this);
  }

  @SuppressWarnings("EqualsDoesntCheckParameterClass")
  @Override
  public final boolean equals(final Object object) {
    return ElementHelper.areEqual(this, object);
  }
}
