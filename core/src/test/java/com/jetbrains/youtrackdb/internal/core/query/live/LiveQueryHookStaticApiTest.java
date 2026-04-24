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

import static com.jetbrains.youtrackdb.api.config.GlobalConfiguration.QUERY_LIVE_SUPPORT;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.db.record.RecordOperation;
import com.jetbrains.youtrackdb.internal.core.query.live.LiveQueryHookV2.LiveQueryOp;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.sql.executor.TestUtilsFixture;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

/**
 * DB-backed pin tests for the {@link LiveQueryHook} and {@link LiveQueryHookV2} public-static API.
 *
 * <p>The static entry points all accept a {@code DatabaseSessionEmbedded}, resolve its
 * {@code SharedContext#getLiveQueryOps}/{@code getLiveQueryOpsV2} reference, and gate behaviour on
 * the session's {@code QUERY_LIVE_SUPPORT} configuration flag. Exercising those branches therefore
 * requires a real session — the sibling {@link LiveQueryDeadCodeTest} covers the parts that can be
 * tested synchronously on the inner {@code LiveQueryOps} / {@code LiveQueryQueueThread} objects.
 *
 * <p>Cross-module grep (performed during this track's review + decomposition phase) confirmed the
 * entire static surface covered here has <strong>zero</strong> production callers in
 * {@code server/}, {@code driver/}, {@code embedded/}, {@code gremlin-annotations/},
 * {@code tests/}. Every {@code @Test} is annotated with a
 * {@code // WHEN-FIXED: Track 22 — delete <class>} marker naming the class the final sweep track
 * should remove once the feature is formally retired.
 *
 * <p>Extends {@link TestUtilsFixture} for the shared {@code @After rollbackIfLeftOpen} safety net.
 * Tests never start a dispatcher thread — the subscribe path will in production (via
 * {@code ops.queueThread.start()}), but the safety net here is the class-level
 * {@link Timeout(15)} rule and the explicit {@code unsubscribe} / {@code LiveQueryOps#close} in
 * every test that does subscribe.
 */
public class LiveQueryHookStaticApiTest extends TestUtilsFixture {

  @Rule
  public Timeout globalTimeout = Timeout.seconds(15);

  // -------------------------------------------------------------------------
  // LiveQueryHook (V1) — getOpsReference, subscribe, unsubscribe, addOp,
  // notifyForTxChanges, removePendingDatabaseOps
  // -------------------------------------------------------------------------

  /**
   * {@link LiveQueryHook#getOpsReference} returns the same {@link LiveQueryHook.LiveQueryOps}
   * instance held by the session's {@code SharedContext} — identity matters because subscribers
   * are stored there and a fresh copy would lose them.
   */
  @Test
  public void v1_getOpsReferenceReturnsSharedContextSingleton() {
    // WHEN-FIXED: Track 22 — delete core/query/live/LiveQueryHook
    var ops1 = LiveQueryHook.getOpsReference(session);
    var ops2 = LiveQueryHook.getOpsReference(session);
    assertSame("getOpsReference must return the SharedContext singleton each call", ops1, ops2);
  }

  /**
   * With {@code QUERY_LIVE_SUPPORT = false} the subscribe path logs a warning and returns -1
   * without registering the listener. Pins the disabled-support early return.
   */
  @Test
  public void v1_subscribeWithLiveSupportDisabledReturnsMinusOne() {
    // WHEN-FIXED: Track 22 — delete core/query/live/LiveQueryHook
    var ops = LiveQueryHook.getOpsReference(session);
    var originalFlag = session.getConfiguration().getValue(QUERY_LIVE_SUPPORT);
    try {
      session.getConfiguration().setValue(QUERY_LIVE_SUPPORT, false);
      var token =
          LiveQueryHook.subscribe(
              1,
              new LiveQueryListener() {
                @Override
                public void onLiveResult(RecordOperation iRecord) {
                }

                @Override
                public void onLiveResultEnd() {
                }
              },
              session);
      assertEquals("disabled support must return -1", Integer.valueOf(-1), token);
      assertFalse(
          "listener must not be registered when live support is disabled",
          ops.getQueueThread().hasListeners());
    } finally {
      session.getConfiguration().setValue(QUERY_LIVE_SUPPORT, originalFlag);
    }
  }

