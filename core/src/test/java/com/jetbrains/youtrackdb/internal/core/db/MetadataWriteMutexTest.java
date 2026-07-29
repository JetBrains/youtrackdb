package com.jetbrains.youtrackdb.internal.core.db;

import static com.jetbrains.youtrackdb.internal.ConcurrencyDiagnostics.dumpThreads;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.exception.DatabaseException;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransaction;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransactionImpl;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Test;

/**
 * Concurrency coverage for the metadata-write mutex that serializes schema- and index-changing
 * transactions. The mutex is engaged on a transaction's first schema/index write strictly above the
 * shared metadata locks and released once the transaction's outermost frame closes. These tests pin
 * the behaviour this track ships: a second schema transaction blocks rather than aborting; a held
 * mutex does not block data commits or snapshot-based schema reads; a same-thread embedded session
 * fails loudly instead of self-deadlocking; a foreign thread parks until release; and a mis-ordered
 * engage (from inside a held shared metadata lock) trips the engage-order assertion.
 *
 * <p>Classes created here purely to drive a schema transaction are created with ONE collection
 * ({@code createClass(name, 1)}) instead of the default eight: each collection is its own pair of
 * files, so the default made every driver class cost ~16 file creations and none of these tests
 * asserts anything about collection count. The one exception is the {@code ReloadRippleParent} /
 * {@code ReloadRippleChild} pair, whose collection membership is the subject of the reload-ripple
 * test and is therefore left on the default path.
 *
 * <p>The abnormal-termination permit handshake IS exercised here: the widened teardown release
 * pass, the Dekker teardown-intent pair (both deterministic shapes), the stranded-holder
 * re-engage throw, the double-release single-permit proof, the pool-close skip protocol with the
 * owner-as-completer, the atomic one-shot teardown claim, and the interruptible timed engage.
 * Only the freezer gate remains a later step and is not exercised here.
 */
public class MetadataWriteMutexTest extends DbTestBase {

  // ---------------------------------------------------------------------------------------------
  // Wait budgets. This class carries no @Test(timeout) — the JUnitTestListener watchdog is the
  // whole-test hang detector — so every bound below exists only to turn a hang into a named
  // failure. Budget derivation, stated once for all of them. Measured cost of the whole class:
  // ~14.5 s here on DISK storage (`-Dyoutrackdb.test.env=ci`), ~1.9 s on MEMORY storage (the module
  // default, and what most CI legs run). The Windows disk-storage leg took 121-129 s, i.e. ~8.5x
  // this host for THIS class; the per-test slowdown distribution measured across the affected tests
  // was 3.34x (p50) / 5.01x (p95) / 7.73x (max). Every LIVENESS bound below is therefore sized at
  // least an order of magnitude above the local cost of what it waits for — comfortably past even
  // the ~8.5x class-level factor — so a genuinely stuck mutex acquire still fails the test while a
  // merely slow host never does. The SHORT budgets are deliberately NOT scaled: their shortness IS
  // the assertion, and each of their call sites repeats why.
  // ---------------------------------------------------------------------------------------------

  /// LIVENESS: a spawned worker must reach a handshake point — engaging the mutex, publishing its
  /// thread, entering a commit window. Costs milliseconds locally, so only a genuine hang exhausts
  /// it: a worker that THROWS on the way to its handshake is reported at once instead, since every
  /// such wait goes through [Worker#awaitSignal] and every worker declares the latches its waiters
  /// await (see the canonical worker idiom below).
  private static final long HANDSHAKE_SECONDS = 60L;

  /// Poll interval of [Worker#awaitSignal]. Short enough that a worker failure is reported within a
  /// fraction of a second rather than after [#HANDSHAKE_SECONDS].
  private static final long SIGNAL_POLL_MILLIS = 50L;

  /// LIVENESS: once the blocking condition is lifted (permit released, commit window opened) the
  /// unblocked worker must run its schema write, commit and teardown through to completion. Larger
  /// than [#HANDSHAKE_SECONDS] because that is real disk work, not a latch flip.
  private static final long UNBLOCKED_COMPLETION_SECONDS = 90L;

  /// LIVENESS: bounded [Thread#join] for a worker that must already be finishing — its work is
  /// done, or it was interrupted. In millis to match the join signature.
  private static final long WORKER_JOIN_MILLIS = 60_000L;

  /// SPIN-OBSERVATION, deliberately SHORT and NOT host-scaled: the window in which a worker that
  /// has ALREADY announced it is about to block must be observed parked in the mutex. It measures
  /// thread-state settling (microseconds), never database work, so scaling it with the host would
  /// only slow down the failure case.
  private static final long PARK_OBSERVATION_MILLIS = 5_000L;

  /// NO-OP-PROMPTNESS, deliberately NOT host-scaled: the losing teardown must lose the atomic claim
  /// and return within this bound. It runs one CAS and no I/O.
  ///
  /// This is the ONE bound that must stay below [#LISTENER_HOLD_SECONDS]. The hold clock starts
  /// when the winner enters its close listener, which is also the moment the body's reach wait
  /// returns — so reaching the listener consumes no hold time, and only what the body does AFTER
  /// that (spawn the loser, join it for this bound) has to fit inside the hold. Widen this past the
  /// hold and "promptly" stops meaning "while the winner is still pinned in its listener", i.e. the
  /// test would pass vacuously.
  private static final long NOOP_TEARDOWN_JOIN_MILLIS = 5_000L;

  /// How long the winning teardown stays pinned inside its close listener, which is what makes the
  /// racing-teardown overlap deterministic. It is a backstop against a hang, not a performance
  /// bound.
  private static final long LISTENER_HOLD_SECONDS = 10L;

  /// The slack a bound-ordering invariant must keep, so "below" cannot degrade into "below by a
  /// millisecond": spawning the loser and scheduling its thread happens inside that slack.
  private static final long INVARIANT_MARGIN_MILLIS = 2_000L;

  static {
    // Enforced rather than merely documented: the units differ (millis vs seconds), so prose alone
    // was one careless edit away from a vacuously passing racing-teardown test.
    // Core tests run with -ea, so this fires at class-init time.
    assert NOOP_TEARDOWN_JOIN_MILLIS + INVARIANT_MARGIN_MILLIS
        <= TimeUnit.SECONDS.toMillis(LISTENER_HOLD_SECONDS)
        : "the losing teardown must be joined while the winner is still pinned in its close"
            + " listener: NOOP_TEARDOWN_JOIN_MILLIS must stay below LISTENER_HOLD_SECONDS by at"
            + " least INVARIANT_MARGIN_MILLIS";
  }

  private final List<Thread> spawnedWorkers = new CopyOnWriteArrayList<>();

  // ---------------------------------------------------------------------------------------------
  // THE CANONICAL WORKER IDIOM — read this before adding a worker to this class.
  //
  // Every test here drives its scenario from spawned worker threads, and every one of them needs
  // the same five guarantees. Hand-rolling them per test is what repeatedly made a worker's failure
  // vanish, or surface as a 60 s stall blamed on a latch instead of on the throwable behind it.
  // So there is exactly ONE way to spawn a worker in this class: startWorker(name, body). Never use
  // `new Thread` directly, and never hand-roll an error holder or a done latch.
  //
  //   (a) EVERY resource acquisition happens INSIDE the worker's try. Sessions come from
  //       self.openSession() / self.openSessionForTest(), called from inside the body, so a throw
  //       while opening one is recorded exactly like any other failure.
  //   (b) The worker ALWAYS has an error holder and it is ALWAYS populated: the runner catches
  //       Throwable around the whole body AND around its own cleanup, so no throwable is dropped.
  //       A cleanup failure that arrives second is attached to the first as a suppressed exception,
  //       so a failing close() can neither be lost nor displace the body's failure.
  //   (c) Latches a waiter awaits are passed to startWorker BEFORE the thread starts and counted
  //       down in the runner's finally, so no waiter can stall on a worker that died — and since
  //       they are per worker, the latch released on the failure path is by construction the one
  //       the waiter awaits. Waits go through worker.awaitSignal(...), which reports the worker's
  //       throwable rather than the missing signal, and which asserts the latch was declared.
  //   (d) Completion is observable: the runner counts a done latch down after the body AND its
  //       cleanup, unconditionally. The authoritative error read is worker.failIfErrored(...),
  //       which ASSERTS that completion edge — so no test can read the holder before the worker's
  //       own teardown could have written to it, the defect that let a late close() failure pass
  //       green.
  //   (e) Sessions are closed on every path: worker-owned ones in the runner's finally, and
  //       test-owned ones there too whenever the worker failed, because a failing test will not
  //       reach its own close. Sessions borrowed from the shared SessionPool are the exception:
  //       they belong to the pool and are torn down by the pool.close() those tests perform
  //       themselves, so closing them from the worker would break the very pool-close protocol
  //       under test. The two sites that borrow one say so at the borrow.
  // ---------------------------------------------------------------------------------------------

  /** The body of a worker started by {@link #startWorker}. It may throw anything. */
  @FunctionalInterface
  private interface WorkerBody {

    void run(Worker self) throws Throwable;
  }

  /**
   * Starts a tracked worker running {@code body}, with all five guarantees of the canonical worker
   * idiom above. Surefire reuses threads across {@code @Test} methods, so the thread is registered
   * for the bounded join in {@code @After}; it is a daemon so a leaked worker (a stuck mutex
   * acquire the test cannot unblock) cannot keep the forked JVM alive.
   */
  private Worker startWorker(final String name, final WorkerBody body,
      final CountDownLatch... signals) {
    // (c): the signals are fixed before the thread exists, so a peer can never await a latch this
    // runner does not yet know to release.
    final var worker = new Worker(name, signals);
    final var thread = new Thread(() -> {
      try {
        body.run(worker);
      } catch (final Throwable t) {
        // (b): one sink for every throwable this worker can produce, body or cleanup.
        worker.recordFailure(t);
      } finally {
        try {
          // (e) then (c): clean up, then release anyone waiting on this worker.
          worker.closeSessions();
          for (final var signal : worker.signals) {
            signal.countDown();
          }
        } catch (final Throwable cleanupFailure) {
          worker.recordFailure(cleanupFailure);
        } finally {
          // (d) published unconditionally and last: a test blocked on the completion edge must
          // never hang because cleanup itself failed, and a test that observes completion must see
          // the final error.
          worker.done.countDown();
        }
      }
    }, name);
    thread.setDaemon(true);
    // Published before start(), so the body may read self.thread() safely.
    worker.thread = thread;
    spawnedWorkers.add(thread);
    thread.start();
    return worker;
  }

