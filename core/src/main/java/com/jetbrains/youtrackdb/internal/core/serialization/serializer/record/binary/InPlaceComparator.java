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

import com.jetbrains.youtrackdb.internal.common.serialization.types.DecimalSerializer;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.exception.CorruptedRecordException;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Date;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.TimeZone;
import javax.annotation.Nullable;

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

  private static final long MILLISEC_PER_DAY = HelperClasses.MILLISEC_PER_DAY;
  private static final TimeZone GMT = TimeZone.getTimeZone("GMT");

  /**
   * Compares a serialized field value against a Java object. For DATE fields, use the overload
   * that accepts a {@link TimeZone} parameter.
   *
   * @return comparison result (negative, zero, positive) or empty if fallback is needed
   */
  public static OptionalInt compare(BinaryField field, Object value) {
    return compare(field, value, null);
  }

  /**
   * Compares a serialized field value against a Java object.
   *
   * @param dbTimeZone the database timezone for DATE fields (required for DATE, ignored for others)
   * @return comparison result (negative, zero, positive) or empty if fallback is needed
   */
  public static OptionalInt compare(
      BinaryField field, Object value, @Nullable TimeZone dbTimeZone) {
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
      case STRING -> compareString(field.bytes, value);
      case BOOLEAN -> compareBoolean(field.bytes, value);
      case DATETIME -> compareDatetime(field.bytes, value);
      case DATE -> compareDate(field.bytes, value, dbTimeZone);
      case DECIMAL -> compareDecimal(field.bytes, value);
      case BINARY -> compareBinary(field.bytes, value);
      case LINK -> OptionalInt.empty(); // ordering not supported; use isEqual() for equality
      default -> OptionalInt.empty();
    };
  }

  /**
   * Checks equality of a serialized field value against a Java object.
   *
   * @return 1 (equal) or 0 (not equal) if comparison succeeded, or empty if fallback is needed
   */
  public static OptionalInt isEqual(BinaryField field, Object value) {
    return isEqual(field, value, null);
  }

  /**
   * Checks equality of a serialized field value against a Java object.
   *
   * @param dbTimeZone the database timezone for DATE fields
   * @return 1 (equal) or 0 (not equal) if comparison succeeded, or empty if fallback is needed
   */
  public static OptionalInt isEqual(
      BinaryField field, Object value, @Nullable TimeZone dbTimeZone) {
    // LINK: specialized equality (ordering is undefined but equality is well-defined)
    if (field.type == PropertyTypeInternal.LINK) {
      return compareLinkEquality(field.bytes, value);
    }

    var cmp = compare(field, value, dbTimeZone);
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

  // ---------------------------------------------------------------------------
  // STRING (VarInt length + UTF-8 bytes)
  // ---------------------------------------------------------------------------

  private static OptionalInt compareString(BytesContainer bytes, Object value) {
    if (!(value instanceof String strValue)) {
      return OptionalInt.empty();
    }
    var serialized = HelperClasses.readString(bytes);
    return OptionalInt.of(serialized.compareTo(strValue));
  }

  // ---------------------------------------------------------------------------
  // BOOLEAN (single byte: 0 or 1)
  // ---------------------------------------------------------------------------

  private static OptionalInt compareBoolean(BytesContainer bytes, Object value) {
    if (!(value instanceof Boolean boolValue)) {
      return OptionalInt.empty();
    }
    var serialized = HelperClasses.readByte(bytes) == 1;
    return OptionalInt.of(Boolean.compare(serialized, boolValue));
  }

  // ---------------------------------------------------------------------------
  // DATETIME (VarInt millis since epoch)
  // ---------------------------------------------------------------------------

  private static OptionalInt compareDatetime(BytesContainer bytes, Object value) {
    long valueMillis;
    if (value instanceof Date date) {
      valueMillis = date.getTime();
    } else if (value instanceof Number number) {
      valueMillis = number.longValue();
    } else {
      return OptionalInt.empty();
    }
    var serialized = VarIntSerializer.readAsLong(bytes);
    return OptionalInt.of(Long.compare(serialized, valueMillis));
  }

  // ---------------------------------------------------------------------------
  // DATE (VarInt days since epoch, requires timezone conversion)
  // ---------------------------------------------------------------------------

  private static OptionalInt compareDate(
      BytesContainer bytes, Object value, @Nullable TimeZone dbTimeZone) {
    if (dbTimeZone == null) {
      return OptionalInt.empty();
    }
    long valueMillis;
    if (value instanceof Date date) {
      valueMillis = date.getTime();
    } else if (value instanceof Number number) {
      valueMillis = number.longValue();
    } else {
      return OptionalInt.empty();
    }
    var savedTime = VarIntSerializer.readAsLong(bytes) * MILLISEC_PER_DAY;
    savedTime = HelperClasses.convertDayToTimezone(GMT, dbTimeZone, savedTime);
    return OptionalInt.of(Long.compare(savedTime, valueMillis));
  }

  // ---------------------------------------------------------------------------
  // DECIMAL (BigDecimal via DecimalSerializer)
  // ---------------------------------------------------------------------------

  private static OptionalInt compareDecimal(BytesContainer bytes, Object value) {
    BigDecimal decimalValue;
    if (value instanceof BigDecimal bd) {
      decimalValue = bd;
    } else if (value instanceof Number number) {
      if (number instanceof Double || number instanceof Float) {
        double dv = number.doubleValue();
        if (Double.isNaN(dv) || Double.isInfinite(dv)) {
          return OptionalInt.empty();
        }
        decimalValue = BigDecimal.valueOf(dv);
      } else {
        decimalValue = BigDecimal.valueOf(number.longValue());
      }
    } else {
      return OptionalInt.empty();
    }
    var serialized = DecimalSerializer.staticDeserialize(bytes.bytes, bytes.offset);
    bytes.skip(DecimalSerializer.staticGetObjectSize(bytes.bytes, bytes.offset));
    return OptionalInt.of(serialized.compareTo(decimalValue));
  }

  // ---------------------------------------------------------------------------
  // BINARY (VarInt length + raw bytes)
  // ---------------------------------------------------------------------------

  private static OptionalInt compareBinary(BytesContainer bytes, Object value) {
    if (!(value instanceof byte[] byteArray)) {
      return OptionalInt.empty();
    }
    var serialized = HelperClasses.readBinary(bytes);
    return OptionalInt.of(Arrays.compare(serialized, byteArray));
  }

  // ---------------------------------------------------------------------------
  // LINK (VarInt clusterId + VarInt clusterPosition) — equality only
  // ---------------------------------------------------------------------------

  private static OptionalInt compareLinkEquality(BytesContainer bytes, Object value) {
    int valueCollectionId;
    long valueCollectionPos;
    if (value instanceof RID rid) {
      valueCollectionId = rid.getCollectionId();
      valueCollectionPos = rid.getCollectionPosition();
    } else if (value instanceof Identifiable identifiable) {
      var identity = identifiable.getIdentity();
      valueCollectionId = identity.getCollectionId();
      valueCollectionPos = identity.getCollectionPosition();
    } else {
      return OptionalInt.empty();
    }
    var serializedId = VarIntSerializer.readAsInteger(bytes);
    var serializedPos = VarIntSerializer.readAsLong(bytes);
    var equal = serializedId == valueCollectionId && serializedPos == valueCollectionPos;
    return OptionalInt.of(equal ? 1 : 0);
  }

  // ===========================================================================
  // ReadBinaryField overloads (ByteBuffer-backed, for PageFrame zero-copy)
  // ===========================================================================

  /**
   * Compares a serialized field value in a {@link ReadBinaryField} against a Java object.
   *
   * @param dbTimeZone the database timezone for DATE fields
   * @return comparison result (negative, zero, positive) or empty if fallback is needed
   */
  public static OptionalInt compare(
      ReadBinaryField field, Object value, @Nullable TimeZone dbTimeZone) {
    assert field != null : "ReadBinaryField must not be null";
    assert field.type() != null : "ReadBinaryField.type must not be null";
    assert value != null : "Comparison value must not be null";

    return switch (field.type()) {
      case INTEGER -> compareIntegerRbc(field.bytes(), value);
      case LONG -> compareLongRbc(field.bytes(), value);
      case SHORT -> compareShortRbc(field.bytes(), value);
      case BYTE -> compareByteRbc(field.bytes(), value);
      case FLOAT -> compareFloatRbc(field.bytes(), value);
      case DOUBLE -> compareDoubleRbc(field.bytes(), value);
      case STRING -> compareStringRbc(field.bytes(), value);
      case BOOLEAN -> compareBooleanRbc(field.bytes(), value);
      case DATETIME -> compareDatetimeRbc(field.bytes(), value);
      case DATE -> compareDateRbc(field.bytes(), value, dbTimeZone);
      case DECIMAL -> compareDecimalRbc(field.bytes(), value);
      case BINARY -> compareBinaryRbc(field.bytes(), value);
      case LINK -> OptionalInt.empty();
      default -> OptionalInt.empty();
    };
  }

  /**
   * Checks equality of a serialized field value in a {@link ReadBinaryField} against a Java object.
   *
   * @param dbTimeZone the database timezone for DATE fields
   * @return 1 (equal) or 0 (not equal) if comparison succeeded, or empty if fallback is needed
   */
  public static OptionalInt isEqual(
      ReadBinaryField field, Object value, @Nullable TimeZone dbTimeZone) {
    if (field.type() == PropertyTypeInternal.LINK) {
      return compareLinkEqualityRbc(field.bytes(), value);
    }

    var cmp = compare(field, value, dbTimeZone);
    if (cmp.isEmpty()) {
      return OptionalInt.empty();
    }
    return OptionalInt.of(cmp.getAsInt() == 0 ? 1 : 0);
  }

  // --- ReadBytesContainer per-type comparison methods ---

  private static OptionalInt compareIntegerRbc(ReadBytesContainer bytes, Object value) {
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

  private static OptionalInt compareLongRbc(ReadBytesContainer bytes, Object value) {
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

  private static OptionalInt compareShortRbc(ReadBytesContainer bytes, Object value) {
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

  private static OptionalInt compareByteRbc(ReadBytesContainer bytes, Object value) {
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

  private static OptionalInt compareFloatRbc(ReadBytesContainer bytes, Object value) {
    if (!(value instanceof Number number)) {
      return OptionalInt.empty();
    }
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

  private static OptionalInt compareDoubleRbc(ReadBytesContainer bytes, Object value) {
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

  private static OptionalInt compareStringRbc(ReadBytesContainer bytes, Object value) {
    if (!(value instanceof String strValue)) {
      return OptionalInt.empty();
    }
    var serialized = HelperClasses.readString(bytes);
    return OptionalInt.of(serialized.compareTo(strValue));
  }

  private static OptionalInt compareBooleanRbc(ReadBytesContainer bytes, Object value) {
    if (!(value instanceof Boolean boolValue)) {
      return OptionalInt.empty();
    }
    var serialized = HelperClasses.readByte(bytes) == 1;
    return OptionalInt.of(Boolean.compare(serialized, boolValue));
  }

  private static OptionalInt compareDatetimeRbc(ReadBytesContainer bytes, Object value) {
    long valueMillis;
    if (value instanceof Date date) {
      valueMillis = date.getTime();
    } else if (value instanceof Number number) {
      valueMillis = number.longValue();
    } else {
      return OptionalInt.empty();
    }
    var serialized = VarIntSerializer.readAsLong(bytes);
    return OptionalInt.of(Long.compare(serialized, valueMillis));
  }

  private static OptionalInt compareDateRbc(
      ReadBytesContainer bytes, Object value, @Nullable TimeZone dbTimeZone) {
    if (dbTimeZone == null) {
      return OptionalInt.empty();
    }
    long valueMillis;
    if (value instanceof Date date) {
      valueMillis = date.getTime();
    } else if (value instanceof Number number) {
      valueMillis = number.longValue();
    } else {
      return OptionalInt.empty();
    }
    var savedTime = VarIntSerializer.readAsLong(bytes) * MILLISEC_PER_DAY;
    savedTime = HelperClasses.convertDayToTimezone(GMT, dbTimeZone, savedTime);
    return OptionalInt.of(Long.compare(savedTime, valueMillis));
  }

  // DECIMAL: 4-byte scale (big-endian int) + 4-byte unscaled length + unscaled bytes
  private static OptionalInt compareDecimalRbc(ReadBytesContainer bytes, Object value) {
    BigDecimal decimalValue;
    if (value instanceof BigDecimal bd) {
      decimalValue = bd;
    } else if (value instanceof Number number) {
      if (number instanceof Double || number instanceof Float) {
        double dv = number.doubleValue();
        if (Double.isNaN(dv) || Double.isInfinite(dv)) {
          return OptionalInt.empty();
        }
        decimalValue = BigDecimal.valueOf(dv);
      } else {
        decimalValue = BigDecimal.valueOf(number.longValue());
      }
    } else {
      return OptionalInt.empty();
    }
    var scale = bytes.getInt();
    var unscaledLen = bytes.getInt();
    if (unscaledLen < 0 || unscaledLen > bytes.remaining()) {
      throw new CorruptedRecordException(
          "Decimal unscaled length exceeds remaining buffer: "
              + unscaledLen + " > " + bytes.remaining());
    }
    var unscaledBytes = new byte[unscaledLen];
    bytes.getBytes(unscaledBytes, 0, unscaledLen);
    var serialized = new BigDecimal(new BigInteger(unscaledBytes), scale);
    return OptionalInt.of(serialized.compareTo(decimalValue));
  }

  private static OptionalInt compareBinaryRbc(ReadBytesContainer bytes, Object value) {
    if (!(value instanceof byte[] byteArray)) {
      return OptionalInt.empty();
    }
    var serialized = HelperClasses.readBinary(bytes);
    return OptionalInt.of(Arrays.compare(serialized, byteArray));
  }

  private static OptionalInt compareLinkEqualityRbc(ReadBytesContainer bytes, Object value) {
    int valueCollectionId;
    long valueCollectionPos;
    if (value instanceof RID rid) {
      valueCollectionId = rid.getCollectionId();
      valueCollectionPos = rid.getCollectionPosition();
    } else if (value instanceof Identifiable identifiable) {
      var identity = identifiable.getIdentity();
      valueCollectionId = identity.getCollectionId();
      valueCollectionPos = identity.getCollectionPosition();
    } else {
      return OptionalInt.empty();
    }
    var serializedId = VarIntSerializer.readAsInteger(bytes);
    var serializedPos = VarIntSerializer.readAsLong(bytes);
    var equal = serializedId == valueCollectionId && serializedPos == valueCollectionPos;
    return OptionalInt.of(equal ? 1 : 0);
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
  private static OptionalLong convertToLong(Number number) {
    if (number instanceof Long) {
      return OptionalLong.of(number.longValue());
    }
    if (number instanceof Integer || number instanceof Short || number instanceof Byte) {
      return OptionalLong.of(number.longValue());
    }
    // Float, Double -> long: always fall back
    return OptionalLong.empty();
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
    // Only standard integer boxed types — reject BigDecimal, BigInteger, etc.
    if (!(number instanceof Integer || number instanceof Long
        || number instanceof Short || number instanceof Byte)) {
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
  private static OptionalLong convertToDouble(Number number) {
    if (number instanceof Double d) {
      return OptionalLong.of(Double.doubleToLongBits(d));
    }
    if (number instanceof Float f) {
      return OptionalLong.of(Double.doubleToLongBits(f.doubleValue()));
    }
    // Only standard integer boxed types — reject BigDecimal, BigInteger, etc.
    if (!(number instanceof Integer || number instanceof Long
        || number instanceof Short || number instanceof Byte)) {
      return OptionalLong.empty();
    }
    // Integer types -> double: safe if |value| <= 2^53
    long longValue = number.longValue();
    if (longValue > DOUBLE_EXACT_LONG_MAX || longValue < -DOUBLE_EXACT_LONG_MAX) {
      return OptionalLong.empty();
    }
    return OptionalLong.of(Double.doubleToLongBits(number.doubleValue()));
  }
}
