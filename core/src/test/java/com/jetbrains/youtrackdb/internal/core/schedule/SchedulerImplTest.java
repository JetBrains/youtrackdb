/*
 *
 *
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jetbrains.youtrackdb.internal.core.schedule;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.api.exception.RecordNotFoundException;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.exception.DatabaseException;
import com.jetbrains.youtrackdb.internal.core.metadata.function.Function;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransactionImpl;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * DB-backed direct-method tests for {@link SchedulerImpl}: lifecycle (registration,
 * removal, close), the six transaction-lifecycle hooks wired from
 * {@link com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded}, the
 * "no leaked timers after close" invariant, and a concurrent register/remove race.
 *
 * <p>The high-level scenario suite in {@link SchedulerTest} drives end-to-end firings
 * with second-level cron rules, and {@link ScheduledEventTest} covers the
 * {@link ScheduledEvent} surface in isolation. This class instead exercises
 * {@link SchedulerImpl} directly so each public method's branches (including the
 * branches not reachable through normal CRUD flow, e.g. dual-call to
 * {@code initScheduleRecord} with the same identity, or the early-return path of
 * {@code preHandleUpdateScheduleInTx} when no event is registered) are pinned with
 * falsifiable assertions on observable state — the registry contents, the
 * per-event {@code timer} field, and the transaction's custom-data map.
 *
 * <p>This class is tagged {@link SequentialTest} because every test that registers
 * an event places a {@link ScheduledFuture} on the JVM-wide
 * {@code YouTrackDBEnginesManager.scheduledPool} (a 2-thread executor shared with
 * direct-memory eviction); running these in parallel with other DB-backed tests
 * under {@code <parallel>classes</parallel>} risks cross-test pollution if a
 * timer leaks. Far-future cron rules ({@code "0 0 12 1 1 ? 2099"}) keep the
 * queued tasks from firing during the test JVM's lifetime, and the {@code @After}
 * cleanup walks the registry and calls {@link SchedulerImpl#removeEvent} for
 * every still-registered event so the JVM-wide pool's delay queue is drained
 * back to its pre-test shape (modulo cancelled-future objects, which are bounded
 * — see the comment in {@link ScheduledEventTest}).
 *
 * <p><b>Hook wiring reference (see {@code DatabaseSessionEmbedded}):</b>
 * <ul>
 *   <li>{@code initScheduleRecord} — fired from {@code afterCreateOperations}
 *     when a new {@code OSchedule} entity is created.</li>
 *   <li>{@code preHandleUpdateScheduleInTx} — fired from
 *     {@code beforeUpdateOperations} before commit when an existing
 *     {@code OSchedule} entity's properties are mutated.</li>
 *   <li>{@code postHandleUpdateScheduleAfterTxCommit} — fired from
 *     {@code afterCommitOperations} for the {@code RecordOperation.UPDATED}
 *     branch.</li>
 *   <li>{@code SchedulerImpl.onAfterEventDropped} (static) — fired from
 *     {@code afterDeleteOperations} when an {@code OSchedule} entity is
 *     deleted; it stamps the dropped event's RID + name into the transaction's
 *     custom data so {@code onEventDropped} can find the name later.</li>
 *   <li>{@code onEventDropped} — fired from {@code afterCommitOperations} for
 *     the {@code RecordOperation.DELETED} branch; reads the RID-name map and
 *     unregisters the event from {@link SchedulerImpl}.</li>
 *   <li>{@code scheduleEvent} (after-create) — fired from
 *     {@code afterCommitOperations} for the {@code RecordOperation.CREATED}
 *     branch; constructs a fresh {@link ScheduledEvent} over the persistent
 *     entity and registers it under {@link SchedulerImpl} via
 *     {@code scheduleEvent}.</li>
 * </ul>
 *
 * <p><b>Out-of-scope coverage</b> (residual gaps documented for the verification
 * step's per-file acceptance):
 * <ul>
 *   <li>The two {@code catch (Exception ex)} log-and-swallow branches at the tail
 *     of {@code preHandleUpdateScheduleInTx} and
 *     {@code postHandleUpdateScheduleAfterTxCommit} are not pinned: reaching them
 *     requires injecting a persistence-layer failure inside the hook body, which
 *     is best done at integration scope rather than from a unit test.</li>
 *   <li>The interrupt-during-run race between {@code ScheduledEvent.interrupt()}
 *     and {@code ScheduledTimerTask.run()}'s finally block lives inside a private
 *     inner class. Driving it deterministically requires either reflection on the
 *     inner-class state or a near-immediate cron rule whose firing window risks
 *     surefire timing flakes; both are deferred — the end-to-end suite in
 *     {@link SchedulerTest} exercises this path indirectly via second-level cron
 *     firings, and the structural fix for the underlying retry-loop {@code break}
 *     placement is queued as a single coupled cleanup with the deletion of the
 *     dead retry-counter flow.</li>
 *   <li>Inspecting the JVM-wide {@code YouTrackDBEnginesManager.scheduledPool}
 *     delay queue directly is replaced by per-{@link ScheduledFuture}
 *     {@code isCancelled()} checks (see {@code closeCancelsAllRegisteredTimers...}).
 *     A regression that drops {@code event.interrupt()} in favor of bare
 *     {@code events.clear()} would leave the saved {@code ScheduledFuture}
 *     reference uncancelled, flipping the assertion red — without coupling the
 *     test to {@code YouTrackDBEnginesManager}'s package-private internals.</li>
 * </ul>
 */
@Category(SequentialTest.class)
public class SchedulerImplTest extends DbTestBase {

  /**
   * Far-future cron rule mirror — see {@link SchedulerTestFixtures#FAR_FUTURE_RULE} for
   * the canonical definition. Re-bound here because this class references the rule in
   * dozens of {@code buildEvent} call sites.
   */
  private static final String FAR_FUTURE_RULE = SchedulerTestFixtures.FAR_FUTURE_RULE;

  @After
  public void cleanRegisteredEvents() {
    SchedulerTestFixtures.removeAllRegisteredEvents(session);
  }

  // ---------------------------------------------------------------------------
  // Lifecycle — direct method calls on SchedulerImpl
  // ---------------------------------------------------------------------------

