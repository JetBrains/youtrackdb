package com.jetbrains.youtrackdb.internal.common.serialization;

import java.nio.ByteOrder;
import org.junit.Assert;

/**
 * Round-trip helpers for {@link BinaryConverter} implementations: write a value to a byte buffer
 * via {@code put*}, assert the on-wire byte layout matches the explicit hex pattern, then read the
 * value back via {@code get*} and assert it equals the original.
 *
 * <p>Concrete subclasses install the converter under test in a {@code @Before} method that assigns
 * the {@link #converter} field. JUnit 4 instantiates a fresh test class per {@code @Test} method,
 * so the field cannot leak between methods. Each subclass then declares {@code @Test} methods that
 * delegate to the protected helpers below.
 *
 * <p>Earlier revisions inlined the bodies as parameterless methods on this base class and forced
 * each subclass to override every method just to attach the {@code @Test} annotation that JUnit 4
 * re-reads at the override site. That shape was a footgun: a third subclass added without
 * remembering {@code @Test} on every override would silently never run, exactly the regression
 * fixed here. The earlier shape also asserted byte-array equality through
 * {@code Assert.assertEquals(byte[], byte[])}, which dispatched to {@code Object.equals}
 * (reference identity) and silently passed for any non-null pair, and passed scalar assertions in
 * {@code (actual, expected)} order, producing inverted failure messages. Helpers below use
 * {@link Assert#assertArrayEquals(byte[], byte[])} with the canonical {@code (expected, actual)}
 * argument order.
 *
 * @since 21.05.13
 */
public abstract class AbstractConverterTest {

  /** Set by the concrete subclass in a {@code @Before} method. Per-method JUnit 4 instantiation
   * guarantees no cross-method leakage. */
  protected BinaryConverter converter;

  /** Round-trips a high-bit int in big-endian order; pins the four-byte layout. */
  protected final void assertPutIntBigEndianRoundTrips() {
    var value = 0xFE23A067;

    var result = new byte[4];
    converter.putInt(result, 0, value, ByteOrder.BIG_ENDIAN);

    Assert.assertArrayEquals(new byte[] {(byte) 0xFE, 0x23, (byte) 0xA0, 0x67}, result);

    Assert.assertEquals(value, converter.getInt(result, 0, ByteOrder.BIG_ENDIAN));
  }

  /** Round-trips a high-bit int in little-endian order; pins the four-byte layout. */
  protected final void assertPutIntLittleEndianRoundTrips() {
    var value = 0xFE23A067;

    var result = new byte[4];
    converter.putInt(result, 0, value, ByteOrder.LITTLE_ENDIAN);

    Assert.assertArrayEquals(new byte[] {0x67, (byte) 0xA0, 0x23, (byte) 0xFE}, result);

    Assert.assertEquals(value, converter.getInt(result, 0, ByteOrder.LITTLE_ENDIAN));
  }

  /** Round-trips a high-bit long in big-endian order; pins the eight-byte layout. */
  protected final void assertPutLongBigEndianRoundTrips() {
    var value = 0xFE23A067ED890C14L;

    var result = new byte[8];
    converter.putLong(result, 0, value, ByteOrder.BIG_ENDIAN);

    Assert.assertArrayEquals(
        new byte[] {(byte) 0xFE, 0x23, (byte) 0xA0, 0x67, (byte) 0xED, (byte) 0x89, 0x0C, 0x14},
        result);
    Assert.assertEquals(value, converter.getLong(result, 0, ByteOrder.BIG_ENDIAN));
  }

  /** Round-trips a high-bit long in little-endian order; pins the eight-byte layout. */
  protected final void assertPutLongLittleEndianRoundTrips() {
    var value = 0xFE23A067ED890C14L;

    var result = new byte[8];
    converter.putLong(result, 0, value, ByteOrder.LITTLE_ENDIAN);

    Assert.assertArrayEquals(
        new byte[] {0x14, 0x0C, (byte) 0x89, (byte) 0xED, 0x67, (byte) 0xA0, 0x23, (byte) 0xFE},
        result);

    Assert.assertEquals(value, converter.getLong(result, 0, ByteOrder.LITTLE_ENDIAN));
  }

  /** Round-trips a high-bit short in big-endian order; pins the two-byte layout. */
  protected final void assertPutShortBigEndianRoundTrips() {
    var value = (short) 0xA028;
    var result = new byte[2];

    converter.putShort(result, 0, value, ByteOrder.BIG_ENDIAN);

    Assert.assertArrayEquals(new byte[] {(byte) 0xA0, 0x28}, result);
    Assert.assertEquals(value, converter.getShort(result, 0, ByteOrder.BIG_ENDIAN));
  }

