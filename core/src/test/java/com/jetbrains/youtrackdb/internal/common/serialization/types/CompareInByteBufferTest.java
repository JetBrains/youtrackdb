/*
 *
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.jetbrains.youtrackdb.internal.common.serialization.types;

import com.jetbrains.youtrackdb.internal.core.index.CompositeKey;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.BinarySerializerFactory;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.impl.index.CompositeKeySerializer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Tests for the compareInByteBuffer() method across all serializers that provide optimized
 * in-buffer comparison. Verifies that the in-buffer comparison produces the same ordering as
 * deserializing both values and comparing them with their natural compareTo() semantics.
 */
public class CompareInByteBufferTest {

  private static BinarySerializerFactory serializerFactory;

  @BeforeClass
  public static void beforeClass() {
    serializerFactory = BinarySerializerFactory.create(
        BinarySerializerFactory.currentBinaryFormatVersion());
  }

  // --- Helper: serialize pageValue into a direct ByteBuffer and searchValue into byte[],
  //     then call compareInByteBuffer and verify its sign matches expected comparison. ---

  private <T> void assertCompare(
      BinarySerializer<T> serializer, T pageValue, T searchValue,
      int expectedSign, Object... hints) {
    // Serialize the "page" value into a direct ByteBuffer (mimics an on-page key)
    final var pageBytes = serializer.serializeNativeAsWhole(
        serializerFactory, pageValue, hints);
    final var buffer = ByteBuffer.allocateDirect(pageBytes.length)
        .order(ByteOrder.nativeOrder());
    buffer.put(pageBytes);

    // Serialize the "search" value into a byte[] (the pre-serialized search key)
    final var searchBytes = serializer.serializeNativeAsWhole(
        serializerFactory, searchValue, hints);

    // Perform the in-buffer comparison
    final var result = serializer.compareInByteBuffer(
        serializerFactory, 0, buffer, searchBytes, 0);

    Assert.assertEquals(
        "compareInByteBuffer(" + pageValue + ", " + searchValue + "): "
            + "expected sign " + expectedSign + " but got " + result,
        expectedSign, Integer.signum(result));
  }

  // =====================================================================
  // IntegerSerializer tests
  // =====================================================================

  @Test
  public void testIntegerEqual() {
    assertCompare(IntegerSerializer.INSTANCE, 42, 42, 0);
  }

  @Test
  public void testIntegerLessThan() {
    assertCompare(IntegerSerializer.INSTANCE, 10, 20, -1);
  }

  @Test
  public void testIntegerGreaterThan() {
    assertCompare(IntegerSerializer.INSTANCE, 100, 50, 1);
  }

  @Test
  public void testIntegerNegative() {
    assertCompare(IntegerSerializer.INSTANCE, -5, 5, -1);
  }

  @Test
  public void testIntegerBothNegative() {
    assertCompare(IntegerSerializer.INSTANCE, -10, -3, -1);
  }

  @Test
  public void testIntegerZero() {
    assertCompare(IntegerSerializer.INSTANCE, 0, 0, 0);
  }

  @Test
  public void testIntegerMinMax() {
    assertCompare(IntegerSerializer.INSTANCE, Integer.MIN_VALUE, Integer.MAX_VALUE, -1);
  }

  // =====================================================================
  // LongSerializer tests
  // =====================================================================

  @Test
  public void testLongEqual() {
    assertCompare(LongSerializer.INSTANCE, 42L, 42L, 0);
  }

  @Test
  public void testLongLessThan() {
    assertCompare(LongSerializer.INSTANCE, 10L, 20L, -1);
  }

  @Test
  public void testLongGreaterThan() {
    assertCompare(LongSerializer.INSTANCE, 100L, 50L, 1);
  }

  @Test
  public void testLongNegative() {
    assertCompare(LongSerializer.INSTANCE, -5L, 5L, -1);
  }

  @Test
  public void testLongMinMax() {
    assertCompare(LongSerializer.INSTANCE, Long.MIN_VALUE, Long.MAX_VALUE, -1);
  }

  // =====================================================================
  // ShortSerializer tests
  // =====================================================================

  @Test
  public void testShortEqual() {
    assertCompare(ShortSerializer.INSTANCE, (short) 42, (short) 42, 0);
  }

  @Test
  public void testShortLessThan() {
    assertCompare(ShortSerializer.INSTANCE, (short) 10, (short) 20, -1);
  }

