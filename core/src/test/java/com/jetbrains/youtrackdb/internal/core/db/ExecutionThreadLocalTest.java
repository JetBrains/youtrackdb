package com.jetbrains.youtrackdb.internal.core.db;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.common.thread.SoftThread;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * Pins the static dispatcher methods on {@link ExecutionThreadLocal} —
 * {@link ExecutionThreadLocal#isInterruptCurrentOperation()},
 * {@link ExecutionThreadLocal#setInterruptCurrentOperation(Thread)} (instance),
 * and {@link ExecutionThreadLocal#setInterruptCurrentOperation()} (static
 * no-arg) — across both the {@link SoftThread} branch and the regular-
 * {@link Thread} fallback. Also pins the per-thread {@code initialValue()}
 * shape: a fresh {@code ExecutionThreadData} record with both replication
 * callback fields null.
 *
 * <p>{@code @Category(SequentialTest.class)} is required because
 * {@link ExecutionThreadLocal#INSTANCE} is a {@code static volatile} that the
 * engine-shutdown listener nullifies. Running this test in parallel with a
 * test that triggers engine shutdown would observe a transient null.
 */
@Category(SequentialTest.class)
public class ExecutionThreadLocalTest {

  @Before
  public void assumeInstanceInitialized() {
    // ExecutionThreadLocal.INSTANCE is a static volatile that the engine-shutdown
    // listener nullifies. Surefire's @Category(SequentialTest.class) keeps this class
    // off the parallel runner, but worker reuse still means a previous test class
    // could have triggered shutdown. Assume.assumeNotNull converts that situation
    // into a documented skip rather than a confusing NPE in the static-dispatcher
    // arms below.
    Assume.assumeNotNull(ExecutionThreadLocal.INSTANCE);
  }

  // Minimal SoftThread subclass for testing — used as a typed placeholder to drive
  // the SoftThread branch of the dispatcher methods. Most tests never call .start();
  // the two static-dispatcher tests at the bottom of this class anonymously subclass
  // this to override run() and DO start the thread to drive currentThread()-based
  // dispatch.
  private static class TestSoftThread extends SoftThread {

    TestSoftThread(String name) {
      super(name);
    }

    @Override
    protected void execute() {
      // unused — execute() is the SoftThread loop body; tests that .start() override
      // run() instead so the dispatcher runs once and the thread terminates.
    }
  }

  // Plain Thread (the fallback branch in the dispatcher methods). Never started —
  // used only as a typed argument to setInterruptCurrentOperation(Thread) to pin
  // the non-SoftThread arm.
  private static final class PlainTestThread extends Thread {

    PlainTestThread(String name) {
      super(name);
    }
  }

  // ExecutionThreadLocal.INSTANCE is the singleton on the class. The static
  // initializer wires a startup/shutdown listener that re-creates / nullifies
  // INSTANCE on engine lifecycle. Pin: while the engines manager is up
  // (which it is for the duration of any unit test), INSTANCE is non-null.
  @Test
  public void instanceIsNonNullWhileEnginesManagerIsLive() {
    assertNotNull(
        "ExecutionThreadLocal.INSTANCE must be initialised on class load",
        ExecutionThreadLocal.INSTANCE);
  }

  // initialValue — the protected ThreadLocal hook returns a fresh
  // ExecutionThreadData with both AsyncReplication callbacks null. We can
  // observe this via ThreadLocal.get() the first time on a thread.
  @Test
  public void initialValueIsFreshExecutionThreadData() {
    var local = ExecutionThreadLocal.INSTANCE;
    var data = local.get();
    assertNotNull("initialValue must produce a non-null record", data);
    assertNull("onAsyncReplicationOk should default to null",
        data.onAsyncReplicationOk);
    assertNull("onAsyncReplicationError should default to null",
        data.onAsyncReplicationError);
  }

  // isInterruptCurrentOperation (static) — current Thread.currentThread()
  // is the surefire worker, which is NOT a SoftThread. The branch returns
  // false without inspecting any state.
  @Test
  public void isInterruptCurrentOperationOnNonSoftThreadReturnsFalse() {
    assertFalse(
        "Surefire worker is not a SoftThread; the fallback branch returns"
            + " false unconditionally",
        ExecutionThreadLocal.isInterruptCurrentOperation());
  }

  // setInterruptCurrentOperation(Thread) — null argument falls through the
  // SoftThread instanceof check (instanceof on null is always false), so the
  // dispatcher returns without throwing. Pinned as observed shape against a
  // future refactor that might add an explicit null check.
  @Test
  public void instanceSetInterruptCurrentOperationOnNullIsNoOp() {
    ExecutionThreadLocal.INSTANCE.setInterruptCurrentOperation(null);
    // No assertion beyond no-throw — the contract IS the no-throw, since a
    // null argument has no observable post-state.
  }

  // setInterruptCurrentOperation(Thread) — instance method on the
  // ExecutionThreadLocal object. With a non-SoftThread argument, the
  // method is a no-op (no exception). Pin the no-throw contract so a
  // future refactor that adds null-handling cannot silently change the
  // contract.
  @Test
  public void instanceSetInterruptCurrentOperationOnNonSoftThreadIsNoOp() {
    var nonSoft = new PlainTestThread("plain-test-thread");
    ExecutionThreadLocal.INSTANCE.setInterruptCurrentOperation(nonSoft);
    // Sanity: the plain Thread has no shutdown flag to inspect, so the
    // observable post-condition is "did not throw".
    assertFalse("plain Thread isInterrupted must remain false after no-op",
        nonSoft.isInterrupted());
  }

  // setInterruptCurrentOperation(Thread) — instance method. With a
  // SoftThread argument, the method calls softShutdown() on it, which
  // sets the shutdownFlag.
  @Test
  public void instanceSetInterruptCurrentOperationOnSoftThreadCallsSoftShutdown() {
    var soft = new TestSoftThread("soft-instance-test-thread");
    assertFalse("Pre-condition: shutdown flag is false on a fresh SoftThread",
        soft.isShutdownFlag());

    ExecutionThreadLocal.INSTANCE.setInterruptCurrentOperation(soft);

    assertTrue("setInterruptCurrentOperation must set the shutdown flag",
        soft.isShutdownFlag());
  }

  // setInterruptCurrentOperation() — static no-arg variant operates on
  // Thread.currentThread(). Surefire worker is non-Soft, so the call is
  // a no-op; we pin "did not throw" and that the static dispatch reaches
  // the same fallback branch.
  @Test
  public void staticSetInterruptCurrentOperationOnSurefireWorkerIsNoOp() {
    // Direct invocation on the surefire worker — does not throw because
    // the dispatcher's instanceof check filters out non-SoftThread.
    ExecutionThreadLocal.setInterruptCurrentOperation();
    assertFalse(
        "Static no-op call must not flip the surefire worker's interrupted"
            + " status",
        Thread.currentThread().isInterrupted());
  }

  // setInterruptCurrentOperation() — static no-arg variant on a SoftThread
  // current-thread context. We start a SoftThread that calls the static
  // method on itself; after it finishes, its shutdownFlag must be true.
  // The SoftThread is never .start()'ed via run-loop; we invoke run()
  // directly off-thread to avoid spinning up the engine listener wiring.
  // Instead, we drive the dispatcher via a thin Runnable executed inside
  // a SoftThread-derived Thread object.
  @Test
  public void staticSetInterruptCurrentOperationOnSoftThreadCallsSoftShutdown()
      throws Exception {
    var soft = new TestSoftThread("soft-static-test-thread") {
      @Override
      protected void execute() {
        // not used — we override run() so the loop never starts
      }

      @Override
      public void run() {
        // Run directly on this SoftThread so currentThread() is `this`.
        ExecutionThreadLocal.setInterruptCurrentOperation();
      }
    };
    soft.start();
    soft.join(5_000);
    assertFalse("SoftThread must terminate within 5 s", soft.isAlive());

    assertTrue(
        "The static dispatcher must call softShutdown() on the current"
            + " SoftThread",
        soft.isShutdownFlag());
  }

  // isInterruptCurrentOperation — when run on a SoftThread that has
  // softShutdown set, returns true. Same pattern as above: we override
  // run() to invoke the static check off the surefire worker.
  @Test
  public void isInterruptCurrentOperationOnSoftThreadReflectsShutdownFlag()
      throws Exception {
    var observed = new boolean[2];
    var soft = new TestSoftThread("soft-isinterrupt-test-thread") {
      @Override
      protected void execute() {
        // not used
      }

      @Override
      public void run() {
        observed[0] = ExecutionThreadLocal.isInterruptCurrentOperation();
        ExecutionThreadLocal.setInterruptCurrentOperation();
        observed[1] = ExecutionThreadLocal.isInterruptCurrentOperation();
      }
    };
    soft.start();
    soft.join(5_000);
    assertFalse("SoftThread must terminate within 5 s", soft.isAlive());

    assertFalse("Pre-shutdown isInterruptCurrentOperation must be false",
        observed[0]);
    assertTrue("Post-shutdown isInterruptCurrentOperation must be true",
        observed[1]);
  }
}
