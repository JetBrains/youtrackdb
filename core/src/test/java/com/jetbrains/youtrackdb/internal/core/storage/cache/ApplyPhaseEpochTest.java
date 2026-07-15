package com.jetbrains.youtrackdb.internal.core.storage.cache;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/**
 * Tests for the {@link ApplyPhaseEpoch} reader/writer protocol (YTDB-1178): writers
 * bracket the commit-time page-apply section with enterApplyPhase()/exitApplyPhase();
 * readers capture both counters in {@link OptimisticReadScope#reset()} and re-check them
 * in {@link OptimisticReadScope#validateOrThrow()}. A reader must fail whenever any apply
 * phase overlaps its read window — including the parity-defeating interleaving where two
 * writers' apply phases overlap each other.
 */
public class ApplyPhaseEpochTest {

  @Test
  public void testCountersStartAtZeroAndIncrementIndependently() {
    // Fresh epoch is quiescent (0/0); enter and exit bump their own counters only.
    var epoch = new ApplyPhaseEpoch();
    assertEquals(0, epoch.enterSeq());
    assertEquals(0, epoch.exitSeq());

    epoch.enterApplyPhase();
    assertEquals(1, epoch.enterSeq());
    assertEquals(0, epoch.exitSeq());

    epoch.exitApplyPhase();
    assertEquals(1, epoch.enterSeq());
    assertEquals(1, epoch.exitSeq());
  }

  @Test
  public void testQuiescentCapturePassesValidation() {
    // A read whose window overlaps no apply phase must pass the epoch check — both on a
    // fresh epoch and after previous apply phases have fully completed.
    var epoch = new ApplyPhaseEpoch();
    var scope = new OptimisticReadScope(epoch);

    scope.reset();
    scope.validateOrThrow(); // fresh epoch: passes

    // A completed apply BEFORE the capture must not affect the next read.
    epoch.enterApplyPhase();
    epoch.exitApplyPhase();
    scope.reset();
    scope.validateOrThrow(); // quiescent again: passes
  }

  @Test
  public void testApplyInFlightAtCaptureFailsValidation() {
    // A writer already inside the apply bracket when the reader captures (enter != exit
    // at capture time) must fail validation — the reader could see partially applied
    // pages with all stamps still valid.
    var epoch = new ApplyPhaseEpoch();
    var scope = new OptimisticReadScope(epoch);

    epoch.enterApplyPhase();
    scope.reset(); // capture sees enter=1, exit=0
    expectValidateFails(scope);
    epoch.exitApplyPhase();
  }

  @Test
  public void testApplyEnteredAfterCaptureFailsValidation() {
    // Epoch quiescent at capture, but a writer enters the apply phase before the reader
    // validates: the live enterSeq no longer matches the captured value → fail.
    var epoch = new ApplyPhaseEpoch();
    var scope = new OptimisticReadScope(epoch);

    scope.reset(); // capture sees enter=0, exit=0
    epoch.enterApplyPhase();
    expectValidateFails(scope);
    epoch.exitApplyPhase();
  }

  @Test
  public void testApplyCompletedWithinReadWindowFailsValidation() {
    // A full apply phase (enter AND exit) between capture and validation must still
    // fail: the reader may have read some pages before and some after the apply.
    var epoch = new ApplyPhaseEpoch();
    var scope = new OptimisticReadScope(epoch);

    scope.reset();
    epoch.enterApplyPhase();
    epoch.exitApplyPhase();
    expectValidateFails(scope); // enterSeq moved from 0 to 1 since capture
  }

  @Test
  public void testResetNeverThrowsAndRecapturesFreshValues() {
    // reset() is called outside the try/fallback block of
    // StorageComponent.executeOptimisticStorageRead, so it must never throw — even while
    // an apply phase is in flight. After the apply completes, a fresh reset() must
    // re-capture and let validation pass again.
    var epoch = new ApplyPhaseEpoch();
    var scope = new OptimisticReadScope(epoch);

    epoch.enterApplyPhase();
    scope.reset(); // in-flight apply: must not throw, only latch the doomed capture
    expectValidateFails(scope);

    epoch.exitApplyPhase();
    scope.reset(); // re-captures enter=1, exit=1 → quiescent
    scope.validateOrThrow();
  }

  @Test
  public void testConcurrentOverlappingAppliesNeverLetOverlappingReaderPass()
      throws Exception {
    // Two writer threads with overlapping apply phases: A enters, B enters, A exits,
    // B exits. This is the interleaving that defeats a single odd/even parity bit —
    // after A's exit a parity scheme would report "idle" while B is still mid-apply.
    // The two-counter scheme must keep failing an overlapping reader until BOTH writers
    // have exited. The interleaving is made deterministic with latches.
    var epoch = new ApplyPhaseEpoch();
    var scope = new OptimisticReadScope(epoch);

    var aEntered = new CountDownLatch(1);
    var bEntered = new CountDownLatch(1);
    var aMayExit = new CountDownLatch(1);
    var bMayExit = new CountDownLatch(1);
    var aExited = new CountDownLatch(1);
    var error = new AtomicReference<Throwable>();

    var writerA = new Thread(() -> {
      try {
        epoch.enterApplyPhase();
        aEntered.countDown();
        await(aMayExit);
        epoch.exitApplyPhase();
        aExited.countDown();
      } catch (Throwable t) {
        error.set(t);
      }
    });
    var writerB = new Thread(() -> {
      try {
        await(aEntered); // enforce: A enters first
        epoch.enterApplyPhase();
        bEntered.countDown();
        await(bMayExit);
        epoch.exitApplyPhase();
      } catch (Throwable t) {
        error.set(t);
      }
    });
    writerA.start();
    writerB.start();

    try {
      await(bEntered);

      // Both applies in flight (enter=2, exit=0): reader must fail.
      scope.reset();
      expectValidateFails(scope);

      // A exits while B is still applying (enter=2, exit=1) — the parity trap.
      aMayExit.countDown();
      await(aExited);
      scope.reset();
      expectValidateFails(scope);
    } finally {
      // Always release the writers so a failing assertion cannot hang the test.
      aMayExit.countDown();
      bMayExit.countDown();
    }

    writerA.join(TimeUnit.SECONDS.toMillis(10));
    writerB.join(TimeUnit.SECONDS.toMillis(10));
    assertNull("Writer thread failed: " + error.get(), error.get());

    // Both writers exited (enter=2, exit=2): a fresh capture passes.
    scope.reset();
    scope.validateOrThrow();
  }

  private static void await(CountDownLatch latch) {
    try {
      assertTrue("Timed out waiting on latch", latch.await(10, TimeUnit.SECONDS));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(e);
    }
  }

  private static void expectValidateFails(OptimisticReadScope scope) {
    try {
      scope.validateOrThrow();
      fail("Expected OptimisticReadFailedException — an apply phase overlapped the read");
    } catch (OptimisticReadFailedException expected) {
      // expected — epoch check detected the overlapping apply phase
    }
  }
}
