package com.jetbrains.youtrackdb.internal.core.tx;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import org.junit.Assert;
import org.junit.Test;

/**
 * Coverage for {@link FrontendTransactionSequenceStatus} record. Exercises
 * both the storage round-trip ({@code store} / {@code read}) and the network
 * round-trip ({@code writeNetwork} / {@code readNetwork}), plus the equals/
 * hashCode override that uses {@link java.util.Arrays#equals(long[], long[])}
 * because records' default array equality is identity-based and would break
 * map/set keys in cluster-sync.
 */
public class FrontendTransactionSequenceStatusTest {

  /**
   * A non-empty status array round-trips byte-for-byte through
   * {@code store}/{@code read}, exercising the var-int length prefix and the
   * per-element loop.
   */
  @Test
  public void storeReadRoundTripPopulated() throws Exception {
    var status = new FrontendTransactionSequenceStatus(new long[] {0L, 1L, 1234567890123L, -1L});

    var bytes = status.store();
    var roundTripped = FrontendTransactionSequenceStatus.read(bytes);

    Assert.assertEquals(status, roundTripped);
    Assert.assertArrayEquals(status.status(), roundTripped.status());
  }

  /**
   * An empty status array round-trips through {@code store}/{@code read},
   * exercising the loop's zero-iteration branch.
   */
  @Test
  public void storeReadRoundTripEmpty() throws Exception {
    var status = new FrontendTransactionSequenceStatus(new long[0]);

    var bytes = status.store();
    var roundTripped = FrontendTransactionSequenceStatus.read(bytes);

    Assert.assertEquals(status, roundTripped);
    Assert.assertEquals(0, roundTripped.status().length);
  }

  /**
   * The network-format round-trip is symmetric and uses the same var-int
   * encoding as storage, but writes against a caller-supplied DataOutput
   * (no allocation).
   */
  @Test
  public void networkRoundTripPopulated() throws Exception {
    var status = new FrontendTransactionSequenceStatus(new long[] {2L, 3L, 5L, 8L, 13L});

    var buffer = new ByteArrayOutputStream();
    try (var out = new DataOutputStream(buffer)) {
      status.writeNetwork(out);
    }

    FrontendTransactionSequenceStatus roundTripped;
    try (var in = new DataInputStream(new ByteArrayInputStream(buffer.toByteArray()))) {
      roundTripped = FrontendTransactionSequenceStatus.readNetwork(in);
    }

    Assert.assertEquals(status, roundTripped);
  }

  /**
   * Equals/hashCode use array contents, not array identity. Two distinct
   * arrays with the same values must be equal so the type can be used as a
   * map key.
   */
  @Test
  public void equalsAndHashCodeUseArrayContents() {
    var a = new FrontendTransactionSequenceStatus(new long[] {1L, 2L, 3L});
    var b = new FrontendTransactionSequenceStatus(new long[] {1L, 2L, 3L});
    var c = new FrontendTransactionSequenceStatus(new long[] {1L, 2L, 4L});

    Assert.assertEquals(a, b);
    Assert.assertEquals(a.hashCode(), b.hashCode());
    Assert.assertNotEquals(a, c);
  }

  /**
   * Reflexivity, null inequality, and class-mismatch inequality of equals
   * — the boilerplate branches missed when only happy-path equality is
   * tested.
   */
  @Test
  public void equalsHandlesIdentityNullAndOtherTypes() {
    var a = new FrontendTransactionSequenceStatus(new long[] {1L});
    Assert.assertEquals(a, a);
    Assert.assertNotEquals(a, null);
    Assert.assertNotEquals(a, "not-a-status");
  }
}