  @Test
  public void scheduleEventRegistersEventByNameInRegistryAndQueuesTimerOnPool() throws Exception {
    // Build via the normal path so the entity is persistent (scheduleEvent's call to
    // event.schedule(...) requires isPersistent()), but immediately drop the auto-registered
    // instance from the registry without deleting the entity. Then call scheduleEvent directly
    // and pin the post-call observable: registry contains the event by name, the registered
    // ScheduledEvent's timer field is non-null (a ScheduledFuture queued on the pool), and
    // getEvent returns the same instance we passed in.
    var function = SchedulerTestFixtures.createTrivialFunction(session, "fnDirectSchedule");
    var event = buildEvent("evt-direct-schedule", FAR_FUTURE_RULE, function, Map.of());
    var impl = session.getSharedContext().getScheduler();

    // Drop the auto-registered instance (and cancel its timer) so our direct scheduleEvent
    // call exercises the putIfAbsent success path rather than the no-op branch.
    var dropped = impl.removeEventInternal("evt-direct-schedule");
    assertNotNull("auto-schedule hook must have registered the event", dropped);
    assertNull("registry must be empty after removeEventInternal",
        impl.getEvent("evt-direct-schedule"));

    impl.scheduleEvent(session, event);

    var registered = impl.getEvent("evt-direct-schedule");
    assertSame("scheduleEvent must register exactly the instance we passed in",
        event, registered);
    assertTrue("registry must list the new event by name",
        impl.getEvents().containsKey("evt-direct-schedule"));
    assertNotNull("scheduleEvent must invoke event.schedule(), populating its timer field",
        readTimerField(event));
  }

  @Test
  public void scheduleEventIsNoOpWhenSameNameIsAlreadyRegisteredAndDoesNotInterruptPriorTimer()
      throws Exception {
    // putIfAbsent semantics: a second scheduleEvent call with the same event name must NOT
    // replace the registered instance, must NOT call event.schedule() on the second event
    // (the second event's timer must stay null), and must NOT cancel the first event's timer.
    var function = SchedulerTestFixtures.createTrivialFunction(session, "fnDuplicate");
    var first = buildEvent("evt-dup", FAR_FUTURE_RULE, function, Map.of());
    var impl = session.getSharedContext().getScheduler();

    var firstRegistered = impl.getEvent("evt-dup");
    assertNotNull("first event must be auto-registered after build",
        firstRegistered);
    var firstTimerBefore = readTimerField(firstRegistered);
    assertNotNull("first event's timer must be queued by the auto-schedule hook",
        firstTimerBefore);

    // Construct an independent ScheduledEvent over the SAME entity. The ctor reads the
    // entity's properties (name, rule, function ref, status, args, exec_id) and resolves
    // the Function via the session, so it must run inside an active transaction. Once
    // constructed, the ScheduledEvent's fields are self-contained and can be used outside
    // the tx — same pattern as the malformed-cron and unsaved-event pins in
    // ScheduledEventTest.
    session.begin();
    ScheduledEvent second;
    try {
      var entity = (EntityImpl) session.loadEntity(first.getIdentity());
      second = new ScheduledEvent(entity, session);
    } finally {
      session.rollback();
    }

    impl.scheduleEvent(session, second);

    assertSame("registry must continue to point at the first registration",
        firstRegistered, impl.getEvent("evt-dup"));
    assertNull("the second instance's timer must remain null — its schedule() was not called",
        readTimerField(second));
    assertSame("the first instance's timer must not be cancelled or replaced",
        firstTimerBefore, readTimerField(firstRegistered));
  }

  @Test
  public void removeEventInternalReturnsRegisteredInstanceAndNullsItsTimerField() throws Exception {
    // removeEventInternal removes from the registry AND invokes event.interrupt(), which
    // cancels the timer and nulls the timer field. Pin both: returned instance equals the
    // registered one, and after the call the returned instance's timer field is null.
    var function = SchedulerTestFixtures.createTrivialFunction(session, "fnRemoveInternal");
    var event = buildEvent("evt-remove-internal", FAR_FUTURE_RULE, function, Map.of());
    var impl = session.getSharedContext().getScheduler();

    var registered = impl.getEvent("evt-remove-internal");
    assertNotNull(registered);
    assertNotNull("timer must be queued before removeEventInternal",
        readTimerField(registered));

    var returned = impl.removeEventInternal("evt-remove-internal");

    assertSame("removeEventInternal must return the previously registered instance",
        registered, returned);
    assertNull("registry entry must be gone",
        impl.getEvent("evt-remove-internal"));
    assertNull("interrupt() must null the timer field on the returned instance",
        readTimerField(returned));
    // The builder-returned reference shares the underlying timerLock with the registered
    // instance only if they are the same object — which the dual-instance invariant
    // (see ScheduledEventTest class Javadoc) makes false in general. So we don't assert
    // anything about event's timer field here.
    assertNotNull("the unrelated builder-returned reference is not affected", event);
  }

  @Test
  public void removeEventInternalReturnsNullForUnregisteredName() {
    // Pin the non-null guard inside removeEventInternal: when events.remove returns null, the
    // method must short-circuit (skip the interrupt() call) and return null itself. This is
    // the path taken when onEventDropped is called for an event that was already manually
    // removed.
    var impl = session.getSharedContext().getScheduler();
    assertNull("removeEventInternal on an unknown name must return null",
        impl.removeEventInternal("does-not-exist"));
  }

  @Test
  public void removeEventDeletesPersistedEntityAndDropsRegistrationAndIsIdempotentOnSecondCall()
      throws Exception {
    // removeEvent runs removeEventInternal first (in-memory drop + interrupt), then deletes
    // the OSchedule row inside its own executeInTx. A second call must be a no-op: the
    // registry is already empty, removeEventInternal returns null, and removeEvent returns
    // before reaching the entity-delete branch — that branch is guarded by `if (event != null)`.
    var function = SchedulerTestFixtures.createTrivialFunction(session, "fnRemoveTwice");
    var event = buildEvent("evt-remove-twice", FAR_FUTURE_RULE, function, Map.of());
    var impl = session.getSharedContext().getScheduler();
    var rid = event.getIdentity();
    var registered = impl.getEvent("evt-remove-twice");
    assertNotNull(registered);
    var queuedFuture = readTimerField(registered);
    assertNotNull("auto-schedule hook must have queued a timer before removeEvent",
        queuedFuture);

    impl.removeEvent(session, "evt-remove-twice");

    assertNull("registry must drop the event after removeEvent",
        impl.getEvent("evt-remove-twice"));
    // The interrupt() side-effect of the inner removeEventInternal call must run: the
    // registered instance's timer field is nulled AND the underlying ScheduledFuture is
    // cancelled. A regression that swapped the inner removeEventInternal call for a bare
    // events.remove(...) would leak the timer on the JVM-wide pool — neither the registry
    // nor the entity-delete check would catch it; this pair of assertions does.
    assertNull("removeEvent must call interrupt() via the inner removeEventInternal",
        readTimerField(registered));
    assertTrue("the queued ScheduledFuture must be cancelled, not just dropped from view",
        queuedFuture.isCancelled());
    // The OSchedule row must be deleted inside removeEvent's executeInTx callback.
    var entityAfter = session.computeInTx(transaction -> {
      try {
        return session.loadEntity(rid);
      } catch (RecordNotFoundException expected) {
        return null;
      }
    });
    assertNull("OSchedule row must be deleted by removeEvent", entityAfter);

    // Second call: idempotent no-op via the null-guard inside removeEvent.
    impl.removeEvent(session, "evt-remove-twice");
    assertNull("registry must remain empty after a second removeEvent",
        impl.getEvent("evt-remove-twice"));
  }

