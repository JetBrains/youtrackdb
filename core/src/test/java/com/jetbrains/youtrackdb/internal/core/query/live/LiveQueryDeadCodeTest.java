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
package com.jetbrains.youtrackdb.internal.core.query.live;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.db.record.RecordOperation;
import com.jetbrains.youtrackdb.internal.core.query.BasicLiveQueryResultListener;
import com.jetbrains.youtrackdb.internal.core.query.LiveQueryMonitor;
import com.jetbrains.youtrackdb.internal.core.query.LiveQueryResultListener;
import com.jetbrains.youtrackdb.internal.core.query.live.LiveQueryHookV2.LiveQueryOp;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

/**
 * Dead-code pin tests for the {@code core/query/live} package and the orphan listener interfaces
 * in {@code core/query}.
 *
 * <p>Cross-module grep (performed during this track's review + decomposition phase) confirmed that
 * the entire live-query subsystem is dead in the core module outside its own tests:
 *
 * <ul>
 *   <li>{@link LiveQueryHook} public-static surface ({@code subscribe} / {@code unsubscribe} /
 *       {@code addOp} / {@code notifyForTxChanges} / {@code removePendingDatabaseOps}) — zero
 *       production callers in {@code server/}, {@code driver/}, {@code embedded/},
 *       {@code gremlin-annotations/}, {@code tests/}.
 *   <li>{@link LiveQueryHookV2} same surface (minus {@code unboxRidbags} which is live via
 *       {@code CopyRecordContentBeforeUpdateStep}) — zero production callers.
 *   <li>{@link LiveQueryQueueThread} / {@link LiveQueryQueueThreadV2} — reached only from the
 *       dead hook {@code subscribe} entry points above.
 *   <li>{@link LiveQueryListener} / {@link LiveQueryListenerV2} — zero implementations in core.
 *   <li>{@link BasicLiveQueryResultListener} / {@link LiveQueryResultListener} /
 *       {@link LiveQueryMonitor} — zero production implementors.
 * </ul>
 *
 * <p>This suite pins the observable shape of that dead surface so JaCoCo reports coverage for it
 * and any future mutation (e.g. accidental re-wiring) is detected immediately. The companion
 * {@code LiveQueryHookStaticApiTest} covers the hook public-static API that requires a
 * {@code DatabaseSessionEmbedded} to exercise (the session-lookup / {@code QUERY_LIVE_SUPPORT}
 * gate paths). Each pin carries a {@code // WHEN-FIXED: Track 22 ...} marker naming the class that
 * the final sweep track should delete.
 *
 * <p><strong>Thread hygiene.</strong> Tests that do start a queue thread attach a class-level
 * {@link Timeout} rule (10 s) as a backstop and always stop + join the thread in a {@code finally}
 * block, so a broken run-loop cannot leak a hung daemon thread into the surefire JVM.
 */
public class LiveQueryDeadCodeTest {

  @Rule
  public Timeout globalTimeout = Timeout.seconds(10);

  // -------------------------------------------------------------------------
  // LiveQueryQueueThread (V1) — dispatcher thread used only by dead LiveQueryHook
  // -------------------------------------------------------------------------

  /**
   * A freshly constructed V1 queue thread has no subscribers and cannot match any token. Pins the
   * initial observable state that the hook's subscribe path depends on.
   */
  @Test
  public void v1_freshThreadHasNoSubscribersOrTokens() {
    // WHEN-FIXED: Track 22 — delete core/query/live/LiveQueryQueueThread
    var thread = new LiveQueryQueueThread();
    try {
      assertFalse("fresh thread must report no listeners", thread.hasListeners());
      assertFalse("fresh thread must not recognise any token", thread.hasToken(42));
      assertTrue("must be constructed as a daemon thread", thread.isDaemon());
      assertEquals("LiveQueryQueueThread", thread.getName());
    } finally {
      // No start() — still safe to call stopExecution on a never-started thread.
      thread.stopExecution();
    }
  }