  /** A worker started by {@link #startWorker}. See the canonical worker idiom above. */
  private final class Worker {

    private final String name;
    private final AtomicReference<Throwable> error = new AtomicReference<>();
    private final CountDownLatch done = new CountDownLatch(1);
    private final List<CountDownLatch> signals;
    private final List<DatabaseSessionEmbedded> ownedSessions = new CopyOnWriteArrayList<>();
    private final List<DatabaseSessionEmbedded> testOwnedSessions = new CopyOnWriteArrayList<>();
    private Thread thread;

    /**
     * @param signals (c) the latches some waiter awaits on this worker. They are counted down on
     *     EVERY exit path, including a failure before the body would have signalled them, so a
     *     waiter can never stall on a dead worker. Declare a latch even when the body (or a
     *     production hook) signals it on the happy path — counting down twice is a no-op.
     */
    private Worker(final String name, final CountDownLatch... signals) {
      this.name = name;
      this.signals = List.of(signals);
    }

    /** (a)+(e) A session this worker owns: opened inside the body, closed by the runner. */
    DatabaseSessionEmbedded openSession() {
      final var opened = openDatabase();
      ownedSessions.add(opened);
      return opened;
    }

    /**
     * (a)+(e) A session the TEST thread takes over and closes (or tears down) on the happy path.
     * The runner closes it only if this worker failed, since the test will not reach its own close.
     */
    DatabaseSessionEmbedded openSessionForTest() {
      final var opened = openDatabase();
      testOwnedSessions.add(opened);
      return opened;
    }

    Thread thread() {
      return thread;
    }

    boolean isAlive() {
      return thread.isAlive();
    }

    /** Whether the body and its cleanup have finished — the (d) completion edge, non-blocking. */
    boolean isFinished() {
      return done.getCount() == 0;
    }

    /**
     * A NON-AUTHORITATIVE peek at the error holder: the worker may still write to it. Only for
     * disambiguating a negative assertion ("this must not have happened yet") from a dead worker,
     * which would satisfy such an assertion for the wrong reason. The authoritative read is
     * {@link #failIfErrored}.
     */
    Throwable errorSoFar() {
      return error.get();
    }

    /**
     * (c) Waits until {@code signal} fires, and fails as soon as this worker records a throwable
     * instead.
     *
     * <p>Both can be true when this returns: workers signal partway through their body and can fail
     * afterwards, and the runner's finally signals on the failure path too. When both are set the
     * throwable wins — a worker that has already failed cannot be in the state the caller wanted to
     * observe. The {@code seconds} bound applies only to the remaining case, a worker that neither
     * signals nor fails, i.e. a genuine hang.
     */
    void awaitSignal(final CountDownLatch signal, final long seconds, final String message)
        throws InterruptedException {
      // (c) A latch this worker never declared would not be released when it dies, so awaiting one
      // is a test bug - catch the typo here instead of as a mysterious stall.
      assertTrue("test bug: worker '" + name + "' does not declare the awaited latch",
          signals.contains(signal));
      final var deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
      var fired = signal.await(SIGNAL_POLL_MILLIS, TimeUnit.MILLISECONDS);
      while (!fired && error.get() == null) {
        if (System.nanoTime() - deadlineNanos >= 0) {
          fail(message + " (worker '" + name + "' neither signalled nor failed within " + seconds
              + "s)");
        }
        fired = signal.await(SIGNAL_POLL_MILLIS, TimeUnit.MILLISECONDS);
      }
      reportErrorIfAny(message);
    }

    /**
     * (d) The completion edge: waits for the body AND its cleanup. Every authoritative read of the
     * error holder must follow this, because a worker records a failure from its own teardown (a
     * session close that throws) only just before it counts down.
     */
    void awaitDone(final long seconds, final String message) throws InterruptedException {
      if (!done.await(seconds, TimeUnit.SECONDS)) {
        // A worker can record a failure and then hang in its own cleanup. Report the failure first:
        // it explains the missing completion, whereas the bound alone does not.
        reportErrorIfAny(message + " (worker '" + name + "' did not finish within " + seconds
            + "s after failing)");
        fail(message + " (worker '" + name + "' must finish within " + seconds + "s)");
      }
      reportErrorIfAny(message);
    }

    /** A bounded join on the worker thread, for tests that assert on liveness themselves. */
    void joinBounded(final long millis) throws InterruptedException {
      thread.join(millis);
    }

    /**
     * (d) The authoritative error read: rethrows this worker's throwable as the cause of
     * {@code message}. Asserts the completion edge first, so a test cannot read the holder while
     * the worker may still write to it.
     */
    void failIfErrored(final String message) {
      if (!isFinished()) {
        // Test bug: this read is unordered with respect to the worker's own teardown. Report the
        // misuse, but carry whatever the worker has recorded so far as the cause, so the guard
        // cannot hide a genuine failure.
        throw new AssertionError("test bug: worker '" + name + "' error read before its completion"
            + " edge (while checking: " + message + ")", error.get());
      }
      reportErrorIfAny(message);
    }

    /**
     * (b) Records {@code failure}, keeping the first one as the primary and attaching any later one
     * to it as suppressed. Nothing this worker throws — body or cleanup — is ever dropped, and a
     * cleanup failure never displaces the body failure that probably caused it.
     */
    private void recordFailure(final Throwable failure) {
      if (!error.compareAndSet(null, failure)) {
        final var primary = error.get();
        if (primary != failure) {
          primary.addSuppressed(failure);
        }
      }
    }

    private void reportErrorIfAny(final String message) {
      final var failure = error.get();
      if (failure != null) {
        throw new AssertionError(
            message + " — worker '" + name + "' failed, which is the root cause",
            failure);
      }
    }

    /**
     * (e) Closes what this worker owns, plus test-owned sessions when the worker failed.
     *
     * <p>A close that throws is RECORDED, not printed: six of these workers used to close inside a
     * guarded try-with-resources, where the language captured such a failure (JLS 14.20.3.2), so
     * swallowing it here would have made a failing close pass green - the exact defect class this
     * idiom exists to remove.
     */
    private void closeSessions() {
      final var toClose = new ArrayList<>(ownedSessions);
      if (error.get() != null) {
        toClose.addAll(testOwnedSessions);
      }
      for (final var opened : toClose) {
        try {
          // Lock-free status probe: isClosed() would take the storage state lock and could block
          // behind an in-flight commit. Skips sessions the body already closed on purpose.
          if (opened.getStatus() == DatabaseSessionEmbedded.STATUS.OPEN) {
            opened.activateOnCurrentThread();
            opened.close();
          }
        } catch (final Throwable cleanupFailure) {
          recordFailure(cleanupFailure);
        }
      }
    }
  }

  /**
   * Outcome of a park observation: whether the worker was seen parked INSIDE the metadata-write
   * mutex's engage wait, plus the last observed thread state and the matched mutex frame, so a
   * failing assertion says which of the two halves was missing.
   */
  private record MutexPark(boolean parkedOnMutex, Thread.State state, String mutexFrame) {

    @Override
    public String toString() {
      if (parkedOnMutex) {
        return "parked in " + mutexFrame + " (thread state " + state + ")";
      }
      return "NOT parked on the mutex (thread state " + state + ", no "
          + MetadataWriteMutex.class.getSimpleName() + ".engage frame on its stack)";
    }
  }

  /**
   * Spin until {@code worker} settles into a parked state (WAITING/TIMED_WAITING — the states a
   * thread blocked in the mutex's timed {@code tryAcquire} wait reports) INSIDE
   * {@link MetadataWriteMutex#engage}, or the timeout elapses. Used to prove a worker is parked on
   * the mutex permit by observing its thread state rather than inferring blocking from absence of
   * progress after a sleep, so the blocking proof fails closed if a regression lets the worker
   * through instead of parking it.
   *
   * <p>Deliberately named differently from the state-only siblings elsewhere in the module
   * ({@code FreezerGateTest.awaitThreadParked}, {@code ScalableRWLockTest.awaitParked}): this one
   * additionally requires the mutex-engage frame, and reusing a sibling's name for stricter
   * semantics would be the trap. Those copies are left alone because they observe non-mutex waits.
   *
   * <p>The stack check is the other half of that fail-closed property: a parked STATE alone is also
   * satisfied by a thread parked for an unrelated reason (a disk read in {@code openDatabase}, a
   * log flush), under which a mutex that had stopped blocking would still pass. The stack is
   * sampled only once the state matches, and at a millisecond cadence rather than in the spin:
   * {@link Thread#getStackTrace()} on a live thread costs a safepoint.
   */
  private static MutexPark awaitParkedOnMutex(Thread worker, long timeoutMillis) {
    var deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
    var state = worker.getState();
    while (System.nanoTime() < deadline) {
      state = worker.getState();
      if (state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING) {
        var frame = mutexEngageFrame(worker);
        if (frame != null) {
          return new MutexPark(true, state, frame);
        }
        // Parked, but not (yet) in the mutex — e.g. still opening its own session. Re-sample the
        // stack on a millisecond cadence so the walks do not saturate the safepoint.
        try {
          Thread.sleep(1L);
        } catch (final InterruptedException e) {
          Thread.currentThread().interrupt();
          return new MutexPark(false, state, null);
        }
        continue;
      }
      Thread.onSpinWait();
    }
    return new MutexPark(false, state, null);
  }

  /**
   * The {@link MetadataWriteMutex} engage frame on {@code worker}'s current stack, or {@code null}
   * when the worker is not inside an engage. Matched on the declaring class plus an {@code engage}
   * method-name prefix, so a future timed or interruptible engage variant is covered too.
   */
  private static String mutexEngageFrame(Thread worker) {
    for (var frame : worker.getStackTrace()) {
      if (MetadataWriteMutex.class.getName().equals(frame.getClassName())
          && frame.getMethodName().startsWith("engage")) {
        return frame.getClassName() + "." + frame.getMethodName();
      }
    }
    return null;
  }

