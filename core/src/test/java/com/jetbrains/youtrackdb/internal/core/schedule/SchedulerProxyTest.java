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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

import com.jetbrains.youtrackdb.api.exception.RecordNotFoundException;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import java.util.HashMap;
import java.util.Map;
import org.junit.After;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * Live-surface delegation parity tests for {@link SchedulerProxy}. The proxy is the public
 * consumer surface returned by {@code session.getMetadata().getScheduler()}, while
 * {@link SchedulerImpl} is the underlying implementation reached via
 * {@code session.getSharedContext().getScheduler()}. The proxy's three state-mutating live
 * methods — {@code scheduleEvent} / {@code removeEvent} / {@code updateEvent} — are
 * referenced explicitly here so a regression that turned any of them into a no-op (or a
 * deletion that broke the {@code Scheduler} interface contract) would flip these tests
 * red. The deprecated {@code load} / {@code close} / {@code create} variants are pinned
 * separately in {@link SchedulerSurfaceDeadCodeTest}.
 *
 * <p>This class is tagged {@link SequentialTest} because each test touches the JVM-wide
 * scheduled pool through {@link SchedulerImpl} (auto-schedule hooks, queued
 * {@code ScheduledFuture} cancellation). The {@code @After} cleanup explicitly removes
 * any registered events to prevent cross-test pollution under
 * {@code <parallel>classes</parallel>}.
 */
@Category(SequentialTest.class)
public class SchedulerProxyTest extends DbTestBase {

  private static final String FAR_FUTURE_RULE = SchedulerTestFixtures.FAR_FUTURE_RULE;

  @After
  public void cleanRegisteredEvents() {
    SchedulerTestFixtures.removeAllRegisteredEvents(session);
  }

  @Test
  public void proxyScheduleEventDelegatesToImplAndRegistersByName() {
    // The auto-schedule hook on save() registers via SchedulerImpl directly, so to pin the
    // proxy's scheduleEvent path specifically: build the event, then drop the in-memory
    // registration (without deleting the DB row), then invoke the proxy's scheduleEvent
    // and assert the registry is repopulated. A regression that turned the proxy method
    // into a no-op would leave the registry empty.
    var function = SchedulerTestFixtures.createTrivialFunction(session, "fnProxySched");
    var event = SchedulerTestFixtures.buildEvent(session, "evt-proxy-sched", FAR_FUTURE_RULE,
        function, Map.of());
    var impl = session.getSharedContext().getScheduler();
    impl.removeEventInternal("evt-proxy-sched");
    assertNull("registry must be empty before proxy scheduleEvent",
        impl.getEvent("evt-proxy-sched"));

    var scheduler = session.getMetadata().getScheduler();
    scheduler.scheduleEvent(session, event);

    assertSame("proxy scheduleEvent must register via the delegate's scheduleEvent path",
        event, scheduler.getEvent("evt-proxy-sched"));
  }

  @Test
  public void proxyRemoveEventDelegatesToImplAndDropsRegistrationAndPersistedRow() {
    // Removal goes through removeEvent -> removeEventInternal + entity.delete inside an
    // executeInTx. After proxy.removeEvent: (a) the registry entry is gone, (b) the
    // OSchedule row is gone. A regression that turned the proxy method into a no-op
    // would leave the registry populated and the row in the DB.
    var function = SchedulerTestFixtures.createTrivialFunction(session, "fnProxyRm");
    var event = SchedulerTestFixtures.buildEvent(session, "evt-proxy-rm", FAR_FUTURE_RULE,
        function, Map.of());
    var rid = event.getIdentity();
    var scheduler = session.getMetadata().getScheduler();
    assertNotNull("event must be registered after build", scheduler.getEvent("evt-proxy-rm"));

    scheduler.removeEvent(session, "evt-proxy-rm");

    assertNull("proxy removeEvent must drop the in-memory registration",
        scheduler.getEvent("evt-proxy-rm"));
    // Verify the persisted row was deleted by attempting a load — a fresh tx is used so the
    // session's caches do not mask the absence of the row. loadEntity throws
    // RecordNotFoundException rather than returning null when the underlying row is gone.
    session.begin();
    try {
      assertThrows("proxy removeEvent must delete the persisted OSchedule row",
          RecordNotFoundException.class, () -> session.loadEntity(rid));
    } finally {
      session.rollback();
    }
  }

  @Test
  public void proxyUpdateEventDelegatesToImplAndReplacesRegistrationWithNewInstance() {
    // updateEvent interrupts the prior registration and replaces the registry entry with the
    // event passed in. Build a fresh ScheduledEvent over the same entity (same name) to
    // simulate a programmatic update, then invoke the proxy's updateEvent and assert the
    // registry now points at the new instance.
    var function = SchedulerTestFixtures.createTrivialFunction(session, "fnProxyUpd");
    var firstEvent = SchedulerTestFixtures.buildEvent(session, "evt-proxy-upd", FAR_FUTURE_RULE,
        function, Map.of());
    var scheduler = session.getMetadata().getScheduler();
    var registeredBefore = scheduler.getEvent("evt-proxy-upd");
    assertNotNull(registeredBefore);

    // Construct a second ScheduledEvent over the same entity — the auto-schedule hook gives
    // us the dual-instance shape (builder-returned vs registered) we need; here we do the
    // converse: build a NEW ScheduledEvent from the same persisted entity. The reload is
    // wrapped in computeInTx because session.loadEntity requires an active transaction.
    // The ctor swallows ParseException internally, so its construction is unchecked.
    ScheduledEvent replacement = session.computeInTx(tx -> {
      var entity = (com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl) session
          .loadEntity(firstEvent.getIdentity());
      return new ScheduledEvent(entity, session);
    });

    scheduler.updateEvent(session, replacement);

    assertSame("proxy updateEvent must replace the registry entry with the new instance",
        replacement, scheduler.getEvent("evt-proxy-upd"));
    assertEquals("registration size must remain 1 after replacement",
        1, scheduler.getEvents().size());
  }

  @Test
  public void proxyGetEventsDelegatesToImplLiveMap() {
    // Same-reference parity between the proxy's view and the impl's view — the proxy is a
    // pass-through, so a regression that wrapped the impl's map (e.g.
    // Collections.unmodifiableMap) would still return a stable reference per call but would
    // diverge from impl.getEvents(). Pinning reference identity catches that divergence.
    var impl = session.getSharedContext().getScheduler();
    var scheduler = session.getMetadata().getScheduler();
    assertSame("proxy getEvents must return the impl's live map by reference",
        impl.getEvents(), scheduler.getEvents());
  }

  @Test
  public void proxyGetEventByNameDelegatesAndReturnsTheRegisteredInstance() {
    var function = SchedulerTestFixtures.createTrivialFunction(session, "fnProxyGet");
    SchedulerTestFixtures.buildEvent(session, "evt-proxy-get", FAR_FUTURE_RULE, function,
        new HashMap<>());
    var impl = session.getSharedContext().getScheduler();
    var scheduler = session.getMetadata().getScheduler();
    assertSame("proxy getEvent must return the same instance the impl has registered",
        impl.getEvent("evt-proxy-get"), scheduler.getEvent("evt-proxy-get"));
    assertNull("proxy getEvent must propagate impl's null for unknown names",
        scheduler.getEvent("evt-proxy-not-registered"));
  }
}