  @Test
  public void removeEventOnUnknownNameIsNoOpAndDoesNotThrow() {
    // The second branch (event == null after removeEventInternal) of removeEvent must be a
    // pure no-op — neither the entity delete nor any other side effect should run. Pin that
    // calling removeEvent on an unknown name is harmless and leaves the registry empty.
    var impl = session.getSharedContext().getScheduler();
    int before = impl.getEvents().size();

    impl.removeEvent(session, "never-registered");

    assertEquals("registry size must be unchanged after removeEvent on unknown name",
        before, impl.getEvents().size());
  }

  @Test
  public void closeCancelsAllRegisteredTimersAndClearsRegistry() throws Exception {
    // No-leaked-timers invariant. Register multiple events, snapshot each one's queued
    // ScheduledFuture reference BEFORE close, call close(), then pin three observables:
    //   (a) the registry is empty,
    //   (b) each previously registered event's timer field is null,
    //   (c) each saved ScheduledFuture's isCancelled() is true.
    // (a)+(b) together catch a regression that drops event.interrupt() in favor of bare
    // events.clear() (the timer field would stay non-null because nobody nulled it).
    // (c) catches the subtler regression that nulls the field but skips Future.cancel:
    // without (c), a refactor that wrote `timer = null` without calling `t.cancel(false)`
    // would leak the queued task on the JVM-wide pool's delay queue while the field-only
    // assertions stayed green. The saved reference is the test's anchor for the
    // post-close cancel check — it must be captured BEFORE close() because close()
    // nulls the field.
    var function = SchedulerTestFixtures.createTrivialFunction(session, "fnClose");
    var first = buildEvent("evt-close-1", FAR_FUTURE_RULE, function, Map.of());
    var second = buildEvent("evt-close-2", FAR_FUTURE_RULE, function, Map.of());
    var impl = session.getSharedContext().getScheduler();

    var firstRegistered = impl.getEvent("evt-close-1");
    var secondRegistered = impl.getEvent("evt-close-2");
    assertNotNull(firstRegistered);
    assertNotNull(secondRegistered);
    var firstFuture = readTimerField(firstRegistered);
    var secondFuture = readTimerField(secondRegistered);
    assertNotNull("both events must have queued timers before close()", firstFuture);
    assertNotNull(secondFuture);
    assertFalse("first event's queued ScheduledFuture must be live before close()",
        firstFuture.isCancelled());
    assertFalse("second event's queued ScheduledFuture must be live before close()",
        secondFuture.isCancelled());

    impl.close();

    assertTrue("close() must clear the registry", impl.getEvents().isEmpty());
    assertNull("close() must interrupt the first event's timer",
        readTimerField(firstRegistered));
    assertNull("close() must interrupt the second event's timer",
        readTimerField(secondRegistered));
    assertTrue("close() must cancel the queued ScheduledFuture, not just null the field",
        firstFuture.isCancelled());
    assertTrue(secondFuture.isCancelled());
    // Builder-returned references are independent instances; we don't assert their state.
    assertNotNull(first);
    assertNotNull(second);
  }

  @Test
  public void updateEventInterruptsPriorRegistrationAndReplacesItWithNewInstance()
      throws Exception {
    // updateEvent is the manual-control sibling of postHandleUpdateScheduleAfterTxCommit:
    // it removes the prior registration (calling interrupt() on the old event), then
    // delegates to scheduleEvent(session, newEvent). Pin the swap: the registry entry's
    // identity changes to the new instance, the old instance's timer is null, and the
    // new instance's timer is a freshly queued ScheduledFuture.
    var function = SchedulerTestFixtures.createTrivialFunction(session, "fnUpdate");
    var first = buildEvent("evt-update", FAR_FUTURE_RULE, function, Map.of());
    var impl = session.getSharedContext().getScheduler();

    var firstRegistered = impl.getEvent("evt-update");
    assertNotNull(firstRegistered);
    var firstTimerBefore = readTimerField(firstRegistered);
    assertNotNull(firstTimerBefore);

    // Construct a fresh ScheduledEvent over the same entity inside a tx (the ctor needs a
    // session-bound entity to resolve the function reference); rollback so we do not commit
    // any spurious update to the entity itself.
    session.begin();
    ScheduledEvent second;
    try {
      var entity = (EntityImpl) session.loadEntity(first.getIdentity());
      second = new ScheduledEvent(entity, session);
    } finally {
      session.rollback();
    }

    impl.updateEvent(session, second);

    assertSame("updateEvent must replace the registry entry with the new instance",
        second, impl.getEvent("evt-update"));
    assertNotSame("the new and old instances must be distinct objects",
        firstRegistered, second);
    assertNull("old instance's timer must be cancelled by the prior interrupt() call",
        readTimerField(firstRegistered));
    assertNotNull("new instance must have its timer queued by the inner scheduleEvent",
        readTimerField(second));
  }

  @Test
  public void getEventsReturnsTheLiveInternalConcurrentHashMapNotADefensiveCopy() {
    // SchedulerImpl#getEvents returns the underlying ConcurrentHashMap directly (no
    // defensive copy). Pin three observables a wrapper-based regression would not all
    // satisfy: (a) same-reference identity across two calls (a stable wrapper would also
    // pass this), (b) ConcurrentHashMap type (an unmodifiableMap wrapper would fail),
    // and (c) live mutation through a previously returned view — a stable
    // unmodifiableMap-style wrapper that cached a snapshot would fail this even if the
    // type and identity checks both passed. Documenting the wart so future refactors are
    // deliberate.
    var function = SchedulerTestFixtures.createTrivialFunction(session, "fnLiveView");
    var impl = session.getSharedContext().getScheduler();
    var firstView = impl.getEvents();
    var secondView = impl.getEvents();
    assertSame("getEvents must return the same internal ConcurrentHashMap reference",
        firstView, secondView);
    assertTrue("the returned map must be the same ConcurrentHashMap type",
        firstView instanceof ConcurrentHashMap);

    int sizeBefore = firstView.size();
    buildEvent("evt-live-view", FAR_FUTURE_RULE, function, Map.of());

    // The PREVIOUSLY-RETURNED firstView must reflect the new registration without a
    // re-fetch — this is the live-not-defensive-copy contract a snapshotted wrapper
    // would violate.
    assertEquals("subsequent registration must be visible through the prior view",
        sizeBefore + 1, firstView.size());
    assertTrue("new registration must be reachable through the prior view",
        firstView.containsKey("evt-live-view"));
  }