  /**
   * With {@code QUERY_LIVE_SUPPORT = false} the unsubscribe path early-returns without touching
   * the ops subscriber map. Sibling to the subscribe disabled-support path.
   */
  @Test
  public void v1_unsubscribeWithLiveSupportDisabledIsNoOp() {
    // WHEN-FIXED: Track 22 — delete core/query/live/LiveQueryHook
    var ops = LiveQueryHook.getOpsReference(session);
    var endCount = new AtomicInteger();
    ops.getQueueThread()
        .subscribe(
            42,
            new LiveQueryListener() {
              @Override
              public void onLiveResult(RecordOperation iRecord) {
              }

              @Override
              public void onLiveResultEnd() {
                endCount.incrementAndGet();
              }
            });

    var originalFlag = session.getConfiguration().getValue(QUERY_LIVE_SUPPORT);
    try {
      session.getConfiguration().setValue(QUERY_LIVE_SUPPORT, false);
      LiveQueryHook.unsubscribe(42, session);
      assertTrue(
          "disabled-support unsubscribe must not remove the listener",
          ops.getQueueThread().hasToken(42));
      assertEquals("onLiveResultEnd must not fire", 0, endCount.get());
    } finally {
      session.getConfiguration().setValue(QUERY_LIVE_SUPPORT, originalFlag);
      ops.getQueueThread().unsubscribe(42);
    }
  }

  /**
   * With no listeners registered, {@code addOp} must early-return without allocating a
   * {@link RecordOperation} or touching the pending-ops map. Pins the
   * {@code if (!ops.queueThread.hasListeners())} guard.
   */
  @Test
  public void v1_addOpWithNoListenersIsNoOp() {
    // WHEN-FIXED: Track 22 — delete core/query/live/LiveQueryHook
    var ops = LiveQueryHook.getOpsReference(session);
    assertFalse("precondition: no listeners", ops.getQueueThread().hasListeners());

    session.begin();
    try {
      var entity = (EntityImpl) session.newEntity();
      LiveQueryHook.addOp(entity, RecordOperation.CREATED, session);
      assertTrue(
          "pending ops must remain empty when there are no listeners", ops.pendingOps.isEmpty());
    } finally {
      session.rollback();
    }
  }

  /**
   * With {@code QUERY_LIVE_SUPPORT = false}, {@code addOp} must early-return even if listeners
   * exist. Pins the disabled-support gate inside addOp.
   */
  @Test
  public void v1_addOpWithLiveSupportDisabledIsNoOp() {
    // WHEN-FIXED: Track 22 — delete core/query/live/LiveQueryHook
    var ops = LiveQueryHook.getOpsReference(session);
    var endCount = new AtomicInteger();
    ops.getQueueThread()
        .subscribe(
            55,
            new LiveQueryListener() {
              @Override
              public void onLiveResult(RecordOperation iRecord) {
              }

              @Override
              public void onLiveResultEnd() {
                endCount.incrementAndGet();
              }
            });

    var originalFlag = session.getConfiguration().getValue(QUERY_LIVE_SUPPORT);
    try {
      session.getConfiguration().setValue(QUERY_LIVE_SUPPORT, false);

      session.begin();
      try {
        var entity = (EntityImpl) session.newEntity();
        LiveQueryHook.addOp(entity, RecordOperation.UPDATED, session);
        assertTrue(
            "pending ops must remain empty when live support is disabled",
            ops.pendingOps.isEmpty());
      } finally {
        session.rollback();
      }
    } finally {
      session.getConfiguration().setValue(QUERY_LIVE_SUPPORT, originalFlag);
      ops.getQueueThread().unsubscribe(55);
    }
  }