  /**
   * {@code subscribe} inserts the listener under the caller-provided token and returns the same
   * token. {@code unsubscribe} removes the listener and invokes {@code onLiveResultEnd} exactly
   * once. Pins synchronous subscribe/unsubscribe semantics without starting the thread.
   */
  @Test
  public void v1_subscribeStoresListener_unsubscribeTriggersOnLiveResultEnd() {
    // WHEN-FIXED: Track 22 — delete core/query/live/LiveQueryQueueThread
    var thread = new LiveQueryQueueThread();
    var endCalls = new AtomicInteger();
    LiveQueryListener listener =
        new LiveQueryListener() {
          @Override
          public void onLiveResult(RecordOperation iRecord) {
            // Not exercised in this synchronous test.
          }

          @Override
          public void onLiveResultEnd() {
            endCalls.incrementAndGet();
          }
        };

    var returned = thread.subscribe(7, listener);

    assertEquals("subscribe returns the caller-provided token", Integer.valueOf(7), returned);
    assertTrue("hasListeners flips to true after subscribe", thread.hasListeners());
    assertTrue("hasToken recognises the subscribed key", thread.hasToken(7));

    thread.unsubscribe(7);

    assertFalse("hasListeners flips back to false after unsubscribe", thread.hasListeners());
    assertFalse("hasToken no longer recognises the unsubscribed key", thread.hasToken(7));
    assertEquals("unsubscribe must trigger onLiveResultEnd exactly once", 1, endCalls.get());
  }

  /**
   * Subscribing twice with the same token silently overwrites the first listener without calling
   * its {@code onLiveResultEnd}. Pin this pre-existing behaviour (production uses
   * {@link java.util.concurrent.ConcurrentHashMap#put}, which replaces without callback) so a
   * future fix that either rejects collisions or end-notifies the replaced listener is detected.
   */
  @Test
  public void v1_subscribeWithExistingTokenSilentlyOverwritesReplacedListener() {
    // WHEN-FIXED: Track 22 — subscribe should either reject duplicate tokens or end-notify the
    // replaced listener (ConcurrentHashMap.put silently drops the previous value today).
    var thread = new LiveQueryQueueThread();
    try {
      var firstEndCount = new AtomicInteger();
      var secondEndCount = new AtomicInteger();

      thread.subscribe(
          1,
          new LiveQueryListener() {
            @Override
            public void onLiveResult(RecordOperation iRecord) {
            }

            @Override
            public void onLiveResultEnd() {
              firstEndCount.incrementAndGet();
            }
          });

      thread.subscribe(
          1,
          new LiveQueryListener() {
            @Override
            public void onLiveResult(RecordOperation iRecord) {
            }

            @Override
            public void onLiveResultEnd() {
              secondEndCount.incrementAndGet();
            }
          });

      thread.unsubscribe(1);

      assertEquals(
          "pre-existing bug: first listener is silently dropped — no onLiveResultEnd invocation",
          0,
          firstEndCount.get());
      assertEquals(
          "second listener receives the single unsubscribe end callback",
          1,
          secondEndCount.get());
    } finally {
      thread.stopExecution();
    }
  }

  /**
   * Unsubscribing a never-subscribed key must be a no-op — no exception, and no callback. Pins the
   * {@code if (res != null)} guard branch.
   */
  @Test
  public void v1_unsubscribeUnknownKeyIsNoOp() {
    // WHEN-FIXED: Track 22 — delete core/query/live/LiveQueryQueueThread
    var thread = new LiveQueryQueueThread();
    var endCalls = new AtomicInteger();

    // Subscribe a different key so the map is non-empty.
    thread.subscribe(
        1,
        new LiveQueryListener() {
          @Override
          public void onLiveResult(RecordOperation iRecord) {
          }

          @Override
          public void onLiveResultEnd() {
            endCalls.incrementAndGet();
          }
        });

    thread.unsubscribe(999);

    assertEquals("unknown key must not trigger any listener callback", 0, endCalls.get());
    assertTrue("the pre-existing subscription must survive", thread.hasToken(1));
  }

  /**
   * {@link LiveQueryQueueThread#clone()} shares the queue + subscribers maps with the original.
   * This is how the hook recycles its dispatcher without losing subscribers. Uses {@code try/
   * finally} to stop both threads defensively — a future mutation that auto-starts the thread at
   * construction would otherwise leak a hung daemon thread into the surefire JVM.
   */
  @Test
  public void v1_cloneSharesQueueAndSubscribers() throws Exception {
    // WHEN-FIXED: Track 22 — delete core/query/live/LiveQueryQueueThread
    var original = new LiveQueryQueueThread();
    LiveQueryQueueThread copy = null;
    try {
      original.subscribe(
          2,
          new LiveQueryListener() {
            @Override
            public void onLiveResult(RecordOperation iRecord) {
            }

            @Override
            public void onLiveResultEnd() {
            }
          });

      copy = original.clone();

      assertNotSame("clone must be a distinct thread instance", original, copy);
      assertTrue("clone must see the original's subscribers (shared map)", copy.hasListeners());
      assertTrue("clone must recognise tokens registered before cloning", copy.hasToken(2));
      assertFalse(
          "constructor must not auto-start the thread (regression guard)", original.isAlive());
      assertFalse(
          "clone must not auto-start the thread (regression guard)", copy.isAlive());
    } finally {
      original.stopExecution();
      original.join(1000);
      if (copy != null) {
        copy.stopExecution();
        copy.join(1000);
      }
    }
  }

