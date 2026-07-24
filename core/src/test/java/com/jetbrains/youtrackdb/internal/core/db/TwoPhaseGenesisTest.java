package com.jetbrains.youtrackdb.internal.core.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.config.YouTrackDBConfig;
import com.jetbrains.youtrackdb.internal.core.index.IndexAbstract;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransactionImpl;
import com.jetbrains.youtrackdb.internal.core.tx.Transaction;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Test;

/**
 * Pins the two-phase genesis restructure (Track 8 D18/G2.c, rulings Q-G1/Q-G2, invariant I-U4):
 * database creation runs ONE phase-1 schema transaction (all internal classes, properties and
 * indexes — engaging the metadata-write mutex, engines built at commit) followed by ONE phase-2
 * data transaction (default roles + users — no mutex), so the {@code OUser.name} UNIQUE engine
 * exists and is built before the first user insert.
 *
 * <p>The genesis commits are observed through a config-registered {@link SessionListener}
 * (registered by {@code internalCreate} before the metadata creation runs), which records for
 * every top-level commit whether the committing session held the metadata-write mutex and how
 * many record operations the transaction carried. NOTE (CN observations row): genesis tests must
 * not pin a single {@code callOnCreateListeners} invocation — it fires twice per create at HEAD.
 */
public class TwoPhaseGenesisTest {

  private static final String ADMIN_PASSWORD = "adminpwd";

  private YouTrackDBImpl youTrackDB;

  private YouTrackDBImpl createContext() {
    return (YouTrackDBImpl) YourTracks.instance(
        DbTestBase.getBaseDirectoryPathStr(TwoPhaseGenesisTest.class));
  }

  @After
  public void tearDown() {
    if (youTrackDB != null) {
      youTrackDB.close();
      youTrackDB = null;
    }
  }

  /**
   * One observation per top-level commit of the genesis session: whether the metadata-write
   * mutex was engaged by the committing session at {@code onBeforeTxCommit} time, how many
   * record operations the transaction carried, and whether the shared index manager already
   * held a BUILT {@code OUser.name} engine when the commit started.
   */
  private record CommitObservation(boolean mutexEngaged, int recordOperations,
      boolean oUserNameEngineBuilt) {

  }

  /** Records every top-level commit the genesis session fires. */
  private static final class RecordingListener implements SessionListener {

    private final List<CommitObservation> observations = new ArrayList<>();

    @Override
    public void onBeforeTxCommit(final Transaction transaction) {
      var session = transaction.getDatabaseSession();
      var mutexEngaged =
          session.getSharedContext().getMetadataWriteMutex().isEngagedBy(session);
      var recordOperations =
          ((FrontendTransactionImpl) transaction).getRecordOperationsCount();
      var index = session.getSharedContext().getIndexManager().getIndex("OUser.name");
      var built = index instanceof IndexAbstract abstractIndex
          && abstractIndex.getIndexId() >= 0;
      observations.add(new CommitObservation(mutexEngaged, recordOperations, built));
    }
  }