  @Test
  public void testShortGreaterThan() {
    assertCompare(ShortSerializer.INSTANCE, (short) 100, (short) 50, 1);
  }

  @Test
  public void testShortNegative() {
    assertCompare(ShortSerializer.INSTANCE, (short) -5, (short) 5, -1);
  }

  // =====================================================================
  // StringSerializer tests
  // =====================================================================

  @Test
  public void testStringEqual() {
    assertCompare(StringSerializer.INSTANCE, "hello", "hello", 0);
  }

  @Test
  public void testStringLessThan() {
    assertCompare(StringSerializer.INSTANCE, "abc", "abd", -1);
  }

  @Test
  public void testStringGreaterThan() {
    assertCompare(StringSerializer.INSTANCE, "xyz", "abc", 1);
  }

  @Test
  public void testStringEmpty() {
    assertCompare(StringSerializer.INSTANCE, "", "", 0);
  }

  @Test
  public void testStringEmptyVsNonEmpty() {
    assertCompare(StringSerializer.INSTANCE, "", "a", -1);
  }

  @Test
  public void testStringNonEmptyVsEmpty() {
    assertCompare(StringSerializer.INSTANCE, "a", "", 1);
  }

  @Test
  public void testStringPrefix() {
    // "abc" < "abcd" because shorter string is less when it's a prefix
    assertCompare(StringSerializer.INSTANCE, "abc", "abcd", -1);
  }

  @Test
  public void testStringUnicode() {
    // Test with non-ASCII characters
    assertCompare(StringSerializer.INSTANCE, "\u00e9", "\u00ea", -1);
  }

  @Test
  public void testStringHighUnicode() {
    // Characters above 0xFF to test the two-byte char encoding
    assertCompare(StringSerializer.INSTANCE, "\u4e16\u754c", "\u4e16\u754c", 0);
  }

  @Test
  public void testStringHighUnicodeDifferent() {
    assertCompare(StringSerializer.INSTANCE, "\u4e16", "\u4e17", -1);
  }

  // =====================================================================
  // UTF8Serializer tests
  // =====================================================================

  @Test
  public void testUTF8Equal() {
    assertCompare(UTF8Serializer.INSTANCE, "hello", "hello", 0);
  }

  @Test
  public void testUTF8LessThan() {
    assertCompare(UTF8Serializer.INSTANCE, "abc", "abd", -1);
  }

  @Test
  public void testUTF8GreaterThan() {
    assertCompare(UTF8Serializer.INSTANCE, "xyz", "abc", 1);
  }

  @Test
  public void testUTF8Empty() {
    assertCompare(UTF8Serializer.INSTANCE, "", "", 0);
  }

  @Test
  public void testUTF8EmptyVsNonEmpty() {
    assertCompare(UTF8Serializer.INSTANCE, "", "a", -1);
  }

  @Test
  public void testUTF8Prefix() {
    assertCompare(UTF8Serializer.INSTANCE, "abc", "abcd", -1);
  }

  @Test
  public void testUTF8TwoByte() {
    // Characters encoded as 2 bytes in UTF-8 (U+0080 to U+07FF)
    assertCompare(UTF8Serializer.INSTANCE, "\u00e9", "\u00ea", -1);
  }

  @Test
  public void testUTF8ThreeByte() {
    // Characters encoded as 3 bytes in UTF-8 (U+0800 to U+FFFF)
    assertCompare(UTF8Serializer.INSTANCE, "\u4e16", "\u4e16", 0);
  }

  @Test
  public void testUTF8ThreeByteDifferent() {
    assertCompare(UTF8Serializer.INSTANCE, "\u4e16", "\u4e17", -1);
  }

  @Test
  public void testUTF8MixedAsciiAndMultibyte() {
    // "a\u00e9" vs "a\u00ea": ASCII prefix matches, then multibyte differs
    assertCompare(UTF8Serializer.INSTANCE, "a\u00e9", "a\u00ea", -1);
  }

  @Test
  public void testUTF8FourByteEqual() {
    // Supplementary characters (U+1F600 = grinning face emoji, 4-byte UTF-8)
    assertCompare(UTF8Serializer.INSTANCE, "\uD83D\uDE00", "\uD83D\uDE00", 0);
  }

