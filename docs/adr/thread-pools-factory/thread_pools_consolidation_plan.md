# Thread Pools Consolidation Plan

## Context

The database engine currently creates **9+ separate thread pools, timers, and ad-hoc threads** across
different classes. Many of these are small single-threaded pools doing periodic lightweight work. Each
pool creates at least one OS thread, contributing to context switching overhead even when mostly idle.
Additionally, some pools use `java.util.Timer` instead of the project's
`ScheduledThreadPoolExecutorWithLogging`, missing out on the uncaught exception logging infrastructure.

**Goal:** Consolidate into fewer shared pools managed centrally by `YouTrackDBEnginesManager`, replacing
`Timer` instances with `ScheduledExecutorService`, and removing dead code.

## Current State (per JVM with 1 embedded instance)

| # | Pool/Thread | Location | Type | Threads |
|---|-------------|----------|------|---------|
| 1 | WAL Flush Task | `CASDiskWriteAheadLog.commitExecutor` (static) | ScheduledExecutorService(1) | 1 |
| 2 | WAL Write Task | `CASDiskWriteAheadLog.writeExecutor` (static) | ExecutorService(1) | 1 |
| 3 | Fuzzy Checkpoint | `AbstractStorage.fuzzyCheckpointExecutor` (static) | ScheduledExecutorService(1) | 1 |
| 4 | WOWCache Flush | `WOWCache.commitExecutor` (static) | ScheduledExecutorService(1) | 1 |
| 5 | Main Executor | `YouTrackDBInternalEmbedded.executor` | ScalingThreadPoolExecutor | 1-N |
| 6 | IO Executor | `YouTrackDBInternalEmbedded.ioExecutor` | ScalingThreadPoolExecutor | 0-N |
| 7 | Instance Timer | `YouTrackDBInternalEmbedded.timer` | Timer | 1 |
| 8 | Global Scheduler | `YouTrackDBScheduler.timer` | Timer | 1 |
| 9 | GranularTicker | `Profiler` → `GranularTicker.executor` | ScheduledExecutorService(1) | 1 |
| 10 | Pool Eviction | `DatabasePoolAbstract.evictionTask` | Timer | 1 per pool |

**Minimum permanent threads: ~8 + 1 per connection pool**

## Target State

| # | Pool | Owner | Type | Threads | Replaces |
|---|------|-------|------|---------|----------|
| 1 | WAL Flush | `YouTrackDBEnginesManager` | ScheduledThreadPoolExecutorWithLogging(1) | 1 | #1 |
| 2 | WAL Write | `YouTrackDBEnginesManager` | ThreadPoolExecutorWithLogging(1,1) | 1 | #2 |
| 3 | Fuzzy Checkpoint | `YouTrackDBEnginesManager` | ScheduledThreadPoolExecutorWithLogging(1) | 1 | #3 |
| 4 | WOWCache Flush | `YouTrackDBEnginesManager` | ScheduledThreadPoolExecutorWithLogging(1) | 1 | #4 |
| 5 | Scheduled | `YouTrackDBEnginesManager` | ScheduledThreadPoolExecutorWithLogging(2) | 2 | #7, #8, #9, #10 |
| 6 | Main Executor | `YouTrackDBEnginesManager` | ScalingThreadPoolExecutor | 1-N | #5 |
| 7 | IO Executor | `YouTrackDBEnginesManager` | ScalingThreadPoolExecutor | 0-N | #6 |

**All pools are owned by `YouTrackDBEnginesManager`** — the single source of truth for thread pools.
This prepares for a future requirement that all pool access must go through this class.

