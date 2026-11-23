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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.common.concur.lock.PartitionedLockManager;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.Test;

public class CachedPageCoverageTest {

  // A shared lookup lock blocks same-file mutations but permits readers and other partitions.
  @Test
  public void sharedLookupLockBlocksMutationButNotOtherPartitions() throws Exception {
    var coverage = new CachedPageCoverage();
    var token = new LogicalPageFileTable.Extents(10, 0);
    var field = CachedPageCoverage.class.getDeclaredField("locks");
    field.setAccessible(true);
    @SuppressWarnings("unchecked")
    var locks = (PartitionedLockManager<Integer>) field.get(coverage);
    var first = coverage.acquireExclusiveLock(1);
    var second = coverage.acquireExclusiveLock(2);
    try {
      assertNotSame(first, second);
      // add() and remove() must safely re-enter a caller's exclusive lock.
      coverage.add(1, 2, token);
      coverage.remove(1, 2);
    } finally {
      second.unlock();
      first.unlock();
    }
    try (var executor = Executors.newFixedThreadPool(2)) {
      var shared = locks.acquireSharedLock(1);
      var started = new CountDownLatch(1);
      java.util.concurrent.Future<?> mutation = null;
      try {
        // Check shared readers before queuing a writer, which can bar later readers.
        assertFalse(executor.submit(() -> coverage.covers(1, 0, token))
            .get(10, TimeUnit.SECONDS));
        mutation = executor.submit(() -> {
          started.countDown();
          coverage.add(1, 8, token);
        });
        assertTrue(started.await(10, TimeUnit.SECONDS));
        final var pendingMutation = mutation;
        assertThrows(TimeoutException.class, () -> pendingMutation.get(100, TimeUnit.MILLISECONDS));
        executor.submit(() -> coverage.add(2, 9, token)).get(10, TimeUnit.SECONDS);
        assertTrue(coverage.covers(2, 9, token));
      } finally {
        shared.unlock();
      }
      mutation.get(10, TimeUnit.SECONDS);
      assertTrue(coverage.covers(1, 8, token));
    }
  }

  // Coverage is an ordered suffix query, isolated by file and incarnation, and removed on purge.
  @Test
  public void coverageTracksInsertionRemovalAndReplacement() {
    var coverage = new CachedPageCoverage();
    var first = new LogicalPageFileTable.Extents(10, 0);
    var next = new LogicalPageFileTable.Extents(10, 0);
    assertFalse(coverage.covers(1, 0, first));
    coverage.add(1, 2, first);
    coverage.add(1, 8, first);
    coverage.add(2, 9, first);
    assertTrue(coverage.covers(1, 8, first));
    assertFalse(coverage.covers(1, 9, first));
    assertFalse(coverage.covers(1, 0, next));
    coverage.remove(1, 8);
    assertFalse(coverage.covers(1, 3, first));
    assertTrue(coverage.covers(1, 2, first));
    coverage.add(1, 5, next);
    assertFalse(coverage.covers(1, 0, first));
    assertTrue(coverage.covers(1, 5, next));
    coverage.remove(1, 5);
    coverage.remove(1, 5);
    assertFalse(coverage.covers(1, 0, next));
    assertTrue(coverage.covers(2, 9, first));
  }
}
