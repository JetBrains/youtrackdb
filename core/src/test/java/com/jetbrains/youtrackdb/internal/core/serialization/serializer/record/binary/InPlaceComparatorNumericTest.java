/*
 *
 *
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *
 *
 */

package com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.common.serialization.types.IntegerSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.LongSerializer;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import java.util.OptionalInt;
import org.junit.Test;

/**
 * Tests for {@link InPlaceComparator} numeric type comparison (INTEGER, LONG, SHORT, BYTE, FLOAT,
 * DOUBLE). Covers same-type comparison, cross-type conversion, precision boundaries, and edge
 * cases.
 */
public class InPlaceComparatorNumericTest {

  // ---- Helper methods to create BinaryFields with serialized values ----

  private static BinaryField intField(int value) {
    var bytes = new BytesContainer();
    VarIntSerializer.write(bytes, value);
    return new BinaryField("test", PropertyTypeInternal.INTEGER, new BytesContainer(bytes.bytes, 0),
        null);
  }

  private static BinaryField longField(long value) {
    var bytes = new BytesContainer();
    VarIntSerializer.write(bytes, value);
    return new BinaryField("test", PropertyTypeInternal.LONG, new BytesContainer(bytes.bytes, 0),
        null);
  }

  private static BinaryField shortField(short value) {
    var bytes = new BytesContainer();
    VarIntSerializer.write(bytes, value);
    return new BinaryField("test", PropertyTypeInternal.SHORT, new BytesContainer(bytes.bytes, 0),
        null);
  }

  private static BinaryField byteField(byte value) {
    var bytes = new BytesContainer();
    var pos = bytes.alloc(1);
    bytes.bytes[pos] = value;
    return new BinaryField("test", PropertyTypeInternal.BYTE, new BytesContainer(bytes.bytes, 0),
        null);
  }

  private static BinaryField floatField(float value) {
    var bytes = new BytesContainer();
    var pos = bytes.alloc(IntegerSerializer.INT_SIZE);
    IntegerSerializer.serializeLiteral(Float.floatToIntBits(value), bytes.bytes, pos);
    return new BinaryField("test", PropertyTypeInternal.FLOAT, new BytesContainer(bytes.bytes, 0),
        null);
  }

  private static BinaryField doubleField(double value) {
    var bytes = new BytesContainer();
    var pos = bytes.alloc(LongSerializer.LONG_SIZE);
    LongSerializer.serializeLiteral(Double.doubleToLongBits(value), bytes.bytes, pos);
    return new BinaryField("test", PropertyTypeInternal.DOUBLE, new BytesContainer(bytes.bytes, 0),
        null);
  }

  // ===========================================================================
  // INTEGER tests
  // ===========================================================================

  /** Compare INTEGER field with matching Integer value — should return 0. */
  @Test
  public void testIntegerEqualSameType() {
    var result = InPlaceComparator.compare(intField(42), 42);
    assertEquals(OptionalInt.of(0), result);
  }

  /** Compare INTEGER field with greater Integer value — serialized < value, so result < 0. */
  @Test
  public void testIntegerLessThan() {
    var result = InPlaceComparator.compare(intField(10), 20);
    assertTrue(result.isPresent());
    assertTrue(result.getAsInt() < 0);
  }

  /** Compare INTEGER field with smaller Integer value — serialized > value, so result > 0. */
  @Test
  public void testIntegerGreaterThan() {
    var result = InPlaceComparator.compare(intField(20), 10);
    assertTrue(result.isPresent());
    assertTrue(result.getAsInt() > 0);
  }

  /** INTEGER compared with a Long value that fits in int range — should succeed. */
  @Test
  public void testIntegerWithLongInRange() {
    var result = InPlaceComparator.compare(intField(100), 100L);
    assertEquals(OptionalInt.of(0), result);
  }

  /** INTEGER compared with a Long value outside int range — should fall back. */
  @Test
  public void testIntegerWithLongOutOfRange() {
    var result = InPlaceComparator.compare(intField(100), (long) Integer.MAX_VALUE + 1);
    assertTrue(result.isEmpty());
  }

  /** INTEGER compared with a Short value — should widen Short to int. */
  @Test
  public void testIntegerWithShort() {
    var result = InPlaceComparator.compare(intField(42), (short) 42);
    assertEquals(OptionalInt.of(0), result);
  }

