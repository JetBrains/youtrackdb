/**
 * **************************************************************************** Copyright (c)
 * 2012-2013, Pedro Ramalhete, Andreia Correia All rights reserved.
 *
 * <p>Redistribution and use in source and binary forms, with or without modification, are
 * permitted provided that the following conditions are met: * Redistributions of source code must
 * retain the above copyright notice, this list of conditions and the following disclaimer. *
 * Redistributions in binary form must reproduce the above copyright notice, this list of conditions
 * and the following disclaimer in the documentation and/or other materials provided with the
 * distribution. * Neither the name of Concurrency Freaks nor the names of its contributors may be
 * used to endorse or promote products derived from this software without specific prior written
 * permission.
 *
 * <p>THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS
 * OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY
 * AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL <COPYRIGHT HOLDER> BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA,
 * OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF
 * THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 * *****************************************************************************
 */
package com.jetbrains.youtrackdb.internal.common.concur.lock;

import com.jetbrains.youtrackdb.internal.core.exception.BaseException;
import com.jetbrains.youtrackdb.internal.core.exception.DatabaseException;
import java.lang.ref.Cleaner;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.StampedLock;
import java.util.function.BooleanSupplier;

/**
 * <h1>Scalable Read-Write Lock </h1>
 * <p>
 * A Read-Write Lock that is scalable with the number of threads doing Read. Uses a
 * two-state-machine for the Readers, and averages two synchronized operations. <br> Although this
 * mechanism was independently designed and implemented by the authors, the idea is very similar to
 * the algorithm C-RW-WP described in this paper: <a
 * href="http://blogs.oracle.com/dave/resource/ppopp13-dice-NUMAAwareRWLocks.pdf">NUMA-Aware
 * Reader-Writer locks</a> <br> Relative to the paper, there are two differences: The threads have
 * no particular order, which means this implementation is <b>not</b> NUMA-aware; Threads attempting
 * a read-lock for the first time are added to a list and removed when the thread terminates,
 * following the mechanism described below. To manage the adding and removal of new Reader threads,
 * we use a ConcurrentLinkedQueue instance named {@code readersStateList} containing all the
 * references to ReadersEntry (Reader's states), which the Writer scans to determine if the Readers
 * have completed or not. After a thread terminates and its {@code ReadersEntry} becomes
 * unreachable, a {@link Cleaner} action removes the Reader's state reference from the
 * {@code readersStateList}, to avoid memory leaking. Advantages:
 *
 * <ul>
 *   <li>Implements {@code java.util.concurrent.locks.ReadWriteLock}
 *   <li>When there are very few Writes, the performance scales with the number of Reader threads
 *   <li>No need to call initialization/cleanup functions per thread
 *   <li>No limitation on the number of concurrent threads
 * </ul>
 * <p>
 * Disadvantages:
 *
 * <ul>
 *   <li>Not Reentrant
 *   <li>Has Writer-Preference
 *   <li>Memory footprint increases with number of threads by sizeof(ReadersEntry) x O(N_threads)
 *   <li>Does not support {@code lockInterruptibly()}
 *   <li>Does not support {@code newCondition()}
 * </ul>
 * <p>
 * For scenarios with few writes, the average case for {@code sharedLock()} is two synchronized
 * calls: an {@code AtomicInteger.set()} on a cache line that is held in exclusive mode by the core
 * where the current thread is running, and an {@code AtomicLong.get()} on a shared cache line.<br>
 * This means that when doing several sequential calls of sharedLock()/unlock() on the same
 * instance, the performance penalty will be small because the accessed variables will most likely
 * be in L1/L2 cache.
 */
public class ScalableRWLock implements ReadWriteLock, java.io.Serializable {

  private static final long serialVersionUID = -7552055681918630764L;

  private static final int SRWL_STATE_NOT_READING = 0;
  private static final int SRWL_STATE_READING = 1;
  private static final AtomicInteger[] dummyArray = new AtomicInteger[0];

  /** Cleaner used to remove reader state when ReadersEntry becomes unreachable. */
  private static final Cleaner CLEANER = Cleaner.create();

  /**
   * List of Reader's states that the Writer will scan when attempting to acquire the lock in
   * write-mode
   */
  private final transient ConcurrentLinkedQueue<AtomicInteger> readersStateList;

