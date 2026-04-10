package com.jetbrains.youtrackdb.internal.core.index.engine.v1;

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
import com.jetbrains.youtrackdb.internal.core.index.CompositeKey;
import com.jetbrains.youtrackdb.internal.core.index.IndexesSnapshot;
import com.jetbrains.youtrackdb.internal.core.index.engine.IndexCountDeltaHolder;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.CellBTreeSingleValue;
import java.io.IOException;
import java.util.Optional;
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
}
