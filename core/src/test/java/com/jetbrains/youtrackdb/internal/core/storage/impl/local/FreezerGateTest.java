package com.jetbrains.youtrackdb.internal.core.storage.impl.local;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.api.exception.ConcurrentModificationException;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.exception.CommandInterruptedException;
import com.jetbrains.youtrackdb.internal.core.exception.ModificationOperationProhibitedException;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.operationsfreezer.FreezeKind;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Test;

/**
 * The freezer gate: a schema-carrying commit never blocks or parks while an OPERATOR freeze (the
 * admin filesystem-snapshot freeze/release pair) is active — it aborts loudly with the stable
 * gate message instead, at whichever of the four checkpoints first observes the freeze — while
 * data commits keep the historical park semantics byte-for-byte and TRANSIENT self-quiesces
 * (synch, backup) stay brief parks for everyone. The tests also pin the single-owner
 * snapshot-pin/clear pairing of the commit path (every commit outcome returns the pin count to
 * its pre-commit value, verified on pooled sessions that are then recycled and re-borrowed) and
 * the CAS-floor underflow guard of the freeze counters.
 *
 * <p>Deliberately NOT pinned: the absence of gate false-positives around the freeze-release
 * instant. The kind-counter guarantee is one-sided by design (publish kind-before-count on arm,
 * retract count-before-kind on release), so a schema commit racing the release can throw although
 * the operator freeze just ended — rare, loud, retryable, accepted.
 */
public class FreezerGateTest extends DbTestBase {

  /** The stable gate-message fragment (the Q-B5 contract, distinct from the legacy supplier). */
  private static final String GATE_MESSAGE_FRAGMENT =
      "Schema commit aborted: operator freeze in progress on storage";

  private final List<Thread> spawnedWorkers = new CopyOnWriteArrayList<>();

  private Thread spawn(Runnable body, String name) {
    var t = new Thread(body, name);
    t.setDaemon(true);
    spawnedWorkers.add(t);
    t.start();
    return t;
  }

  @After
  public void joinSpawnedWorkers() throws InterruptedException {
    for (var t : spawnedWorkers) {
      t.join(10_000);
      if (t.isAlive()) {
        t.interrupt();
        fail("worker did not join within 10s: " + t.getName());
      }
    }
    spawnedWorkers.clear();
  }

  private static Thread.State awaitThreadParked(Thread worker, long timeoutMillis) {
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
    Thread.State state = worker.getState();
    while (System.nanoTime() < deadline) {
      state = worker.getState();
      if (state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING) {
        return state;
      }
      Thread.onSpinWait();
    }
    return state;
  }

  private AbstractStorage storage() {
    return (AbstractStorage) session.getStorage();
  }

  private static int pinCount(DatabaseSessionEmbedded target) {
    return target.getMetadata().getThreadLocalSchemaSnapshotPinCount();
  }

  /**
   * Recycles {@code borrowed} into the pool, re-borrows, and runs a schema DDL commit — the
   * pinned recycled-borrower health probe: a leaked snapshot pin would freeze the borrower's
   * schema view and trip the force-clear guard; a double clear would poison the next pin.
   */
  private void assertRecycledBorrowRunsDdl(DatabaseSessionEmbedded borrowed, String className) {
    borrowed.activateOnCurrentThread();
    assertEquals("the session must carry no snapshot pin at recycle time", 0, pinCount(borrowed));
    borrowed.close();
    var reborrowed = pool.acquire();
    try {
      reborrowed.executeInTx(
          tx -> reborrowed.getMetadata().getSchema().createClass(className));
      assertTrue(reborrowed.getMetadata().getSchema().existsClass(className));
    } finally {
      reborrowed.close();
    }
    session.activateOnCurrentThread();
  }