  @Test
  public void getEventReturnsNullForUnknownNameAndRegisteredInstanceForKnownName() {
    // Trivial accessor pin — registry contains exactly what scheduleEvent put there.
    var function = SchedulerTestFixtures.createTrivialFunction(session, "fnAccess");
    var event = buildEvent("evt-access", FAR_FUTURE_RULE, function, Map.of());
    var impl = session.getSharedContext().getScheduler();

    assertNull("unknown name must return null",
        impl.getEvent("unknown"));
    assertNotNull("known name must return a non-null ScheduledEvent",
        impl.getEvent("evt-access"));
    // The builder-returned reference is a separate instance from the one auto-registered
    // by the after-commit hook (see ScheduledEventTest class Javadoc on the dual-instance
    // invariant), so we don't pin assertSame here.
    assertNotNull(event);
  }

  // ---------------------------------------------------------------------------
  // Hook 1: initScheduleRecord — already-exists guard branch
  // ---------------------------------------------------------------------------

  @Test
  public void initScheduleRecordThrowsWhenSameIdentityIsAlreadyRegisteredUnderTheSameName() {
    // The guard inside initScheduleRecord rejects duplicate registration:
    //   if (event != null && event.getIdentity().equals(entity.getIdentity()))
    //     throw new DatabaseException(
    //         "Scheduled event with name '...' already exists in database");
    // Trigger by registering an event normally (auto-schedule hook fires, registry holds it),
    // then reload the entity in a fresh tx and call initScheduleRecord with that entity. The
    // identity matches the registered event's, so the guard fires and DatabaseException is
    // thrown. This is the only branch of initScheduleRecord not covered by ScheduledEventTest.
    var function = SchedulerTestFixtures.createTrivialFunction(session, "fnInitDup");
    var event = buildEvent("evt-init-dup", FAR_FUTURE_RULE, function, Map.of());
    var impl = session.getSharedContext().getScheduler();

    // initScheduleRecord reads entity.getProperty(PROP_NAME) and entity.getIdentity(); the
    // entity must be session-bound for the property read to succeed, so run the call inside
    // an active tx and roll back to leave the persisted state untouched.
    session.begin();
    try {
      var entity = (EntityImpl) session.loadEntity(event.getIdentity());

      var ex = assertThrows(DatabaseException.class, () -> impl.initScheduleRecord(entity));
      assertTrue("error message must mention the conflicting event name: <"
          + ex.getMessage() + ">",
          ex.getMessage().contains("evt-init-dup"));
    } finally {
      session.rollback();
    }
  }

  // ---------------------------------------------------------------------------
  // Hook 2 & 3 (static): onAfterEventDropped + onEventDropped
  // ---------------------------------------------------------------------------

  @Test
  public void onAfterEventDroppedPopulatesTxCustomDataMapWithRidToNameMappingForFreshTx() {
    // The static onAfterEventDropped(tx, entity) reads the "droppedEventsMap" key from the
    // transaction's custom data; if absent, it creates a HashMap and re-stores it; in either
    // case it puts {entity.getIdentity() -> entity.getProperty(PROP_NAME)}. Pin the
    // fresh-tx branch (custom data was null before the call).
    var function = SchedulerTestFixtures.createTrivialFunction(session, "fnDropFresh");
    var event = buildEvent("evt-drop-fresh", FAR_FUTURE_RULE, function, Map.of());

    session.begin();
    try {
      var tx = (FrontendTransactionImpl) session.getTransactionInternal();
      assertNull("droppedEventsMap must be null on a fresh tx",
          tx.getCustomData(SchedulerTestFixtures.DROPPED_EVENTS_MAP_KEY));

      var entity = (EntityImpl) session.loadEntity(event.getIdentity());
      SchedulerImpl.onAfterEventDropped(tx, entity);

      @SuppressWarnings("unchecked")
      var droppedMap =
          (Map<RID, String>) tx.getCustomData(SchedulerTestFixtures.DROPPED_EVENTS_MAP_KEY);
      assertNotNull("onAfterEventDropped must populate droppedEventsMap on a fresh tx",
          droppedMap);
      assertEquals("droppedEventsMap must contain exactly one entry after the first call",
          1, droppedMap.size());
      assertEquals("droppedEventsMap must map RID -> event name",
          "evt-drop-fresh", droppedMap.get(event.getIdentity()));
    } finally {
      session.rollback();
    }
  }

  @Test
  public void onAfterEventDroppedAppendsToExistingMapInTxCustomDataInsteadOfReplacingIt() {
    // The else branch of onAfterEventDropped — when the map already exists in custom data,
    // a second call must reuse it (append) rather than replace it. Pin by calling twice
    // for two distinct events and asserting both entries survive.
    var function = SchedulerTestFixtures.createTrivialFunction(session, "fnDropAppend");
    var first = buildEvent("evt-drop-1", FAR_FUTURE_RULE, function, Map.of());
    var second = buildEvent("evt-drop-2", FAR_FUTURE_RULE, function, Map.of());

    session.begin();
    try {
      var tx = (FrontendTransactionImpl) session.getTransactionInternal();
      var firstEntity = (EntityImpl) session.loadEntity(first.getIdentity());
      var secondEntity = (EntityImpl) session.loadEntity(second.getIdentity());

      SchedulerImpl.onAfterEventDropped(tx, firstEntity);
      @SuppressWarnings("unchecked")
      var afterFirstCall =
          (Map<RID, String>) tx.getCustomData(SchedulerTestFixtures.DROPPED_EVENTS_MAP_KEY);
      assertNotNull(afterFirstCall);

      SchedulerImpl.onAfterEventDropped(tx, secondEntity);
      @SuppressWarnings("unchecked")
      var afterSecondCall =
          (Map<RID, String>) tx.getCustomData(SchedulerTestFixtures.DROPPED_EVENTS_MAP_KEY);

      assertSame("the second call must reuse the same Map instance, not replace it",
          afterFirstCall, afterSecondCall);
      assertEquals("both entries must survive the second call", 2, afterSecondCall.size());
      assertEquals("evt-drop-1", afterSecondCall.get(first.getIdentity()));
      assertEquals("evt-drop-2", afterSecondCall.get(second.getIdentity()));
    } finally {
      session.rollback();
    }
  }