  /** Round-trips a high-bit short in little-endian order; pins the two-byte layout. */
  protected final void assertPutShortLittleEndianRoundTrips() {
    var value = (short) 0xA028;
    var result = new byte[2];

    converter.putShort(result, 0, value, ByteOrder.LITTLE_ENDIAN);

    Assert.assertArrayEquals(new byte[] {0x28, (byte) 0xA0}, result);
    Assert.assertEquals(value, converter.getShort(result, 0, ByteOrder.LITTLE_ENDIAN));
  }

  /** Round-trips a high-bit char in big-endian order; pins the two-byte layout. */
  protected final void assertPutCharBigEndianRoundTrips() {
    var value = (char) 0xA028;
    var result = new byte[2];

    converter.putChar(result, 0, value, ByteOrder.BIG_ENDIAN);

    Assert.assertArrayEquals(new byte[] {(byte) 0xA0, 0x28}, result);
    Assert.assertEquals(value, converter.getChar(result, 0, ByteOrder.BIG_ENDIAN));
  }

  /** Round-trips a high-bit char in little-endian order; pins the two-byte layout. */
  protected final void assertPutCharLittleEndianRoundTrips() {
    var value = (char) 0xA028;
    var result = new byte[2];

    converter.putChar(result, 0, value, ByteOrder.LITTLE_ENDIAN);

    Assert.assertArrayEquals(new byte[] {0x28, (byte) 0xA0}, result);
    Assert.assertEquals(value, converter.getChar(result, 0, ByteOrder.LITTLE_ENDIAN));
  }

  // ---------------------------------------------------------------------------
  // Non-zero-offset round-trips
  //
  // A regression that ignored the offset parameter (always wrote at index 0)
  // would silently pass the offset-0 helpers above. The helpers below place the
  // value mid-buffer with a guard byte before and after the encoded region and
  // assert (a) the encoded bytes land at the requested offset, (b) the guard
  // bytes are untouched, and (c) the value round-trips when read back from the
  // same offset.
  // ---------------------------------------------------------------------------

  /** Writes an int at offset 2 in a 7-byte buffer (big-endian); guards untouched, value round-trips. */
  protected final void assertPutIntAtNonZeroOffsetBigEndianRoundTrips() {
    var value = 0xFE23A067;
    var buffer = new byte[7];
    buffer[0] = (byte) 0xAA; // guard before
    buffer[1] = (byte) 0xBB; // guard before
    buffer[6] = (byte) 0xCC; // guard after

    converter.putInt(buffer, 2, value, ByteOrder.BIG_ENDIAN);

    Assert.assertArrayEquals(
        new byte[] {
            (byte) 0xAA,
            (byte) 0xBB,
            (byte) 0xFE,
            0x23,
            (byte) 0xA0,
            0x67,
            (byte) 0xCC
        },
        buffer);
    Assert.assertEquals(value, converter.getInt(buffer, 2, ByteOrder.BIG_ENDIAN));
  }

  /** Writes an int at offset 2 in a 7-byte buffer (little-endian); guards untouched, value round-trips. */
  protected final void assertPutIntAtNonZeroOffsetLittleEndianRoundTrips() {
    var value = 0xFE23A067;
    var buffer = new byte[7];
    buffer[0] = (byte) 0xAA;
    buffer[1] = (byte) 0xBB;
    buffer[6] = (byte) 0xCC;

    converter.putInt(buffer, 2, value, ByteOrder.LITTLE_ENDIAN);

    Assert.assertArrayEquals(
        new byte[] {
            (byte) 0xAA,
            (byte) 0xBB,
            0x67,
            (byte) 0xA0,
            0x23,
            (byte) 0xFE,
            (byte) 0xCC
        },
        buffer);
    Assert.assertEquals(value, converter.getInt(buffer, 2, ByteOrder.LITTLE_ENDIAN));
  }

  /** Writes a long at offset 3 in a 13-byte buffer (big-endian); guards untouched, round-trips. */
  protected final void assertPutLongAtNonZeroOffsetBigEndianRoundTrips() {
    var value = 0xFE23A067ED890C14L;
    var buffer = new byte[13];
    buffer[0] = (byte) 0xAA;
    buffer[1] = (byte) 0xBB;
    buffer[2] = (byte) 0xCC;
    buffer[11] = (byte) 0xDD;
    buffer[12] = (byte) 0xEE;

    converter.putLong(buffer, 3, value, ByteOrder.BIG_ENDIAN);

    Assert.assertArrayEquals(
        new byte[] {
            (byte) 0xAA,
            (byte) 0xBB,
            (byte) 0xCC,
            (byte) 0xFE,
            0x23,
            (byte) 0xA0,
            0x67,
            (byte) 0xED,
            (byte) 0x89,
            0x0C,
            0x14,
            (byte) 0xDD,
            (byte) 0xEE
        },
        buffer);
    Assert.assertEquals(value, converter.getLong(buffer, 3, ByteOrder.BIG_ENDIAN));
  }