  /**
   * Checkpoint (1), the entry probe: a schema commit against a PRE-ENGAGED park-mode operator
   * freeze throws the stable gate exception before taking the snapshot pin or any lock — the pin
   * count is balanced across the rejection, and the recycled pooled borrower stays healthy.
   * Path (a) of the five-path pin-balance matrix.
   */
  @Test(timeout = 60_000)
  public void probeThrowsOnPreEngagedOperatorFreeze() {
    // The timeout converts a total gate regression (this main-thread commit parking in the
    // freezer with the release sitting in the finally below) from a fork-wide hang into a named
    // failure. JUnit runs a timed test body on a surrogate thread, so the @Before-thread session
    // must be re-activated here.
    session.activateOnCurrentThread();
    var pooled = pool.acquire();
    pooled.begin();
    pooled.getMetadata().getSchema().createClass("ProbeFreeze");

    session.activateOnCurrentThread();
    session.freeze(false); // park-mode operator freeze
    try {
      pooled.activateOnCurrentThread();
      var pinsBefore = pinCount(pooled);
      var thrown = assertThrows(ModificationOperationProhibitedException.class, pooled::commit);
      assertTrue("the gate message is a stable contract: " + thrown.getMessage(),
          thrown.getMessage().contains(GATE_MESSAGE_FRAGMENT));
      assertEquals("a probe rejection must leave the snapshot pin balanced",
          pinsBefore, pinCount(pooled));
      // Placement pin: the entry probe rejects at commit() entry, BEFORE the snapshot pin, the
      // metadata locks, and the freezer. The zero pin-delta assert above cannot pin placement by
      // itself (a deeper checkpoint's throw also balances the pin through the nested finally),
      // so the throw site is attributed by its stack: no commitSchemaCarry and no freezer frame.
      assertTrue("the rejection must come from the entry probe, not a deeper checkpoint",
          Arrays.stream(thrown.getStackTrace()).noneMatch(
              frame -> frame.getMethodName().equals("commitSchemaCarry")
                  || frame.getClassName().endsWith("OperationsFreezer")));
    } finally {
      session.activateOnCurrentThread();
      session.release();
    }
    assertRecycledBorrowRunsDdl(pooled, "AfterProbeReject");
  }

  /**
   * Checkpoint (2), the write-lock abort: a freeze that engages in the probe-to-entry window —
   * after the commit passed the entry probe but before it acquired the storage write lock — is
   * caught by the abort-predicate acquisition, which throws the same stable gate exception with
   * the two metadata locks unwinding through their finallys. Driven deterministically by holding
   * the committed schema's write lock (blocking the commit AFTER its probe), engaging the freeze,
   * then letting the commit proceed into the gated acquisition. Path (b) of the pin-balance
   * matrix.
   */
  @Test
  public void writeLockAbortThrowsWhenFreezeEngagesAfterProbe() throws Exception {
    var schemaShared = session.getSharedContext().getSchema();
    var commitError = new AtomicReference<Throwable>();
    var pooledRef = new AtomicReference<DatabaseSessionEmbedded>();
    var pinsAfter = new AtomicReference<Integer>();
    var ready = new CountDownLatch(1);
    var goCommit = new CountDownLatch(1);
    var done = new CountDownLatch(1);

    var worker = spawn(() -> {
      try {
        var pooled = pool.acquire();
        pooledRef.set(pooled);
        pooled.begin();
        pooled.getMetadata().getSchema().createClass("Cp2Freeze");
        ready.countDown();
        try {
          goCommit.await();
          pooled.commit();
        } catch (Throwable t) {
          commitError.set(t);
        }
        pinsAfter.set(pinCount(pooled));
      } finally {
        done.countDown();
      }
    }, "cp2-committer");

    // The worker's tx-local seed also needs the committed schema's write lock, so the lock is
    // taken only AFTER the worker finished its schema write and before its commit starts:
    // blocking the commit at commitSchemaCarry's first lock acquisition, strictly AFTER the
    // entry probe ran (no freeze existed then).
    assertTrue(ready.await(10, TimeUnit.SECONDS));
    schemaShared.acquireSchemaWriteLock(session);
    var lockHeld = true;
    try {
      goCommit.countDown();
      // The worker passes the probe (no freeze yet) and blocks on the held schema write lock.
      var state = awaitThreadParked(worker, 5_000);
      assertTrue("the committer must block on the held schema lock, observed " + state,
          state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING);

      // The freeze engages in the probe-to-entry window (stateLock is free: the committer holds
      // no storage lock yet).
      session.freeze(false);
      try {
        // Release the schema lock: the committer proceeds to the gated write-lock acquisition,
        // whose abort predicate sees the operator freeze and throws.
        schemaShared.releaseSchemaWriteLock(session, false);
        lockHeld = false;
        assertTrue(done.await(15, TimeUnit.SECONDS));
        assertNotNull("the commit must have failed at the write-lock checkpoint",
            commitError.get());
        assertTrue("the failure must be the stable gate exception: " + commitError.get(),
            commitError.get() instanceof ModificationOperationProhibitedException
                && commitError.get().getMessage().contains(GATE_MESSAGE_FRAGMENT));
        assertEquals("the write-lock abort must leave the snapshot pin balanced",
            Integer.valueOf(0), pinsAfter.get());
      } finally {
        session.activateOnCurrentThread();
        session.release();
      }
    } finally {
      if (lockHeld) {
        schemaShared.releaseSchemaWriteLock(session, false);
      }
    }
    assertRecycledBorrowRunsDdl(pooledRef.get(), "AfterCp2Abort");
  }