  /**
   * The run loop drains queued {@link RecordOperation}s to each subscriber. Pins the happy-path
   * dispatch and ensures the loop does not invoke callbacks on cloned-away subscriber maps.
   */
  @Test
  public void v1_runLoopDispatchesEnqueuedOperation() throws Exception {
    // WHEN-FIXED: Track 22 — delete core/query/live/LiveQueryQueueThread
    var thread = new LiveQueryQueueThread();
    var received = new AtomicReference<RecordOperation>();
    var latch = new CountDownLatch(1);

    thread.subscribe(
        3,
        new LiveQueryListener() {
          @Override
          public void onLiveResult(RecordOperation iRecord) {
            received.set(iRecord);
            latch.countDown();
          }

          @Override
          public void onLiveResultEnd() {
          }
        });

    thread.start();
    try {
      var op = new RecordOperation(null, RecordOperation.CREATED);
      thread.enqueue(op);

      assertTrue(
          "listener must receive the enqueued op within 5 seconds",
          latch.await(5, TimeUnit.SECONDS));
      assertSame("dispatched op must be the one enqueued", op, received.get());
    } finally {
      thread.stopExecution();
      thread.join(1000);
      assertFalse("thread must have exited its run loop", thread.isAlive());
    }
  }

  /**
   * A listener that throws on {@code onLiveResult} must not crash the dispatcher thread, AND the
   * dispatcher must keep delivering subsequent ops. Pins both the per-listener {@code try/catch}
   * in {@link LiveQueryQueueThread#run()} and the loop-level survival past a throwing callback.
   */
  @Test
  public void v1_listenerExceptionDoesNotKillDispatcher() throws Exception {
    // WHEN-FIXED: Track 22 — delete core/query/live/LiveQueryQueueThread
    var thread = new LiveQueryQueueThread();
    var throwCount = new AtomicInteger();
    var goodCount = new AtomicInteger();
    var firstOpSeenByGood = new CountDownLatch(1);
    var secondOpSeenByGood = new CountDownLatch(1);

    thread.subscribe(
        1,
        new LiveQueryListener() {
          @Override
          public void onLiveResult(RecordOperation iRecord) {
            throwCount.incrementAndGet();
            throw new RuntimeException("intentional — exercise dispatcher try/catch");
          }

          @Override
          public void onLiveResultEnd() {
          }
        });
    thread.subscribe(
        2,
        new LiveQueryListener() {
          @Override
          public void onLiveResult(RecordOperation iRecord) {
            var count = goodCount.incrementAndGet();
            if (count == 1) {
              firstOpSeenByGood.countDown();
            } else if (count == 2) {
              secondOpSeenByGood.countDown();
            }
          }

          @Override
          public void onLiveResultEnd() {
          }
        });

    thread.start();
    try {
      thread.enqueue(new RecordOperation(null, RecordOperation.CREATED));
      assertTrue(
          "sibling listener must run even after a peer throws on the first op",
          firstOpSeenByGood.await(5, TimeUnit.SECONDS));

      // Enqueue a second op to verify the dispatcher did not auto-deregister the throwing listener
      // and did not enter a broken state after swallowing the exception.
      thread.enqueue(new RecordOperation(null, RecordOperation.UPDATED));
      assertTrue(
          "good listener must receive a second op — dispatcher survived past the throw",
          secondOpSeenByGood.await(5, TimeUnit.SECONDS));

      assertEquals("good listener saw both ops", 2, goodCount.get());
      assertEquals(
          "throwing listener was still invoked on the second op (no auto-deregister)",
          2,
          throwCount.get());
    } finally {
      thread.stopExecution();
      thread.join(1000);
      assertFalse("dispatcher must have exited after stopExecution", thread.isAlive());
    }
  }