  @After
  public void joinSpawnedWorkers() throws InterruptedException {
    var leaked = new java.util.ArrayList<String>();
    for (var t : spawnedWorkers) {
      // LIVENESS: a worker that has finished its scenario returns immediately; a worker stuck on a
      // mutex permit (or dying late in its teardown) is what exhausts this bound, so it is sized
      // for the slowest CI host.
      t.join(WORKER_JOIN_MILLIS);
      if (t.isAlive()) {
        leaked.add(t.getName());
      }
    }
    if (!leaked.isEmpty()) {
      // Dump BEFORE the interrupts below, which unwind the stacks that explain the leak. This is
      // the only diagnosis this class can get: a worker parked in MetadataWriteMutex.engage waits
      // on a Semaphore, which records no AQS exclusive owner, so the watchdog's detector
      // (ThreadMXBean.findDeadlockedThreads) provably cannot see it, and the watchdog would only
      // fire after its own multi-minute timeout anyway.
      // Labelled with the test whose workers leaked (the TestName rule still holds it during
      // @After): four test classes share one forked JVM, so a block in the CI report has to name
      // its own test to be traceable.
      dumpThreads(getClass().getSimpleName() + "." + name.getMethodName()
          + ": workers did not join within " + WORKER_JOIN_MILLIS + "ms: " + leaked);
    }
    for (var t : spawnedWorkers) {
      if (t.isAlive()) {
        t.interrupt();
      }
    }
    spawnedWorkers.clear();
    if (!leaked.isEmpty()) {
      fail("workers did not join within " + WORKER_JOIN_MILLIS
          + "ms — likely a stuck mutex acquire: " + leaked);
    }
  }

  /**
   * Two concurrent schema transactions serialize on the mutex without either aborting. The first
   * session opens a schema transaction (engaging the mutex on its first schema write) and parks
   * holding it. A second session on another thread starts its own schema transaction; its first
   * schema write must block on the mutex — proven by the second thread not having created its class
   * while the first still holds the permit. Once the first commits and releases, the second proceeds
   * and commits its own class. Neither transaction is rolled back by contention: blocking, not
   * aborting, is the single-writer mechanism.
   */
  @Test
  public void twoConcurrentSchemaTransactionsSerializeWithoutAbort() throws InterruptedException {
    var firstHoldsMutex = new CountDownLatch(1);
    var firstMayCommit = new CountDownLatch(1);
    var secondCreatedClass = new AtomicBoolean(false);
    var secondAborted = new AtomicBoolean(false);
    var secondAboutToEngage = new CountDownLatch(1);

    // First session: open a schema tx, engage the mutex by creating a class, then park holding it
    // until the test releases it. Runs on its own thread so its session activation does not collide
    // with the test thread's default session. One collection per class: these classes exist only to
    // drive a schema transaction, and the default eight would create ~16 files apiece.
    var firstWriter = startWorker("mutex-first-writer", self -> {
      var first = self.openSession();
      first.activateOnCurrentThread();
      first.begin();
      first.getMetadata().getSchema().createClass("FirstSchemaTx", 1);
      firstHoldsMutex.countDown();
      firstMayCommit.await();
      first.commit();
    }, firstHoldsMutex);

    // Declared here so the authoritative error read after the try can reach it.
    Worker secondWriter = null;
    try {
      firstWriter.awaitSignal(firstHoldsMutex, HANDSHAKE_SECONDS,
          "first session must engage the mutex");

      // Second session: try to start a schema tx while the first holds the mutex. Its createClass
      // must block on the mutex, so secondCreatedClass stays false until the first releases.
      secondWriter = startWorker("mutex-second-writer", self -> {
        var second = self.openSession();
        second.activateOnCurrentThread();
        second.begin();
        try {
          // Signal that the very next call is the blocking schema write, so the test can observe
          // this thread park on the permit deterministically.
          secondAboutToEngage.countDown();
          second.getMetadata().getSchema().createClass("SecondSchemaTx", 1);
          // No mutex-holder check here on purpose. engage() overwrites the single holder slot
          // unconditionally, so "the first session no longer holds it" is true the instant THIS
          // write returns even under a hypothetical two-permit mutex that let both writers run
          // concurrently - i.e. it cannot fail, and it cannot prove serialization. What does
          // prove it is below: the park observation and secondCreatedClass staying false while
          // the first holds the permit both go red against a two-permit regression.
          secondCreatedClass.set(true);
          second.commit();
        } catch (Throwable txError) {
          secondAborted.set(true);
          throw txError;
        }
      }, secondAboutToEngage);

      // Wait until the second worker is about to make its blocking schema write, then observe it
      // actually park on the permit (WAITING/TIMED_WAITING, inside the mutex's engage frame) rather
      // than inferring blocking from a sleep. The full-path latch only proves the worker reached
      // the call; the park observation proves the call is parked on the semaphore, so this fails
      // closed if a regression let the second writer through instead of blocking it.
      secondWriter.awaitSignal(secondAboutToEngage, HANDSHAKE_SECONDS,
          "the second worker must reach its blocking schema write");
      // Deliberately short: the worker has already announced the next call blocks, so this observes
      // thread-state settling, not host-dependent database work.
      var observedPark = awaitParkedOnMutex(secondWriter.thread(), PARK_OBSERVATION_MILLIS);
      assertTrue("the second schema tx must park on the mutex while the first holds it, observed "
          + observedPark, observedPark.parkedOnMutex());
      assertFalse("the second schema tx must block on the mutex while the first holds it",
          secondCreatedClass.get());
      assertFalse("the second schema tx must not be aborted by contention — it blocks instead",
          secondAborted.get());

      // Release the first; the second now acquires the mutex and finishes. LIVENESS only: the bound
      // says "the unblocked worker must not hang", never "it must be fast". The serialization
      // itself is what the park observation and the secondCreatedClass check above proved.
      firstMayCommit.countDown();
      secondWriter.awaitDone(UNBLOCKED_COMPLETION_SECONDS,
          "the second schema tx must finish after the first releases the mutex");
    } finally {
      // Unconditional: the first worker parks on an unbounded firstMayCommit.await(), so a failed
      // assertion above must still release it — otherwise the @After join reports a leaked worker
      // instead of the real cause. countDown() is idempotent, so the in-flow release above stands.
      firstMayCommit.countDown();
    }

    // (d) The completion edge before the authoritative error reads. Worker 1's error holder is only
    // final once its own session close has run, so reading it earlier would silently drop a close
    // that throws a moment later. failIfErrored asserts this ordering rather than trusting it.
    firstWriter.awaitDone(UNBLOCKED_COMPLETION_SECONDS,
        "the first schema tx worker must finish, including its own session close");
    firstWriter.failIfErrored("the first schema tx must not error on contention");
    secondWriter.failIfErrored("the second schema tx must not error on contention");

    assertTrue("the second schema tx must have created its class once unblocked",
        secondCreatedClass.get());
    // Both transactions committed without a contention abort: the first held the mutex to its
    // commit, the second blocked and then ran, and neither threw. Single-writer is enforced by
    // blocking, not by aborting. Both classes are visible in the committed schema afterwards: the
    // second transaction seeded its tx-local schema from a fresh committed read after unparking,
    // so it built on the first transaction's just-committed class instead of re-parsing its own
    // stale begin-time snapshot — whose set-diff would have phantom-dropped the first class's
    // collection and made the second commit fail (the stale-seed regression this pins).
    var committedSchema = session.getMetadata().getSchema();
    assertTrue("the first tx's class must survive the second tx's commit",
        committedSchema.existsClass("FirstSchemaTx"));
    assertTrue("the second tx's class must be committed",
        committedSchema.existsClass("SecondSchemaTx"));
  }

  /**
   * A committed-schema reload never engages the metadata-write mutex and never trips the
   * engage-order guard, even though its {@code fromStream} inheritance rebuild ripples a subclass's
   * collection into an indexed superclass's membership inside the reload's own transaction — the
   * shape that, unguarded, made the index-manager seam treat the ripple as the transaction's first
   * schema write and engage the mutex under the schema write lock (the
   * {@code IllegalStateException} "must engage above SchemaShared.lock" red). The test pins both
   * halves: the reload runs while ANOTHER session is parked holding the mutex mid-schema-tx, so a
   * spurious engage attempt could not return (it would park on the held permit and wedge the reload
   * under the schema write lock) — completing promptly proves no engage happened — and afterwards
   * the committed view is intact, the reloading session holds no permit, and a follow-up schema
   * transaction on the same session works (no leaked guard state).
   */
  @Test
  public void schemaReloadWithIndexedSuperclassDoesNotEngageMutex() throws InterruptedException {
    // Committed class graph whose reload ripples: an indexed superclass and a subclass. Built on
    // the legacy top-level DDL path like the sibling tests' setup classes.
    var schema = session.getMetadata().getSchema();
    var parent = schema.createClass("ReloadRippleParent");
    parent.createProperty("name", PropertyType.STRING);
    parent.createIndex("ReloadRippleParent.name", SchemaClass.INDEX_TYPE.NOTUNIQUE, "name");
    schema.createClass("ReloadRippleChild", parent);

    // Another session engages the mutex via a schema tx and parks holding it, so an engage attempt
    // from the reload below cannot succeed silently — it would park and wedge the reload.
    var mutexHeld = new CountDownLatch(1);
    var holderMayFinish = new CountDownLatch(1);
    var holder = startWorker("reload-ripple-mutex-holder", self -> {
      var holderSession = self.openSession();
      holderSession.activateOnCurrentThread();
      holderSession.begin();
      // One collection: this class only has to drive a schema transaction so the mutex is held.
      // (The ReloadRippleParent/Child pair above keeps the default count — the subclass's
      // collection membership rippling into the indexed superclass IS the subject of this test.)
      holderSession.getMetadata().getSchema().createClass("ReloadRippleMutexHolder", 1);
      mutexHeld.countDown();
      holderMayFinish.await();
      holderSession.rollback();
    }, mutexHeld);

    try {
      // Inside the try because the holder parks on an unbounded holderMayFinish.await(): if THIS
      // wait fails the finally still has to release it, or the @After join reports a stranded
      // worker instead of the real cause.
      holder.awaitSignal(mutexHeld, HANDSHAKE_SECONDS,
          "the holder session must engage the mutex");

      // The reload must complete while the mutex is held: the committed-view rebuild is not a
      // schema write, so its inheritance-rebuild ripples are suppressed by the reload guard
      // instead of seeding a tx-local schema state (which would either throw the engage-order
      // IllegalStateException or park on the held permit).
      session.getMetadata().reload();
    } finally {
      holderMayFinish.countDown();
    }

    assertFalse("the reloading session must not hold the mutex after the reload",
        session.getSharedContext().getMetadataWriteMutex().isEngagedBy(session));
    // (d) Completion edge before the authoritative read: the holder's rollback and session close
    // run after it was released above, and either can fail.
    holder.awaitDone(UNBLOCKED_COMPLETION_SECONDS, "the mutex-holder session must finish");
    holder.failIfErrored("the mutex-holder session must not error");
    // The reload rebuilt the committed view intact.
    assertTrue(session.getMetadata().getSchema().existsClass("ReloadRippleParent"));
    assertTrue(session.getMetadata().getSchema().existsClass("ReloadRippleChild"));
    // A follow-up schema transaction on the reloading session works: the reload guard cleared and
    // the mutex is free again once the holder rolled back.
    session.executeInTx(
        tx -> session.getMetadata().getSchema().createClass("ReloadRippleAfter", 1));
    assertTrue(session.getMetadata().getSchema().existsClass("ReloadRippleAfter"));
  }

