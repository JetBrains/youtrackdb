package com.jetbrains.youtrackdb.internal.core.index.engine.v1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.common.util.RawPair;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.id.SnapshotMarkerRID;
import com.jetbrains.youtrackdb.internal.core.id.TombstoneRID;
import com.jetbrains.youtrackdb.internal.core.index.CompositeKey;
import com.jetbrains.youtrackdb.internal.core.index.IndexesSnapshot;
import com.jetbrains.youtrackdb.internal.core.index.engine.IndexCountDeltaHolder;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.CellBTreeSingleValue;
import java.io.IOException;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentMatchers;

/**
 * Tests for the same-TX re-put guard in {@link VersionedIndexOps#doVersionedPut}.
 *
 * <p>The guard must skip re-insertion only when the existing entry has the same
 * version (same TX) AND the same value (RID). A re-put with a different value
 * must proceed with remove+re-insert to update the entry.
 */
public class VersionedIndexOpsRePutGuardTest {

  private static final long TX_VERSION = 42L;
  private static final int ENGINE_ID = 7;
  private static final RID RID_A = new RecordId(10, 1);
  private static final RID RID_B = new RecordId(10, 2);

  @SuppressWarnings("unchecked")
  private final CellBTreeSingleValue<CompositeKey> tree =
      mock(CellBTreeSingleValue.class);
  private final IndexesSnapshot snapshot = mock(IndexesSnapshot.class);
  private final AtomicOperation atomicOp = mock(AtomicOperation.class);

  @Before
  public void setUp() {
    when(atomicOp.getCommitTs()).thenReturn(TX_VERSION);
    when(atomicOp.getOrCreateIndexCountDeltas())
        .thenReturn(new IndexCountDeltaHolder());
  }

  /**
   * Same TX re-put with identical value (RecordId): the guard should skip the
   * mutation and return false (no-op).
   */
  @Test
  public void sameTxRePut_identicalRecordId_skips() throws IOException {
    var existingKey = new CompositeKey("key1", TX_VERSION);
    var existing = Optional.of(new RawPair<>(existingKey, (RID) RID_A));

    var compositeKey = new CompositeKey("key1");
    boolean result = VersionedIndexOps.doVersionedPut(
        tree, snapshot, atomicOp, compositeKey, RID_A,
        ENGINE_ID, false, existing);

    assertFalse("Same TX re-put with identical value should be a no-op", result);
    verify(tree, never()).remove(
        ArgumentMatchers.any(), ArgumentMatchers.any());
    verify(tree, never()).put(
        ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any());
  }

  /**
   * Same TX re-put with identical value (SnapshotMarkerRID): the guard should
   * skip the mutation and return false (no-op).
   */
  @Test
  public void sameTxRePut_identicalSnapshotMarker_skips() throws IOException {
    var markerRid = new SnapshotMarkerRID(RID_A);
    var existingKey = new CompositeKey("key1", TX_VERSION);
    var existing = Optional.of(new RawPair<>(existingKey, (RID) markerRid));

    var compositeKey = new CompositeKey("key1");
    boolean result = VersionedIndexOps.doVersionedPut(
        tree, snapshot, atomicOp, compositeKey, RID_A,
        ENGINE_ID, false, existing);

    assertFalse(
        "Same TX re-put with identical value (SnapshotMarkerRID) should be a no-op",
        result);
    verify(tree, never()).remove(
        ArgumentMatchers.any(), ArgumentMatchers.any());
  }

  /**
   * Same TX re-put with a DIFFERENT value: the guard must NOT skip — the entry
   * must be replaced so the new RID takes effect. This is the bug scenario that
   * the value equality check prevents.
   */
  @Test
  public void sameTxRePut_differentValue_proceeds() throws IOException {
    var existingKey = new CompositeKey("key1", TX_VERSION);
    var existing = Optional.of(new RawPair<>(existingKey, (RID) RID_A));

    when(tree.put(ArgumentMatchers.any(), ArgumentMatchers.any(),
        ArgumentMatchers.any())).thenReturn(true);

    var compositeKey = new CompositeKey("key1");
    boolean result = VersionedIndexOps.doVersionedPut(
        tree, snapshot, atomicOp, compositeKey, RID_B,
        ENGINE_ID, false, existing);

    assertTrue("Same TX re-put with different value must proceed", result);
    verify(tree).remove(atomicOp, existingKey);
  }

