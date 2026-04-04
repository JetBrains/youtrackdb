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
 * Tests for {@link InPlaceComparator} ReadBinaryField numeric type comparison (INTEGER, LONG,
 * SHORT, BYTE, FLOAT, DOUBLE). Mirrors {@link InPlaceComparatorNumericTest} but uses
 * ReadBinaryField / ReadBytesContainer (ByteBuffer-backed) instead of BinaryField / BytesContainer.
 */
public class InPlaceComparatorRbcNumericTest {

  // ---- Helper methods to create ReadBinaryFields with serialized values ----

  private static ReadBinaryField intFieldRbc(int value) {
    var bytes = new BytesContainer();
    VarIntSerializer.write(bytes, value);
    return new ReadBinaryField(
        "test", PropertyTypeInternal.INTEGER, new ReadBytesContainer(bytes.bytes, 0), null);
  }

  private static ReadBinaryField longFieldRbc(long value) {
    var bytes = new BytesContainer();
    VarIntSerializer.write(bytes, value);
    return new ReadBinaryField(
        "test", PropertyTypeInternal.LONG, new ReadBytesContainer(bytes.bytes, 0), null);
  }

  private static ReadBinaryField shortFieldRbc(short value) {
    var bytes = new BytesContainer();
    VarIntSerializer.write(bytes, value);
    return new ReadBinaryField(
        "test", PropertyTypeInternal.SHORT, new ReadBytesContainer(bytes.bytes, 0), null);
  }

  private static ReadBinaryField byteFieldRbc(byte value) {
    var bytes = new BytesContainer();
    var pos = bytes.alloc(1);
    bytes.bytes[pos] = value;
    return new ReadBinaryField(
        "test", PropertyTypeInternal.BYTE, new ReadBytesContainer(bytes.bytes, 0), null);
  }

  private static ReadBinaryField floatFieldRbc(float value) {
    var bytes = new BytesContainer();
    var pos = bytes.alloc(IntegerSerializer.INT_SIZE);
    IntegerSerializer.serializeLiteral(Float.floatToIntBits(value), bytes.bytes, pos);
    return new ReadBinaryField(
        "test", PropertyTypeInternal.FLOAT, new ReadBytesContainer(bytes.bytes, 0), null);
  }

  private static ReadBinaryField doubleFieldRbc(double value) {
    var bytes = new BytesContainer();
    var pos = bytes.alloc(LongSerializer.LONG_SIZE);
    LongSerializer.serializeLiteral(Double.doubleToLongBits(value), bytes.bytes, pos);
    return new ReadBinaryField(
        "test", PropertyTypeInternal.DOUBLE, new ReadBytesContainer(bytes.bytes, 0), null);
  }

  // ===========================================================================
  // INTEGER tests
  // ===========================================================================

  /** INTEGER equal — same type. */
  @Test
  public void testIntegerEqualSameType() {
    var result = InPlaceComparator.compare(intFieldRbc(42), 42, null);
    assertEquals(OptionalInt.of(0), result);
  }

  /** INTEGER less than. */
  @Test
  public void testIntegerLessThan() {
    var result = InPlaceComparator.compare(intFieldRbc(10), 20, null);
    assertTrue(result.isPresent());
    assertTrue(result.getAsInt() < 0);
  }

  /** INTEGER greater than. */
  @Test
  public void testIntegerGreaterThan() {
    var result = InPlaceComparator.compare(intFieldRbc(20), 10, null);
    assertTrue(result.isPresent());
    assertTrue(result.getAsInt() > 0);
  }

  /** INTEGER with Long in range — should succeed. */
  @Test
  public void testIntegerWithLongInRange() {
    var result = InPlaceComparator.compare(intFieldRbc(100), 100L, null);
    assertEquals(OptionalInt.of(0), result);
  }

  /** INTEGER with Long out of range — should fall back. */
  @Test
  public void testIntegerWithLongOutOfRange() {
    var result =
        InPlaceComparator.compare(intFieldRbc(100), (long) Integer.MAX_VALUE + 1, null);
    assertTrue(result.isEmpty());
  }

  /** INTEGER with Float — should fall back. */
  @Test
  public void testIntegerWithFloatFallback() {
    var result = InPlaceComparator.compare(intFieldRbc(42), 42.0f, null);
    assertTrue(result.isEmpty());
  }

  /** INTEGER with non-Number — should fall back. */
  @Test
  public void testIntegerWithNonNumber() {
    var result = InPlaceComparator.compare(intFieldRbc(42), "42", null);
    assertTrue(result.isEmpty());
  }

  /** INTEGER boundary: MAX_VALUE. */
  @Test
  public void testIntegerMaxValue() {
    var result =
        InPlaceComparator.compare(intFieldRbc(Integer.MAX_VALUE), Integer.MAX_VALUE, null);
    assertEquals(OptionalInt.of(0), result);
  }

  /** INTEGER boundary: MIN_VALUE. */
  @Test
  public void testIntegerMinValue() {
    var result =
        InPlaceComparator.compare(intFieldRbc(Integer.MIN_VALUE), Integer.MIN_VALUE, null);
    assertEquals(OptionalInt.of(0), result);
  }

  /** INTEGER zero. */
  @Test
  public void testIntegerZero() {
    var result = InPlaceComparator.compare(intFieldRbc(0), 0, null);
    assertEquals(OptionalInt.of(0), result);
  }

  // ===========================================================================
  // LONG tests
  // ===========================================================================

  /** LONG equal — same type. */
  @Test
  public void testLongEqualSameType() {
    var result = InPlaceComparator.compare(longFieldRbc(123456789L), 123456789L, null);
    assertEquals(OptionalInt.of(0), result);
  }