  /**
   * The thread-id of the Writer currently holding the lock in write-mode, or SRWL_INVALID_TID if
   * there is no Writer holding or attempting to acquire the lock in write mode.
   */
  private final transient StampedLock stampedLock;

  /**
   * Thread-local reference to the current thread's ReadersEntry instance. It's from this instance
   * that the current Reader thread is able to determine where to store its own state, and the
   * number of reentrant read lock loops for that particular thread.
   */
  private final transient ThreadLocal<ReadersEntry> entry;

  private final transient AtomicReference<AtomicInteger[]> readersStateArrayRef;

  /**
   * The lock returned by method {@link ScalableRWLock#readLock}.
   */
  private final InnerReadLock readerLock;

  /**
   * The lock returned by method {@link ScalableRWLock#writeLock}.
   */
  private final InnerWriteLock writerLock;

  /**
   * Holds the per-thread reader state. When this entry becomes unreachable (e.g. after the owning
   * thread terminates), a {@link Cleaner} action removes its state from {@code readersStateList}.
   */
  static final class ReadersEntry {

    public final AtomicInteger state;

    public ReadersEntry(AtomicInteger state) {
      this.state = state;
    }
  }

  /**
   * Cleanup action registered with the {@link Cleaner}. Captures only the specific fields needed
   * for cleanup — must NOT reference the {@link ReadersEntry} itself (otherwise the entry would
   * never become phantom-reachable) nor the {@link ScalableRWLock} instance (otherwise the
   * lock's {@link ThreadLocal} would keep the entry reachable, creating a memory leak in
   * long-lived thread pools).
   */
  private static class CleanupAction implements Runnable {

    private final ConcurrentLinkedQueue<AtomicInteger> readersStateList;
    private final AtomicReference<AtomicInteger[]> readersStateArrayRef;
    private final AtomicInteger state;

    CleanupAction(
        ConcurrentLinkedQueue<AtomicInteger> readersStateList,
        AtomicReference<AtomicInteger[]> readersStateArrayRef,
        AtomicInteger state) {
      this.readersStateList = readersStateList;
      this.readersStateArrayRef = readersStateArrayRef;
      this.state = state;
    }

    @Override
    public void run() {
      readersStateList.remove(state);
      readersStateArrayRef.set(null);
      // Paranoia: just in case someone forgot to call sharedUnlock()
      // and there is a Writer waiting on that state
      state.set(SRWL_STATE_NOT_READING);
    }
  }

  /**
   * Read-only lock
   */
  final class InnerReadLock implements Lock {

    @Override
    public void lock() {
      sharedLock();
    }

    @Override
    public void unlock() {
      sharedUnlock();
    }

    @Override
    public boolean tryLock() {
      return sharedTryLock();
    }

    @Override
    public boolean tryLock(long timeout, TimeUnit unit) throws java.lang.InterruptedException {
      if (Thread.interrupted()) {
        throw new java.lang.InterruptedException();
      }
      return sharedTryLockNanos(unit.toNanos(timeout));
    }

    @Override
    public void lockInterruptibly() {
      // Not supported
      throw new UnsupportedOperationException();
    }

    @Override
    public Condition newCondition() {
      // Not supported
      throw new UnsupportedOperationException();
    }
  }

  /**
   * Write-only lock
   */
  final class InnerWriteLock implements Lock {

    @Override
    public void lock() {
      exclusiveLock();
    }

    @Override
    public void unlock() {
      exclusiveUnlock();
    }

    @Override
    public boolean tryLock() {
      return exclusiveTryLock();
    }

    @Override
    public boolean tryLock(long timeout, TimeUnit unit) throws java.lang.InterruptedException {
      if (Thread.interrupted()) {
        throw new java.lang.InterruptedException();
      }
      return exclusiveTryLockNanos(unit.toNanos(timeout));
    }

    @Override
    public void lockInterruptibly() {
      // Not supported
      throw new UnsupportedOperationException();
    }

    @Override
    public Condition newCondition() {
      // Not supported
      throw new UnsupportedOperationException();
    }
  }