  /** INTEGER compared with a Byte value — should widen Byte to int. */
  @Test
  public void testIntegerWithByte() {
    var result = InPlaceComparator.compare(intField(7), (byte) 7);
    assertEquals(OptionalInt.of(0), result);
  }

  /** INTEGER compared with a Float — should fall back (float-to-int not supported). */
  @Test
  public void testIntegerWithFloatFallback() {
    var result = InPlaceComparator.compare(intField(42), 42.0f);
    assertTrue(result.isEmpty());
  }

  /** INTEGER compared with a Double — should fall back (double-to-int not supported). */
  @Test
  public void testIntegerWithDoubleFallback() {
    var result = InPlaceComparator.compare(intField(42), 42.0);
    assertTrue(result.isEmpty());
  }

  /** INTEGER at Integer.MAX_VALUE — boundary test. */
  @Test
  public void testIntegerMaxValue() {
    var result = InPlaceComparator.compare(intField(Integer.MAX_VALUE), Integer.MAX_VALUE);
    assertEquals(OptionalInt.of(0), result);
  }

  /** INTEGER at Integer.MIN_VALUE — boundary test. */
  @Test
  public void testIntegerMinValue() {
    var result = InPlaceComparator.compare(intField(Integer.MIN_VALUE), Integer.MIN_VALUE);
    assertEquals(OptionalInt.of(0), result);
  }

  /** INTEGER with non-Number value — should fall back. */
  @Test
  public void testIntegerWithNonNumber() {
    var result = InPlaceComparator.compare(intField(42), "42");
    assertTrue(result.isEmpty());
  }

  /** INTEGER zero — boundary test. */
  @Test
  public void testIntegerZero() {
    var result = InPlaceComparator.compare(intField(0), 0);
    assertEquals(OptionalInt.of(0), result);
  }

  /** INTEGER negative value. */
  @Test
  public void testIntegerNegative() {
    var result = InPlaceComparator.compare(intField(-100), -100);
    assertEquals(OptionalInt.of(0), result);
  }

  // ===========================================================================
  // LONG tests
  // ===========================================================================

  /** Compare LONG field with matching Long value. */
  @Test
  public void testLongEqualSameType() {
    var result = InPlaceComparator.compare(longField(123456789L), 123456789L);
    assertEquals(OptionalInt.of(0), result);
  }

  /** LONG less than comparison. */
  @Test
  public void testLongLessThan() {
    var result = InPlaceComparator.compare(longField(10L), 20L);
    assertTrue(result.isPresent());
    assertTrue(result.getAsInt() < 0);
  }

  /** LONG greater than comparison. */
  @Test
  public void testLongGreaterThan() {
    var result = InPlaceComparator.compare(longField(20L), 10L);
    assertTrue(result.isPresent());
    assertTrue(result.getAsInt() > 0);
  }

  /** LONG compared with Integer — should widen Integer to long. */
  @Test
  public void testLongWithInteger() {
    var result = InPlaceComparator.compare(longField(42L), 42);
    assertEquals(OptionalInt.of(0), result);
  }

  /** LONG compared with Short — should widen to long. */
  @Test
  public void testLongWithShort() {
    var result = InPlaceComparator.compare(longField(42L), (short) 42);
    assertEquals(OptionalInt.of(0), result);
  }

  /** LONG compared with Float — should fall back. */
  @Test
  public void testLongWithFloatFallback() {
    var result = InPlaceComparator.compare(longField(42L), 42.0f);
    assertTrue(result.isEmpty());
  }

  /** LONG compared with Double — should fall back. */
  @Test
  public void testLongWithDoubleFallback() {
    var result = InPlaceComparator.compare(longField(42L), 42.0);
    assertTrue(result.isEmpty());
  }

  /** LONG at Long.MAX_VALUE. */
  @Test
  public void testLongMaxValue() {
    var result = InPlaceComparator.compare(longField(Long.MAX_VALUE), Long.MAX_VALUE);
    assertEquals(OptionalInt.of(0), result);
  }

  /** LONG at Long.MIN_VALUE. */
  @Test
  public void testLongMinValue() {
    var result = InPlaceComparator.compare(longField(Long.MIN_VALUE), Long.MIN_VALUE);
    assertEquals(OptionalInt.of(0), result);
  }

  // ===========================================================================
  // SHORT tests
  // ===========================================================================

  /** Compare SHORT field with matching Short value. */
  @Test
  public void testShortEqualSameType() {
    var result = InPlaceComparator.compare(shortField((short) 42), (short) 42);
    assertEquals(OptionalInt.of(0), result);
  }