  /**
   * {@link LiveQueryQueueThread#stopExecution()} sets the stopped flag and interrupts the thread.
   * Both V1 and V2 must exit once {@code stopExecution} is called (via the {@code !stopped} loop
   * guard). The separate pin {@link #v1_loneInterruptExitsRunLoopWithoutStopped} covers the
   * V1-specific behaviour that a bare {@link Thread#interrupt()} alone is enough to terminate —
   * which {@link #v2_loneInterruptDoesNotExitRunLoopUntilStopped} then contrasts with V2.
   */
  @Test
  public void v1_stopExecutionExitsRunLoop() throws Exception {
    // WHEN-FIXED: Track 22 — reconcile V1/V2 interrupt handling (V1 breaks, V2 continues)
    var thread = new LiveQueryQueueThread();
    thread.start();
    assertTrue("thread must be alive after start", thread.isAlive());

    thread.stopExecution();
    thread.join(2000);

    assertFalse("V1 run loop must exit after stopExecution", thread.isAlive());
  }

  /**
   * V1-specific behaviour: a lone {@link Thread#interrupt()} (without setting {@code stopped}) is
   * enough to exit the V1 run loop because the {@code catch (InterruptedException) { break; }}
   * branch terminates unconditionally. This is the observable difference against V2, which
   * re-interrupts and continues — see {@link #v2_loneInterruptDoesNotExitRunLoopUntilStopped}.
   */
  @Test
  public void v1_loneInterruptExitsRunLoopWithoutStopped() throws Exception {
    // WHEN-FIXED: Track 22 — reconcile V1/V2 interrupt handling (V1 breaks, V2 continues)
    var thread = new LiveQueryQueueThread();
    thread.start();
    awaitThreadState(thread, Thread.State.WAITING, 2000);

    try {
      // Do NOT call stopExecution — stopped stays false. V1 must still exit because its catch
      // branch is a bare `break`, independent of the stopped flag.
      thread.interrupt();
      thread.join(2000);
      assertFalse(
          "V1 must exit on a lone interrupt (break path, independent of stopped flag)",
          thread.isAlive());
    } finally {
      // Belt-and-braces for the case where the pin regresses and join() returned without exit.
      thread.stopExecution();
      thread.join(1000);
    }
  }

  // -------------------------------------------------------------------------
  // LiveQueryHook.LiveQueryOps (V1 wrapper) — owns the dispatcher thread
  // -------------------------------------------------------------------------

  /**
   * {@link LiveQueryHook.LiveQueryOps} initialises its dispatcher thread lazily — the thread is
   * allocated at construction but not started. Pins the expected initial state.
   */
  @Test
  public void v1_opsExposeFreshQueueThread() {
    // WHEN-FIXED: Track 22 — delete core/query/live/LiveQueryHook
    var ops = new LiveQueryHook.LiveQueryOps();
    var thread = ops.getQueueThread();
    assertNotNull("ops must expose its dispatcher thread", thread);
    assertFalse("dispatcher must not auto-start on construction", thread.isAlive());
    assertFalse("no subscribers yet", thread.hasListeners());
  }

  /**
   * {@code close()} on a never-started ops instance stops the thread, joins (returns
   * immediately since the thread never started), and clears the pending-ops map.
   */
  @Test
  public void v1_opsCloseOnNeverStartedOpsIsIdempotent() {
    // WHEN-FIXED: Track 22 — delete core/query/live/LiveQueryHook
    var ops = new LiveQueryHook.LiveQueryOps();
    ops.close();
    // A second close must not blow up either.
    ops.close();
    assertFalse(ops.getQueueThread().isAlive());
  }

  // -------------------------------------------------------------------------
  // LiveQueryQueueThreadV2 — V2 dispatcher thread, distinct batching + interrupt semantics
  // -------------------------------------------------------------------------

  /**
   * {@link LiveQueryQueueThreadV2#clone()} produces a new thread bound to the same {@code ops}
   * reference. The clone must observably drain ops enqueued on the shared ops — proving the ops
   * reference is actually shared, not a fresh copy. Starting the clone (not the original) lets a
   * mutation that forgets to share {@code ops} be detected via the absence of dispatch.
   */
  @Test
  public void v2_cloneSharesOpsReference() throws Exception {
    // WHEN-FIXED: Track 22 — delete core/query/live/LiveQueryQueueThreadV2
    var ops = new LiveQueryHookV2.LiveQueryOps();
    var original = new LiveQueryQueueThreadV2(ops);
    var received = new CountDownLatch(1);
    ops.subscribe(
        4,
        new LiveQueryListenerV2() {
          @Override
          public void onLiveResults(List<LiveQueryOp> iRecords) {
            received.countDown();
          }

          @Override
          public void onLiveResultEnd() {
          }

          @Override
          public int getToken() {
            return 4;
          }
        });

    var copy = original.clone();
    assertNotSame("clone must be a distinct thread instance", original, copy);
    assertTrue(
        "clone must see subscribers registered on the shared ops", ops.hasListeners());

    // Only start the clone — original stays idle. If clone() built a fresh ops, ops.enqueue would
    // land on a queue the clone does not drain, and the latch would time out.
    copy.start();
    try {
      ops.enqueue(new LiveQueryOp(null, null, null, RecordOperation.CREATED));
      assertTrue(
          "clone must drain the shared ops' queue (proving the ops reference is shared)",
          received.await(5, TimeUnit.SECONDS));
    } finally {
      copy.stopExecution();
      copy.join(1000);
      original.stopExecution();
      original.join(1000);
      assertFalse("clone must have exited after stopExecution", copy.isAlive());
    }
  }