  @Test
  public void onEventDroppedReadsTxCustomDataMapAndUnregistersEventByName() throws Exception {
    // onEventDropped reads the RID->name map populated by onAfterEventDropped, looks up the
    // event name for the given RID, and calls removeEventInternal(name). Pin by populating
    // the map manually (so the test is independent of the production after-delete flow),
    // capturing the registered instance and its queued ScheduledFuture BEFORE the hook
    // runs, then asserting (a) registry entry is gone, (b) the timer field is nulled, and
    // (c) the saved ScheduledFuture is cancelled. Without (b)+(c) a regression that
    // swapped removeEventInternal for bare events.remove(name) would leak the timer
    // silently — the registry pin alone would pass.
    var function = SchedulerTestFixtures.createTrivialFunction(session, "fnEventDropped");
    var event = buildEvent("evt-on-dropped", FAR_FUTURE_RULE, function, Map.of());
    var impl = session.getSharedContext().getScheduler();
    var registered = impl.getEvent("evt-on-dropped");
    assertNotNull("event must be registered before onEventDropped", registered);
    var queuedFuture = readTimerField(registered);
    assertNotNull(queuedFuture);

    session.begin();
    try {
      var tx = (FrontendTransactionImpl) session.getTransactionInternal();
      var entity = (EntityImpl) session.loadEntity(event.getIdentity());
      // Populate the map via the static helper — same path the production hook uses.
      SchedulerImpl.onAfterEventDropped(tx, entity);

      impl.onEventDropped(session, event.getIdentity());

      assertNull("onEventDropped must unregister the event by name",
          impl.getEvent("evt-on-dropped"));
      assertNull("onEventDropped must call interrupt() via removeEventInternal",
          readTimerField(registered));
      assertTrue("the queued ScheduledFuture must be cancelled, not just dropped from view",
          queuedFuture.isCancelled());
    } finally {
      session.rollback();
    }
  }

  @Test
  public void onEventDroppedNullPointerExceptionsWhenTxCustomDataMapWasNeverPopulated()
      throws Exception {
    // Production has no null-guard on the tx custom-data map (SchedulerImpl#onEventDropped
    // reads it then calls .get(rid) directly). If the after-commit pipeline ever invokes
    // onEventDropped without a prior onAfterEventDropped — e.g., a future refactor that
    // changes hook ordering, or a partial recovery path — the .get(rid) call NPEs. Pin
    // the current behavior so the regression catches the change. The test creates a
    // standalone event entity (no schedule registration), then drives onEventDropped on
    // a fresh tx where the map was never populated. WHEN-FIXED: add a null-guard around
    // the map.get(rid) call (SchedulerImpl#onEventDropped) so a missing map yields a
    // no-op rather than NPE; flip this to assertNull(impl.getEvent(name)) once fixed.
    var function = SchedulerTestFixtures.createTrivialFunction(session, "fnNullMap");
    var event = buildEvent("evt-null-map", FAR_FUTURE_RULE, function, Map.of());
    var impl = session.getSharedContext().getScheduler();
    var rid = event.getIdentity();
    // Drop the auto-registration so removeEventInternal would be a no-op even if the
    // production code somehow reached it through a non-NPE path; the test focuses on
    // pinning the .get(rid) NPE, not the downstream registry effect.
    impl.removeEventInternal("evt-null-map");

    session.begin();
    try {
      // Fresh tx — droppedEventsMap is null in custom data.
      assertNull("droppedEventsMap must be null on the fresh tx for the pin to be valid",
          ((FrontendTransactionImpl) session.getTransactionInternal())
              .getCustomData(SchedulerTestFixtures.DROPPED_EVENTS_MAP_KEY));
      assertThrows(NullPointerException.class, () -> impl.onEventDropped(session, rid));
    } finally {
      session.rollback();
    }
  }

  // ---------------------------------------------------------------------------
  // Hook 4: preHandleUpdateScheduleInTx — branches by dirty fields
  // ---------------------------------------------------------------------------

  @Test
  public void preHandleUpdateScheduleInTxIsNoOpWhenEventIsNotRegisteredUnderTheGivenName() {
    // The early-return branch — if the event lookup returns null, the method short-circuits
    // (no dirty-field analysis, no rids set population). Pin by clearing the registry first,
    // then calling preHandleUpdateScheduleInTx with a fresh entity. The tx custom data must
    // remain unset.
    var function = SchedulerTestFixtures.createTrivialFunction(session, "fnPreNoEvent");
    var event = buildEvent("evt-pre-no-event", FAR_FUTURE_RULE, function, Map.of());
    var impl = session.getSharedContext().getScheduler();
    impl.removeEventInternal("evt-pre-no-event");

    session.begin();
    try {
      var entity = (EntityImpl) session.loadEntity(event.getIdentity());
      impl.preHandleUpdateScheduleInTx(session, entity);

      var tx = (FrontendTransactionImpl) session.getTransactionInternal();
      assertNull("no event registered → no rids set populated",
          tx.getCustomData(SchedulerTestFixtures.RIDS_OF_EVENTS_TO_RESCHEDULE_KEY));
    } finally {
      session.rollback();
    }
  }

  @Test
  public void postHandleUpdateScheduleAfterTxCommitIsNoOpWhenRidsSetIsAbsent() {
    // The early-return branch — if the rids set in custom data is null (no prior
    // preHandleUpdateScheduleInTx fired this tx), postHandleUpdateScheduleAfterTxCommit
    // must short-circuit without touching the registry. Pin by calling it with a fresh
    // tx and asserting the registry is unchanged.
    var function = SchedulerTestFixtures.createTrivialFunction(session, "fnPostNoSet");
    var event = buildEvent("evt-post-no-set", FAR_FUTURE_RULE, function, Map.of());
    var impl = session.getSharedContext().getScheduler();

    var registeredBefore = impl.getEvent("evt-post-no-set");
    assertNotNull(registeredBefore);

    session.begin();
    try {
      var entity = (EntityImpl) session.loadEntity(event.getIdentity());
      impl.postHandleUpdateScheduleAfterTxCommit(session, entity);

      assertSame("registry entry must be unchanged when rids set is absent",
          registeredBefore, impl.getEvent("evt-post-no-set"));
    } finally {
      session.rollback();
    }
  }

