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

package com.jetbrains.youtrack.db.internal.common.concur.lock;

import com.jetbrains.youtrack.db.internal.common.types.ModifiableInteger;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.AbstractOwnableSynchronizer;
import java.util.concurrent.locks.LockSupport;

/**
 * @since 8/18/14
 */
public class ReadersWriterSpinLock extends AbstractOwnableSynchronizer {

  private static final long serialVersionUID = 7975120282194559960L;

  private final transient LongAdder distributedCounter;
  private final transient AtomicReference<WNode> tail = new AtomicReference<WNode>();
  private final transient ThreadLocal<ModifiableInteger> lockHolds = new InitOModifiableInteger();

  private final transient ThreadLocal<WNode> myNode = new InitWNode();

  public ReadersWriterSpinLock() {
    final var wNode = new WNode();
    wNode.locked = false;

    tail.set(wNode);

    distributedCounter = new LongAdder();
  }

  /**
   * Tries to acquire lock during provided interval of time and returns either if provided time
   * interval was passed or if lock was acquired.
   *
   * @param timeout Timeout during of which we should wait for read lock.
   * @return <code>true</code> if read lock was acquired.
   */
  public boolean tryAcquireReadLock(long timeout) {
    final var lHolds = lockHolds.get();

    final var holds = lHolds.intValue();
    if (holds > 0) {
      // we have already acquire read lock
      lHolds.increment();
      return true;
    } else if (holds < 0) {
      // write lock is acquired before, do nothing
      return true;
    }

    distributedCounter.increment();

    var wNode = tail.get();

    final var start = System.nanoTime();
    while (wNode.locked) {
      distributedCounter.decrement();

      while (wNode.locked && wNode == tail.get()) {
        wNode.waitingReaders.put(Thread.currentThread(), Boolean.TRUE);

        if (wNode.locked && wNode == tail.get()) {
          final var parkTimeout = timeout - (System.nanoTime() - start);
          if (parkTimeout > 0) {
            LockSupport.parkNanos(this, parkTimeout);
          } else {
            return false;
          }
        }

        wNode = tail.get();

        if (System.nanoTime() - start > timeout) {
          return false;
        }
      }

      distributedCounter.increment();

      wNode = tail.get();
      if (System.nanoTime() - start > timeout) {
        distributedCounter.decrement();

        return false;
      }
    }

    lHolds.increment();
    assert lHolds.intValue() == 1;

    return true;
  }

  public void acquireReadLock() {
    final var lHolds = lockHolds.get();

    final var holds = lHolds.intValue();
    if (holds > 0) {
      // we have already acquire read lock
      lHolds.increment();
      return;
    } else if (holds < 0) {
      // write lock is acquired before, do nothing
      return;
    }

    distributedCounter.increment();

    var wNode = tail.get();
    while (wNode.locked) {
      distributedCounter.decrement();

      while (wNode.locked && wNode == tail.get()) {
        wNode.waitingReaders.put(Thread.currentThread(), Boolean.TRUE);

        if (wNode.locked && wNode == tail.get()) {
          LockSupport.park(this);
        }

        wNode = tail.get();
      }

      distributedCounter.increment();

      wNode = tail.get();
    }

    lHolds.increment();
    assert lHolds.intValue() == 1;
  }

  public void releaseReadLock() {
    final var lHolds = lockHolds.get();
    final var holds = lHolds.intValue();
    if (holds > 1) {
      lHolds.decrement();
      return;
    } else if (holds < 0) {
      // write lock was acquired before, do nothing
      return;
    }

    distributedCounter.decrement();

    lHolds.decrement();
    assert lHolds.intValue() == 0;
  }

  public void acquireWriteLock() {
    final var lHolds = lockHolds.get();

    if (lHolds.intValue() < 0) {
      lHolds.decrement();
      return;
    }

    final var node = myNode.get();
    node.locked = true;

    final var pNode = tail.getAndSet(myNode.get());

    while (pNode.locked) {
      pNode.waitingWriter = Thread.currentThread();

      if (pNode.locked) {
        LockSupport.park(this);
      }
    }

    pNode.waitingWriter = null;

    while (distributedCounter.sum() != 0) {
      Thread.yield();
    }

    setExclusiveOwnerThread(Thread.currentThread());

    lHolds.decrement();
    assert lHolds.intValue() == -1;
  }

  public void releaseWriteLock() {
    final var lHolds = lockHolds.get();

    if (lHolds.intValue() < -1) {
      lHolds.increment();
      return;
    }

    setExclusiveOwnerThread(null);

    final var node = myNode.get();
    myNode.set(new WNode());
    node.locked = false;

    final var waitingWriter = node.waitingWriter;
    if (waitingWriter != null) {
      LockSupport.unpark(waitingWriter);
    }

    while (!node.waitingReaders.isEmpty()) {
      final Set<Thread> readers = node.waitingReaders.keySet();
      final var threadIterator = readers.iterator();

      while (threadIterator.hasNext()) {
        final var reader = threadIterator.next();
        threadIterator.remove();

        LockSupport.unpark(reader);
      }
    }

    lHolds.increment();
    assert lHolds.intValue() == 0;
  }

  private static final class InitWNode extends ThreadLocal<WNode> {

    @Override
    protected WNode initialValue() {
      return new WNode();
    }
  }

  private static final class InitOModifiableInteger extends ThreadLocal<ModifiableInteger> {

    @Override
    protected ModifiableInteger initialValue() {
      return new ModifiableInteger();
    }
  }

  private static final class WNode {

    private final ConcurrentHashMap<Thread, Boolean> waitingReaders =
        new ConcurrentHashMap<Thread, Boolean>();

    private volatile boolean locked = true;
    private volatile Thread waitingWriter;
  }
}
