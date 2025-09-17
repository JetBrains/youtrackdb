package com.jetbrains.youtrackdb.internal.core.gremlin;

import static com.google.common.base.Preconditions.checkNotNull;

import com.jetbrains.youtrackdb.api.gremlin.YTDBGraph;
import com.jetbrains.youtrackdb.api.gremlin.embedded.YTDBElement;
import com.jetbrains.youtrackdb.api.record.Edge;
import com.jetbrains.youtrackdb.api.record.Entity;
import com.jetbrains.youtrackdb.api.record.Identifiable;
import com.jetbrains.youtrackdb.api.record.RID;
import com.jetbrains.youtrackdb.internal.core.db.record.ridbag.LinkBag;
import com.jetbrains.youtrackdb.internal.core.gremlin.io.LinkBagStub;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.structure.Property;
import org.apache.tinkerpop.gremlin.structure.util.ElementHelper;

public abstract class YTDBElementImpl implements YTDBElement {
  private final ThreadLocal<Entity> threadLocalEntity = new ThreadLocal<>();
  private static final char INTERNAL_PREFIX = '@';
  private static final List<String> EDGE_LINK_FIELDS = List.of(Edge.DIRECTION_IN,
      Edge.DIRECTION_OUT);

  @Nullable
  private final Entity fastPathEntity;

  protected YTDBGraphInternal graph;
  protected final RID rid;

  public YTDBElementImpl(final YTDBGraphInternal graph, final Identifiable identifiable) {
    this.graph = checkNotNull(graph);
    var id = checkNotNull(identifiable);

    this.rid = id.getIdentity();

    if (identifiable instanceof Entity entity) {
      this.fastPathEntity = entity;
    } else {
      this.fastPathEntity = null;
    }
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

  protected <V, P extends Property<V>> P writeProperty(
      YTDBPropertyFactory<V, P> propFactory, final String key, final V value) {
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
      return propFactory.create(key, null, this);
    }

    if (value instanceof LinkBagStub linkBagStub) {
      var linkBag = new LinkBag(graphTx.getDatabaseSession(), linkBagStub);
      entity.setProperty(key, linkBag);
      //noinspection unchecked
      return propFactory.create(key, (V) linkBag, this);
    }
    if (value instanceof List<?> || value instanceof Set<?> || value instanceof Map<?, ?>) {
      var type = PropertyTypeInternal.getTypeByValue(value);
      if (type == null) {
        throw new IllegalArgumentException("Unsupported type: " + value.getClass().getName());
      }
      var convertedValue = type.convert(value, graphTx.getDatabaseSession());
      entity.setProperty(key, convertedValue);

      return propFactory.create(key, value, this);
    }

    if (value instanceof YTDBElement ytDBElement) {
      var rid = ytDBElement.id();
      entity.setProperty(key, rid);
    } else {
      entity.setProperty(key, value);
    }

    return propFactory.create(key, value, this);
  }

  protected <V, P extends Property<V>> P readProperty(
      YTDBPropertyFactory<V, P> propFactory, String key) {
    graph.tx().readWrite();

    final var entity = getRawEntity();
    return keyExists(entity, key) ?
        propFactory.create(key, entity.getProperty(key), this) :
        propFactory.empty();
  }

  protected <V, P extends Property<V>> Iterator<P> readProperties(
      YTDBPropertyFactory<V, P> propFactory, final String... propertyKeys) {
    this.graph.tx().readWrite();
    final var entity = getRawEntity();
    final var keysToReturn = propertyKeys.length > 0 ?
        Arrays.stream(propertyKeys).filter(key -> keyExists(entity, key)) :
        entity.getPropertyNames().stream().filter(key -> !keyIgnored(entity, key));

    return keysToReturn
        .map(key -> propFactory.create(key, entity.getProperty(key), this))
        .iterator();
  }

  @Override
  public boolean hasProperty(String key) {
    graph.tx().readWrite();

    return keyExists(getRawEntity(), key);
  }

  @Override
  public boolean removeProperty(String key) {
    graph.tx().readWrite();

    final var entity = getRawEntity();
    if (keyExists(entity, key)) {
      entity.removeProperty(key);
      return true;
    } else {
      return false;
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

    if (fastPathEntity == null || fastPathEntity.isNotBound(session)) {
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

  private static boolean keyExists(Entity entity, String key) {
    return !keyIgnored(entity, key) && entity.hasProperty(key);
  }

  private static boolean keyIgnored(Entity entity, String key) {
    return key == null || key.isEmpty() ||
        key.charAt(0) == INTERNAL_PREFIX ||
        entity.isStatefulEdge() && EDGE_LINK_FIELDS.contains(key);
  }
}
