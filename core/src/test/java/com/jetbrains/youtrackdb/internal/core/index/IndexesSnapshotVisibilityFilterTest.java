
package com.jetbrains.youtrackdb.internal.core.index;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.common.util.RawPair;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.id.SnapshotMarkerRID;
import com.jetbrains.youtrackdb.internal.core.id.TombstoneRID;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for {@link IndexesSnapshot#snapshotVisibility} verifying snapshot isolation
 * of index entries as seen through the primary B-Tree stream.
 *
 * <h3>Key insight</h3>
 * The primary B-Tree only holds the <b>latest</b> entry per (key, RID) pair.
 * Previous versions are preserved exclusively in the snapshot index.
 * The {@code snapshotVisibility} uses the snapshot to reconstruct visibility for
 * readers whose {@code visibleVersion} predates the latest primary entry.
 *
 * <h3>Test scenario — record lifecycle</h3>
 * <ol>
 *   <li>put("Foo", #20:0) at version 125 → primary: {@code [Foo, #20:0, 125] → #20:0}</li>
 *   <li>remove("Foo", #20:0) at version 128 → primary: {@code [Foo, #20:0, 128] → -#20:0}</li>
 *   <li>put("Foo", #20:0) at version 130 (over tombstone) → primary: {@code [Foo, #20:0, 130] → ~#20:0}</li>
 *   <li>remove("Foo", #20:0) at version 135 → primary: {@code [Foo, #20:0, 135] → -#20:0}</li>
 * </ol>
 *
 * <h3>Resulting snapshot index contents (via addSnapshotPair)</h3>
 * <pre>
 *   remove@128 → addSnapshotPair([Foo, #20:0, 125], [Foo, #20:0, 128], #20:0)
 *     produces: [Foo, #20:0, 125] → -#20:0  (TombstoneRID = was alive at v125)
 *               [Foo, #20:0, 128] →  #20:0  (RecordId     = was removed at v128)
 *
 *   remove@135 → addSnapshotPair([Foo, #20:0, 130], [Foo, #20:0, 135], #20:0)
 *     produces: [Foo, #20:0, 130] → -#20:0  (TombstoneRID = was alive at v130)
 *               [Foo, #20:0, 135] →  #20:0  (RecordId     = was removed at v135)
 * </pre>
 *
 * <h3>Primary B-Tree after all operations</h3>
 * Only the latest entry remains: {@code [Foo, #20:0, 135] → -#20:0} (TombstoneRID).
 *
 * <h3>Expected visibility (via snapshotVisibility — only called for version >= visible)</h3>
 * {@code snapshotVisibility} is invoked when the primary entry's version >= visibleVersion
 * and the RID is a TombstoneRID or SnapshotMarkerRID. It looks up the snapshot to determine
 * whether the record was alive at the given visibleVersion.
 * <pre>
 *   visibleVersion=121 → not visible (before first add, no snapshot entry)
 *   visibleVersion=126 → visible     (snapshot has TombstoneRID@125 = was alive)
 *   visibleVersion=130 → not visible (snapshot has RecordId@128 = was removed)
 *   visibleVersion=133 → visible     (snapshot has TombstoneRID@130 = was alive)
 *   visibleVersion=136 → N/A         (version < visible, handled by caller, not snapshotVisibility)
 * </pre>
 */
public class IndexesSnapshotVisibilityFilterTest {

  private static final long INDEX_ID = 1L;
  private static final RID RID_20_0 = new RecordId(20, 0);
  private static final TombstoneRID TOMBSTONE_20_0 = new TombstoneRID(RID_20_0);
  private static final SnapshotMarkerRID SNAPSHOT_MARKER_20_0 = new SnapshotMarkerRID(RID_20_0);

  private IndexesSnapshot snapshot;

  @Before
  public void setUp() {
    snapshot = new IndexesSnapshot().subIndexSnapshot(INDEX_ID);

    // remove@128: was alive at v125, removed at v128
    snapshot.addSnapshotPair(
        new CompositeKey("Foo", RID_20_0, 125L),
        new CompositeKey("Foo", RID_20_0, 128L),
        RID_20_0);

    // remove@135: was alive at v130, removed at v135
    snapshot.addSnapshotPair(
        new CompositeKey("Foo", RID_20_0, 130L),
        new CompositeKey("Foo", RID_20_0, 135L),
        RID_20_0);
  }

  // ========================================================================
  // Helper: create a primary B-Tree pair and call snapshotVisibility
  // ========================================================================

  private List<RawPair<CompositeKey, RID>> callSnapshotVisibility(
      IndexesSnapshot snap, RawPair<CompositeKey, RID> pair, long visibleVersion) {
    return snap.snapshotVisibility(pair, visibleVersion).toList();
  }

  private List<RawPair<CompositeKey, RID>> callSnapshotVisibility(
      RawPair<CompositeKey, RID> pair, long visibleVersion) {
    return callSnapshotVisibility(snapshot, pair, visibleVersion);
  }

  // ========================================================================
  // Primary entry helpers for each lifecycle stage
  // ========================================================================

  /**
   * After step 2 (remove@128): primary tree has a tombstone at v128.
   * The old entry at v125 was deleted from the tree and moved to the snapshot.
   */
  private RawPair<CompositeKey, RID> pairAfterRemove128() {
    return new RawPair<>(new CompositeKey("Foo", RID_20_0, 128L), TOMBSTONE_20_0);
  }

  /**
   * After step 3 (put@130 over tombstone): primary tree has a SnapshotMarkerRID at v130.
   * The tombstone at v128 was removed and replaced.
   */
  private RawPair<CompositeKey, RID> pairAfterPut130() {
    return new RawPair<>(new CompositeKey("Foo", RID_20_0, 130L), SNAPSHOT_MARKER_20_0);
  }

  /**
   * After step 4 (remove@135): primary tree has a tombstone at v135.
   * This is the final state of the primary tree.
   */
  private RawPair<CompositeKey, RID> pairAfterRemove135() {
    return new RawPair<>(new CompositeKey("Foo", RID_20_0, 135L), TOMBSTONE_20_0);
  }

  // ========================================================================
  //  After remove@128: primary = {[Foo, #20:0, 128] → TombstoneRID}
  //  snapshotVisibility is called when version >= visibleVersion
  // ========================================================================

  @Test
  public void afterRemove128_version120_beforeAnyOp_notVisible() {
    // v=121 < 128: snapshot lowerEntry(121) = empty → not visible
    var result = callSnapshotVisibility(pairAfterRemove128(), 121L);
    assertTrue("Before any operation, nothing should be visible", result.isEmpty());
  }

  /**
   * v=126: TombstoneRID entry at v128 >= 126.
   * Snapshot lowerEntry(126) = {125: TombstoneRID(#20:0)}.
   * entry = TombstoneRID → passes → visible.
   * (TombstoneRID in snapshot means "was alive at this version")
   */
  @Test
  public void afterRemove128_version125_wasAlive_visible() {
    var result = callSnapshotVisibility(pairAfterRemove128(), 126L);
    assertEquals("Record was alive at v125, should be visible via snapshot", 1, result.size());
  }

  @Test
  public void afterRemove128_version126_stillAlive_visible() {
    var result = callSnapshotVisibility(pairAfterRemove128(), 127L);
    assertEquals(1, result.size());
  }

  // ========================================================================
  //  After put@130 (over tombstone): primary = {[Foo, #20:0, 130] → ~#20:0}
  //  SnapshotMarkerRID triggers snapshotVisibility the same way as TombstoneRID
  // ========================================================================

  @Test
  public void afterPut130_version120_beforeAll_notVisible() {
    var result = callSnapshotVisibility(pairAfterPut130(), 121L);
    assertTrue(result.isEmpty());
  }

  /**
   * v=126: SnapshotMarkerRID at v130 >= 126.
   * Snapshot lowerEntry(126) = {125: TombstoneRID}.
   * entry = TombstoneRID → passes → visible (was alive at v125).
   */
  @Test
  public void afterPut130_version125_wasAliveBeforeFirstRemove_visible() {
    var result = callSnapshotVisibility(pairAfterPut130(), 126L);
    assertEquals(1, result.size());
  }

  /**
   * v=130: SnapshotMarkerRID at v130 >= 130.
   * Snapshot lowerEntry(130) = {128: RecordId}.
   * entry = RecordId → NOT TombstoneRID → filtered out → not visible.
   *
   * <b>This is the critical test that validates lowerEntry() over floorEntry().</b>
   */
  @Test
  public void afterPut130_version129_betweenRemoveAndReAdd_notVisible() {
    var result = callSnapshotVisibility(pairAfterPut130(), 130L);
    assertTrue("Between remove@128 and re-add@130, record must not be visible", result.isEmpty());
  }

  // ========================================================================
  //  After remove@135 (final state): primary = {[Foo, #20:0, 135] → TombstoneRID}
  //  This is the most realistic scenario: all 4 snapshot entries exist.
  // ========================================================================

  @Test
  public void afterRemove135_version120_beforeAll_notVisible() {
    var result = callSnapshotVisibility(pairAfterRemove135(), 121L);
    assertTrue(result.isEmpty());
  }

  @Test
  public void afterRemove135_version125_wasAlive_visible() {
    // v=126 < 135: snapshot lowerEntry(126) = {125: Tombstone} → visible
    var result = callSnapshotVisibility(pairAfterRemove135(), 126L);
    assertEquals(1, result.size());
  }

  @Test
  public void afterRemove135_version127_stillAliveBeforeRemove128_visible() {
    var result = callSnapshotVisibility(pairAfterRemove135(), 128L);
    assertEquals(1, result.size());
  }

  /**
   * v=129: snapshot lowerEntry(129) = {128: RecordId}.
   * entry = RecordId → not TombstoneRID → filtered → not visible.
   */
  @Test
  public void afterRemove135_version128_atFirstRemove_notVisible() {
    var result = callSnapshotVisibility(pairAfterRemove135(), 129L);
    assertTrue(result.isEmpty());
  }

  /**
   * v=130: snapshot lowerEntry(130) = {128: RecordId}.
   * entry = RecordId → filtered → not visible.
   *
   * <b>Critical: using floorEntry() would pick 130: Tombstone → WRONGLY visible.</b>
   */
  @Test
  public void afterRemove135_version129_betweenRemoveAndReAdd_notVisible() {
    var result = callSnapshotVisibility(pairAfterRemove135(), 130L);
    assertTrue("Must not be visible between remove@128 and re-add@130", result.isEmpty());
  }

  /**
   * v=131: snapshot lowerEntry(131) = {130: Tombstone}.
   * entry = Tombstone → passes → visible.
   */
  @Test
  public void afterRemove135_version130_atReAdd_visible() {
    var result = callSnapshotVisibility(pairAfterRemove135(), 131L);
    assertEquals(1, result.size());
  }

  @Test
  public void afterRemove135_version132_afterReAdd_visible() {
    var result = callSnapshotVisibility(pairAfterRemove135(), 133L);
    assertEquals(1, result.size());
  }

  @Test
  public void afterRemove135_version134_justBeforeFinalRemove_visible() {
    var result = callSnapshotVisibility(pairAfterRemove135(), 135L);
    assertEquals(1, result.size());
  }

  // ========================================================================
  //  Edge cases
  // ========================================================================

  @Test
  public void emptySnapshot_futureTombstone_notVisible() {
    IndexesSnapshot emptySnap = new IndexesSnapshot().subIndexSnapshot(INDEX_ID);
    var pair = new RawPair<>(new CompositeKey("X", RID_20_0, 200L), (RID) TOMBSTONE_20_0);
    var result = callSnapshotVisibility(emptySnap, pair, 101L);
    assertTrue(
        "Future TombstoneRID with empty snapshot should not be visible",
        result.isEmpty());
  }

  @Test
  public void emptySnapshot_futureSnapshotMarker_notVisible() {
    IndexesSnapshot emptySnap = new IndexesSnapshot().subIndexSnapshot(INDEX_ID);
    var pair = new RawPair<>(new CompositeKey("X", RID_20_0, 200L), (RID) SNAPSHOT_MARKER_20_0);
    var result = callSnapshotVisibility(emptySnap, pair, 101L);
    assertTrue(
        "Future SnapshotMarkerRID with empty snapshot should not be visible",
        result.isEmpty());
  }

  // ========================================================================
  //  Multiple records: independent snapshots
  // ========================================================================

  @Test
  public void twoRecords_independentVisibility() {
    IndexesSnapshot snap = new IndexesSnapshot().subIndexSnapshot(INDEX_ID);

    RID ridA = new RecordId(10, 1);

    // Record A: put@100, remove@110
    snap.addSnapshotPair(
        new CompositeKey("Val", ridA, 100L),
        new CompositeKey("Val", ridA, 110L),
        ridA);

    // Primary after A removed: [Val, #10:1, 110] → TombstoneRID(A)
    var pairA = new RawPair<>(new CompositeKey("Val", ridA, 110L), (RID) new TombstoneRID(ridA));

    // At v=108: lowerEntry(108) = {100: Tomb} → visible (A was alive)
    var resultA108 = callSnapshotVisibility(snap, pairA, 108L);
    assertEquals("Record A should be visible at v=108", 1, resultA108.size());

    // At v=113: lowerEntry(113) = {110: RecordId} → not Tombstone → not visible
    var resultA113 = callSnapshotVisibility(snap, pairA, 113L);
    assertTrue("Record A at v=113: snapshot lookup returns RecordId, not visible",
        resultA113.isEmpty());
  }

  // ========================================================================
  //  Three-phase lifecycle: validates lowerEntry() vs floorEntry()
  // ========================================================================

  /**
   * Standalone three-phase test with its own snapshot.
   * add@100, remove@110, re-add@120.
   * After remove@110, primary has tombstone at v110.
   *
   * At v=110: lowerEntry(110) = {100: Tombstone} → visible
   */
  @Test
  public void threePhase_afterRemove_visibleBeforeRemove() {
    IndexesSnapshot snap = new IndexesSnapshot().subIndexSnapshot(INDEX_ID);
    RID rid = new RecordId(30, 5);

    // remove@110 creates the pair
    snap.addSnapshotPair(
        new CompositeKey("Key", rid, 100L),
        new CompositeKey("Key", rid, 110L),
        rid);

    // Primary after remove@110:
    var pair = new RawPair<>(new CompositeKey("Key", rid, 110L), (RID) new TombstoneRID(rid));

    var result = callSnapshotVisibility(snap, pair, 110L);
    assertEquals("Was alive at v=109, should be visible", 1, result.size());
  }

  /**
   * After re-add@120 (over tombstone): primary has SnapshotMarkerRID at v120.
   *
   * At v=116: SnapshotMarkerRID at v120 >= 116.
   * lowerEntry(116) = {110: RecordId}.
   * entry = RecordId → NOT Tombstone → not visible.
   *
   * <b>floorEntry() would return 110: RecordId too, but using lowerEntry() is correct.</b>
   */
  @Test
  public void threePhase_afterReAdd_version115_notVisible() {
    IndexesSnapshot snap = new IndexesSnapshot().subIndexSnapshot(INDEX_ID);
    RID rid = new RecordId(30, 5);

    // remove@110 creates the first pair
    snap.addSnapshotPair(
        new CompositeKey("Key", rid, 100L),
        new CompositeKey("Key", rid, 110L),
        rid);

    // remove@135 would create the second pair, but for this test
    // we only need the re-add@120's TombstoneRID in the snapshot.
    // re-add@120 over tombstone — the "added" half of a future pair:
    // In a full lifecycle this would be part of a pair from a later remove,
    // but here we simulate it as a standalone entry via addSnapshotPair
    // with a synthetic removed key.
    snap.addSnapshotPair(
        new CompositeKey("Key", rid, 120L),
        new CompositeKey("Key", rid, 135L),
        rid);

    // Primary after put@120:
    var pair = new RawPair<>(new CompositeKey("Key", rid, 120L), (RID) new SnapshotMarkerRID(rid));

    var result = callSnapshotVisibility(snap, pair, 116L);
    assertTrue("Between remove@110 and re-add@120, must not be visible", result.isEmpty());
  }
}
