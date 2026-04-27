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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import java.util.HashMap;
import org.junit.After;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * Dead-code pin tests for the {@link Scheduler} interface's deprecated zero-argument methods
 * — {@code load()}, {@code close()}, and {@code create()} — and their {@link SchedulerProxy}
 * implementations. A cross-module grep over {@code core/main}, {@code server/}, {@code driver/},
 * {@code embedded/}, {@code gremlin-annotations/}, and {@code tests/} performed during this
 * track's review phase confirmed zero callers reach these methods through the {@code Scheduler}
 * interface: the only live load/close/create call sites all go through {@link SchedulerImpl}
 * directly (which does not implement {@code Scheduler}) — {@code SchedulerImpl#load(session)}
 * and {@code SchedulerImpl#close()} are reached via {@code SharedContext}. The deprecated
 * zero-argument trio on the {@code Scheduler} interface (and its {@code SchedulerProxy}
 * overrides, including the no-op {@code close()}) has zero callers anywhere in the codebase.
 *
 * <p>Each test pins a falsifiable behavioral observable so a future deletion that removes
 * one of these methods will fail compilation here (the proxy method signatures themselves
 * become unreachable) and a regression that turns one of them into a no-op will fail at
 * runtime — that is the loud signal we want for the final sweep.
 *
 * <p>WHEN-FIXED: delete the following from {@link Scheduler} (zero production callers
 * confirmed) and their corresponding {@link SchedulerProxy} overrides — {@code load()},
 * {@code close()}, {@code create()}. The interface's other surface
 * ({@code scheduleEvent}/{@code removeEvent}/{@code updateEvent}/{@code getEvents}/
 * {@code getEvent}) is live and stays.
 *
 * <p>This class is tagged {@link SequentialTest} because it touches the JVM-wide scheduled
 * pool through {@link SchedulerImpl}; the {@code @After} cleanup explicitly removes any
 * registered events to prevent cross-test pollution under {@code <parallel>classes</parallel>}.
 */
@Category(SequentialTest.class)
public class SchedulerSurfaceDeadCodeTest extends DbTestBase {

  private static final String FAR_FUTURE_RULE = SchedulerTestFixtures.FAR_FUTURE_RULE;

  @After
  public void cleanRegisteredEvents() {
    SchedulerTestFixtures.removeAllRegisteredEvents(session);
  }

  // ---------------------------------------------------------------------------
  // Scheduler.create() — deprecated proxy entry point delegates to SchedulerImpl.create
  // ---------------------------------------------------------------------------

  @Test
  public void deprecatedCreateOnProxyDelegatesToSchedulerImplAndRecreatesScheduleClass() {
    // The proxy's create() override calls SchedulerImpl.create(session). To pin that the
    // delegation actually runs (and would catch a regression that turns the proxy method
    // into a no-op), we drop the OSchedule class first so create()'s early-return guard
    // (existsClass check) does NOT short-circuit. After the call, the schema must contain
    // OSchedule again with the mandatory PROP_NAME property — configured by
    // SchedulerImpl.create's body.
    var schema = session.getMetadata().getSchema();
    assertTrue("OSchedule class must exist before the test starts (set up by SharedContext)",
        schema.existsClass(ScheduledEvent.CLASS_NAME));

    // Schema mutations are not transactional in YouTrackDB; drop the class outside any
    // active transaction to avoid the "Cannot change the schema while a transaction is
    // active" guard.
    schema.dropClass(ScheduledEvent.CLASS_NAME);
    assertFalse("precondition: OSchedule class is gone before create() is called",
        schema.existsClass(ScheduledEvent.CLASS_NAME));

    var scheduler = session.getMetadata().getScheduler();
    @SuppressWarnings("deprecation")
    Runnable invoke = scheduler::create;
    invoke.run();

    assertTrue("OSchedule class must be re-created by deprecated create()",
        schema.existsClass(ScheduledEvent.CLASS_NAME));
    var cls = schema.getClass(ScheduledEvent.CLASS_NAME);
    assertNotNull("PROP_NAME property must be created by SchedulerImpl.create",
        cls.getProperty(ScheduledEvent.PROP_NAME));
  }

  @Test
  public void deprecatedCreateOnProxyIsNoOpWhenScheduleClassAlreadyExists() {
    // Symmetric pin: the early-return path of SchedulerImpl.create is the most common
    // execution path on a healthy database. Confirm that calling create() when OSchedule
    // already exists is a no-op — no exception, and the class continues to expose the
    // mandatory PROP_NAME property without a duplicate creation attempt corrupting it.
    var schema = session.getMetadata().getSchema();
    assertTrue("OSchedule class must exist before the test starts",
        schema.existsClass(ScheduledEvent.CLASS_NAME));

    var scheduler = session.getMetadata().getScheduler();
    @SuppressWarnings("deprecation")
    Runnable invoke = scheduler::create;
    invoke.run();

    assertTrue("OSchedule class must still exist after the early-return create() call",
        schema.existsClass(ScheduledEvent.CLASS_NAME));
    assertNotNull("PROP_NAME property must remain on the OSchedule class",
        schema.getClass(ScheduledEvent.CLASS_NAME).getProperty(ScheduledEvent.PROP_NAME));
  }

  // ---------------------------------------------------------------------------
  // Scheduler.close() — deprecated proxy method is documented as "DO NOTHING"
  // ---------------------------------------------------------------------------

  @Test
  public void deprecatedCloseOnProxyDoesNotCancelRegisteredEventsOrCloseDelegate() {
    // The SchedulerProxy#close override is a no-op (the comment in the source explicitly says
    // "DO NOTHING THE DELEGATE CLOSE IS MANAGED IN A DIFFERENT CONTEXT"). After registering
    // an event and calling proxy.close(), the event must still be retrievable via the proxy's
    // live getEvent / getEvents calls — and the same ScheduledEvent instance, not a
    // re-registered replacement — proving the underlying SchedulerImpl was not closed.
    var function = SchedulerTestFixtures.createTrivialFunction(session, "fnDeadClose");
    session.executeInTx(transaction -> new ScheduledEventBuilder()
        .setName("evt-close")
        .setRule(FAR_FUTURE_RULE)
        .setFunction(function)
        .setArguments(new HashMap<>())
        .build(session));

    var scheduler = session.getMetadata().getScheduler();
    var before = scheduler.getEvent("evt-close");
    assertNotNull("event must be registered after save (auto-schedule hook)", before);

    @SuppressWarnings("deprecation")
    Runnable invoke = scheduler::close;
    invoke.run();

    var after = scheduler.getEvent("evt-close");
    assertNotNull("deprecated proxy close() must not unregister events", after);
    assertSame("close() must not replace the registered ScheduledEvent instance",
        before, after);
    assertTrue("registry must contain the registered event by name",
        scheduler.getEvents().containsKey("evt-close"));
  }

  // ---------------------------------------------------------------------------
  // Scheduler.load() — deprecated proxy method delegates to SchedulerImpl.load(session)
  // ---------------------------------------------------------------------------

  @Test
  public void deprecatedLoadOnProxyRebuildsRegistryFromPersistedScheduleRows() {
    // SchedulerProxy#load delegates to SchedulerImpl#load(session) which scans every
    // OSchedule row, constructs a fresh ScheduledEvent from the entity, and re-registers
    // it via scheduleEvent. Pin the actual re-registration: forcibly clear the in-memory
    // registry (without deleting the DB row), then invoke load() and confirm the event
    // re-appears. This exercises the load-from-DB-into-empty-registry path that the
    // putIfAbsent guard in scheduleEvent would otherwise short-circuit if the registry
    // were left intact from the auto-schedule hook.
    var function = SchedulerTestFixtures.createTrivialFunction(session, "fnDeadLoad");
    session.executeInTx(transaction -> new ScheduledEventBuilder()
        .setName("evt-load")
        .setRule(FAR_FUTURE_RULE)
        .setFunction(function)
        .setArguments(new HashMap<>())
        .build(session));

    var scheduler = session.getMetadata().getScheduler();
    var impl = session.getSharedContext().getScheduler();
    // Drop the in-memory registration WITHOUT deleting the OSchedule entity (the DB row
    // remains for load() to discover). removeEventInternal also calls event.interrupt(),
    // which cancels the timer queued by the auto-schedule hook.
    var dropped = impl.removeEventInternal("evt-load");
    assertNotNull("event must have been registered before drop", dropped);
    assertNull("registry must be empty after removeEventInternal",
        scheduler.getEvent("evt-load"));

    @SuppressWarnings("deprecation")
    Runnable invoke = scheduler::load;
    invoke.run();

    // Pin: load() reads the OSchedule row from the DB and repopulates the registry.
    var reloaded = scheduler.getEvent("evt-load");
    assertNotNull("deprecated proxy load() must re-register events from the database",
        reloaded);
    assertEquals("evt-load", reloaded.getName());
  }

  @Test
  public void deprecatedLoadOnProxyIsIdempotentWhenEventIsAlreadyRegistered() {
    // The putIfAbsent guard in SchedulerImpl#scheduleEvent prevents load() from creating a
    // duplicate registration when the event is already in the registry (e.g., from the
    // auto-schedule hook fired during the original save). Pin idempotence: calling load()
    // a second time over an already-populated registry must not change identity or count.
    var function = SchedulerTestFixtures.createTrivialFunction(session, "fnDeadLoadIdem");
    session.executeInTx(transaction -> new ScheduledEventBuilder()
        .setName("evt-load-idem")
        .setRule(FAR_FUTURE_RULE)
        .setFunction(function)
        .setArguments(new HashMap<>())
        .build(session));

    var scheduler = session.getMetadata().getScheduler();
    var before = scheduler.getEvent("evt-load-idem");
    assertNotNull(before);
    int sizeBefore = scheduler.getEvents().size();

    @SuppressWarnings("deprecation")
    Runnable invoke = scheduler::load;
    invoke.run();

    assertSame("idempotent load must not replace the existing registration",
        before, scheduler.getEvent("evt-load-idem"));
    assertEquals("idempotent load must not duplicate the registry entry",
        sizeBefore, scheduler.getEvents().size());
  }
}