  @Test
  public void testUTF8FourByteDifferent() {
    // U+1F600 (grinning face) vs U+1F601 (grinning face with smiling eyes)
    // Same high surrogate (D83D), different low surrogate (DE00 vs DE01)
    assertCompare(UTF8Serializer.INSTANCE, "\uD83D\uDE00", "\uD83D\uDE01", -1);
  }

  @Test
  public void testUTF8FourByteDifferentHighSurrogate() {
    // U+10000 (high surrogate D800) vs U+1F600 (high surrogate D83D).
    // Differ at the high surrogate level, exercising the early-return path.
    var s1 = new String(Character.toChars(0x10000)); // D800 DC00
    var s2 = "\uD83D\uDE00"; // D83D DE00
    var expected = Integer.signum(s1.compareTo(s2));
    assertCompare(UTF8Serializer.INSTANCE, s1, s2, expected);
  }

  @Test
  public void testUTF8FourByteVsThreeByte() {
    // Supplementary char (U+1F600, 4-byte UTF-8) vs BMP char (U+4E16, 3-byte UTF-8).
    // String.compareTo() compares UTF-16 code units: high surrogate 0xD83D vs 0x4E16.
    var supplementary = "\uD83D\uDE00"; // U+1F600
    var bmp = "\u4E16"; // U+4E16
    var expected = Integer.signum(supplementary.compareTo(bmp));
    assertCompare(UTF8Serializer.INSTANCE, supplementary, bmp, expected);
  }

  @Test
  public void testUTF8FourByteWithAsciiPrefix() {
    // "a" + U+1F600 vs "a" + U+1F601: ASCII prefix matches, then 4-byte differs
    assertCompare(UTF8Serializer.INSTANCE, "a\uD83D\uDE00", "a\uD83D\uDE01", -1);
  }

  @Test
  public void testUTF8MixedBmpAndSupplementary() {
    // U+4E16 (BMP, 3-byte) + U+1F600 (supplementary, 4-byte) vs same
    assertCompare(UTF8Serializer.INSTANCE,
        "\u4E16\uD83D\uDE00", "\u4E16\uD83D\uDE00", 0);
  }

  @Test
  public void testUTF8MixedBmpAndSupplementaryDifferent() {
    // Strings differ at the supplementary character position
    assertCompare(UTF8Serializer.INSTANCE,
        "\u4E16\uD83D\uDE00", "\u4E16\uD83D\uDE01", -1);
  }

  // =====================================================================
  // CompositeKeySerializer tests
  // =====================================================================

  @Test
  public void testCompositeKeyEqual() {
    var key1 = new CompositeKey(42, "hello");
    var key2 = new CompositeKey(42, "hello");
    var types = new PropertyTypeInternal[] {
        PropertyTypeInternal.INTEGER, PropertyTypeInternal.STRING};
    assertCompare(CompositeKeySerializer.INSTANCE, key1, key2, 0, types);
  }

  @Test
  public void testCompositeKeyFirstFieldLess() {
    var key1 = new CompositeKey(10, "hello");
    var key2 = new CompositeKey(20, "hello");
    var types = new PropertyTypeInternal[] {
        PropertyTypeInternal.INTEGER, PropertyTypeInternal.STRING};
    assertCompare(CompositeKeySerializer.INSTANCE, key1, key2, -1, types);
  }

  @Test
  public void testCompositeKeySecondFieldGreater() {
    var key1 = new CompositeKey(42, "xyz");
    var key2 = new CompositeKey(42, "abc");
    var types = new PropertyTypeInternal[] {
        PropertyTypeInternal.INTEGER, PropertyTypeInternal.STRING};
    assertCompare(CompositeKeySerializer.INSTANCE, key1, key2, 1, types);
  }

  @Test
  public void testCompositeKeyNullField() {
    // null < any non-null value
    var key1 = new CompositeKey();
    key1.addKey(null);
    key1.addKey("hello");
    var key2 = new CompositeKey(42, "hello");
    var types = new PropertyTypeInternal[] {
        PropertyTypeInternal.INTEGER, PropertyTypeInternal.STRING};
    assertCompare(CompositeKeySerializer.INSTANCE, key1, key2, -1, types);
  }

  @Test
  public void testCompositeKeyBothNullField() {
    // Both null in first field - should compare equal on first field,
    // then compare on second
    var key1 = new CompositeKey();
    key1.addKey(null);
    key1.addKey("abc");
    var key2 = new CompositeKey();
    key2.addKey(null);
    key2.addKey("xyz");
    var types = new PropertyTypeInternal[] {
        PropertyTypeInternal.INTEGER, PropertyTypeInternal.STRING};
    assertCompare(CompositeKeySerializer.INSTANCE, key1, key2, -1, types);
  }

