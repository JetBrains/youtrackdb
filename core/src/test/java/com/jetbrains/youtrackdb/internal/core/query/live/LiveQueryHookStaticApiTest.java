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
 * Tests use direct-ops subscription (bypassing the static {@code subscribe} entry point) wherever
 * possible to avoid starting the dispatcher thread; the dedicated happy-path tests that DO go
 * through the static entry point start the dispatcher and stop it explicitly in {@code finally}.
 * The class-level {@link Timeout} rule (15 s) is a safety backstop for tests that touch threading.
 */
public class LiveQueryHookStaticApiTest extends TestUtilsFixture {

  @Rule
  public Timeout globalTimeout = Timeout.seconds(15);

  // ---------------------------------------------------------------------------
  // Helpers — extracted to reduce ~10x duplication of the QUERY_LIVE_SUPPORT
  // save/restore pattern and the anonymous-listener boilerplate. Keep these
  // close to the tests (package-private visibility not needed — tests live in
  // the same class) so a future reader can see what's being abstracted.
  // ---------------------------------------------------------------------------

  /**
   * Runs {@code body} with {@code QUERY_LIVE_SUPPORT} set to {@code flag}, always restoring the
   * original value in a {@code finally}. The caller passes a {@link Runnable} (which may throw
   * unchecked exceptions from inside it); checked exceptions must be wrapped at the call site.
   */
  private void withLiveSupport(boolean flag, Runnable body) {
    var original = session.getConfiguration().getValue(QUERY_LIVE_SUPPORT);
    try {
      session.getConfiguration().setValue(QUERY_LIVE_SUPPORT, flag);
      body.run();
    } finally {
      session.getConfiguration().setValue(QUERY_LIVE_SUPPORT, original);
    }
  }

  /**
   * Builds a {@link LiveQueryListener} whose callbacks are pure no-ops — suitable for the tests
   * that only care about subscription-state observables, not dispatched results.
   */
  private static LiveQueryListener noopListenerV1() {
    return new LiveQueryListener() {
      @Override
      public void onLiveResult(RecordOperation iRecord) {
      }

      @Override
      public void onLiveResultEnd() {
      }
    };
  }

  /**
   * V1 listener that increments {@code endCount} on {@code onLiveResultEnd} — for tests that
   * need to observe whether the end callback fired.
   */
  private static LiveQueryListener countingListenerV1(AtomicInteger endCount) {
    return new LiveQueryListener() {
      @Override
      public void onLiveResult(RecordOperation iRecord) {
      }

      @Override
      public void onLiveResultEnd() {
        endCount.incrementAndGet();
      }
    };
  }

  /**
   * Builds a {@link LiveQueryListenerV2} with the given token and pure no-op callbacks.
   */
  private static LiveQueryListenerV2 noopListenerV2(int token) {
    return new LiveQueryListenerV2() {
      @Override
      public void onLiveResults(List<LiveQueryOp> iRecords) {
      }

      @Override
      public void onLiveResultEnd() {
      }

      @Override
      public int getToken() {
        return token;
      }
    };
  }

  /**
   * V2 listener with a counted {@code onLiveResultEnd} — for disabled-support tests that pin
   * "end callback must not fire".
   */
  private static LiveQueryListenerV2 countingListenerV2(int token, AtomicInteger endCount) {
    return new LiveQueryListenerV2() {
      @Override
      public void onLiveResults(List<LiveQueryOp> iRecords) {
      }

      @Override
      public void onLiveResultEnd() {
        endCount.incrementAndGet();
      }

      @Override
      public int getToken() {
        return token;
      }
    };
  }

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
    withLiveSupport(false, () -> {
      var token = LiveQueryHook.subscribe(1, noopListenerV1(), session);
      assertEquals("disabled support must return -1", Integer.valueOf(-1), token);
      assertFalse(
          "listener must not be registered when live support is disabled",
          ops.getQueueThread().hasListeners());
    });
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
    ops.getQueueThread().subscribe(42, countingListenerV1(endCount));

