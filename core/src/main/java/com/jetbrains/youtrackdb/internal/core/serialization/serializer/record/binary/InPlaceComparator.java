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

import static com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary.HelperClasses.readInteger;
import static com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary.HelperClasses.readLong;

import java.util.OptionalInt;

/**
 * Compares a serialized property value (in a {@link BinaryField}) against a Java object without
 * deserializing the property. Returns comparison results as {@link OptionalInt} (empty = fallback
 * to deserialization needed) or {@code boolean} for equality checks.
 *
 * <p>Type conversion: the passed-in Java value is converted to the property's serialized type
 * before same-type comparison. Narrowing conversions that would lose precision return empty
 * (fallback). Float-to-integer and double-to-integer conversions always fall back.
 */
public final class InPlaceComparator {

  // Precision boundaries for safe integer-to-floating-point conversion.
  // Beyond these thresholds, the float/double cannot represent the integer exactly.
  private static final int FLOAT_EXACT_INT_MAX = 1 << 24; // 2^24 = 16_777_216
  private static final long DOUBLE_EXACT_LONG_MAX = 1L << 53; // 2^53

  private InPlaceComparator() {
  }

  /**
   * Compares a serialized field value against a Java object.
   *
   * @return comparison result (negative, zero, positive) or empty if fallback is needed
   */
  public static OptionalInt compare(BinaryField field, Object value) {
    assert field != null : "BinaryField must not be null";
    assert field.type != null : "BinaryField.type must not be null";
    assert value != null : "Comparison value must not be null";

    return switch (field.type) {
      case INTEGER -> compareInteger(field.bytes, value);
      case LONG -> compareLong(field.bytes, value);
      case SHORT -> compareShort(field.bytes, value);
      case BYTE -> compareByte(field.bytes, value);
      case FLOAT -> compareFloat(field.bytes, value);
      case DOUBLE -> compareDouble(field.bytes, value);
      default -> OptionalInt.empty();
    };
  }

  /**
   * Checks equality of a serialized field value against a Java object. Delegates to {@link
   * #compare} for numeric types — specialized equality checks for non-numeric types will be added
   * in a follow-up step.
   *
   * @return true/false if comparison succeeded, or empty if fallback is needed
   */
  public static OptionalInt isEqual(BinaryField field, Object value) {
    // For numeric types, equality is derived from compare() == 0.
    // Non-numeric types (STRING, LINK, etc.) can override with specialized equality
    // in a follow-up step.
    var cmp = compare(field, value);
    if (cmp.isEmpty()) {
      return OptionalInt.empty();
    }
    return OptionalInt.of(cmp.getAsInt() == 0 ? 1 : 0);
  }

  // ---------------------------------------------------------------------------
  // INTEGER (VarInt encoded)
  // ---------------------------------------------------------------------------

  private static OptionalInt compareInteger(BytesContainer bytes, Object value) {
    if (!(value instanceof Number number)) {
      return OptionalInt.empty();
    }

    var converted = convertToInt(number);
    if (converted.isEmpty()) {
      return OptionalInt.empty();
    }

    var serialized = VarIntSerializer.readAsInteger(bytes);
    return OptionalInt.of(Integer.compare(serialized, converted.getAsInt()));
  }

  // ---------------------------------------------------------------------------
  // LONG (VarInt encoded)
  // ---------------------------------------------------------------------------

  private static OptionalInt compareLong(BytesContainer bytes, Object value) {
    if (!(value instanceof Number number)) {
      return OptionalInt.empty();
    }

    var converted = convertToLong(number);
    if (converted.isEmpty()) {
      return OptionalInt.empty();
    }

    var serialized = VarIntSerializer.readAsLong(bytes);
    return OptionalInt.of(Long.compare(serialized, converted.getAsLong()));
  }

  // ---------------------------------------------------------------------------
  // SHORT (VarInt encoded)
  // ---------------------------------------------------------------------------

  private static OptionalInt compareShort(BytesContainer bytes, Object value) {
    if (!(value instanceof Number number)) {
      return OptionalInt.empty();
    }

    var converted = convertToShort(number);
    if (converted.isEmpty()) {
      return OptionalInt.empty();
    }

    var serialized = VarIntSerializer.readAsShort(bytes);
    return OptionalInt.of(Short.compare(serialized, (short) converted.getAsInt()));
  }

  // ---------------------------------------------------------------------------
  // BYTE (single raw byte)
  // ---------------------------------------------------------------------------

  private static OptionalInt compareByte(BytesContainer bytes, Object value) {
    if (!(value instanceof Number number)) {
      return OptionalInt.empty();
    }

    var converted = convertToByte(number);
    if (converted.isEmpty()) {
      return OptionalInt.empty();
    }

    var serialized = HelperClasses.readByte(bytes);
    return OptionalInt.of(Byte.compare(serialized, (byte) converted.getAsInt()));
  }

  // ---------------------------------------------------------------------------
  // FLOAT (4 bytes, int bits)
  // ---------------------------------------------------------------------------

  private static OptionalInt compareFloat(BytesContainer bytes, Object value) {
    if (!(value instanceof Number number)) {
      return OptionalInt.empty();
    }

    // If either operand is double-precision, widen both to double for comparison
    if (value instanceof Double) {
      var serializedFloat = Float.intBitsToFloat(readInteger(bytes));
      return OptionalInt.of(Double.compare(serializedFloat, number.doubleValue()));
    }

    var converted = convertToFloat(number);
    if (converted.isEmpty()) {
      return OptionalInt.empty();
    }

    var serialized = Float.intBitsToFloat(readInteger(bytes));
    return OptionalInt.of(
        Float.compare(serialized, Float.intBitsToFloat(converted.getAsInt())));
  }