  /**
   * {@code notifyForTxChanges} must early-return when the pending-ops map is empty. Pins the
   * first-branch fast path that avoids touching the dispatcher queue.
   */
  @Test
  public void v1_notifyForTxChangesEmptyPendingOpsIsFastReturn() {
    // WHEN-FIXED: Track 22 — delete core/query/live/LiveQueryHook
    var ops = LiveQueryHook.getOpsReference(session);
    assertTrue("precondition: pending map empty", ops.pendingOps.isEmpty());
    // Must not throw and must not mutate anything.
    LiveQueryHook.notifyForTxChanges(session);
    assertTrue(ops.pendingOps.isEmpty());
  }

  /**
   * {@code notifyForTxChanges} with live support disabled must NOT enqueue pending ops. Pins the
   * disabled-support gate that sits after the empty-map fast path.
   */
  @Test
  public void v1_notifyForTxChangesWithLiveSupportDisabledDoesNotDispatch() {
    // WHEN-FIXED: Track 22 — delete core/query/live/LiveQueryHook
    var ops = LiveQueryHook.getOpsReference(session);

    session.begin();
    try {
      var entity = (EntityImpl) session.newEntity();

      // Populate pending by subscribing + addOp first (with live support enabled),
      // then flip the flag and call notify.
      ops.getQueueThread()
          .subscribe(
              70,
              new LiveQueryListener() {
                @Override
                public void onLiveResult(RecordOperation iRecord) {
                }

                @Override
                public void onLiveResultEnd() {
                }
              });
      try {
        LiveQueryHook.addOp(entity, RecordOperation.CREATED, session);
        assertFalse(
            "precondition: pending ops populated while live support enabled",
            ops.pendingOps.isEmpty());

        var originalFlag = session.getConfiguration().getValue(QUERY_LIVE_SUPPORT);
        try {
          session.getConfiguration().setValue(QUERY_LIVE_SUPPORT, false);
          LiveQueryHook.notifyForTxChanges(session);
        } finally {
          session.getConfiguration().setValue(QUERY_LIVE_SUPPORT, originalFlag);
        }

        assertFalse(
            "pending ops must be left intact when disabled-support short-circuits notify",
            ops.pendingOps.isEmpty());
      } finally {
        ops.getQueueThread().unsubscribe(70);
      }
    } finally {
      session.rollback();
    }
  }

  /**
   * {@code removePendingDatabaseOps} with live support disabled must NOT touch the pending-ops
   * map. Pins the early-return gate.
   */
  @Test
  public void v1_removePendingDatabaseOpsWithLiveSupportDisabledIsNoOp() {
    // WHEN-FIXED: Track 22 — delete core/query/live/LiveQueryHook
    var ops = LiveQueryHook.getOpsReference(session);
    var originalFlag = session.getConfiguration().getValue(QUERY_LIVE_SUPPORT);
    try {
      session.getConfiguration().setValue(QUERY_LIVE_SUPPORT, false);
      // Must not throw.
      LiveQueryHook.removePendingDatabaseOps(session);
    } finally {
      session.getConfiguration().setValue(QUERY_LIVE_SUPPORT, originalFlag);
    }
    assertTrue(
        "pending ops untouched — entry never existed and disabled-support path did not add one",
        ops.pendingOps.isEmpty());
  }

  /**
   * {@code removePendingDatabaseOps} must tolerate a closed database gracefully. Pins the
   * {@code isClosed()} short-circuit.
   *
   * <p>Uses a dedicated secondary session so the fixture's shared {@code session} can continue
   * serving subsequent tests' {@code @After} teardown.
   */
  @Test
  public void v1_removePendingDatabaseOpsOnClosedSessionIsNoOp() {
    // WHEN-FIXED: Track 22 — delete core/query/live/LiveQueryHook
    var secondary = openDatabase();
    secondary.close();
    assertTrue("precondition: secondary session is closed", secondary.isClosed());
    // Must not throw — the isClosed() guard short-circuits before touching ops.
    LiveQueryHook.removePendingDatabaseOps(secondary);
    // Reactivate the fixture's session — opening a second session can switch the thread-local.
    session.activateOnCurrentThread();
  }