  /**
   * Put over a SnapshotMarkerRID from a different TX (prior committed TX with
   * version=10, current TX with version=42). This is the normal
   * "update-after-prior-update" production path. Must proceed: remove old entry,
   * put new SnapshotMarkerRID, and add snapshot pair with unwrapped identity.
   */
  @Test
  public void doVersionedPut_overSnapshotMarkerFromDifferentTx_proceedsAndCreatesSnapshotPair()
      throws IOException {
    var priorVersion = 10L;
    var existingKey = new CompositeKey("key1", priorVersion);
    var existingMarker = new SnapshotMarkerRID(RID_A);
    var existing = Optional.of(new RawPair<>(existingKey, (RID) existingMarker));

    when(tree.put(ArgumentMatchers.any(), ArgumentMatchers.any(),
        ArgumentMatchers.any())).thenReturn(true);

    var compositeKey = new CompositeKey("key1");
    boolean result = VersionedIndexOps.doVersionedPut(
        tree, snapshot, atomicOp, compositeKey, RID_B,
        ENGINE_ID, false, existing);

    assertTrue("Put over SnapshotMarkerRID from different TX must proceed", result);

    // Old entry must be removed
    verify(tree).remove(atomicOp, existingKey);

    // New entry must be a SnapshotMarkerRID wrapping RID_B at current TX version
    var expectedNewKey = new CompositeKey("key1", TX_VERSION);
    verify(tree).put(
        ArgumentMatchers.eq(atomicOp),
        ArgumentMatchers.eq(expectedNewKey),
        ArgumentMatchers.any(SnapshotMarkerRID.class));

    // Snapshot pair must use the unwrapped identity (RID_A), not the marker
    verify(snapshot).addSnapshotPair(
        ArgumentMatchers.eq(existingKey),
        ArgumentMatchers.eq(expectedNewKey),
        ArgumentMatchers.eq(RID_A));
  }

  /**
   * Put over a TombstoneRID from a different TX (resurrection). The prior TX
   * deleted the entry (version=10), and the current TX (version=42) re-inserts.
   * Must proceed: remove old tombstone, put new SnapshotMarkerRID, accumulate
   * +1 count delta (entry comes back to life), and NOT create a snapshot pair
   * (tombstone has no visible prior state to snapshot).
   */
  @Test
  public void doVersionedPut_overTombstoneFromDifferentTx_proceedsAndAccumulatesDelta()
      throws IOException {
    var priorVersion = 10L;
    var existingKey = new CompositeKey("key1", priorVersion);
    var tombstone = new TombstoneRID(RID_A);
    var existing = Optional.of(new RawPair<>(existingKey, (RID) tombstone));

    when(tree.put(ArgumentMatchers.any(), ArgumentMatchers.any(),
        ArgumentMatchers.any())).thenReturn(true);

    var compositeKey = new CompositeKey("key1");
    boolean result = VersionedIndexOps.doVersionedPut(
        tree, snapshot, atomicOp, compositeKey, RID_B,
        ENGINE_ID, false, existing);

    assertTrue("Put over TombstoneRID from different TX must proceed", result);

    // Old tombstone entry must be removed
    verify(tree).remove(atomicOp, existingKey);

    // New entry must be a SnapshotMarkerRID wrapping RID_B at current TX version
    var expectedNewKey = new CompositeKey("key1", TX_VERSION);
    verify(tree).put(
        ArgumentMatchers.eq(atomicOp),
        ArgumentMatchers.eq(expectedNewKey),
        ArgumentMatchers.any(SnapshotMarkerRID.class));

    // Snapshot pair must NOT be created — tombstone has no visible prior state
    verify(snapshot, never()).addSnapshotPair(
        ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any());

    // Count delta must be +1 (resurrection: entry re-appears)
    var deltaHolder = atomicOp.getOrCreateIndexCountDeltas();
    var delta = deltaHolder.getDeltas().get(ENGINE_ID);
    assertThat(delta).as("Delta must exist for engine " + ENGINE_ID).isNotNull();
    assertThat(delta.getTotalDelta()).as("Total delta must be +1").isEqualTo(1);
    assertThat(delta.getNullDelta()).as("Null delta must be 0 (non-null key)").isEqualTo(0);
  }