  @Test
  public void testCompositeKeyNonNullPageVsNullSearch() {
    // Page key has non-null first field, search key has null first field.
    // This covers the "non-null > search null" branch (line 497).
    var key1 = new CompositeKey(42, "hello"); // page value: non-null first field
    var key2 = new CompositeKey();
    key2.addKey(null);
    key2.addKey("hello"); // search value: null first field
    var types = new PropertyTypeInternal[] {
        PropertyTypeInternal.INTEGER, PropertyTypeInternal.STRING};
    assertCompare(CompositeKeySerializer.INSTANCE, key1, key2, 1, types);
  }

  @Test
  public void testCompositeKeySingleField() {
    var key1 = new CompositeKey((Object) 42);
    var key2 = new CompositeKey((Object) 43);
    var types = new PropertyTypeInternal[] {PropertyTypeInternal.INTEGER};
    assertCompare(CompositeKeySerializer.INSTANCE, key1, key2, -1, types);
  }

  @Test
  public void testCompositeKeyDifferentFieldCount() {
    // Keys with different numbers of fields
    var key1 = new CompositeKey((Object) 42);
    var key2 = new CompositeKey(42, "hello");
    var types1 = new PropertyTypeInternal[] {PropertyTypeInternal.INTEGER};
    var types2 = new PropertyTypeInternal[] {
        PropertyTypeInternal.INTEGER, PropertyTypeInternal.STRING};

    // Serialize with their respective hints
    final var pageBytes = CompositeKeySerializer.INSTANCE.serializeNativeAsWhole(
        serializerFactory, key1, (Object[]) types1);
    final var buffer = ByteBuffer.allocateDirect(pageBytes.length)
        .order(ByteOrder.nativeOrder());
    buffer.put(pageBytes);

    final var searchBytes = CompositeKeySerializer.INSTANCE.serializeNativeAsWhole(
        serializerFactory, key2, (Object[]) types2);

    final var result = CompositeKeySerializer.INSTANCE.compareInByteBuffer(
        serializerFactory, 0, buffer, searchBytes, 0);

    // key1 has fewer fields, so key1 < key2
    Assert.assertTrue("Key with fewer fields should be less", result < 0);
  }

  // =====================================================================
  // Offset tests - verify comparison works at non-zero offsets
  // =====================================================================

  @Test
  public void testIntegerAtOffset() {
    // Put some padding before the actual data in the ByteBuffer
    final int offset = 13;
    final var pageBytes = IntegerSerializer.INSTANCE.serializeNativeAsWhole(
        serializerFactory, 42);
    final var buffer = ByteBuffer.allocateDirect(pageBytes.length + offset)
        .order(ByteOrder.nativeOrder());
    buffer.position(offset);
    buffer.put(pageBytes);

    final var searchBytes = IntegerSerializer.INSTANCE.serializeNativeAsWhole(
        serializerFactory, 42);

    final var result = IntegerSerializer.INSTANCE.compareInByteBuffer(
        serializerFactory, offset, buffer, searchBytes, 0);
    Assert.assertEquals(0, result);
  }

  @Test
  public void testStringAtOffset() {
    final int offset = 7;
    final var pageBytes = StringSerializer.INSTANCE.serializeNativeAsWhole(
        serializerFactory, "test");
    final var buffer = ByteBuffer.allocateDirect(pageBytes.length + offset)
        .order(ByteOrder.nativeOrder());
    buffer.position(offset);
    buffer.put(pageBytes);

    final var searchBytes = StringSerializer.INSTANCE.serializeNativeAsWhole(
        serializerFactory, "test");

    final var result = StringSerializer.INSTANCE.compareInByteBuffer(
        serializerFactory, offset, buffer, searchBytes, 0);
    Assert.assertEquals(0, result);
  }

  // =====================================================================
  // Default fallback test - verify the default implementation works
  // =====================================================================

  @Test
  public void testDefaultFallbackWithBooleanSerializer() {
    // BooleanSerializer doesn't override compareInByteBuffer, so uses the default
    var serializer = BooleanSerializer.INSTANCE;
    assertCompare(serializer, true, true, 0);
    assertCompare(serializer, false, true, -1);
    assertCompare(serializer, true, false, 1);
  }
}