  /**
   * Checkpoint (2)'s abort MECHANISM, not just its outcome: an armed schema commit whose storage
   * write-lock acquisition is blocked behind a freezer-parked data commit — the parked commit
   * holds {@code stateLock.readLock} while it parks behind a TRANSIENT quiesce, so a plain
   * {@code lock()} at checkpoint (2) would sit in the writer queue for the freeze's whole
   * duration and, by writer preference, stall every new reader behind it: the exact read-outage
   * defect the abort-predicate acquisition exists to prevent. Once the operator freeze engages,
   * the commit must abort out of the acquisition (checkpoint-(2) stack attribution, balanced
   * pin) and reads must flow while the freeze is still engaged. Red-first proven by reverting
   * checkpoint (2) to a plain acquisition: the commit hangs behind the parked reader and the
   * bounded await fails.
   */
  @Test
  public void writeLockAbortFiresWhileQueuedBehindFreezerParkedReader() throws Exception {
    session.getMetadata().getSchema().createClass("Cp2QueueData");
    var schemaShared = session.getSharedContext().getSchema();
    var manager = storage().getAtomicOperationsManager();
    var dataError = new AtomicReference<Throwable>();
    var commitError = new AtomicReference<Throwable>();
    var pinsAfter = new AtomicReference<Integer>();
    var dataCommitted = new CountDownLatch(1);
    var ready = new CountDownLatch(1);
    var goCommit = new CountDownLatch(1);
    var done = new CountDownLatch(1);

    var transientId = manager.freezeWriteOperations(FreezeKind.TRANSIENT_QUIESCE, null);
    long operatorId = -2;
    try {
      // Ingredient 1: a data commit parked in the freezer behind the transient quiesce. The
      // pure-data commit branch takes stateLock.readLock around its apply and the freezer entry
      // sits inside it, so the parked thread HOLDS the read lock — the blocker a plain
      // write-lock acquisition cannot get past until the freezes release.
      var dataWriter = spawn(() -> {
        try (var writerSession = openDatabase()) {
          writerSession.activateOnCurrentThread();
          writerSession.executeInTx(tx -> {
            var row = (EntityImpl) writerSession.newEntity("Cp2QueueData");
            row.setProperty("v", 1);
          });
          dataCommitted.countDown();
        } catch (Throwable t) {
          dataError.compareAndSet(null, t);
        }
      }, "cp2-parked-reader");
      var state = awaitThreadParked(dataWriter, 10_000);
      assertTrue("the data commit must park in the freezer holding the read lock, observed "
          + state, state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING);

      // Ingredient 2: the armed schema commit, blocked right after its entry probe by the held
      // committed-schema write lock (the same probe-to-entry-window drive as the test above).
      var worker = spawn(() -> {
        var pooled = pool.acquire();
        try {
          pooled.begin();
          pooled.getMetadata().getSchema().createClass("Cp2Queue");
          ready.countDown();
          try {
            goCommit.await();
            pooled.commit();
          } catch (Throwable t) {
            commitError.set(t);
          }
          pinsAfter.set(pinCount(pooled));
        } finally {
          pooled.close();
          done.countDown();
        }
      }, "cp2-queued-committer");

      assertTrue(ready.await(10, TimeUnit.SECONDS));
      schemaShared.acquireSchemaWriteLock(session);
      var lockHeld = true;
      try {
        goCommit.countDown();
        state = awaitThreadParked(worker, 5_000);
        assertTrue("the committer must block on the held schema lock, observed " + state,
            state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING);

        // Release the schema lock: the committer proceeds into checkpoint (2), where the parked
        // reader blocks the drain. The brief sleep biases the interleaving toward the
        // queued-in-acquisition trajectory (write bit held, drain spinning on the parked
        // reader); if the freeze below engages before the committer reaches the acquisition, it
        // aborts at the acquisition's entry check instead — the same checkpoint and the same
        // assertions, so the bias cannot flake the test.
        schemaShared.releaseSchemaWriteLock(session, false);
        lockHeld = false;
        Thread.sleep(500);

        // The operator freeze engages at manager level (the public freeze() API needs the read
        // lock the queued writer blocks); the abort predicate turns true and the committer must
        // abort out of the lock acquisition within one poll.
        operatorId = manager.freezeWriteOperations(FreezeKind.OPERATOR, null);
        assertTrue("the queued schema commit must abort instead of waiting out the freeze",
            done.await(15, TimeUnit.SECONDS));
        assertNotNull("the commit must have failed at the write-lock checkpoint",
            commitError.get());
        assertTrue("the failure must be the stable gate exception: " + commitError.get(),
            commitError.get() instanceof ModificationOperationProhibitedException
                && commitError.get().getMessage().contains(GATE_MESSAGE_FRAGMENT));
        // Checkpoint attribution: the abort must come from commitSchemaCarry's gated
        // acquisition — not from the entry probe (which ran before the freeze existed) and not
        // from the in-freezer checkpoints (which a plain-lock regression could reach only after
        // the freezes released).
        var frames = commitError.get().getStackTrace();
        assertTrue("expected a commitSchemaCarry frame, got " + Arrays.toString(frames),
            Arrays.stream(frames)
                .anyMatch(frame -> frame.getMethodName().equals("commitSchemaCarry")));
        assertTrue("the abort must not come from the in-freezer checkpoints",
            Arrays.stream(frames)
                .noneMatch(frame -> frame.getClassName().endsWith("OperationsFreezer")));
        assertEquals("the write-lock abort must leave the snapshot pin balanced",
            Integer.valueOf(0), pinsAfter.get());

        // The outage property itself: with the aborted writer gone (the abort releases the
        // write bit), READS flow while the operator freeze is still engaged. Bounded worker so
        // a regression (a queued writer stalling readers) fails the join instead of hanging the
        // fork; reads never enter the freezer, so the active freezes cannot park it.
        var readerDone = new CountDownLatch(1);
        var readerError = new AtomicReference<Throwable>();
        spawn(() -> {
          try {
            session.activateOnCurrentThread();
            session.begin();
            try {
              session.browseClass("Cp2QueueData").hasNext();
            } finally {
              session.rollback();
            }
          } catch (Throwable t) {
            readerError.compareAndSet(null, t);
          } finally {
            readerDone.countDown();
          }
        }, "cp2-read-prober");
        assertTrue("reads must flow while the operator freeze is engaged",
            readerDone.await(10, TimeUnit.SECONDS));
        if (readerError.get() != null) {
          throw new AssertionError("the read probe must succeed under the operator freeze",
              readerError.get());
        }
        session.activateOnCurrentThread();
      } finally {
        if (lockHeld) {
          schemaShared.releaseSchemaWriteLock(session, false);
        }
      }
    } finally {
      if (operatorId != -2) {
        manager.unfreezeWriteOperations(operatorId);
      }
      manager.unfreezeWriteOperations(transientId);
    }
    assertTrue("the parked data commit must complete after the release",
        dataCommitted.await(10, TimeUnit.SECONDS));
    if (dataError.get() != null) {
      throw new AssertionError("the parked data commit must complete cleanly", dataError.get());
    }
  }