  // ═══════════════════════════════════════════════════════════════════════
  // doVersionedRemove: double-tombstone guard
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * When the existing entry is already a TombstoneRID (deleted), doVersionedRemove
   * must return false without modifying the tree or snapshot. Re-deleting would
   * create a corrupt snapshot pair causing phantom resurrections.
   */
  @Test
  public void doVersionedRemove_encountersTombstone_returnsFalse() throws IOException {
    var existingKey = new CompositeKey("key1", 10L);
    var tombstone = new TombstoneRID(RID_A);

    when(tree.iterateEntriesBetween(
        ArgumentMatchers.any(), ArgumentMatchers.eq(true),
        ArgumentMatchers.any(), ArgumentMatchers.eq(true),
        ArgumentMatchers.eq(true), ArgumentMatchers.any()))
        .thenAnswer(inv -> Stream.of(new RawPair<>(existingKey, (RID) tombstone)));

    boolean result = VersionedIndexOps.doVersionedRemove(
        tree, snapshot, atomicOp, new CompositeKey("key1"),
        ENGINE_ID, false);

    assertFalse("Remove of already-tombstoned entry must be a no-op", result);
    verify(tree, never()).remove(
        ArgumentMatchers.any(), ArgumentMatchers.any());
    verify(tree, never()).put(
        ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any());
    verify(snapshot, never()).addSnapshotPair(
        ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any());
  }

  /**
   * When the existing entry is a SnapshotMarkerRID (from a prior TX update),
   * doVersionedRemove must proceed: remove the old entry, put a tombstone wrapping
   * the unwrapped identity, and add a snapshot pair with the unwrapped identity.
   * This is the "delete-after-update" production path.
   */
  @Test
  public void doVersionedRemove_encountersSnapshotMarker_proceedsNormally()
      throws IOException {
    var priorVersion = 10L;
    var existingKey = new CompositeKey("key1", priorVersion);
    var markerRid = new SnapshotMarkerRID(RID_A);

    when(tree.iterateEntriesBetween(
        ArgumentMatchers.any(), ArgumentMatchers.eq(true),
        ArgumentMatchers.any(), ArgumentMatchers.eq(true),
        ArgumentMatchers.eq(true), ArgumentMatchers.any()))
        .thenAnswer(inv -> Stream.of(new RawPair<>(existingKey, (RID) markerRid)));
    when(tree.remove(ArgumentMatchers.any(), ArgumentMatchers.eq(existingKey)))
        .thenReturn(markerRid);
    when(tree.put(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any()))
        .thenReturn(true);

    boolean result = VersionedIndexOps.doVersionedRemove(
        tree, snapshot, atomicOp, new CompositeKey("key1"),
        ENGINE_ID, false);

    assertTrue("Remove of SnapshotMarkerRID entry must proceed", result);
    verify(tree).remove(atomicOp, existingKey);

    var expectedNewKey = new CompositeKey("key1", TX_VERSION);
    verify(tree).put(
        ArgumentMatchers.eq(atomicOp),
        ArgumentMatchers.eq(expectedNewKey),
        ArgumentMatchers.any(TombstoneRID.class));

    // Snapshot pair must receive the unwrapped identity (RID_A), not SnapshotMarkerRID
    verify(snapshot).addSnapshotPair(
        ArgumentMatchers.eq(existingKey),
        ArgumentMatchers.eq(expectedNewKey),
        ArgumentMatchers.eq(RID_A));
  }

  /**
   * When the existing entry is a live RecordId, doVersionedRemove must proceed
   * normally: remove the old entry, put a tombstone, and add a snapshot pair.
   */
  @Test
  public void doVersionedRemove_encountersLiveEntry_proceedsNormally() throws IOException {
    var existingKey = new CompositeKey("key1", 10L);

    when(tree.iterateEntriesBetween(
        ArgumentMatchers.any(), ArgumentMatchers.eq(true),
        ArgumentMatchers.any(), ArgumentMatchers.eq(true),
        ArgumentMatchers.eq(true), ArgumentMatchers.any()))
        .thenAnswer(inv -> Stream.of(new RawPair<>(existingKey, (RID) RID_A)));
    when(tree.remove(ArgumentMatchers.any(), ArgumentMatchers.eq(existingKey)))
        .thenReturn(RID_A);
    when(tree.put(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any()))
        .thenReturn(true);

    boolean result = VersionedIndexOps.doVersionedRemove(
        tree, snapshot, atomicOp, new CompositeKey("key1"),
        ENGINE_ID, false);

    assertTrue("Remove of live entry must proceed", result);
    verify(tree).remove(atomicOp, existingKey);
    verify(tree).put(
        ArgumentMatchers.eq(atomicOp),
        ArgumentMatchers.eq(new CompositeKey("key1", TX_VERSION)),
        ArgumentMatchers.any(TombstoneRID.class));
    verify(snapshot).addSnapshotPair(
        ArgumentMatchers.eq(existingKey),
        ArgumentMatchers.eq(new CompositeKey("key1", TX_VERSION)),
        ArgumentMatchers.eq(RID_A));
  }
}
