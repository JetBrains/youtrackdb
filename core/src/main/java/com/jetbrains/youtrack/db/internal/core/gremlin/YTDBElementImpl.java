package com.jetbrains.youtrack.db.internal.core.gremlin;

import static com.google.common.base.Preconditions.checkNotNull;

import com.jetbrains.youtrack.db.api.gremlin.YTDBGraph;
import com.jetbrains.youtrack.db.api.gremlin.embedded.YTDBElement;
import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.core.db.record.ridbag.LinkBag;
import com.jetbrains.youtrack.db.internal.core.gremlin.io.LinkBagStub;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.PropertyTypeInternal;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.structure.Property;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.util.ElementHelper;

public abstract class YTDBElementImpl implements YTDBElement {
  private final ThreadLocal<Entity> threadLocalEntity = new ThreadLocal<>();
  private final Entity fastPathEntity;

  protected YTDBGraphInternal graph;
  protected final RID rid;

  public YTDBElementImpl(final YTDBGraphInternal graph, final Entity rawEntity) {
    this.graph = checkNotNull(graph);
    var entity = checkNotNull(rawEntity);

    this.rid = entity.getIdentity();
    this.fastPathEntity = entity;
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
  public YTDBGraph graph() {
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

    var graphTx = graph.tx();
    graphTx.readWrite();

    var entity = getRawEntity();
    if (value == null) {
      entity.setProperty(key, null);
      return new YTDBPropertyImpl<>(key, null, this);
    }

    if (value instanceof LinkBagStub linkBagStub) {
      var linkBag = new LinkBag(graphTx.getDatabaseSession(), linkBagStub);
      entity.setProperty(key, linkBag);
      //noinspection unchecked
      return new YTDBPropertyImpl<>(key, (V) linkBag, this);
    }
    if (value instanceof List<?> || value instanceof Set<?> || value instanceof Map<?, ?>) {
      var type = PropertyTypeInternal.getTypeByValue(value);
      if (type == null) {
        throw new IllegalArgumentException("Unsupported type: " + value.getClass().getName());
      }
      var convertedValue = type.convert(value, graphTx.getDatabaseSession());
      entity.setProperty(key, convertedValue);

      return new YTDBPropertyImpl<>(key, value, this);
    }

    if (value instanceof YTDBElement ytDBElement) {
      var rid = ytDBElement.id();
      entity.setProperty(key, rid);
    } else {
      entity.setProperty(key, value);
    }

    return new YTDBPropertyImpl<>(key, value, this);
  }

  @Override
  public <V> Property<V> property(String key) {
    graph.tx().readWrite();

    if (key == null || key.isEmpty()) {
      return Property.empty();
    }

    var entity = getRawEntity();
    if (entity.hasProperty(key)) {
      return new YTDBPropertyImpl<>(key, getRawEntity().getProperty(key), this);
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
          .filter(key -> key != null && !key.isEmpty() && entity.hasProperty(key))
          .map(entry -> new YTDBPropertyImpl<V>(entry, entity.getProperty(entry), this))
          .iterator();
    } else {
      return entity.getPropertyNames().stream()
          .map(entry -> new YTDBPropertyImpl<V>(entry, entity.getProperty(entry), this))
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

  @Override
  public final int hashCode() {
    return ElementHelper.hashCode(this);
  }

  @SuppressWarnings("EqualsDoesntCheckParameterClass")
  @Override
  public final boolean equals(final Object object) {
    return ElementHelper.areEqual(this, object);
  }

  public Entity getRawEntity() {
    var graphTx = graph.tx();
    var session = graphTx.getDatabaseSession();

    if (fastPathEntity.isNotBound(session)) {
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
    } else {
      return fastPathEntity;
    }
  }
}