  /**
   * Default constructor
   */
  public ScalableRWLock() {
    // States of the Readers, one entry in the list per thread
    readersStateList = new ConcurrentLinkedQueue<AtomicInteger>();

    stampedLock = new StampedLock();

    // Default value for "entry" is null which is ok because, the thread
    // calling the constructor may never attempt to read-lock this
    // instance and, therefore, there is not point in allocating an
    // instance of ReadersEntry for it.
    entry = new ThreadLocal<ReadersEntry>();

    readersStateArrayRef = new AtomicReference<AtomicInteger[]>(null);

    readerLock = new ScalableRWLock.InnerReadLock();
    writerLock = new ScalableRWLock.InnerWriteLock();
  }

  @Override
  public Lock readLock() {
    return readerLock;
  }

  @Override
  public Lock writeLock() {
    return writerLock;
  }

  /**
   * Whether the exclusive (write) lock is currently held by some thread. The underlying
   * {@link StampedLock} tracks no owner, so this cannot distinguish the current thread from
   * another holder; it exists for assertions that a code path runs inside an exclusive-lock
   * window (a caller that must itself hold the lock cannot be foiled by another holder anyway,
   * because that holder would have blocked it).
   */
  public boolean isWriteLocked() {
    return stampedLock.isWriteLocked();
  }

  /**
   * Creates a new ReadersEntry instance for the current thread and its associated AtomicInteger to
   * store the state of the Reader
   *
   * @return Returns a reference to the newly created instance of {@code ReadersEntry}
   */
  private ReadersEntry addState() {
    final var state = new AtomicInteger(SRWL_STATE_NOT_READING);
    final var newEntry = new ReadersEntry(state);
    // Register cleanup before the entry is reachable from the ThreadLocal, so that when the
    // thread dies and the entry becomes phantom-reachable, the Cleaner removes the state.
    // If the thread dies between entry.set() and readersStateList.add(), the Cleaner fires
    // a harmless no-op remove() because the state is still SRWL_STATE_NOT_READING and was
    // never added to the list, so no writer can spin on it.
    CLEANER.register(newEntry,
        new CleanupAction(readersStateList, readersStateArrayRef, state));
    entry.set(newEntry);
    readersStateList.add(state);
    readersStateArrayRef.set(null);
    return newEntry;
  }

  /**
   * Acquires the read lock.
   *
   * <p>Acquires the read lock if the write lock is not held by another thread and returns
   * immediately.
   *
   * <p>If the write lock is held by another thread then the current thread yields until the write
   * lock is released.
   */
  public void sharedLock() {
    var localEntry = entry.get();
    // Initialize a new Reader-state for this thread if needed
    if (localEntry == null) {
      localEntry = addState();
    }

    final var currentReadersState = localEntry.state;
    // The "optimistic" code path takes only two synchronized calls:
    // a set() on a cache line that should be held in exclusive mode
    // by the current thread, and a get() on a cache line that is shared.
    while (true) {
      // Full volatile write (StoreLoad barrier) is required here: this is a Dekker-style
      // pattern — the writer must observe READING before the reader checks isWriteLocked(),
      // otherwise both could proceed concurrently.
      currentReadersState.set(SRWL_STATE_READING);
      if (!stampedLock.isWriteLocked()) {
        // Acquired lock in read-only mode
        return;
      } else {
        // Back off to NOT_READING to unblock a waiting writer. lazySet (release/StoreStore)
        // is sufficient: we are not protecting a critical section, just signalling that we
        // gave up. The writer will eventually see NOT_READING.
        currentReadersState.lazySet(SRWL_STATE_NOT_READING);
        // Some (other) thread is holding the write-lock, we must wait
        while (stampedLock.isWriteLocked()) {
          Thread.yield();
        }
      }
    }
  }

  /**
   * Attempts to release the read lock.
   *
   * <p>If the current thread is the holder of this lock then the {@code reentrantReaderCount} is
   * decremented. If the {@code reentrantReaderCount} is now zero then the lock is released. If the
   * current thread is not the holder of this lock then {@link IllegalMonitorStateException} is
   * thrown.
   *
   * @throws IllegalMonitorStateException if the current thread does not hold this lock.
   */
  public void sharedUnlock() {
    final var localEntry = entry.get();
    if (localEntry == null) {
      // ERROR: Tried to unlock a non read-locked lock
      throw new IllegalMonitorStateException();
    } else {
      // lazySet (release/StoreStore) is sufficient: all stores from the critical section
      // are ordered before this release, and the writer will eventually see NOT_READING.
      // The full StoreLoad barrier from set() is not needed because the writer's
      // stampedLock.writeLock() provides its own acquire barrier when it starts scanning.
      localEntry.state.lazySet(SRWL_STATE_NOT_READING);
    }
  }