  /** SHORT less than comparison. */
  @Test
  public void testShortLessThan() {
    var result = InPlaceComparator.compare(shortField((short) 10), (short) 20);
    assertTrue(result.isPresent());
    assertTrue(result.getAsInt() < 0);
  }

  /** SHORT with Integer in range — should narrow Integer to short. */
  @Test
  public void testShortWithIntegerInRange() {
    var result = InPlaceComparator.compare(shortField((short) 100), 100);
    assertEquals(OptionalInt.of(0), result);
  }

  /** SHORT with Integer out of range — should fall back. */
  @Test
  public void testShortWithIntegerOutOfRange() {
    var result = InPlaceComparator.compare(shortField((short) 100), 100_000);
    assertTrue(result.isEmpty());
  }

  /** SHORT with Long in range — should narrow Long to short. */
  @Test
  public void testShortWithLongInRange() {
    var result = InPlaceComparator.compare(shortField((short) 100), 100L);
    assertEquals(OptionalInt.of(0), result);
  }

  /** SHORT with Long out of range — should fall back. */
  @Test
  public void testShortWithLongOutOfRange() {
    var result = InPlaceComparator.compare(shortField((short) 100), 100_000L);
    assertTrue(result.isEmpty());
  }

  /** SHORT with Byte — should widen Byte to short. */
  @Test
  public void testShortWithByte() {
    var result = InPlaceComparator.compare(shortField((short) 42), (byte) 42);
    assertEquals(OptionalInt.of(0), result);
  }

  /** SHORT with Float — should fall back. */
  @Test
  public void testShortWithFloatFallback() {
    var result = InPlaceComparator.compare(shortField((short) 42), 42.0f);
    assertTrue(result.isEmpty());
  }

  /** SHORT at Short.MAX_VALUE. */
  @Test
  public void testShortMaxValue() {
    var result = InPlaceComparator.compare(shortField(Short.MAX_VALUE), (short) Short.MAX_VALUE);
    assertEquals(OptionalInt.of(0), result);
  }

  /** SHORT at Short.MIN_VALUE. */
  @Test
  public void testShortMinValue() {
    var result = InPlaceComparator.compare(shortField(Short.MIN_VALUE), (short) Short.MIN_VALUE);
    assertEquals(OptionalInt.of(0), result);
  }

  // ===========================================================================
  // BYTE tests
  // ===========================================================================

  /** Compare BYTE field with matching Byte value. */
  @Test
  public void testByteEqualSameType() {
    var result = InPlaceComparator.compare(byteField((byte) 42), (byte) 42);
    assertEquals(OptionalInt.of(0), result);
  }

  /** BYTE less than comparison. */
  @Test
  public void testByteLessThan() {
    var result = InPlaceComparator.compare(byteField((byte) 10), (byte) 20);
    assertTrue(result.isPresent());
    assertTrue(result.getAsInt() < 0);
  }

  /** BYTE with Integer in range — should narrow Integer to byte. */
  @Test
  public void testByteWithIntegerInRange() {
    var result = InPlaceComparator.compare(byteField((byte) 42), 42);
    assertEquals(OptionalInt.of(0), result);
  }

  /** BYTE with Integer out of range — should fall back. */
  @Test
  public void testByteWithIntegerOutOfRange() {
    var result = InPlaceComparator.compare(byteField((byte) 42), 1000);
    assertTrue(result.isEmpty());
  }

  /** BYTE with Short in range — should narrow Short to byte. */
  @Test
  public void testByteWithShortInRange() {
    var result = InPlaceComparator.compare(byteField((byte) 42), (short) 42);
    assertEquals(OptionalInt.of(0), result);
  }

  /** BYTE with Short out of range — should fall back. */
  @Test
  public void testByteWithShortOutOfRange() {
    var result = InPlaceComparator.compare(byteField((byte) 42), (short) 1000);
    assertTrue(result.isEmpty());
  }

  /** BYTE with Long in range — should narrow Long to byte. */
  @Test
  public void testByteWithLongInRange() {
    var result = InPlaceComparator.compare(byteField((byte) 42), 42L);
    assertEquals(OptionalInt.of(0), result);
  }

  /** BYTE with Long out of range — should fall back. */
  @Test
  public void testByteWithLongOutOfRange() {
    var result = InPlaceComparator.compare(byteField((byte) 42), 1000L);
    assertTrue(result.isEmpty());
  }