  // ---------------------------------------------------------------------------
  // DOUBLE (8 bytes, long bits)
  // ---------------------------------------------------------------------------

  private static OptionalInt compareDouble(BytesContainer bytes, Object value) {
    if (!(value instanceof Number number)) {
      return OptionalInt.empty();
    }

    var converted = convertToDouble(number);
    if (converted.isEmpty()) {
      return OptionalInt.empty();
    }

    var serialized = Double.longBitsToDouble(readLong(bytes));
    return OptionalInt.of(
        Double.compare(serialized, Double.longBitsToDouble(converted.getAsLong())));
  }

  // ===========================================================================
  // Type conversion helpers
  // ===========================================================================

  /**
   * Converts a Number to int. Falls back (returns empty) for float/double types and for
   * long/short/byte values outside int range.
   */
  private static OptionalInt convertToInt(Number number) {
    if (number instanceof Integer) {
      return OptionalInt.of(number.intValue());
    }
    if (number instanceof Long l) {
      if (l < Integer.MIN_VALUE || l > Integer.MAX_VALUE) {
        return OptionalInt.empty();
      }
      return OptionalInt.of(l.intValue());
    }
    if (number instanceof Short || number instanceof Byte) {
      return OptionalInt.of(number.intValue());
    }
    // Float, Double -> integer: always fall back
    return OptionalInt.empty();
  }

  /**
   * Converts a Number to long. Falls back for float/double types.
   */
  private static java.util.OptionalLong convertToLong(Number number) {
    if (number instanceof Long) {
      return java.util.OptionalLong.of(number.longValue());
    }
    if (number instanceof Integer || number instanceof Short || number instanceof Byte) {
      return java.util.OptionalLong.of(number.longValue());
    }
    // Float, Double -> long: always fall back
    return java.util.OptionalLong.empty();
  }

  /**
   * Converts a Number to short. Falls back for float/double and out-of-range values.
   */
  private static OptionalInt convertToShort(Number number) {
    if (number instanceof Short) {
      return OptionalInt.of(number.shortValue());
    }
    if (number instanceof Byte) {
      return OptionalInt.of(number.shortValue());
    }
    if (number instanceof Integer i) {
      if (i < Short.MIN_VALUE || i > Short.MAX_VALUE) {
        return OptionalInt.empty();
      }
      return OptionalInt.of(i.shortValue());
    }
    if (number instanceof Long l) {
      if (l < Short.MIN_VALUE || l > Short.MAX_VALUE) {
        return OptionalInt.empty();
      }
      return OptionalInt.of((int) l.shortValue());
    }
    // Float, Double -> short: always fall back
    return OptionalInt.empty();
  }

  /**
   * Converts a Number to byte. Falls back for float/double and out-of-range values.
   */
  private static OptionalInt convertToByte(Number number) {
    if (number instanceof Byte) {
      return OptionalInt.of(number.byteValue());
    }
    if (number instanceof Short s) {
      if (s < Byte.MIN_VALUE || s > Byte.MAX_VALUE) {
        return OptionalInt.empty();
      }
      return OptionalInt.of(s.byteValue());
    }
    if (number instanceof Integer i) {
      if (i < Byte.MIN_VALUE || i > Byte.MAX_VALUE) {
        return OptionalInt.empty();
      }
      return OptionalInt.of(i.byteValue());
    }
    if (number instanceof Long l) {
      if (l < Byte.MIN_VALUE || l > Byte.MAX_VALUE) {
        return OptionalInt.empty();
      }
      return OptionalInt.of((int) l.byteValue());
    }
    // Float, Double -> byte: always fall back
    return OptionalInt.empty();
  }

  /**
   * Converts a Number to float bits (as int). Falls back for integers outside the exact
   * representable range (|value| > 2^24), and for doubles that would lose precision.
   */
  private static OptionalInt convertToFloat(Number number) {
    if (number instanceof Float f) {
      return OptionalInt.of(Float.floatToIntBits(f));
    }
    // Double -> float: always fall back (potential precision loss)
    if (number instanceof Double) {
      return OptionalInt.empty();
    }
    // Integer types -> float: safe only if |value| <= 2^24
    long longValue = number.longValue();
    if (longValue > FLOAT_EXACT_INT_MAX || longValue < -FLOAT_EXACT_INT_MAX) {
      return OptionalInt.empty();
    }
    return OptionalInt.of(Float.floatToIntBits(number.floatValue()));
  }

  /**
   * Converts a Number to double bits (as long). Falls back for long values outside the exact
   * representable range (|value| > 2^53).
   */
  private static java.util.OptionalLong convertToDouble(Number number) {
    if (number instanceof Double d) {
      return java.util.OptionalLong.of(Double.doubleToLongBits(d));
    }
    if (number instanceof Float f) {
      return java.util.OptionalLong.of(Double.doubleToLongBits(f.doubleValue()));
    }
    // Integer types -> double: safe if |value| <= 2^53
    long longValue = number.longValue();
    if (longValue > DOUBLE_EXACT_LONG_MAX || longValue < -DOUBLE_EXACT_LONG_MAX) {
      return java.util.OptionalLong.empty();
    }
    return java.util.OptionalLong.of(Double.doubleToLongBits(number.doubleValue()));
  }
}