**Storage pools (#1-#4) remain single-threaded** to guarantee sequential execution within each
component (WAL flush tasks must not overlap, WOWCache flush tasks must not overlap, etc.).

**Minimum permanent threads: 7** (down from 8+). Timer threads (#7, #8, #9, #10) consolidated into a
2-thread scheduled pool (#5). Two threads ensure that `GranularTicker`'s high-frequency nanosecond
timestamp updates are not delayed by other scheduled tasks (eviction, auto-close, cron events, etc.).

## Unchanged

- `YTDBGremlinSession.executor` — per-session, ThreadLocal requirement
- `LiveQueryQueueThread` / `LiveQueryQueueThreadV2` — dedicated event-loop threads
- `IndexManagerEmbedded` rebuild thread — one-off thread
- Shutdown hooks — JVM mechanism

## Implementation Steps

### Step 1: Add all pools to YouTrackDBEnginesManager

**File:** `core/.../core/YouTrackDBEnginesManager.java`

Add fields:
```java
// Storage pools — single-threaded for sequential execution guarantees
private final ThreadGroup storageThreadGroup;
private final ScheduledExecutorService walFlushExecutor;
private final ExecutorService walWriteExecutor;
private final ScheduledExecutorService fuzzyCheckpointExecutor;
private final ScheduledExecutorService wowCacheFlushExecutor;

// General pools
private final ScheduledExecutorService scheduledPool;  // shared, 1 thread
private ExecutorService executor;                       // main work, scaling
private ExecutorService ioExecutor;                     // IO work, scaling (nullable)
```

- Create `storageThreadGroup` as child of the existing `threadGroup` ("YouTrackDB")
- Each storage pool is `ScheduledThreadPoolExecutorWithLogging(1)` or `ThreadPoolExecutorWithLogging(1,1)`
  with a `SingletonNamedThreadFactory` in `storageThreadGroup` — **single-threaded to guarantee
  sequential execution** (WAL flush must not overlap with itself, WOWCache flush must not overlap
  with itself, etc.)
- Create `scheduledPool` = `new ScheduledThreadPoolExecutorWithLogging(2, new NamedThreadFactory("YouTrackDB Scheduler", threadGroup))`
- `executor` and `ioExecutor` are created lazily via factory methods called from
  `YouTrackDBInternalEmbedded` during its construction (since their sizing depends on instance
  configuration). Once created, they are owned and shut down by the manager.

Add getters: `getWalFlushExecutor()`, `getWalWriteExecutor()`, `getFuzzyCheckpointExecutor()`,
`getWowCacheFlushExecutor()`, `getScheduledPool()`, `getStorageThreadGroup()`,
`getExecutor()`, `getIoExecutor()`.

Add factory methods: `createExecutor(YouTrackDBConfigImpl config)`,
`createIoExecutor(YouTrackDBConfigImpl config)` — called by the first `YouTrackDBInternalEmbedded`
during init, store result in manager fields. Subsequent calls (from additional embedded instances)
return the already-created executor without creating a new one. All `YouTrackDBInternalEmbedded`
instances share the same executor and ioExecutor — this is consistent with the storage pools
(#1-#4) which are already static (shared across all instances).

In `shutdown()`: gracefully shut down all pools (before existing shutdown handlers run).

### Step 2: Replace static storage pools

**File:** `core/.../storage/impl/local/AbstractStorage.java`
- Remove `storageThreadGroup` static field and its static initializer block
- Remove `fuzzyCheckpointExecutor` static field
- Change all usages of `fuzzyCheckpointExecutor` to `YouTrackDBEnginesManager.instance().getFuzzyCheckpointExecutor()`
- Change all usages of `storageThreadGroup` to `YouTrackDBEnginesManager.instance().getStorageThreadGroup()`

**File:** `core/.../storage/impl/local/paginated/wal/cas/CASDiskWriteAheadLog.java`
- Remove static `commitExecutor` and `writeExecutor` fields and their static initializer
- Change `commitExecutor` usages to `YouTrackDBEnginesManager.instance().getWalFlushExecutor()`
- Change `writeExecutor` usages to `YouTrackDBEnginesManager.instance().getWalWriteExecutor()`

**File:** `core/.../storage/cache/local/WOWCache.java`
- Remove static `commitExecutor` field and its static initializer
- Change usages to `YouTrackDBEnginesManager.instance().getWowCacheFlushExecutor()`

### Step 3: Move main/IO executors to YouTrackDBEnginesManager

**File:** `core/.../core/db/YouTrackDBInternalEmbedded.java`
- Remove `executor` and `ioExecutor` fields
- Remove `newExecutor()` and `newIoExecutor()` private methods
- Remove executor shutdown logic from `close()` (manager handles shutdown now)
- In constructor: call `youTrack.createExecutor(config)` and `youTrack.createIoExecutor(config)`
- Everywhere `executor` was used: call `youTrack.getExecutor()`
- Everywhere `ioExecutor` was used: call `youTrack.getIoExecutor()`

**File:** `core/.../core/YouTrackDBEnginesManager.java`
- Add `createExecutor(YouTrackDBConfigImpl config)` — moves the scaling pool creation logic
  from `YouTrackDBInternalEmbedded.newExecutor()`, stores in field, returns it
- Add `createIoExecutor(YouTrackDBConfigImpl config)` — moves from `newIoExecutor()`,
  stores in field, returns it (nullable if IO pool disabled)
- **Important:** The current `newExecutor()`/`newIoExecutor()` methods do **not** pass a
  `ThreadGroup` to `ThreadPoolExecutors.newScalingThreadPool()` — threads inherit the caller's
  thread group by default. The manager versions must pass the manager's `threadGroup` explicitly
  so that executor threads belong to the "YouTrackDB" thread group and are covered by the
  `ShutdownPendingThreadsHandler` interrupt logic.
- **Preserve `SourceTraceExecutorService` wrapping:** The current `newExecutor()` and
  `newIoExecutor()` conditionally wrap the executor with `SourceTraceExecutorService` when
  `GlobalConfiguration.EXECUTOR_DEBUG_TRACE_SOURCE` is enabled. The manager's factory methods
  must replicate this wrapping so debug tracing continues to work.
- Shutdown both in `shutdown()` with graceful termination + timeout

### Step 4: Replace Timers with shared ScheduledExecutorService

#### 4a. Change `SchedulerInternal` interface

**File:** `core/.../core/db/SchedulerInternal.java`
- Change method signatures from `TimerTask` params to `Runnable` + return `ScheduledFuture<?>`:
```java
public interface SchedulerInternal {
  ScheduledFuture<?> schedule(Runnable task, long delay, long period);
  ScheduledFuture<?> scheduleOnce(Runnable task, long delay);
}
```

**Note:** `SchedulerInternal` is extended by `YouTrackDBInternal` (line 36:
`public interface YouTrackDBInternal extends AutoCloseable, SchedulerInternal`), which is
implemented by `YouTrackDBInternalEmbedded`. This means the signature change cascades through
the inheritance chain: `SchedulerInternal` → `YouTrackDBInternal` → `YouTrackDBInternalEmbedded`.
Any other implementations of `YouTrackDBInternal` will also need updating — notably
`YTDBInternalProxy` in `server/.../YouTrackDBServer.java` (inner class at line 619), which
delegates `schedule()` / `scheduleOnce()` to the embedded instance (lines 839-846), and any
test stubs/mocks.

#### 4b. Replace Timer in `YouTrackDBInternalEmbedded`

**File:** `core/.../core/db/YouTrackDBInternalEmbedded.java`
- Remove `Timer timer` field
- Remove `new Timer(...)` from constructor
- Remove `timer.cancel()` from `close()`
- Implement `schedule()` and `scheduleOnce()` using `YouTrackDBEnginesManager.instance().getScheduledPool()`:
  ```java
  public ScheduledFuture<?> schedule(Runnable task, long delay, long period) {
    return YouTrackDBEnginesManager.instance().getScheduledPool()
        .scheduleWithFixedDelay(task, delay, period, TimeUnit.MILLISECONDS);
  }
  public ScheduledFuture<?> scheduleOnce(Runnable task, long delay) {
    return YouTrackDBEnginesManager.instance().getScheduledPool()
        .schedule(task, delay, TimeUnit.MILLISECONDS);
  }
  ```
- Change `autoCloseTimer` from `TimerTask` to `ScheduledFuture<?>`, update `cancel()` to `cancel(false)`.
  **Note:** `autoCloseTimer.cancel()` lives in `internalClose()` (line 1054), not in the outer
  `close()` method — `internalClose()` is called from within `synchronized(this)` at line 1011.
- Change `initAutoClose()` to create a `Runnable` instead of `TimerTask`

#### 4c. Update `CommandTimeoutChecker`

**File:** `core/.../core/db/CommandTimeoutChecker.java`
- Change `timer` field from `TimerTask` to `ScheduledFuture<?>`
- Change constructor to create a `Runnable` and use `scheduler.schedule(runnable, timeout/10, timeout/10)`
- Change `close()` to call `timer.cancel(false)` instead of `timer.cancel()`

#### 4d. Update `CachedDatabasePoolFactoryImpl`

**File:** `core/.../core/db/CachedDatabasePoolFactoryImpl.java`
- Add a `ScheduledFuture<?> cleanUpFuture` field
- Change `createCleanUpTask()` to return `Runnable` instead of `TimerTask`. The current
  `TimerTask` calls `this.cancel()` on itself when the factory is closed (line 59), but a plain
  `Runnable` has no `cancel()`. Instead, the `Runnable` references the `cleanUpFuture` field:
  `if (closed) { var f = cleanUpFuture; if (f != null) f.cancel(false); }`
- Change `scheduleCleanUpCache()` to accept `Runnable` instead of `TimerTask`, call
  `youTrackDB.schedule(task, timeout, timeout)`, and store the returned `ScheduledFuture<?>`
  in the `cleanUpFuture` field

#### 4e. Update `ScheduledEvent`

**File:** `core/.../core/schedule/ScheduledEvent.java`
- Add a `private final ReentrantLock timerLock = new ReentrantLock()` field. Currently,
  `interrupt()` uses `synchronized (this)` on the `ScheduledEvent` to atomically read, null, and
  cancel the `timer` field, but the `timer = task` assignment in `ScheduledEvent.schedule()`
  (line 162) has **no synchronization at all** — the two operations can race. Meanwhile,
  `ScheduledTimerTask.schedule()` uses `synchronized (this)` on the `ScheduledTimerTask` instance
  (a different monitor) to protect scheduling logic. An explicit `ReentrantLock` shared between
  the event and its task ensures all `timer` field accesses use the same lock. It is also
  virtual-thread-friendly on
  JDK 21-23 (`synchronized` pins virtual threads to carrier threads; `ReentrantLock` does not;
  JDK 24+ fixes this via JEP 491, but the project targets JDK 21+).
- Change `timer` field from `volatile TimerTask` to `volatile ScheduledFuture<?>`
- Change `ScheduledTimerTask` from `extends TimerTask` to `implements Runnable`
- In `interrupt()`: replace `synchronized (this)` with `timerLock.lock()` / `timerLock.unlock()`
  in a try-finally, and change `t.cancel()` to `t.cancel(false)`
- Pass `timerLock` to `ScheduledTimerTask` constructor (store as a field)
- In `ScheduledTimerTask.schedule()`: replace `synchronized (this)` with
  `timerLock.lock()` / `timerLock.unlock()` in a try-finally. `scheduleOnce()` now returns
  `ScheduledFuture<?>` instead of `void`, and accepts `Runnable` instead of `TimerTask`. Since
  `ScheduledTimerTask` now `implements Runnable`, it can still pass `this`. Assign the returned
  `ScheduledFuture<?>` to `event.timer` so that `interrupt()` can cancel it.
- In `ScheduledEvent.schedule()` (the outer method, line 150): **remove** the `timer = task`
  assignment (line 162) — `task` is now a `Runnable`, not assignable to the `ScheduledFuture<?>` field.
  The `event.timer` field is now set inside `ScheduledTimerTask.schedule()` instead.
- In `ScheduledTimerTask.run()` → inside `runTask()`, check `event.timer != null` still works since `timer` is now `ScheduledFuture<?>`

#### 4f. Replace `YouTrackDBScheduler` Timer

**File:** `core/.../core/YouTrackDBScheduler.java`
- Remove `Timer timer` field entirely — use `YouTrackDBEnginesManager.instance().getScheduledPool()`
- Change both `scheduleTask()` overloads to return `ScheduledFuture<?>` instead of `TimerTask`:
  - `scheduleTask(Runnable, long delay, long period)` — the primary overload
  - Remove `scheduleTask(Runnable, Date firstTime, long period)` — the `Date`-based overload has
    no external callers: all call sites — `Profiler.scheduleTask()`,
    `DirectMemoryAllocator.scheduleTask()`, and the excluded `lucene` module's test — use the
    `(Runnable, long, long)` primary overload. The `Date`-based overload is only called internally
    by the primary overload to compute the initial delay. Replace with direct
    `scheduledPool.scheduleWithFixedDelay()` or
    `scheduledPool.schedule()` depending on period.
- Keep the exception-catching wrapper around the `Runnable` — convert it from a `TimerTask` subclass
  to a plain lambda/wrapper. `ScheduledThreadPoolExecutorWithLogging.afterExecute()` only **logs**
  exceptions; it does **not** prevent periodic task cancellation. With `scheduleWithFixedDelay()`, an
  uncaught exception completes the `ScheduledFuture` exceptionally and **silently stops all further
  executions**. The wrapper must catch `Exception` (and log + swallow) to keep periodic tasks alive.
  `Error` is intentionally **not** swallowed — the current code catches `Error`, logs it, and
  rethrows. With `scheduleWithFixedDelay()` the rethrown `Error` completes the `ScheduledFuture`
  exceptionally and stops all future executions of that task, which is the desired behavior:
  an `Error` is serious enough to warrant killing the periodic task.
- Remove the `ScalableRWLock lock` field and all `lock`/`unlock` calls — the lock was needed
  because `Timer.schedule()` is not thread-safe after `Timer.cancel()`. `ScheduledExecutorService`
  is inherently thread-safe; the `active` flag check + submission is a best-effort guard, not
  a correctness requirement.
- `activate()` / `shutdown()` no longer create/cancel a Timer — just set the `active` flag
- `scheduleTask()` checks `active` before submitting to the shared pool

#### 4g. Replace `DatabasePoolAbstract` Timer

**File:** `core/.../core/db/DatabasePoolAbstract.java`
- Replace `Timer evictionTask` with `ScheduledFuture<?>`
- Schedule eviction using `YouTrackDBEnginesManager.instance().getScheduledPool()`
- Replace `Evictor extends TimerTask` with `Evictor implements Runnable`
- Cancel via `evictionFuture.cancel(false)` in close/cleanup

#### 4h. Update `GranularTicker`

**File:** `core/.../common/profiler/GranularTicker.java`
- Remove the private `ScheduledExecutorService executor` field
- Accept `ScheduledExecutorService` in constructor instead of creating one
- In `start()`: use the provided executor
- In `stop()`/`close()`: cancel both `ScheduledFuture<?>` instances (one for `nanoTime` updates,
  one for `nanoTimeDifference` updates) instead of shutting down the executor. Store them as
  fields from `start()`.

**File:** `core/.../common/profiler/Profiler.java`
- Pass `YouTrackDBEnginesManager.instance().getScheduledPool()` when creating `GranularTicker`

### Step 5: Verify `DirectMemoryAllocator` and `Profiler` scheduled tasks

**File:** `core/.../common/directmemory/DirectMemoryAllocator.java`
- Uses `YouTrackDBEnginesManager.instance().getScheduler().scheduleTask(...)` — automatically
  benefits from the `YouTrackDBScheduler` refactoring in Step 4f. No code changes needed.

**File:** `core/.../common/profiler/Profiler.java`
- Uses `scheduler.scheduleTask(new MemoryChecker(), ...)` — same as above, automatically
  benefits from Step 4f. No code changes needed beyond the `GranularTicker` change in Step 4h.

### Step 6: Make `BaseThreadFactory` set UncaughtExceptionHandler

**File:** `core/.../common/thread/BaseThreadFactory.java`
- Add `thread.setUncaughtExceptionHandler(new UncaughtExceptionHandler())` in `newThread()` to ensure
  all pool threads have the logging handler (currently only `NonDaemonThreadFactory` does this)

## Files Modified (summary)

| File | Change |
|------|--------|
| `core/.../core/YouTrackDBEnginesManager.java` | Add all 7 pools, factory methods, getters, shutdown |
| `core/.../storage/impl/local/AbstractStorage.java` | Remove static fuzzyCheckpointExecutor/storageThreadGroup, delegate to manager |
| `core/.../storage/impl/local/paginated/wal/cas/CASDiskWriteAheadLog.java` | Remove static commitExecutor/writeExecutor, delegate to manager |
| `core/.../storage/cache/local/WOWCache.java` | Remove static commitExecutor, delegate to manager |
| `core/.../core/db/SchedulerInternal.java` | Change interface: TimerTask → Runnable, return ScheduledFuture |
| `core/.../core/db/YouTrackDBInternal.java` | Inherits SchedulerInternal — signature changes cascade here |
| `core/.../core/db/YouTrackDBInternalEmbedded.java` | Remove Timer + executor/ioExecutor fields, delegate all to manager |
| `server/.../server/YouTrackDBServer.java` | Update `YTDBInternalProxy.schedule()`/`scheduleOnce()` signatures |
| `core/.../core/db/CommandTimeoutChecker.java` | TimerTask → Runnable + ScheduledFuture |
| `core/.../core/db/CachedDatabasePoolFactoryImpl.java` | TimerTask → Runnable + ScheduledFuture |
| `core/.../core/schedule/ScheduledEvent.java` | TimerTask → Runnable + ScheduledFuture |
| `core/.../core/YouTrackDBScheduler.java` | Replace Timer + lock with shared scheduledPool, remove Date overload |
| `core/.../core/db/DatabasePoolAbstract.java` | Replace Timer with shared scheduledPool |
| `core/.../common/profiler/GranularTicker.java` | Accept ScheduledExecutorService, don't create own |
| `core/.../common/profiler/Profiler.java` | Pass shared scheduledPool to GranularTicker |
| `core/.../common/thread/BaseThreadFactory.java` | Add UncaughtExceptionHandler to all threads |
| `core/src/test/.../core/db/CommandTimeoutCheckerTest.java` | Update `SchedulerInternal` impl: `TimerTask` → `Runnable`, return `ScheduledFuture<?>` |
| `core/src/test/.../core/db/YouTrackDBEmbeddedTests.java` | Update `schedule()`/`scheduleOnce()` call sites: `TimerTask` → `Runnable` |

## Verification

1. **Unit tests:**
   ```bash
   ./mvnw clean -pl core test
   ```
2. **Server tests (for YTDBGremlinSession):**
   ```bash
   ./mvnw clean -pl server test
   ```
3. **Integration tests (storage/WAL/cache changes):**
   ```bash
   ./mvnw clean -pl core verify -P ci-integration-tests
   ```
4. **Full test suite if any doubts:**
   ```bash
   ./mvnw clean package
   ```
