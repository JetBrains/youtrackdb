
package com.jetbrains.youtrackdb.internal.core.index;

import static org.junit.Assert.assertEquals;
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
    var pair = new RawPair<>(new CompositeKey("Foo", RID_20_0, 100L), (RID) RID_20_0);

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
    var pair = new RawPair<>(new CompositeKey("Foo", RID_20_0, 100L), (RID) RID_20_0);

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
    var pair = new RawPair<>(new CompositeKey("Foo", RID_20_0, 200L), (RID) RID_20_0);

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
    var alive = new RawPair<>(new CompositeKey("Foo", RID_20_0, 100L), (RID) RID_20_0);
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
}
