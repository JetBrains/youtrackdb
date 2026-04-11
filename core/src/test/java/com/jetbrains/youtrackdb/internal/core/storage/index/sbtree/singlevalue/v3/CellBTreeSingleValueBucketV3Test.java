package com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.v3;

import static org.junit.Assert.*;

import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.id.SnapshotMarkerRID;
import com.jetbrains.youtrackdb.internal.core.id.TombstoneRID;
import org.junit.Test;

/**
 * Tests for RID encoding/decoding round-trip in {@link CellBTreeSingleValueBucketV3}.
 * Verifies that TombstoneRID, SnapshotMarkerRID, and plain RecordId survive
 * serialization via their encoded collectionId/collectionPosition values.
 */
public class CellBTreeSingleValueBucketV3Test {

  /**
   * A TombstoneRID wrapping cluster 0 must survive the encode/decode round-trip.
   * Regression: plain negation of collectionId loses the tombstone marker for cluster 0
   * because -0 == 0 in Java integer arithmetic.
   */
  @Test
  public void testDecodeRID_tombstoneClusterZero() {
    var original = new TombstoneRID(new RecordId(0, 42));
    RID decoded = CellBTreeSingleValueBucketV3.decodeRID(
        original.getCollectionId(), original.getCollectionPosition());

    assertTrue(decoded instanceof TombstoneRID);
    var tombstone = (TombstoneRID) decoded;
    assertEquals(0, tombstone.getIdentity().getCollectionId());
    assertEquals(42, tombstone.getIdentity().getCollectionPosition());
  }

  /** TombstoneRID for a non-zero cluster must round-trip correctly. */
  @Test
  public void testDecodeRID_tombstoneNonZeroCluster() {
    var original = new TombstoneRID(new RecordId(5, 100));
    RID decoded = CellBTreeSingleValueBucketV3.decodeRID(
        original.getCollectionId(), original.getCollectionPosition());

    assertTrue(decoded instanceof TombstoneRID);
    var tombstone = (TombstoneRID) decoded;
    assertEquals(5, tombstone.getIdentity().getCollectionId());
    assertEquals(100, tombstone.getIdentity().getCollectionPosition());
  }

  /** SnapshotMarkerRID must round-trip correctly, including position 0. */
  @Test
  public void testDecodeRID_snapshotMarker() {
    var original = new SnapshotMarkerRID(new RecordId(3, 0));
    RID decoded = CellBTreeSingleValueBucketV3.decodeRID(
        original.getCollectionId(), original.getCollectionPosition());

    assertTrue(decoded instanceof SnapshotMarkerRID);
    var marker = (SnapshotMarkerRID) decoded;
    assertEquals(3, marker.getIdentity().getCollectionId());
    assertEquals(0, marker.getIdentity().getCollectionPosition());
  }

  /**
   * TombstoneRID at Short.MAX_VALUE cluster must survive the encode/decode
   * round-trip. This is the realistic maximum cluster ID boundary.
   */
  @Test
  public void testDecodeRID_tombstoneMaxCluster() {
    var original = new TombstoneRID(new RecordId(Short.MAX_VALUE, 0));
    RID decoded = CellBTreeSingleValueBucketV3.decodeRID(
        original.getCollectionId(), original.getCollectionPosition());

    assertTrue(decoded instanceof TombstoneRID);
    assertEquals(Short.MAX_VALUE,
        ((TombstoneRID) decoded).getIdentity().getCollectionId());
  }

  /**
   * SnapshotMarkerRID at Long.MAX_VALUE position must survive the
   * encode/decode round-trip. The encoded position overflows to
   * Long.MIN_VALUE in two's complement.
   */
  @Test
  public void testDecodeRID_snapshotMarkerMaxPosition() {
    var original = new SnapshotMarkerRID(new RecordId(0, Long.MAX_VALUE));
    RID decoded = CellBTreeSingleValueBucketV3.decodeRID(
        original.getCollectionId(), original.getCollectionPosition());

    assertTrue(decoded instanceof SnapshotMarkerRID);
    assertEquals(Long.MAX_VALUE,
        ((SnapshotMarkerRID) decoded).getIdentity().getCollectionPosition());
  }

  /** A plain RecordId with non-negative fields must decode as a plain RecordId. */
  @Test
  public void testDecodeRID_plainRecordId() {
    RID decoded = CellBTreeSingleValueBucketV3.decodeRID(10, 20);

    assertTrue(decoded instanceof RecordId);
    assertEquals(10, decoded.getCollectionId());
    assertEquals(20, decoded.getCollectionPosition());
  }

  @Test
  public void testDecodeRID_plainRecordId_clusterZero_positionZero() {
    // collectionId=0, collectionPosition=0: the zero boundary adjacent to
    // the negative-check for TombstoneRID. Must decode as plain RecordId.
    RID decoded = CellBTreeSingleValueBucketV3.decodeRID(0, 0);

    assertTrue("Cluster 0, position 0 must be plain RecordId",
        decoded instanceof RecordId);
    assertEquals(0, decoded.getCollectionId());
    assertEquals(0, decoded.getCollectionPosition());
  }
}
