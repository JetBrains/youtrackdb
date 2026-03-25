package com.jetbrains.youtrackdb.internal.core.storage.index.edgebtree.btree;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Direction;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Vertex;
import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Crash-recovery durability test for tombstone GC in
 * {@link com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree.SharedLinkBagBTree}.
 *
 * <p>Verifies that after a simulated crash (via {@code forceDatabaseClose}
 * without prior session close), WAL recovery restores the B-tree to a
 * consistent state: surviving edges are traversable, deleted edges do not
 * reappear (no ghost resurrection), and no exceptions occur during traversal.
 *
 * <p>The test forces BTree-backed link bag storage (threshold = -1) so that
 * even a small number of edges goes through SharedLinkBagBTree. It creates
 * enough edges to trigger bucket overflows and GC during the insert/delete
 * cycle before crashing.
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
    FileUtils.deleteDirectory(new File(testDir));
  }

  /**
   * Simulates a crash after tombstone GC has occurred, then verifies that
   * WAL recovery restores the B-tree correctly.
   *
   * <p>The test flow:
   * <ol>
   *   <li>Create a hub vertex with EDGE_COUNT outgoing edges (tx1, committed)</li>
   *   <li>Delete DELETE_COUNT edges in a new tx (creates cross-tx tombstones),
   *       then insert INSERT_AFTER_DELETE_COUNT new edges (triggers GC on
   *       tombstones during bucket overflow). Commit tx2.</li>
   *   <li>Force-close the database without session close (simulates crash)</li>
   *   <li>Reopen and verify: surviving + new edges are traversable, deleted
   *       edges don't reappear, total count is correct</li>
   * </ol>
   */
  @Test
  public void crashAfterTombstoneGC_recoveryPreservesEdges() {
    var dbName = "crashGCRecovery";
    ytdb.create(dbName, DatabaseType.DISK, ADMIN, ADMIN_PWD, "admin");

    // Phase 1: Create hub with many edges and commit.
    var edgeTargetNames = new ArrayList<String>();
    RID hubRid;

    var session = ytdb.open(dbName, ADMIN, ADMIN_PWD);
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

    // Collect edge RIDs to delete (first DELETE_COUNT edges)
    var deletedNames = new HashSet<String>();
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
    var newNames = new HashSet<String>();
    for (int i = 0; i < INSERT_AFTER_DELETE_COUNT; i++) {
      var target = tx2.newVertex("V");
      var name = "new" + i;
      target.setProperty("name", name);
      tx2.newEdge(hubV, target, "E");
      newNames.add(name);
    }
    tx2.commit();

    // Phase 3: Force-close without session close — simulates crash.
    // Do NOT call session.close() first, as that triggers graceful flush.
    session.activateOnCurrentThread();
    ytdb.internal.forceDatabaseClose(dbName);

    // Phase 4: Reopen — WAL recovery runs automatically.
    var recoveredSession = ytdb.open(dbName, ADMIN, ADMIN_PWD);
    var txVerify = recoveredSession.begin();
    Vertex recoveredHub = txVerify.load(hubRid);

    // Collect all edge target names after recovery
    var survivingNames = new HashSet<String>();
    for (var edge : recoveredHub.getEdges(Direction.OUT)) {
      var targetName =
          ((Vertex) edge.getVertex(Direction.IN)).getProperty("name").toString();
      survivingNames.add(targetName);
    }

    // Expected count: original - deleted + new
    int expectedCount = EDGE_COUNT - DELETE_COUNT + INSERT_AFTER_DELETE_COUNT;
    assertThat(survivingNames)
        .as("Edge count after recovery must match expected")
        .hasSize(expectedCount);

    // Deleted edges must not reappear (no ghost resurrection)
    assertThat(survivingNames)
        .as("Deleted edges must not reappear after recovery")
        .doesNotContainAnyElementsOf(deletedNames);

    // All new edges must be present
    assertThat(survivingNames)
        .as("Newly inserted edges must survive recovery")
        .containsAll(newNames);

    // Surviving original edges must be present
    var expectedSurvivors = new HashSet<>(edgeTargetNames);
    expectedSurvivors.removeAll(deletedNames);
    assertThat(survivingNames)
        .as("Surviving original edges must be present after recovery")
        .containsAll(expectedSurvivors);

    txVerify.commit();
    recoveredSession.close();
  }
}