  /** BYTE with Float — should fall back. */
  @Test
  public void testByteWithFloatFallback() {
    var result = InPlaceComparator.compare(byteField((byte) 42), 42.0f);
    assertTrue(result.isEmpty());
  }

  /** BYTE at Byte.MAX_VALUE. */
  @Test
  public void testByteMaxValue() {
    var result = InPlaceComparator.compare(byteField(Byte.MAX_VALUE), (byte) Byte.MAX_VALUE);
    assertEquals(OptionalInt.of(0), result);
  }

  /** BYTE at Byte.MIN_VALUE. */
  @Test
  public void testByteMinValue() {
    var result = InPlaceComparator.compare(byteField(Byte.MIN_VALUE), (byte) Byte.MIN_VALUE);
    assertEquals(OptionalInt.of(0), result);
  }

  // ===========================================================================
  // FLOAT tests
  // ===========================================================================

  /** Compare FLOAT field with matching Float value. */
  @Test
  public void testFloatEqualSameType() {
    var result = InPlaceComparator.compare(floatField(3.14f), 3.14f);
    assertEquals(OptionalInt.of(0), result);
  }

  /** FLOAT less than comparison. */
  @Test
  public void testFloatLessThan() {
    var result = InPlaceComparator.compare(floatField(1.0f), 2.0f);
    assertTrue(result.isPresent());
    assertTrue(result.getAsInt() < 0);
  }

  /** FLOAT greater than comparison. */
  @Test
  public void testFloatGreaterThan() {
    var result = InPlaceComparator.compare(floatField(2.0f), 1.0f);
    assertTrue(result.isPresent());
    assertTrue(result.getAsInt() > 0);
  }

  /**
   * FLOAT compared with Double — should widen float to double for comparison. A float value
   * of 3.14f widened to double != 3.14d, so they should not be equal.
   */
  @Test
  public void testFloatWithDoubleWidening() {
    // 3.14f cast to double is 3.140000104904175, not 3.14
    var result = InPlaceComparator.compare(floatField(3.14f), 3.14);
    assertTrue(result.isPresent());
    // They should NOT be equal due to float precision
    assertTrue(result.getAsInt() != 0);
  }

  /** FLOAT compared with Double where both are exactly representable. */
  @Test
  public void testFloatWithDoubleExactMatch() {
    var result = InPlaceComparator.compare(floatField(1.0f), 1.0);
    assertEquals(OptionalInt.of(0), result);
  }

  /**
   * FLOAT compared with Integer within safe range (|value| <= 2^24) — should convert int to
   * float.
   */
  @Test
  public void testFloatWithIntegerInSafeRange() {
    var result = InPlaceComparator.compare(floatField(42.0f), 42);
    assertEquals(OptionalInt.of(0), result);
  }

  /**
   * FLOAT compared with Integer at precision boundary (2^24) — should succeed since 2^24 is
   * exactly representable.
   */
  @Test
  public void testFloatWithIntegerAtPrecisionBoundary() {
    int boundary = 1 << 24; // 16_777_216
    var result = InPlaceComparator.compare(floatField((float) boundary), boundary);
    assertEquals(OptionalInt.of(0), result);
  }

  /**
   * FLOAT compared with Integer above precision boundary (2^24 + 1) — should fall back because
   * float cannot represent this integer exactly.
   */
  @Test
  public void testFloatWithIntegerAbovePrecisionBoundary() {
    int aboveBoundary = (1 << 24) + 1; // 16_777_217
    var result = InPlaceComparator.compare(floatField(42.0f), aboveBoundary);
    assertTrue(result.isEmpty());
  }

  /** FLOAT NaN compared with NaN — Float.compare(NaN, NaN) == 0. */
  @Test
  public void testFloatNaN() {
    var result = InPlaceComparator.compare(floatField(Float.NaN), Float.NaN);
    assertEquals(OptionalInt.of(0), result);
  }

  /** FLOAT -0.0 compared with +0.0 — Float.compare(-0.0f, 0.0f) < 0. */
  @Test
  public void testFloatNegativeZero() {
    var result = InPlaceComparator.compare(floatField(-0.0f), 0.0f);
    assertTrue(result.isPresent());
    assertTrue(result.getAsInt() < 0);
  }

  /** FLOAT positive infinity. */
  @Test
  public void testFloatPositiveInfinity() {
    var result =
        InPlaceComparator.compare(floatField(Float.POSITIVE_INFINITY), Float.POSITIVE_INFINITY);
    assertEquals(OptionalInt.of(0), result);
  }