  /**
   * Design pins G.5 #3 and #10 (I-U4 + Q-G2): during genesis EXACTLY ONE commit engages the
   * metadata-write mutex (the phase-1 schema transaction), and after it EXACTLY ONE commit
   * carries record operations (the merged phase-2 roles+users transaction) — the trailing
   * predicate-security init transaction is read-only. The phase-2 commit does not engage the
   * mutex (it never touches schema).
   */
  @Test
  public void mutexEngagedInPhaseOneOnlyAndPhaseTwoCommitsOnce() {
    youTrackDB = createContext();
    var listener = new RecordingListener();
    var config = YouTrackDBConfig.builder().addSessionListener(listener).build();
    var dbName = "twoPhaseObservation";
    youTrackDB.create(dbName, DatabaseType.MEMORY, config);
    try {
      var observations = listener.observations;
      assertFalse("the genesis session must have fired commits", observations.isEmpty());

      var engagedIndexes = new ArrayList<Integer>();
      for (var i = 0; i < observations.size(); i++) {
        if (observations.get(i).mutexEngaged()) {
          engagedIndexes.add(i);
        }
      }
      assertEquals("exactly ONE genesis commit engages the metadata-write mutex (phase 1),"
          + " saw engaged commits at " + engagedIndexes + " of " + observations,
          1, engagedIndexes.size());
      var phaseOne = engagedIndexes.get(0);

      var recordCarryingAfterPhaseOne = new ArrayList<Integer>();
      for (var i = phaseOne + 1; i < observations.size(); i++) {
        if (observations.get(i).recordOperations() > 0) {
          recordCarryingAfterPhaseOne.add(i);
        }
      }
      assertEquals("exactly ONE commit after phase 1 carries record operations (the merged"
          + " phase-2 roles+users transaction), saw " + recordCarryingAfterPhaseOne
          + " of " + observations,
          1, recordCarryingAfterPhaseOne.size());
      var phaseTwo = observations.get(recordCarryingAfterPhaseOne.get(0));
      assertFalse("phase 2 never touches schema, so it must not engage the mutex",
          phaseTwo.mutexEngaged());
    } finally {
      youTrackDB.drop(dbName);
    }
  }

  /**
   * Design pin G.5 #4 (engine-before-insert, I-U4's positive property): when the phase-2
   * transaction commits its user inserts, the shared index manager already holds a BUILT
   * {@code OUser.name} engine (built by the phase-1 commit) — and after creation the default
   * users are found through the index-backed lookup (the same planner path
   * {@code getUserInternal} uses, exercised here both by the authenticated open and by the
   * explicit indexed query).
   */
  @Test
  public void oUserNameEngineIsBuiltBeforeFirstUserInsert() {
    youTrackDB = createContext();
    var listener = new RecordingListener();
    var config = YouTrackDBConfig.builder().addSessionListener(listener).build();
    var dbName = "engineBeforeInsert";
    youTrackDB.create(dbName, DatabaseType.MEMORY, config,
        "admin", ADMIN_PASSWORD, "admin");
    try {
      // Locate phase 2 (the first record-carrying commit after the mutex-engaged one) and
      // assert the engine was already built when it committed.
      var observations = listener.observations;
      var phaseOne = -1;
      for (var i = 0; i < observations.size(); i++) {
        if (observations.get(i).mutexEngaged()) {
          phaseOne = i;
          break;
        }
      }
      assertTrue("the phase-1 schema commit must exist", phaseOne >= 0);
      CommitObservation phaseTwo = null;
      for (var i = phaseOne + 1; i < observations.size(); i++) {
        if (observations.get(i).recordOperations() > 0) {
          phaseTwo = observations.get(i);
          break;
        }
      }
      assertNotNull("the phase-2 data commit must exist", phaseTwo);
      assertTrue("the OUser.name engine must be BUILT before the first user insert commits",
          phaseTwo.oUserNameEngineBuilt());

      // The authenticated open resolves the admin user through the security lookup, and the
      // indexed query returns exactly the inserted user.
      try (var session = youTrackDB.open(dbName, "admin", ADMIN_PASSWORD)) {
        var index = session.getSharedContext().getIndexManager().getIndex(session, "OUser.name");
        assertNotNull("OUser.name must exist after genesis", index);
        assertTrue("OUser.name must resolve to a built engine",
            index instanceof IndexAbstract abstractIndex && abstractIndex.getIndexId() >= 0);
        try (var result = session.query("select from OUser where name = 'admin'")) {
          assertTrue("the admin user must be found via the indexed lookup", result.hasNext());
          result.next();
          assertFalse("exactly one admin user must exist", result.hasNext());
        }
      }
    } finally {
      youTrackDB.drop(dbName);
    }
  }

