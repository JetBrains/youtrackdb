package com.jetbrains.youtrackdb.internal.common.serialization;

import java.nio.ByteOrder;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * Verifies {@link SafeBinaryConverter} byte-order conversion correctness.
 *
 * <p>The pure-Java {@code SafeBinaryConverter} performs byte-array indexing through normal Java
 * array accesses, so out-of-bounds indices throw {@link ArrayIndexOutOfBoundsException}. The
 * subclass-specific tests at the bottom of this class pin that bounds-checking behaviour. The
 * {@link UnsafeBinaryConverter} sibling does <strong>not</strong> bounds-check (it indexes via
 * {@code sun.misc.Unsafe}, which would corrupt heap memory on out-of-bounds access), so those
 * negative tests are deliberately Safe-only.
 */
public class SafeConverterTest extends AbstractConverterTest {

  @Before
  public void setUp() {
    converter = new SafeBinaryConverter();
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

  // --- Native byte order round-trips ---

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

  // ---------------------------------------------------------------------------
  // Safe-only bounds-checking tests
  //
  // The Java byte[] indexing in SafeBinaryConverter throws AIOOBE on negative
  // or past-end offsets and on writes that don't fit in a length-0 buffer. The
  // Unsafe sibling reads/writes raw memory at the same offset and would silently
  // corrupt the heap, so these tests deliberately do not exist on UnsafeConverterTest.
  // ---------------------------------------------------------------------------

  /** Negative offset on putInt throws AIOOBE; pins the bounds-check contract. */
  @Test(expected = ArrayIndexOutOfBoundsException.class)
  public void putIntAtNegativeOffsetThrows() {
    var buffer = new byte[8];
    converter.putInt(buffer, -1, 0xCAFEBABE, ByteOrder.BIG_ENDIAN);
  }

  /** putLong into a length-0 buffer throws AIOOBE; pins the bounds-check contract. */
  @Test(expected = ArrayIndexOutOfBoundsException.class)
  public void putLongIntoZeroLengthBufferThrows() {
    var buffer = new byte[0];
    converter.putLong(buffer, 0, 0xDEADBEEFCAFEBABEL, ByteOrder.BIG_ENDIAN);
  }

  /** getShort at a negative offset throws AIOOBE; pins the bounds-check contract. */
  @Test(expected = ArrayIndexOutOfBoundsException.class)
  public void getShortAtNegativeOffsetThrows() {
    var buffer = new byte[8];
    converter.getShort(buffer, -1, ByteOrder.BIG_ENDIAN);
  }

  /** getChar from a length-0 buffer throws AIOOBE; pins the bounds-check contract. */
  @Test(expected = ArrayIndexOutOfBoundsException.class)
  public void getCharFromZeroLengthBufferThrows() {
    var buffer = new byte[0];
    converter.getChar(buffer, 0, ByteOrder.BIG_ENDIAN);
  }

  /** putInt that would write past the buffer end throws AIOOBE. */
  @Test(expected = ArrayIndexOutOfBoundsException.class)
  public void putIntPastEndOfBufferThrows() {
    var buffer = new byte[5];
    // putInt writes 4 bytes starting at offset 2 → indices 2-5; index 5 is OOB.
    converter.putInt(buffer, 2, 0xCAFEBABE, ByteOrder.BIG_ENDIAN);
  }

  /**
   * SafeBinaryConverter exposes a public no-arg constructor in addition to the
   * {@link SafeBinaryConverter#INSTANCE} singleton; pin both yield equivalent observable behaviour.
   */
  @Test
  public void instanceAndNewConstructorAgree() {
    var newInstance = new SafeBinaryConverter();
    var buffer = new byte[4];
    SafeBinaryConverter.INSTANCE.putInt(buffer, 0, 0x12345678, ByteOrder.BIG_ENDIAN);
    Assert.assertEquals(0x12345678, newInstance.getInt(buffer, 0, ByteOrder.BIG_ENDIAN));
  }

  /** SafeBinaryConverter never reports native acceleration. */
  @Test
  public void nativeAccelerationUsedReturnsFalse() {
    Assert.assertFalse(converter.nativeAccelerationUsed());
    Assert.assertFalse(SafeBinaryConverter.INSTANCE.nativeAccelerationUsed());
  }
}
