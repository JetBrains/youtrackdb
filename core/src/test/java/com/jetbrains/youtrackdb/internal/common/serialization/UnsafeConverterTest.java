package com.jetbrains.youtrackdb.internal.common.serialization;

import java.nio.ByteOrder;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * Verifies {@link UnsafeBinaryConverter} byte-order conversion correctness.
 *
 * <p>Out-of-bounds tests are deliberately Safe-only: the {@code sun.misc.Unsafe}-backed paths in
 * this converter do not bounds-check, so a negative offset or length-0 write would corrupt heap
 * memory rather than throw. See {@link SafeConverterTest} for the bounds-check contract.
 *
 * <p><strong>Residual gap.</strong> Every {@code put*}/{@code get*} method on
 * {@link UnsafeBinaryConverter} dispatches on the {@code useOnlyAlignedAccess} flag, which is
 * captured into a {@code static final} at class init from
 * {@code GlobalConfiguration.DIRECT_MEMORY_ONLY_ALIGNED_ACCESS} (default {@code true}). Tests in
 * this class therefore exercise the aligned-byte-by-byte branches only; the
 * {@code !useOnlyAlignedAccess} fast-path branches (the {@code theUnsafe.putShort/putInt/...}
 * + {@code reverseBytes} family) are not reachable from a single-JVM test without classloader
 * tricks. The wider deferred cleanup that retires the unsafe-backed converter will absorb that
 * surface.
 */
public class UnsafeConverterTest extends AbstractConverterTest {

  @Before
  public void setUp() {
    converter = new UnsafeBinaryConverter();
  }

  @Test
  public void putIntBigEndianRoundTrips() {
    assertPutIntBigEndianRoundTrips();
  }

  @Test
  public void putIntLittleEndianRoundTrips() {
    assertPutIntLittleEndianRoundTrips();
  }

  @Test
  public void putLongBigEndianRoundTrips() {
    assertPutLongBigEndianRoundTrips();
  }

  @Test
  public void putLongLittleEndianRoundTrips() {
    assertPutLongLittleEndianRoundTrips();
  }

  @Test
  public void putShortBigEndianRoundTrips() {
    assertPutShortBigEndianRoundTrips();
  }

  @Test
  public void putShortLittleEndianRoundTrips() {
    assertPutShortLittleEndianRoundTrips();
  }

  @Test
  public void putCharBigEndianRoundTrips() {
    assertPutCharBigEndianRoundTrips();
  }

  @Test
  public void putCharLittleEndianRoundTrips() {
    assertPutCharLittleEndianRoundTrips();
  }

  // --- Non-zero-offset round-trips ---

  @Test
  public void putIntAtNonZeroOffsetBigEndianRoundTrips() {
    assertPutIntAtNonZeroOffsetBigEndianRoundTrips();
  }

  @Test
  public void putIntAtNonZeroOffsetLittleEndianRoundTrips() {
    assertPutIntAtNonZeroOffsetLittleEndianRoundTrips();
  }

  @Test
  public void putLongAtNonZeroOffsetBigEndianRoundTrips() {
    assertPutLongAtNonZeroOffsetBigEndianRoundTrips();
  }

  @Test
  public void putLongAtNonZeroOffsetLittleEndianRoundTrips() {
    assertPutLongAtNonZeroOffsetLittleEndianRoundTrips();
  }

  @Test
  public void putShortAtNonZeroOffsetBigEndianRoundTrips() {
    assertPutShortAtNonZeroOffsetBigEndianRoundTrips();
  }

  @Test
  public void putShortAtNonZeroOffsetLittleEndianRoundTrips() {
    assertPutShortAtNonZeroOffsetLittleEndianRoundTrips();
  }

  @Test
  public void putCharAtNonZeroOffsetBigEndianRoundTrips() {
    assertPutCharAtNonZeroOffsetBigEndianRoundTrips();
  }

  @Test
  public void putCharAtNonZeroOffsetLittleEndianRoundTrips() {
    assertPutCharAtNonZeroOffsetLittleEndianRoundTrips();
  }

  // --- Boundary values ---

  @Test
  public void putIntBoundaryValuesRoundTrip() {
    assertPutIntBoundaryValuesRoundTrip();
  }

  @Test
  public void putLongBoundaryValuesRoundTrip() {
    assertPutLongBoundaryValuesRoundTrip();
  }

  @Test
  public void putShortBoundaryValuesRoundTrip() {
    assertPutShortBoundaryValuesRoundTrip();
  }

  @Test
  public void putCharBoundaryValuesRoundTrip() {
    assertPutCharBoundaryValuesRoundTrip();
  }

  // --- Native byte order round-trips (exercises the no-reverseBytes fast path) ---

  @Test
  public void putIntNativeByteOrderRoundTrips() {
    assertPutIntNativeByteOrderRoundTrips();
  }

  @Test
  public void putLongNativeByteOrderRoundTrips() {
    assertPutLongNativeByteOrderRoundTrips();
  }

  @Test
  public void putShortNativeByteOrderRoundTrips() {
    assertPutShortNativeByteOrderRoundTrips();
  }

  @Test
  public void putCharNativeByteOrderRoundTrips() {
    assertPutCharNativeByteOrderRoundTrips();
  }

  /**
   * UnsafeBinaryConverter exposes both a public no-arg constructor and the
   * {@link UnsafeBinaryConverter#INSTANCE} singleton; pin that the two share an on-wire contract
   * in both directions. A regression where one of the two paths produced a different layout for
   * the same value would still be caught.
   */
  @Test
  public void instanceAndNewConstructorAgree() {
    var newInstance = new UnsafeBinaryConverter();

    // singleton writes, fresh instance reads
    var bufferA = new byte[4];
    UnsafeBinaryConverter.INSTANCE.putInt(bufferA, 0, 0x12345678, ByteOrder.BIG_ENDIAN);
    Assert.assertEquals(0x12345678, newInstance.getInt(bufferA, 0, ByteOrder.BIG_ENDIAN));

    // fresh instance writes, singleton reads
    var bufferB = new byte[4];
    newInstance.putInt(bufferB, 0, 0x12345678, ByteOrder.BIG_ENDIAN);
    Assert.assertEquals(
        0x12345678, UnsafeBinaryConverter.INSTANCE.getInt(bufferB, 0, ByteOrder.BIG_ENDIAN));

    // The wire layouts produced by the two paths must be byte-for-byte identical.
    Assert.assertArrayEquals(bufferA, bufferB);
  }

  /** UnsafeBinaryConverter always reports native acceleration. */
  @Test
  public void nativeAccelerationUsedReturnsTrue() {
    Assert.assertTrue(converter.nativeAccelerationUsed());
    Assert.assertTrue(UnsafeBinaryConverter.INSTANCE.nativeAccelerationUsed());
  }
}