    try {
      withLiveSupport(false, () -> {
        LiveQueryHook.unsubscribe(42, session);
        assertTrue(
            "disabled-support unsubscribe must not remove the listener",
            ops.getQueueThread().hasToken(42));
        assertEquals("onLiveResultEnd must not fire", 0, endCount.get());
      });
    } finally {
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
    ops.getQueueThread().subscribe(55, countingListenerV1(endCount));

    try {
      withLiveSupport(false, () -> {
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
      });
    } finally {
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
      ops.getQueueThread().subscribe(70, noopListenerV1());
      try {
        LiveQueryHook.addOp(entity, RecordOperation.CREATED, session);
        assertFalse(
            "precondition: pending ops populated while live support enabled",
            ops.pendingOps.isEmpty());

        withLiveSupport(false, () -> LiveQueryHook.notifyForTxChanges(session));

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
   *
   * <p>Populates pending-ops first (subscribe + {@code addOp} with live support enabled) so the
   * post-disable assertion is falsifiable — a regression removing the {@code QUERY_LIVE_SUPPORT}
   * guard inside {@code removePendingDatabaseOps} would reach {@code ops.pendingOps.remove(session)}
   * and evict the entry, which this test's size-preservation check would catch.
   */
  @Test
  public void v1_removePendingDatabaseOpsWithLiveSupportDisabledIsNoOp() {
    // WHEN-FIXED: Track 22 — delete core/query/live/LiveQueryHook
    var ops = LiveQueryHook.getOpsReference(session);

    session.begin();
    try {
      var entity = (EntityImpl) session.newEntity();
      ops.getQueueThread().subscribe(71, noopListenerV1());
      try {
        LiveQueryHook.addOp(entity, RecordOperation.CREATED, session);
        assertFalse(
            "precondition: pending ops populated while live support enabled",
            ops.pendingOps.isEmpty());
        var sizeBefore = ops.pendingOps.size();

        withLiveSupport(false, () -> LiveQueryHook.removePendingDatabaseOps(session));

        assertEquals(
            "disabled-support guard must leave pendingOps size unchanged",
            sizeBefore,
            ops.pendingOps.size());
        assertNotNull(
            "disabled-support guard must preserve the session's pending-ops entry",
            ops.pendingOps.get(session));
      } finally {
        ops.getQueueThread().unsubscribe(71);
      }
    } finally {
      session.rollback();
    }
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
    var ops = LiveQueryHook.getOpsReference(session);
    var pendingSizeBefore = ops.pendingOps.size();

    var secondary = openDatabase();
    try {
      secondary.close();
      assertTrue("precondition: secondary session is closed", secondary.isClosed());
      // Must not throw AND must not mutate pendingOps — the isClosed() guard short-circuits
      // before reaching `ops.pendingOps.remove(database)`. If the guard is removed, the call
      // would (at minimum) change pendingOps.size() — the post-state assertion pins that.
      LiveQueryHook.removePendingDatabaseOps(secondary);
    } finally {
      // Opening a second session can switch the thread-local; restore fixture session regardless
      // of whether any preceding call threw so the @After safety net runs cleanly. Belt-and-
      // braces close() here in case the earlier close() threw — double-close is idempotent.
      if (!secondary.isClosed()) {
        secondary.close();
      }
      session.activateOnCurrentThread();
    }

    assertEquals(
        "closed-session short-circuit must not mutate pendingOps",
        pendingSizeBefore,
        ops.pendingOps.size());
  }

  /**
   * V2 sibling of {@link #v1_removePendingDatabaseOpsOnClosedSessionIsNoOp}: the V2 closed-session
   * short-circuit must not mutate the V2 ops' pending-ops map.
   */
  @Test
  public void v2_removePendingDatabaseOpsOnClosedSessionIsNoOp() {
    // WHEN-FIXED: Track 22 — delete core/query/live/LiveQueryHookV2
    var ops = LiveQueryHookV2.getOpsReference(session);
    var pendingSizeBefore = ops.pendingOps.size();

    var secondary = openDatabase();
    try {
      secondary.close();
      assertTrue("precondition: secondary session is closed", secondary.isClosed());
      LiveQueryHookV2.removePendingDatabaseOps(secondary);
    } finally {
      if (!secondary.isClosed()) {
        secondary.close();
      }
      session.activateOnCurrentThread();
    }

    assertEquals(
        "V2 closed-session short-circuit must not mutate pendingOps",
        pendingSizeBefore,
        ops.pendingOps.size());
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
    withLiveSupport(false, () -> {
      var token = LiveQueryHookV2.subscribe(99, noopListenerV2(99), session);
      assertEquals(Integer.valueOf(-1), token);
      assertFalse(ops.hasListeners());
    });
  }

  /** V2 unsubscribe with live support disabled must early-return without touching listeners. */
  @Test
  public void v2_unsubscribeWithLiveSupportDisabledIsNoOp() {
    // WHEN-FIXED: Track 22 — delete core/query/live/LiveQueryHookV2
    var ops = LiveQueryHookV2.getOpsReference(session);
    var endCount = new AtomicInteger();
    ops.subscribe(123, countingListenerV2(123, endCount));
    try {
      withLiveSupport(false, () -> {
        LiveQueryHookV2.unsubscribe(123, session);
        assertTrue("listener must survive disabled-support unsubscribe", ops.hasListeners());
        assertEquals("onLiveResultEnd must not fire", 0, endCount.get());
      });
    } finally {
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
    ops.subscribe(321, noopListenerV2(321));
    try {
      withLiveSupport(false, () -> {
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
      });
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

  /**
   * V2 removePendingDatabaseOps with disabled support must not touch state.
   *
   * <p>Populates pending-ops first so the assertion is falsifiable — a regression removing the
   * V2 {@code QUERY_LIVE_SUPPORT} guard would evict the entry on the disabled call.
   */
  @Test
  public void v2_removePendingDatabaseOpsWithLiveSupportDisabledIsNoOp() {
    // WHEN-FIXED: Track 22 — delete core/query/live/LiveQueryHookV2
    var ops = LiveQueryHookV2.getOpsReference(session);
    ops.subscribe(781, noopListenerV2(781));
    try {
      var cls = createClassInstance();
      session.begin();
      try {
        var entity = (EntityImpl) session.newEntity(cls.getName());
        entity.setProperty("v", 1);
        LiveQueryHookV2.addOp(session, entity, RecordOperation.CREATED);
        assertFalse(
            "precondition: addOp must populate pending ops while live support enabled",
            ops.pendingOps.isEmpty());
        var sizeBefore = ops.pendingOps.size();

        withLiveSupport(false, () -> LiveQueryHookV2.removePendingDatabaseOps(session));

        assertEquals(
            "V2 disabled-support guard must leave pendingOps size unchanged",
            sizeBefore,
            ops.pendingOps.size());
        assertNotNull(
            "V2 disabled-support guard must preserve the session's pending-ops entry",
            ops.pendingOps.get(session));
      } finally {
        session.rollback();
      }
    } finally {
      ops.unsubscribe(781);
    }
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
    // The listener body is irrelevant to this test — no dispatcher is started here, so
    // onLiveResults will never fire. The subscription exists only to flip ops.hasListeners() so
    // addOp reaches the calculateProjections(ops) call path.
    ops.subscribe(888, noopListenerV2(888));

    try {
      var cls = createClassInstance();
      session.begin();
      var entity = (EntityImpl) session.newEntity(cls.getName());
      entity.setProperty("only", "value");
      session.commit();
      var committedVersion = entity.getVersion();

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
        // The @rid/@class/@version metadata branch is populated regardless of the bug. Pinning
        // all three so a future regression that breaks that branch is distinguished from a fix
        // that restores the property loop.
        assertNotNull(
            "metadata @rid must be populated outside the buggy property loop",
            queued.after.getProperty("@rid"));
        assertEquals(
            "metadata @class must be populated outside the buggy property loop",
            cls.getName(),
            queued.after.getProperty("@class"));
        assertEquals(
            "metadata @version must be the committed entity's version + 1 (calculateAfter pin)",
            Long.valueOf(committedVersion + 1),
            queued.after.getProperty("@version"));
      } finally {
        session.rollback();
      }
    } finally {
      ops.unsubscribe(888);
    }
  }

  /**
   * Happy path for {@link LiveQueryHookV2#subscribe}: with live support enabled the static entry
   * point must register the listener on the session's shared ops AND reach the internal
   * {@code synchronized(ops.threadLock)} auto-start block that clones + starts the dispatcher.
   *
   * <p>Listener-registration alone is not a falsifiable pin for the auto-start branch — a
   * mutation that removed the {@code queueThread.start()} call would still register the listener
   * via the trailing {@code ops.subscribe(...)} call. The falsifiable observable is that a
   * post-subscribe {@code ops.enqueue(...)} must reach the listener within a bounded window:
   * without a running dispatcher the latch would never fire.
   */
  @Test
  public void v2_subscribeThroughStaticEntryPointRegistersListenerAndReachesAutoStart()
      throws Exception {
    // WHEN-FIXED: Track 22 — delete core/query/live/LiveQueryHookV2
    var ops = LiveQueryHookV2.getOpsReference(session);
    var endCount = new AtomicInteger();
    var delivered = new java.util.concurrent.CountDownLatch(1);
    var listener =
        new LiveQueryListenerV2() {
          @Override
          public void onLiveResults(List<LiveQueryOp> iRecords) {
            delivered.countDown();
          }

          @Override
          public void onLiveResultEnd() {
            endCount.incrementAndGet();
          }

          @Override
          public int getToken() {
            return 4242;
          }
        };

    var token = LiveQueryHookV2.subscribe(4242, listener, session);
    try {
      assertEquals("subscribe returns the caller token", Integer.valueOf(4242), token);
      assertTrue("listener must be registered on the shared ops", ops.hasListeners());
      assertSame(
          "getSubscribers exposes the same listener instance",
          listener,
          ops.getSubscribers().get(4242));

      // Auto-start pin: enqueue after subscribe and await dispatch. If the static entry point
      // did not auto-start the dispatcher (e.g. the synchronized(threadLock) block were removed),
      // the queue would accumulate ops but never be drained — the latch would never fire.
      ops.enqueue(new LiveQueryOp(null, null, null, RecordOperation.CREATED));
      assertTrue(
          "static subscribe must auto-start the dispatcher (enqueue reaches listener)",
          delivered.await(5, java.util.concurrent.TimeUnit.SECONDS));
    } finally {
      LiveQueryHookV2.unsubscribe(4242, session);
      // ops.close() joins the dispatcher without a bound; the class-level @Rule Timeout(15s)
      // is the only backstop if the join hangs. Track 22's close() rewrite should add a bounded
      // join — until then, the rule is adequate.
      ops.close();
    }
    assertFalse(
        "listener removed by unsubscribe (ops.close itself clears only pendingOps)",
        ops.hasListeners());
    assertEquals(
        "unsubscribe must call onLiveResultEnd exactly once", 1, endCount.get());
  }

  /**
   * Happy path for {@link LiveQueryHookV2#notifyForTxChanges}: with live support enabled and
   * pending ops populated, the call must drain pending-ops into the queue and clear the map.
   * Pins the body branch that is not reached by the empty-pending or disabled-support short-cuts.
   */
  @Test
  public void v2_notifyForTxChangesDrainsPendingIntoQueueAndClearsMap() {
    // WHEN-FIXED: Track 22 — delete core/query/live/LiveQueryHookV2
    var ops = LiveQueryHookV2.getOpsReference(session);
    ops.subscribe(777, noopListenerV2(777));
    try {
      var cls = createClassInstance();
      session.begin();
      try {
        var entity = (EntityImpl) session.newEntity(cls.getName());
        entity.setProperty("v", 1);
        LiveQueryHookV2.addOp(session, entity, RecordOperation.CREATED);
        assertFalse(
            "precondition: addOp must populate pending ops", ops.pendingOps.isEmpty());
        var queueSizeBefore = ops.getQueue().size();

        LiveQueryHookV2.notifyForTxChanges(session);

        assertNull(
            "pending ops for this session must be cleared after notify",
            ops.pendingOps.get(session));
        assertEquals(
            "notifyForTxChanges must drain exactly one op into the queue",
            queueSizeBefore + 1,
            ops.getQueue().size());
      } finally {
        session.rollback();
      }
    } finally {
      ops.unsubscribe(777);
    }
  }

  /**
   * V2 {@code addOp} with an UPDATED op against the same entity instance must merge with the
   * previously queued UPDATED op — traversing the pending list and overwriting {@code prev.after}
   * rather than appending a second entry. Pins the dedup branch at
   * {@code LiveQueryHookV2.java:226-232} that is not reached by any CREATED / DELETED test.
   */
  @Test
  public void v2_addOpUpdatedTwiceOnSameEntityMergesInPlace() {
    // WHEN-FIXED: Track 22 — delete core/query/live/LiveQueryHookV2
    var ops = LiveQueryHookV2.getOpsReference(session);
    ops.subscribe(999, noopListenerV2(999));
    try {
      var cls = createClassInstance();
      session.begin();
      var entity = (EntityImpl) session.newEntity(cls.getName());
      entity.setProperty("v", 1);
      session.commit();

      session.begin();
      try (var rs = session.query("SELECT FROM " + cls.getName())) {
        var reloaded = (EntityImpl) rs.next().asEntity();

        LiveQueryHookV2.addOp(session, reloaded, RecordOperation.UPDATED);
        LiveQueryHookV2.addOp(session, reloaded, RecordOperation.UPDATED);

        var pending = ops.pendingOps.get(session);
        assertNotNull(pending);
        assertEquals(
            "second UPDATED on the same entity instance must merge with the previous op, not"
                + " append a second entry",
            1,
            pending.size());
      } finally {
        session.rollback();
      }
    } finally {
      ops.unsubscribe(999);
    }
  }
}