  /**
   * Acquires the write lock.
   *
   * <p>Acquires the write lock if neither the read nor write lock are held by another thread and
   * returns immediately, setting the write lock {@code reentrantWriterCount} to one.
   *
   * <p>If the current thread already holds the write lock then the {@code reentrantWriterCount} is
   * incremented by one and the method returns immediately.
   *
   * <p>If the lock is held by another thread, then the current thread yields and lies dormant
   * until the write lock has been acquired, at which time the {@code reentrantWriterCount} is set
   * to one.
   */
  public void exclusiveLock() {
    // Try to acquire the lock in write-mode
    stampedLock.writeLock();

    // We can only do this after writerOwner has been set to the current thread
    var localReadersStateArray = readersStateArrayRef.get();
    if (localReadersStateArray == null) {
      // Set to dummyArray before scanning the readersStateList to impose
      // a linearizability condition
      readersStateArrayRef.set(dummyArray);
      // Copy readersStateList to an array
      localReadersStateArray = readersStateList.toArray(new AtomicInteger[readersStateList.size()]);
      readersStateArrayRef.compareAndSet(dummyArray, localReadersStateArray);
    }

    // Scan the array of Reader states
    for (var readerState : localReadersStateArray) {
      while (readerState != null && readerState.get() == SRWL_STATE_READING) {
        Thread.yield();
      }
    }
  }

  /**
   * Attempts to release the write lock.
   *
   * <p>If the current thread is the holder of this lock then the {@code reentrantWriterCount} is
   * decremented. If {@code reentrantWriterCount} is now zero then the lock is released. If the
   * current thread is not the holder of this lock then {@link IllegalMonitorStateException} is
   * thrown.
   *
   * @throws IllegalMonitorStateException if the current thread does not hold this lock.
   */
  public void exclusiveUnlock() {
    if (!stampedLock.isWriteLocked()) {
      // ERROR: tried to unlock a non write-locked instance
      throw new IllegalMonitorStateException();
    }

    stampedLock.asWriteLock().unlock();
  }

  /**
   * Acquires the read lock only if the write lock is not held by another thread at the time of
   * invocation.
   *
   * <p>Acquires the read lock if the write lock is not held by another thread and returns
   * immediately with the value {@code true}.
   *
   * <p>If the write lock is held by another thread then this method will return immediately with
   * the value {@code false}.
   *
   * @return {@code true} if the read lock was acquired
   */
  public boolean sharedTryLock() {
    var localEntry = entry.get();
    // Initialize a new Reader-state for this thread if needed
    if (localEntry == null) {
      localEntry = addState();
    }

    final var currentReadersState = localEntry.state;
    // Full volatile write required (Dekker pattern) — see sharedLock() for reasoning.
    currentReadersState.set(SRWL_STATE_READING);
    if (!stampedLock.isWriteLocked()) {
      // Acquired lock in read-only mode
      return true;
    } else {
      // Back off — lazySet sufficient (see sharedLock() backoff for reasoning).
      currentReadersState.lazySet(SRWL_STATE_NOT_READING);
      return false;
    }
  }