  /**
   * Checkpoint (3) plus the operator-arm cut: a schema commit parked behind a TRANSIENT quiesce
   * (all four locks held, parked in the freezer) is woken by an arriving operator freeze's
   * cut-and-unpark and throws the stable gate exception at the loop-top gate instead of staying
   * parked for the operator freeze's whole duration. Driven at the freezer-manager level (the
   * public freeze() API cannot engage mid-window by construction — it needs the state lock the
   * parked commit holds — but the gate stays the authoritative backstop for exactly this layered
   * shape).
   */
  @Test
  public void parkedSchemaCommitWakesAndThrowsWhenOperatorFreezeArrives() throws Exception {
    var manager = storage().getAtomicOperationsManager();
    var commitError = new AtomicReference<Throwable>();
    var pinsAfterThrow = new AtomicReference<Integer>();
    var pooledClosed = new CountDownLatch(1);

    var transientId = manager.freezeWriteOperations(FreezeKind.TRANSIENT_QUIESCE, null);
    long operatorId = -2;
    try {
      var worker = spawn(() -> {
        var pooled = pool.acquire();
        try {
          pooled.begin();
          pooled.getMetadata().getSchema().createClass("LayeredFreeze");
          try {
            pooled.commit(); // parks in the freezer behind the transient quiesce
          } catch (Throwable t) {
            commitError.set(t);
          }
          pinsAfterThrow.set(pinCount(pooled));
        } finally {
          pooled.close();
          pooledClosed.countDown();
        }
      }, "layered-committer");

      var state = awaitThreadParked(worker, 10_000);
      assertTrue("the schema commit must park behind the transient quiesce, observed " + state,
          state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING);
      assertEquals("the commit must still be parked", 1, pooledClosed.getCount());

      // The operator freeze arrives layered over the transient: its cut-and-unpark wakes the
      // parked entrant, which re-evaluates at the loop-top gate and throws.
      operatorId = manager.freezeWriteOperations(FreezeKind.OPERATOR, null);
      assertTrue("the woken schema commit must fail promptly instead of staying parked",
          pooledClosed.await(10, TimeUnit.SECONDS));
      assertNotNull(commitError.get());
      assertTrue("the failure must be the stable gate exception: " + commitError.get(),
          commitError.get() instanceof ModificationOperationProhibitedException
              && commitError.get().getMessage().contains(GATE_MESSAGE_FRAGMENT));
      // Direct pin balance on the in-freezer (loop-top) throw path — previously covered only
      // indirectly by the pooled recycle inside close(), whose failure would have read as a
      // latch timeout rather than a pin leak.
      assertEquals("the in-freezer gate throw must leave the snapshot pin balanced",
          Integer.valueOf(0), pinsAfterThrow.get());
    } finally {
      if (operatorId != -2) {
        manager.unfreezeWriteOperations(operatorId);
      }
      manager.unfreezeWriteOperations(transientId);
    }
    // The gate left the storage fully usable.
    session.activateOnCurrentThread();
    session.executeInTx(
        tx -> session.getMetadata().getSchema().createClass("AfterLayeredAbort"));
    assertTrue(session.getMetadata().getSchema().existsClass("AfterLayeredAbort"));
  }