  // -------------------------------------------------------------------------
  // LiveQueryHookV2 — same static surface + calculateBefore + calculateProjections
  // -------------------------------------------------------------------------

  /**
   * {@link LiveQueryHookV2#getOpsReference} returns the V2 singleton stored on the session's
   * {@code SharedContext}, distinct from the V1 ops.
   */
  @Test
  public void v2_getOpsReferenceReturnsSharedContextSingleton() {
    // WHEN-FIXED: Track 22 — delete core/query/live/LiveQueryHookV2
    var ops1 = LiveQueryHookV2.getOpsReference(session);
    var ops2 = LiveQueryHookV2.getOpsReference(session);
    assertSame("V2 ops must be a SharedContext singleton", ops1, ops2);
  }

  /** V2 subscribe must also honor {@code QUERY_LIVE_SUPPORT = false} and return -1. */
  @Test
  public void v2_subscribeWithLiveSupportDisabledReturnsMinusOne() {
    // WHEN-FIXED: Track 22 — delete core/query/live/LiveQueryHookV2
    var ops = LiveQueryHookV2.getOpsReference(session);
    var originalFlag = session.getConfiguration().getValue(QUERY_LIVE_SUPPORT);
    try {
      session.getConfiguration().setValue(QUERY_LIVE_SUPPORT, false);
      var token =
          LiveQueryHookV2.subscribe(
              99,
              new LiveQueryListenerV2() {
                @Override
                public void onLiveResults(List<LiveQueryOp> iRecords) {
                }

                @Override
                public void onLiveResultEnd() {
                }

                @Override
                public int getToken() {
                  return 99;
                }
              },
              session);
      assertEquals(Integer.valueOf(-1), token);
      assertFalse(ops.hasListeners());
    } finally {
      session.getConfiguration().setValue(QUERY_LIVE_SUPPORT, originalFlag);
    }
  }

  /** V2 unsubscribe with live support disabled must early-return without touching listeners. */
  @Test
  public void v2_unsubscribeWithLiveSupportDisabledIsNoOp() {
    // WHEN-FIXED: Track 22 — delete core/query/live/LiveQueryHookV2
    var ops = LiveQueryHookV2.getOpsReference(session);
    var endCount = new AtomicInteger();
    ops.subscribe(
        123,
        new LiveQueryListenerV2() {
          @Override
          public void onLiveResults(List<LiveQueryOp> iRecords) {
          }

          @Override
          public void onLiveResultEnd() {
            endCount.incrementAndGet();
          }

          @Override
          public int getToken() {
            return 123;
          }
        });
    var originalFlag = session.getConfiguration().getValue(QUERY_LIVE_SUPPORT);
    try {
      session.getConfiguration().setValue(QUERY_LIVE_SUPPORT, false);
      LiveQueryHookV2.unsubscribe(123, session);
      assertTrue("listener must survive disabled-support unsubscribe", ops.hasListeners());
      assertEquals("onLiveResultEnd must not fire", 0, endCount.get());
    } finally {
      session.getConfiguration().setValue(QUERY_LIVE_SUPPORT, originalFlag);
      ops.unsubscribe(123);
    }
  }

  /** V2 addOp with zero listeners must early-return without populating pending-ops. */
  @Test
  public void v2_addOpWithNoListenersIsNoOp() {
    // WHEN-FIXED: Track 22 — delete core/query/live/LiveQueryHookV2
    var ops = LiveQueryHookV2.getOpsReference(session);
    assertFalse("precondition: no listeners", ops.hasListeners());

    session.begin();
    try {
      var entity = (EntityImpl) session.newEntity();
      LiveQueryHookV2.addOp(session, entity, RecordOperation.CREATED);
      assertTrue("pending ops must remain empty", ops.pendingOps.isEmpty());
    } finally {
      session.rollback();
    }
  }

