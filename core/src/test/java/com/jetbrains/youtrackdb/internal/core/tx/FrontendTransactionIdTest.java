package com.jetbrains.youtrackdb.internal.core.tx;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.Optional;
import org.junit.Assert;
import org.junit.Test;

/**
 * Coverage for {@link FrontendTransactionId} record. Targets the symmetric
 * read/write serialization (with and without a node owner), the constructor
 * canonical-record assertion that nodeOwner is non-null, and the toString
 * contract used in cluster-sync logging.
 */
public class FrontendTransactionIdTest {

  /**
   * Writes a transaction id with a node owner and reads it back, verifying
   * that the boolean-prefix branch for present owner is exercised end-to-end.
   */
  @Test
  public void writeReadRoundTripWithNodeOwner() throws Exception {
    var original = new FrontendTransactionId(Optional.of("nodeA"), 42, 1234567890123L);

    var buffer = new ByteArrayOutputStream();
    try (var out = new DataOutputStream(buffer)) {
      original.write(out);
    }

    FrontendTransactionId roundTripped;
    try (var in = new DataInputStream(new ByteArrayInputStream(buffer.toByteArray()))) {
      roundTripped = FrontendTransactionId.read(in);
    }

    Assert.assertEquals(original, roundTripped);
    Assert.assertEquals(Optional.of("nodeA"), roundTripped.nodeOwner());
    Assert.assertEquals(42, roundTripped.position());
    Assert.assertEquals(1234567890123L, roundTripped.sequence());
  }

  /**
   * Writes a transaction id with an empty node owner and reads it back,
   * verifying the boolean-prefix branch for absent owner.
   */
  @Test
  public void writeReadRoundTripWithoutNodeOwner() throws Exception {
    var original = new FrontendTransactionId(Optional.empty(), -1, 0L);

    var buffer = new ByteArrayOutputStream();
    try (var out = new DataOutputStream(buffer)) {
      original.write(out);
    }

    FrontendTransactionId roundTripped;
    try (var in = new DataInputStream(new ByteArrayInputStream(buffer.toByteArray()))) {
      roundTripped = FrontendTransactionId.read(in);
    }

    Assert.assertEquals(original, roundTripped);
    Assert.assertEquals(Optional.empty(), roundTripped.nodeOwner());
    Assert.assertEquals(-1, roundTripped.position());
    Assert.assertEquals(0L, roundTripped.sequence());
  }

  /**
   * The compact-record canonical constructor asserts nodeOwner is non-null.
   * Records with assertions enabled (-ea) trip an AssertionError; this test
   * pins that contract so a future change of the nullability invariant is
   * a deliberate decision.
   */
  @Test
  public void constructorRejectsNullNodeOwner() {
    Assert.assertThrows(
        AssertionError.class,
        () -> new FrontendTransactionId(null, 0, 0L));
  }

  /**
   * The toString format is consumed by cluster-sync logs and must remain
   * "<position>:<sequence> owner:<Optional[…]>" so log-grepping continues
   * to work.
   */
  @Test
  public void toStringContainsPositionSequenceAndOwner() {
    var withOwner = new FrontendTransactionId(Optional.of("ytdb-node-1"), 7, 11L);
    Assert.assertEquals("7:11 owner:Optional[ytdb-node-1]", withOwner.toString());

    var withoutOwner = new FrontendTransactionId(Optional.empty(), 7, 11L);
    Assert.assertEquals("7:11 owner:Optional.empty", withoutOwner.toString());
  }

  /**
   * Two FrontendTransactionId values with identical components must be
   * record-equal. Cluster-sync code uses transaction-ids as map keys.
   */
  @Test
  public void equalsAndHashCodeFollowRecordContract() {
    var a = new FrontendTransactionId(Optional.of("n1"), 5, 100L);
    var b = new FrontendTransactionId(Optional.of("n1"), 5, 100L);
    var c = new FrontendTransactionId(Optional.of("n1"), 5, 101L);

    Assert.assertEquals(a, b);
    Assert.assertEquals(a.hashCode(), b.hashCode());
    Assert.assertNotEquals(a, c);
  }
}