  /** FLOAT negative infinity. */
  @Test
  public void testFloatNegativeInfinity() {
    var result =
        InPlaceComparator.compare(floatField(Float.NEGATIVE_INFINITY), Float.NEGATIVE_INFINITY);
    assertEquals(OptionalInt.of(0), result);
  }

  /** FLOAT MAX_VALUE. */
  @Test
  public void testFloatMaxValue() {
    var result = InPlaceComparator.compare(floatField(Float.MAX_VALUE), Float.MAX_VALUE);
    assertEquals(OptionalInt.of(0), result);
  }

  /** FLOAT MIN_VALUE (smallest positive). */
  @Test
  public void testFloatMinValue() {
    var result = InPlaceComparator.compare(floatField(Float.MIN_VALUE), Float.MIN_VALUE);
    assertEquals(OptionalInt.of(0), result);
  }

  // ===========================================================================
  // DOUBLE tests
  // ===========================================================================

  /** Compare DOUBLE field with matching Double value. */
  @Test
  public void testDoubleEqualSameType() {
    var result = InPlaceComparator.compare(doubleField(3.14159265358979), 3.14159265358979);
    assertEquals(OptionalInt.of(0), result);
  }

  /** DOUBLE less than comparison. */
  @Test
  public void testDoubleLessThan() {
    var result = InPlaceComparator.compare(doubleField(1.0), 2.0);
    assertTrue(result.isPresent());
    assertTrue(result.getAsInt() < 0);
  }

  /** DOUBLE greater than comparison. */
  @Test
  public void testDoubleGreaterThan() {
    var result = InPlaceComparator.compare(doubleField(2.0), 1.0);
    assertTrue(result.isPresent());
    assertTrue(result.getAsInt() > 0);
  }

  /** DOUBLE compared with Float — should widen float to double. */
  @Test
  public void testDoubleWithFloat() {
    var result = InPlaceComparator.compare(doubleField(1.0), 1.0f);
    assertEquals(OptionalInt.of(0), result);
  }

  /** DOUBLE compared with Integer — safe, int < 2^53. */
  @Test
  public void testDoubleWithInteger() {
    var result = InPlaceComparator.compare(doubleField(42.0), 42);
    assertEquals(OptionalInt.of(0), result);
  }

  /** DOUBLE compared with Long within safe range (|value| <= 2^53). */
  @Test
  public void testDoubleWithLongInSafeRange() {
    var result = InPlaceComparator.compare(doubleField(123456789.0), 123456789L);
    assertEquals(OptionalInt.of(0), result);
  }

  /**
   * DOUBLE compared with Long at precision boundary (2^53) — should succeed since 2^53 is exactly
   * representable.
   */
  @Test
  public void testDoubleWithLongAtPrecisionBoundary() {
    long boundary = 1L << 53; // 9_007_199_254_740_992
    var result = InPlaceComparator.compare(doubleField((double) boundary), boundary);
    assertEquals(OptionalInt.of(0), result);
  }

  /**
   * DOUBLE compared with Long above precision boundary (2^53 + 1) — should fall back because
   * double cannot represent this long exactly.
   */
  @Test
  public void testDoubleWithLongAbovePrecisionBoundary() {
    long aboveBoundary = (1L << 53) + 1; // 9_007_199_254_740_993
    var result = InPlaceComparator.compare(doubleField(42.0), aboveBoundary);
    assertTrue(result.isEmpty());
  }

  /** DOUBLE NaN compared with NaN — Double.compare(NaN, NaN) == 0. */
  @Test
  public void testDoubleNaN() {
    var result = InPlaceComparator.compare(doubleField(Double.NaN), Double.NaN);
    assertEquals(OptionalInt.of(0), result);
  }

  /** DOUBLE -0.0 compared with +0.0 — Double.compare(-0.0, 0.0) < 0. */
  @Test
  public void testDoubleNegativeZero() {
    var result = InPlaceComparator.compare(doubleField(-0.0), 0.0);
    assertTrue(result.isPresent());
    assertTrue(result.getAsInt() < 0);
  }

  /** DOUBLE positive infinity. */
  @Test
  public void testDoublePositiveInfinity() {
    var result =
        InPlaceComparator.compare(
            doubleField(Double.POSITIVE_INFINITY), Double.POSITIVE_INFINITY);
    assertEquals(OptionalInt.of(0), result);
  }

