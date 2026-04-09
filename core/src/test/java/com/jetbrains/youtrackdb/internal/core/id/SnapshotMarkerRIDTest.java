package com.jetbrains.youtrackdb.internal.core.id;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
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
   * SnapshotMarkerRID(#5:10) must keep collectionId unchanged,
   * encode collectionPosition as -(10+1) = -11, and recover the original
   * via getIdentity().
   */
  @Test
  public void encodesCollectionPosition() {
    var original = new RecordId(5, 10);
    var marker = new SnapshotMarkerRID(original);

    assertEquals(5, marker.getCollectionId());
    assertEquals(-11, marker.getCollectionPosition());
    assertSame(original, marker.getIdentity());
  }

  /**
   * CollectionPosition=0 must encode as -1. Boundary case where the
   * shift+negate formula must not produce 0.
   */
  @Test
  public void positionZero_encodesAsMinusOne() {
    var original = new RecordId(7, 0);
    var marker = new SnapshotMarkerRID(original);

    assertEquals(7, marker.getCollectionId());
    assertEquals(-1, marker.getCollectionPosition());
  }

  /**
   * Delegates isPersistent() to the wrapped identity.
   */
  @Test
  public void delegatesPersistenceChecks() {
    var persistent = new RecordId(3, 7);
    assertTrue(new SnapshotMarkerRID(persistent).isPersistent());
  }

  /**
   * Boundary: collectionId=0 and collectionPosition=0 are both valid (zero is
   * non-negative). The compact constructor assertions must accept this.
   */
  @Test
  public void zeroIdsAreAccepted() {
    var rid = new SnapshotMarkerRID(new RecordId(0, 0));
    assertEquals(0, rid.getCollectionId());
    assertEquals(-1, rid.getCollectionPosition());
  }

  /**
   * Wrapping a RID with negative collectionId must trigger an AssertionError.
   * Requires -ea (enabled assertions) in the test JVM args (configured in
   * core/pom.xml argLine).
   */
  @Test
  public void negativeCollectionId_throwsAssertionError() {
    assertThrows(AssertionError.class, () -> new SnapshotMarkerRID(new RecordId(-1, 0)));
  }

  /**
   * Wrapping a RID with negative collectionPosition must trigger an AssertionError.
   */
  @Test
  public void negativeCollectionPosition_throwsAssertionError() {
    assertThrows(AssertionError.class, () -> new SnapshotMarkerRID(new RecordId(0, -1)));
  }

  /**
   * toString uses "~" prefix to distinguish from live and tombstone entries.
   */
  @Test
  public void toStringUsesTildePrefix() {
    var marker = new SnapshotMarkerRID(new RecordId(5, 10));
    assertEquals("~#5:10", marker.toString());
  }
}
