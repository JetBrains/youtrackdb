package com.jetbrains.youtrackdb.internal.core.gremlin;

import static com.google.common.base.Preconditions.checkNotNull;

import com.jetbrains.youtrackdb.api.gremlin.embedded.YTDBElement;
import com.jetbrains.youtrackdb.api.gremlin.embedded.YTDBProperty;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Edge;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Entity;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;
import org.apache.tinkerpop.gremlin.structure.Graph.Hidden;
import org.apache.tinkerpop.gremlin.structure.Property;
import org.apache.tinkerpop.gremlin.structure.util.ElementHelper;

public abstract class YTDBElementImpl implements YTDBElement {
  private static final int COLLECTED_THREAD_CLEANUP_LIMIT = 16;
  private static final char INTERNAL_PREFIX = '@';
  private static final List<String> EDGE_LINK_FIELDS =
      List.of(Edge.DIRECTION_IN, Edge.DIRECTION_OUT);

  @Nullable private final Entity fastPathEntity;

  // Fallback infrastructure stays absent for wrappers that always use their direct entity.
  @Nullable private volatile HolderInfrastructure holderInfrastructure;

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

  /// Common logic for reading the value of an element property. Called from [[YTDBVertex]] and
  /// [[YTDBEdge]] implementations with corresponding [[YTDBPropertyFactory]] instances.
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
    final var keysToReturn =
        propertyKeys.length > 0 ? Arrays.stream(propertyKeys) : entity.getPropertyNames().stream();

    return keysToReturn
        .map(key -> readFromEntity(propFactory, key, entity, null))
        .filter(Objects::nonNull)
        .iterator();
  }

  @Nullable private <V, P extends Property<V>> P readFromEntity(
      YTDBPropertyFactory<V, P> propFactory,
      String key,
      EntityImpl source,
      @Nullable P emptyValue) {
    if (keyIgnored(source, key)) {
      return emptyValue;
    }
    final var valueAndType = source.<V>getPropertyAndType(key);
    return valueAndType == null ? emptyValue
        : propFactory.create(key, valueAndType.value(), valueAndType.type(), this);
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

      var infrastructure = holderInfrastructure();
      cleanCollectedThreadHolders(infrastructure);

      var holderReference = infrastructure.threadLocalHolder.get();
      var holder = holderReference == null ? null : holderReference.get();
      if (holder == null) {
        holder = new EntityHolder(tx.loadEntity(rid));
        infrastructure.entityHolders.put(
            new ThreadReference(Thread.currentThread(), infrastructure.collectedThreads), holder);
        infrastructure.threadLocalHolder.set(new WeakReference<>(holder));
      } else if (holder.entity.isNotBound(session)) {
        holder.entity = tx.load(holder.entity);
      }

      return holder.entity;
    } else {
      return fastPathEntity;
    }
  }

  private HolderInfrastructure holderInfrastructure() {
    var infrastructure = holderInfrastructure;
    if (infrastructure == null) {
      synchronized (this) {
        infrastructure = holderInfrastructure;
        if (infrastructure == null) {
          infrastructure = new HolderInfrastructure();
          holderInfrastructure = infrastructure;
        }
      }
    }
    return infrastructure;
  }

  private static void cleanCollectedThreadHolders(HolderInfrastructure infrastructure) {
    // Bound cleanup work so one access never scans all accumulated holders.
    for (var cleaned = 0; cleaned < COLLECTED_THREAD_CLEANUP_LIMIT; cleaned++) {
      var threadReference = infrastructure.collectedThreads.poll();
      if (threadReference == null) {
        return;
      }

      infrastructure.entityHolders.remove(threadReference);
    }
  }

  private static final class HolderInfrastructure {
    // The thread stores only a weak holder reference. The wrapper owns holders and their entities.
    private final ThreadLocal<WeakReference<EntityHolder>> threadLocalHolder = new ThreadLocal<>();
    private final ReferenceQueue<Thread> collectedThreads = new ReferenceQueue<>();
    // Weak thread keys prevent a live wrapper from retaining terminated threads.
    private final Map<ThreadReference, EntityHolder> entityHolders = new ConcurrentHashMap<>();
  }

  private static final class EntityHolder {
    private Entity entity;

    private EntityHolder(Entity entity) {
      this.entity = entity;
    }
  }

  private static final class ThreadReference extends WeakReference<Thread> {
    private ThreadReference(Thread thread, ReferenceQueue<Thread> collectedThreads) {
      super(thread, collectedThreads);
    }
  }

  private static boolean keyExists(Entity entity, String key) {
    return !keyIgnored(entity, key) && entity.hasProperty(key);
  }

  private static boolean keyIgnored(Entity entity, String key) {
    return key == null || key.isEmpty() ||
        key.charAt(0) == INTERNAL_PREFIX ||
        (entity.isEdge() && EDGE_LINK_FIELDS.contains(key));
  }
}