  /**
   * Design pin G.5 #2(b) (+ the Step-2 TQ14 deferral): a full context close and disk reopen
   * loads the genesis-POPULATED schema from the persisted bytes — a fresh {@link SharedContext}
   * parses the root, the per-class records, the index manager and the security records without
   * any same-session cache assistance. A completed create can never show an empty schema.
   */
  @Test
  public void reopenShowsGenesisPopulatedSchema() {
    youTrackDB = createContext();
    var dbName = "genesisReopen";
    youTrackDB.create(dbName, DatabaseType.DISK, "admin", ADMIN_PASSWORD, "admin");
    try {
      // Full context close: the reopened context loads a fresh SharedContext from disk.
      youTrackDB.close();
      youTrackDB = createContext();
      try (var session = youTrackDB.open(dbName, "admin", ADMIN_PASSWORD)) {
        var schema = session.getMetadata().getSchema();
        for (var className : new String[] {"OUser", "ORole", "OSecurityPolicy", "OFunction",
            "OSequence", "OSchedule", "O", "V", "E"}) {
          assertTrue("genesis class '" + className + "' must survive the disk reopen",
              schema.existsClass(className));
        }
        var index = session.getSharedContext().getIndexManager().getIndex(session, "OUser.name");
        assertNotNull("OUser.name must survive the disk reopen", index);
        try (var result = session.query("select from OUser where name = 'admin'")) {
          assertTrue("the admin user must be found after the disk reopen", result.hasNext());
        }
      }
    } finally {
      youTrackDB.drop(dbName);
    }
  }

  /**
   * Design pin G.5 #6 (system-DB genesis; CN53/OBS-2 constraint honored — the system database
   * is touched STRICTLY SEQUENTIALLY here, never from parallel threads): the system database's
   * genesis runs the identical phase 1 (all security classes exist) while phase 2 stays empty
   * (no default roles or users), and the completion marker is present.
   */
  @Test
  public void systemDatabaseGenesisCreatesSchemaWithoutDefaultUsers() {
    youTrackDB = createContext();
    var internal = (YouTrackDBInternalEmbedded) youTrackDB.internal;
    var systemDatabase = internal.getSystemDatabase();
    systemDatabase.init();

    var counts = systemDatabase.executeWithDB(session -> {
      assertTrue("the system database genesis must create the security schema",
          session.getMetadata().getSchema().existsClass("OUser"));
      assertTrue(session.getMetadata().getSchema().existsClass("ORole"));
      long users;
      long roles;
      session.begin();
      // Default roles and users are skipped for the system database (phase 2 empty); the
      // system-role records DefaultSecuritySystem creates post-genesis are ORole subjects, so
      // only OUser is asserted zero here.
      users = session.countClass("OUser");
      roles = session.countClass("ORole");
      session.rollback();
      return new long[] {users, roles};
    });
    assertEquals("the system database must hold no default users", 0L, counts[0]);

    var storage = internal.getStorage(SystemDatabase.SYSTEM_DB_NAME);
    assertNotNull(storage);
    assertEquals("the system database's genesis-completion marker must be present",
        "true", storage.getProperty(SharedContext.GENESIS_COMPLETED_PROPERTY));
  }

  /**
   * Design pin G.5 #5 (guard half): a repeat {@code security.create} call on a database whose
   * classes exist stays a cheap, transaction-free no-op returning {@code null} — the contract
   * the import call site ({@code DatabaseImport.removeDefaultCollections}) relies on. The full
   * import-nested path is exercised end-to-end by the import suites.
   */
  @Test
  public void repeatSecurityCreateIsTxFreeNoOp() {
    youTrackDB = createContext();
    var dbName = "guardNoOp";
    youTrackDB.create(dbName, DatabaseType.MEMORY, "admin", ADMIN_PASSWORD, "admin");
    try (var session = youTrackDB.open(dbName, "admin", ADMIN_PASSWORD)) {
      var result = session.getSharedContext().getSecurity().create(session);
      assertNull("create on a populated schema must be a guard no-op returning null", result);
      assertFalse("the guard no-op must not leave a transaction open", session.isTxActive());
    } finally {
      youTrackDB.drop(dbName);
    }
  }
}
