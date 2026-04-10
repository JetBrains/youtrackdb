package com.jetbrains.youtrackdb.internal.core.id;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
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
   * TombstoneRID(5, 10) must encode collectionId as -(5+1) = -6,
   * keep collectionPosition unchanged, and recover the original via getIdentity().
   */
  @Test
  public void encodesCollectionId_primitiveConstructor() {
    var tombstone = new TombstoneRID(5, 10);

    assertEquals(-6, tombstone.getCollectionId());
    assertEquals(10, tombstone.getCollectionPosition());
    assertEquals(new RecordId(5, 10), tombstone.getIdentity());
  }

  /**
   * TombstoneRID(RID) wrapping constructor produces the same encoding as the
   * primitive constructor.
   */
  @Test
  public void encodesCollectionId_wrappingConstructor() {
    var original = new RecordId(5, 10);
    var tombstone = new TombstoneRID(original);

    assertEquals(-6, tombstone.getCollectionId());
    assertEquals(10, tombstone.getCollectionPosition());
    assertEquals(original, tombstone.getIdentity());
  }

  /**
   * CollectionId=0 must encode as -1. Boundary case where the shift+negate
   * formula must not produce 0 (which would be ambiguous with a live entry).
   */
  @Test
  public void collectionIdZero_encodesAsMinusOne() {
    var tombstone = new TombstoneRID(0, 42);

    assertEquals(-1, tombstone.getCollectionId());
    assertEquals(42, tombstone.getCollectionPosition());
  }

  /**
   * Delegates isPersistent() — a TombstoneRID with valid ids is persistent.
   */
  @Test
  public void delegatesPersistenceChecks() {
    assertTrue(new TombstoneRID(3, 7).isPersistent());
  }

  /**
   * Boundary: collectionId=0 and collectionPosition=0 are both valid (zero is
   * non-negative). The constructor assertions must accept this.
   */
  @Test
  public void zeroIdsAreAccepted() {
    var rid = new TombstoneRID(0, 0);
    assertEquals(-1, rid.getCollectionId());
    assertEquals(0, rid.getCollectionPosition());
  }

  /**
   * Primitive constructor with negative collectionId must trigger an AssertionError.
   * Requires -ea (enabled assertions) in the test JVM args (configured in
   * core/pom.xml argLine).
   */
  @Test
  public void negativeCollectionId_throwsAssertionError() {
    assertThrows(AssertionError.class, () -> new TombstoneRID(-1, 0));
  }

  /**
   * Primitive constructor with negative collectionPosition must trigger an AssertionError.
   */
  @Test
  public void negativeCollectionPosition_throwsAssertionError() {
    assertThrows(AssertionError.class, () -> new TombstoneRID(0, -1));
  }

  /**
   * Wrapping constructor with negative collectionId must trigger an AssertionError.
   */
  @Test
  public void wrappingConstructor_negativeCollectionId_throwsAssertionError() {
    assertThrows(AssertionError.class, () -> new TombstoneRID(new RecordId(-1, 0)));
  }

  /**
   * Wrapping constructor with negative collectionPosition must trigger an AssertionError.
   */
  @Test
  public void wrappingConstructor_negativeCollectionPosition_throwsAssertionError() {
    assertThrows(AssertionError.class, () -> new TombstoneRID(new RecordId(0, -1)));
  }

  /**
   * toString uses "-" prefix to distinguish from live entries.
   */
  @Test
  public void toStringUsesDashPrefix() {
    var tombstone = new TombstoneRID(5, 10);
    assertEquals("-#5:10", tombstone.toString());
  }

  /**
   * Two TombstoneRIDs with the same underlying identity are equal.
   */
  @Test
  public void equalsAndHashCode_sameIdentity() {
    var a = new TombstoneRID(5, 10);
    var b = new TombstoneRID(new RecordId(5, 10));

    assertEquals(a, b);
    assertEquals(a.hashCode(), b.hashCode());
  }

  /**
   * TombstoneRIDs with different identities are not equal.
   */
  @Test
  public void equalsAndHashCode_differentIdentity() {
    var a = new TombstoneRID(5, 10);
    var b = new TombstoneRID(5, 11);

    assertNotEquals(a, b);
  }

  /**
   * A TombstoneRID equals the underlying RecordId (same collection/position),
   * because equals is based on getIdentity() values.
   */
  @Test
  public void equalsRecordIdWithSameIdentity() {
    var tombstone = new TombstoneRID(5, 10);
    var recordId = new RecordId(5, 10);

    assertEquals(tombstone, recordId);
  }

  /**
   * getIdentity() returns a RecordId with the original (non-encoded) values.
   */
  @Test
  public void getIdentityReturnsCorrectRecordId() {
    var tombstone = new TombstoneRID(5, 10);
    var identity = tombstone.getIdentity();

    assertEquals(5, identity.getCollectionId());
    assertEquals(10, identity.getCollectionPosition());
  }
}
