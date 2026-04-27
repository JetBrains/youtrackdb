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

import static com.jetbrains.youtrackdb.internal.core.schedule.ScheduledEvent.PROP_ARGUMENTS;
import static com.jetbrains.youtrackdb.internal.core.schedule.ScheduledEvent.PROP_FUNC;
import static com.jetbrains.youtrackdb.internal.core.schedule.ScheduledEvent.PROP_NAME;
import static com.jetbrains.youtrackdb.internal.core.schedule.ScheduledEvent.PROP_RULE;
import static com.jetbrains.youtrackdb.internal.core.schedule.ScheduledEvent.PROP_STATUS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.core.db.tool.DatabaseExportException;
import com.jetbrains.youtrackdb.internal.core.metadata.function.Function;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.schedule.Scheduler.STATUS;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * DB-backed tests for {@link ScheduledEvent}: persistence round-trip, status enum surface,
 * the silent-{@link java.text.ParseException} swallow in the constructor, monotonicity of the
 * private {@code nextExecutionId} counter, and the unsaved-event guard in
 * {@link ScheduledEvent#schedule}.
 *
 * <p>This class is tagged {@link SequentialTest} because every test that creates an event
 * touches the JVM-wide scheduled pool through {@code YouTrackDBEnginesManager.scheduledPool}
 * (a 2-thread executor shared with direct-memory eviction) — running these in parallel with
 * other DB-backed tests under {@code <parallel>classes</parallel>} risks cross-test pollution
 * via leaked timer tasks. Far-future cron rules ({@code "0 0 12 1 1 ? 2099"}) keep the
 * scheduled timers from firing during the test JVM's lifetime, and explicit
 * {@link ScheduledEvent#interrupt()} calls cancel each timer before the next
 * {@link ScheduledEvent#schedule} overwrites it (without {@code interrupt}, the
 * old {@code timer} reference is lost and the prior task remains in the pool's queue).
 *
 * <p>All event creation goes through the production {@link ScheduledEventBuilder#build}
 * inside a transaction so the {@code initScheduleRecord} and after-commit
 * {@code scheduleEvent} hooks fire — that is the realistic save path. Two tests bypass the
 * builder by constructing the entity directly: the {@code malformedCron} pin (so the
 * constructor's silent {@link java.text.ParseException} swallow is observable in isolation,
 * without inviting an after-commit hook NPE that would also leave a half-constructed event
 * in the {@link SchedulerImpl} registry) and the {@code unsavedEvent} guard (so we can
 * reach the {@code !isPersistent()} branch of {@link ScheduledEvent#schedule}).
 */
@Category(SequentialTest.class)
public class ScheduledEventTest extends DbTestBase {

  /**
   * Far-future cron rule. The expression parses as a single firing time at noon on
   * 1 January 2099 — the corresponding scheduled timer therefore never fires within the
   * test JVM's lifetime, which keeps the JVM-wide scheduled pool quiet under
   * {@link SequentialTest}. (The cron's year-list parser exhausts after one match;
   * subsequent {@code getNextValidTimeAfter} calls return {@code null}, but that path is
   * not exercised here — every test cancels its timer via {@link ScheduledEvent#interrupt}
   * before the rule's only firing time is reached.)
   */
  private static final String FAR_FUTURE_RULE = "0 0 12 1 1 ? 2099";

  @After
  public void interruptDanglingEvents() {
    // Defense-in-depth: if a test left an event registered in SchedulerImpl, drop it via the
    // public API before DbTestBase#afterTest tears down the database. SchedulerImpl#close
    // (called via SharedContext#close in YouTrackDB#close) already cancels all registered
    // events, but doing it here keeps test output focused on the test under inspection
    // rather than the close-time cancellation cascade.
    if (session != null && !session.isClosed()) {
      var scheduler = session.getMetadata().getScheduler();
      var eventNames = List.copyOf(scheduler.getEvents().keySet());
      for (var name : eventNames) {
        try {
          scheduler.removeEvent(session, name);
        } catch (Exception ignored) {
          // Event entity may already be deleted (e.g., as part of the test); ignore.
        }
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Persistence round-trip — build, save in tx, reload, inspect each property
  // ---------------------------------------------------------------------------

  @Test
  public void buildPersistsAllPropertiesAndAfterReloadEntityKeepsName() {
    var function = createFunction(session, "logEvent1");
    var event = buildEvent("evt-name", FAR_FUTURE_RULE, function, Map.of("note", "hello"));
    assertNotNull("builder must return a non-null ScheduledEvent", event);
    assertTrue("ScheduledEvent must have a persistent RID after the surrounding tx commit",
        event.getIdentity().isPersistent());

    // Reload inside a fresh transaction — DB read operations require an active tx.
    String reloadedName =
        session.computeInTx(transaction -> ((EntityImpl) session.loadEntity(event.getIdentity()))
            .getProperty(PROP_NAME));
    assertEquals("evt-name", reloadedName);
  }

  @Test
  public void reloadedEntityKeepsRuleAndArgumentsAndFunctionLink() {
    var function = createFunction(session, "logEvent2");
    var args = new HashMap<Object, Object>();
    args.put("note", "hi");
    args.put("count", 42);
    var event = buildEvent("evt-payload", FAR_FUTURE_RULE, function, args);

    session.computeInTx(transaction -> {
      var reloaded = (EntityImpl) session.loadEntity(event.getIdentity());
      assertEquals(FAR_FUTURE_RULE, reloaded.getProperty(PROP_RULE));
      Map<String, Object> reloadedArgs = reloaded.getProperty(PROP_ARGUMENTS);
      assertEquals("hi", reloadedArgs.get("note"));
      assertEquals(42, ((Number) reloadedArgs.get("count")).intValue());
      // The function link is stored as the function's RID (see ScheduledEvent#toEntity);
      // reloaded entity exposes it via getProperty as a link. Pin that the link round-trips
      // (non-null reference back to the saved function record).
      Object funcLink = reloaded.getProperty(PROP_FUNC);
      assertNotNull("function link must round-trip", funcLink);
      return null;
    });
  }

  @Test
  public void initScheduleRecordHookSetsStoppedStatusOnNewlyCreatedEntity() {
    // The DatabaseSessionEmbedded#beforeCreateOperations hook calls
    // SchedulerImpl#initScheduleRecord which sets PROP_STATUS to STATUS.STOPPED.name() before
    // commit. Verify the persisted status string matches the enum value.
    var function = createFunction(session, "logEvent3");
    var event = buildEvent("evt-status", FAR_FUTURE_RULE, function, Map.of());

    Object status =
        session.computeInTx(transaction -> ((EntityImpl) session.loadEntity(event.getIdentity()))
            .getProperty(PROP_STATUS));
    // The hook stores the .name() string of the enum.
    assertEquals(STATUS.STOPPED.name(), status);
  }

  // ---------------------------------------------------------------------------
  // STATUS enum — surface stability (string values are persisted by name)
  // ---------------------------------------------------------------------------

  @Test
  public void statusEnumExposesExactlyThreeValuesNamedRunningStoppedAndWaiting() {
    // The persistence layer stores STATUS values by their name() string (see toEntity and
    // initScheduleRecord). A rename of any constant would silently break read of pre-rename
    // OSchedule rows in existing DBs. Pin the exact literal names so the rename is loud.
    var values = STATUS.values();
    assertEquals("STATUS must have exactly three constants", 3, values.length);
    assertEquals("RUNNING", STATUS.RUNNING.name());
    assertEquals("STOPPED", STATUS.STOPPED.name());
    assertEquals("WAITING", STATUS.WAITING.name());
  }

  @Test
  public void statusEnumValueOfRoundTripsByName() {
    // Every persisted PROP_STATUS string round-trips through STATUS.valueOf in
    // ScheduledEvent's constructor (line "status = STATUS.valueOf(statusValue)").
    // Pin that round-trip is identity for each constant.
    for (var s : STATUS.values()) {
      assertEquals(s, STATUS.valueOf(s.name()));
    }
  }

  // ---------------------------------------------------------------------------
  // Production bug pin: silent ParseException swallow in the constructor
  // ---------------------------------------------------------------------------

  @Test
  public void scheduledEventConstructorSilentlySwallowsMalformedCronAndLeavesCronFieldNull()
      throws Exception {
    // Production-bug pin: ScheduledEvent ctor at the cron-compile site catches ParseException
    // without rethrowing and leaves the private 'cron' field null. The downstream consequence
    // is that ScheduledTimerTask#schedule (called from ScheduledEvent#schedule and from the
    // after-commit auto-schedule hook in DatabaseSessionEmbedded#afterCommitOperations) NPEs
    // on cron.getNextValidTimeAfter when the saved entity is reloaded later.
    //
    // We populate a transient entity inside an explicit begin/rollback so the OSchedule row
    // is never committed — that way the after-commit auto-schedule hook (which would also
    // construct a ScheduledEvent and immediately call schedule() → NPE on the null cron) does
    // not fire. This isolates the pin to the constructor's swallow behavior.
    //
    // WHEN-FIXED: this test will fail when the constructor either rethrows the ParseException
    // (ideally as a runtime exception) or rejects the entity earlier in the save pipeline,
    // turning the late NPE into an early, readable error.
    var function = createFunction(session, "logEvent4");

    var tx = session.begin();
    ScheduledEvent event;
    try {
      EntityImpl entity = (EntityImpl) session.newEntity(ScheduledEvent.CLASS_NAME);
      entity.setProperty(PROP_NAME, "bad-cron");
      entity.setProperty(PROP_RULE, "this is not a cron expression");
      entity.setProperty(PROP_FUNC, function.getIdentity());
      event = new ScheduledEvent(entity, session);
      // Constructor must NOT have thrown — the ParseException is silently logged.
      assertEquals("rule string round-trips intact", "this is not a cron expression",
          event.getRule());
    } finally {
      // Discard the entity: rollback prevents the OSchedule row from reaching commit, so the
      // after-commit auto-schedule hook does not fire on this transient row.
      tx.rollback();
    }

    // Pin the silent-swallow observable: the private cron field is null after construction.
    Field cronField = ScheduledEvent.class.getDeclaredField("cron");
    cronField.setAccessible(true);
    assertNull("cron field must be null after silently-swallowed ParseException",
        cronField.get(event));
  }

  // ---------------------------------------------------------------------------
  // schedule() guard — unsaved event is rejected before any timer is created
  // ---------------------------------------------------------------------------

  @Test
  public void scheduleOfUnpersistedEventThrowsDatabaseExportExceptionBeforeReachingPool() {
    // ScheduledEvent#schedule rejects events whose identity is non-persistent via the
    // "Cannot schedule an unsaved event" check. We populate a transient entity inside an
    // explicit begin/rollback so its identity stays non-persistent (committing would assign a
    // persistent RID and the auto-schedule hook would fire), then verify the guard fires
    // before ScheduledTimerTask#schedule reaches the JVM-wide pool — otherwise we would see
    // a NullPointerException from cron.getNextValidTimeAfter rather than the readable
    // DatabaseExportException.
    var function = createFunction(session, "logEvent5");

    var tx = session.begin();
    ScheduledEvent event;
    try {
      EntityImpl entity = (EntityImpl) session.newEntity(ScheduledEvent.CLASS_NAME);
      entity.setProperty(PROP_NAME, "unsaved");
      entity.setProperty(PROP_RULE, FAR_FUTURE_RULE);
      entity.setProperty(PROP_FUNC, function.getIdentity());
      event = new ScheduledEvent(entity, session);
    } finally {
      tx.rollback();
    }

    try {
      event.schedule(databaseName, "admin", session.getSharedContext().getYouTrackDB());
      fail("schedule() must reject an event with a non-persistent identity");
    } catch (DatabaseExportException expected) {
      assertTrue("rejection message must mention the unsaved-event reason: <"
          + expected.getMessage() + ">",
          expected.getMessage().contains("Cannot schedule an unsaved event"));
    }
  }

  // ---------------------------------------------------------------------------
  // nextExecutionId monotonicity — strictly increasing across schedule() calls
  // ---------------------------------------------------------------------------

  @Test
  public void scheduleIncrementsPrivateNextExecutionIdMonotonicallyAcrossRepeatedCalls()
      throws Exception {
    // Each schedule() call constructs a fresh ScheduledTimerTask whose schedule() body
    // calls nextExecutionId.incrementAndGet() before queuing the timer. Pin that successive
    // schedule() calls produce strictly increasing IDs (1, 2, 3) on the same instance.
    //
    // We use a far-future cron so the queued timers do not fire during the test, and we
    // call interrupt() between schedule() calls — without interrupt, the prior timer
    // reference is lost (schedule overwrites event.timer unconditionally), so the prior
    // pool task remains queued for year 2099. interrupt() captures the current timer,
    // nulls the field, and cancels the pool task.
    var function = createFunction(session, "logEvent6");
    var event = buildEvent("evt-monotonic", FAR_FUTURE_RULE, function, Map.of());

    Field idField = ScheduledEvent.class.getDeclaredField("nextExecutionId");
    idField.setAccessible(true);
    var atomic = (java.util.concurrent.atomic.AtomicLong) idField.get(event);
    long initial = atomic.get();

    event.schedule(databaseName, "admin", session.getSharedContext().getYouTrackDB());
    long afterFirst = atomic.get();
    event.interrupt();

    event.schedule(databaseName, "admin", session.getSharedContext().getYouTrackDB());
    long afterSecond = atomic.get();
    event.interrupt();

    event.schedule(databaseName, "admin", session.getSharedContext().getYouTrackDB());
    long afterThird = atomic.get();
    event.interrupt();

    assertEquals("first schedule increments by 1", initial + 1, afterFirst);
    assertEquals("second schedule increments by 1", initial + 2, afterSecond);
    assertEquals("third schedule increments by 1", initial + 3, afterThird);
    // Strictness — each successive ID is greater than the previous.
    assertNotEquals(afterFirst, afterSecond);
    assertNotEquals(afterSecond, afterThird);
    assertTrue(afterFirst < afterSecond && afterSecond < afterThird);
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
