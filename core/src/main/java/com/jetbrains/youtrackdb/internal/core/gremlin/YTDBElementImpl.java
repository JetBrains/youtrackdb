package com.jetbrains.youtrackdb.internal.core.gremlin;

import static com.google.common.base.Preconditions.checkNotNull;

import com.jetbrains.youtrackdb.api.gremlin.YTDBGraph;
import com.jetbrains.youtrackdb.api.gremlin.embedded.YTDBEdge;
import com.jetbrains.youtrackdb.api.gremlin.embedded.YTDBElement;
import com.jetbrains.youtrackdb.api.gremlin.embedded.YTDBProperty;
import com.jetbrains.youtrackdb.api.gremlin.embedded.YTDBVertex;
import com.jetbrains.youtrackdb.api.record.Edge;
import com.jetbrains.youtrackdb.api.record.Entity;
import com.jetbrains.youtrackdb.api.record.Identifiable;
import com.jetbrains.youtrackdb.api.record.RID;
import com.jetbrains.youtrackdb.internal.core.db.record.ridbag.LinkBag;
import com.jetbrains.youtrackdb.internal.core.gremlin.io.LinkBagStub;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nullable;
import org.apache.tinkerpop.gremlin.structure.Graph.Hidden;
import org.apache.tinkerpop.gremlin.structure.Property;
import org.apache.tinkerpop.gremlin.structure.util.ElementHelper;

public abstract class YTDBElementImpl implements YTDBElement {
  private final ThreadLocal<Entity> threadLocalEntity = new ThreadLocal<>();
  private static final char INTERNAL_PREFIX = '@';
  private static final List<String> EDGE_LINK_FIELDS =
      List.of(Edge.DIRECTION_IN, Edge.DIRECTION_OUT);

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

  /// Common logic for setting the value of an element property. Called from [[YTDBVertex]] and
  /// [[YTDBEdge]] implementations with corresponding [[YTDBPropertyFactory]] instances.
  protected <V, P extends YTDBProperty<V>> P writeProperty(
      YTDBPropertyFactory<V, P> propFactory, final String key, final V value) {
    if (key == null) {
      throw Property.Exceptions.propertyKeyCanNotBeNull();
    }
    if (Hidden.isHidden(key)) {
      throw Property.Exceptions.propertyKeyCanNotBeAHiddenKey(key);
    }

    var graphTx = graph.tx();
    graphTx.readWrite();

    final var entity = ((EntityImpl) getRawEntity());

    final V valueToReturn;
    final Object valueToSet;
    if (value == null) {
      valueToSet = null;
      valueToReturn = null;
    } else if (value instanceof LinkBagStub linkBagStub) {
      valueToSet = new LinkBag(graphTx.getDatabaseSession(), linkBagStub);
      //noinspection unchecked
      valueToReturn = (V) valueToSet;
    } else if (value instanceof List<?> || value instanceof Set<?> || value instanceof Map<?, ?>) {
      final var typeInternal = PropertyTypeInternal.getTypeByValue(value);
      if (typeInternal == null) {
        throw new IllegalArgumentException("Unsupported type: " + value.getClass().getName());
      }
      valueToSet = typeInternal.convert(value, graphTx.getDatabaseSession());
      valueToReturn = value;
    } else if (value instanceof YTDBElement ytDBElement) {
      valueToSet = ytDBElement.id();
      valueToReturn = value;
    } else {
      valueToSet = value;
      valueToReturn = value;
    }

    final var type = entity.setPropertyAndReturnType(key, valueToSet);
    return propFactory.create(key, valueToReturn, type, this);
  }

  /// Common logic for reading the value of an element property. Called from
  /// [[YTDBVertex]] and
  /// [[YTDBEdge]] implementations with corresponding
  /// [[YTDBPropertyFactory]] instances.
  protected <V, P extends YTDBProperty<V>> P readProperty(
      YTDBPropertyFactory<V, P> propFactory, String key) {
    graph.tx().readWrite();

    return readFromEntity(propFactory, key, (EntityImpl) getRawEntity(), propFactory.empty());
  }

  /// Common logic for reading the values of multiple element properties. Called from [[YTDBVertex]]
  /// and [[YTDBEdge]] implementations with corresponding [[YTDBPropertyFactory]] instances.
  protected <V, P extends Property<V>> Iterator<P> readProperties(
      YTDBPropertyFactory<V, P> propFactory, final String... propertyKeys) {
    this.graph.tx().readWrite();
    final var entity = ((EntityImpl) getRawEntity());
    final var keysToReturn = propertyKeys.length > 0 ?
        Arrays.stream(propertyKeys) :
        entity.getPropertyNames().stream();

    return keysToReturn
        .map(key -> readFromEntity(propFactory, key, entity, null))
        .filter(Objects::nonNull)
        .iterator();
  }

  @Nullable
  private <V, P extends Property<V>> P readFromEntity(
      YTDBPropertyFactory<V, P> propFactory,
      String key,
      EntityImpl source,
      @Nullable P emptyValue
  ) {
    if (keyIgnored(source, key)) {
      return emptyValue;
    }
    final var valueAndType = source.<V>getPropertyAndType(key);
    return valueAndType == null ?
        emptyValue :
        propFactory.create(key, valueAndType.value(), valueAndType.type(), this);
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
