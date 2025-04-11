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
package com.jetbrains.youtrack.db.internal.core.db;

import com.googlecode.concurrentlinkedhashmap.ConcurrentLinkedHashMap;
import com.googlecode.concurrentlinkedhashmap.EvictionListener;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrack.db.internal.core.YouTrackDBListenerAbstract;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;

/**
 * Factory for {@link PartitionedDatabasePool} pool, which also works as LRU cache with good
 * mutlicore architecture support.
 *
 * <p>In case of remote storage database pool will keep connections to the remote storage till you
 * close pool. So in case of remote storage you should close pool factory at the end of it's usage,
 * it also may be closed on application shutdown but you should not rely on this behaviour.
 *
 * @since 06/11/14
 */
public class PartitionedDatabasePoolFactory extends YouTrackDBListenerAbstract {

  private volatile int maxPoolSize = 64;
  private boolean closed = false;

  private final ConcurrentLinkedHashMap<PoolIdentity, PartitionedDatabasePool> poolStore;

  private final EvictionListener<PoolIdentity, PartitionedDatabasePool> evictionListener =
      new EvictionListener<PoolIdentity, PartitionedDatabasePool>() {
        @Override
        public void onEviction(
            final PoolIdentity poolIdentity,
            final PartitionedDatabasePool partitionedDatabasePool) {
          partitionedDatabasePool.close();
        }
      };

  public PartitionedDatabasePoolFactory() {
    this(100);
  }

  public PartitionedDatabasePoolFactory(final int capacity) {
    poolStore =
        new ConcurrentLinkedHashMap.Builder<PoolIdentity, PartitionedDatabasePool>()
            .maximumWeightedCapacity(capacity)
            .listener(evictionListener)
            .build();

    YouTrackDBEnginesManager.instance().registerWeakYouTrackDBStartupListener(this);
    YouTrackDBEnginesManager.instance().registerWeakYouTrackDBShutdownListener(this);
  }

  public int getMaxPoolSize() {
    return maxPoolSize;
  }

  public void setMaxPoolSize(int maxPoolSize) {
    checkForClose();

    this.maxPoolSize = maxPoolSize;
  }

  public void reset() {
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

  public PartitionedDatabasePool get(
      final String url, final String userName, final String userPassword) {
    checkForClose();

    final var poolIdentity = new PoolIdentity(url, userName, userPassword);

    var pool = poolStore.get(poolIdentity);
    if (pool != null && !pool.isClosed()) {
      return pool;
    }

    if (pool != null) {
      poolStore.remove(poolIdentity, pool);
    }

    while (true) {
      pool = new PartitionedDatabasePool(url, userName, userPassword, 8, maxPoolSize);

      final var oldPool = poolStore.putIfAbsent(poolIdentity, pool);

      if (oldPool != null) {
        if (!oldPool.isClosed()) {
          return oldPool;
        } else {
          poolStore.remove(poolIdentity, oldPool);
        }
      } else {
        return pool;
      }
    }
  }

  public Collection<PartitionedDatabasePool> getPools() {
    checkForClose();

    return Collections.unmodifiableCollection(poolStore.values());
  }

  public void close() {
    if (closed) {
      return;
    }

    closed = true;
    reset();
  }

  private void checkForClose() {
    if (closed) {
      throw new IllegalStateException("Pool factory is closed");
    }
  }

  public boolean isClosed() {
    return closed;
  }

  @Override
  public void onShutdown() {
    close();
  }

  private static final class PoolIdentity {

    private final String url;
    private final String userName;
    private final String userPassword;

    private PoolIdentity(String url, String userName, String userPassword) {
      this.url = url;
      this.userName = userName;
      this.userPassword = userPassword;
    }

    @Override
    public boolean equals(final Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }

      final var that = (PoolIdentity) o;

      if (!url.equals(that.url)) {
        return false;
      }
      if (!userName.equals(that.userName)) {
        return false;
      }
      return userPassword.equals(that.userPassword);
    }

    @Override
    public int hashCode() {
      var result = url.hashCode();
      result = 31 * result + userName.hashCode();
      result = 31 * result + userPassword.hashCode();
      return result;
    }
  }
}