  /**
   * A held mutex does not block data commits or snapshot-based schema reads. While one session holds
   * the mutex (an open schema tx with a tx-local write), a second session commits a pure-data
   * transaction and performs a snapshot-based schema read; both proceed without waiting on the
   * mutex. This is the mutex-orthogonality property that keeps the low-rate-low-contention premise
   * holding: the mutex only serializes schema writers, never readers or data writers.
   */
  @Test
  public void heldMutexDoesNotBlockDataCommitOrSnapshotRead() throws InterruptedException {
    // A data class for the concurrent data commit to write into. One collection: the test writes a
    // single entity and asserts nothing about collection count.
    session.getMetadata().getSchema().createClass("DataClass", 1);

    var mutexHeld = new CountDownLatch(1);
    var schemaTxMayCommit = new CountDownLatch(1);

    var holder = startWorker("mutex-holder", self -> {
      var holderSession = self.openSession();
      holderSession.activateOnCurrentThread();
      holderSession.begin();
      // Engage the mutex via a schema write, then park holding it. One collection: the class only
      // exists to drive the schema transaction.
      holderSession.getMetadata().getSchema().createClass("MutexHolderClass", 1);
      assertTrue("the schema writer must hold the mutex",
          holderSession.getSharedContext().getMetadataWriteMutex().isEngagedBy(holderSession));
      mutexHeld.countDown();
      schemaTxMayCommit.await();
      holderSession.rollback();
    }, mutexHeld);

    var snapshotReadSawDataClass = new AtomicBoolean(false);
    Worker dataWorker = null;
    try {
      // The isEngagedBy assertion above runs BEFORE the body's countDown, so a holder failure there
      // signals only through the runner's finally; awaitSignal reports the throwable either way.
      holder.awaitSignal(mutexHeld, HANDSHAKE_SECONDS, "the schema writer must engage the mutex");

      // On a separate thread (so it does not touch the test session), run a pure-data commit and a
      // snapshot-based schema read while the mutex is held. Both must complete without waiting on
      // the mutex: a regression that made them take it would block until the holder released, which
      // only happens AFTER this wait, so this is a liveness bound on a permanent block — not a
      // performance claim — and is sized for the slowest CI host.
      dataWorker = startWorker("data-writer-and-reader", self -> {
        var dataSession = self.openSession();
        dataSession.activateOnCurrentThread();
        // Pure-data commit: create an entity in an existing class. Touches no schema, so it never
        // engages the mutex and must not block behind the held permit.
        dataSession.executeInTx(tx -> {
          var entity = dataSession.newEntity("DataClass");
          entity.setProperty("v", 1);
        });
        // Snapshot-based schema read: a plain existsClass goes through the immutable snapshot,
        // which does not take the mutex.
        snapshotReadSawDataClass.set(
            dataSession.getMetadata().getSchema().existsClass("DataClass"));
      });

      dataWorker.awaitDone(UNBLOCKED_COMPLETION_SECONDS,
          "a data commit and a snapshot read must complete while the mutex is held — they do not"
              + " wait on the schema mutex");
      assertTrue("the snapshot-based schema read must have run unblocked",
          snapshotReadSawDataClass.get());
    } finally {
      // Unconditional: the holder parks on an unbounded schemaTxMayCommit.await(), so any failure
      // above must still release it or the @After join reports a leaked worker instead of the real
      // cause.
      schemaTxMayCommit.countDown();
    }

    // (d) Completion edges before the authoritative reads.
    holder.awaitDone(UNBLOCKED_COMPLETION_SECONDS, "the mutex holder must finish");
    holder.failIfErrored("the mutex holder must not error");
    if (dataWorker != null) {
      dataWorker.failIfErrored("the data path must not be blocked by the mutex");
    }
  }

  /**
   * The same thread cannot open a second session's schema transaction while it still holds the mutex
   * through the first session. This is the legal embedded-session case: rather than parking forever
   * on a permit its own thread holds (a self-deadlock), the inner engage throws loudly. Driven
   * directly against the mutex with two real session objects on one thread, which is exactly the
   * holder/engage relationship a same-thread embedded session produces.
   */
  @Test
  public void sameThreadSecondSessionEngageThrows() {
    var mutex = session.getSharedContext().getMetadataWriteMutex();
    var outer = session;
    var inner = openDatabase();
    var outerOrdinal = 0L;
    try {
      outerOrdinal = mutex.engage(outer);
      assertTrue("the outer session must hold the mutex", mutex.isEngagedBy(outer));
      try {
        mutex.engage(inner);
        fail("engaging on a thread that already holds the mutex through a different session must"
            + " throw rather than self-deadlock");
      } catch (IllegalStateException expected) {
        assertTrue("the reject must name the same-thread different-session cause",
            expected.getMessage().contains("different session"));
      }
      // The outer session still holds the permit; the failed inner engage did not release it.
      assertTrue("a rejected same-thread engage must not disturb the outer session's hold",
          mutex.isEngagedBy(outer));
    } finally {
      mutex.releaseFor(outer, outerOrdinal);
      inner.activateOnCurrentThread();
      inner.close();
      outer.activateOnCurrentThread();
    }
  }

  /**
   * A foreign thread parks on a held mutex and proceeds only once the permit is released. The test
   * thread engages the mutex through its session; a second thread tries to engage through another
   * session and must block until the test thread releases. Driven directly against the mutex so the
   * park/unpark timing is observable without a full schema-tx teardown.
   */
  @Test
  public void differentThreadParksUntilRelease() throws InterruptedException {
    var mutex = session.getSharedContext().getMetadataWriteMutex();
    var foreignEngaged = new CountDownLatch(1);
    var foreignSession = new AtomicReference<DatabaseSessionEmbedded>();
    var foreignAboutToEngage = new CountDownLatch(1);
    var foreignOrdinal = new AtomicLong();

    var ownOrdinal = mutex.engage(session);
    // Declared here so the finally below can read it; assigned before any wait on it.
    Worker parker = null;
    try {
      // The session is opened INSIDE the body (self.openSessionForTest), so a failure there is
      // recorded and released like any other; the test thread closes it on the happy path.
      parker = startWorker("mutex-foreign-parker", self -> {
        var other = self.openSessionForTest();
        foreignSession.set(other);
        other.activateOnCurrentThread();
        // Signal that the next call blocks, so the test can observe this thread park on the permit
        // deterministically.
        foreignAboutToEngage.countDown();
        // Blocks here until the test thread releases the permit.
        foreignOrdinal.set(mutex.engage(other));
        foreignEngaged.countDown();
      }, foreignAboutToEngage, foreignEngaged);

      // While the test thread holds the permit, the foreign thread must park on it. Observe the
      // parked thread state deterministically rather than inferring parking from a negative await.
      parker.awaitSignal(foreignAboutToEngage, HANDSHAKE_SECONDS,
          "the foreign worker must reach its blocking engage");
      // Deliberately short: the worker has already announced that its next call is the engage, so
      // this window only has to cover thread-state settling.
      var observedPark = awaitParkedOnMutex(parker.thread(), PARK_OBSERVATION_MILLIS);
      assertTrue("a foreign thread must park on the held mutex, observed " + observedPark,
          observedPark.parkedOnMutex());
      // Probes the ORDINAL, not the latch: foreignEngaged is also released when the worker dies, so
      // a latch-count assertion here could only ever go red, never distinguish "engage completed
      // early" (the regression) from "the worker failed". The ordinal is written only by a
      // successful engage, and 0 is not a valid ordinal (MetadataWriteMutex numbers from 1).
      assertEquals("the foreign engage must not have completed while the permit is held",
          0L, foreignOrdinal.get());
    } finally {
      mutex.releaseFor(session, ownOrdinal);
    }

    parker.awaitSignal(foreignEngaged, UNBLOCKED_COMPLETION_SECONDS,
        "the foreign thread must engage once the permit is released");
    // (d) Completion edge before the authoritative read.
    parker.awaitDone(UNBLOCKED_COMPLETION_SECONDS, "the foreign worker must finish");
    parker.failIfErrored("the foreign engage must succeed after release");
    // Release on the foreign thread's behalf and close its session so the @After join is clean.
    var other = foreignSession.get();
    assertNotNull("the foreign session must have been opened", other);
    mutex.releaseFor(other, foreignOrdinal.get());
    other.activateOnCurrentThread();
    other.close();
    session.activateOnCurrentThread();
  }

  /**
   * The engage-order guard rejects an engage attempted while the current thread already holds the
   * schema write lock. Engaging from inside a shared-lock acquisition is the deadlock
   * shape the guard defends against: a second transaction would park on the mutex while holding a
       * shared write lock, freezing lock-based reads and deadlocking against the commit-side lock
       * acquisition. The guard is an always-on runtime throw (an {@link IllegalStateException}), not an
       * assert, so it survives the production default of disabled assertions. Driven by holding the schema write lock and then routing a schema write
   * through {@code ensureTxSchemaState}, which is where the engage and its order guard live.
   */
  @Test
  public void engageOrderGuardRejectsWhenSchemaLockHeld() {
    var schema = session.getSharedContext().getSchema();
    session.begin();
    schema.acquireSchemaWriteLock(session);
    try {
      session.ensureTxSchemaState();
      fail("engaging the mutex while holding the schema write lock must be rejected by the"
          + " engage-order guard");
    } catch (IllegalStateException expected) {
      assertTrue("the rejection message must explain the engage-above-schema-lock requirement",
          expected.getMessage() != null && expected.getMessage().contains("SchemaShared.lock"));
    } finally {
      schema.releaseSchemaWriteLock(session, false);
      session.rollback();
    }
  }