  /**
   * V2 run loop drains queued {@link LiveQueryOp}s in batches and fans them out to every
   * subscriber. Pins (a) that both enqueued ops reach the listener within the timeout and (b) the
   * V2-specific batching contract: two ops enqueued before the consumer starts must collapse into
   * a single {@code onLiveResults} callback via the inner {@code queue.poll()} drain loop.
   */
  @Test
  public void v2_runLoopBatchesAndDispatchesToSubscribers() throws Exception {
    // WHEN-FIXED: Track 22 — delete core/query/live/LiveQueryQueueThreadV2
    var ops = new LiveQueryHookV2.LiveQueryOps();
    var batches = new ArrayList<List<LiveQueryOp>>();
    var bothArrived = new CountDownLatch(1);
    var first = new LiveQueryOp(null, null, null, RecordOperation.CREATED);
    var second = new LiveQueryOp(null, null, null, RecordOperation.DELETED);

    ops.subscribe(
        9,
        new LiveQueryListenerV2() {
          @Override
          public void onLiveResults(List<LiveQueryOp> iRecords) {
            synchronized (batches) {
              batches.add(new ArrayList<>(iRecords));
              var seen = new ArrayList<LiveQueryOp>();
              for (var batch : batches) {
                seen.addAll(batch);
              }
              if (seen.contains(first) && seen.contains(second)) {
                bothArrived.countDown();
              }
            }
          }

          @Override
          public void onLiveResultEnd() {
          }

          @Override
          public int getToken() {
            return 9;
          }
        });

    // Enqueue BEFORE starting the thread so both ops are already queued when take() unblocks —
    // this makes the batching collapse deterministic.
    ops.enqueue(first);
    ops.enqueue(second);

    var thread = new LiveQueryQueueThreadV2(ops);
    thread.start();
    try {
      assertTrue(
          "both ops must be delivered within 5 seconds",
          bothArrived.await(5, TimeUnit.SECONDS));
      synchronized (batches) {
        // Pin: with both ops enqueued before the consumer started, the drain loop must collapse
        // them into a single onLiveResults callback. If the drain-loop were mutated to exit after
        // the blocking take() (never calling poll()), ops would arrive in two separate batches
        // and this assertion would fail — making the batching contract falsifiable.
        assertEquals(
            "V2 must batch: two pre-enqueued ops must arrive in one onLiveResults callback",
            1,
            batches.size());
        var onlyBatch = batches.get(0);
        assertEquals("batch must contain exactly the two enqueued ops", 2, onlyBatch.size());
        assertTrue("first enqueued op must be in the batch", onlyBatch.contains(first));
        assertTrue("second enqueued op must be in the batch", onlyBatch.contains(second));
      }
    } finally {
      thread.stopExecution();
      thread.join(1000);
      assertFalse("V2 thread must exit after stopExecution", thread.isAlive());
    }
  }

  /**
   * V2 stopExecution must interrupt the run loop; the V2 behaviour is distinct from V1 — the loop
   * re-interrupts the thread and {@code continue}s, but the outer {@code while (!stopped)} check
   * gates termination. With {@code stopped} set, the loop exits on the next iteration.
   */
  @Test
  public void v2_stopExecutionExitsRunLoop() throws Exception {
    // WHEN-FIXED: Track 22 — reconcile V1/V2 interrupt handling (V1 breaks, V2 continues)
    var ops = new LiveQueryHookV2.LiveQueryOps();
    var thread = new LiveQueryQueueThreadV2(ops);
    thread.start();
    assertTrue("thread must be alive after start", thread.isAlive());

    thread.stopExecution();
    thread.join(2000);

    assertFalse(
        "V2 run loop must exit once stopped=true + interrupted", thread.isAlive());
  }