  /** DOUBLE negative infinity. */
  @Test
  public void testDoubleNegativeInfinity() {
    var result =
        InPlaceComparator.compare(
            doubleField(Double.NEGATIVE_INFINITY), Double.NEGATIVE_INFINITY);
    assertEquals(OptionalInt.of(0), result);
  }

  /** DOUBLE MAX_VALUE. */
  @Test
  public void testDoubleMaxValue() {
    var result = InPlaceComparator.compare(doubleField(Double.MAX_VALUE), Double.MAX_VALUE);
    assertEquals(OptionalInt.of(0), result);
  }

  /** DOUBLE MIN_VALUE (smallest positive). */
  @Test
  public void testDoubleMinValue() {
    var result = InPlaceComparator.compare(doubleField(Double.MIN_VALUE), Double.MIN_VALUE);
    assertEquals(OptionalInt.of(0), result);
  }

  // ===========================================================================
  // isEqual tests
  // ===========================================================================

  /** isEqual returns 1 (true) for equal values. */
  @Test
  public void testIsEqualTrue() {
    var result = InPlaceComparator.isEqual(intField(42), 42);
    assertEquals(OptionalInt.of(1), result);
  }

  /** isEqual returns 0 (false) for non-equal values. */
  @Test
  public void testIsEqualFalse() {
    var result = InPlaceComparator.isEqual(intField(42), 99);
    assertEquals(OptionalInt.of(0), result);
  }

  /** isEqual falls back for unsupported type conversion. */
  @Test
  public void testIsEqualFallback() {
    var result = InPlaceComparator.isEqual(intField(42), "42");
    assertTrue(result.isEmpty());
  }

  // ===========================================================================
  // Unsupported type fallback
  // ===========================================================================

  /** Unsupported PropertyTypeInternal returns empty (fallback). */
  @Test
  public void testUnsupportedTypeFallback() {
    var bytes = new BytesContainer();
    VarIntSerializer.write(bytes, 42);
    var field = new BinaryField("test", PropertyTypeInternal.EMBEDDED,
        new BytesContainer(bytes.bytes, 0), null);
    var result = InPlaceComparator.compare(field, 42);
    assertTrue(result.isEmpty());
  }

  // ===========================================================================
  // Cross-type ordering verification
  // ===========================================================================

  /**
   * Verify that INTEGER cross-type comparison with Long preserves correct ordering: serialized
   * value 10 compared with Long value 20 → negative.
   */
  @Test
  public void testIntegerVsLongOrdering() {
    var result = InPlaceComparator.compare(intField(10), 20L);
    assertTrue(result.isPresent());
    assertTrue(result.getAsInt() < 0);
  }

  /**
   * Verify that LONG cross-type comparison with Integer preserves correct ordering: serialized
   * value 20 compared with Integer value 10 → positive.
   */
  @Test
  public void testLongVsIntegerOrdering() {
    var result = InPlaceComparator.compare(longField(20L), 10);
    assertTrue(result.isPresent());
    assertTrue(result.getAsInt() > 0);
  }

  /**
   * FLOAT with Integer at negative precision boundary: -(2^24) should be exactly representable.
   */
  @Test
  public void testFloatWithNegativeIntegerAtPrecisionBoundary() {
    int boundary = -(1 << 24);
    var result = InPlaceComparator.compare(floatField((float) boundary), boundary);
    assertEquals(OptionalInt.of(0), result);
  }

  /**
   * FLOAT with Integer at negative precision boundary - 1: should fall back.
   */
  @Test
  public void testFloatWithNegativeIntegerBelowPrecisionBoundary() {
    int belowBoundary = -(1 << 24) - 1;
    var result = InPlaceComparator.compare(floatField(42.0f), belowBoundary);
    assertTrue(result.isEmpty());
  }

  /**
   * DOUBLE with Long at negative precision boundary: -(2^53) should be exactly representable.
   */
  @Test
  public void testDoubleWithNegativeLongAtPrecisionBoundary() {
    long boundary = -(1L << 53);
    var result = InPlaceComparator.compare(doubleField((double) boundary), boundary);
    assertEquals(OptionalInt.of(0), result);
  }

  /**
   * DOUBLE with Long at negative precision boundary - 1: should fall back.
   */
  @Test
  public void testDoubleWithNegativeLongBelowPrecisionBoundary() {
    long belowBoundary = -(1L << 53) - 1;
    var result = InPlaceComparator.compare(doubleField(42.0), belowBoundary);
    assertTrue(result.isEmpty());
  }
}