  @Test
  public void postHandleUpdateScheduleAfterTxCommitIsNoOpWhenRidsSetExistsButDoesNotContainEntity()
      throws Exception {
    // Branch where the rids set is non-null but does not contain the entity's identity
    // (some other event in the same tx had its rule changed, but not this one).
    var function = SchedulerTestFixtures.createTrivialFunction(session, "fnPostOtherRid");
    var event = buildEvent("evt-post-other", FAR_FUTURE_RULE, function, Map.of());
    var impl = session.getSharedContext().getScheduler();

    var registeredBefore = impl.getEvent("evt-post-other");
    assertNotNull(registeredBefore);
    var timerBefore = readTimerField(registeredBefore);

    session.begin();
    try {
      var tx = (FrontendTransactionImpl) session.getTransactionInternal();
      // Populate the rids set with an unrelated RID — Object.equals on a fresh ChangeableRID
      // would not match the persistent entity's RID, so the contains check returns false.
      var unrelatedRid = session.newEntity().getIdentity();
      Set<RID> ridSet = new HashSet<>();
      ridSet.add(unrelatedRid);
      tx.setCustomData(SchedulerTestFixtures.RIDS_OF_EVENTS_TO_RESCHEDULE_KEY, ridSet);

      var entity = (EntityImpl) session.loadEntity(event.getIdentity());
      impl.postHandleUpdateScheduleAfterTxCommit(session, entity);

      assertSame("rids set without this entity → registry unchanged",
          registeredBefore, impl.getEvent("evt-post-other"));
      assertSame("timer must not be cancelled when this entity isn't in the rids set",
          timerBefore, readTimerField(registeredBefore));
    } finally {
      session.rollback();
    }
  }

  @Test
  public void postHandleUpdateScheduleAfterTxCommitReschedulesWhenRidsSetContainsEntityIdentity()
      throws Exception {
    // Pin the reschedule branch of postHandleUpdateScheduleAfterTxCommit by calling the
    // hook directly with the rids-of-events-to-reschedule custom-data set pre-populated.
    // This isolates the post-hook's reschedule logic from SQL UPDATE's dirty-property
    // tracking — which is governed by the storage layer and is hard to drive
    // deterministically from a unit test. The end-to-end SQL UPDATE flow is covered by
    // SchedulerTest.eventBySQL via its log-counter assertion (a regression that disabled
    // the post-hook would also break that test).
    //
    // Setup:
    //   1. Register event A via buildEvent (auto-schedule hook fires after commit).
    //   2. Open a fresh tx, reload the entity, pre-populate the rids set with this
    //      entity's identity (mimicking what the pre-handle hook would have written).
    //   3. Call postHandleUpdateScheduleAfterTxCommit directly.
    //   4. Pin: registry now holds a different ScheduledEvent instance (not A), the old
    //      A has its timer field nulled, and the new event has a freshly queued timer.
    var function = SchedulerTestFixtures.createTrivialFunction(session, "fnPostReschedule");
    var event = buildEvent("evt-post-resched", FAR_FUTURE_RULE, function, Map.of());
    var impl = session.getSharedContext().getScheduler();

    var beforeUpdate = impl.getEvent("evt-post-resched");
    assertNotNull(beforeUpdate);
    assertNotNull("event must have a queued timer before the reschedule",
        readTimerField(beforeUpdate));

    session.begin();
    try {
      var entity = (EntityImpl) session.loadEntity(event.getIdentity());
      var tx = (FrontendTransactionImpl) session.getTransactionInternal();

      Set<RID> ridSet = new HashSet<>();
      ridSet.add(entity.getIdentity());
      tx.setCustomData(SchedulerTestFixtures.RIDS_OF_EVENTS_TO_RESCHEDULE_KEY, ridSet);

      impl.postHandleUpdateScheduleAfterTxCommit(session, entity);

      var afterUpdate = impl.getEvent("evt-post-resched");
      assertNotNull("registry must still contain the event after the reschedule call",
          afterUpdate);
      assertNotSame("post-hook must replace the registered instance via updateEvent "
          + "when the rid is in the rids set",
          beforeUpdate, afterUpdate);
      assertNull("old event's timer must be cancelled by the inner updateEvent",
          readTimerField(beforeUpdate));
      assertNotNull("new event must have a freshly queued timer",
          readTimerField(afterUpdate));
    } finally {
      session.rollback();
    }
  }

  @Test
  public void preHandleUpdateScheduleInTxPopulatesRidsSetWhenPropRuleIsDirty() throws Exception {
    // The rule-dirty branch is the only state-mutating path of preHandleUpdateScheduleInTx,
    // and its effect — populating the RIDS_OF_EVENTS_TO_RESCHEDULE custom-data set — is what
    // postHandleUpdateScheduleAfterTxCommit subsequently reads to decide which entries to
    // reschedule. Pin both sub-branches:
    //   (a) rids == null first time → set is created and stored;
    //   (b) rids != null on a second call within the same tx → existing set is REUSED (not
    //       replaced) and the second entity's RID is appended.
    // A regression that dropped the rids.add(entity.getIdentity()) call, or that replaced
    // the set on every call, would flip these assertions red.
    var function = SchedulerTestFixtures.createTrivialFunction(session, "fnPreRuleDirty");
    var firstEvent = buildEvent("evt-pre-rule-1", FAR_FUTURE_RULE, function, Map.of());
    var secondEvent = buildEvent("evt-pre-rule-2", FAR_FUTURE_RULE, function, Map.of());
    var impl = session.getSharedContext().getScheduler();
    assertNotNull(impl.getEvent("evt-pre-rule-1"));
    assertNotNull(impl.getEvent("evt-pre-rule-2"));

    session.begin();
    try {
      var firstEntity = (EntityImpl) session.loadEntity(firstEvent.getIdentity());
      // Mutate PROP_RULE so getDirtyPropertiesBetweenCallbacksInternal includes it.
      firstEntity.setProperty(ScheduledEvent.PROP_RULE, "0 0 13 1 1 ? 2099");
      impl.preHandleUpdateScheduleInTx(session, firstEntity);

      var tx = (FrontendTransactionImpl) session.getTransactionInternal();
      @SuppressWarnings("unchecked")
      var ridsAfterFirst =
          (Set<RID>) tx.getCustomData(SchedulerTestFixtures.RIDS_OF_EVENTS_TO_RESCHEDULE_KEY);
      assertNotNull("first rule-dirty call must initialise the rids set", ridsAfterFirst);
      assertTrue("first call must append this entity's identity",
          ridsAfterFirst.contains(firstEntity.getIdentity()));
      assertEquals("first call seeds exactly one entry", 1, ridsAfterFirst.size());

      var secondEntity = (EntityImpl) session.loadEntity(secondEvent.getIdentity());
      secondEntity.setProperty(ScheduledEvent.PROP_RULE, "0 0 14 1 1 ? 2099");
      impl.preHandleUpdateScheduleInTx(session, secondEntity);

      @SuppressWarnings("unchecked")
      var ridsAfterSecond =
          (Set<RID>) tx.getCustomData(SchedulerTestFixtures.RIDS_OF_EVENTS_TO_RESCHEDULE_KEY);
      assertSame("second rule-dirty call must REUSE the existing rids set, not replace it",
          ridsAfterFirst, ridsAfterSecond);
      assertEquals("second call appends without losing the first entry",
          2, ridsAfterSecond.size());
      assertTrue(ridsAfterSecond.contains(firstEntity.getIdentity()));
      assertTrue(ridsAfterSecond.contains(secondEntity.getIdentity()));
    } finally {
      session.rollback();
    }
  }