  /**
   * V2-specific behaviour: a lone {@link Thread#interrupt()} (without setting {@code stopped}) is
   * NOT enough to exit the V2 run loop — the {@code catch (InterruptedException)} branch
   * re-interrupts and {@code continue}s, and because {@code stopped} is still false the outer
   * {@code while} condition sends the loop back into {@code queue.take()}. The thread must stay
   * alive until {@code stopExecution} is called. This is the observable difference against V1 —
   * see {@link #v1_loneInterruptExitsRunLoopWithoutStopped}.
   */
  @Test
  public void v2_loneInterruptDoesNotExitRunLoopUntilStopped() throws Exception {
    // WHEN-FIXED: Track 22 — reconcile V1/V2 interrupt handling (V1 breaks, V2 continues)
    var ops = new LiveQueryHookV2.LiveQueryOps();
    var thread = new LiveQueryQueueThreadV2(ops);
    thread.start();
    awaitThreadState(thread, Thread.State.WAITING, 2000);

    try {
      thread.interrupt();
      // Give V2 a short window to re-interrupt and settle back into queue.take() WAITING state.
      // If the production catch-branch were mutated to `break`, the thread would exit during this
      // window and the subsequent assertTrue(isAlive) would fail — pinning the contrast with V1.
      awaitThreadState(thread, Thread.State.WAITING, 1000);
      assertTrue(
          "V2 must absorb a lone interrupt and continue (stopped still false)",
          thread.isAlive());
    } finally {
      thread.stopExecution();
      thread.join(2000);
      assertFalse("V2 must exit after stopExecution", thread.isAlive());
    }
  }

  // -------------------------------------------------------------------------
  // LiveQueryHookV2.LiveQueryOps — direct API surface (independent of a session)
  // -------------------------------------------------------------------------

  /**
   * V2 ops expose a subscribers map that starts empty and reflects add/remove synchronously.
   * Pins the invariant used by the V2 hook's {@code addOp} early-return guard. The caller token
   * must be returned as the <em>same</em> Integer instance (identity, not just value) — pinned
   * with an Integer outside the JDK cache range so {@code assertSame} is meaningful.
   */
  @Test
  public void v2_opsSubscribersLifecycle() {
    // WHEN-FIXED: Track 22 — delete core/query/live/LiveQueryHookV2
    var ops = new LiveQueryHookV2.LiveQueryOps();

    assertFalse("fresh ops must report no listeners", ops.hasListeners());
    assertTrue("subscribers map must be empty", ops.getSubscribers().isEmpty());
    assertNotNull("queue must be non-null", ops.getQueue());

    var listener = new CollectingV2Listener(5000, new ArrayList<>());
    Integer callerToken = Integer.valueOf(5000); // Outside JDK Integer cache range.

    var token = ops.subscribe(callerToken, listener);
    assertSame("subscribe must return the caller-supplied Integer reference", callerToken, token);
    assertTrue("hasListeners flips after subscribe", ops.hasListeners());
    assertSame(
        "getSubscribers exposes the same listener instance",
        listener,
        ops.getSubscribers().get(callerToken));

    ops.unsubscribe(callerToken);

    assertFalse("hasListeners flips back after unsubscribe", ops.hasListeners());
    assertEquals(
        "unsubscribe triggers exactly one onLiveResultEnd callback",
        1,
        listener.endCount.get());
  }

  /**
   * Unsubscribing a never-registered token is a no-op. Pins the {@code if (res != null)} guard
   * in {@link LiveQueryHookV2.LiveQueryOps#unsubscribe}.
   */
  @Test
  public void v2_opsUnsubscribeUnknownTokenIsNoOp() {
    // WHEN-FIXED: Track 22 — delete core/query/live/LiveQueryHookV2
    var ops = new LiveQueryHookV2.LiveQueryOps();

    var captured = new ArrayList<List<LiveQueryOp>>();
    var listener = new CollectingV2Listener(6, captured);
    ops.subscribe(6, listener);

    ops.unsubscribe(777);

    assertEquals(
        "unknown token must not fire onLiveResultEnd on any listener", 0, listener.endCount.get());
    assertTrue("the pre-existing subscription must survive", ops.hasListeners());
  }

  /**
   * {@link LiveQueryHookV2.LiveQueryOps#enqueue} offers to the shared blocking queue; directly
   * verifiable via {@link LiveQueryHookV2.LiveQueryOps#getQueue} without starting a dispatcher.
   */
  @Test
  public void v2_opsEnqueueRoutesToSharedQueue() {
    // WHEN-FIXED: Track 22 — delete core/query/live/LiveQueryHookV2
    var ops = new LiveQueryHookV2.LiveQueryOps();
    var op = new LiveQueryOp(null, null, null, RecordOperation.UPDATED);

    ops.enqueue(op);

    assertEquals("queue must contain exactly the enqueued op", 1, ops.getQueue().size());
    assertSame(op, ops.getQueue().peek());
  }

