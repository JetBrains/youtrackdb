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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import org.junit.Test;

/**
 * Validates that VarIntSerializer and HelperClasses ReadBytesContainer overloads produce identical
 * results to the BytesContainer versions. Each test serializes values using the existing write
 * path (BytesContainer), then deserializes with both BytesContainer and ReadBytesContainer and
 * compares results.
 */
public class VarIntAndHelperClassesReadBytesContainerTest {

  // --- VarIntSerializer tests ---

  @Test
  public void testReadUnsignedVarLongSingleByte() {
    // Value 5 encodes in 1 byte
    assertVarIntRoundTrip(5L);
  }

  @Test
  public void testReadUnsignedVarLongMultiByte() {
    // Value 300 encodes in 2 bytes
    assertVarIntRoundTrip(300L);
  }

  @Test
  public void testReadUnsignedVarLongZero() {
    // Zero is a single-byte boundary encoding (0x00)
    assertVarIntRoundTrip(0L);
  }

  @Test
  public void testReadUnsignedVarLongLargeValue() {
    // Large value requiring many bytes
    assertVarIntRoundTrip(Long.MAX_VALUE / 2);
  }

  @Test
  public void testReadSignedVarLongPositive() {
    assertSignedVarIntRoundTrip(42);
  }

  @Test
  public void testReadSignedVarLongNegative() {
    assertSignedVarIntRoundTrip(-100);
  }

  @Test
  public void testReadSignedVarLongZero() {
    assertSignedVarIntRoundTrip(0);
  }

  @Test
  public void testReadSignedVarLongMaxValue() {
    assertSignedVarIntRoundTrip(Long.MAX_VALUE);
  }

  @Test
  public void testReadSignedVarLongMinValue() {
    assertSignedVarIntRoundTrip(Long.MIN_VALUE);
  }

  @Test
  public void testReadAsInteger() {
    var writeContainer = new BytesContainer();
    VarIntSerializer.write(writeContainer, 12345);
    var bytes = writeContainer.fitBytes();

    var bc = new BytesContainer(bytes);
    var rbc = new ReadBytesContainer(bytes);

    assertEquals(12345, VarIntSerializer.readAsInteger(rbc));
    assertEquals(12345, VarIntSerializer.readAsInteger(bc));
  }

  @Test
  public void testReadAsLong() {
    var writeContainer = new BytesContainer();
    VarIntSerializer.write(writeContainer, 9876543210L);
    var bytes = writeContainer.fitBytes();

    var bc = new BytesContainer(bytes);
    var rbc = new ReadBytesContainer(bytes);

    assertEquals(9876543210L, VarIntSerializer.readAsLong(rbc));
    assertEquals(9876543210L, VarIntSerializer.readAsLong(bc));
  }

  @Test
  public void testReadAsShort() {
    var writeContainer = new BytesContainer();
    VarIntSerializer.write(writeContainer, (short) 1234);
    var bytes = writeContainer.fitBytes();

    var bc = new BytesContainer(bytes);
    var rbc = new ReadBytesContainer(bytes);

    assertEquals(
        VarIntSerializer.readAsShort(bc), VarIntSerializer.readAsShort(rbc));
  }

  @Test
  public void testReadAsByte() {
    var writeContainer = new BytesContainer();
    VarIntSerializer.write(writeContainer, (byte) 42);
    var bytes = writeContainer.fitBytes();

    var bc = new BytesContainer(bytes);
    var rbc = new ReadBytesContainer(bytes);

    assertEquals(VarIntSerializer.readAsByte(bc), VarIntSerializer.readAsByte(rbc));
  }

  // --- HelperClasses tests ---

  @Test
  public void testReadBinary() {
    var data = new byte[] {10, 20, 30, 40, 50};
    var writeContainer = new BytesContainer();
    HelperClasses.writeBinary(writeContainer, data);
    var bytes = writeContainer.fitBytes();

    var bc = new BytesContainer(bytes);
    var rbc = new ReadBytesContainer(bytes);

    assertArrayEquals(HelperClasses.readBinary(bc), HelperClasses.readBinary(rbc));
  }

  @Test
  public void testReadBinaryEmpty() {
    var writeContainer = new BytesContainer();
    HelperClasses.writeBinary(writeContainer, new byte[0]);
    var bytes = writeContainer.fitBytes();

    var bc = new BytesContainer(bytes);
    var rbc = new ReadBytesContainer(bytes);

    assertArrayEquals(HelperClasses.readBinary(bc), HelperClasses.readBinary(rbc));
  }

