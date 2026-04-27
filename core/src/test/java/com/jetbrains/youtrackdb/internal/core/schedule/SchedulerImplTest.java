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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.exception.DatabaseException;
import com.jetbrains.youtrackdb.internal.core.metadata.function.Function;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransactionImpl;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
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
 */
@Category(SequentialTest.class)
public class SchedulerImplTest extends DbTestBase {

  /**
   * Far-future cron rule. The expression parses as a single firing at noon on
   * 1 January 2099 — every call to {@code getNextValidTimeAfter(now)} during
   * the test window returns that same timestamp, so any timer queued by
   * {@link SchedulerImpl#scheduleEvent} waits for a moment that does not
   * arrive before the surefire JVM exits.
   */
  private static final String FAR_FUTURE_RULE = "0 0 12 1 1 ? 2099";

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

    impl.removeEvent(session, "evt-remove-twice");

    assertNull("registry must drop the event after removeEvent",
        impl.getEvent("evt-remove-twice"));
    // The OSchedule row must be deleted inside removeEvent's executeInTx callback.
    var entityAfter = session.computeInTx(transaction -> {
      try {
        return session.loadEntity(rid);
      } catch (com.jetbrains.youtrackdb.api.exception.RecordNotFoundException expected) {
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
    // No-leaked-timers invariant. Register multiple events, snapshot each one's timer
    // reference, call close(), and assert (a) the registry is empty, (b) each previously
    // registered event's timer field is null (close() iterates and calls event.interrupt()
    // for every entry). This is the explicit pin against a regression that drops the
    // event.interrupt() loop and replaces it with events.clear(): the registry would still
    // be empty, but the queued futures would survive in the JVM-wide pool's delay queue.
    var function = SchedulerTestFixtures.createTrivialFunction(session, "fnClose");
    var first = buildEvent("evt-close-1", FAR_FUTURE_RULE, function, Map.of());
    var second = buildEvent("evt-close-2", FAR_FUTURE_RULE, function, Map.of());
    var impl = session.getSharedContext().getScheduler();

    var firstRegistered = impl.getEvent("evt-close-1");
    var secondRegistered = impl.getEvent("evt-close-2");
    assertNotNull(firstRegistered);
    assertNotNull(secondRegistered);
    assertNotNull("both events must have queued timers before close()",
        readTimerField(firstRegistered));
    assertNotNull(readTimerField(secondRegistered));

    impl.close();

    assertTrue("close() must clear the registry", impl.getEvents().isEmpty());
    assertNull("close() must interrupt the first event's timer",
        readTimerField(firstRegistered));
    assertNull("close() must interrupt the second event's timer",
        readTimerField(secondRegistered));
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
    // defensive copy). Pin that by invoking it twice and asserting same-reference equality —
    // a regression that wraps the return in Collections.unmodifiableMap or that returns
    // a new copy each time would break this assertion. Documenting the wart so future
    // refactors are deliberate.
    var impl = session.getSharedContext().getScheduler();
    var firstView = impl.getEvents();
    var secondView = impl.getEvents();
    assertSame("getEvents must return the same internal ConcurrentHashMap reference",
        firstView, secondView);
    assertTrue("the returned map must be the same ConcurrentHashMap type",
        firstView instanceof ConcurrentHashMap);
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
    //     throw new DatabaseException("Scheduled event with name '...' already exists in database");
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
          tx.getCustomData("droppedEventsMap"));

      var entity = (EntityImpl) session.loadEntity(event.getIdentity());
      SchedulerImpl.onAfterEventDropped(tx, entity);

      @SuppressWarnings("unchecked")
      var droppedMap = (Map<RID, String>) tx.getCustomData("droppedEventsMap");
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
      var afterFirstCall = (Map<RID, String>) tx.getCustomData("droppedEventsMap");
      assertNotNull(afterFirstCall);

      SchedulerImpl.onAfterEventDropped(tx, secondEntity);
      @SuppressWarnings("unchecked")
      var afterSecondCall = (Map<RID, String>) tx.getCustomData("droppedEventsMap");

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
  public void onEventDroppedReadsTxCustomDataMapAndUnregistersEventByName() {
    // onEventDropped reads the RID->name map populated by onAfterEventDropped, looks up the
    // event name for the given RID, and calls removeEventInternal(name). Pin by populating
    // the map manually (so the test is independent of the production after-delete flow) and
    // asserting the registry entry is gone after the call.
    var function = SchedulerTestFixtures.createTrivialFunction(session, "fnEventDropped");
    var event = buildEvent("evt-on-dropped", FAR_FUTURE_RULE, function, Map.of());
    var impl = session.getSharedContext().getScheduler();

    assertNotNull("event must be registered before onEventDropped",
        impl.getEvent("evt-on-dropped"));

    session.begin();
    try {
      var tx = (FrontendTransactionImpl) session.getTransactionInternal();
      var entity = (EntityImpl) session.loadEntity(event.getIdentity());
      // Populate the map via the static helper — same path the production hook uses.
      SchedulerImpl.onAfterEventDropped(tx, entity);

      impl.onEventDropped(session, event.getIdentity());

      assertNull("onEventDropped must unregister the event by name",
          impl.getEvent("evt-on-dropped"));
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
          tx.getCustomData(SchedulerImpl.class.getName() + ".ridsOfEventsToReschedule"));
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
      Set<RID> ridSet = new java.util.HashSet<>();
      ridSet.add(unrelatedRid);
      tx.setCustomData(SchedulerImpl.class.getName() + ".ridsOfEventsToReschedule", ridSet);

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

      Set<RID> ridSet = new java.util.HashSet<>();
      ridSet.add(entity.getIdentity());
      tx.setCustomData(SchedulerImpl.class.getName() + ".ridsOfEventsToReschedule", ridSet);

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
  public void preHandleUpdateScheduleInTxRejectsNameChangeViaCaughtValidationExceptionAndLogs()
      throws Exception {
    // The name-change branch throws ValidationException inside preHandleUpdateScheduleInTx,
    // but the surrounding catch swallows it (logs only). Pin the observable: after a
    // name-change attempt followed by commit, the registry still holds the original event
    // under its ORIGINAL name (the post-hook does NOT see a rids-set entry because the
    // rule wasn't changed and the validation failure happens before the rids-set logic
    // for rule changes). The original event remains addressable by its old name.
    var function = SchedulerTestFixtures.createTrivialFunction(session, "fnNameChange");
    var event = buildEvent("evt-name-orig", FAR_FUTURE_RULE, function, Map.of());
    var impl = session.getSharedContext().getScheduler();

    var beforeUpdate = impl.getEvent("evt-name-orig");
    assertNotNull(beforeUpdate);

    // Attempt the name change via SQL UPDATE with explicit begin/commit — same reason as the
    // rule-change test: SQL UPDATE drives the dirty-property tracking through the hook chain.
    // The hook's catch block swallows the ValidationException, so the commit still completes —
    // but the registry remains keyed by the ORIGINAL name (the registry is keyed by getName()
    // at construction time, not by the mutated entity property).
    session.begin();
    session.execute("update OSchedule set name = 'evt-name-new' where name = 'evt-name-orig'")
        .close();
    session.commit();

    assertSame("registry must continue to hold the event under its original name "
        + "even after a name-change attempt",
        beforeUpdate, impl.getEvent("evt-name-orig"));
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
  // Concurrency: removeEventInternal must observe ConcurrentHashMap atomicity
  // when called from multiple threads on disjoint event names.
  // ---------------------------------------------------------------------------

  @Test
  public void concurrentRemoveEventInternalOnDisjointEventNamesProducesConsistentEmptyRegistry()
      throws Exception {
    // Pre-build N events on the main thread (sequential, so no DB-level concurrency
    // conflicts on metadata records). Then spawn N worker threads, each calling
    // removeEventInternal on its own event name. The registry uses ConcurrentHashMap so
    // remove() is thread-safe; this pin asserts the contract by checking that after the
    // joinAll every name is gone and every event's timer has been cancelled (interrupt()
    // was called). A regression that swaps ConcurrentHashMap for a plain HashMap would
    // either drop entries silently or fail under contention.
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
      var futures = new java.util.ArrayList<Future<?>>(threadCount);
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

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private ScheduledEvent buildEvent(String name, String rule, Function function,
      Map<Object, Object> args) {
    return session.computeInTx(transaction -> new ScheduledEventBuilder()
        .setName(name)
        .setRule(rule)
        .setFunction(function)
        .setArguments(args == null ? new HashMap<>() : new HashMap<>(args))
        .build(session));
  }

  /**
   * Reflectively reads the private {@code timer} field of a {@link ScheduledEvent} so
   * tests can pin the field's nullability after {@code interrupt()} or {@code close()}.
   * The field is volatile and assigned only inside {@code timerLock}; we read it on the
   * test thread, which is sound for assertion purposes because the operations under
   * test (interrupt / close / cancel) all complete on the test thread before the
   * read.
   */
  private static ScheduledFuture<?> readTimerField(ScheduledEvent event) throws Exception {
    Field field = ScheduledEvent.class.getDeclaredField("timer");
    field.setAccessible(true);
    return (ScheduledFuture<?>) field.get(event);
  }
}