  @Test
  public void preHandleUpdateScheduleInTxSwallowsValidationExceptionAndShortCircuitsRuleBranch()
      throws Exception {
    // The PROP_NAME-dirty branch throws ValidationException inside preHandleUpdateScheduleInTx;
    // the surrounding catch swallows it. The original test asserted only that the registry was
    // unchanged — but the registry is keyed by ScheduledEvent.getName() (set at ctor time, not
    // by entity property), so the registry would be unchanged whether the throw fired or not.
    // That made it non-falsifiable: a regression that removed the throw would still leave the
    // registry untouched and the assertion green.
    //
    // The falsifiable observable is the *short-circuit* effect: when PROP_NAME and PROP_RULE
    // are BOTH dirty, the throw on PROP_NAME must fire before the PROP_RULE branch populates
    // the rids set. So if the throw is removed, control would fall through to the rule branch
    // and the rids set would be non-null after the call. Pin the inverse: rids set is null,
    // proving the throw short-circuited.
    //
    // Setup detail: the hook reads schedulerName = entity.getProperty(PROP_NAME), then looks
    // it up via getEvent. To make that lookup hit a registered entry after we mutate PROP_NAME,
    // we register a SECOND event under the target name and mutate event A's PROP_NAME to that
    // target. The hook finds event B by lookup, sees PROP_NAME in the dirty set, and throws.
    var function = SchedulerTestFixtures.createTrivialFunction(session, "fnNameDirtyAB");
    var eventA = buildEvent("evt-name-A", FAR_FUTURE_RULE, function, Map.of());
    buildEvent("evt-name-B", "0 0 13 1 1 ? 2099", function, Map.of());
    var impl = session.getSharedContext().getScheduler();
    assertNotNull("setup: event A must be registered", impl.getEvent("evt-name-A"));
    assertNotNull("setup: event B must be registered", impl.getEvent("evt-name-B"));

    session.begin();
    try {
      var entityA = (EntityImpl) session.loadEntity(eventA.getIdentity());
      // Mutate both PROP_NAME (to a registered name so the lookup succeeds) and PROP_RULE.
      entityA.setProperty(ScheduledEvent.PROP_NAME, "evt-name-B");
      entityA.setProperty(ScheduledEvent.PROP_RULE, "0 0 14 1 1 ? 2099");

      // Must NOT throw to the caller — the catch swallows the inner ValidationException.
      impl.preHandleUpdateScheduleInTx(session, entityA);

      var tx = (FrontendTransactionImpl) session.getTransactionInternal();
      assertNull("ValidationException must short-circuit before the PROP_RULE branch "
          + "populates the rids set; null rids → throw fired",
          tx.getCustomData(SchedulerTestFixtures.RIDS_OF_EVENTS_TO_RESCHEDULE_KEY));
    } finally {
      session.rollback();
    }
  }

  // ---------------------------------------------------------------------------
  // Hook 5: scheduleEvent (after-create) — exercised end-to-end by every buildEvent
  // call. Pin the explicit invariant: after a fresh save, the registry contains
  // exactly one entry by the configured name, with a queued timer.
  // ---------------------------------------------------------------------------

  @Test
  public void afterCreateAutoScheduleHookRegistersANewlySavedEventByNameWithAQueuedTimer()
      throws Exception {
    var function = SchedulerTestFixtures.createTrivialFunction(session, "fnAutoSchedule");
    var impl = session.getSharedContext().getScheduler();
    int sizeBefore = impl.getEvents().size();

    var event = buildEvent("evt-auto-schedule", FAR_FUTURE_RULE, function, Map.of());

    var registered = impl.getEvent("evt-auto-schedule");
    assertNotNull("after-commit hook must register the new event by name",
        registered);
    assertNotNull("after-commit hook must call event.schedule(), populating timer",
        readTimerField(registered));
    assertEquals("registry must grow by exactly one entry",
        sizeBefore + 1, impl.getEvents().size());
    // Builder-returned reference: dual-instance — see ScheduledEventTest class Javadoc.
    assertNotNull(event);
  }

  // ---------------------------------------------------------------------------
  // Hook 6: end-to-end DELETE flow — afterDeleteOperations -> static
  //         onAfterEventDropped -> afterCommitOperations -> onEventDropped
  // ---------------------------------------------------------------------------

  @Test
  public void deletingScheduleEntityThroughDbApiUnregistersEventViaTheTwoStageDeleteHook()
      throws Exception {
    // The delete flow runs in two stages:
    //   afterDeleteOperations (during commit prep) -> SchedulerImpl.onAfterEventDropped
    //     stamps the {RID -> name} pair into the tx's custom data.
    //   afterCommitOperations (after commit) for RecordOperation.DELETED ->
    //     SchedulerImpl.onEventDropped reads the map and calls removeEventInternal.
    // Pin the end-to-end observable: after a delete commit, the registry no longer has the
    // event AND the previously registered instance's timer is null (interrupted by
    // removeEventInternal).
    var function = SchedulerTestFixtures.createTrivialFunction(session, "fnDeleteFlow");
    var event = buildEvent("evt-delete-flow", FAR_FUTURE_RULE, function, Map.of());
    var impl = session.getSharedContext().getScheduler();

    var registered = impl.getEvent("evt-delete-flow");
    assertNotNull(registered);
    assertNotNull(readTimerField(registered));

    session.executeInTx(tx -> {
      var entity = session.loadEntity(event.getIdentity());
      session.delete(entity);
    });

    assertNull("delete-flow hooks must unregister the event from SchedulerImpl",
        impl.getEvent("evt-delete-flow"));
    assertNull("delete-flow hooks must interrupt the previously registered instance's timer",
        readTimerField(registered));
  }

