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
 */

package com.jetbrains.youtrack.db.internal.common.concur.lock;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.Lock;
import org.junit.Before;
import org.junit.Test;

/**
 *
 */
public class OneEntryPerKeyLockManagerNullKeysTest {

  private OneEntryPerKeyLockManager<String> manager;

  @Before
  public void before() {
    manager = new OneEntryPerKeyLockManager<String>(true, -1, 100);
  }

  @Test
  public void testNullKeysInCollectionBatch() {
    final List<String> keys = new ArrayList<String>();
    keys.add(null);
    keys.add("key");
    keys.add(null);

    final var locks = manager.acquireExclusiveLocksInBatch(keys);
    assertEquals(keys.size(), locks.length);
    assertEquals(2, wrapper(locks[0]).getLockCount());
    assertEquals(2, wrapper(locks[1]).getLockCount());
    assertEquals(1, wrapper(locks[2]).getLockCount());

    for (var lock : locks) {
      lock.unlock();
    }
    assertEquals(0, wrapper(locks[0]).getLockCount());
    assertEquals(0, wrapper(locks[1]).getLockCount());
    assertEquals(0, wrapper(locks[2]).getLockCount());
  }

  @Test
  public void testNullKeysInArrayBatch() {
    final var keys = new String[]{null, "key", null};

    final var locks = manager.acquireExclusiveLocksInBatch(keys);
    assertEquals(keys.length, locks.length);
    assertEquals(2, wrapper(locks[0]).getLockCount());
    assertEquals(2, wrapper(locks[1]).getLockCount());
    assertEquals(1, wrapper(locks[2]).getLockCount());

    for (var lock : locks) {
      lock.unlock();
    }
    assertEquals(0, wrapper(locks[0]).getLockCount());
    assertEquals(0, wrapper(locks[1]).getLockCount());
    assertEquals(0, wrapper(locks[2]).getLockCount());
  }

  @Test
  public void testNullKeyExclusive() {
    manager.acquireExclusiveLock(null);
    final var lock = manager.acquireExclusiveLock(null);
    assertEquals(2, wrapper(lock).getLockCount());
    lock.unlock();
    assertEquals(1, wrapper(lock).getLockCount());
    manager.releaseExclusiveLock(null);
    assertEquals(0, wrapper(lock).getLockCount());
  }

  @Test
  public void testNullKeyShared() {
    manager.acquireSharedLock(null);
    final var lock = manager.acquireSharedLock(null);
    assertEquals(2, wrapper(lock).getLockCount());
    lock.unlock();
    assertEquals(1, wrapper(lock).getLockCount());
    manager.releaseSharedLock(null);
    assertEquals(0, wrapper(lock).getLockCount());
  }

  private static OneEntryPerKeyLockManager.CountableLockWrapper wrapper(Lock lock) {
    return (OneEntryPerKeyLockManager.CountableLockWrapper) lock;
  }
}
