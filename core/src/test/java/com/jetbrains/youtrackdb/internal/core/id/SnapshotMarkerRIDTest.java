package com.jetbrains.youtrackdb.internal.core.id;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
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
   * Delegates isPersistent() and isNew() to the wrapped identity.
   */
  @Test
  public void delegatesPersistenceChecks() {
    var persistent = new RecordId(3, 7);
    assertTrue(new SnapshotMarkerRID(persistent).isPersistent());

    var newRid = new RecordId(-1, -1);
    assertTrue(new SnapshotMarkerRID(newRid).isNew());
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