  // ---------------------------------------------------------------------------
  // Concurrency: removeEventInternal under multi-thread access. Two pins —
  //   (a) cleanup-under-concurrency (disjoint per-thread keyspace);
  //   (b) same-key atomicity (multiple threads contend on the same name).
  // ---------------------------------------------------------------------------

  @Test
  public void concurrentRemoveEventInternalOnDisjointEventNamesProducesConsistentEmptyRegistry()
      throws Exception {
    // Cleanup smoke test: spawn N worker threads, each owning its own disjoint partition
    // of event names, and assert that after a parallel sweep every event is removed AND
    // every timer is cancelled. This pin asserts the *cleanup invariant under concurrent
    // operation* (no leaked timers on the JVM-wide pool when removeEventInternal is
    // sprayed from multiple threads), not the same-key atomicity contract — that contract
    // is exercised by concurrentRemoveEventInternalOnSameNameReturnsNonNullExactlyOnce
    // below, where threads contend on the same registry entry.
    final int threadCount = 4;
    final int eventsPerThread = 3;
    final int totalEvents = threadCount * eventsPerThread;
    var function = SchedulerTestFixtures.createTrivialFunction(session, "fnConcurrent");
    var impl = session.getSharedContext().getScheduler();

    // Pre-build all events sequentially on the main thread — auto-schedule hook registers
    // each one. We capture the registered (not the builder-returned) instances so the
    // post-removal timer-field assertion is on the exact instance whose interrupt() was
    // called.
    var registered = new ScheduledEvent[totalEvents];
    var names = new String[totalEvents];
    for (int n = 0; n < totalEvents; n++) {
      names[n] = "evt-concurrent-" + n;
      buildEvent(names[n], FAR_FUTURE_RULE, function, Map.of());
      registered[n] = impl.getEvent(names[n]);
      assertNotNull("pre-build registration must succeed for " + names[n], registered[n]);
      assertNotNull("pre-build must queue a timer for " + names[n],
          readTimerField(registered[n]));
    }

    var executor = Executors.newFixedThreadPool(threadCount);
    var startGate = new CountDownLatch(1);
    try {
      var futures = new ArrayList<Future<?>>(threadCount);
      for (int t = 0; t < threadCount; t++) {
        final int threadId = t;
        Future<?> future = executor.submit(() -> {
          try {
            startGate.await();
            for (int i = 0; i < eventsPerThread; i++) {
              int idx = threadId * eventsPerThread + i;
              impl.removeEventInternal(names[idx]);
            }
          } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            fail("worker thread was interrupted: " + ie.getMessage());
          }
          return null;
        });
        futures.add(future);
      }

      startGate.countDown();
      for (var future : futures) {
        future.get(60, TimeUnit.SECONDS);
      }
    } finally {
      executor.shutdown();
      assertTrue("worker pool must terminate",
          executor.awaitTermination(60, TimeUnit.SECONDS));
    }

    // Final invariants: registry has no event under any of the N names AND every event's
    // timer was cancelled by the removeEventInternal call (interrupt() nulled the field).
    for (int n = 0; n < totalEvents; n++) {
      assertNull("event '" + names[n] + "' must not be left in the registry after concurrent"
          + " removeEventInternal",
          impl.getEvent(names[n]));
      assertNull("event '" + names[n] + "' must have its timer field nulled by interrupt()",
          readTimerField(registered[n]));
    }
  }

  @Test
  public void concurrentRemoveEventInternalOnSameNameReturnsNonNullExactlyOnce() throws Exception {
    // Atomicity contract: when N threads call removeEventInternal(SAME_NAME), exactly ONE
    // thread observes the non-null return value (the registered ScheduledEvent), and N-1
    // observe null. ConcurrentHashMap.remove(k) guarantees this; a regression that swapped
    // the registry for a non-thread-safe Map (or relaxed the atomicity in any way) would
    // either return non-null from multiple threads (double-cancel of the same timer →
    // observable as cancelled-twice race in interrupt()) or drop the entry without any
    // thread seeing it. Pin the contract by counting non-null returns under N-way contention.
    var function = SchedulerTestFixtures.createTrivialFunction(session, "fnContend");
    var event = buildEvent("evt-contend", FAR_FUTURE_RULE, function, Map.of());
    var impl = session.getSharedContext().getScheduler();
    var registered = impl.getEvent("evt-contend");
    assertNotNull(registered);
    var queuedFuture = readTimerField(registered);
    assertNotNull(queuedFuture);
    // Builder-returned reference is independent — see ScheduledEventTest class Javadoc.
    assertNotNull(event);

    final int threadCount = 8;
    var executor = Executors.newFixedThreadPool(threadCount);
    var startGate = new CountDownLatch(1);
    var nonNullCount = new AtomicInteger();
    try {
      var futures = new ArrayList<Future<?>>(threadCount);
      for (int t = 0; t < threadCount; t++) {
        Future<?> future = executor.submit(() -> {
          try {
            startGate.await();
            if (impl.removeEventInternal("evt-contend") != null) {
              nonNullCount.incrementAndGet();
            }
          } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            fail("worker thread was interrupted: " + ie.getMessage());
          }
          return null;
        });
        futures.add(future);
      }
      startGate.countDown();
      for (var future : futures) {
        future.get(60, TimeUnit.SECONDS);
      }
    } finally {
      executor.shutdown();
      assertTrue("worker pool must terminate",
          executor.awaitTermination(60, TimeUnit.SECONDS));
    }

    assertEquals("exactly one thread must observe the non-null return from "
        + "removeEventInternal under same-key contention",
        1, nonNullCount.get());
    assertNull("registry must be empty after the contended removes",
        impl.getEvent("evt-contend"));
    assertNull("timer field must be nulled by the single winning interrupt() call",
        readTimerField(registered));
    assertTrue("the queued ScheduledFuture must be cancelled exactly once",
        queuedFuture.isCancelled());
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private ScheduledEvent buildEvent(String name, String rule, Function function,
      Map<Object, Object> args) {
    return SchedulerTestFixtures.buildEvent(session, name, rule, function, args);
  }

  private static ScheduledFuture<?> readTimerField(ScheduledEvent event) throws Exception {
    return SchedulerTestFixtures.readTimerField(event);
  }
}
