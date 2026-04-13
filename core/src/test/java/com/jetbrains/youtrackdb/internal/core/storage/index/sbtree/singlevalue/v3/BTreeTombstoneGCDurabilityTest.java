package com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.v3;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.common.io.FileUtils;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Vertex;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Durability test for tombstone GC in the index {@link BTree}.
 *
 * <p>Verifies that after a non-graceful shutdown (via {@code forceDatabaseClose}
 * without prior session close), the database reopens with consistent index
 * state: surviving entries are findable via index lookup, deleted entries do
 * not reappear (no ghost resurrection), and no exceptions occur during
 * traversal.
 *
 * <p><b>Note on crash fidelity:</b> {@code forceDatabaseClose} performs a
 * storage-level shutdown that flushes WAL and dirty pages before closing.
 * This means it tests durability across a non-graceful close (no explicit
 * session close), not true mid-operation crash recovery. True WAL replay
 * testing would require preventing the flush — e.g., by copying data files
 * before operations and replaying WAL against the stale copy. The current
 * test still provides value by verifying that GC-modified B-tree state
 * survives the close/reopen cycle without corruption.
 *
 * <p>The test creates a class with an indexed STRING property, inserts enough
 * records to fill multiple BTree buckets (~400+ entries), then performs
 * cross-tx deletion + new inserts to trigger tombstone GC during bucket
 * overflow.
 *
 * <p>Does NOT extend {@link DbTestBase} because we need explicit control
 * over the database lifecycle (close, reopen, forceDatabaseClose).
 */
public class BTreeTombstoneGCDurabilityTest {

  private static final String ADMIN = "admin";
  private static final String ADMIN_PWD = "adminpwd";

  // Enough entries to trigger multiple bucket overflows and GC in the index
  // BTree. With ~30 bytes per entry (CompositeKey(String, Long) + RID) and
  // ~8KB usable per bucket, ~250 entries per bucket. 500 entries ensures
  // multiple splits.
  private static final int INITIAL_COUNT = 500;

  // Number of records to delete in the cross-tx phase. These create
  // index tombstones that should be GC'd during subsequent inserts.
  private static final int DELETE_COUNT = 200;

  // Number of additional records to insert after deletion. These trigger
  // bucket overflows that invoke tombstone GC.
  private static final int INSERT_AFTER_DELETE_COUNT = 300;

  private String testDir;
  private YouTrackDBImpl ytdb;

  @Before
  public void setUp() {
    testDir = DbTestBase.getBaseDirectoryPathStr(getClass());
    ytdb = (YouTrackDBImpl) YourTracks.instance(testDir);
  }

  @After
  public void tearDown() {
    if (ytdb != null) {
      ytdb.close();
    }
    FileUtils.deleteRecursively(new File(testDir));
  }