  /**
   * The Q-B4 herd: an operator freeze arriving over PARKED DATA commits wakes them all
   * (the deliberate bounded thundering herd of the cut), but none is admitted — they re-park
   * through the freezer's re-evaluating loop and complete only when every freeze is released.
   * Data-commit semantics under freezes stay byte-for-byte: park, never throw.
   */
  @Test
  public void operatorFreezeHerdReparksDataCommitsWithoutAdmission() throws Exception {
    session.getMetadata().getSchema().createClass("HerdData");
    var manager = storage().getAtomicOperationsManager();
    var errors = new AtomicReference<Throwable>();
    var committed = new CountDownLatch(2);

    var transientId = manager.freezeWriteOperations(FreezeKind.TRANSIENT_QUIESCE, null);
    long operatorId = -2;
    try {
      var workers = new Thread[2];
      for (var i = 0; i < 2; i++) {
        workers[i] = spawn(() -> {
          try (var writerSession = openDatabase()) {
            writerSession.activateOnCurrentThread();
            writerSession.executeInTx(tx -> {
              var row = (EntityImpl) writerSession.newEntity("HerdData");
              row.setProperty("v", 1);
            });
            committed.countDown();
          } catch (Throwable t) {
            errors.compareAndSet(null, t);
          }
        }, "herd-data-writer");
      }
      for (var w : workers) {
        var state = awaitThreadParked(w, 10_000);
        assertTrue("each data commit must park behind the transient quiesce, observed " + state,
            state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING);
      }

      operatorId = manager.freezeWriteOperations(FreezeKind.OPERATOR, null);
      // The cut woke them; none may be admitted — each re-parks through the loop.
      for (var w : workers) {
        var state = awaitThreadParked(w, 10_000);
        assertTrue("each woken data commit must re-park, observed " + state,
            state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING);
      }
      assertEquals("no data commit may be admitted under the layered freezes", 2,
          committed.getCount());

      manager.unfreezeWriteOperations(operatorId);
      operatorId = -2;
      // Still parked: the transient quiesce remains.
      for (var w : workers) {
        var state = awaitThreadParked(w, 10_000);
        assertTrue("data commits must stay parked under the remaining transient, observed "
            + state,
            state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING);
      }
    } finally {
      if (operatorId != -2) {
        manager.unfreezeWriteOperations(operatorId);
      }
      manager.unfreezeWriteOperations(transientId);
    }

    assertTrue("both data commits must complete after the release",
        committed.await(10, TimeUnit.SECONDS));
    if (errors.get() != null) {
      throw new AssertionError("data commits must never throw under park-mode freezes",
          errors.get());
    }
  }

