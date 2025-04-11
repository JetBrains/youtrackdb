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
package com.jetbrains.youtrack.db.internal.common.concur.resource;

import com.jetbrains.youtrack.db.internal.common.concur.lock.LockException;
import com.jetbrains.youtrack.db.internal.core.YouTrackDBShutdownListener;
import com.jetbrains.youtrack.db.internal.core.YouTrackDBStartupListener;
import com.jetbrains.youtrack.db.internal.core.YouTrackDBEnginesManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reentrant implementation of Resource Pool. It manages multiple resource acquisition on thread
 * local map. If you're looking for a Reentrant implementation look at #ReentrantResourcePool.
 *
 * @see ResourcePool
 */
public class ReentrantResourcePool<K, V> extends ResourcePool<K, V>
    implements YouTrackDBStartupListener, YouTrackDBShutdownListener {

  private volatile ThreadLocal<Map<K, ResourceHolder<V>>> activeResources =
      new ThreadLocal<Map<K, ResourceHolder<V>>>();

  private static final class ResourceHolder<V> {

    private final V resource;
    private int counter = 1;

    private ResourceHolder(V resource) {
      this.resource = resource;
    }
  }

  public ReentrantResourcePool(
      final int maxResources, final ResourcePoolListener<K, V> listener) {
    super(maxResources, listener);

    YouTrackDBEnginesManager.instance().registerWeakYouTrackDBShutdownListener(this);
    YouTrackDBEnginesManager.instance().registerWeakYouTrackDBStartupListener(this);
  }

  @Override
  public void onShutdown() {
    activeResources = null;
  }

  @Override
  public void onStartup() {
    if (activeResources == null) {
      activeResources = new ThreadLocal<Map<K, ResourceHolder<V>>>();
    }
  }

  public V getResource(K key, final long maxWaitMillis, Object... additionalArgs)
      throws LockException {
    var resourceHolderMap = activeResources.get();

    if (resourceHolderMap == null) {
      resourceHolderMap = new HashMap<K, ResourceHolder<V>>();
      activeResources.set(resourceHolderMap);
    }

    final var holder = resourceHolderMap.get(key);
    if (holder != null) {
      holder.counter++;
      return holder.resource;
    }
    try {
      final var res = super.getResource(key, maxWaitMillis, additionalArgs);
      resourceHolderMap.put(key, new ResourceHolder<V>(res));
      return res;

    } catch (RuntimeException e) {
      resourceHolderMap.remove(key);

      // PROPAGATE IT
      throw e;
    }
  }

  public boolean returnResource(final V res) {
    final var resourceHolderMap = activeResources.get();
    if (resourceHolderMap != null) {
      K keyToRemove = null;
      for (var entry : resourceHolderMap.entrySet()) {
        final var holder = entry.getValue();
        if (holder.resource.equals(res)) {
          holder.counter--;
          assert holder.counter >= 0;
          if (holder.counter > 0) {
            return false;
          }

          keyToRemove = entry.getKey();
          break;
        }
      }

      resourceHolderMap.remove(keyToRemove);
    }

    return super.returnResource(res);
  }

  public int getConnectionsInCurrentThread(final K key) {
    final var resourceHolderMap = activeResources.get();
    if (resourceHolderMap == null) {
      return 0;
    }

    final var holder = resourceHolderMap.get(key);
    if (holder == null) {
      return 0;
    }

    return holder.counter;
  }

  public void remove(final V res) {
    this.resources.remove(res);

    final List<K> activeResourcesToRemove = new ArrayList<K>();
    final var activeResourcesMap = activeResources.get();

    if (activeResourcesMap != null) {
      for (var entry : activeResourcesMap.entrySet()) {
        final var holder = entry.getValue();
        if (holder.resource.equals(res)) {
          activeResourcesToRemove.add(entry.getKey());
        }
      }

      for (var resourceKey : activeResourcesToRemove) {
        activeResourcesMap.remove(resourceKey);
        sem.release();
      }
    }
  }
}
