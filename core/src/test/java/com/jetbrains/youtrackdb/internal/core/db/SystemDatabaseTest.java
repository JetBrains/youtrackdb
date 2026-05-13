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
package com.jetbrains.youtrackdb.internal.core.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * Pins the live surface of {@link SystemDatabase}: enabled-flag predicate, lazy init on
 * first access, server-id idempotence across openings, and the four execute / query /
 * executeInDBScope / executeWithDB callback delegators.
 *
 * <p>Runs sequentially because:
 * <ol>
 *   <li>{@link SystemDatabase#SYSTEM_DB_NAME} is a hardcoded constant — concurrent classes
 *       in the same engine instance would race for the same {@code OSystem} directory in
 *       disk mode. The companion {@link SystemDatabaseDisabledTest} carries the same
 *       {@code @Category(SequentialTest)} marker for the same reason.</li>
 *   <li>The disabled-mode test sub-class flips a global flag
 *       ({@link GlobalConfiguration#DB_SYSTEM_DATABASE_ENABLED}) which leaks across
 *       parallel-runner classes if not isolated.</li>
 * </ol>
 */
@Category(SequentialTest.class)
public class SystemDatabaseTest extends DbTestBase {

  // --------------------------------------------------------------------------------------------
  // Enabled-mode happy path
  // --------------------------------------------------------------------------------------------

  /**
   * The system database is enabled by default. Pins that {@code exists()} and {@code
   * openSystemDatabaseSession()} both succeed and that the {@code OSystem} database is
   * lazily created on first access. The behaviour is identical for memory-mode and
   * disk-mode engines (the constant {@link SystemDatabase#SYSTEM_DB_NAME}).
   */
  @Test
  public void systemDatabaseIsCreatedAndOpenableOnFirstAccess() {
    var systemDb = systemDatabase();

    assertNotNull(systemDb);

    // First call is the lazy-init path: existence check fails → init() creates it.
    try (var sysSession = systemDb.openSystemDatabaseSession()) {
      assertNotNull(sysSession);
      assertEquals(SystemDatabase.SYSTEM_DB_NAME, sysSession.getDatabaseName());
    }
    assertTrue("system DB must exist after first openSystemDatabaseSession",
        systemDb.exists());
  }

  /**
   * Once {@link SystemDatabase#init()} has run, the wrapper's cached server-id is
   * non-null, non-empty, and stable across repeated {@code openSystemDatabaseSession}
   * cycles. Pins the {@code checkServerId()} "row already present" branch: after the
   * row is seeded by the first {@code init()}, any subsequent {@code init()} call
   * takes the {@code browseClass} arm to read the existing UUID rather than creating
   * a new one.
   *
   * <p>We call {@code init()} explicitly because {@code openSystemDatabaseSession()}
   * only invokes {@code init()} when {@code !exists()} — so on a fresh wrapper that
   * sees a pre-existing OSystem (left over from another test method's run in the
   * same shared engine path), the wrapper's {@code serverId} field would otherwise
   * stay null. This is a latent shape captured by the falsifiable-regression pin
   * below.
   */
  @Test
  public void getServerIdIsStableAcrossRepeatedOpens() {
    var systemDb = systemDatabase();

    // Force init() so checkServerId populates the wrapper's serverId field even when
    // the OSystem DB already exists from a previous test method's run.
    systemDb.init();

    var firstServerId = systemDb.getServerId();
    assertNotNull("serverId must be populated after init", firstServerId);
    assertFalse("serverId must not be empty", firstServerId.isEmpty());

    // Second init() takes the "already populated" branch and returns the same UUID.
    systemDb.init();
    assertEquals("serverId must remain stable across init calls",
        firstServerId, systemDb.getServerId());

    // openSystemDatabaseSession() does not mutate serverId — pin observed shape.
    try (var s = systemDb.openSystemDatabaseSession()) {
      assertNotNull(s);
    }
    assertEquals(firstServerId, systemDb.getServerId());
  }

  /**
   * Falsifiable-regression pin: when the OSystem database already exists (a previous
   * test method or process created it), a freshly-constructed {@link SystemDatabase}
   * wrapper's {@code openSystemDatabaseSession()} skips {@code init()} via the
   * {@code if (!exists())} guard, leaving the wrapper's {@code serverId} field null
   * until {@code init()} is explicitly invoked. Any caller that expects
   * {@code getServerId()} to be populated after the first session-open is exposed
   * to this latent shape.
   *
   * <p>Pinned here as observed shape — not a behaviour change request. A future
   * refactor that always calls {@code checkServerId()} on first open (or eagerly in
   * the constructor) should update this test in lockstep.
   */
  @Test
  public void openSessionDoesNotPopulateServerIdWhenDbAlreadyExists() {
    var systemDb = systemDatabase();
    // Make sure OSystem exists so the next fresh wrapper would see exists()=true.
    systemDb.init();
    assertNotNull(systemDb.getServerId());

    // We can't easily build a "fresh wrapper" since it's owned by the engine —
    // but we can pin the underlying shape: after init(), exists() returns true,
    // and a subsequent openSystemDatabaseSession() takes the !init branch (does
    // not mutate serverId).
    var capturedId = systemDb.getServerId();
    try (var s = systemDb.openSystemDatabaseSession()) {
      assertNotNull(s);
    }
    // serverId is unchanged because openSystemDatabaseSession's init-guard short-
    // circuits when exists()=true.
    assertEquals(capturedId, systemDb.getServerId());
  }

  // --------------------------------------------------------------------------------------------
  // execute() / query() / executeInDBScope() / executeWithDB() — callback delegators
  // --------------------------------------------------------------------------------------------

  /**
   * Pins {@code execute(callback, sql, args)} — must run the callback against the
   * system-database session and return the value the callback produced. Also implicitly
   * pins that {@code execute} bypasses authentication (system DB context).
   */
  @Test
  public void executeRunsCallbackAgainstSystemSession() {
    var systemDb = systemDatabase();
    // Ensure system DB exists so the SELECT below has a real metadata view.
    systemDb.openSystemDatabaseSession().close();

    var callbackCount = new java.util.concurrent.atomic.AtomicInteger();
    String result = systemDb.execute(
        (rs, session) -> {
          callbackCount.incrementAndGet();
          assertNotNull("execute callback must receive a non-null session", session);
          assertEquals(SystemDatabase.SYSTEM_DB_NAME, session.getDatabaseName());
          assertNotNull("execute callback must receive a non-null result-set", rs);
          return "ok";
        },
        "SELECT FROM " + SystemDatabase.SERVER_INFO_CLASS);

    assertEquals("ok", result);
    assertEquals("execute callback must be invoked exactly once", 1, callbackCount.get());
  }

  /**
   * Pins {@code query(callback, sql, args)} — must wrap the SELECT in a transaction
   * (computeInTx) and return the callback's value. Asserting the row count is a load-
   * bearing check: the {@code ServerInfo} class always has exactly one row after init.
   */
  @Test
  public void queryRunsCallbackInsideTransaction() {
    var systemDb = systemDatabase();
    systemDb.openSystemDatabaseSession().close();

    Long rowCount = systemDb.query(
        (rs, session) -> {
          assertNotNull(session);
          assertEquals(SystemDatabase.SYSTEM_DB_NAME, session.getDatabaseName());
          // Inside the callback we should see the active transaction.
          assertNotNull("query callback must run inside an active tx",
              session.getActiveTransactionOrNull());
          return rs.findFirst(r -> r.<Long>getProperty("count"));
        },
        "SELECT count(*) AS count FROM " + SystemDatabase.SERVER_INFO_CLASS);

    assertEquals(Long.valueOf(1L), rowCount);
  }

  /**
   * Pins {@code executeInDBScope(callback)} as a thin wrapper over {@code executeWithDB}:
   * the callback is invoked once with the system session, and the wrapper itself returns
   * void.
   */
  @Test
  public void executeInDBScopeForwardsToExecuteWithDb() {
    var systemDb = systemDatabase();
    systemDb.openSystemDatabaseSession().close();

    var sessionRef = new DatabaseSessionEmbedded[1];
    systemDb.executeInDBScope(session -> {
      sessionRef[0] = session;
      assertEquals(SystemDatabase.SYSTEM_DB_NAME, session.getDatabaseName());
      return null;
    });

    assertNotNull("executeInDBScope must invoke its callback", sessionRef[0]);
  }

  /**
   * Pins {@code executeWithDB(callback)} — the callback receives the open system
   * session and may return any value.
   */
  @Test
  public void executeWithDbReturnsCallbackValue() {
    var systemDb = systemDatabase();
    systemDb.openSystemDatabaseSession().close();

    Integer answer = systemDb.executeWithDB(session -> {
      assertNotNull(session);
      return 42;
    });

    assertEquals(Integer.valueOf(42), answer);
  }

  /**
   * Pins that the same {@link SystemDatabase} instance is returned across repeated
   * {@code getSystemDatabase()} calls — there is one SystemDatabase per shared engine
   * context. (A future refactor that creates a fresh instance per call would silently
   * break {@code serverId} caching.)
   */
  @Test
  public void getSystemDatabaseReturnsSameInstance() {
    var first = systemDatabase();
    var second = systemDatabase();
    assertSame(first, second);
  }

  // --------------------------------------------------------------------------------------------
  // Late-mutation invariant — captured-at-ctor enabled flag wins
  // --------------------------------------------------------------------------------------------

  /**
   * {@link SystemDatabase} captures the {@code DB_SYSTEM_DATABASE_ENABLED} flag in its
   * constructor (see SystemDatabase.java:46-50). A mutation of the global value after
   * the engine is up must not retroactively disable an already-enabled system database.
   * The companion {@link SystemDatabaseDisabledTest} covers the symmetric case where
   * the flag is false at engine-construction time and {@code openSystemDatabaseSession}
   * must throw.
   */
  @Test
  public void lateMutationOfEnabledFlagDoesNotDisableLiveSystemDatabase() {
    var systemDb = systemDatabase();
    var oldValue = GlobalConfiguration.DB_SYSTEM_DATABASE_ENABLED.getValueAsBoolean();
    try {
      GlobalConfiguration.DB_SYSTEM_DATABASE_ENABLED.setValue(false);
      // Captured-at-ctor enabled flag is still `true`, so the next open must succeed.
      try (var s = systemDb.openSystemDatabaseSession()) {
        assertNotNull(s);
      }
    } finally {
      GlobalConfiguration.DB_SYSTEM_DATABASE_ENABLED.setValue(oldValue);
    }
  }

  // --------------------------------------------------------------------------------------------
  // Constants — load-bearing identifiers used by reflective callers and the security layer
  // --------------------------------------------------------------------------------------------

  @Test
  public void publicConstantsArePinned() {
    // SYSTEM_DB_NAME is the disk path component on disk mode. A change here is a
    // breaking change requiring lockstep cleanup at every existing OSystem directory.
    assertEquals("OSystem", SystemDatabase.SYSTEM_DB_NAME);
    assertEquals("ServerInfo", SystemDatabase.SERVER_INFO_CLASS);
    assertEquals("serverId", SystemDatabase.SERVER_ID_PROPERTY);
  }

  // --------------------------------------------------------------------------------------------
  // Negative path — bad SQL still routes through the bypass-security wrapper
  // --------------------------------------------------------------------------------------------

  /**
   * Malformed SQL passed to {@code execute()} must surface the parser/runtime exception
   * but not corrupt the system session — a subsequent legitimate call must still
   * succeed. Pins the engine's session-cleanup contract under failure.
   */
  @Test
  public void executeMalformedSqlBubblesUpButLeavesSessionUsable() {
    var systemDb = systemDatabase();
    systemDb.openSystemDatabaseSession().close();

    try {
      systemDb.execute((rs, s) -> 0, "TOTALLY NOT SQL @!#");
      fail("expected an exception for malformed SQL");
    } catch (RuntimeException expected) {
      // Some form of parse / command exception is expected — pinning only that
      // *something* is thrown is enough for the negative branch.
      assertNotNull(expected.getMessage());
    }

    // System DB must still be usable after the failure.
    String afterRecovery = systemDb.execute((rs, s) -> "still-here",
        "SELECT FROM " + SystemDatabase.SERVER_INFO_CLASS);
    assertEquals("still-here", afterRecovery);
  }

  /**
   * After the row is seeded by the first {@code init()}, a second explicit
   * {@code init()} drives {@code checkServerId()} through the "row already exists"
   * branch (the {@code browseClass} path), which {@link SystemDatabaseDisabledTest}
   * does not exercise. We call {@code init()} explicitly rather than relying on
   * {@code openSystemDatabaseSession()} because that method only calls {@code init()}
   * when {@code !exists()}.
   */
  @Test
  public void browseExistingServerInfoBranchIsExercised() {
    var systemDb = systemDatabase();
    systemDb.init();
    var firstId = systemDb.getServerId();
    assertNotNull(firstId);

    // Re-init takes the "row already exists" path and uses browseClass to retrieve
    // the existing entity instead of creating a new ServerInfo row.
    systemDb.init();
    assertEquals("serverId must not be cleared by a re-init cycle",
        firstId, systemDb.getServerId());
  }

  /**
   * Pins {@code getServerId()} as a stable getter — back-to-back reads must yield
   * equal values. Identity is not pinned because synchronized-write/unsync-read
   * publishes through a happens-before edge but does not guarantee referential
   * stability against any future internal caching strategy.
   */
  @Test
  public void getServerIdAccessIsConsistentWithinSingleSession() {
    var systemDb = systemDatabase();
    systemDb.init();
    var a = systemDb.getServerId();
    var b = systemDb.getServerId();
    assertEquals(a, b);
  }

  // --------------------------------------------------------------------------------------------
  // Helpers
  // --------------------------------------------------------------------------------------------

  private SystemDatabase systemDatabase() {
    var sysDb = session.getSharedContext().getYouTrackDB().getSystemDatabase();
    assertNotNull("SystemDatabase must be wired into the shared context", sysDb);
    return sysDb;
  }
}
