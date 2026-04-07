
package com.jetbrains.youtrackdb.internal.core.index;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.common.util.RawPair;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.id.SnapshotMarkerRID;
import com.jetbrains.youtrackdb.internal.core.id.TombstoneRID;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationsTable.AtomicOperationsSnapshot;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for {@link IndexesSnapshot} verifying snapshot isolation
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

  private static IndexesSnapshot newSnapshot(long indexId) {
    return new IndexesSnapshot(
        new ConcurrentSkipListMap<>(),
        new ConcurrentSkipListMap<>(AbstractStorage.INDEX_SNAPSHOT_VERSION_COMPARATOR),
        new AtomicLong(), indexId);
  }

  @Before
  public void setUp() {
    snapshot = newSnapshot(INDEX_ID);

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
    var result = new java.util.ArrayList<RawPair<CompositeKey, RID>>();
    snap.emitSnapshotVisibility(pair, visibleVersion, java.util.function.Function.identity(),
        result::add);
    return result;
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
  public void afterFirstRemove_readerBeforeAnyOp_notVisible() {
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
  public void afterFirstRemove_readerWhileAlive_visible() {
    var result = callSnapshotVisibility(pairAfterRemove128(), 126L);
    assertEquals("Record was alive at v125, should be visible via snapshot", 1, result.size());
  }

  @Test
  public void afterFirstRemove_readerStillAliveBeforeRemove_visible() {
    var result = callSnapshotVisibility(pairAfterRemove128(), 127L);
    assertEquals(1, result.size());
  }

  // ========================================================================
  //  After put@130 (over tombstone): primary = {[Foo, #20:0, 130] → ~#20:0}
  //  SnapshotMarkerRID triggers snapshotVisibility the same way as TombstoneRID
  // ========================================================================

  @Test
  public void afterReAdd_readerBeforeAnyOp_notVisible() {
    var result = callSnapshotVisibility(pairAfterPut130(), 121L);
    assertTrue(result.isEmpty());
  }

  /**
   * v=126: SnapshotMarkerRID at v130 >= 126.
   * Snapshot lowerEntry(126) = {125: TombstoneRID}.
   * entry = TombstoneRID → passes → visible (was alive at v125).
   */
  @Test
  public void afterReAdd_readerWhileAliveBeforeFirstRemove_visible() {
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
  public void afterReAdd_readerBetweenRemoveAndReAdd_notVisible() {
    var result = callSnapshotVisibility(pairAfterPut130(), 130L);
    assertTrue("Between remove@128 and re-add@130, record must not be visible", result.isEmpty());
  }

  // ========================================================================
  //  After remove@135 (final state): primary = {[Foo, #20:0, 135] → TombstoneRID}
  //  This is the most realistic scenario: all 4 snapshot entries exist.
  // ========================================================================

  @Test
  public void afterFinalRemove_readerBeforeAnyOp_notVisible() {
    var result = callSnapshotVisibility(pairAfterRemove135(), 121L);
    assertTrue(result.isEmpty());
  }

  @Test
  public void afterFinalRemove_readerDuringFirstAlivePhase_visible() {
    // v=126 < 135: snapshot lowerEntry(126) = {125: Tombstone} → visible
    var result = callSnapshotVisibility(pairAfterRemove135(), 126L);
    assertEquals(1, result.size());
  }

  @Test
  public void afterFinalRemove_readerJustBeforeFirstRemove_visible() {
    var result = callSnapshotVisibility(pairAfterRemove135(), 128L);
    assertEquals(1, result.size());
  }

  /**
   * v=129: snapshot lowerEntry(129) = {128: RecordId}.
   * entry = RecordId → not TombstoneRID → filtered → not visible.
   */
  @Test
  public void afterFinalRemove_readerAtFirstRemove_notVisible() {
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
  public void afterFinalRemove_readerBetweenRemoveAndReAdd_notVisible() {
    var result = callSnapshotVisibility(pairAfterRemove135(), 130L);
    assertTrue("Must not be visible between remove@128 and re-add@130", result.isEmpty());
  }

  /**
   * v=131: snapshot lowerEntry(131) = {130: Tombstone}.
   * entry = Tombstone → passes → visible.
   */
  @Test
  public void afterFinalRemove_readerAtReAdd_visible() {
    var result = callSnapshotVisibility(pairAfterRemove135(), 131L);
    assertEquals(1, result.size());
  }

  @Test
  public void afterFinalRemove_readerAfterReAdd_visible() {
    var result = callSnapshotVisibility(pairAfterRemove135(), 133L);
    assertEquals(1, result.size());
  }

  @Test
  public void afterFinalRemove_readerJustBeforeFinalRemove_visible() {
    var result = callSnapshotVisibility(pairAfterRemove135(), 135L);
    assertEquals(1, result.size());
  }

  // ========================================================================
  //  Edge cases
  // ========================================================================

  @Test
  public void emptySnapshot_futureTombstone_notVisible() {
    IndexesSnapshot emptySnap = newSnapshot(INDEX_ID);
    var pair = new RawPair<>(new CompositeKey("X", RID_20_0, 200L), (RID) TOMBSTONE_20_0);
    var result = callSnapshotVisibility(emptySnap, pair, 101L);
    assertTrue(
        "Future TombstoneRID with empty snapshot should not be visible",
        result.isEmpty());
  }

  @Test
  public void emptySnapshot_futureSnapshotMarker_notVisible() {
    IndexesSnapshot emptySnap = newSnapshot(INDEX_ID);
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
    IndexesSnapshot snap = newSnapshot(INDEX_ID);

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
    IndexesSnapshot snap = newSnapshot(INDEX_ID);
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

  // ========================================================================
  //  Cross-key leak: lowerEntry must not return entries for a different key
  // ========================================================================

  /**
   * Regression test: snapshot contains entries only for key "Bar". When
   * snapshotVisibility is called for a primary entry with key "Foo"
   * (which sorts after "Bar"), lowerEntry must NOT return the "Bar"
   * snapshot entry and surface it as a result for "Foo".
   *
   * Multi-value index keys: CompositeKey(userKey, RID, version).
   * Snapshot has: [Bar, #10:1, 100] → TombstoneRID  (was alive at v100)
   *              [Bar, #10:1, 110] → RecordId       (was removed at v110)
   *
   * Primary entry for Foo: [Foo, #10:1, 150] → TombstoneRID
   *   (version=150 >= visibleVersion=105, so snapshotVisibility is called)
   *
   * lowerEntry([Foo, #10:1, 105]) could return [Bar, #10:1, 100] → TombstoneRID
   *   because "Bar" < "Foo". This would incorrectly make "Foo" visible.
   */
  @Test
  public void lowerEntry_doesNotLeakAcrossKeys_multiValue() {
    IndexesSnapshot snap = newSnapshot(INDEX_ID);
    RID rid = new RecordId(10, 1);

    // Only "Bar" has snapshot entries — "Foo" was never in the snapshot
    snap.addSnapshotPair(
        new CompositeKey("Bar", rid, 100L),
        new CompositeKey("Bar", rid, 110L),
        rid);

    // Primary entry for "Foo" at v150 (TombstoneRID — deleted after snapshot)
    var fooPair = new RawPair<>(
        new CompositeKey("Foo", rid, 150L), (RID) new TombstoneRID(rid));

    // visibleVersion=105: lowerEntry([Foo, #10:1, 105]) must not return
    // the [Bar, #10:1, 100] entry
    var result = callSnapshotVisibility(snap, fooPair, 105L);
    assertTrue(
        "snapshotVisibility must not leak 'Bar' snapshot entry as a result for 'Foo'",
        result.isEmpty());
  }

  /**
   * Same cross-key leak scenario for single-value index keys.
   * Single-value keys: CompositeKey(userKey, version).
   *
   * Snapshot has entries for "Bar", primary entry is for "Foo".
   */
  @Test
  public void lowerEntry_doesNotLeakAcrossKeys_singleValue() {
    IndexesSnapshot snap = newSnapshot(INDEX_ID);
    RID rid = new RecordId(10, 1);

    // Only "Bar" has snapshot entries
    snap.addSnapshotPair(
        new CompositeKey("Bar", 100L),
        new CompositeKey("Bar", 110L),
        rid);

    // Primary entry for "Foo" at v150 (TombstoneRID)
    var fooPair = new RawPair<>(
        new CompositeKey("Foo", 150L), (RID) new TombstoneRID(rid));

    var result = callSnapshotVisibility(snap, fooPair, 105L);
    assertTrue(
        "snapshotVisibility must not leak 'Bar' snapshot entry as a result for 'Foo'",
        result.isEmpty());
  }

  /**
   * Variant: snapshot has entries for "Foo" AND "Bar". When querying for "Foo",
   * lowerEntry must return the "Foo" entry (not "Bar").
   * This validates that when a matching entry exists, the correct one is returned.
   */
  @Test
  public void lowerEntry_returnsCorrectKey_whenBothKeysExist() {
    IndexesSnapshot snap = newSnapshot(INDEX_ID);
    RID ridBar = new RecordId(10, 1);
    RID ridFoo = new RecordId(20, 2);

    // "Bar" snapshot: alive at v100, removed at v110
    snap.addSnapshotPair(
        new CompositeKey("Bar", ridBar, 100L),
        new CompositeKey("Bar", ridBar, 110L),
        ridBar);

    // "Foo" snapshot: alive at v100, removed at v110
    snap.addSnapshotPair(
        new CompositeKey("Foo", ridFoo, 100L),
        new CompositeKey("Foo", ridFoo, 110L),
        ridFoo);

    // Primary entry for "Foo" at v110 (TombstoneRID)
    var fooPair = new RawPair<>(
        new CompositeKey("Foo", ridFoo, 110L), (RID) new TombstoneRID(ridFoo));

    // visibleVersion=105: should find "Foo"@100 (TombstoneRID) → visible
    var result = callSnapshotVisibility(snap, fooPair, 105L);
    assertEquals("Foo should be visible via its own snapshot entry", 1, result.size());
    // Verify the returned RID is Foo's, not Bar's
    assertEquals(ridFoo, result.getFirst().second());
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
  public void threePhase_afterReAdd_readerBetweenRemoveAndReAdd_notVisible() {
    IndexesSnapshot snap = newSnapshot(INDEX_ID);
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

  // ========================================================================
  //  visibilityFilterMapped() — full pipeline tests
  //  These test the inProgressVersions filtering and the version < visibleVersion
  //  branches that are only reachable through visibilityFilterMapped(), not through
  //  emitSnapshotVisibility() alone.
  // ========================================================================

  /**
   * Creates a minimal {@link AtomicOperation} proxy that only supports
   * {@code getAtomicOperationsSnapshot()} — sufficient for visibilityFilterMapped().
   */
  private static AtomicOperation atomicOpStub(long visibleVersion,
      LongOpenHashSet inProgress) {
    var snap = new AtomicOperationsSnapshot(
        visibleVersion, visibleVersion, inProgress, visibleVersion);
    return (AtomicOperation) Proxy.newProxyInstance(
        AtomicOperation.class.getClassLoader(),
        new Class[] {AtomicOperation.class},
        (proxy, method, args) -> {
          if ("getAtomicOperationsSnapshot".equals(method.getName())) {
            return snap;
          }
          throw new UnsupportedOperationException(method.getName());
        });
  }

  private List<RawPair<CompositeKey, RID>> filterMapped(
      IndexesSnapshot snap, long visibleVersion, LongOpenHashSet inProgress,
      List<RawPair<CompositeKey, RID>> entries) {
    return snap
        .visibilityFilter(
            atomicOpStub(visibleVersion, inProgress),
            entries.stream())
        .toList();
  }

  private List<RawPair<CompositeKey, RID>> filterMapped(
      IndexesSnapshot snap, long visibleVersion,
      List<RawPair<CompositeKey, RID>> entries) {
    return filterMapped(snap, visibleVersion, LongOpenHashSet.of(), entries);
  }

  // --- inProgressVersions filtering ---

  /**
   * An entry whose version matches an in-progress TX must be skipped, even if
   * the version is below the visible threshold and the RID is a plain RecordId
   * (which would normally be emitted).
   */
  @Test
  public void inProgress_recordId_filteredOut() {
    var snap = newSnapshot(INDEX_ID);
    var pair = new RawPair<>(new CompositeKey("Foo", RID_20_0, 100L), RID_20_0);

    // version=100 < visibleVersion=200, but version=100 is in-progress → filtered
    var result = filterMapped(snap, 200L, LongOpenHashSet.of(100L), List.of(pair));
    assertTrue("Entry from in-progress TX must be filtered out", result.isEmpty());
  }

  /**
   * A TombstoneRID entry from an in-progress TX must also be filtered.
   */
  @Test
  public void inProgress_tombstoneRid_filteredOut() {
    var snap = newSnapshot(INDEX_ID);
    var pair = new RawPair<>(
        new CompositeKey("Foo", RID_20_0, 100L), (RID) new TombstoneRID(RID_20_0));

    var result = filterMapped(snap, 200L, LongOpenHashSet.of(100L), List.of(pair));
    assertTrue("Tombstone from in-progress TX must be filtered out", result.isEmpty());
  }

  /**
   * A SnapshotMarkerRID entry from an in-progress TX must also be filtered.
   */
  @Test
  public void inProgress_snapshotMarkerRid_filteredOut() {
    var snap = newSnapshot(INDEX_ID);
    var pair = new RawPair<>(
        new CompositeKey("Foo", RID_20_0, 100L), (RID) new SnapshotMarkerRID(RID_20_0));

    var result = filterMapped(snap, 200L, LongOpenHashSet.of(100L), List.of(pair));
    assertTrue("SnapshotMarkerRID from in-progress TX must be filtered out",
        result.isEmpty());
  }

  /**
   * When in-progress set is non-empty but does NOT contain the entry's version,
   * the entry must still pass through normally.
   */
  @Test
  public void inProgress_nonMatchingVersion_passesThrough() {
    var snap = newSnapshot(INDEX_ID);
    var pair = new RawPair<>(new CompositeKey("Foo", RID_20_0, 100L), RID_20_0);

    // version=100 is NOT in the in-progress set {99, 101}
    var result = filterMapped(snap, 200L, LongOpenHashSet.of(99L, 101L), List.of(pair));
    assertEquals("Entry not in in-progress set should pass through", 1, result.size());
  }

  // --- version < visibleVersion: committed entries by RID type ---

  /**
   * A committed RecordId entry (version < visibleVersion) represents a live record
   * and must be emitted as-is.
   */
  @Test
  public void committed_recordId_visible() {
    var snap = newSnapshot(INDEX_ID);
    var pair = new RawPair<>(new CompositeKey("Foo", RID_20_0, 100L), RID_20_0);

    var result = filterMapped(snap, 200L, List.of(pair));
    assertEquals("Committed RecordId must be visible", 1, result.size());
    assertEquals(RID_20_0, result.getFirst().second());
  }

  /**
   * A committed TombstoneRID entry (version < visibleVersion) represents a deleted
   * record and must be filtered out.
   */
  @Test
  public void committed_tombstoneRid_filteredOut() {
    var snap = newSnapshot(INDEX_ID);
    var pair = new RawPair<>(
        new CompositeKey("Foo", RID_20_0, 100L), (RID) new TombstoneRID(RID_20_0));

    var result = filterMapped(snap, 200L, List.of(pair));
    assertTrue("Committed TombstoneRID must not be visible", result.isEmpty());
  }

  /**
   * A committed SnapshotMarkerRID (version < visibleVersion) represents a re-added
   * record. It must be emitted with the unwrapped identity RID (not the marker).
   */
  @Test
  public void committed_snapshotMarkerRid_emittedWithIdentity() {
    var snap = newSnapshot(INDEX_ID);
    var marker = new SnapshotMarkerRID(RID_20_0);
    var pair = new RawPair<>(new CompositeKey("Foo", RID_20_0, 100L), (RID) marker);

    var result = filterMapped(snap, 200L, List.of(pair));
    assertEquals("Committed SnapshotMarkerRID must be visible", 1, result.size());
    // The emitted RID must be the unwrapped identity, not the SnapshotMarkerRID
    assertEquals(RID_20_0, result.getFirst().second());
    assertTrue("Emitted RID must be a plain RecordId, not SnapshotMarkerRID",
        result.getFirst().second() instanceof RecordId);
  }

  // --- version >= visibleVersion: phantom entries ---

  /**
   * A RecordId entry with version >= visibleVersion is a phantom insert and must
   * be filtered out.
   */
  @Test
  public void phantom_recordId_filteredOut() {
    var snap = newSnapshot(INDEX_ID);
    var pair = new RawPair<>(new CompositeKey("Foo", RID_20_0, 200L), RID_20_0);

    var result = filterMapped(snap, 200L, List.of(pair));
    assertTrue("Phantom RecordId must not be visible", result.isEmpty());
  }

  // --- null atomicOperation ---

  /**
   * When atomicOperation is null, visibleVersion defaults to Long.MAX_VALUE and
   * inProgressVersions is empty. All non-tombstone entries should be visible.
   */
  @Test
  public void nullAtomicOp_allNonTombstoneVisible() {
    var snap = newSnapshot(INDEX_ID);
    var alive = new RawPair<>(new CompositeKey("Foo", RID_20_0, 100L), RID_20_0);
    var dead = new RawPair<>(
        new CompositeKey("Bar", RID_20_0, 100L), (RID) new TombstoneRID(RID_20_0));
    var marker = new RawPair<>(
        new CompositeKey("Baz", RID_20_0, 100L), (RID) new SnapshotMarkerRID(RID_20_0));

    var result = snap
        .visibilityFilter(null, Stream.of(alive, dead, marker))
        .toList();

    assertEquals("Only alive and marker entries should be visible", 2, result.size());
  }

  // --- mixed stream ---

  /**
   * A mixed stream with committed, in-progress, and phantom entries should
   * produce exactly the expected visible subset in a single pass.
   */
  @Test
  public void mixedStream_correctFiltering() {
    var snap = newSnapshot(INDEX_ID);
    var ridA = new RecordId(10, 1);
    var ridB = new RecordId(10, 2);
    var ridC = new RecordId(10, 3);

    // Committed alive (version=80 < visibleVersion=100)
    var committedAlive = new RawPair<>(
        new CompositeKey("A", ridA, 80L), (RID) ridA);
    // Committed tombstone (version=90 < visibleVersion=100)
    var committedDead = new RawPair<>(
        new CompositeKey("B", ridB, 90L), (RID) new TombstoneRID(ridB));
    // In-progress (version=95 is in the in-progress set)
    var inProgressEntry = new RawPair<>(
        new CompositeKey("C", ridC, 95L), (RID) ridC);
    // Phantom (version=110 >= visibleVersion=100)
    var phantom = new RawPair<>(
        new CompositeKey("D", ridA, 110L), (RID) ridA);

    var result = filterMapped(snap, 100L, LongOpenHashSet.of(95L),
        List.of(committedAlive, committedDead, inProgressEntry, phantom));

    assertEquals("Only the committed alive entry should survive", 1, result.size());
    assertEquals(ridA, result.getFirst().second());
  }

  // --- in-progress TX replaces a committed version ---

  /**
   * Regression test for a visibility bug where an in-progress TX that replaces a
   * committed entry makes the key completely invisible.
   *
   * Scenario:
   * <ol>
   *   <li>TX1 inserts key "A" at version 100 (committed).
   *       Primary B-Tree: {@code [A, #20:0, 100] → #20:0}</li>
   *   <li>TX2 updates key "A" at version 105 (in-progress). The old v100 entry is
   *       replaced in the B-Tree with a SnapshotMarkerRID at v105, and the old value
   *       is moved to the snapshot via addSnapshotPair.
   *       Primary B-Tree: {@code [A, #20:0, 105] → ~#20:0} (SnapshotMarkerRID)
   *       Snapshot: {@code [A, #20:0, 100] → -#20:0} (TombstoneRID = was alive at v100)
   *                 {@code [A, #20:0, 105] →  #20:0} (RecordId = was replaced at v105)</li>
   *   <li>TX3 reads with visibleVersion=104, inProgressVersions={105}.
   *       TX3 encounters the primary entry [A, #20:0, 105] → SnapshotMarkerRID.
   *       Version 105 is in the in-progress set, so the current code skips the entry
   *       entirely. But the old committed value at v100 (visible to TX3) was removed
   *       from the B-Tree and only exists in the snapshot. Key "A" becomes invisible
   *       to TX3 when it should be visible.</li>
   * </ol>
   *
   * Expected: TX3 should see key "A" via the snapshot (was alive at v100, which is
   * before TX3's visibleVersion=104).
   */
  @Test
  public void inProgress_replacesCommittedEntry_oldVersionVisibleViaSnapshot() {
    var snap = newSnapshot(INDEX_ID);

    // TX2 at v105 replaces the committed v100 entry → snapshot pair created
    snap.addSnapshotPair(
        new CompositeKey("A", RID_20_0, 100L),
        new CompositeKey("A", RID_20_0, 105L),
        RID_20_0);

    // Primary B-Tree after TX2's update: SnapshotMarkerRID at v105
    var primaryEntry = new RawPair<>(
        new CompositeKey("A", RID_20_0, 105L), (RID) SNAPSHOT_MARKER_20_0);

    // TX3: visibleVersion=104, inProgressVersions={105}
    var result = filterMapped(snap, 104L, LongOpenHashSet.of(105L), List.of(primaryEntry));

    assertEquals(
        "In-progress TX replaced committed entry — old version should be visible via snapshot",
        1, result.size());
    assertEquals(RID_20_0, result.getFirst().second());
  }

  /**
   * Same scenario as above but with a TombstoneRID in the primary B-Tree (in-progress
   * TX deletes rather than updates), and the snapshot holds the previously committed value.
   *
   * TX1 inserts key "B" at v200 (committed). TX2 deletes key "B" at v210 (in-progress).
   * Primary: {@code [B, #20:0, 210] → -#20:0} (TombstoneRID).
   * Snapshot: v200 → TombstoneRID (was alive), v210 → RecordId (was removed).
   * TX3 reads with visibleVersion=208, inProgressVersions={210}.
   * Expected: TX3 should see "B" (was alive at v200 < visibleVersion=208).
   */
  @Test
  public void inProgress_deletesCommittedEntry_oldVersionVisibleViaSnapshot() {
    var snap = newSnapshot(INDEX_ID);

    // TX2 at v210 deletes the committed v200 entry → snapshot pair created
    snap.addSnapshotPair(
        new CompositeKey("B", RID_20_0, 200L),
        new CompositeKey("B", RID_20_0, 210L),
        RID_20_0);

    // Primary B-Tree after TX2's delete: TombstoneRID at v210
    var primaryEntry = new RawPair<>(
        new CompositeKey("B", RID_20_0, 210L), (RID) TOMBSTONE_20_0);

    // TX3: visibleVersion=208, inProgressVersions={210}
    var result = filterMapped(snap, 208L, LongOpenHashSet.of(210L), List.of(primaryEntry));

    assertEquals(
        "In-progress TX deleted committed entry — old version should be visible via snapshot",
        1, result.size());
    assertEquals(RID_20_0, result.getFirst().second());
  }

  /**
   * Variant: in-progress TX replaces a committed entry, but the reader's
   * visibleVersion is before the committed version too. The old version should
   * NOT be visible because it was committed after the reader's snapshot.
   *
   * TX1 inserts at v100. TX2 updates at v105 (in-progress).
   * TX3 reads with visibleVersion=99, inProgressVersions={105}.
   * Expected: not visible (v100 commit is after TX3's snapshot).
   */
  @Test
  public void inProgress_replacesCommittedEntry_readerBeforeCommit_notVisible() {
    var snap = newSnapshot(INDEX_ID);

    snap.addSnapshotPair(
        new CompositeKey("A", RID_20_0, 100L),
        new CompositeKey("A", RID_20_0, 105L),
        RID_20_0);

    var primaryEntry = new RawPair<>(
        new CompositeKey("A", RID_20_0, 105L), (RID) SNAPSHOT_MARKER_20_0);

    // TX3: visibleVersion=99, inProgressVersions={105}
    var result = filterMapped(snap, 99L, LongOpenHashSet.of(105L), List.of(primaryEntry));

    assertTrue(
        "Reader's snapshot predates the committed version — should not be visible",
        result.isEmpty());
  }

  /**
   * Variant: in-progress TX replaces a committed entry that was itself a deletion
   * (TombstoneRID in the snapshot). The old "alive" version is even further back.
   *
   * TX1 inserts at v90, TX2 deletes at v100 (committed), TX3 re-inserts at v110 (in-progress).
   * Snapshot from TX2's delete: v90 → TombstoneRID (was alive), v100 → RecordId (was removed).
   * Snapshot from TX3's re-insert: v100 → TombstoneRID (tombstone), v110 → RecordId (replaced).
   * TX4 reads with visibleVersion=95, inProgressVersions={110}.
   * Expected: TX4 should see the key (was alive at v90 < visibleVersion=95).
   */
  @Test
  public void inProgress_replacesCommittedDeletion_deepHistoryVisible() {
    var snap = newSnapshot(INDEX_ID);

    // TX2's delete at v100: was alive at v90, removed at v100
    snap.addSnapshotPair(
        new CompositeKey("C", RID_20_0, 90L),
        new CompositeKey("C", RID_20_0, 100L),
        RID_20_0);

    // TX3's re-insert at v110: the tombstone at v100 is replaced
    snap.addSnapshotPair(
        new CompositeKey("C", RID_20_0, 100L),
        new CompositeKey("C", RID_20_0, 110L),
        RID_20_0);

    // Primary after TX3's re-insert: SnapshotMarkerRID at v110
    var primaryEntry = new RawPair<>(
        new CompositeKey("C", RID_20_0, 110L), (RID) SNAPSHOT_MARKER_20_0);

    // TX4: visibleVersion=95, inProgressVersions={110}
    var result = filterMapped(snap, 95L, LongOpenHashSet.of(110L), List.of(primaryEntry));

    assertEquals(
        "Deep history: record was alive at v90, should be visible at visibleVersion=95",
        1, result.size());
    assertEquals(RID_20_0, result.getFirst().second());
  }

  // --- visibilityFilterMapped() with non-identity keyMapper ---

  /**
   * visibilityFilterMapped() with a non-identity keyMapper must apply the
   * mapper to the CompositeKey of each visible entry and must not apply it
   * to filtered-out entries. Includes a tombstoned entry that should be
   * filtered out alongside the visible entry.
   */
  @Test
  public void visibilityFilterMapped_appliesKeyMapper() {
    var snap = newSnapshot(INDEX_ID);
    var visible = new RawPair<>(new CompositeKey("Foo", RID_20_0, 100L), RID_20_0);
    var tombstoned = new RawPair<>(
        new CompositeKey("Bar", RID_20_0, 100L), (RID) TOMBSTONE_20_0);

    // keyMapper extracts just the first element of the CompositeKey
    Function<CompositeKey, Object> keyMapper = k -> k.getKeys().getFirst();

    var result = snap
        .visibilityFilterMapped(
            atomicOpStub(200L, LongOpenHashSet.of()),
            Stream.of(visible, tombstoned),
            keyMapper)
        .toList();

    assertEquals(1, result.size());
    assertEquals("Foo", result.getFirst().first());
    assertEquals(RID_20_0, result.getFirst().second());
  }

  /**
   * When an in-progress entry falls back to snapshot lookup, the keyMapper
   * must still be applied to the original CompositeKey, not to any
   * intermediate object.
   */
  @Test
  public void visibilityFilterMapped_keyMapperAppliedOnSnapshotFallback() {
    var snap = newSnapshot(INDEX_ID);
    // Set up snapshot: was alive at v125, removed at v128
    snap.addSnapshotPair(
        new CompositeKey("Foo", RID_20_0, 125L),
        new CompositeKey("Foo", RID_20_0, 128L),
        RID_20_0);

    // B-tree entry: in-progress TX at v128 with TombstoneRID
    var pair = new RawPair<>(
        new CompositeKey("Foo", RID_20_0, 128L), (RID) new TombstoneRID(RID_20_0));

    Function<CompositeKey, Object> keyMapper = k -> k.getKeys().getFirst();

    // visibleVersion=127: snapshot lowerEntry(127) finds v125 TombstoneRID → visible
    var result = snap
        .visibilityFilterMapped(
            atomicOpStub(127L, LongOpenHashSet.of(128L)),
            Stream.of(pair),
            keyMapper)
        .toList();

    assertEquals(1, result.size());
    assertEquals("Foo", result.getFirst().first());
    assertEquals(RID_20_0, result.getFirst().second());
  }

  // ========================================================================
  //  checkVisibility() — direct single-entry visibility checks
  //  These test the new checkVisibility() method which encapsulates the full
  //  visibility decision for a single entry, returning @Nullable RID.
  // ========================================================================

  /**
   * A committed RecordId (version < visibleVersion) is a live record and must
   * be returned as-is.
   */
  @Test
  public void checkVisibility_committed_recordId_visible() {
    var snap = newSnapshot(INDEX_ID);
    var key = new CompositeKey("Foo", RID_20_0, 100L);

    var result = snap.checkVisibility(key, RID_20_0, 200L, LongOpenHashSet.of());
    assertEquals("Committed RecordId must be visible", RID_20_0, result);
    assertTrue("Must return plain RecordId", result instanceof RecordId);
  }

  /**
   * A committed TombstoneRID (version < visibleVersion) represents a deleted
   * record and must return null.
   */
  @Test
  public void checkVisibility_committed_tombstoneRid_null() {
    var snap = newSnapshot(INDEX_ID);
    var key = new CompositeKey("Foo", RID_20_0, 100L);

    var result = snap.checkVisibility(key, TOMBSTONE_20_0, 200L, LongOpenHashSet.of());
    assertNull("Committed TombstoneRID must not be visible", result);
  }

  /**
   * A committed SnapshotMarkerRID (version < visibleVersion) represents a
   * re-added record. Must return the unwrapped identity RID.
   */
  @Test
  public void checkVisibility_committed_snapshotMarkerRid_returnsIdentity() {
    var snap = newSnapshot(INDEX_ID);
    var key = new CompositeKey("Foo", RID_20_0, 100L);

    var result = snap.checkVisibility(key, SNAPSHOT_MARKER_20_0, 200L, LongOpenHashSet.of());
    assertEquals("Committed SnapshotMarkerRID must be visible", RID_20_0, result);
    assertTrue("Must return plain RecordId, not SnapshotMarkerRID",
        result instanceof RecordId);
  }

  /**
   * An in-progress RecordId entry must return null — the entry is a new insert
   * with no prior history in the snapshot.
   */
  @Test
  public void checkVisibility_inProgress_recordId_null() {
    var snap = newSnapshot(INDEX_ID);
    var key = new CompositeKey("Foo", RID_20_0, 100L);

    var result = snap.checkVisibility(key, RID_20_0, 200L, LongOpenHashSet.of(100L));
    assertNull("In-progress RecordId must not be visible", result);
  }

  /**
   * An in-progress TombstoneRID entry must fall through to the snapshot lookup.
   * When the snapshot contains a prior visible version, it must be returned.
   */
  @Test
  public void checkVisibility_inProgress_tombstoneRid_snapshotFallback() {
    var snap = newSnapshot(INDEX_ID);

    // Snapshot: was alive at v90, removed at v100
    snap.addSnapshotPair(
        new CompositeKey("Foo", RID_20_0, 90L),
        new CompositeKey("Foo", RID_20_0, 100L),
        RID_20_0);

    var key = new CompositeKey("Foo", RID_20_0, 100L);

    // version=100 is in-progress, visibleVersion=95 — snapshot has v90 TombstoneRID
    var result = snap.checkVisibility(key, TOMBSTONE_20_0, 95L, LongOpenHashSet.of(100L));
    assertEquals("In-progress TombstoneRID should fall back to snapshot", RID_20_0, result);
    assertTrue("Snapshot fallback must return plain RecordId",
        result instanceof RecordId);
  }

  /**
   * An in-progress TombstoneRID entry with no matching snapshot entry must
   * return null.
   */
  @Test
  public void checkVisibility_inProgress_tombstoneRid_noSnapshotEntry_null() {
    var snap = newSnapshot(INDEX_ID);
    var key = new CompositeKey("Foo", RID_20_0, 100L);

    var result = snap.checkVisibility(key, TOMBSTONE_20_0, 95L, LongOpenHashSet.of(100L));
    assertNull("In-progress TombstoneRID with no snapshot entry must not be visible", result);
  }

  /**
   * An in-progress SnapshotMarkerRID entry must also fall through to the
   * snapshot lookup, same as TombstoneRID.
   */
  @Test
  public void checkVisibility_inProgress_snapshotMarkerRid_snapshotFallback() {
    var snap = newSnapshot(INDEX_ID);

    snap.addSnapshotPair(
        new CompositeKey("Foo", RID_20_0, 90L),
        new CompositeKey("Foo", RID_20_0, 100L),
        RID_20_0);

    var key = new CompositeKey("Foo", RID_20_0, 100L);

    var result = snap.checkVisibility(
        key, SNAPSHOT_MARKER_20_0, 95L, LongOpenHashSet.of(100L));
    assertEquals("In-progress SnapshotMarkerRID should fall back to snapshot",
        RID_20_0, result);
    assertTrue("Snapshot fallback must return plain RecordId",
        result instanceof RecordId);
  }

  /**
   * A phantom RecordId (version > visibleVersion) must return null.
   */
  @Test
  public void checkVisibility_phantom_recordId_null() {
    var snap = newSnapshot(INDEX_ID);
    var key = new CompositeKey("Foo", RID_20_0, 300L);

    var result = snap.checkVisibility(key, RID_20_0, 200L, LongOpenHashSet.of());
    assertNull("Phantom RecordId must not be visible", result);
  }

  /**
   * A phantom TombstoneRID (version >= visibleVersion) must fall through to the
   * snapshot lookup. When the snapshot has a prior visible version, return it.
   */
  @Test
  public void checkVisibility_phantom_tombstoneRid_snapshotFallback() {
    var snap = newSnapshot(INDEX_ID);

    snap.addSnapshotPair(
        new CompositeKey("Foo", RID_20_0, 90L),
        new CompositeKey("Foo", RID_20_0, 100L),
        RID_20_0);

    var key = new CompositeKey("Foo", RID_20_0, 100L);

    // version=100 >= visibleVersion=95, TombstoneRID → snapshot fallback
    var result = snap.checkVisibility(key, TOMBSTONE_20_0, 95L, LongOpenHashSet.of());
    assertEquals("Phantom TombstoneRID should fall back to snapshot", RID_20_0, result);
    assertTrue("Snapshot fallback must return plain RecordId",
        result instanceof RecordId);
  }

  /**
   * When the inProgressVersions set is empty, the in-progress check is
   * short-circuited — the entry proceeds to committed logic and is visible.
   * When the same version IS in the in-progress set, it must be filtered out.
   */
  @Test
  public void checkVisibility_emptyInProgressSet_vs_matching_contrasted() {
    var snap = newSnapshot(INDEX_ID);
    var key = new CompositeKey("Foo", RID_20_0, 100L);

    // Empty in-progress set: version 100 < visibleVersion 200 → committed, visible
    var visible = snap.checkVisibility(key, RID_20_0, 200L, LongOpenHashSet.of());
    assertEquals("Empty inProgress set: committed RecordId must be visible", RID_20_0, visible);

    // Same entry IS filtered when its version is in the in-progress set
    var filtered = snap.checkVisibility(key, RID_20_0, 200L, LongOpenHashSet.of(100L));
    assertNull("Same entry must be null when version is in-progress", filtered);
  }

  /**
   * When the inProgressVersions set is non-empty but does not contain the
   * entry's version, the entry must proceed to committed/phantom logic.
   */
  @Test
  public void checkVisibility_nonMatchingInProgressVersion_passesThrough() {
    var snap = newSnapshot(INDEX_ID);
    var key = new CompositeKey("Foo", RID_20_0, 100L);

    // version=100 is NOT in the in-progress set {99, 101}
    var result = snap.checkVisibility(key, RID_20_0, 200L, LongOpenHashSet.of(99L, 101L));
    assertEquals("Non-matching in-progress version should not filter entry",
        RID_20_0, result);
  }

  // --- TC1: phantom SnapshotMarkerRID ---

  /**
   * A phantom SnapshotMarkerRID (version >= visibleVersion) must fall through to
   * the snapshot lookup, same as TombstoneRID.
   */
  @Test
  public void checkVisibility_phantom_snapshotMarkerRid_snapshotFallback() {
    var snap = newSnapshot(INDEX_ID);

    snap.addSnapshotPair(
        new CompositeKey("Foo", RID_20_0, 90L),
        new CompositeKey("Foo", RID_20_0, 100L),
        RID_20_0);

    var key = new CompositeKey("Foo", RID_20_0, 100L);

    // version=100 >= visibleVersion=95, SnapshotMarkerRID → snapshot fallback
    var result = snap.checkVisibility(key, SNAPSHOT_MARKER_20_0, 95L, LongOpenHashSet.of());
    assertEquals("Phantom SnapshotMarkerRID should fall back to snapshot", RID_20_0, result);
    assertTrue("Snapshot fallback must return plain RecordId",
        result instanceof RecordId);
  }

  // --- TC2: exact boundary version == visibleVersion ---

  /**
   * When version == visibleVersion with a TombstoneRID and no snapshot entry,
   * the entry is a phantom (not committed) and must return null.
   * Validates the strict {@code <} comparison (not {@code <=}).
   */
  @Test
  public void checkVisibility_exactBoundary_tombstoneRid_noSnapshot_null() {
    var snap = newSnapshot(INDEX_ID);
    var key = new CompositeKey("Foo", RID_20_0, 100L);

    // version=100 == visibleVersion=100 → phantom path, TombstoneRID, no snapshot → null
    var result = snap.checkVisibility(key, TOMBSTONE_20_0, 100L, LongOpenHashSet.of());
    assertNull("version == visibleVersion is phantom, not committed", result);
  }

  // --- TC3: cross-key leak through checkVisibility ---

  /**
   * checkVisibility() must not leak snapshot entries from a different user key.
   * Snapshot has entries only for "Bar"; a phantom TombstoneRID for "Foo" must
   * not pick up "Bar"'s snapshot entry via lowerEntry().
   */
  @Test
  public void checkVisibility_phantom_tombstoneRid_crossKeyLeak_null() {
    var snap = newSnapshot(INDEX_ID);
    var rid = new RecordId(10, 1);

    // Only "Bar" has snapshot entries
    snap.addSnapshotPair(
        new CompositeKey("Bar", rid, 100L),
        new CompositeKey("Bar", rid, 110L),
        rid);

    // Phantom TombstoneRID for "Foo" — lowerEntry might find "Bar"'s entry
    var key = new CompositeKey("Foo", rid, 150L);
    var result = snap.checkVisibility(key, new TombstoneRID(rid), 105L, LongOpenHashSet.of());
    assertNull("Must not leak Bar's snapshot entry for Foo query", result);
  }

  // --- TC4: single-value key through checkVisibility ---

  /**
   * checkVisibility() with a single-value index key (CompositeKey(userKey, version))
   * must correctly look up snapshot entries where userKeyLen=1.
   */
  @Test
  public void checkVisibility_singleValueKey_phantom_tombstoneRid_snapshotFallback() {
    var snap = newSnapshot(INDEX_ID);
    var rid = new RecordId(10, 1);

    // Single-value key: CompositeKey(userKey, version)
    snap.addSnapshotPair(
        new CompositeKey("Foo", 90L),
        new CompositeKey("Foo", 100L),
        rid);

    var key = new CompositeKey("Foo", 100L);
    var result = snap.checkVisibility(key, new TombstoneRID(rid), 95L, LongOpenHashSet.of());
    assertEquals("Single-value key snapshot fallback should find entry", rid, result);
    assertTrue("Snapshot fallback must return plain RecordId",
        result instanceof RecordId);
  }

  // --- TC1 fix: exact boundary tests for RecordId and SnapshotMarkerRID ---

  /**
   * version == visibleVersion with RecordId must take the phantom path (null),
   * not the committed path. Guards the strict {@code <} comparison against
   * accidental {@code <=} regression.
   */
  @Test
  public void checkVisibility_exactBoundary_recordId_null() {
    var snap = newSnapshot(INDEX_ID);
    var key = new CompositeKey("Foo", RID_20_0, 100L);

    var result = snap.checkVisibility(key, RID_20_0, 100L, LongOpenHashSet.of());
    assertNull("version == visibleVersion must be phantom, not committed", result);
  }

  /**
   * version == visibleVersion with SnapshotMarkerRID must take the phantom path
   * (snapshot fallback), not the committed path (which would return identity
   * directly). With no snapshot entries, must return null.
   */
  @Test
  public void checkVisibility_exactBoundary_snapshotMarkerRid_null() {
    var snap = newSnapshot(INDEX_ID);
    var key = new CompositeKey("Foo", RID_20_0, 100L);

    var result = snap.checkVisibility(key, SNAPSHOT_MARKER_20_0, 100L, LongOpenHashSet.of());
    assertNull("version == visibleVersion SnapshotMarkerRID with no snapshot must be null",
        result);
  }

  // --- TC3: snapshot has RecordId guard (was-removed), not TombstoneRID ---

  /**
   * When lookupSnapshotRid's lowerEntry finds a RecordId guard (meaning
   * "was removed at this version"), it must return null. This happens when
   * the reader's visibleVersion is after the removal version.
   */
  @Test
  public void checkVisibility_phantom_tombstoneRid_snapshotHasRecordIdGuard_null() {
    var snap = newSnapshot(INDEX_ID);

    // was alive at v90, removed at v100
    snap.addSnapshotPair(
        new CompositeKey("Foo", RID_20_0, 90L),
        new CompositeKey("Foo", RID_20_0, 100L),
        RID_20_0);

    // Query at visibleVersion=101: lowerEntry(101) finds v100 → RecordId guard → null
    var key = new CompositeKey("Foo", RID_20_0, 110L);
    var result = snap.checkVisibility(key, TOMBSTONE_20_0, 101L, LongOpenHashSet.of());
    assertNull("Snapshot's RecordId guard means 'was removed' — must return null", result);
  }

  // --- TC4: non-empty snapshot where lowerEntry returns null ---

  /**
   * When the snapshot is non-empty but all entries sort after the search key,
   * lowerEntry returns null and lookupSnapshotRid must return null.
   */
  @Test
  public void checkVisibility_phantom_tombstoneRid_snapshotAllAfterSearchKey_null() {
    var snap = newSnapshot(INDEX_ID);

    // Snapshot entry at v200 — but query visibleVersion is 50
    snap.addSnapshotPair(
        new CompositeKey("Foo", RID_20_0, 200L),
        new CompositeKey("Foo", RID_20_0, 210L),
        RID_20_0);

    var key = new CompositeKey("Foo", RID_20_0, 220L);
    // visibleVersion=50 → searchKey version=50, all snapshot entries are after it
    var result = snap.checkVisibility(key, TOMBSTONE_20_0, 50L, LongOpenHashSet.of());
    assertNull("No snapshot entry before search key — must return null", result);
  }
}