  /**
   * Acquires the read lock if the write lock is not held by another thread within the given waiting
   * time.
   *
   * <p>Acquires the read lock if the write lock is not held by another thread and returns
   * immediately with the value {@code true}.
   *
   * <p>If the write lock is held by another thread then the current thread yields execution until
   * one of two things happens:
   *
   * <ul>
   *   <li>The read lock is acquired by the current thread; or
   *   <li>The specified waiting time elapses.
   * </ul>
   *
   * <p>If the read lock is acquired then the value {@code true} is returned.
   *
   * @param nanosTimeout the time to wait for the read lock in nanoseconds
   * @return {@code true} if the read lock was acquired
   */
  public boolean sharedTryLockNanos(long nanosTimeout) {
    final var lastTime = System.nanoTime();
    var localEntry = entry.get();
    // Initialize a new Reader-state for this thread if needed
    if (localEntry == null) {
      localEntry = addState();
    }

    final var currentReadersState = localEntry.state;
    while (true) {
      // Full volatile write required (Dekker pattern) — see sharedLock() for reasoning.
      currentReadersState.set(SRWL_STATE_READING);
      if (!stampedLock.isWriteLocked()) {
        // Acquired lock in read-only mode
        return true;
      } else {
        // Back off — lazySet sufficient (see sharedLock() backoff for reasoning).
        currentReadersState.lazySet(SRWL_STATE_NOT_READING);

        if (nanosTimeout <= 0) {
          return false;
        }
        if (System.nanoTime() - lastTime < nanosTimeout) {
          Thread.yield();
        } else {
          return false;
        }
      }
    }
  }

  /**
   * Acquires the write lock only if it is not held by another thread at the time of invocation.
   *
   * <p>Acquires the write lock if the write lock is not held by another thread and returns
   * immediately with the value {@code true} if and only if no other thread is attempting a read
   * lock, setting the write lock {@code writerLoop} count to one.
   *
   * <p>If the current thread already holds this lock then the {@code reentrantWriterCount} count
   * is incremented by one and the method returns {@code true}.
   *
   * <p>If the write lock is held by another thread then this method will return immediately with
   * the value {@code false}.
   *
   * @return {@code true} if the write lock was free and was acquired by the current thread, or the
   * write lock was already held by the current thread; and {@code false} otherwise.
   */
  public boolean exclusiveTryLock() {
    // Try to acquire the lock in write-mode
    if (stampedLock.tryWriteLock() == 0) {
      return false;
    }

    // We can only do this after writerOwner has been set to the current thread
    var localReadersStateArray = readersStateArrayRef.get();
    if (localReadersStateArray == null) {
      // Set to dummyArray before scanning the readersStateList to impose
      // a linearizability condition
      readersStateArrayRef.set(dummyArray);
      // Copy readersStateList to an array
      localReadersStateArray = readersStateList.toArray(new AtomicInteger[readersStateList.size()]);
      readersStateArrayRef.compareAndSet(dummyArray, localReadersStateArray);
    }

    // Scan the array of Reader states
    for (var readerState : localReadersStateArray) {
      if (readerState != null && readerState.get() == SRWL_STATE_READING) {
        // There is at least one ongoing Reader so give up
        stampedLock.asWriteLock().unlock();
        return false;
      }
    }

    return true;
  }

  /**
   * Acquires the write lock if it is not held by another thread within the given waiting time.
   *
   * <p>Acquires the write lock if the write lock is not held by another thread and returns
   * immediately with the value {@code true} if and only if no other thread is attempting a read
   * lock, setting the write lock {@code reentrantWriterCount} to one. If another thread is
   * attempting a read lock, this function <b>may yield until the read lock is released</b>.
   *
   * <p>If the current thread already holds this lock then the {@code reentrantWriterCount} is
   * incremented by one and the method returns {@code true}.
   *
   * <p>If the write lock is held by another thread then the current thread yields and lies dormant
   * until one of two things happens:
   *
   * <ul>
   *   <li>The write lock is acquired by the current thread; or
   *   <li>The specified waiting time elapses
   * </ul>
   *
   * <p>If the write lock is acquired then the value {@code true} is returned and the write lock
   * {@code reentrantWriterCount} is set to one.
   *
   * @param nanosTimeout the time to wait for the write lock in nanoseconds
   * @return {@code true} if the lock was free and was acquired by the current thread, or the write
   * lock was already held by the current thread; and {@code false} if the waiting time elapsed
   * before the lock could be acquired.
   */
  public boolean exclusiveTryLockNanos(long nanosTimeout) throws java.lang.InterruptedException {
    final var lastTime = System.nanoTime();
    // Try to acquire the lock in write-mode
    if (stampedLock.tryWriteLock(nanosTimeout, TimeUnit.NANOSECONDS) == 0) {
      return false;
    }

    // We can only do this after writerOwner has been set to the current thread
    var localReadersStateArray = readersStateArrayRef.get();
    if (localReadersStateArray == null) {
      // Set to dummyArray before scanning the readersStateList to impose
      // a linearizability condition
      readersStateArrayRef.set(dummyArray);
      // Copy readersStateList to an array
      localReadersStateArray = readersStateList.toArray(new AtomicInteger[readersStateList.size()]);
      readersStateArrayRef.compareAndSet(dummyArray, localReadersStateArray);
    }

    // Scan the array of Reader states
    for (var readerState : localReadersStateArray) {
      while (readerState != null && readerState.get() == SRWL_STATE_READING) {
        if (System.nanoTime() - lastTime < nanosTimeout) {
          Thread.yield();
        } else {
          // Time has expired and there is at least one ongoing Reader so give up
          stampedLock.asWriteLock().unlock();
          return false;
        }
      }
    }

    return true;
  }