  /** V2 addOp with live support disabled must also early-return. */
  @Test
  public void v2_addOpWithLiveSupportDisabledIsNoOp() {
    // WHEN-FIXED: Track 22 — delete core/query/live/LiveQueryHookV2
    var ops = LiveQueryHookV2.getOpsReference(session);
    ops.subscribe(
        321,
        new LiveQueryListenerV2() {
          @Override
          public void onLiveResults(List<LiveQueryOp> iRecords) {
          }

          @Override
          public void onLiveResultEnd() {
          }

          @Override
          public int getToken() {
            return 321;
          }
        });
    try {
      var originalFlag = session.getConfiguration().getValue(QUERY_LIVE_SUPPORT);
      try {
        session.getConfiguration().setValue(QUERY_LIVE_SUPPORT, false);
        session.begin();
        try {
          var entity = (EntityImpl) session.newEntity();
          LiveQueryHookV2.addOp(session, entity, RecordOperation.UPDATED);
          assertTrue(
              "pending ops must remain empty when live support is disabled",
              ops.pendingOps.isEmpty());
        } finally {
          session.rollback();
        }
      } finally {
        session.getConfiguration().setValue(QUERY_LIVE_SUPPORT, originalFlag);
      }
    } finally {
      ops.unsubscribe(321);
    }
  }

  /** V2 notifyForTxChanges empty-pending fast path. */
  @Test
  public void v2_notifyForTxChangesEmptyPendingOpsIsFastReturn() {
    // WHEN-FIXED: Track 22 — delete core/query/live/LiveQueryHookV2
    var ops = LiveQueryHookV2.getOpsReference(session);
    assertTrue(ops.pendingOps.isEmpty());
    LiveQueryHookV2.notifyForTxChanges(session);
    assertTrue(ops.pendingOps.isEmpty());
  }

  /** V2 removePendingDatabaseOps with disabled support must not touch state. */
  @Test
  public void v2_removePendingDatabaseOpsWithLiveSupportDisabledIsNoOp() {
    // WHEN-FIXED: Track 22 — delete core/query/live/LiveQueryHookV2
    var ops = LiveQueryHookV2.getOpsReference(session);
    var originalFlag = session.getConfiguration().getValue(QUERY_LIVE_SUPPORT);
    try {
      session.getConfiguration().setValue(QUERY_LIVE_SUPPORT, false);
      LiveQueryHookV2.removePendingDatabaseOps(session);
    } finally {
      session.getConfiguration().setValue(QUERY_LIVE_SUPPORT, originalFlag);
    }
    assertTrue(ops.pendingOps.isEmpty());
  }

  /**
   * {@code calculateBefore} must populate the resulting {@link com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal}
   * with every property on the entity plus the standard metadata ({@code @rid}, {@code @class},
   * {@code @version}) — pinning the full branch that is reached when {@code projectionsToLoad}
   * is null (i.e. "load all").
   *
   * <p>Commits the entity first so the property map is materialised through the full save path;
   * this is what production calls look like (the hook fires from
   * {@code CopyRecordContentBeforeUpdateStep} against an already-persisted record).
   */
  @Test
  public void v2_calculateBeforeWithNullProjectionsLoadsAllProperties() {
    // WHEN-FIXED: Track 22 — delete core/query/live/LiveQueryHookV2
    var cls = createClassInstance();
    session.begin();
    var entity = (EntityImpl) session.newEntity(cls.getName());
    entity.setProperty("name", "n1");
    entity.setProperty("count", 7);
    session.commit();

    session.begin();
    try (var rs = session.query("SELECT FROM " + cls.getName())) {
      var reloaded = (EntityImpl) rs.next().asEntity();

      var result = LiveQueryHookV2.calculateBefore(session, reloaded, null);

      assertNotNull(result);
      assertEquals("n1", result.getProperty("name"));
      assertEquals(Integer.valueOf(7), result.getProperty("count"));
      assertNotNull(
          "calculateBefore must populate the @rid metadata property",
          result.getProperty("@rid"));
      assertEquals(cls.getName(), result.getProperty("@class"));
    } finally {
      session.rollback();
    }
  }

