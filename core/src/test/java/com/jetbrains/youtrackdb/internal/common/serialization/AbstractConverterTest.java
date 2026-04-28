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
}