  /** Writes a long at offset 3 in a 13-byte buffer (little-endian); guards untouched, round-trips. */
  protected final void assertPutLongAtNonZeroOffsetLittleEndianRoundTrips() {
    var value = 0xFE23A067ED890C14L;
    var buffer = new byte[13];
    buffer[0] = (byte) 0xAA;
    buffer[1] = (byte) 0xBB;
    buffer[2] = (byte) 0xCC;
    buffer[11] = (byte) 0xDD;
    buffer[12] = (byte) 0xEE;

    converter.putLong(buffer, 3, value, ByteOrder.LITTLE_ENDIAN);

    Assert.assertArrayEquals(
        new byte[] {
            (byte) 0xAA,
            (byte) 0xBB,
            (byte) 0xCC,
            0x14,
            0x0C,
            (byte) 0x89,
            (byte) 0xED,
            0x67,
            (byte) 0xA0,
            0x23,
            (byte) 0xFE,
            (byte) 0xDD,
            (byte) 0xEE
        },
        buffer);
    Assert.assertEquals(value, converter.getLong(buffer, 3, ByteOrder.LITTLE_ENDIAN));
  }

  /** Writes a short at offset 1 in a 4-byte buffer (big-endian); guards untouched, round-trips. */
  protected final void assertPutShortAtNonZeroOffsetBigEndianRoundTrips() {
    var value = (short) 0xA028;
    var buffer = new byte[] {(byte) 0xAA, 0x00, 0x00, (byte) 0xBB};

    converter.putShort(buffer, 1, value, ByteOrder.BIG_ENDIAN);

    Assert.assertArrayEquals(
        new byte[] {(byte) 0xAA, (byte) 0xA0, 0x28, (byte) 0xBB}, buffer);
    Assert.assertEquals(value, converter.getShort(buffer, 1, ByteOrder.BIG_ENDIAN));
  }

  /** Writes a short at offset 1 in a 4-byte buffer (little-endian); guards untouched, round-trips. */
  protected final void assertPutShortAtNonZeroOffsetLittleEndianRoundTrips() {
    var value = (short) 0xA028;
    var buffer = new byte[] {(byte) 0xAA, 0x00, 0x00, (byte) 0xBB};

    converter.putShort(buffer, 1, value, ByteOrder.LITTLE_ENDIAN);

    Assert.assertArrayEquals(
        new byte[] {(byte) 0xAA, 0x28, (byte) 0xA0, (byte) 0xBB}, buffer);
    Assert.assertEquals(value, converter.getShort(buffer, 1, ByteOrder.LITTLE_ENDIAN));
  }

  /** Writes a char at offset 2 in a 5-byte buffer (big-endian); guards untouched, round-trips. */
  protected final void assertPutCharAtNonZeroOffsetBigEndianRoundTrips() {
    var value = (char) 0xA028;
    var buffer = new byte[] {(byte) 0xAA, (byte) 0xBB, 0x00, 0x00, (byte) 0xCC};

    converter.putChar(buffer, 2, value, ByteOrder.BIG_ENDIAN);

    Assert.assertArrayEquals(
        new byte[] {(byte) 0xAA, (byte) 0xBB, (byte) 0xA0, 0x28, (byte) 0xCC}, buffer);
    Assert.assertEquals(value, converter.getChar(buffer, 2, ByteOrder.BIG_ENDIAN));
  }

  /** Writes a char at offset 2 in a 5-byte buffer (little-endian); guards untouched, round-trips. */
  protected final void assertPutCharAtNonZeroOffsetLittleEndianRoundTrips() {
    var value = (char) 0xA028;
    var buffer = new byte[] {(byte) 0xAA, (byte) 0xBB, 0x00, 0x00, (byte) 0xCC};

    converter.putChar(buffer, 2, value, ByteOrder.LITTLE_ENDIAN);

    Assert.assertArrayEquals(
        new byte[] {(byte) 0xAA, (byte) 0xBB, 0x28, (byte) 0xA0, (byte) 0xCC}, buffer);
    Assert.assertEquals(value, converter.getChar(buffer, 2, ByteOrder.LITTLE_ENDIAN));
  }

  // ---------------------------------------------------------------------------
  // Boundary values
  //
  // Round-trip the integer extremes (MIN / MAX / 0 / -1) in both byte orders.
  // A regression that wrote only the low bytes (or used the wrong shift width)
  // would corrupt the high bits and fail to round-trip MIN/MAX, while still
  // passing the offset-0 high-bit helper above.
  // ---------------------------------------------------------------------------