  /**
   * The CAS-floor underflow guard: double releases (both the id-keyed and the -1 sentinel shape)
   * floor the counters at zero — logged, never thrown, never negative — and a subsequent genuine
   * operator freeze still arms the gate (a silent disarm is the exact outage class the guard
   * exists to prevent).
   */
  @Test(timeout = 60_000)
  public void doubleReleaseFloorsCountersAndGateStillArms() {
    // Timeout: a gate regression parks the main-thread commit below with the releases already
    // consumed — a named failure beats a fork hang. Timed bodies run on a surrogate thread, so
    // the session is re-activated.
    session.activateOnCurrentThread();
    var manager = storage().getAtomicOperationsManager();
    var operatorId = manager.freezeWriteOperations(FreezeKind.OPERATOR, null);
    assertTrue(manager.isOperatorFreezeActive());
    manager.unfreezeWriteOperations(operatorId);
    assertFalse(manager.isOperatorFreezeActive());
    // Double release, id-keyed: floored, no negative counter, no exception.
    manager.unfreezeWriteOperations(operatorId);
    assertFalse(manager.isOperatorFreezeActive());
    // Double release, sentinel shape: floored likewise.
    manager.unfreezeWriteOperations(-1);
    assertFalse(manager.isOperatorFreezeActive());

    // The gate still arms for a genuine freeze after the botched releases...
    var secondId = manager.freezeWriteOperations(FreezeKind.OPERATOR, null);
    try {
      assertTrue("the gate must still arm after floored double releases",
          manager.isOperatorFreezeActive());
      session.begin();
      session.getMetadata().getSchema().createClass("DoubleReleaseGate");
      var thrown = assertThrows(ModificationOperationProhibitedException.class, session::commit);
      assertTrue(thrown.getMessage().contains(GATE_MESSAGE_FRAGMENT));
    } finally {
      manager.unfreezeWriteOperations(secondId);
    }
    assertFalse(manager.isOperatorFreezeActive());
    // ...and disarms cleanly.
    session.executeInTx(
        tx -> session.getMetadata().getSchema().createClass("AfterDoubleRelease"));
    assertTrue(session.getMetadata().getSchema().existsClass("AfterDoubleRelease"));
  }

  /**
   * The operator freeze-id bookkeeping across storage-level {@code freeze()}/{@code release()}
   * cycles: {@code freeze()} retains its registration's real id and {@code release()} releases
   * by it, so the freezer's id&rarr;kind record is removed each cycle and the retained-id set
   * does not grow for the storage's lifetime (both freeze arms: park-mode and throw-mode). A
   * leak here is invisible to the counters — the gate still arms and disarms — hence the direct
   * set-size pin.
   */
  @Test(timeout = 60_000)
  public void repeatedFreezeReleaseCyclesDoNotLeakOperatorFreezeIds() {
    // Timed bodies run on a surrogate thread, so the session is re-activated.
    session.activateOnCurrentThread();
    var manager = storage().getAtomicOperationsManager();
    var baseline = manager.registeredOperatorFreezeIdCount();
    for (var i = 0; i < 16; i++) {
      // Alternate the throw-mode and park-mode arms — both register OPERATOR with a real id.
      session.freeze(i % 2 == 0);
      try {
        assertTrue(manager.isOperatorFreezeActive());
      } finally {
        session.release();
      }
    }
    assertEquals("freeze()/release() cycles must not strand operator freeze ids",
        baseline, manager.registeredOperatorFreezeIdCount());
    assertFalse("no operator freeze may remain registered after the paired cycles",
        manager.isOperatorFreezeActive());
  }

