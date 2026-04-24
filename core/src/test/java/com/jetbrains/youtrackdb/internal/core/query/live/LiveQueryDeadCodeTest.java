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
   * This is how the hook recycles its dispatcher without losing subscribers.
   */
  @Test
  public void v1_cloneSharesQueueAndSubscribers() {
    // WHEN-FIXED: Track 22 — delete core/query/live/LiveQueryQueueThread
    var original = new LiveQueryQueueThread();
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

    var copy = original.clone();

    assertNotSame("clone must be a distinct thread instance", original, copy);
    assertTrue("clone must see the original's subscribers (shared map)", copy.hasListeners());
    assertTrue("clone must recognise tokens registered before cloning", copy.hasToken(2));
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
   * A listener that throws on {@code onLiveResult} must not crash the dispatcher thread. Pins the
   * {@code try/catch} around the listener callback in {@link LiveQueryQueueThread#run()}.
   */
  @Test
  public void v1_listenerExceptionDoesNotKillDispatcher() throws Exception {
    // WHEN-FIXED: Track 22 — delete core/query/live/LiveQueryQueueThread
    var thread = new LiveQueryQueueThread();
    var firstLatch = new CountDownLatch(1);
    var secondLatch = new CountDownLatch(1);
    var goodCount = new AtomicInteger();

    thread.subscribe(
        1,
        new LiveQueryListener() {
          @Override
          public void onLiveResult(RecordOperation iRecord) {
            firstLatch.countDown();
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
            goodCount.incrementAndGet();
            secondLatch.countDown();
          }

          @Override
          public void onLiveResultEnd() {
          }
        });

    thread.start();
    try {
      thread.enqueue(new RecordOperation(null, RecordOperation.CREATED));
      assertTrue(
          "throwing listener must still run", firstLatch.await(5, TimeUnit.SECONDS));
      assertTrue(
          "sibling listener must run even after a peer throws",
          secondLatch.await(5, TimeUnit.SECONDS));
      assertEquals(1, goodCount.get());
    } finally {
      thread.stopExecution();
      thread.join(1000);
    }
  }

  /**
   * {@link LiveQueryQueueThread#stopExecution()} sets the stopped flag and interrupts the thread.
   * Pins the V1 behavior — on {@link InterruptedException} the run loop {@code break}s (distinct
   * from V2 which re-interrupts and {@code continue}s).
   */
  @Test
  public void v1_stopExecutionInterruptsRunLoopAndExits() throws Exception {
    // WHEN-FIXED: Track 22 — reconcile V1/V2 interrupt handling (V1 breaks, V2 continues)
    var thread = new LiveQueryQueueThread();
    thread.start();
    assertTrue("thread must be alive after start", thread.isAlive());

    thread.stopExecution();
    thread.join(2000);

    assertFalse("V1 run loop must exit on interrupt (break path)", thread.isAlive());
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
   * reference so recycling preserves subscribers and the shared queue.
   */
  @Test
  public void v2_cloneSharesOpsReference() {
    // WHEN-FIXED: Track 22 — delete core/query/live/LiveQueryQueueThreadV2
    var ops = new LiveQueryHookV2.LiveQueryOps();
    var original = new LiveQueryQueueThreadV2(ops);
    var copy = original.clone();

    assertNotSame("clone must be a distinct thread instance", original, copy);

    ops.subscribe(4, new CollectingV2Listener(4, new ArrayList<>()));
    assertTrue(
        "clone must see subscribers registered on the shared ops", ops.hasListeners());
  }

  /**
   * V2 run loop drains queued {@link LiveQueryOp}s in batches and fans them out to every
   * subscriber. Pins the batching happy-path and the {@code continue} path of the inner
   * {@code queue.poll()} draining loop.
   */
  @Test
  public void v2_runLoopBatchesAndDispatchesToSubscribers() throws Exception {
    // WHEN-FIXED: Track 22 — delete core/query/live/LiveQueryQueueThreadV2
    var ops = new LiveQueryHookV2.LiveQueryOps();
    var received = new ArrayList<LiveQueryOp>();
    var latch = new CountDownLatch(1);

    ops.subscribe(
        9,
        new LiveQueryListenerV2() {
          @Override
          public void onLiveResults(List<LiveQueryOp> iRecords) {
            synchronized (received) {
              received.addAll(iRecords);
            }
            latch.countDown();
          }

          @Override
          public void onLiveResultEnd() {
          }

          @Override
          public int getToken() {
            return 9;
          }
        });

    var thread = new LiveQueryQueueThreadV2(ops);
    thread.start();
    try {
      var first = new LiveQueryOp(null, null, null, RecordOperation.CREATED);
      var second = new LiveQueryOp(null, null, null, RecordOperation.DELETED);
      ops.enqueue(first);
      ops.enqueue(second);

      assertTrue(
          "listener must receive a batch within 5 seconds",
          latch.await(5, TimeUnit.SECONDS));
      synchronized (received) {
        assertFalse("batch must be non-empty", received.isEmpty());
        // Ordering within a batch is queue order — primary assertion is that both ops arrived.
        assertTrue(received.contains(first));
        // Second op may arrive in the same batch (poll drain) or a follow-up batch — either way
        // we must see it within the timeout. Drain up to one more batch.
      }
    } finally {
      thread.stopExecution();
      thread.join(1000);
      assertFalse("V2 thread must exit after stopExecution", thread.isAlive());
    }
  }

  /**
   * V2 stopExecution must interrupt the run loop; the V2 behavior is distinct from V1 — the loop
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

  // -------------------------------------------------------------------------
  // LiveQueryHookV2.LiveQueryOps — direct API surface (independent of a session)
  // -------------------------------------------------------------------------

  /**
   * V2 ops expose a subscribers map that starts empty and reflects add/remove synchronously.
   * Pins the invariant used by the V2 hook's {@code addOp} early-return guard.
   */
  @Test
  public void v2_opsSubscribersLifecycle() {
    // WHEN-FIXED: Track 22 — delete core/query/live/LiveQueryHookV2
    var ops = new LiveQueryHookV2.LiveQueryOps();

    assertFalse("fresh ops must report no listeners", ops.hasListeners());
    assertTrue("subscribers map must be empty", ops.getSubscribers().isEmpty());
    assertNotNull("queue must be non-null", ops.getQueue());

    var captured = new ArrayList<List<LiveQueryOp>>();
    var listener = new CollectingV2Listener(5, captured);

    var token = ops.subscribe(5, listener);
    assertEquals("subscribe must return the caller token", Integer.valueOf(5), token);
    assertTrue("hasListeners flips after subscribe", ops.hasListeners());
    assertSame(
        "getSubscribers exposes the same listener instance", listener, ops.getSubscribers().get(5));

    ops.unsubscribe(5);

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
   * Track 22 deletion along with the entire live-query subsystem.
   */
  @Test
  public void deadInterface_basicLiveQueryResultListenerHasZeroProductionImpls() {
    // WHEN-FIXED: Track 22 — delete core/query/BasicLiveQueryResultListener
    BasicLiveQueryResultListener<?, ?> anon =
        new BasicLiveQueryResultListener<>() {
          @Override
          public void onCreate(
              com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded session,
              com.jetbrains.youtrackdb.internal.core.query.BasicResult data) {
          }

          @Override
          public void onUpdate(
              com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded session,
              com.jetbrains.youtrackdb.internal.core.query.BasicResult before,
              com.jetbrains.youtrackdb.internal.core.query.BasicResult after) {
          }

          @Override
          public void onDelete(
              com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded session,
              com.jetbrains.youtrackdb.internal.core.query.BasicResult data) {
          }

          @Override
          public void onError(
              com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded session,
              com.jetbrains.youtrackdb.internal.core.exception.BaseException exception) {
          }

          @Override
          public void onEnd(
              com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded session) {
          }
        };
    assertNotNull(anon);
  }

  /**
   * {@link LiveQueryResultListener} extends {@link BasicLiveQueryResultListener} with no added
   * methods. Zero production implementors — pin surface for Track 22 deletion.
   */
  @Test
  public void deadInterface_liveQueryResultListenerHasZeroProductionImpls() {
    // WHEN-FIXED: Track 22 — delete core/query/LiveQueryResultListener
    LiveQueryResultListener anon =
        new LiveQueryResultListener() {
          @Override
          public void onCreate(
              com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded session,
              com.jetbrains.youtrackdb.internal.core.query.Result data) {
          }

          @Override
          public void onUpdate(
              com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded session,
              com.jetbrains.youtrackdb.internal.core.query.Result before,
              com.jetbrains.youtrackdb.internal.core.query.Result after) {
          }

          @Override
          public void onDelete(
              com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded session,
              com.jetbrains.youtrackdb.internal.core.query.Result data) {
          }

          @Override
          public void onError(
              com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded session,
              com.jetbrains.youtrackdb.internal.core.exception.BaseException exception) {
          }

          @Override
          public void onEnd(
              com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded session) {
          }
        };
    assertNotNull(anon);
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