  @Test
  public void testReadString() {
    var writeContainer = new BytesContainer();
    HelperClasses.writeString(writeContainer, "Hello World");
    var bytes = writeContainer.fitBytes();

    var bc = new BytesContainer(bytes);
    var rbc = new ReadBytesContainer(bytes);

    assertEquals(HelperClasses.readString(bc), HelperClasses.readString(rbc));
  }

  @Test
  public void testReadStringEmpty() {
    var writeContainer = new BytesContainer();
    HelperClasses.writeString(writeContainer, "");
    var bytes = writeContainer.fitBytes();

    var bc = new BytesContainer(bytes);
    var rbc = new ReadBytesContainer(bytes);

    assertEquals(HelperClasses.readString(bc), HelperClasses.readString(rbc));
  }

  @Test
  public void testReadStringUtf8() {
    var writeContainer = new BytesContainer();
    HelperClasses.writeString(writeContainer, "Привет мир");
    var bytes = writeContainer.fitBytes();

    var bc = new BytesContainer(bytes);
    var rbc = new ReadBytesContainer(bytes);

    assertEquals(HelperClasses.readString(bc), HelperClasses.readString(rbc));
  }

  @Test
  public void testReadStringUtf8FourByteChars() {
    // 4-byte UTF-8 characters (emoji) to verify byte-length vs char-length handling
    var emoji = "\uD83D\uDE00\uD83D\uDE01\uD83D\uDE02"; // 3 emoji, 12 UTF-8 bytes
    var writeContainer = new BytesContainer();
    HelperClasses.writeString(writeContainer, emoji);
    var bytes = writeContainer.fitBytes();

    var bc = new BytesContainer(bytes);
    var rbc = new ReadBytesContainer(bytes);

    var bcResult = HelperClasses.readString(bc);
    var rbcResult = HelperClasses.readString(rbc);

    assertEquals(emoji, rbcResult);
    assertEquals(bcResult, rbcResult);
  }

  @Test
  public void testReadByte() {
    var bytes = new byte[] {42};

    var bc = new BytesContainer(bytes);
    var rbc = new ReadBytesContainer(bytes);

    assertEquals(HelperClasses.readByte(bc), HelperClasses.readByte(rbc));
  }

  @Test
  public void testReadInteger() {
    var writeContainer = new BytesContainer();
    var pos = writeContainer.alloc(4);
    com.jetbrains.youtrackdb.internal.common.serialization.types.IntegerSerializer
        .serializeLiteral(12345, writeContainer.bytes, pos);
    var bytes = writeContainer.fitBytes();

    var bc = new BytesContainer(bytes);
    var rbc = new ReadBytesContainer(bytes);

    assertEquals(HelperClasses.readInteger(bc), HelperClasses.readInteger(rbc));
  }

  @Test
  public void testReadLong() {
    var writeContainer = new BytesContainer();
    var pos = writeContainer.alloc(8);
    com.jetbrains.youtrackdb.internal.common.serialization.types.LongSerializer
        .serializeLiteral(9876543210L, writeContainer.bytes, pos);
    var bytes = writeContainer.fitBytes();

    var bc = new BytesContainer(bytes);
    var rbc = new ReadBytesContainer(bytes);

    assertEquals(HelperClasses.readLong(bc), HelperClasses.readLong(rbc));
  }

  @Test
  public void testReadType() {
    // Test with a valid type (STRING has id 7)
    var typeId = (byte) PropertyTypeInternal.STRING.getId();
    var bytes = new byte[] {typeId};

    var bc = new BytesContainer(bytes);
    var rbc = new ReadBytesContainer(bytes);

    assertEquals(HelperClasses.readType(bc), HelperClasses.readType(rbc));
  }

  @Test
  public void testReadTypeNull() {
    // -1 means null type
    var bytes = new byte[] {-1};

    var bc = new BytesContainer(bytes);
    var rbc = new ReadBytesContainer(bytes);

    assertNull(HelperClasses.readType(bc));
    assertNull(HelperClasses.readType(rbc));
  }

  @Test
  public void testReadOTypeRunThrough() {
    var bytes = new byte[] {7}; // STRING type

    var bc = new BytesContainer(bytes);
    var rbc = new ReadBytesContainer(bytes);

    // justRunThrough=true should skip the byte and return null for both
    assertNull(HelperClasses.readOType(bc, true));
    assertNull(HelperClasses.readOType(rbc, true));

    // Both should have advanced by 1 byte
    assertEquals(1, bc.offset);
    assertEquals(1, rbc.offset());
  }

  @Test
  public void testReadOType() {
    var typeId = (byte) PropertyTypeInternal.INTEGER.getId();
    var bytes = new byte[] {typeId};

    var bc = new BytesContainer(bytes);
    var rbc = new ReadBytesContainer(bytes);

    assertEquals(HelperClasses.readOType(bc, false), HelperClasses.readOType(rbc, false));
  }

