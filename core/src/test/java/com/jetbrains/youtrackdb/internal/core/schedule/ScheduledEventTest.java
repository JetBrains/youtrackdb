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
import static com.jetbrains.youtrackdb.internal.core.schedule.ScheduledEvent.PROP_EXEC_ID;
import static com.jetbrains.youtrackdb.internal.core.schedule.ScheduledEvent.PROP_FUNC;
import static com.jetbrains.youtrackdb.internal.core.schedule.ScheduledEvent.PROP_NAME;
import static com.jetbrains.youtrackdb.internal.core.schedule.ScheduledEvent.PROP_RULE;
import static com.jetbrains.youtrackdb.internal.core.schedule.ScheduledEvent.PROP_STARTTIME;
import static com.jetbrains.youtrackdb.internal.core.schedule.ScheduledEvent.PROP_STATUS;
import static org.junit.Assert.assertEquals;
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
import java.util.Date;
import java.util.HashMap;
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
 *
 * <p><b>Dual-instance invariant.</b> The after-commit {@code scheduleEvent} hook
 * (DatabaseSessionEmbedded#afterCommitOperations) constructs a <em>separate</em>
 * {@link ScheduledEvent} instance over the same persistent entity and registers it under
 * {@link SchedulerImpl}. The instance returned by {@link #buildEvent} has its own
 * {@code nextExecutionId} starting at zero and its own {@code timer} field. Tests that
 * operate on the builder-returned instance therefore need both per-instance
 * {@code interrupt()} calls (handled inside the test body) <em>and</em> the
 * {@code @After} {@code removeEvent} cleanup (which interrupts the registered instance).
 *
 * <p><b>Retry-loop pin deferral.</b> A "retry loop runs 10 times unconditionally" pin
 * was considered for this class but deferred. The actual observable is the 10× run of
 * {@code event.save(session)} inside the private {@code ScheduledTimerTask#executeEventFunction}
 * — the user-supplied function itself runs once per cron firing, only the save loop is
 * unconditional (no {@code break} on success, and {@code catch NeedRetryException} is
 * mis-scoped inside the lambda so it cannot reach the surrounding loop). Pinning the
 * save count from outside the class requires either reflective access to that private
 * inner-class method or a custom {@code ScheduledEvent} subclass that overrides
 * {@code save}; both options are deferred to the final-sweep cleanup that fixes the
 * underlying bug, since pinning the buggy behavior here would either under-pin (passes
 * after fix without flipping) or over-pin (test code must change shape at fix time).
 */
@Category(SequentialTest.class)
public class ScheduledEventTest extends DbTestBase {

  /**
   * Far-future cron rule mirror — see {@link SchedulerTestFixtures#FAR_FUTURE_RULE} for the
   * canonical definition and rationale (single firing at noon on 1 January 2099, queued
   * task never fires during the surefire JVM window). Re-bound here as a shorthand
   * because this class references the constant inside dozens of tests.
   */
  private static final String FAR_FUTURE_RULE = SchedulerTestFixtures.FAR_FUTURE_RULE;

  @After
  public void interruptDanglingEvents() {
    // Defense-in-depth: if a test left an event registered in SchedulerImpl, drop it via
    // the public API before DbTestBase#afterTest tears down the database. SchedulerImpl#close
    // (called via SharedContext#close in YouTrackDB#close) already cancels all registered
    // events, but doing it here keeps test output focused on the test under inspection
    // rather than the close-time cancellation cascade.
    SchedulerTestFixtures.removeAllRegisteredEvents(session);
  }

  // ---------------------------------------------------------------------------
  // Persistence round-trip — build, save in tx, reload, inspect each property
  // ---------------------------------------------------------------------------

  @Test
  public void buildPersistsAllPropertiesAndAfterReloadEntityKeepsName() {
    var function = SchedulerTestFixtures.createTrivialFunction(session, "fnRoundTripName");
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
    var function = SchedulerTestFixtures.createTrivialFunction(session, "fnRoundTripPayload");
    var args = new HashMap<Object, Object>();
    args.put("note", "hi");
    args.put("count", 42);
    var event = buildEvent("evt-payload", FAR_FUTURE_RULE, function, args);

    session.computeInTx(transaction -> {
      var reloaded = (EntityImpl) session.loadEntity(event.getIdentity());
      assertEquals(FAR_FUTURE_RULE, reloaded.getProperty(PROP_RULE));
      Map<String, Object> reloadedArgs = reloaded.getProperty(PROP_ARGUMENTS);
      assertEquals("args map round-trips with exactly the two written entries",
          2, reloadedArgs.size());
      assertEquals("hi", reloadedArgs.get("note"));
      assertEquals(42, ((Number) reloadedArgs.get("count")).intValue());
      // The function link is stored as the function's RID (see ScheduledEvent#toEntity);
      // pin RID equality so a future change that captures a different identity is loud.
      Object funcLink = reloaded.getProperty(PROP_FUNC);
      assertEquals("function link must point back to the saved function's RID",
          function.getIdentity(), funcLink);
      return null;
    });
  }

  @Test
  public void explicitSaveRoundTripsStartTimeAsEpochByDefaultThroughToEntity() {
    // ScheduledEvent#startTime is a primitive long defaulting to 0L; only ScheduledEvent's
    // toEntity (invoked from IdentityWrapper#save) writes it as PROP_STARTTIME (DATETIME).
    // The build() path does not call toEntity — only the builder's own properties map is
    // copied via updateFromMap, so a freshly-built-but-never-saved event has no
    // PROP_STARTTIME on its entity. Pin that an explicit event.save(session) call does
    // populate the property, and that the default round-trips as a non-null Date at epoch
    // — a future change to the field's type or default would surface here.
    var function = SchedulerTestFixtures.createTrivialFunction(session, "fnStartTime");
    var event = buildEvent("evt-st", FAR_FUTURE_RULE, function, Map.of());

    // Without an explicit save, PROP_STARTTIME is absent.
    Object beforeSave =
        session.computeInTx(transaction -> ((EntityImpl) session.loadEntity(event.getIdentity()))
            .getProperty(PROP_STARTTIME));
    assertNull("build() does not invoke toEntity, so PROP_STARTTIME stays unset", beforeSave);

    // Explicit save() drives toEntity, which writes PROP_STARTTIME from the long field.
    session.executeInTx(transaction -> event.save(session));

    Object afterSave =
        session.computeInTx(transaction -> ((EntityImpl) session.loadEntity(event.getIdentity()))
            .getProperty(PROP_STARTTIME));
    assertNotNull("PROP_STARTTIME must round-trip after explicit event.save()", afterSave);
    assertEquals("default startTime persists as the epoch timestamp",
        0L, ((Date) afterSave).getTime());
  }

  @Test
  public void initScheduleRecordHookSetsStoppedStatusOnNewlyCreatedEntity() {
    // The DatabaseSessionEmbedded#beforeCreateOperations hook calls
    // SchedulerImpl#initScheduleRecord which sets PROP_STATUS to STATUS.STOPPED.name() before
    // commit. Verify the persisted status string matches the enum value.
    var function = SchedulerTestFixtures.createTrivialFunction(session, "fnInitStatus");
    var event = buildEvent("evt-status", FAR_FUTURE_RULE, function, Map.of());

    Object status =
        session.computeInTx(transaction -> ((EntityImpl) session.loadEntity(event.getIdentity()))
            .getProperty(PROP_STATUS));
    // The hook stores the .name() string of the enum.
    assertEquals(STATUS.STOPPED.name(), status);
  }

  // ---------------------------------------------------------------------------
  // Constructor — null-fallback branches over PROP_ARGUMENTS / PROP_EXEC_ID
  // ---------------------------------------------------------------------------

  @Test
  public void constructorFallsBackToEmptyArgumentsMapWhenEntityHasNoArgumentsProperty() {
    // ScheduledEvent's constructor coalesces a missing PROP_ARGUMENTS via
    // Objects.requireNonNullElse(args, Collections.emptyMap()) so that getArguments()
    // never returns null. Pin the fallback so a future refactor that drops the coalesce
    // (and would NPE in executeEventFunction's getArguments() call) is caught here.
    var function = SchedulerTestFixtures.createTrivialFunction(session, "fnNullArgs");

    session.begin();
    ScheduledEvent event;
    try {
      EntityImpl entity = (EntityImpl) session.newEntity(ScheduledEvent.CLASS_NAME);
      entity.setProperty(PROP_NAME, "no-args");
      entity.setProperty(PROP_RULE, FAR_FUTURE_RULE);
      entity.setProperty(PROP_FUNC, function.getIdentity());
      // Intentionally do NOT set PROP_ARGUMENTS.
      event = new ScheduledEvent(entity, session);
    } finally {
      session.rollback();
    }

    assertNotNull("getArguments() must never return null", event.getArguments());
    assertTrue("missing PROP_ARGUMENTS must yield an empty map",
        event.getArguments().isEmpty());
  }

  @Test
  public void constructorSeedsNextExecutionIdFromExistingExecIdPropertyWhenNonNull()
      throws Exception {
    // ScheduledEvent's constructor reads PROP_EXEC_ID and seeds nextExecutionId to that
    // value (or 0 if absent). Pin the non-null branch so a reloaded event keeps its
    // execution-history counter — a refactor that swaps the ternary or drops the seed
    // would cause reloaded events to "forget" their prior id and re-run already-completed
    // executions.
    var function = SchedulerTestFixtures.createTrivialFunction(session, "fnSeedExecId");

    session.begin();
    ScheduledEvent event;
    try {
      EntityImpl entity = (EntityImpl) session.newEntity(ScheduledEvent.CLASS_NAME);
      entity.setProperty(PROP_NAME, "seeded");
      entity.setProperty(PROP_RULE, FAR_FUTURE_RULE);
      entity.setProperty(PROP_FUNC, function.getIdentity());
      entity.setProperty(PROP_EXEC_ID, 42L);
      event = new ScheduledEvent(entity, session);
    } finally {
      session.rollback();
    }

    Field idField = ScheduledEvent.class.getDeclaredField("nextExecutionId");
    idField.setAccessible(true);
    var atomic = (java.util.concurrent.atomic.AtomicLong) idField.get(event);
    assertEquals("nextExecutionId must seed from the entity's PROP_EXEC_ID",
        42L, atomic.get());
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
    // Note: the cron field is non-volatile and non-final; the reflective read here is sound
    // only because it happens on the same thread that constructed the event. A complete fix
    // for the silent-swallow bug should also close the publication gap by making cron
    // volatile or assigning it under timerLock (matching the read site in ScheduledTimerTask).
    var function = SchedulerTestFixtures.createTrivialFunction(session, "fnMalformedCron");

    session.begin();
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
      session.rollback();
    }

    // Pin the silent-swallow observable: the private cron field is null after construction.
    Field cronField = ScheduledEvent.class.getDeclaredField("cron");
    cronField.setAccessible(true);
    assertNull("cron field must be null after silently-swallowed ParseException",
        cronField.get(event));
    // Also pin that the rollback path did not register a half-constructed event in the
    // SchedulerImpl registry — the auto-schedule hook's post-commit path was never reached.
    assertNull("transient bad-cron event must not appear in the registry after rollback",
        session.getMetadata().getScheduler().getEvent("bad-cron"));
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
    var function = SchedulerTestFixtures.createTrivialFunction(session, "fnUnsavedGuard");

    session.begin();
    ScheduledEvent event;
    try {
      EntityImpl entity = (EntityImpl) session.newEntity(ScheduledEvent.CLASS_NAME);
      entity.setProperty(PROP_NAME, "unsaved");
      entity.setProperty(PROP_RULE, FAR_FUTURE_RULE);
      entity.setProperty(PROP_FUNC, function.getIdentity());
      event = new ScheduledEvent(entity, session);
    } finally {
      session.rollback();
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
    // nulls the field, and cancels the pool task. Note that cancel(false) does not remove
    // the cancelled task from the underlying ScheduledThreadPoolExecutor's delay queue
    // (setRemoveOnCancelPolicy(true) is not configured), so a small bounded amount of
    // cancelled-future heap survives until JVM shutdown — acceptable for SequentialTest.
    var function = SchedulerTestFixtures.createTrivialFunction(session, "fnMonotonic");
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

    // The +1/+2/+3 form pins both the increment magnitude (1) and strict monotonicity —
    // a regression that increments by 2 per call would fail the first assertion.
    assertEquals("first schedule increments by 1", initial + 1, afterFirst);
    assertEquals("second schedule increments by 1 more", initial + 2, afterSecond);
    assertEquals("third schedule increments by 1 more", initial + 3, afterThird);
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /**
   * Builds and saves a ScheduledEvent through the production fluent path inside a single
   * transaction so the {@code initScheduleRecord} and after-commit {@code scheduleEvent}
   * hooks fire. The args map is defensively copied because the builder stores it by
   * reference (see {@link ScheduledEventBuilderTest}); copying here keeps each test's
   * args independent of the literal {@code Map.of(...)} view passed in.
   */
  private ScheduledEvent buildEvent(String name, String rule, Function function,
      Map<Object, Object> args) {
    return SchedulerTestFixtures.buildEvent(session, name, rule, function, args);
  }
}