  /**
   * The engage-order guard rejects an engage attempted while the current thread already holds the
   * index-manager write lock. Same engage-from-inside-a-held-lock hazard as the schema-lock case but
   * for the other shared metadata lock the de-guarded index-manager paths take, so the guard must
   * reject holding either lock at engage time, and it must do so at runtime under disabled assertions.
   */
  @Test
  public void engageOrderGuardRejectsWhenIndexManagerLockHeld() throws Exception {
    var indexManager = session.getSharedContext().getIndexManager();
    // The index-manager write lock is a private field guarded by protected acquire/release methods
    // that take a transaction. Acquire it directly via reflection so the engage runs with only that
    // lock held — the faithful "engaged from inside the index-manager lock" hazard — without adding
    // a test-only acquire seam to production code.
    var lockField = indexManager.getClass().getDeclaredField("lock");
    lockField.setAccessible(true);
    var rwLock = (java.util.concurrent.locks.ReentrantReadWriteLock) lockField.get(indexManager);

    session.begin();
    rwLock.writeLock().lock();
    try {
      assertTrue("the index-manager write lock must be held for the test",
          indexManager.isWriteLockHeldByCurrentThread());
      session.ensureTxSchemaState();
      fail(
          "engaging the mutex while holding the index-manager write lock must be rejected by the"
              + " engage-order guard");
    } catch (IllegalStateException expected) {
      assertTrue(
          "the rejection message must explain the engage-above-index-manager-lock requirement",
          expected.getMessage() != null && expected.getMessage().contains("index-manager"));
    } finally {
      rwLock.writeLock().unlock();
      session.rollback();
    }

    // Sanity: with no lock held, the engage-order assert passes and the seed engages the mutex.
    session.begin();
    try {
      assertNotNull("a well-ordered engage must seed the tx-local state",
          session.ensureTxSchemaState());
      assertTrue("a well-ordered first schema write must engage the mutex",
          session.getSharedContext().getMetadataWriteMutex().isEngagedBy(session));
    } finally {
      session.rollback();
    }
    assertFalse("the mutex must be released once the outermost frame closes",
        session.getSharedContext().getMetadataWriteMutex().isEngagedBy(session));
  }

  /**
   * The same-thread second-session reject fires through the real production seam — a schema write
   * routed via {@code ensureTxSchemaState} — not just against bare {@code mutex.engage} calls. The
   * test thread opens a schema transaction on the outer session (engaging the mutex by creating a
   * class), then opens a second session on the same thread and attempts a schema write through it;
   * that write must throw loudly rather than self-deadlock on a permit its own thread already holds.
   * Driving the reject through {@code createClass} (not a direct {@code engage}) makes the wiring
   * load-bearing: if {@code ensureTxSchemaState} ever stopped engaging the mutex, this test fails
   * where the primitive-only reject test would not. After the outer transaction's outermost frame
   * closes, the permit must be released through the real {@code close()} teardown.
   */
  @Test
  public void sameThreadSecondSessionSchemaWriteThrowsThroughProductionPath() {
    var outer = session;
    outer.begin();
    // First schema write engages the mutex through the production seam (ensureTxSchemaState).
    outer.getMetadata().getSchema().createClass("OuterTxClass", 1);
    assertTrue("the outer schema write must engage the mutex through the production seam",
        outer.getSharedContext().getMetadataWriteMutex().isEngagedBy(outer));

    var inner = openDatabase();
    try {
      inner.activateOnCurrentThread();
      inner.begin();
      // The inner session's first schema write reaches engage on a thread that already holds the
      // permit through the outer session — it must throw the same-thread different-session reject
      // rather than park forever on the single permit.
      var ex =
          assertThrows(
              "a same-thread second session's schema write must throw rather than self-deadlock",
              IllegalStateException.class,
              () -> inner.getMetadata().getSchema().createClass("InnerTxClass", 1));
      assertTrue("the reject must name the same-thread different-session cause: " + ex.getMessage(),
          ex.getMessage() != null && ex.getMessage().contains("different session"));
      inner.rollback();
    } finally {
      inner.activateOnCurrentThread();
      inner.close();
      outer.activateOnCurrentThread();
      // The outer rollback's close() must release the permit through the real teardown path.
      outer.rollback();
    }
    assertFalse("the outer rollback's close() must release the permit through the real teardown",
        outer.getSharedContext().getMetadataWriteMutex().isEngagedBy(outer));
  }

  /**
   * A failed seed releases the permit so the next schema writer is not stranded. When the first
   * schema write of a transaction engages the mutex and the subsequent tx-local copy seed throws,
   * {@code ensureTxSchemaState} releases the permit in its catch arm before rethrowing. Without that
   * release the single permit would be held forever (the custom-data marker that records "the seed
   * exists" was never written, so a same-tx retry would re-engage on the holding thread and a
   * foreign thread would park forever). This test forces the seed to throw by stubbing the committed
   * schema's {@code copyForTx} to fail, asserts the throw surfaces, then proves the concurrency
   * consequence the release prevents: a second schema transaction on another thread engages promptly
   * rather than parking on a leaked permit.
   */
  @Test
  public void seedFailureReleasesPermitSoTheNextWriterIsNotStranded() throws Exception {
    var sharedContext = session.getSharedContext();
    var mutex = sharedContext.getMetadataWriteMutex();
    var realSchema = sharedContext.getSchema();

    // Stub copyForTx to throw so the seed fails after the mutex is engaged but before the marker is
    // written — exactly the engage-then-failed-seed window the catch-arm release covers. A spy keeps
    // every other schema read delegating to the real instance, so only the seed path fails.
    var failingSchema = org.mockito.Mockito.spy(realSchema);
    org.mockito.Mockito.doThrow(new RuntimeException("forced seed failure"))
        .when(failingSchema)
        .copyForTx(org.mockito.ArgumentMatchers.any());

    var schemaField =
        com.jetbrains.youtrackdb.internal.core.db.SharedContext.class.getDeclaredField("schema");
    schemaField.setAccessible(true);
    schemaField.set(sharedContext, failingSchema);
    try {
      session.begin();
      try {
        var thrown =
            assertThrows(
                "a seed whose copyForTx fails must rethrow",
                RuntimeException.class,
                session::ensureTxSchemaState);
        assertNotNull("the rethrown seed failure must carry its cause", thrown);
        // The catch arm must have released the permit before rethrowing.
        assertFalse("a failed seed must not strand the permit on the failing session",
            mutex.isEngagedBy(session));
      } finally {
        session.rollback();
      }
    } finally {
      // Restore the real schema before exercising the next writer, so its seed succeeds.
      schemaField.set(sharedContext, realSchema);
    }

    assertFalse("after the failed seed and rollback the permit must be free",
        mutex.isEngagedBy(session));

    // The concurrency consequence: a fresh schema transaction on another thread must engage and
    // commit promptly. If the failed seed had stranded the permit, this worker would park forever on
    // engage and the bounded join below would leave it alive, failing the test.
    var nextWriter = startWorker("post-seed-failure-writer", self -> {
      var next = self.openSession();
      next.activateOnCurrentThread();
      next.begin();
      next.getMetadata().getSchema().createClass("AfterSeedFailure", 1);
      next.commit();
    });
    // LIVENESS: a stranded permit parks this worker forever, so only a hang exhausts the bound.
    nextWriter.joinBounded(WORKER_JOIN_MILLIS);
    assertFalse("the next schema writer must not be stranded behind a leaked permit",
        nextWriter.isAlive());
    // (d) The join above is the completion edge for this read.
    nextWriter.failIfErrored("the next schema writer must engage and commit after a failed seed");
  }

  /**
   * A teardown whose rollback SKIPS entirely — the transaction reads as already rolled back, so
   * {@code session.rollback()}'s isActive gate bypasses rollbackInternal and tx.close() (the
   * normal release site) never runs — must still release the permit through the widened release
   * pass in internalClose's outer finally. Driven by forcing the open transaction's status to
   * ROLLED_BACK: the teardown completes "normally" (no throw) but without ever reaching
   * tx.close(). The sibling test below covers the teardown-THROWS shape of the same widened
   * release contract.
   */
  @Test
  public void teardownWithSkippedRollbackStillReleasesPermit() {
    var mutex = session.getSharedContext().getMetadataWriteMutex();
    var victim = openDatabase();
    victim.activateOnCurrentThread();
    victim.begin();
    victim.getMetadata().getSchema().createClass("RollbackSkipVictim", 1);
    assertTrue("the schema write must engage the mutex", mutex.isEngagedBy(victim));

    // Force the state session.rollback()'s isActive gate skips: the teardown then never calls
    // rollbackInternal, so tx.close() — and its release finally — never runs.
    var tx = (FrontendTransactionImpl) victim.getTransactionInternal();
    tx.setStatus(FrontendTransaction.TXSTATUS.ROLLED_BACK);
    victim.close();

    assertFalse("the widened outer-finally release pass must free the permit even when the"
        + " teardown's rollback was skipped and tx.close() never ran",
        mutex.isEngagedBy(victim));
    // Clean up the transaction object the skipped rollback left dangling (its atomic operation
    // was never deactivated and its tsMin never reset): close it explicitly on this thread.
    victim.activateOnCurrentThread();
    tx.close();
    // The permit is usable, not merely unrecorded: the next schema transaction proceeds.
    session.activateOnCurrentThread();
    session.executeInTx(
        tx2 -> session.getMetadata().getSchema().createClass("AfterRollbackSkip", 1));
    assertTrue(session.getMetadata().getSchema().existsClass("AfterRollbackSkip"));
  }

