package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.operationsfreezer;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/**
 * Liveness of the freezer's waiting-list protocol under concurrent OPERATOR freeze churn.
 *
 * <p>{@code WaitingList.cutWaitingList} is only sound with a SINGLE concurrent cutter: it captures
 * {@code tail} BEFORE {@code head}, so a second cutter that completes a full cut (plus at least
 * one enqueue) between those two reads leaves the first cutter holding a cross-generation pair —
 * a head that is AT OR PAST its captured tail. The head CAS then succeeds (the head value is
 * current even though the tail value is stale), the list head is swung BACKWARDS onto an
 * already-detached node, and the cutter's traversal walks forward from the new head chasing a
 * tail that lies behind it: it blocks forever in {@code waitTillAllLinksWillBeCreated} on the
 * link latch of a node that will never receive a successor (or on a detached tail-copy node,
 * whose latch is never counted down at all).
 *
 * <p>Historically the sole cutter was {@code releaseOperations} on the {@code freezeRequests}
 * 1&rarr;0 transition, so the protocol held. The Track 7 freezer gate added an OPERATOR-arm
 * cut-and-unpark on every operator freeze registration — a second concurrent cutter that broke
 * the single-cutter invariant and hung the integration suite
 * ({@code FreezeAndDBRecordInsertAtomicityTest}, whose worker threads freeze/release
 * concurrently). The fix serializes cutters: {@code cutWaitingList} is {@code synchronized}, so
 * each cut detaches one consistent finite generation while enqueues stay lock-free. (The
 * alternative — keeping the release as the sole cutter and having the operator arm WALK the
 * live list without detaching — was tried first and fails this very test by LIVELOCK: woken
 * data entrants re-enqueue fresh nodes while operator freezes remain active, so a non-detaching
 * walk chases a list that grows faster than it is traversed.)
 *
 * <p>This test is the bounded unit-level reproducer: several threads churn operator
 * freeze/release cycles (each registration fires the arm-side cut; each 1&rarr;0 release fires
 * the release cut) while data threads keep continuous {@code startOperation}/{@code endOperation}
 * traffic flowing (their herd re-parks keep the waiting list non-empty and the enqueue traffic
 * up). With unserialized cutters the cross-generation capture wedges a freezer thread inside
 * {@code cutWaitingList} within a few seconds; the test then fails on the bounded join instead of
 * hanging the fork. With serialized cutters the churn drains cleanly every time.
 */
public class OperationsFreezerLivenessTest {

  /** Operator freeze/release churn threads — each registration exercises the arm-side wake. */
  private static final int CHURN_THREADS =
      Math.max(3, Runtime.getRuntime().availableProcessors() / 2);

  /** Continuous data-operation threads — the herd that keeps the waiting list populated. */
  private static final int DATA_THREADS =
      Math.max(4, Runtime.getRuntime().availableProcessors() / 2);

  /** How long the churn runs before the bounded shutdown is asserted. */
  private static final long CHURN_MILLIS = 5_000;

  /** Bounded join per thread: generous for a healthy shutdown, finite on a wedged freezer. */
  private static final long JOIN_MILLIS = 15_000;

  @Test(timeout = 120_000)
  public void operatorFreezeChurnNeverWedgesTheWaitingList() throws Exception {
    final var freezer = new OperationsFreezer();
    final var running = new AtomicReference<>(Boolean.TRUE);
    final var firstError = new AtomicReference<Throwable>();

    final List<Thread> churnThreads = new ArrayList<>();
    for (var i = 0; i < CHURN_THREADS; i++) {
      final var thread = new Thread(() -> {
        try {
          while (running.get()) {
            final var id = freezer.freezeOperations(FreezeKind.OPERATOR, null);
            freezer.releaseOperations(id);
          }
        } catch (Throwable t) {
          firstError.compareAndSet(null, t);
        }
      }, "freezer-liveness-churn-" + i);
      thread.setDaemon(true);
      churnThreads.add(thread);
    }

    final List<Thread> dataThreads = new ArrayList<>();
    for (var i = 0; i < DATA_THREADS; i++) {
      final var thread = new Thread(() -> {
        try {
          while (running.get()) {
            freezer.startOperation();
            freezer.endOperation();
          }
        } catch (Throwable t) {
          firstError.compareAndSet(null, t);
        }
      }, "freezer-liveness-data-" + i);
      thread.setDaemon(true);
      dataThreads.add(thread);
    }

    dataThreads.forEach(Thread::start);
    churnThreads.forEach(Thread::start);

    Thread.sleep(CHURN_MILLIS);
    running.set(Boolean.FALSE);

    try {
      // The churn threads pair every freeze with a release, so after the last of them exits the
      // freeze-request count is 0 and the final release's cut has unparked every waiter; the
      // data threads then complete their in-flight startOperation and observe the stop flag. A
      // thread that fails this bounded join is wedged inside the freezer — the liveness defect.
      for (final var thread : churnThreads) {
        thread.join(JOIN_MILLIS);
        if (thread.isAlive()) {
          failWedged(thread, churnThreads, dataThreads);
        }
      }
      for (final var thread : dataThreads) {
        thread.join(JOIN_MILLIS);
        if (thread.isAlive()) {
          failWedged(thread, churnThreads, dataThreads);
        }
      }
    } finally {
      // Failure hygiene: unblock any churn thread stuck in the waiting-list latch wait (the wait
      // is interruptible and rethrows) so the daemon does not keep a freeze registered while the
      // rest of the fork runs. Parked data daemons are left alone — parked threads cost nothing,
      // while an interrupt would turn their park into a hot spin.
      for (final var thread : churnThreads) {
        if (thread.isAlive()) {
          thread.interrupt();
        }
      }
    }

    if (firstError.get() != null) {
      throw new AssertionError("freezer churn worker failed", firstError.get());
    }
    assertFalse("no operator freeze may remain registered after the paired churn drained",
        freezer.isOperatorFreezeActive());
  }

  /** Fails with the wedged thread's stack so the hang site is visible in the test report. */
  private static void failWedged(Thread wedged, List<Thread> churnThreads,
      List<Thread> dataThreads) {
    final var diagnostic = new StringBuilder("freezer liveness failure: thread '")
        .append(wedged.getName())
        .append("' did not terminate within ")
        .append(JOIN_MILLIS)
        .append(" ms; it is wedged at:\n");
    for (final var element : wedged.getStackTrace()) {
      diagnostic.append("    at ").append(element).append('\n');
    }
    diagnostic.append("thread states: ");
    for (final var thread : churnThreads) {
      diagnostic.append(thread.getName()).append('=').append(thread.getState()).append(' ');
    }
    for (final var thread : dataThreads) {
      diagnostic.append(thread.getName()).append('=').append(thread.getState()).append(' ');
    }
    fail(diagnostic.toString());
  }
}
