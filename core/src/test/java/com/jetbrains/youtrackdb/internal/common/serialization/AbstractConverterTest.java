package com.jetbrains.youtrackdb.internal.common.serialization;

import java.nio.ByteOrder;
import org.junit.Assert;

/**
 * Base test class for {@link BinaryConverter} implementations verifying byte-order conversions.
 *
 * <p>Concrete subclasses inject the converter under test in {@code @Before} and override each
 * {@code testPut*} method with a {@code @Test} annotation so JUnit 4 picks them up. Earlier
 * revisions of these tests omitted {@code @Test} entirely and silently never ran; equality on
 * {@code byte[]} also went through {@code Object.equals} (reference identity) instead of element
 * comparison. Both issues are fixed here.
 *
 * @since 21.05.13
 */
public abstract class AbstractConverterTest {

  protected BinaryConverter converter;

  public void testPutIntBigEndian() {
    var value = 0xFE23A067;

    var result = new byte[4];
    converter.putInt(result, 0, value, ByteOrder.BIG_ENDIAN);

    Assert.assertArrayEquals(new byte[] {(byte) 0xFE, 0x23, (byte) 0xA0, 0x67}, result);

    Assert.assertEquals(value, converter.getInt(result, 0, ByteOrder.BIG_ENDIAN));
  }

  public void testPutIntLittleEndian() {
    var value = 0xFE23A067;

    var result = new byte[4];
    converter.putInt(result, 0, value, ByteOrder.LITTLE_ENDIAN);

    Assert.assertArrayEquals(new byte[] {0x67, (byte) 0xA0, 0x23, (byte) 0xFE}, result);

    Assert.assertEquals(value, converter.getInt(result, 0, ByteOrder.LITTLE_ENDIAN));
  }

  public void testPutLongBigEndian() {
    var value = 0xFE23A067ED890C14L;

    var result = new byte[8];
    converter.putLong(result, 0, value, ByteOrder.BIG_ENDIAN);

    Assert.assertArrayEquals(
        new byte[] {(byte) 0xFE, 0x23, (byte) 0xA0, 0x67, (byte) 0xED, (byte) 0x89, 0x0C, 0x14},
        result);
    Assert.assertEquals(value, converter.getLong(result, 0, ByteOrder.BIG_ENDIAN));
  }

  public void testPutLongLittleEndian() {
    var value = 0xFE23A067ED890C14L;

    var result = new byte[8];
    converter.putLong(result, 0, value, ByteOrder.LITTLE_ENDIAN);

    Assert.assertArrayEquals(
        new byte[] {0x14, 0x0C, (byte) 0x89, (byte) 0xED, 0x67, (byte) 0xA0, 0x23, (byte) 0xFE},
        result);

    Assert.assertEquals(value, converter.getLong(result, 0, ByteOrder.LITTLE_ENDIAN));
  }

  public void testPutShortBigEndian() {
    var value = (short) 0xA028;
    var result = new byte[2];

    converter.putShort(result, 0, value, ByteOrder.BIG_ENDIAN);

    Assert.assertArrayEquals(new byte[] {(byte) 0xA0, 0x28}, result);
    Assert.assertEquals(value, converter.getShort(result, 0, ByteOrder.BIG_ENDIAN));
  }

  public void testPutShortLittleEndian() {
    var value = (short) 0xA028;
    var result = new byte[2];

    converter.putShort(result, 0, value, ByteOrder.LITTLE_ENDIAN);

    Assert.assertArrayEquals(new byte[] {0x28, (byte) 0xA0}, result);
    Assert.assertEquals(value, converter.getShort(result, 0, ByteOrder.LITTLE_ENDIAN));
  }

  public void testPutCharBigEndian() {
    var value = (char) 0xA028;
    var result = new byte[2];

    converter.putChar(result, 0, value, ByteOrder.BIG_ENDIAN);

    Assert.assertArrayEquals(new byte[] {(byte) 0xA0, 0x28}, result);
    Assert.assertEquals(value, converter.getChar(result, 0, ByteOrder.BIG_ENDIAN));
  }

  public void testPutCharLittleEndian() {
    var value = (char) 0xA028;
    var result = new byte[2];

    converter.putChar(result, 0, value, ByteOrder.LITTLE_ENDIAN);

    Assert.assertArrayEquals(new byte[] {0x28, (byte) 0xA0}, result);
    Assert.assertEquals(value, converter.getChar(result, 0, ByteOrder.LITTLE_ENDIAN));
  }
}
