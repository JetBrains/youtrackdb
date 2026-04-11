package com.jetbrains.youtrackdb.internal.core.id;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Encoding roundtrip tests for {@link SnapshotMarkerRID}.
 * Verifies that the serialization discriminator (negated collection position)
 * works correctly and that the original identity is recoverable.
 */
public class SnapshotMarkerRIDTest {

  /**
   * SnapshotMarkerRID(5, 10) must keep collectionId unchanged,
   * encode collectionPosition as -(10+1) = -11, and recover the original
   * via getIdentity().
   */
  @Test
  public void encodesCollectionPosition_primitiveConstructor() {
    var marker = new SnapshotMarkerRID(5, 10);

    assertEquals(5, marker.getCollectionId());
    assertEquals(-11, marker.getCollectionPosition());
    assertEquals(new RecordId(5, 10), marker.getIdentity());
  }

  /**
   * SnapshotMarkerRID(RID) wrapping constructor produces the same encoding as
   * the primitive constructor.
   */
  @Test
  public void encodesCollectionPosition_wrappingConstructor() {
    var original = new RecordId(5, 10);
    var marker = new SnapshotMarkerRID(original);

    assertEquals(5, marker.getCollectionId());
    assertEquals(-11, marker.getCollectionPosition());
    assertEquals(original, marker.getIdentity());
  }

  /**
   * CollectionPosition=0 must encode as -1. Boundary case where the
   * shift+negate formula must not produce 0.
   */
  @Test
  public void positionZero_encodesAsMinusOne() {
    var marker = new SnapshotMarkerRID(7, 0);

    assertEquals(7, marker.getCollectionId());
    assertEquals(-1, marker.getCollectionPosition());
  }

  /**
   * Delegates isPersistent() — a SnapshotMarkerRID with valid ids is persistent.
   */
  @Test
  public void delegatesPersistenceChecks() {
    assertTrue(new SnapshotMarkerRID(3, 7).isPersistent());
  }

  /**
   * Boundary: collectionId=0 and collectionPosition=0 are both valid (zero is
   * non-negative). The constructor assertions must accept this.
   */
  @Test
  public void zeroIdsAreAccepted() {
    var rid = new SnapshotMarkerRID(0, 0);
    assertEquals(0, rid.getCollectionId());
    assertEquals(-1, rid.getCollectionPosition());
  }

  /**
   * Primitive constructor with negative collectionId must trigger an AssertionError.
   * Requires -ea (enabled assertions) in the test JVM args (configured in
   * core/pom.xml argLine).
   */
  @Test
  public void negativeCollectionId_throwsAssertionError() {
    assertThrows(AssertionError.class, () -> new SnapshotMarkerRID(-1, 0));
  }

  /**
   * Primitive constructor with negative collectionPosition must trigger an AssertionError.
   */
  @Test
  public void negativeCollectionPosition_throwsAssertionError() {
    assertThrows(AssertionError.class, () -> new SnapshotMarkerRID(0, -1));
  }

  /**
   * Wrapping constructor with negative collectionId must trigger an AssertionError.
   */
  @Test
  public void wrappingConstructor_negativeCollectionId_throwsAssertionError() {
    assertThrows(AssertionError.class, () -> new SnapshotMarkerRID(new RecordId(-1, 0)));
  }

  /**
   * Wrapping constructor with negative collectionPosition must trigger an AssertionError.
   */
  @Test
  public void wrappingConstructor_negativeCollectionPosition_throwsAssertionError() {
    assertThrows(AssertionError.class, () -> new SnapshotMarkerRID(new RecordId(0, -1)));
  }

  /**
   * toString uses "~" prefix to distinguish from live and tombstone entries.
   */
  @Test
  public void toStringUsesTildePrefix() {
    var marker = new SnapshotMarkerRID(5, 10);
    assertEquals("~#5:10", marker.toString());
  }

  /**
   * Two SnapshotMarkerRIDs with the same underlying identity are equal.
   */
  @Test
  public void equalsAndHashCode_sameIdentity() {
    var a = new SnapshotMarkerRID(5, 10);
    var b = new SnapshotMarkerRID(new RecordId(5, 10));

    assertEquals(a, b);
    assertEquals(a.hashCode(), b.hashCode());
  }

  /**
   * SnapshotMarkerRIDs with different identities are not equal.
   */
  @Test
  public void equalsAndHashCode_differentIdentity() {
    var a = new SnapshotMarkerRID(5, 10);
    var b = new SnapshotMarkerRID(5, 11);

    assertNotEquals(a, b);
  }

  /**
   * A SnapshotMarkerRID equals the underlying RecordId (same collection/position),
   * because equals is based on getIdentity() values.
   */
  @Test
  public void equalsRecordIdWithSameIdentity() {
    var marker = new SnapshotMarkerRID(5, 10);
    var recordId = new RecordId(5, 10);

    assertEquals(marker, recordId);
  }

  /**
   * Cross-marker equality: SnapshotMarkerRID and TombstoneRID with the same
   * underlying identity must be equal (equality is identity-based, not type-based).
   */
  @Test
  public void equalsAcrossMarkerTypes() {
    var marker = new SnapshotMarkerRID(5, 10);
    var tombstone = new TombstoneRID(5, 10);
    assertEquals(marker, tombstone);
    assertEquals(tombstone, marker);
    assertEquals(marker.hashCode(), tombstone.hashCode());
  }

  /**
   * SnapshotMarkerRID(0, Long.MAX_VALUE) must encode position as
   * -(MAX_VALUE + 1) = MIN_VALUE via two's-complement overflow.
   * The identity must recover the original MAX_VALUE.
   */
  @Test
  public void encodesCollectionPosition_maxValue_overflowSafe() {
    var marker = new SnapshotMarkerRID(0, Long.MAX_VALUE);
    // -(MAX_VALUE + 1) = MIN_VALUE in two's complement
    assertEquals(Long.MIN_VALUE, marker.getCollectionPosition());
    assertEquals(Long.MAX_VALUE, marker.getIdentity().getCollectionPosition());
  }

  /**
   * SnapshotMarkerRID(Short.MAX_VALUE, 0) must pass collectionId through
   * unchanged (SnapshotMarkerRID only encodes position, not id).
   * Short.MAX_VALUE is the largest valid collection ID.
   */
  @Test
  public void encodesCollectionId_maxValue_overflowSafe() {
    var marker = new SnapshotMarkerRID(Short.MAX_VALUE, 0);
    assertEquals(Short.MAX_VALUE, marker.getCollectionId());
    assertEquals(Short.MAX_VALUE, marker.getIdentity().getCollectionId());
  }

  /**
   * getIdentity() returns a RecordId with the original (non-encoded) values.
   */
  @Test
  public void getIdentityReturnsCorrectRecordId() {
    var marker = new SnapshotMarkerRID(5, 10);
    var identity = marker.getIdentity();

    assertEquals(5, identity.getCollectionId());
    assertEquals(10, identity.getCollectionPosition());
  }
}