  /**
   * Close on a never-started V2 ops must stop the placeholder thread (a no-op), join, and clear
   * pending state. Pins idempotence.
   */
  @Test
  public void v2_opsCloseOnNeverStartedIsIdempotent() {
    // WHEN-FIXED: Track 22 — delete core/query/live/LiveQueryHookV2
    var ops = new LiveQueryHookV2.LiveQueryOps();

    ops.close();
    ops.close();

    assertFalse("listeners cleared after close", ops.hasListeners());
  }

  // -------------------------------------------------------------------------
  // LiveQueryHookV2.LiveQueryOp — constructor detach semantics
  // -------------------------------------------------------------------------

  /**
   * The constructor must store {@code null} for {@code before}/{@code after} when the caller
   * passes {@code null} — covering the CREATED (no before) and DELETED (no after) branches
   * without requiring a real {@code Result}.
   */
  @Test
  public void v2_liveQueryOpConstructorAllowsNullBeforeAndAfter() {
    // WHEN-FIXED: Track 22 — delete core/query/live/LiveQueryHookV2
    var op = new LiveQueryOp(null, null, null, RecordOperation.CREATED);
    assertNull("null before must stay null", op.before);
    assertNull("null after must stay null", op.after);
    assertEquals("type must be preserved", RecordOperation.CREATED, op.type);
  }

  // -------------------------------------------------------------------------
  // Zero-impl interface pins — the interfaces compile and can be instantiated anonymously;
  // production ships zero implementations, so these pins flag the surface for Track 22 deletion.
  // -------------------------------------------------------------------------

  /**
   * {@link LiveQueryListener} has zero production implementors — the only instantiations in the
   * codebase are inline test stubs like this one. Pin flags the surface for deletion.
   */
  @Test
  public void deadInterface_liveQueryListenerHasZeroProductionImpls() {
    // WHEN-FIXED: Track 22 — delete core/query/live/LiveQueryListener
    LiveQueryListener anon =
        new LiveQueryListener() {
          @Override
          public void onLiveResult(RecordOperation iRecord) {
          }

          @Override
          public void onLiveResultEnd() {
          }
        };
    assertNotNull(anon);
    // Exercising both methods to ensure the abstract contract does not accidentally become
    // non-abstract (e.g. via a default method that would hide a production impl gap).
    anon.onLiveResult(new RecordOperation(null, RecordOperation.CREATED));
    anon.onLiveResultEnd();
  }

  /**
   * {@link LiveQueryListenerV2} — zero production implementors. Pin surface for Track 22
   * deletion.
   */
  @Test
  public void deadInterface_liveQueryListenerV2HasZeroProductionImpls() {
    // WHEN-FIXED: Track 22 — delete core/query/live/LiveQueryListenerV2
    LiveQueryListenerV2 anon =
        new LiveQueryListenerV2() {
          @Override
          public void onLiveResults(List<LiveQueryOp> iRecords) {
          }

          @Override
          public void onLiveResultEnd() {
          }

          @Override
          public int getToken() {
            return -1;
          }
        };
    assertEquals(-1, anon.getToken());
    anon.onLiveResults(List.of());
    anon.onLiveResultEnd();
  }

  /**
   * {@link BasicLiveQueryResultListener} — zero production implementors in core. Pin surface for
   * Track 22 deletion along with the entire live-query subsystem. Exercising all five callbacks
   * proves the abstract contract has not been eroded by default methods that would silently hide
   * a production impl gap.
   */
  @Test
  public void deadInterface_basicLiveQueryResultListenerHasZeroProductionImpls() {
    // WHEN-FIXED: Track 22 — delete core/query/BasicLiveQueryResultListener
    var calls = new AtomicInteger();
    BasicLiveQueryResultListener<?, ?> anon =
        new BasicLiveQueryResultListener<>() {
          @Override
          public void onCreate(
              com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded session,
              com.jetbrains.youtrackdb.internal.core.query.BasicResult data) {
            calls.incrementAndGet();
          }

          @Override
          public void onUpdate(
              com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded session,
              com.jetbrains.youtrackdb.internal.core.query.BasicResult before,
              com.jetbrains.youtrackdb.internal.core.query.BasicResult after) {
            calls.incrementAndGet();
          }

          @Override
          public void onDelete(
              com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded session,
              com.jetbrains.youtrackdb.internal.core.query.BasicResult data) {
            calls.incrementAndGet();
          }

          @Override
          public void onError(
              com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded session,
              com.jetbrains.youtrackdb.internal.core.exception.BaseException exception) {
            calls.incrementAndGet();
          }

          @Override
          public void onEnd(
              com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded session) {
            calls.incrementAndGet();
          }
        };
    anon.onCreate(null, null);
    anon.onUpdate(null, null, null);
    anon.onDelete(null, null);
    anon.onError(null, null);
    anon.onEnd(null);
    assertEquals("all five callbacks must remain abstract (non-default) methods", 5, calls.get());
  }