  /**
   * With an explicit set of projections, {@code calculateBefore} must ONLY load the listed
   * properties (plus standard metadata) — proving the filtered branch of the per-property loop.
   */
  @Test
  public void v2_calculateBeforeWithProjectionsOnlyLoadsListedProperties() {
    // WHEN-FIXED: Track 22 — delete core/query/live/LiveQueryHookV2
    var cls = createClassInstance();
    session.begin();
    var entity = (EntityImpl) session.newEntity(cls.getName());
    entity.setProperty("keep", "visible");
    entity.setProperty("drop", "hidden");
    session.commit();

    session.begin();
    try (var rs = session.query("SELECT FROM " + cls.getName())) {
      var reloaded = (EntityImpl) rs.next().asEntity();

      var result = LiveQueryHookV2.calculateBefore(session, reloaded, java.util.Set.of("keep"));

      assertEquals("visible", result.getProperty("keep"));
      assertNull(
          "property absent from the projections set must not be loaded",
          result.getProperty("drop"));
      assertEquals(cls.getName(), result.getProperty("@class"));
    } finally {
      session.rollback();
    }
  }

  /**
   * Pre-existing bug pin: {@code calculateProjections} never populates the returned {@link
   * java.util.Set} — the method always produces either an empty {@code HashSet} (when
   * {@code ops.subscribers} is non-null) or {@code null}. Track 22 should restore the
   * per-subscriber union that the method's Javadoc describes.
   *
   * <p>Reachable by exercising {@code addOp} with a listener attached — that path routes through
   * {@code calculateProjections(ops)} internally, and the resulting empty Set produces a
   * {@code ResultInternal} carrying every entity property (the "projections==null treated as load
   * all" branch in {@code calculateBefore}/{@code calculateAfter}). Observe the symptom indirectly
   * via the populated pending-ops entry.
   */
  @Test
  public void v2_calculateProjectionsAlwaysEmptyOrNull_knownBug() {
    // WHEN-FIXED: Track 22 — calculateProjections populates the projection set per subscriber
    var ops = LiveQueryHookV2.getOpsReference(session);
    var captured = new ArrayList<List<LiveQueryOp>>();
    ops.subscribe(
        888,
        new LiveQueryListenerV2() {
          @Override
          public void onLiveResults(List<LiveQueryOp> iRecords) {
            synchronized (captured) {
              captured.addAll(List.of(iRecords));
            }
          }

          @Override
          public void onLiveResultEnd() {
          }

          @Override
          public int getToken() {
            return 888;
          }
        });

    try {
      var cls = createClassInstance();
      session.begin();
      var entity = (EntityImpl) session.newEntity(cls.getName());
      entity.setProperty("only", "value");
      session.commit();

      session.begin();
      try (var rs = session.query("SELECT FROM " + cls.getName())) {
        var reloaded = (EntityImpl) rs.next().asEntity();

        LiveQueryHookV2.addOp(session, reloaded, RecordOperation.CREATED);

        var pending = ops.pendingOps.get(session);
        assertNotNull("addOp must populate pending-ops for the active session", pending);
        assertEquals(1, pending.size());
        var queued = pending.get(0);
        // The bug observable: calculateProjections always returns an empty HashSet (never null)
        // for a live subscribers map, which then filters out EVERY property in calculateAfter's
        // `projectionsToLoad == null || projectionsToLoad.contains(prop)` check. The "after"
        // Result exists but carries no user properties — only the standard @rid/@class/@version
        // metadata that calculateAfter sets unconditionally after the property loop.
        assertNotNull("CREATED op must populate the after Result", queued.after);
        assertNull(
            "bug: calculateProjections returns an empty set that filters out every property",
            queued.after.getProperty("only"));
        assertEquals(
            "standard metadata is still populated outside the buggy property loop",
            cls.getName(),
            queued.after.getProperty("@class"));
      } finally {
        session.rollback();
      }
    } finally {
      ops.unsubscribe(888);
    }
  }
}