  /** Round-trips Integer MIN/MAX/0/-1 in both byte orders at offset 0. */
  protected final void assertPutIntBoundaryValuesRoundTrip() {
    for (var order : new ByteOrder[] {ByteOrder.BIG_ENDIAN, ByteOrder.LITTLE_ENDIAN}) {
      for (var value : new int[] {Integer.MIN_VALUE, Integer.MAX_VALUE, 0, -1, 1}) {
        var buffer = new byte[4];
        converter.putInt(buffer, 0, value, order);
        Assert.assertEquals(
            "value=" + value + " order=" + order, value, converter.getInt(buffer, 0, order));
      }
    }
  }

  /** Round-trips Long MIN/MAX/0/-1 in both byte orders at offset 0. */
  protected final void assertPutLongBoundaryValuesRoundTrip() {
    for (var order : new ByteOrder[] {ByteOrder.BIG_ENDIAN, ByteOrder.LITTLE_ENDIAN}) {
      for (var value : new long[] {Long.MIN_VALUE, Long.MAX_VALUE, 0L, -1L, 1L}) {
        var buffer = new byte[8];
        converter.putLong(buffer, 0, value, order);
        Assert.assertEquals(
            "value=" + value + " order=" + order, value, converter.getLong(buffer, 0, order));
      }
    }
  }

  /** Round-trips Short MIN/MAX/0/-1 in both byte orders at offset 0. */
  protected final void assertPutShortBoundaryValuesRoundTrip() {
    for (var order : new ByteOrder[] {ByteOrder.BIG_ENDIAN, ByteOrder.LITTLE_ENDIAN}) {
      for (var value : new short[] {Short.MIN_VALUE, Short.MAX_VALUE, (short) 0, (short) -1}) {
        var buffer = new byte[2];
        converter.putShort(buffer, 0, value, order);
        Assert.assertEquals(
            "value=" + value + " order=" + order, value, converter.getShort(buffer, 0, order));
      }
    }
  }

  /** Round-trips Char MIN/MAX/0 in both byte orders at offset 0. */
  protected final void assertPutCharBoundaryValuesRoundTrip() {
    for (var order : new ByteOrder[] {ByteOrder.BIG_ENDIAN, ByteOrder.LITTLE_ENDIAN}) {
      for (var value : new char[] {Character.MIN_VALUE, Character.MAX_VALUE, ' ', '\u00FF',
          '\uFF00'}) {
        var buffer = new byte[2];
        converter.putChar(buffer, 0, value, order);
        Assert.assertEquals(
            "value=" + (int) value + " order=" + order,
            value,
            converter.getChar(buffer, 0, order));
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Native byte-order round-trips
  //
  // The Unsafe-backed converter has a fast path that skips reverseBytes() when
  // the requested order matches ByteOrder.nativeOrder() (the common case on
  // little-endian x86 / ARM). The Safe converter is order-symmetric and exercises
  // either bigEndian or littleEndian helper unconditionally. Pinning the native-
  // order round-trip per type catches a regression that mishandles either path.
  // ---------------------------------------------------------------------------

  /** Round-trips an int via the native byte order; exercises the unsafe no-reverse fast path. */
  protected final void assertPutIntNativeByteOrderRoundTrips() {
    var value = 0xFE23A067;
    var buffer = new byte[4];
    converter.putInt(buffer, 0, value, ByteOrder.nativeOrder());
    Assert.assertEquals(value, converter.getInt(buffer, 0, ByteOrder.nativeOrder()));
  }

  /** Round-trips a long via the native byte order; exercises the unsafe no-reverse fast path. */
  protected final void assertPutLongNativeByteOrderRoundTrips() {
    var value = 0xFE23A067ED890C14L;
    var buffer = new byte[8];
    converter.putLong(buffer, 0, value, ByteOrder.nativeOrder());
    Assert.assertEquals(value, converter.getLong(buffer, 0, ByteOrder.nativeOrder()));
  }

  /** Round-trips a short via the native byte order; exercises the unsafe no-reverse fast path. */
  protected final void assertPutShortNativeByteOrderRoundTrips() {
    var value = (short) 0xA028;
    var buffer = new byte[2];
    converter.putShort(buffer, 0, value, ByteOrder.nativeOrder());
    Assert.assertEquals(value, converter.getShort(buffer, 0, ByteOrder.nativeOrder()));
  }

  /** Round-trips a char via the native byte order; exercises the unsafe no-reverse fast path. */
  protected final void assertPutCharNativeByteOrderRoundTrips() {
    var value = (char) 0xA028;
    var buffer = new byte[2];
    converter.putChar(buffer, 0, value, ByteOrder.nativeOrder());
    Assert.assertEquals(value, converter.getChar(buffer, 0, ByteOrder.nativeOrder()));
  }
}
