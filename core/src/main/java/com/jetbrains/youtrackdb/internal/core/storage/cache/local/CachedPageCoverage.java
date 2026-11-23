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
package com.jetbrains.youtrackdb.internal.core.storage.cache.local;

import com.jetbrains.youtrackdb.internal.common.concur.lock.PartitionedLockManager;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;

/**
 * Ordered coverage for each live incarnation. Callers hold the exclusive file partition lock
 * through the cache mutation. Lookup takes the shared lock, so insertion, eviction and purge are
 * linearizable. This manager is separate from the page-key manager to preserve lock order.
 */
final class CachedPageCoverage {

  private record Coverage(LogicalPageFileTable.Extents token, TreeSet<Long> pages) {
  }

  private final Map<Integer, Coverage> files = new ConcurrentHashMap<>();
  private final PartitionedLockManager<Integer> locks = new PartitionedLockManager<>();

  Lock acquireExclusiveLock(int fileId) {
    return locks.acquireExclusiveLock(fileId);
  }

  void add(int fileId, long page, LogicalPageFileTable.Extents token) {
    // Reentrant acquisition also supports callers that hold the lock through a cache mutation.
    final var lock = acquireExclusiveLock(fileId);
    try {
      var coverage = files.get(fileId);
      if (coverage == null || coverage.token() != token) {
        coverage = new Coverage(token, new TreeSet<>());
        files.put(fileId, coverage);
      }
      coverage.pages().add(page);
    } finally {
      lock.unlock();
    }
  }

  void remove(int fileId, long page) {
    // Re-enter the caller's exclusive lock when removing a cached image and its coverage.
    final var lock = acquireExclusiveLock(fileId);
    try {
      var coverage = files.get(fileId);
      if (coverage != null) {
        coverage.pages().remove(page);
        if (coverage.pages().isEmpty()) {
          files.remove(fileId);
        }
      }
    } finally {
      lock.unlock();
    }
  }

  boolean covers(int fileId, long page, LogicalPageFileTable.Extents token) {
    final var lock = locks.acquireSharedLock(fileId);
    try {
      var coverage = files.get(fileId);
      return coverage != null && coverage.token() == token
          && coverage.pages().ceiling(page) != null;
    } finally {
      lock.unlock();
    }
  }
}
