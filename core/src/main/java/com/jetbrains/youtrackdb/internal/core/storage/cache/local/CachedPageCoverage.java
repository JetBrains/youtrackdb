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

import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ordered coverage for each live incarnation. Callers hold the file stripe through the cache
 * mutation. Lookup takes the same stripe, so insertion, eviction and purge are linearizable.
 */
final class CachedPageCoverage {

  private record Coverage(LogicalPageFileTable.Extents token, TreeSet<Long> pages) {
  }

  private final Map<Integer, Coverage> files = new ConcurrentHashMap<>();
  private final Object[] locks = new Object[256];

  CachedPageCoverage() {
    java.util.Arrays.setAll(locks, index -> new Object());
  }

  Object lock(int fileId) {
    return locks[Integer.hashCode(fileId) & (locks.length - 1)];
  }

  void add(int fileId, long page, LogicalPageFileTable.Extents token) {
    synchronized (lock(fileId)) {
      var coverage = files.get(fileId);
      if (coverage == null || coverage.token() != token) {
        coverage = new Coverage(token, new TreeSet<>());
        files.put(fileId, coverage);
      }
      coverage.pages().add(page);
    }
  }

  void remove(int fileId, long page) {
    synchronized (lock(fileId)) {
      var coverage = files.get(fileId);
      if (coverage != null) {
        coverage.pages().remove(page);
        if (coverage.pages().isEmpty()) {
          files.remove(fileId);
        }
      }
    }
  }

  boolean covers(int fileId, long page, LogicalPageFileTable.Extents token) {
    synchronized (lock(fileId)) {
      var coverage = files.get(fileId);
      return coverage != null && coverage.token() == token
          && coverage.pages().ceiling(page) != null;
    }
  }
}
