package com.jetbrains.youtrackdb.internal.core.id;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Encoding roundtrip tests for {@link TombstoneRID}.
 * Verifies that the serialization discriminator (negated collection ID) works
 * correctly and that the original identity is recoverable.
 */
public class TombstoneRIDTest {

  /**
   * TombstoneRID(#5:10) must encode collectionId as -(5+1) = -6,
   * keep collectionPosition unchanged, and recover the original via getIdentity().
   */
  @Test
  public void encodesCollectionId() {
    var original = new RecordId(5, 10);
    var tombstone = new TombstoneRID(original);

    assertEquals(-6, tombstone.getCollectionId());
    assertEquals(10, tombstone.getCollectionPosition());
    assertSame(original, tombstone.getIdentity());
  }

  /**
   * CollectionId=0 must encode as -1. Boundary case where the shift+negate
   * formula must not produce 0 (which would be ambiguous with a live entry).
   */
  @Test
  public void collectionIdZero_encodesAsMinusOne() {
    var original = new RecordId(0, 42);
    var tombstone = new TombstoneRID(original);

    assertEquals(-1, tombstone.getCollectionId());
    assertEquals(42, tombstone.getCollectionPosition());
  }

  /**
   * Delegates isPersistent() to the wrapped identity.
   */
  @Test
  public void delegatesPersistenceChecks() {
    var persistent = new RecordId(3, 7);
    assertTrue(new TombstoneRID(persistent).isPersistent());
  }

  /**
   * Boundary: collectionId=0 and collectionPosition=0 are both valid (zero is
   * non-negative). The compact constructor assertions must accept this.
   */
  @Test
  public void zeroIdsAreAccepted() {
    var rid = new TombstoneRID(new RecordId(0, 0));
    assertEquals(-1, rid.getCollectionId());
    assertEquals(0, rid.getCollectionPosition());
  }

  /**
   * Wrapping a RID with negative collectionId must trigger an AssertionError.
   * Requires -ea (enabled assertions) in the test JVM args (configured in
   * core/pom.xml argLine).
   */
  @Test
  public void negativeCollectionId_throwsAssertionError() {
    assertThrows(AssertionError.class, () -> new TombstoneRID(new RecordId(-1, 0)));
  }

  /**
   * Wrapping a RID with negative collectionPosition must trigger an AssertionError.
   */
  @Test
  public void negativeCollectionPosition_throwsAssertionError() {
    assertThrows(AssertionError.class, () -> new TombstoneRID(new RecordId(0, -1)));
  }

  /**
   * toString uses "-" prefix to distinguish from live entries.
   */
  @Test
  public void toStringUsesDashPrefix() {
    var tombstone = new TombstoneRID(new RecordId(5, 10));
    assertEquals("-#5:10", tombstone.toString());
  }
}
