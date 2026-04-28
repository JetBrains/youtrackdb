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
   * {@link UnsafeBinaryConverter#INSTANCE} singleton; pin both yield equivalent observable behaviour.
   */
  @Test
  public void instanceAndNewConstructorAgree() {
    var newInstance = new UnsafeBinaryConverter();
    var buffer = new byte[4];
    UnsafeBinaryConverter.INSTANCE.putInt(buffer, 0, 0x12345678, ByteOrder.BIG_ENDIAN);
    Assert.assertEquals(0x12345678, newInstance.getInt(buffer, 0, ByteOrder.BIG_ENDIAN));
  }

  /** UnsafeBinaryConverter always reports native acceleration. */
  @Test
  public void nativeAccelerationUsedReturnsTrue() {
    Assert.assertTrue(converter.nativeAccelerationUsed());
    Assert.assertTrue(UnsafeBinaryConverter.INSTANCE.nativeAccelerationUsed());
  }
}
