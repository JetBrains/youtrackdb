package com.jetbrains.youtrackdb.internal.core.storage.index.edgebtree.btree;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.common.io.FileUtils;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Direction;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Vertex;
import java.io.File;
import java.util.HashSet;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Durability test for tombstone GC in
 * {@link com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree.SharedLinkBagBTree}.
 *
 * <p>Verifies that after a non-graceful shutdown (via {@code forceDatabaseClose}
 * without prior session close), the database reopens with consistent state:
 * surviving edges are traversable, deleted edges do not reappear (no ghost
 * resurrection), and no exceptions occur during traversal.
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
 * <p>The test forces BTree-backed link bag storage (threshold = -1) so that
 * even a small number of edges goes through SharedLinkBagBTree. It creates
 * enough edges to trigger bucket overflows and GC during the insert/delete
 * cycle.
 *
 * <p>Does NOT extend {@link DbTestBase} because we need explicit control
 * over the database lifecycle (close, reopen, forceDatabaseClose).
 */
public class SharedLinkBagBTreeTombstoneGCDurabilityTest {

  private static final String ADMIN = "admin";
  private static final String ADMIN_PWD = "adminpwd";

  // Enough edges to trigger multiple bucket overflows and GC in the
  // SharedLinkBagBTree. With ~20 bytes per entry and ~8KB per bucket,
  // ~400 entries per bucket. 600 edges ensures multiple splits + GC.
  private static final int EDGE_COUNT = 600;

  // Number of edges to delete in the cross-tx phase. These create
  // tombstones that should be GC'd during subsequent inserts.
  private static final int DELETE_COUNT = 200;

  // Number of additional edges to insert after deletion. These trigger
  // bucket overflows that invoke tombstone GC.
  private static final int INSERT_AFTER_DELETE_COUNT = 300;

  private String testDir;
  private YouTrackDBImpl ytdb;
  private int originalThreshold;

  @Before
  public void setUp() {
    testDir = DbTestBase.getBaseDirectoryPathStr(getClass());
    ytdb = (YouTrackDBImpl) YourTracks.instance(testDir);
    // Force BTree-backed link bag storage for all edge counts.
    originalThreshold =
        (int) GlobalConfiguration.LINK_COLLECTION_EMBEDDED_TO_BTREE_THRESHOLD.getValue();
    GlobalConfiguration.LINK_COLLECTION_EMBEDDED_TO_BTREE_THRESHOLD.setValue(-1);
  }

  @After
  public void tearDown() throws Exception {
    GlobalConfiguration.LINK_COLLECTION_EMBEDDED_TO_BTREE_THRESHOLD
        .setValue(originalThreshold);
    if (ytdb != null) {
      ytdb.close();
    }
    FileUtils.deleteRecursively(new File(testDir));
  }

  /**
   * Force-closes the database after tombstone GC has occurred (committed
   * cross-tx deletions + new inserts that trigger GC), then reopens and
   * verifies the B-tree state is consistent.
   *
   * <p>The test flow:
   * <ol>
   *   <li>Create a hub vertex with EDGE_COUNT outgoing edges (tx1, committed)</li>
   *   <li>Delete DELETE_COUNT edges in a new tx (creates cross-tx tombstones),
   *       then insert INSERT_AFTER_DELETE_COUNT new edges (triggers GC on
   *       tombstones during bucket overflow). Commit tx2.</li>
   *   <li>Force-close the database without session close</li>
   *   <li>Reopen and verify: surviving + new edges are traversable, deleted
   *       edges don't reappear, total count is correct</li>
   * </ol>
   */
  @Test
  public void forceClose_afterTombstoneGC_preservesEdges() {
    var dbName = "forceCloseGCRecovery";
    ytdb.create(dbName, DatabaseType.DISK, ADMIN, ADMIN_PWD, "admin");

    // Phase 1: Create hub with many edges and commit.
    var edgeTargetNames = new HashSet<String>();
    var deletedNames = new HashSet<String>();
    var newNames = new HashSet<String>();
    RID hubRid;

    var session = ytdb.open(dbName, ADMIN, ADMIN_PWD);
    try {
      var tx = session.begin();
      var hub = tx.newVertex("V");
      hub.setProperty("name", "hub");
      for (int i = 0; i < EDGE_COUNT; i++) {
        var target = tx.newVertex("V");
        var name = "t" + i;
        target.setProperty("name", name);
        tx.newEdge(hub, target, "E");
        edgeTargetNames.add(name);
      }
      hubRid = hub.getIdentity();
      tx.commit();

      // Phase 2: In a new tx, delete some edges (creating tombstones),
      // then insert new ones (triggering GC on tombstones during overflow).
      var tx2 = session.begin();
      Vertex hubV = tx2.load(hubRid);

      // Delete first DELETE_COUNT edges and record their target names
      var edgesIter = hubV.getEdges(Direction.OUT).iterator();
      int deletedSoFar = 0;
      while (edgesIter.hasNext() && deletedSoFar < DELETE_COUNT) {
        var edge = edgesIter.next();
        var targetName =
            ((Vertex) edge.getVertex(Direction.IN)).getProperty("name").toString();
        deletedNames.add(targetName);
        tx2.delete(edge);
        deletedSoFar++;
      }
      assertThat(deletedSoFar)
          .as("Should have deleted %d edges", DELETE_COUNT)
          .isEqualTo(DELETE_COUNT);

      // Insert new edges to trigger bucket overflow + GC on tombstones
      hubV = tx2.load(hubRid);
      for (int i = 0; i < INSERT_AFTER_DELETE_COUNT; i++) {
        var target = tx2.newVertex("V");
        var name = "new" + i;
        target.setProperty("name", name);
        tx2.newEdge(hubV, target, "E");
        newNames.add(name);
      }
      tx2.commit();

      // Phase 3: Force-close without session close.
      // Note: forceDatabaseClose still flushes WAL + dirty pages, so this
      // tests durability across non-graceful close, not true crash recovery.
      session.activateOnCurrentThread();
    } finally {
      // Force-close bypasses normal session close — clean up at storage level.
      ytdb.internal.forceDatabaseClose(dbName);
    }

    // Phase 4: Reopen and verify edge state.
    var recoveredSession = ytdb.open(dbName, ADMIN, ADMIN_PWD);
    try {
      var txVerify = recoveredSession.begin();
      Vertex recoveredHub = txVerify.load(hubRid);

      // Collect all edge target names after recovery
      var survivingNames = new HashSet<String>();
      for (var edge : recoveredHub.getEdges(Direction.OUT)) {
        var targetName =
            ((Vertex) edge.getVertex(Direction.IN)).getProperty("name").toString();
        survivingNames.add(targetName);
      }

      // Build the full expected set: original survivors + new edges
      var expectedNames = new HashSet<>(edgeTargetNames);
      expectedNames.removeAll(deletedNames);
      expectedNames.addAll(newNames);

      // Exact-match assertion: catches ghosts, missing edges, and count
      // mismatches in one shot with a clear diff in the failure message.
      assertThat(survivingNames)
          .as("Recovered edges must exactly match expected survivors + new edges")
          .containsExactlyInAnyOrderElementsOf(expectedNames);

      // Separate ghost-resurrection check for diagnostic clarity —
      // if it fails, the developer immediately knows the issue.
      assertThat(survivingNames)
          .as("Deleted edges must not reappear after recovery (ghost resurrection)")
          .doesNotContainAnyElementsOf(deletedNames);

      txVerify.commit();
    } finally {
      recoveredSession.close();
    }
  }
}