  /**
   * Acquires the write lock ONCE with an abort predicate, for a waiter that must give way to an
   * external condition (an operator freeze engaging) without ever spuriously failing on
   * contention alone.
   *
   * <p>The two-guarantee contract this primitive exists for (both load-bearing for the freezer
   * gate's third checkpoint; see the correctness comments inline):
   *
   * <ul>
   *   <li><b>Bounded acquisition under sustained readers.</b> The write bit is acquired exactly
   *       once and then HELD through the reader drain — writer preference: from the moment the
   *       bit is set, new readers observe {@code isWriteLocked()} and back off exactly as they do
   *       against {@link #exclusiveLock()}. There is no inter-attempt release window (unlike an
   *       {@link #exclusiveTryLockNanos} retry loop, which releases the bit on every drain
   *       timeout and forfeits admission to slip-in readers), so the acquisition completes within
   *       the maximum residual reader residence — deterministic, no starvation, no retry storm.
   *   <li><b>Abort within one poll granularity.</b> The predicate is polled between phase-1
   *       {@code tryWriteLock} attempts (each bounded by {@code pollNanos}) and on every yield
   *       iteration of the phase-2 reader-drain spin (the tightest granularity available; the
   *       intended predicate is a single atomic-counter read, so per-iteration polling costs
   *       less than the yield beside it). On abort the write bit is released fully — no queue
   *       entry, no held bit, no residual writer-intent state — so no reader or writer is
   *       stranded (parked readers spin on {@code isWriteLocked()} and proceed; there is no
   *       wait/notify channel to lose a wakeup on) and the primitive is immediately reusable.
   * </ul>
   *
   * <p>The predicate is additionally re-checked immediately after the drain completes (which is
   * also immediately after bit acquisition when there are no residual readers to drain), BEFORE
   * returning {@code true}: a condition arriving exactly at the acquisition-success edge aborts
   * here rather than being missed and caught only by later downstream gates with the lock held.
   *
   * <p>No new deadlock edge: the method blocks only on the same two waits {@link #exclusiveLock()}
   * already performs (the stamped writer queue and the reader-drain spin), both now bounded by the
   * abort predicate; the abort path releases everything before returning, and a queued phase-1
   * candidate holds nothing at all (readers never consult the stamped writer queue — they poll
   * only {@code isWriteLocked()}).
   *
   * <p>Interruption: an interrupt while parked in the phase-1 timed acquire restores the interrupt
   * flag and throws {@link DatabaseException} naming the lock state. The phase-2 drain is a yield
   * spin, uninterruptible exactly like {@link #exclusiveLock()}'s.
   *
   * <p>All other methods of this class are byte-for-byte unaffected; readers pay nothing new.
   *
   * <p>Fairness note: a phase-1 timeout re-enters the stamped writer queue at its tail, so under
   * sustained contention from plain {@link #exclusiveLock()} writers this waiter can lose its
   * queue position once per {@code pollNanos} — phase 1 is unbounded in theory under a permanent
   * writer storm. Acceptable for the intended consumer (storage state locks have rare, short
   * writers); a fairness-sensitive consumer would need a different phase-1 shape.
   *
   * <p>A predicate that THROWS is propagated — but never with the write bit held: a phase-1 throw
   * happens with nothing acquired, and the phase-2 evaluation sites are guarded so the bit is
   * released before the failure escapes (an ownerless write bit would wedge every reader and
   * writer of this lock forever).
   *
   * @param abort     polled condition; when it returns {@code true} the acquisition is abandoned
   *                  and this method returns {@code false} with no lock state held. Must be cheap
   *                  (it is polled per drain iteration) and must not itself touch this lock.
   * @param pollNanos the phase-1 per-attempt park bound; also the coarsest abort-detection
   *                  latency while queued against another writer. Must be positive.
   * @return {@code true} when the write lock was acquired (caller releases via
   * {@link #exclusiveUnlock()}); {@code false} when the abort predicate turned true first.
   */
  public boolean exclusiveLockWithAbort(final BooleanSupplier abort, final long pollNanos) {
    java.util.Objects.requireNonNull(abort, "abort predicate must not be null");
    if (pollNanos <= 0) {
      throw new IllegalArgumentException("pollNanos must be positive, got " + pollNanos);
    }
    // Phase 1: queue against writers only. A parked tryWriteLock candidate blocks no readers:
    // readers poll isWriteLocked(), which stays false until an acquisition actually succeeds,
    // so aborting from this phase leaves no trace at all.
    long stamp = 0;
    while (stamp == 0) {
      if (abort.getAsBoolean()) {
        return false;
      }
      try {
        stamp = stampedLock.tryWriteLock(pollNanos, TimeUnit.NANOSECONDS);
      } catch (final InterruptedException e) {
        // Restore the flag and fail loudly naming the state: a swallowed interrupt would turn
        // into an unbounded uninterruptible wait. The message stays generic (this is a shared
        // primitive, not a storage-only one) and reports only what is certain: the write bit was
        // not acquired by this call. The holder snapshot is best-effort — it can name a free lock
        // when a pre-interrupted thread never actually parked (StampedLock checks the interrupt
        // flag before attempting) or when the holder released concurrently.
        Thread.currentThread().interrupt();
        throw BaseException.wrapException(
            new DatabaseException(
                "interrupted while acquiring the write lock with an abort predicate (write bit"
                    + " not acquired by this call; "
                    + (stampedLock.isWriteLocked()
                        ? "another writer currently holds the write bit"
                        : "no writer currently holds the write bit")
                    + ")"),
            e, (String) null);
      }
    }

    // Phase 2: the write bit is HELD from here on — writer preference engaged exactly like
    // exclusiveLock (new readers observe isWriteLocked() and back off). Drain the residual
    // readers, polling the abort predicate on every yield iteration. The whole phase is guarded:
    // a THROW from the predicate (or any phase-2 failure) must release the bit before
    // propagating — an ownerless write bit would wedge every reader (spinning on isWriteLocked)
    // and writer of this lock forever. No double-unlock is possible: the two abort branches
    // below unlock and RETURN immediately, so any throw reaching the catch arrives with the bit
    // still held.
    try {
      var localReadersStateArray = readersStateArrayRef.get();
      if (localReadersStateArray == null) {
        // Set to dummyArray before scanning the readersStateList to impose
        // a linearizability condition
        readersStateArrayRef.set(dummyArray);
        // Copy readersStateList to an array
        localReadersStateArray =
            readersStateList.toArray(new AtomicInteger[readersStateList.size()]);
        readersStateArrayRef.compareAndSet(dummyArray, localReadersStateArray);
      }

      for (var readerState : localReadersStateArray) {
        while (readerState != null && readerState.get() == SRWL_STATE_READING) {
          if (abort.getAsBoolean()) {
            // Full release: the bit drops, backed-off readers spinning on isWriteLocked() proceed
            // (no lost wakeup possible — there is no parking channel, only the polled bit), and
            // the primitive is immediately reusable.
            stampedLock.asWriteLock().unlock();
            return false;
          }
          Thread.yield();
        }
      }

      // Predicate re-check at the acquisition-success edge, before returning true. This closes
      // the window where the condition arrives exactly as the drain completes — including the
      // zero-residual-readers case, where the drain loop body never ran and so never polled.
      if (abort.getAsBoolean()) {
        stampedLock.asWriteLock().unlock();
        return false;
      }
      return true;
    } catch (final Throwable phaseTwoFailure) {
      stampedLock.asWriteLock().unlock();
      throw phaseTwoFailure;
    }
  }
}