  /**
   * The legacy throw-mode operator freeze is byte-for-byte unchanged for data writes: the
   * registered supplier's message ("Modification requests are prohibited") — NOT the gate's
   * distinct wording — surfaces on a data write under freeze(true).
   */
  @Test(timeout = 60_000)
  public void throwModeFreezeKeepsLegacySupplierForDataWrites() {
    // Timeout: a regression to park-mode semantics parks the main-thread write below with the
    // release in the finally — a named failure beats a fork hang. Timed bodies run on a
    // surrogate thread, so the session is re-activated.
    session.activateOnCurrentThread();
    session.getMetadata().getSchema().createClass("ThrowModeData");
    session.freeze(true);
    try {
      var thrown = assertThrows(ModificationOperationProhibitedException.class,
          () -> session.executeInTx(tx -> {
            var row = (EntityImpl) session.newEntity("ThrowModeData");
            row.setProperty("v", 1);
          }));
      assertTrue("the legacy supplier message must surface: " + thrown.getMessage(),
          thrown.getMessage().contains("Modification requests are prohibited"));
      assertFalse("the gate wording must not leak onto the legacy path",
          thrown.getMessage().contains(GATE_MESSAGE_FRAGMENT));
    } finally {
      session.release();
    }
    session.executeInTx(tx -> {
      var row = (EntityImpl) session.newEntity("ThrowModeData");
      row.setProperty("v", 2);
    });
  }

  /**
   * The pinned BG8 contract (user-ruled 2026-07-23, Option A — deterministic throw): a data
   * commit already PARKED under an earlier park-mode freeze, when a THROW-MODE operator freeze
   * engages over it, is woken by the operator-arm cut, re-evaluates, and THROWS the registered
   * supplier's exception deterministically — it does not park through to completion after the
   * release, as the pre-gate code happened to do. Throw-mode means the operator explicitly
   * requested loud failure for writes, and LockSupport's spurious-wakeup spec never guaranteed
   * park-through anyway. The asserted exception is the SUPPLIER's (legacy wording), not the
   * schema-gate factory's — the two must not blur.
   */
  @Test(timeout = 60_000)
  public void cutWokenParkedDataCommitThrowsSupplierUnderThrowModeOperatorFreeze()
      throws Exception {
    // Timed bodies run on a surrogate thread, so the session is re-activated.
    session.activateOnCurrentThread();
    session.getMetadata().getSchema().createClass("ThrowModeWake");
    var manager = storage().getAtomicOperationsManager();
    var writerOutcome = new AtomicReference<Throwable>();
    var writerDone = new CountDownLatch(1);

    var transientId = manager.freezeWriteOperations(FreezeKind.TRANSIENT_QUIESCE, null);
    long operatorId = -2;
    try {
      var writer = spawn(() -> {
        try (var writerSession = openDatabase()) {
          writerSession.activateOnCurrentThread();
          try {
            writerSession.executeInTx(tx -> {
              var row = (EntityImpl) writerSession.newEntity("ThrowModeWake");
              row.setProperty("v", 1);
            });
          } catch (Throwable t) {
            writerOutcome.set(t);
          }
        } finally {
          writerDone.countDown();
        }
      }, "throw-mode-wake-writer");
      var state = awaitThreadParked(writer, 10_000);
      assertTrue("the data commit must park behind the transient quiesce, observed " + state,
          state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING);
      assertEquals("the commit must still be parked", 1, writerDone.getCount());

      // The THROW-MODE operator freeze engages over the park-mode quiesce (manager level, with
      // the legacy supplier freeze(true) registers): its cut wakes the parked entrant, which
      // must throw the supplier's exception instead of re-parking.
      operatorId = manager.freezeWriteOperations(FreezeKind.OPERATOR,
          () -> new ModificationOperationProhibitedException(session.getDatabaseName(),
              "Modification requests are prohibited"));
      assertTrue("the woken data commit must fail promptly instead of parking through",
          writerDone.await(10, TimeUnit.SECONDS));
      var outcome = writerOutcome.get();
      assertNotNull("the woken data commit must have thrown", outcome);
      assertTrue("the failure must be the supplier's exception: " + outcome,
          outcome instanceof ModificationOperationProhibitedException
              && outcome.getMessage().contains("Modification requests are prohibited"));
      assertFalse("the schema-gate wording must not leak onto the supplier path",
          outcome.getMessage().contains(GATE_MESSAGE_FRAGMENT));
    } finally {
      if (operatorId != -2) {
        manager.unfreezeWriteOperations(operatorId);
      }
      manager.unfreezeWriteOperations(transientId);
    }

    // The throw left the storage fully usable after the releases.
    session.executeInTx(tx -> {
      var row = (EntityImpl) session.newEntity("ThrowModeWake");
      row.setProperty("v", 2);
    });
  }