  /**
   * Force-closes the database after tombstone GC has occurred (committed
   * cross-tx deletions + new inserts that trigger GC), then reopens and
   * verifies the index state is consistent.
   *
   * <p>The test flow:
   * <ol>
   *   <li>Create a class with an indexed STRING property. Insert
   *       INITIAL_COUNT records (tx1, committed).</li>
   *   <li>Delete DELETE_COUNT records in a new tx (creates cross-tx
   *       index tombstones), then insert INSERT_AFTER_DELETE_COUNT new
   *       records (triggers GC on tombstones during bucket overflow).
   *       Commit tx2.</li>
   *   <li>Force-close the database without session close.</li>
   *   <li>Reopen and verify: surviving + new records are findable via
   *       index lookup, deleted records not returned (no ghost
   *       resurrection), total indexed count is correct.</li>
   * </ol>
   */
  @Test
  public void forceClose_afterTombstoneGC_preservesIndexEntries() {
    var dbName = "forceCloseIndexGCRecovery";
    ytdb.create(dbName, DatabaseType.DISK, ADMIN, ADMIN_PWD, "admin");

    // Phase 1: Create schema, insert initial records, commit.
    var survivingValues = new HashSet<String>();
    var deletedValues = new HashSet<String>();
    var newValues = new HashSet<String>();
    // Store RIDs and values from phase 1 for cross-tx deletion in phase 2
    List<RID> allRids = new ArrayList<>();
    List<String> allValues = new ArrayList<>();

    var session = ytdb.open(dbName, ADMIN, ADMIN_PWD);
    try {
      // Create schema with indexed property
      var cls = session.createVertexClass("TestDoc");
      cls.createProperty("value", PropertyType.STRING);
      cls.createIndex("TestDoc_value_idx",
          SchemaClass.INDEX_TYPE.NOTUNIQUE, "value");

      // Insert initial records
      var tx = session.begin();
      for (int i = 0; i < INITIAL_COUNT; i++) {
        var doc = tx.newVertex("TestDoc");
        var val = "val" + String.format("%06d", i);
        doc.setProperty("value", val);
        allValues.add(val);
        survivingValues.add(val);
      }
      tx.commit();

      // Collect committed RIDs (stable after commit)
      var txRead = session.begin();
      try (var results = txRead.query("SELECT FROM TestDoc ORDER BY value")) {
        while (results.hasNext()) {
          allRids.add(results.next().getIdentity());
        }
      }
      txRead.commit();

      // Phase 2: In a new tx, delete some records (creating tombstones),
      // then insert new ones (triggering GC on tombstones during overflow).
      var tx2 = session.begin();

      // Delete first DELETE_COUNT records by loading via stored RIDs.
      // Deleting in a new tx creates cross-tx index tombstones.
      for (int i = 0; i < DELETE_COUNT; i++) {
        Vertex v = tx2.load(allRids.get(i));
        var val = v.<String>getProperty("value");
        deletedValues.add(val);
        survivingValues.remove(val);
        tx2.delete(v);
      }

      // Insert new records to trigger bucket overflow + GC on tombstones
      for (int i = 0; i < INSERT_AFTER_DELETE_COUNT; i++) {
        var doc = tx2.newVertex("TestDoc");
        var val = "new" + String.format("%06d", i);
        doc.setProperty("value", val);
        newValues.add(val);
      }
      tx2.commit();

      // Phase 3: Force-close without session close.
      session.activateOnCurrentThread();
    } finally {
      ytdb.internal.forceDatabaseClose(dbName);
    }

    // Phase 4: Reopen and verify index state.
    var recoveredSession = ytdb.open(dbName, ADMIN, ADMIN_PWD);
    try {
      var txVerify = recoveredSession.begin();

      // Build the full expected set: original survivors + new records
      var expectedValues = new HashSet<>(survivingValues);
      expectedValues.addAll(newValues);

      // Verify each expected value is findable via index lookup
      for (var val : expectedValues) {
        try (var results = txVerify.query(
            "SELECT FROM TestDoc WHERE value = ?", val)) {
          assertThat(results.hasNext())
              .as("Expected value '%s' must be findable via index after recovery",
                  val)
              .isTrue();
        }
      }

      // Verify deleted values do NOT appear (no ghost resurrection)
      for (var val : deletedValues) {
        try (var results = txVerify.query(
            "SELECT FROM TestDoc WHERE value = ?", val)) {
          assertThat(results.hasNext())
              .as("Deleted value '%s' must not reappear after recovery "
                  + "(ghost resurrection)", val)
              .isFalse();
        }
      }

      // Verify total count matches expected
      long totalCount;
      try (var countResults = txVerify.query(
          "SELECT count(*) as cnt FROM TestDoc")) {
        assertThat(countResults.hasNext()).isTrue();
        totalCount = countResults.next().<Long>getProperty("cnt");
      }

      long expectedCount =
          INITIAL_COUNT - DELETE_COUNT + INSERT_AFTER_DELETE_COUNT;
      assertThat(totalCount)
          .as("Total record count after recovery must match expected")
          .isEqualTo(expectedCount);

      txVerify.commit();
    } finally {
      recoveredSession.close();
    }
  }
}
