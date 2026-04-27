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

import com.jetbrains.youtrackdb.api.exception.RecordNotFoundException;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.metadata.function.Function;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;

/**
 * Package-private fixtures shared by {@link ScheduledEventTest},
 * {@link SchedulerSurfaceDeadCodeTest}, and {@link SchedulerImplTest}.
 *
 * <p>The pre-existing {@link SchedulerTest} uses richer log-style functions and its own
 * {@code YouTrackDBImpl}-based setup, so it is intentionally not migrated to these helpers —
 * sharing across the new {@code DbTestBase}-derived classes is the goal here.
 */
final class SchedulerTestFixtures {

  /**
   * Far-future cron rule. The expression parses as a single firing at noon on
   * 1 January 2099 — every call to {@code getNextValidTimeAfter(now)} during the test
   * window returns that same timestamp, so any timer queued by
   * {@link SchedulerImpl#scheduleEvent} waits for a moment that does not arrive
   * before the surefire JVM exits. Tests therefore never fire the function body, and
   * each queued task is cancelled either by the test itself (interrupt / removeEvent /
   * close) or by the shared {@code @After} cleanup. Bumping the year keeps this
   * fixture sound for as long as the project ships.
   */
  static final String FAR_FUTURE_RULE = "0 0 12 1 1 ? 2099";

  /**
   * Mirror of the package-private {@code SchedulerImpl#DROPPED_EVENTS_MAP} key. Tests
   * that drive {@code onAfterEventDropped} / {@code onEventDropped} directly read or
   * seed this transaction-custom-data slot; if a future refactor renames the production
   * constant, this single mirror is the only line to update — the alternative is per-test
   * string literals that would silently miss the rename and turn invariant pins into
   * tautologies.
   */
  static final String DROPPED_EVENTS_MAP_KEY = "droppedEventsMap";

  /**
   * Mirror of the package-private {@code SchedulerImpl#RIDS_OF_EVENTS_TO_RESCHEDULE_KEY}.
   * Same rename-protection rationale as {@link #DROPPED_EVENTS_MAP_KEY}.
   */
  static final String RIDS_OF_EVENTS_TO_RESCHEDULE_KEY =
      SchedulerImpl.class.getName() + ".ridsOfEventsToReschedule";

  private SchedulerTestFixtures() {
  }

  /**
   * Creates a trivial SQL function whose body is {@code select 1}. Each scheduler test
   * needs at least one valid {@link Function} reference for {@link ScheduledEventBuilder}'s
   * {@code setFunction} setter — the function body itself is never executed in these tests
   * because every test uses far-future cron rules and explicit interrupt() / removeEvent
   * cleanup before any firing time is reached.
   */
  static Function createTrivialFunction(DatabaseSessionEmbedded session, String name) {
    return session.computeInTx(transaction -> {
      var func = session.getMetadata().getFunctionLibrary().createFunction(name);
      func.setLanguage("SQL");
      func.setCode("select 1");
      func.save(session);
      return func;
    });
  }

  /**
   * Builds and persists a {@link ScheduledEvent} using {@link ScheduledEventBuilder}
   * inside a fresh transaction. The args map is defensively copied because the builder
   * stores it by reference (see {@link ScheduledEventBuilderTest}); copying here keeps
   * each call's args independent of the literal {@code Map.of(...)} view passed in.
   *
   * <p>Returns the builder-side {@link ScheduledEvent} instance — the auto-schedule
   * after-commit hook constructs an independent registered instance over the same
   * persistent entity, so callers that need to inspect the registered instance must
   * read it from {@code SchedulerImpl#getEvent(name)}. This dual-instance invariant is
   * documented at length in the {@link ScheduledEventTest} class Javadoc.
   */
  static ScheduledEvent buildEvent(DatabaseSessionEmbedded session, String name, String rule,
      Function function, Map<Object, Object> args) {
    return session.computeInTx(transaction -> new ScheduledEventBuilder()
        .setName(name)
        .setRule(rule)
        .setFunction(function)
        .setArguments(args == null ? new HashMap<>() : new HashMap<>(args))
        .build(session));
  }

  /**
   * Reflectively reads the private {@code timer} field of a {@link ScheduledEvent} so
   * tests can pin the field's nullability after {@code interrupt()}, {@code close()},
   * or {@code removeEventInternal()}. The field is volatile and assigned only inside
   * {@code timerLock}.
   *
   * <p>Single-thread callers (sequential tests) read the field on the same thread that
   * produced the value — happens-before is trivially satisfied. Multi-thread callers
   * (e.g. concurrent register/remove tests) must establish a happens-before edge before
   * reading; either by joining on a {@code Future.get()} for the writer thread or by
   * awaiting a {@code CountDownLatch} count-down that the writer has issued. Otherwise
   * the volatile read may observe a stale value despite the volatile guarantee, because
   * the writer's effect propagates through {@code timerLock} release rather than through
   * the field's own write fence.
   */
  static ScheduledFuture<?> readTimerField(ScheduledEvent event) throws Exception {
    Field field = ScheduledEvent.class.getDeclaredField("timer");
    field.setAccessible(true);
    return (ScheduledFuture<?>) field.get(event);
  }

  /**
   * Removes every event currently registered in the {@link SchedulerImpl} for the given
   * session. Used as the {@code @After} cleanup body in scheduler tests so leaked
   * registrations from the after-commit auto-schedule hook do not survive into the
   * next test method's {@code DbTestBase} fixture.
   *
   * <p>Only {@link RecordNotFoundException} is swallowed: the event entity may already
   * have been deleted by the test under inspection, in which case {@code removeEvent}
   * throws this exception once it tries to delete the row a second time. Any other
   * exception (NPE, validation failure, etc.) propagates so a future regression in the
   * cleanup path surfaces as a test failure rather than a silent leak.
   */
  static void removeAllRegisteredEvents(DatabaseSessionEmbedded session) {
    if (session == null || session.isClosed()) {
      return;
    }
    var scheduler = session.getMetadata().getScheduler();
    var names = List.copyOf(scheduler.getEvents().keySet());
    for (var name : names) {
      try {
        scheduler.removeEvent(session, name);
      } catch (RecordNotFoundException expected) {
        // Event entity already deleted by the test under inspection; in-memory
        // registry entry is what we need to clean up — removeEventInternal already
        // handled that part before the delete attempt threw.
      }
    }
  }
}