  /** LONG less than. */
  @Test
  public void testLongLessThan() {
    var result = InPlaceComparator.compare(longFieldRbc(10L), 20L, null);
    assertTrue(result.isPresent());
    assertTrue(result.getAsInt() < 0);
  }

  /** LONG with Integer — should widen. */
  @Test
  public void testLongWithInteger() {
    var result = InPlaceComparator.compare(longFieldRbc(42L), 42, null);
    assertEquals(OptionalInt.of(0), result);
  }

  /** LONG boundary: MAX_VALUE. */
  @Test
  public void testLongMaxValue() {
    var result =
        InPlaceComparator.compare(longFieldRbc(Long.MAX_VALUE), Long.MAX_VALUE, null);
    assertEquals(OptionalInt.of(0), result);
  }

  /** LONG boundary: MIN_VALUE. */
  @Test
  public void testLongMinValue() {
    var result =
        InPlaceComparator.compare(longFieldRbc(Long.MIN_VALUE), Long.MIN_VALUE, null);
    assertEquals(OptionalInt.of(0), result);
  }

  // ===========================================================================
  // SHORT tests
  // ===========================================================================

  /** SHORT equal — same type. */
  @Test
  public void testShortEqualSameType() {
    var result = InPlaceComparator.compare(shortFieldRbc((short) 42), (short) 42, null);
    assertEquals(OptionalInt.of(0), result);
  }

  /** SHORT with Integer in range — should narrow. */
  @Test
  public void testShortWithIntegerInRange() {
    var result = InPlaceComparator.compare(shortFieldRbc((short) 100), 100, null);
    assertEquals(OptionalInt.of(0), result);
  }

  /** SHORT with Integer out of range — should fall back. */
  @Test
  public void testShortWithIntegerOutOfRange() {
    var result = InPlaceComparator.compare(shortFieldRbc((short) 100), 100_000, null);
    assertTrue(result.isEmpty());
  }

  // ===========================================================================
  // BYTE tests
  // ===========================================================================

  /** BYTE equal — same type. */
  @Test
  public void testByteEqualSameType() {
    var result = InPlaceComparator.compare(byteFieldRbc((byte) 7), (byte) 7, null);
    assertEquals(OptionalInt.of(0), result);
  }

  /** BYTE less than. */
  @Test
  public void testByteLessThan() {
    var result = InPlaceComparator.compare(byteFieldRbc((byte) 1), (byte) 5, null);
    assertTrue(result.isPresent());
    assertTrue(result.getAsInt() < 0);
  }

  // ===========================================================================
  // FLOAT tests
  // ===========================================================================

  /** FLOAT equal — same type. */
  @Test
  public void testFloatEqualSameType() {
    var result = InPlaceComparator.compare(floatFieldRbc(3.14f), 3.14f, null);
    assertEquals(OptionalInt.of(0), result);
  }

  /** FLOAT less than. */
  @Test
  public void testFloatLessThan() {
    var result = InPlaceComparator.compare(floatFieldRbc(1.0f), 2.0f, null);
    assertTrue(result.isPresent());
    assertTrue(result.getAsInt() < 0);
  }

  /** FLOAT with Double — widens to double comparison. */
  @Test
  public void testFloatWithDouble() {
    var result = InPlaceComparator.compare(floatFieldRbc(3.14f), (double) 3.14f, null);
    assertEquals(OptionalInt.of(0), result);
  }

  /** FLOAT with non-Number — should fall back. */
  @Test
  public void testFloatWithNonNumber() {
    var result = InPlaceComparator.compare(floatFieldRbc(1.0f), "1.0", null);
    assertTrue(result.isEmpty());
  }

  // ===========================================================================
  // DOUBLE tests
  // ===========================================================================

  /** DOUBLE equal — same type. */
  @Test
  public void testDoubleEqualSameType() {
    var result = InPlaceComparator.compare(doubleFieldRbc(2.718281828), 2.718281828, null);
    assertEquals(OptionalInt.of(0), result);
  }

  /** DOUBLE less than. */
  @Test
  public void testDoubleLessThan() {
    var result = InPlaceComparator.compare(doubleFieldRbc(1.0), 2.0, null);
    assertTrue(result.isPresent());
    assertTrue(result.getAsInt() < 0);
  }

  /** DOUBLE with Float — should widen float to double. */
  @Test
  public void testDoubleWithFloat() {
    var result = InPlaceComparator.compare(doubleFieldRbc(42.0), 42.0f, null);
    assertEquals(OptionalInt.of(0), result);
  }

  /** DOUBLE with Integer — widens to double (42 is within 2^53 range). */
  @Test
  public void testDoubleWithInteger() {
    var result = InPlaceComparator.compare(doubleFieldRbc(42.0), 42, null);
    assertEquals(OptionalInt.of(0), result);
  }

  // ===========================================================================
  // isEqual tests (derived from compare)
  // ===========================================================================

  /** isEqual for INTEGER — equal. */
  @Test
  public void testIsEqualIntegerTrue() {
    var result = InPlaceComparator.isEqual(intFieldRbc(42), 42, null);
    assertEquals(OptionalInt.of(1), result);
  }

  /** isEqual for INTEGER — not equal. */
  @Test
  public void testIsEqualIntegerFalse() {
    var result = InPlaceComparator.isEqual(intFieldRbc(42), 99, null);
    assertEquals(OptionalInt.of(0), result);
  }

  /** isEqual for DOUBLE — equal. */
  @Test
  public void testIsEqualDoubleTrue() {
    var result = InPlaceComparator.isEqual(doubleFieldRbc(3.14), 3.14, null);
    assertEquals(OptionalInt.of(1), result);
  }
}
