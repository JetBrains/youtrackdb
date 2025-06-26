package com.jetbrains.youtrack.db.internal.common.concur.resource;

import com.googlecode.concurrentlinkedhashmap.ConcurrentLinkedHashMap;
import com.googlecode.concurrentlinkedhashmap.EvictionListener;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrack.db.internal.core.YouTrackDBListenerAbstract;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;

public class ResourcePoolFactory<K, T> extends YouTrackDBListenerAbstract {

  private volatile int maxPartitions = Runtime.getRuntime().availableProcessors() << 3;
  private volatile int maxPoolSize = 64;
  private boolean closed = false;

  private final ConcurrentLinkedHashMap<K, ResourcePool<K, T>> poolStore;
  private final ObjectFactoryFactory<K, T> objectFactoryFactory;

  private final EvictionListener<K, ResourcePool<K, T>> evictionListener =
      new EvictionListener<K, ResourcePool<K, T>>() {
        @Override
        public void onEviction(K key, ResourcePool<K, T> partitionedObjectPool) {
          partitionedObjectPool.close();
        }
      };

  public ResourcePoolFactory(final ObjectFactoryFactory<K, T> objectFactoryFactory) {
    this(objectFactoryFactory, 100);
  }

  public ResourcePoolFactory(
      final ObjectFactoryFactory<K, T> objectFactoryFactory, final int capacity) {
    this.objectFactoryFactory = objectFactoryFactory;
    poolStore =
        new ConcurrentLinkedHashMap.Builder<K, ResourcePool<K, T>>()
            .maximumWeightedCapacity(capacity)
            .listener(evictionListener)
            .build();

    YouTrackDBEnginesManager.instance().registerWeakYouTrackDBStartupListener(this);
    YouTrackDBEnginesManager.instance().registerWeakYouTrackDBShutdownListener(this);
  }

  public int getMaxPoolSize() {
    return maxPoolSize;
  }

  public void setMaxPoolSize(final int maxPoolSize) {
    checkForClose();

    this.maxPoolSize = maxPoolSize;
  }

  public ResourcePool<K, T> get(final K key) {
    checkForClose();

    var pool = poolStore.get(key);
    if (pool != null) {
      return pool;
    }

    pool = new ResourcePool<K, T>(maxPoolSize, objectFactoryFactory.create(key));

    final var oldPool = poolStore.putIfAbsent(key, pool);
    if (oldPool != null) {
      pool.close();
      return oldPool;
    }

    return pool;
  }

  public int getMaxPartitions() {
    return maxPartitions;
  }

  public void setMaxPartitions(final int maxPartitions) {
    this.maxPartitions = maxPartitions;
  }

  public Collection<ResourcePool<K, T>> getPools() {
    checkForClose();

    return Collections.unmodifiableCollection(poolStore.values());
  }

  public void close() {
    if (closed) {
      return;
    }

    closed = true;

    while (!poolStore.isEmpty()) {
      final var poolIterator = poolStore.values().iterator();

      while (poolIterator.hasNext()) {
        final var pool = poolIterator.next();

        try {
          pool.close();
        } catch (Exception e) {
          LogManager.instance().error(this, "Error during pool close", e);
        }

        poolIterator.remove();
      }
    }

    for (var pool : poolStore.values()) {
      pool.close();
    }

    poolStore.clear();
  }

  @Override
  public void onShutdown() {
    close();
  }

  private void checkForClose() {
    if (closed) {
      throw new IllegalStateException("Pool factory is closed");
    }
  }

  public interface ObjectFactoryFactory<K, T> {

    ResourcePoolListener<K, T> create(K key);
  }
}
