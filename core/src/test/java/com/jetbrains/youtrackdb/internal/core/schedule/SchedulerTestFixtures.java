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
import java.util.List;

/**
 * Package-private fixtures shared by {@link ScheduledEventTest} and
 * {@link SchedulerSurfaceDeadCodeTest}.
 *
 * <p>The pre-existing {@link SchedulerTest} uses richer log-style functions and its own
 * {@code YouTrackDBImpl}-based setup, so it is intentionally not migrated to these helpers —
 * sharing across the two new {@code DbTestBase}-derived classes is the goal here.
 */
final class SchedulerTestFixtures {

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