  /**
   * Data commits under a TRANSIENT quiesce keep the historical semantics byte-for-byte: a brief
   * park, then success — never a throw.
   */
  @Test
  public void dataCommitParksUnderTransientFreezeUnchanged() throws Exception {
    session.getMetadata().getSchema().createClass("TransientData");
    var manager = storage().getAtomicOperationsManager();
    var error = new AtomicReference<Throwable>();
    var committed = new CountDownLatch(1);

    var transientId = manager.freezeWriteOperations(FreezeKind.TRANSIENT_QUIESCE, null);
    try {
      var worker = spawn(() -> {
        try (var writerSession = openDatabase()) {
          writerSession.activateOnCurrentThread();
          writerSession.executeInTx(tx -> {
            var row = (EntityImpl) writerSession.newEntity("TransientData");
            row.setProperty("v", 7);
          });
          committed.countDown();
        } catch (Throwable t) {
          error.compareAndSet(null, t);
        }
      }, "transient-data-writer");
      var state = awaitThreadParked(worker, 10_000);
      assertTrue("the data commit must park under the transient quiesce, observed " + state,
          state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING);
      assertEquals(1, committed.getCount());
    } finally {
      manager.unfreezeWriteOperations(transientId);
    }
    assertTrue("the data commit must complete after the release",
        committed.await(10, TimeUnit.SECONDS));
    if (error.get() != null) {
      throw new AssertionError("a parked data commit must complete, never throw", error.get());
    }
  }

  /**
   * The remaining three paths of the pin-balance matrix on pooled sessions — (c) a failed data
   * commit (version conflict), (d) a failed schema-carry commit (in-window fault), (e) a
   * successful schema commit — each leaves the snapshot pin count at its pre-commit value and the
   * recycled, re-borrowed session fully able to run DDL. (Paths (a) probe rejection and (b)
   * write-lock abort are pinned by the two gate tests above.)
   */
  @Test
  public void pinBalanceAcrossCommitOutcomesOnRecycledPooledSessions() {
    // (e) success.
    var pooledSuccess = pool.acquire();
    assertEquals(0, pinCount(pooledSuccess));
    pooledSuccess.executeInTx(
        tx -> pooledSuccess.getMetadata().getSchema().createClass("PinSuccess"));
    assertEquals("a successful schema commit must leave the pin balanced", 0,
        pinCount(pooledSuccess));
    assertRecycledBorrowRunsDdl(pooledSuccess, "AfterPinSuccess");

    // (d) failed schema-carry commit: in-window fault, retry-family so the storage stays open.
    var pooledSchemaFail = pool.acquire();
    storage().setCommitWindowTestHook(() -> {
      throw new CommandInterruptedException(session.getDatabaseName(), "injected pin-path fault");
    });
    try {
      pooledSchemaFail.begin();
      pooledSchemaFail.getMetadata().getSchema().createClass("PinSchemaFail");
      assertThrows(CommandInterruptedException.class, pooledSchemaFail::commit);
    } finally {
      storage().setCommitWindowTestHook(null);
    }
    assertEquals("a failed schema-carry commit must leave the pin balanced", 0,
        pinCount(pooledSchemaFail));
    assertRecycledBorrowRunsDdl(pooledSchemaFail, "AfterPinSchemaFail");

    // (c) failed data commit: version conflict in the record apply.
    session.activateOnCurrentThread();
    session.executeInTx(tx -> session.getMetadata().getSchema().createClass("PinDataConflict"));
    var rid = session.computeInTx(tx -> {
      var row = (EntityImpl) session.newEntity("PinDataConflict");
      row.setProperty("v", 0);
      return row.getIdentity();
    });
    var pooledDataFail = pool.acquire();
    pooledDataFail.begin();
    EntityImpl stale = pooledDataFail.load(rid);
    stale.setProperty("v", 1);
    // Bump the record's committed version underneath the pooled transaction.
    session.activateOnCurrentThread();
    session.executeInTx(tx -> {
      EntityImpl fresh = session.load(rid);
      fresh.setProperty("v", 2);
    });
    pooledDataFail.activateOnCurrentThread();
    assertThrows(ConcurrentModificationException.class, pooledDataFail::commit);
    assertEquals("a version-conflict data commit must leave the pin balanced", 0,
        pinCount(pooledDataFail));
    assertRecycledBorrowRunsDdl(pooledDataFail, "AfterPinDataConflict");
  }
}