  @Test
  public void testReadOptimizedLink() {
    // Write a link: clusterId=5, clusterPos=100
    var writeContainer = new BytesContainer();
    VarIntSerializer.write(writeContainer, 5);
    VarIntSerializer.write(writeContainer, 100);
    var bytes = writeContainer.fitBytes();

    var bc = new BytesContainer(bytes);
    var rbc = new ReadBytesContainer(bytes);

    var bcResult = HelperClasses.readOptimizedLink(bc, false);
    var rbcResult = HelperClasses.readOptimizedLink(rbc, false);

    assertEquals(bcResult, rbcResult);
  }

  @Test
  public void testReadOptimizedLinkRunThrough() {
    var writeContainer = new BytesContainer();
    VarIntSerializer.write(writeContainer, 5);
    VarIntSerializer.write(writeContainer, 100);
    var bytes = writeContainer.fitBytes();

    var bc = new BytesContainer(bytes);
    var rbc = new ReadBytesContainer(bytes);

    assertNull(HelperClasses.readOptimizedLink(bc, true));
    assertNull(HelperClasses.readOptimizedLink(rbc, true));

    // Both should have consumed the same bytes
    assertEquals(bc.offset, rbc.offset());
  }

  // --- Multiple values in sequence ---

  @Test
  public void testMultipleVarIntsInSequence() {
    // Write multiple values, then read them all back with both container types
    var writeContainer = new BytesContainer();
    VarIntSerializer.write(writeContainer, 1);
    VarIntSerializer.write(writeContainer, -42);
    VarIntSerializer.write(writeContainer, 300);
    VarIntSerializer.write(writeContainer, 0);
    VarIntSerializer.write(writeContainer, Long.MAX_VALUE);
    var bytes = writeContainer.fitBytes();

    var bc = new BytesContainer(bytes);
    var rbc = new ReadBytesContainer(bytes);

    assertEquals(VarIntSerializer.readAsInteger(bc), VarIntSerializer.readAsInteger(rbc));
    assertEquals(VarIntSerializer.readAsInteger(bc), VarIntSerializer.readAsInteger(rbc));
    assertEquals(VarIntSerializer.readAsInteger(bc), VarIntSerializer.readAsInteger(rbc));
    assertEquals(VarIntSerializer.readAsInteger(bc), VarIntSerializer.readAsInteger(rbc));
    assertEquals(VarIntSerializer.readAsLong(bc), VarIntSerializer.readAsLong(rbc));

    // Both should have consumed all bytes
    assertEquals(bc.offset, rbc.offset());
  }

  @Test
  public void testMixedStringAndBinaryInSequence() {
    var writeContainer = new BytesContainer();
    HelperClasses.writeString(writeContainer, "first");
    HelperClasses.writeBinary(writeContainer, new byte[] {1, 2, 3});
    HelperClasses.writeString(writeContainer, "second");
    var bytes = writeContainer.fitBytes();

    var bc = new BytesContainer(bytes);
    var rbc = new ReadBytesContainer(bytes);

    assertEquals(HelperClasses.readString(bc), HelperClasses.readString(rbc));
    assertArrayEquals(HelperClasses.readBinary(bc), HelperClasses.readBinary(rbc));
    assertEquals(HelperClasses.readString(bc), HelperClasses.readString(rbc));
  }

  // --- Helper methods ---

  private void assertVarIntRoundTrip(long value) {
    var writeContainer = new BytesContainer();
    VarIntSerializer.writeUnsignedVarLong(value, writeContainer);
    var bytes = writeContainer.fitBytes();

    var bc = new BytesContainer(bytes);
    var rbc = new ReadBytesContainer(bytes);

    var bcResult = VarIntSerializer.readUnsignedVarLong(bc);
    var rbcResult = VarIntSerializer.readUnsignedVarLong(rbc);

    // Verify both implementations agree and produce the original value
    assertEquals(value, rbcResult);
    assertEquals(bcResult, rbcResult);
  }

  private void assertSignedVarIntRoundTrip(long value) {
    var writeContainer = new BytesContainer();
    VarIntSerializer.write(writeContainer, value);
    var bytes = writeContainer.fitBytes();

    var bc = new BytesContainer(bytes);
    var rbc = new ReadBytesContainer(bytes);

    var bcResult = VarIntSerializer.readSignedVarLong(bc);
    var rbcResult = VarIntSerializer.readSignedVarLong(rbc);

    // Verify both implementations agree and produce the original value
    assertEquals(value, rbcResult);
    assertEquals(bcResult, rbcResult);
  }
}