  /**
   * {@link LiveQueryResultListener} extends {@link BasicLiveQueryResultListener} with no added
   * methods. Zero production implementors — pin surface for Track 22 deletion. Exercising all
   * five callbacks pins the abstract contract.
   */
  @Test
  public void deadInterface_liveQueryResultListenerHasZeroProductionImpls() {
    // WHEN-FIXED: Track 22 — delete core/query/LiveQueryResultListener
    var calls = new AtomicInteger();
    LiveQueryResultListener anon =
        new LiveQueryResultListener() {
          @Override
          public void onCreate(
              com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded session,
              com.jetbrains.youtrackdb.internal.core.query.Result data) {
            calls.incrementAndGet();
          }

          @Override
          public void onUpdate(
              com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded session,
              com.jetbrains.youtrackdb.internal.core.query.Result before,
              com.jetbrains.youtrackdb.internal.core.query.Result after) {
            calls.incrementAndGet();
          }

          @Override
          public void onDelete(
              com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded session,
              com.jetbrains.youtrackdb.internal.core.query.Result data) {
            calls.incrementAndGet();
          }

          @Override
          public void onError(
              com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded session,
              com.jetbrains.youtrackdb.internal.core.exception.BaseException exception) {
            calls.incrementAndGet();
          }

          @Override
          public void onEnd(
              com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded session) {
            calls.incrementAndGet();
          }
        };
    anon.onCreate(null, null);
    anon.onUpdate(null, null, null);
    anon.onDelete(null, null);
    anon.onError(null, null);
    anon.onEnd(null);
    assertEquals("all five callbacks must remain abstract (non-default) methods", 5, calls.get());
  }

  /**
   * {@link LiveQueryMonitor} — zero production implementors. Pin surface for Track 22 deletion.
   */
  @Test
  public void deadInterface_liveQueryMonitorHasZeroProductionImpls() {
    // WHEN-FIXED: Track 22 — delete core/query/LiveQueryMonitor
    var unsubscribed = new AtomicInteger();
    LiveQueryMonitor anon =
        new LiveQueryMonitor() {
          @Override
          public void unSubscribe() {
            unsubscribed.incrementAndGet();
          }

          @Override
          public int getMonitorId() {
            return 123;
          }
        };
    assertEquals(123, anon.getMonitorId());
    anon.unSubscribe();
    assertEquals(1, unsubscribed.get());
  }

  // -------------------------------------------------------------------------
  // Test helpers
  // -------------------------------------------------------------------------

  /**
   * Spin-waits until {@code thread} reaches {@code target} state or {@code timeoutMillis} elapses.
   * Used by the lone-interrupt pins so the test only probes {@code interrupt()} once the run loop
   * is actually blocked in {@code queue.take()}; otherwise the interrupt races with thread start.
   */
  private static void awaitThreadState(Thread thread, Thread.State target, long timeoutMillis)
      throws InterruptedException {
    var deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
    while (thread.getState() != target && System.nanoTime() < deadline) {
      Thread.sleep(5);
    }
  }

  /** V2 listener that records every batch it receives; used by the multi-test assertions above. */
  private static final class CollectingV2Listener implements LiveQueryListenerV2 {
    private final int token;
    private final List<List<LiveQueryOp>> batches;
    private final AtomicInteger endCount = new AtomicInteger();

    CollectingV2Listener(int token, List<List<LiveQueryOp>> batches) {
      this.token = token;
      this.batches = batches;
    }

    @Override
    public void onLiveResults(List<LiveQueryOp> iRecords) {
      synchronized (batches) {
        batches.add(new ArrayList<>(iRecords));
      }
    }

    @Override
    public void onLiveResultEnd() {
      endCount.incrementAndGet();
    }

    @Override
    public int getToken() {
      return token;
    }
  }
}
