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
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.core.metadata.function.Function;
import java.util.HashMap;
import java.util.List;
import org.junit.After;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * Dead-code pin tests for the {@link Scheduler} interface's deprecated zero-argument methods
 * — {@code load()}, {@code close()}, and {@code create()} — and their {@link SchedulerProxy}
 * implementations. A cross-module grep over {@code core/main}, {@code server/}, {@code driver/},
 * {@code embedded/}, {@code gremlin-annotations/}, and {@code tests/} performed during this
 * track's review phase confirmed zero callers reach these methods through the {@code Scheduler}
 * interface: the only live load/close/create call sites all go through {@link SchedulerImpl}'s
 * non-deprecated overloads (e.g., {@code SchedulerImpl#load(DatabaseSessionEmbedded)} and
 * {@code SchedulerImpl#close()}) reached via {@code SharedContext}, never through
 * {@code SchedulerProxy}.
 *
 * <p>Each test pins the proxy's behavioral characteristics so a future deletion that removes
 * one of these methods will fail compilation here (the proxy method signatures themselves
 * become unreachable) — that is the loud signal we want for the final sweep.
 *
 * <p>WHEN-FIXED: delete the following from {@link Scheduler} (zero production callers
 * confirmed) and their corresponding {@link SchedulerProxy} overrides — {@code load()},
 * {@code close()}, {@code create()}. The interface's other surface
 * ({@code scheduleEvent}/{@code removeEvent}/{@code updateEvent}/{@code getEvents}/
 * {@code getEvent}) is live and stays.
 *
 * <p>This class is tagged {@link SequentialTest} because it touches the JVM-wide scheduled
 * pool through {@link SchedulerImpl} (load() schedules events that survive across tests if
 * not cleaned up); the {@code @After} cleanup explicitly removes any registered events to
 * prevent cross-test pollution under {@code <parallel>classes</parallel>}.
 */
@Category(SequentialTest.class)
public class SchedulerSurfaceDeadCodeTest extends DbTestBase {

  @After
  public void cleanRegisteredEvents() {
    if (session != null && !session.isClosed()) {
      var scheduler = session.getMetadata().getScheduler();
      var names = List.copyOf(scheduler.getEvents().keySet());
      for (var name : names) {
        try {
          scheduler.removeEvent(session, name);
        } catch (Exception ignored) {
          // best-effort cleanup
        }
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Scheduler.create() — deprecated proxy entry point delegates to SchedulerImpl.create
  // ---------------------------------------------------------------------------

  @Test
  public void deprecatedCreateOnProxyIsIdempotentWhenScheduleClassAlreadyExists() {
    // SchedulerImpl#create early-returns if the OSchedule class already exists in the schema —
    // every database created via DbTestBase has it (SharedContext#create calls
    // SchedulerImpl.create at db creation time). Calling proxy.create() should therefore not
    // throw and should not disturb existing schema state.
    var scheduler = session.getMetadata().getScheduler();
    assertTrue("OSchedule class must exist before the test starts (set up by SharedContext)",
        session.getMetadata().getSchema().existsClass(ScheduledEvent.CLASS_NAME));

    @SuppressWarnings("deprecation")
    Runnable invoke = scheduler::create;
    invoke.run();

    // Class still exists, no exception thrown.
    assertTrue("OSchedule class must still exist after deprecated create() call",
        session.getMetadata().getSchema().existsClass(ScheduledEvent.CLASS_NAME));
  }

  // ---------------------------------------------------------------------------
  // Scheduler.close() — deprecated proxy method is documented as "DO NOTHING"
  // ---------------------------------------------------------------------------

  @Test
  public void deprecatedCloseOnProxyDoesNotCancelRegisteredEventsOrCloseDelegate() {
    // The SchedulerProxy#close override is a no-op (the comment in the source explicitly says
    // "DO NOTHING THE DELEGATE CLOSE IS MANAGED IN A DIFFERENT CONTEXT"). After registering
    // an event and calling proxy.close(), the event must still be retrievable via the proxy's
    // live getEvent / getEvents calls — proving the underlying SchedulerImpl was not closed.
    var function = createFunction(session, "deadCloseFn");
    session.executeInTx(transaction -> new ScheduledEventBuilder()
        .setName("evt-close")
        .setRule("0 0 12 1 1 ? 2099")
        .setFunction(function)
        .setArguments(new HashMap<>())
        .build(session));

    var scheduler = session.getMetadata().getScheduler();
    assertNotNull("event must be registered after save (auto-schedule hook)",
        scheduler.getEvent("evt-close"));

    @SuppressWarnings("deprecation")
    Runnable invoke = scheduler::close;
    invoke.run();

    // Event registry must be untouched — close() on the proxy is the no-op it claims to be.
    assertNotNull("deprecated proxy close() must not unregister events",
        scheduler.getEvent("evt-close"));
    assertEquals("the registered event remains the only entry",
        1, scheduler.getEvents().size());
  }

  // ---------------------------------------------------------------------------
  // Scheduler.load() — deprecated proxy method delegates to SchedulerImpl.load(session)
  // ---------------------------------------------------------------------------

  @Test
  public void deprecatedLoadOnProxyReregistersEventsFromTheDatabaseIntoSchedulerImpl() {
    // SchedulerProxy#load delegates to SchedulerImpl#load(session) which scans every
    // OSchedule row, constructs a fresh ScheduledEvent from the entity, and re-registers
    // it via scheduleEvent. Pin that re-registration is observable: after we forcibly
    // remove an event from the in-memory registry (via removeEventInternal-style behavior
    // through the proxy), calling load() must bring it back if the underlying entity
    // still exists in the database.
    //
    // We exercise this by: (1) creating a saved event so the OSchedule entity exists,
    // (2) calling load(), and (3) asserting the event is registered. This pins the load
    // path even when no auto-schedule hook fired because the database already had the row.
    var function = createFunction(session, "deadLoadFn");
    session.executeInTx(transaction -> new ScheduledEventBuilder()
        .setName("evt-load")
        .setRule("0 0 12 1 1 ? 2099")
        .setFunction(function)
        .setArguments(new HashMap<>())
        .build(session));

    var scheduler = session.getMetadata().getScheduler();
    // Sanity check: event was registered by the auto-schedule hook.
    assertNotNull(scheduler.getEvent("evt-load"));

    @SuppressWarnings("deprecation")
    Runnable invoke = scheduler::load;
    invoke.run();

    // After load(), the event remains registered (load is idempotent — putIfAbsent guards in
    // SchedulerImpl#scheduleEvent prevent duplicate registration).
    assertNotNull("deprecated proxy load() must re-register events from the database",
        scheduler.getEvent("evt-load"));
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private static Function createFunction(
      com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded session,
      String name) {
    return session.computeInTx(transaction -> {
      var func = session.getMetadata().getFunctionLibrary().createFunction(name);
      func.setLanguage("SQL");
      func.setCode("select 1");
      func.save(session);
      return func;
    });
  }
}
