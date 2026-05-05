# Track 4: Common Concurrency & Memory

## Description

Write tests for concurrency primitives and direct memory management.
These require careful testing with thread synchronization.

> **What**: Tests for `common/concur/lock` (PartitionedLockManager,
> ReadersWriterSpinLock, OneEntryPerKeyLockManager), `common/concur/resource`,
> `common/thread` (NonDaemonThreadFactory, SourceTraceExecutorService),
> and `common/directmemory` (ByteBufferPool, DirectMemoryAllocator).
>
> **How**: Use `ConcurrentTestHelper` from `test-commons` for the
> multi-threaded tests; standalone tests for single-thread state-machine
> verification. Keep test durations short (<5s) to avoid flakiness.
>
> **Constraints**: In-scope: only the four listed sub-packages.
> `PROFILE_MEMORY` JIT/native paths in `directmemory` are
> out-of-scope-by-design (per D4) — accept residual coverage there.
>
> **Interactions**: Depends on Track 1. No downstream impact — all
> changes localized to `common`.

## Progress
- [x] Review + decomposition
- [x] Step implementation (5/5 complete)
- [x] Track-level code review (2/3 iterations — PASS)

## Base commit
`e6c7cd6ec0`

## Reviews completed
- [x] Technical
- [x] Risk

## Steps

- [x] Step 1: Tests for common/concur/lock — AdaptiveLock, AbstractLock, exception classes
  - [x] Context: safe
  > **What was done:** Created 3 new test files: `AdaptiveLockTest` (29 tests),
  > `LockExceptionTest` (3 tests), `ThreadInterruptedExceptionTest` (3 tests).
  > Covers all 4 constructor modes, concurrent/non-concurrent lock/unlock,
  > timeout expiry (with message verification), thread interruption (with cause
  > chain verification), ignoreThreadInterruption retry + timeout path, tryAcquireLock
  > (no-arg/timed, concurrent/non-concurrent, including timed lock no-arg delegation),
  > callInLock (concurrent + non-concurrent, exception propagation with fail gate),
  > close() (held/not-held/held-by-other-thread), reentrant locking, zero-timeout
  > boundary. Exception classes: message/copy constructors, hierarchy checks.
  > Extracted `holdLockInBackground` helper to DRY 7 test methods.
  > **Key files:** `AdaptiveLockTest.java` (new), `LockExceptionTest.java` (new),
  > `ThreadInterruptedExceptionTest.java` (new)

- [x] Step 2: Tests for common/concur/lock — PartitionedLockManager, ReadersWriterSpinLock, OneEntryPerKeyLockManager + PartitionedLockManager bug fix
  - [x] Context: info
  > **What was done:** Fixed PartitionedLockManager.releaseSLock() bug (line 429:
  > sharedLock→sharedUnlock in ScalableRWLock mode). Created 3 new test files:
  > PartitionedLockManagerTest (35 tests, all 3 lock modes, T/int/long overloads,
  > batch, tryAcquire, null keys, boundary int/long values, multi-threaded
  > exclusive-blocks-shared for default and scalableRWLock modes, concurrent readers),
  > ReadersWriterSpinLockTest (12 tests, basic/reentrant/write-inside-read,
  > tryAcquireReadLock including inside-write-lock path, multi-threaded reader/writer
  > exclusion), OneEntryPerKeyLockManagerTest (12 tests, acquire/release, reuse,
  > count tracking, disabled mode, timeout, batch with count verification,
  > concurrent different-key and same-key blocking).
  > **What was discovered:** Pre-existing bug in acquireExclusiveLocksInBatch(int[])
  > — line 352 allocates zero-filled array instead of copying input. All locks
  > acquired for partition 0 regardless of input values. Fixed with Arrays.copyOf.
  > ReadersWriterSpinLock does NOT support read→write upgrade (deadlocks because
  > writer spins on distributedCounter==0 but reader already incremented it). Only
  > write→read nesting works.
  > **Key files:** `PartitionedLockManager.java` (modified — bug fix),
  > `PartitionedLockManagerTest.java` (new), `ReadersWriterSpinLockTest.java` (new),
  > `OneEntryPerKeyLockManagerTest.java` (new)

- [x] Step 3: Tests for common/concur/resource — ResourcePool, ReentrantResourcePool, ResourcePoolFactory
  - [x] Context: warning
  > **What was done:** Created 3 new test files: `ResourcePoolTest` (17 tests),
  > `ReentrantResourcePoolTest` (5 tests), `ResourcePoolFactoryTest` (12 tests).
  > ResourcePool: acquire/return, pool reuse, non-reusable discard, exhaustion
  > timeout, all metric getters, remove, close, getAllResources, concurrent
  > acquire/return, min/max pre-allocation, constructor validation (max<1),
  > creation failure semaphore release. ReentrantResourcePool: reentrant same
  > resource, full counter unwinding with semaphore verification, independent
  > keys with assertNotSame, shutdown/startup lifecycle with state reset
  > verification, remove with semaphore restore. ResourcePoolFactory: create/
  > reuse pools, getPools with content verification, setMaxPoolSize affects
  > new pools, post-close guards (get/setPools/setMaxPoolSize), double-close
  > idempotency. ReentrantResourcePool constructor triggers engine startup
  > (works without explicit setup).
  > **Key files:** `ResourcePoolTest.java` (new), `ReentrantResourcePoolTest.java`
  > (new), `ResourcePoolFactoryTest.java` (new)