  /**
   * A teardown that genuinely THROWS before tx.close() must still release the permit through the
   * widened outer-finally release pass, with the failure propagating to the closer. Driven
   * deterministically: a session listener's {@code onBeforeTxRollback} throws an
   * {@link AssertionError}, which the listener loop does NOT swallow (it absorbs only
   * {@code Exception}), so the teardown's rollback aborts before clear()/tx.close() and the error
   * escapes internalClose — the exact pre-tx-close strand shape the widened release exists for.
   * The failed teardown also releases the atomic teardown claim, so a later cleanup close can
   * retry and fully close the broken session.
   */
  @Test
  public void teardownThrowBeforeTxCloseStillReleasesPermit() {
    var mutex = session.getSharedContext().getMetadataWriteMutex();
    var victim = openDatabase();
    victim.activateOnCurrentThread();
    var listener = new SessionListener() {
      @Override
      public void onBeforeTxRollback(
          final com.jetbrains.youtrackdb.internal.core.tx.Transaction transaction) {
        throw new AssertionError("forced pre-rollback teardown failure");
      }
    };
    victim.registerListener(listener);
    try {
      victim.begin();
      victim.getMetadata().getSchema().createClass("RollbackThrowVictim", 1);
      assertTrue("the schema write must engage the mutex", mutex.isEngagedBy(victim));
      try {
        victim.close();
        fail("the teardown must propagate the pre-tx-close failure");
      } catch (final AssertionError expected) {
        assertTrue("the propagated failure must be the injected one",
            expected.getMessage().contains("forced pre-rollback teardown failure"));
      }
      assertFalse("the widened outer-finally release pass must free the permit even when the"
          + " teardown threw before tx.close()",
          mutex.isEngagedBy(victim));
    } finally {
      // The failed teardown released the teardown claim, so this retry close completes the
      // broken session's teardown (the listener is gone, the rollback proceeds normally).
      victim.unregisterListener(listener);
      victim.activateOnCurrentThread();
      victim.close();
      session.activateOnCurrentThread();
    }
    // The permit is usable by the next writer.
    session.executeInTx(
        tx -> session.getMetadata().getSchema().createClass("AfterRollbackThrow", 1));
    assertTrue(session.getMetadata().getSchema().existsClass("AfterRollbackThrow"));
  }

  /**
   * Dekker pair, teardown-first shape: an engage attempted on a session already marked for
   * teardown must fail loudly WITHOUT acquiring (or while self-releasing), leaving the permit
   * free. Covers the wait-loop's self-check and the post-acquire re-check with one observable
   * contract: a marked session cannot walk away holding the permit, and the failure is a
   * DatabaseException, not a silent park.
   */
  @Test
  public void engageOnTeardownMarkedSessionFailsLoudAndLeavesPermitFree() {
    var mutex = session.getSharedContext().getMetadataWriteMutex();
    var marked = openDatabase();
    marked.activateOnCurrentThread();
    marked.begin();
    marked.markTeardownIntent();
    try {
      try {
        marked.getMetadata().getSchema().createClass("MarkedSessionClass", 1);
        fail("a schema write on a teardown-marked session must fail loudly");
      } catch (final DatabaseException expected) {
        assertTrue("the failure must name the closed-while-engaging cause",
            expected.getMessage().contains("while"));
      }
      assertFalse("a rejected engage must leave the permit free", mutex.isEngagedBy(marked));
    } finally {
      marked.getTransactionInternal().rollbackInternal();
      marked.clearTeardownIntent();
      marked.close();
      session.activateOnCurrentThread();
    }
    // The permit is genuinely free: the next writer engages and commits.
    session.executeInTx(
        tx -> session.getMetadata().getSchema().createClass("AfterMarkedReject", 1));
  }

  /**
   * Dekker pair, engage-first shape: a foreign-thread teardown of a session holding an engaged
   * permit harvests the ordinal through the release funnel and frees the permit — the pool-close
   * heal path. The owner parks holding an open schema transaction; the test thread (playing the
   * pool thread) activates the session and runs the full teardown; the permit must be free
   * afterwards and the next writer must proceed.
   */
  @Test
  public void foreignTeardownHarvestsEngagedPermit() throws InterruptedException {
    var mutex = session.getSharedContext().getMetadataWriteMutex();
    var engaged = new CountDownLatch(1);
    var ownerMayFinish = new CountDownLatch(1);
    var ownerSession = new AtomicReference<DatabaseSessionEmbedded>();
    // The session is opened INSIDE the body; the TEST thread tears it down on the happy path, so
    // the runner closes it only if this worker failed.
    var ownerWorker = startWorker("foreign-teardown-owner", self -> {
      var owner = self.openSessionForTest();
      ownerSession.set(owner);
      owner.activateOnCurrentThread();
      owner.begin();
      owner.getMetadata().getSchema().createClass("ForeignTeardownClass", 1);
      engaged.countDown();
      ownerMayFinish.await();
    }, engaged);

    try {
      ownerWorker.awaitSignal(engaged, HANDSHAKE_SECONDS, "the owner must engage the mutex");
      var owner = ownerSession.get();
      assertTrue("the owner session must hold the permit", mutex.isEngagedBy(owner));

      // Foreign teardown (the pool-close shape): activate the owner's session on THIS thread and
      // run its own full teardown. The release pass harvests the engage's ordinal and frees the
      // permit; the (session, ordinal) CAS is the second belt.
      owner.activateOnCurrentThread();
      owner.internalClose(false);
      assertFalse("the foreign teardown must harvest the engaged permit",
          mutex.isEngagedBy(owner));
    } finally {
      // Unconditional: the owner parks on an unbounded ownerMayFinish.await(), so any failure above
      // must still release it or the @After join reports a leaked worker instead of the real cause.
      ownerMayFinish.countDown();
    }

    // (d) Completion edge before the authoritative read: the owner unparks only once released.
    ownerWorker.awaitDone(UNBLOCKED_COMPLETION_SECONDS, "the owner worker must finish");
    ownerWorker.failIfErrored("the owner must not error");

    session.activateOnCurrentThread();
    // The permit is usable by the next writer.
    session.executeInTx(
        tx -> session.getMetadata().getSchema().createClass("AfterForeignTeardown", 1));
    assertTrue(session.getMetadata().getSchema().existsClass("AfterForeignTeardown"));
  }

  /**
   * Dekker pair, post-acquire re-check shape (the engage-side belt of the V2 ordering): the
   * teardown-intent mark lands while the engage is PARKED in the permit wait — past the loop-top
   * self-check — so the engage ACQUIRES the permit and only then sees the mark; it must
   * self-release through the atomic claim and throw. Deterministic drive: the engage parks behind
   * a held permit, the mark is set while it is parked, then the holder releases — the woken
   * tryAcquire succeeds and the next mark read is the post-acquire re-check. The distinct
   * "while engaging" message pins that exact branch (the loop-top arm says "while waiting to
   * engage"); the permit must be free afterwards (self-released, not stranded).
   */
  @Test
  public void postAcquireDekkerRecheckSelfReleasesAndThrows() throws InterruptedException {
    var mutex = session.getSharedContext().getMetadataWriteMutex();
    var holderOrdinal = mutex.engage(session); // the permit is held, so the victim's engage parks
    var victimRef = new AtomicReference<DatabaseSessionEmbedded>();
    // victimThrown captures the engage failure this test is ASSERTING on, caught inside the body so
    // it never becomes the worker's error; the worker's own holder covers everything else.
    var victimThrown = new AtomicReference<Throwable>();
    var victimReady = new CountDownLatch(1);
    var victim = startWorker("post-acquire-dekker-victim", self -> {
      var victimSession = self.openSession();
      victimRef.set(victimSession);
      victimSession.activateOnCurrentThread();
      victimSession.begin();
      victimReady.countDown();
      try {
        // Parks in the engage wait; after the holder releases, the acquire succeeds and the
        // post-acquire re-check sees the mark set below.
        victimSession.getMetadata().getSchema().createClass("PostAcquireDekker", 1);
      } catch (Throwable t) {
        victimThrown.set(t);
      } finally {
        victimSession.getTransactionInternal().rollbackInternal();
        victimSession.clearTeardownIntent();
        victimSession.close();
      }
    }, victimReady);

    try {
      victim.awaitSignal(victimReady, HANDSHAKE_SECONDS,
          "the victim must reach its blocking schema write");
      // Deliberately short: victimReady already proves the victim reached its blocking write, so
      // only thread-state settling is being waited on here.
      var observedPark = awaitParkedOnMutex(victim.thread(), PARK_OBSERVATION_MILLIS);
      assertTrue("the victim must park on the held permit, observed " + observedPark,
          observedPark.parkedOnMutex());
      // The mark lands while the victim is parked INSIDE the permit wait — past its loop-top
      // check — so only the post-acquire re-check can see it.
      victimRef.get().markTeardownIntent();
    } finally {
      mutex.releaseFor(session, holderOrdinal);
    }

    // (d) Completion edge, then the authoritative read: a rollback or close failure in the victim's
    // own cleanup lands in its holder just before it finishes.
    victim.awaitDone(UNBLOCKED_COMPLETION_SECONDS, "the victim must finish");
    victim.failIfErrored("the victim's session handling must not error");
    assertNotNull("the marked victim's engage must have failed", victimThrown.get());
    assertTrue("the failure must be the post-acquire self-release branch (message pins the"
        + " 'while engaging' arm, not the loop-top 'while waiting' arm): " + victimThrown.get(),
        victimThrown.get() instanceof DatabaseException
            && victimThrown.get().getMessage().contains("while engaging the metadata-write mutex"));
    // The self-release freed the permit: the next writer engages and commits.
    session.executeInTx(
        tx -> session.getMetadata().getSchema().createClass("AfterPostAcquireDekker", 1));
    assertTrue(session.getMetadata().getSchema().existsClass("AfterPostAcquireDekker"));
  }

  /**
   * The atomic one-shot teardown claim: two racing full teardowns of one session run EXACTLY one
   * full teardown body — the close listeners fire once and the storage session count is
   * decremented once (a double decrement would skew the count and could auto-close the storage
   * under a live session). Deterministic under any interleaving: the first teardown is held open
   * INSIDE its close listener while the second teardown runs to completion — with a plain
   * status-based guard the second would pass (the status flips CLOSED only after the listeners)
   * and double-run; with the claim it must no-op.
   */
  @Test
  public void concurrentTeardownsRunExactlyOneFullTeardown() throws InterruptedException {
    var victim = openDatabase();
    var closeListenerFired = new AtomicLong();
    var firstInListener = new CountDownLatch(1);
    var releaseListener = new CountDownLatch(1);
    victim.registerListener(new SessionListener() {
      @Override
      public void onClose(final DatabaseSessionEmbedded database) {
        closeListenerFired.incrementAndGet();
        firstInListener.countDown();
        try {
          // The overlap window the whole test rests on: the winning teardown stays inside this
          // listener while the losing one runs. Both bounds below must stay strictly under it.
          releaseListener.await(LISTENER_HOLD_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
    });

    // firstInListener is counted down by the close listener, so a winner that throws BEFORE its
    // listener fires would never signal it on its own; declaring it here releases the waiter on
    // that path too, and awaitSignal then reports the throwable rather than the missing signal.
    var first = startWorker("teardown-first", self -> {
      victim.activateOnCurrentThread();
      victim.internalClose(false);
    }, firstInListener);
    Worker second = null;
    try {
      // LIVENESS, not a short bound: the hold clock starts when the winner ENTERS the listener,
      // which is the same moment this wait returns, so time spent reaching the listener consumes no
      // hold time and widening this cannot make the test vacuous.
      first.awaitSignal(firstInListener, HANDSHAKE_SECONDS,
          "the first teardown must reach its close listener");

      // The second teardown runs while the first is mid-body (status still OPEN): it must no-op on
      // the atomic claim rather than double-running the listeners and the session-count decrement.
      second = startWorker("teardown-second", self -> {
        victim.activateOnCurrentThread();
        victim.internalClose(false);
      });
      // Deliberately SHORT, and the one bound that must stay under LISTENER_HOLD_SECONDS (asserted
      // at class init): "promptly" means "while the winner is still pinned inside its close
      // listener", and the loser runs one CAS and no I/O, so a slow host cannot need more — only a
      // regression that actually re-ran the teardown body would.
      second.joinBounded(NOOP_TEARDOWN_JOIN_MILLIS);
      assertFalse("the losing teardown must no-op promptly", second.isAlive());
    } finally {
      // Unconditional: a failed assertion above must not leave the winning teardown pinned in its
      // close listener for the rest of the hold.
      releaseListener.countDown();
    }

    first.joinBounded(WORKER_JOIN_MILLIS);
    assertFalse("the winning teardown must complete", first.isAlive());
    // (d) Both joins are completion edges: a teardown that threw — the winner late in its body, or
    // the loser instead of no-opping — used to pass unnoticed here.
    first.failIfErrored("the winning teardown must not throw");
    second.failIfErrored("the losing teardown must not throw");
    assertEquals("exactly one full teardown may run (single listener firing, single"
        + " session-count decrement)", 1L, closeListenerFired.get());

    // The storage session accounting is intact: another session opens, works, and closes.
    session.activateOnCurrentThread();
    session.executeInTx(
        tx -> session.getMetadata().getSchema().createClass("AfterClaimRace", 1));
    assertTrue(session.getMetadata().getSchema().existsClass("AfterClaimRace"));
  }

  /**
   * Double release keeps a single permit. An explicit early release (playing the foreign
   * teardown's pass) followed by the owner's own tx-close release must free the permit exactly
   * once: the session-level atomic ordinal claim lets only one releaser through, and a stale
   * ordinal presented directly to the mutex warn-noops. Proven by observing the single-permit
   * property afterwards: with one session holding the permit, a second engager PARKS — a
   * double-released (double-incremented) permit would admit it immediately.
   */
  @Test
  public void doubleReleaseKeepsSinglePermit() throws InterruptedException {
    var mutex = session.getSharedContext().getMetadataWriteMutex();
    session.begin();
    session.getMetadata().getSchema().createClass("DoubleReleaseClass", 1);
    assertTrue(mutex.isEngagedBy(session));

    // First releaser (the foreign teardown's pass in miniature): claims the ordinal and releases.
    session.releaseMetadataWriteMutexForTx();
    assertFalse("the first release must free the permit", mutex.isEngagedBy(session));
    // A stale re-presentation directly to the mutex must warn-noop, not release again.
    mutex.releaseFor(session, 999_999L);

    // Second releaser (the owner's tx-close finally): the atomic claim returns 0 — no-op.
    session.commit();

    // Single-permit proof: engage through one session, then a second engager must PARK rather
    // than acquire a phantom second permit.
    var firstOrdinal = mutex.engage(session);
    var proberReady = new CountDownLatch(1);
    var acquired = new CountDownLatch(1);
    var secondOrdinal = new AtomicLong();
    var secondSession = new AtomicReference<DatabaseSessionEmbedded>();
    // BOTH latches are declared: the test waits on proberReady first and on acquired afterwards, so
    // a prober that fails inside engage() has to release the second one too - releasing only the
    // first left the later wait to stall out its whole bound and discard the real error.
    var prober = startWorker("double-release-prober", self -> {
      var other = self.openSessionForTest();
      secondSession.set(other);
      other.activateOnCurrentThread();
      // Latch handshake, not a busy-spin the observer runs: opening the session above is real disk
      // work, and a 60 s Thread.onSpinWait() loop would hot-spin a core inside a fork that runs
      // four test classes in parallel.
      proberReady.countDown();
      secondOrdinal.set(mutex.engage(other));
      acquired.countDown();
    }, proberReady, acquired);

    try {
      // LIVENESS: the prober signals only after opening its own session, which is real disk work,
      // so this is sized for the slowest CI host. It returns as soon as the signal lands.
      prober.awaitSignal(proberReady, HANDSHAKE_SECONDS, "the prober must start");
      // Deliberately short: the prober's next call after signalling IS the engage.
      var observedPark = awaitParkedOnMutex(prober.thread(), PARK_OBSERVATION_MILLIS);
      assertTrue("a second engager must park on the single permit (a double release would have"
          + " admitted it immediately), observed " + observedPark,
          observedPark.parkedOnMutex());
      // Probes the ORDINAL for the same reason as the foreign parker above: `acquired` is released
      // on the failure path too, while the ordinal is written only by a successful engage.
      assertEquals("the prober must not have acquired while the permit is held",
          0L, secondOrdinal.get());
    } finally {
      // Unconditional, same reason as the latch releases elsewhere in this class: the prober parks
      // on this permit, so a failed assertion above must still free it. Otherwise the prober is
      // stranded and the @After join reports "a stuck mutex acquire" on top of — and ahead of — the
      // real failure.
      mutex.releaseFor(session, firstOrdinal);
    }

    prober.awaitSignal(acquired, UNBLOCKED_COMPLETION_SECONDS,
        "the prober must acquire after the release");
    // (d) Completion edge before the authoritative read.
    prober.awaitDone(UNBLOCKED_COMPLETION_SECONDS, "the prober must finish");
    prober.failIfErrored("the prober must engage after the release");
    mutex.releaseFor(secondSession.get(), secondOrdinal.get());
    var other = secondSession.get();
    other.activateOnCurrentThread();
    other.close();
    session.activateOnCurrentThread();
  }

  /**
   * A same-session re-engage on a stranded holder throws immediately instead of parking forever
   * on the session's own permit. The strand is simulated by engaging the mutex directly (no
   * session-side ordinal record, so no teardown will ever release it); the next schema write on
   * the same session must throw {@link IllegalStateException} naming the stranded holder and the
   * likely cause — the type and message are pinned contract.
   */
  @Test
  public void strandedSameSessionReengageThrowsLoudly() {
    var mutex = session.getSharedContext().getMetadataWriteMutex();
    var strandedOrdinal = mutex.engage(session);
    try {
      session.begin();
      try {
        var thrown = assertThrows(
            "a same-session re-engage on a stranded holder must throw, not park",
            IllegalStateException.class,
            () -> session.getMetadata().getSchema().createClass("StrandedReengage", 1));
        assertTrue("the message must name the stranded-holder state: " + thrown.getMessage(),
            thrown.getMessage().contains("already held by this session"));
        assertTrue("the message must name the likely cause: " + thrown.getMessage(),
            thrown.getMessage().contains("never released"));
      } finally {
        session.rollback();
      }
    } finally {
      mutex.releaseFor(session, strandedOrdinal);
    }
  }

  /**
   * Q-A2 skip protocol, owner-completes interleaving: a pool close that finds the session
   * mid-commit on its owner thread defers the teardown to the owner. The pool thread performs
   * only the whitelist (mark + log): the commit is undisturbed and completes successfully, the
   * owner's completer then runs the full teardown on the owning thread, the permit is freed, the
   * committed class is visible, and the storage remains fully usable (the session count was
   * decremented exactly once, by the owner's completer — no premature storage auto-close).
   */
  @Test
  public void poolCloseDuringCommitDefersTeardownToOwner() throws Exception {
    var storage = (AbstractStorage) session.getStorage();
    var mutex = session.getSharedContext().getMetadataWriteMutex();
    var inWindow = new CountDownLatch(1);
    var releaseWindow = new CountDownLatch(1);
    storage.setCommitWindowTestHook(() -> {
      inWindow.countDown();
      try {
        releaseWindow.await();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RuntimeException(e);
      }
    });

    var pooledRef = new AtomicReference<DatabaseSessionEmbedded>();
    Worker owner = null;
    Worker poolCloser = null;
    try {
      owner = startWorker("pool-skip-owner", self -> {
        // (e) exemption: a borrowed pool session is the POOL's to close, and pool.close() below is
        // what this test drives. Closing it from the worker would pre-empt the skip protocol under
        // test, so it is deliberately neither worker- nor test-owned.
        var pooled = pool.acquire();
        pooledRef.set(pooled);
        pooled.begin();
        pooled.getMetadata().getSchema().createClass("PoolSkipClass", 1);
        pooled.commit();
      }, inWindow);

      // inWindow is signalled by the commit-window hook, so an owner that fails before reaching the
      // window never signals it on its own; declaring it releases this wait on that path too.
      owner.awaitSignal(inWindow, HANDSHAKE_SECONDS,
          "the owner must park inside the commit window");

      // Pool close while the owner is mid-commit: the skip branch marks and defers. Run it on
      // its own thread with a bounded await so a skip regression (a full teardown that parks on
      // the commit's held locks) FAILS the test instead of hanging the fork — the window latch is
      // still released by the finally below either way.
      poolCloser = startWorker("pool-closer", self -> pool.close());
      // LIVENESS despite the "promptly" wording: the regression this catches is a full teardown
      // that parks on the live commit's held locks and therefore NEVER returns, so widening the
      // bound for a slow host does not weaken the check — pool.close() also tears down the pool's
      // other sessions, which is real disk work.
      poolCloser.awaitDone(UNBLOCKED_COMPLETION_SECONDS,
          "pool.close() must return promptly — the skip must neither park on the live commit nor"
              + " tear it down");
      var pooled = pooledRef.get();
      assertNotNull(pooled);
      // Lock-free status probe: isClosed() would take the storage state lock and block behind
      // the parked commit's held write lock. The skip must have left the session OPEN.
      assertEquals("the skip must not close the mid-commit session",
          DatabaseSessionEmbedded.STATUS.OPEN, pooled.getStatus());
      assertTrue("the skip must not release the live commit's permit",
          mutex.isEngagedBy(pooled));
      // A worker that DIED also reads "finished" here, so the non-authoritative error peek is what
      // separates the regression this catches (the commit completed early, i.e. the skip did not
      // defer) from an owner that simply failed - the latter is reported with its cause by the
      // awaitDone/failIfErrored pair once the window is released.
      assertNull("the owner must not have failed inside the commit window", owner.errorSoFar());
      assertFalse("the commit must still be parked in the window", owner.isFinished());
    } finally {
      releaseWindow.countDown();
      storage.setCommitWindowTestHook(null);
    }

    // (d) Completion edges before the authoritative reads.
    owner.awaitDone(UNBLOCKED_COMPLETION_SECONDS, "the owner's commit must finish");
    owner.failIfErrored("the deferred teardown must not disturb the commit outcome");
    poolCloser.failIfErrored("the pool close must not throw");
    var pooled = pooledRef.get();
    // The owner's completer ran the full teardown on the owning thread. (The window is released
    // now, so the lock-taking isClosed() probe is safe again.)
    assertTrue("the owner's completer must have closed the session", pooled.isClosed());
    assertFalse("the permit must be free after the owner's teardown",
        mutex.isEngagedBy(pooled));
    // The commit is durable and the storage fully usable afterwards (sole session-count
    // decrement, no premature auto-close).
    assertTrue("the deferred-teardown commit must be durable",
        session.getMetadata().getSchema().existsClass("PoolSkipClass"));
    session.executeInTx(
        tx -> session.getMetadata().getSchema().createClass("AfterPoolSkip", 1));
    assertTrue(session.getMetadata().getSchema().existsClass("AfterPoolSkip"));
  }

  /**
   * Q-A2 skip protocol, pool-falls-through interleaving: when the pool close's re-validation
   * finds no in-flight commit (here: an idle open schema transaction), it runs the normal full
   * teardown itself — rollback, session closed, permit harvested — and the next writer proceeds.
   */
  @Test
  public void poolCloseFallsThroughToFullTeardownWhenNotCommitting() throws Exception {
    var mutex = session.getSharedContext().getMetadataWriteMutex();
    var engaged = new CountDownLatch(1);
    var ownerMayFinish = new CountDownLatch(1);
    var pooledRef = new AtomicReference<DatabaseSessionEmbedded>();
    var owner = startWorker("pool-fallthrough-owner", self -> {
      // (e) exemption, as in poolCloseDuringCommitDefersTeardownToOwner: the borrowed session is
      // the pool's to close, and the pool.close() below is the teardown this test is about.
      var pooled = pool.acquire();
      pooledRef.set(pooled);
      pooled.begin();
      pooled.getMetadata().getSchema().createClass("PoolFallThroughClass", 1);
      engaged.countDown();
      ownerMayFinish.await();
    }, engaged);

    try {
      owner.awaitSignal(engaged, HANDSHAKE_SECONDS, "the owner must engage the mutex");
      var pooled = pooledRef.get();
      assertNotNull(pooled);
      assertTrue(mutex.isEngagedBy(pooled));

      // The tx is idle-open (BEGUN, not COMMITTING): the pool's re-validation falls through to the
      // full teardown on the pool thread — the one legitimate foreign releaser.
      pool.close();
      assertTrue("the pool's full teardown must close the idle session", pooled.isClosed());
      assertFalse("the pool's full teardown must harvest the permit", mutex.isEngagedBy(pooled));
    } finally {
      // Unconditional: the owner parks on an unbounded ownerMayFinish.await(), so any failure above
      // must still release it or the @After join reports a leaked worker instead of the real cause.
      ownerMayFinish.countDown();
    }

    // (d) Completion edge before the authoritative read.
    owner.awaitDone(UNBLOCKED_COMPLETION_SECONDS, "the pool-fallthrough owner must finish");
    owner.failIfErrored("the pool-fallthrough owner must not error");

    session.activateOnCurrentThread();
    session.executeInTx(
        tx -> session.getMetadata().getSchema().createClass("AfterPoolFallThrough", 1));
    assertTrue(session.getMetadata().getSchema().existsClass("AfterPoolFallThrough"));
  }

  /**
   * A deferred-teardown failure never masks the commit outcome. The session is marked for
   * teardown (as the pool skip does mid-commit) and carries a close listener that throws an
   * {@link AssertionError} — an error the teardown's listener loop does not swallow. The commit
   * must still return success and the class must be durably committed: the owner's completer is
   * throw-isolated, so the teardown throwable is logged, never propagated over a durable commit
   * (which would drive a client to retry a durably applied commit).
   */
  @Test
  public void throwingCloseListenerNeverMasksCommitOutcome() {
    var victim = openDatabase();
    victim.activateOnCurrentThread();
    var listener = new SessionListener() {
      @Override
      public void onClose(final DatabaseSessionEmbedded database) {
        throw new AssertionError("forced close-listener failure");
      }
    };
    victim.registerListener(listener);
    try {
      victim.begin();
      victim.getMetadata().getSchema().createClass("MaskedOutcomeClass", 1);
      // Simulate the pool skip having marked the session mid-commit.
      victim.markTeardownIntent();
      // Must return normally: the completer's teardown failure is logged, not thrown.
      victim.commit();
    } finally {
      victim.unregisterListener(listener);
      victim.clearTeardownIntent();
      if (!victim.isClosed()) {
        // The completer's teardown removed the thread-local activation; re-activate before the
        // cleanup close.
        victim.activateOnCurrentThread();
        victim.close();
      }
      session.activateOnCurrentThread();
    }
    assertTrue("the commit outcome must stand despite the teardown failure",
        session.getMetadata().getSchema().existsClass("MaskedOutcomeClass"));
  }

  /**
   * An interrupted engage waiter throws {@link DatabaseException} naming the holder and restores
   * the interrupt flag — the waiter is killable, unlike the old uninterruptible park. The wait
   * itself stays unbounded (no spurious DDL failure by contention alone); interruption is the
   * only early exit besides the waiter's own teardown.
   */
  @Test
  public void interruptedEngageWaiterThrowsAndRestoresInterruptFlag() throws InterruptedException {
    var mutex = session.getSharedContext().getMetadataWriteMutex();
    var holderOrdinal = mutex.engage(session);
    var thrown = new AtomicReference<Throwable>();
    var flagRestored = new AtomicBoolean(false);
    var waiterStarted = new CountDownLatch(1);
    try {
      var waiter = startWorker("interrupted-engage-waiter", self -> {
        // The session is worker-owned: the runner closes it after this body returns, which is why
        // the interrupt flag is cleared here first (a set flag would disturb that close).
        var other = self.openSession();
        try {
          other.activateOnCurrentThread();
          waiterStarted.countDown();
          try {
            mutex.engage(other);
          } catch (Throwable t) {
            thrown.set(t);
            flagRestored.set(Thread.currentThread().isInterrupted());
          }
        } finally {
          Thread.interrupted();
        }
      }, waiterStarted);

      waiter.awaitSignal(waiterStarted, HANDSHAKE_SECONDS, "the waiter must start");
      // Deliberately short: the waiter's next call after the start latch is the engage itself.
      var observedPark = awaitParkedOnMutex(waiter.thread(), PARK_OBSERVATION_MILLIS);
      assertTrue("the waiter must park on the held permit, observed " + observedPark,
          observedPark.parkedOnMutex());
      waiter.thread().interrupt();
      // LIVENESS: an interruptible waiter exits at once; only a regression back to an
      // uninterruptible park exhausts this bound.
      waiter.joinBounded(WORKER_JOIN_MILLIS);
      assertFalse("the interrupted waiter must exit", waiter.isAlive());
      // (d) The join is the completion edge for this read.
      waiter.failIfErrored("the waiter's session handling must not error");
      assertNotNull("the interrupted waiter must have thrown", thrown.get());
      assertTrue("the throw must be a DatabaseException naming the wait: " + thrown.get(),
          thrown.get() instanceof DatabaseException
              && thrown.get().getMessage().contains("interrupted while waiting"));
      assertTrue("the interrupt flag must be restored before the throw", flagRestored.get());
    } finally {
      mutex.releaseFor(session, holderOrdinal);
    }
  }

  /**
   * Pool-close loop isolation: a session whose teardown throws must not abort the loop and
   * strand the remaining sessions. Two borrowed idle sessions both carry close listeners that
   * throw {@link AssertionError} (which the listener loop does not swallow); the pool close must
   * still complete without throwing — pre-isolation, the first throwing realClose aborted the
   * loop and the whole close.
   */
  @Test
  public void poolCloseLoopSurvivesThrowingSessionTeardown() {
    var first = pool.acquire();
    var second = pool.acquire();
    var listener = new SessionListener() {
      @Override
      public void onClose(final DatabaseSessionEmbedded database) {
        throw new AssertionError("forced teardown failure");
      }
    };
    first.registerListener(listener);
    second.registerListener(listener);
    // Must not throw: each realClose is throw-isolated, so the loop reaches every session.
    pool.close();
    session.activateOnCurrentThread();
    // The storage stays usable afterwards.
    session.executeInTx(
        tx -> session.getMetadata().getSchema().createClass("AfterThrowingPoolClose", 1));
    assertTrue(session.getMetadata().getSchema().existsClass("AfterThrowingPoolClose"));
  }
}