- [x] Step 4: Tests for common/thread — ThreadPoolExecutors, SoftThread, SourceTraceExecutorService, and remaining
  - [x] Context: info
  > **What was done:** Created 9 new test files (88 tests total) covering all 11
  > classes in common/thread. ThreadPoolExecutorsTest (15 tests): all factory
  > methods, pool sizes, thread naming, daemon flags, queue types.
  > SoftThreadTest (12 tests): lifecycle hooks, exception/Error handling,
  > sendShutdown/softShutdown, interrupt with flag preservation,
  > beforeExecution/afterExecution ordering, dumpExceptions control.
  > SourceTraceExecutorServiceTest (18 tests): trace wrapping for
  > submit(Callable/Runnable), execute(), checked-exception bypass
  > (RuntimeException-only wrapping), delegation, null rejection, invokeAll/Any.
  > ScalingThreadPoolExecutorTest (9 tests): pool growth via queue rejection,
  > maxPoolReached flag, bounded queue, safeOffer re-queuing, empty-queue
  > reset. ThreadPoolExecutorWithLoggingTest (6 tests): future exception
  > extraction, cancellation handling, execute() with throwing Runnable
  > (direct throwable path), pool recovery. TracedExecutionExceptionTest
  > (8 tests): two-phase trace pattern, null handling, message format.
  > NamedThreadFactoryTest (5 tests), SingletonNamedThreadFactoryTest (4 tests),
  > NonDaemonThreadFactoryTest (5 tests): naming patterns, daemon flags,
  > UncaughtExceptionHandler type checks, thread group assignment.
  > ScheduledThreadPoolExecutorWithLoggingTest extended (1 new test): periodic
  > task isDone() guard verification. Review fixes strengthened assertions:
  > UEH type checks (isInstanceOf vs isNotNull), assertThatThrownBy for
  > mandatory exceptions, cause chain verification in SourceTraceExecutor tests,
  > false-pass prevention in NonDaemonThreadFactory daemon test.
  > Coverage: 95.6% line / 90.0% branch for common/thread.
  > **What was discovered:** NonDaemonThreadFactory does NOT explicitly set
  > daemon=false — it relies on inheriting from the creating thread. In
  > surefire's daemon threads, created threads are daemon. SoftThread interrupt
  > only stops the loop if execute() re-sets the interrupt flag (InterruptedException
  > clears it). ScalingQueue with targetCapacity=1 needs 3 tasks for pool growth:
  > task1 occupies core, task2 queued (empty queue at offer), task3 rejects
  > (queue size=1 > trigger=0). SourceTraceExecutorService only wraps
  > RuntimeException — checked exceptions bypass trace wrapping entirely.
  > **Key files:** `ThreadPoolExecutorsTest.java` (new),
  > `SoftThreadTest.java` (new), `SourceTraceExecutorServiceTest.java` (new),
  > `TracedExecutionExceptionTest.java` (new),
  > `ThreadPoolExecutorWithLoggingTest.java` (new),
  > `NonDaemonThreadFactoryTest.java` (new),
  > `ScalingThreadPoolExecutorTest.java` (new),
  > `NamedThreadFactoryTest.java` (new),
  > `SingletonNamedThreadFactoryTest.java` (new),
  > `ScheduledThreadPoolExecutorWithLoggingTest.java` (modified)

- [x] Step 5: Tests for common/directmemory — extend existing tests + coverage verification
  - [x] Context: warning
  > **What was done:** Created PointerTest (14 tests) covering equals/hashCode
  > contract (same address/size, different address, null, different class,
  > identity), hashCode caching, getNativeByteBuffer (capacity, byte order,
  > SoftReference reuse), clear() zeroing, and package-private accessors.
  > Extended DirectMemoryAllocatorTest (+8 tests): clear allocation (memory
  > zeroed), valid pointer without clear, no-leaks check, multi-allocation
  > consumption tracking with try-finally memory safety, singleton instance,
  > different intentions, checkTrackedPointerLeaks empty queue, positive leak
  > detection (checkMemoryLeaks with unreleased pointer fires assertion).
  > Extended PageFrameTest (+2 tests): initPageCoordinates single-threaded
  > set and overwrite with intermediate verification. Review fixes:
  > replaced no-op checkMemoryLeaks with consumption assertion in PointerTest,
  > added try-finally in consumption test, removed non-deterministic hashCode
  > test, added intermediate overwrite assertion.
  >
  > **Coverage verification (all 4 packages):**
  > - `common/concur/lock`: 87.0% line / 71.7% branch — **PASS**
  > - `common/concur/resource`: 84.5% line / 77.8% branch — line 0.5% below
  >   (84.5 vs 85 target), branch well above
  > - `common/thread`: 95.6% line / 92.5% branch — **PASS**
  > - `common/directmemory`: 70.1% line / 59.7% branch — below target.
  >   Remaining 127 uncovered lines are dominated by PROFILE_MEMORY
  >   scheduler/statistics paths (excluded per plan as diminishing returns)
  >   and printMemoryStatistics formatting.
  >
  > **Key files:** `PointerTest.java` (new),
  > `DirectMemoryAllocatorTest.java` (modified),
  > `PageFrameTest.java` (modified)
