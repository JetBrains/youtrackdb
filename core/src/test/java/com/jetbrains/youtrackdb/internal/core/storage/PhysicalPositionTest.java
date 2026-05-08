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
package com.jetbrains.youtrackdb.internal.core.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import org.junit.Test;

/**
 * Tests for PhysicalPosition: Externalizable round-trip, SerializableStream round-trip,
 * equals/hashCode contract, copyFrom, toString, and RecordMetadata getters.
 */
public class PhysicalPositionTest {

  // --- Externalizable round-trip ---

  @Test
  public void testExternalizableRoundTripPreservesAllFields()
      throws IOException, ClassNotFoundException {
    // Verifies that writeExternal/readExternal preserves all four fields
    // (collectionPosition, recordType, recordSize, recordVersion) across
    // Java serialization.
    var original = new PhysicalPosition();
    original.collectionPosition = 123456789L;
    original.recordType = (byte) 5;
    original.recordSize = 512;
    original.recordVersion = 42L;

    var restored = serializeDeserialize(original);

    assertEquals(original.collectionPosition, restored.collectionPosition);
    assertEquals(original.recordType, restored.recordType);
    assertEquals(original.recordSize, restored.recordSize);
    assertEquals(original.recordVersion, restored.recordVersion);
  }

  @Test
  public void testExternalizableRoundTripWithZeroValues()
      throws IOException, ClassNotFoundException {
    // Verifies that the zero/default state round-trips correctly — no
    // special sentinel or compression that could mangle zero values.
    var original = new PhysicalPosition();
    // All fields default to 0

    var restored = serializeDeserialize(original);

    assertEquals(0L, restored.collectionPosition);
    assertEquals((byte) 0, restored.recordType);
    assertEquals(0, restored.recordSize);
    assertEquals(0L, restored.recordVersion);
  }

  @Test
  public void testExternalizableRoundTripWithNegativeValues()
      throws IOException, ClassNotFoundException {
    // Negative values for long/int fields are valid (e.g., unset markers,
    // maximum-range probes). Ensures no unsigned reinterpretation.
    var original = new PhysicalPosition();
    original.collectionPosition = Long.MIN_VALUE;
    original.recordType = (byte) -1;
    original.recordSize = Integer.MIN_VALUE;
    original.recordVersion = -1L;

    var restored = serializeDeserialize(original);

    assertEquals(Long.MIN_VALUE, restored.collectionPosition);
    assertEquals((byte) -1, restored.recordType);
    assertEquals(Integer.MIN_VALUE, restored.recordSize);
    assertEquals(-1L, restored.recordVersion);
  }

  @Test
  public void testExternalizableRoundTripWithMaxValues()
      throws IOException, ClassNotFoundException {
    // Maximum boundary values for all fields.
    var original = new PhysicalPosition();
    original.collectionPosition = Long.MAX_VALUE;
    original.recordType = Byte.MAX_VALUE;
    original.recordSize = Integer.MAX_VALUE;
    original.recordVersion = Long.MAX_VALUE;

    var restored = serializeDeserialize(original);

    assertEquals(Long.MAX_VALUE, restored.collectionPosition);
    assertEquals(Byte.MAX_VALUE, restored.recordType);
    assertEquals(Integer.MAX_VALUE, restored.recordSize);
    assertEquals(Long.MAX_VALUE, restored.recordVersion);
  }

  @Test
  public void testExternalizableFieldWriteOrder() throws IOException, ClassNotFoundException {
    // PhysicalPosition writes: collectionPosition, recordType, recordSize, recordVersion.
    // Distinct non-zero values verify the order is preserved correctly.
    var original = new PhysicalPosition();
    original.collectionPosition = 111L;
    original.recordType = (byte) 2;
    original.recordSize = 333;
    original.recordVersion = 444L;

    var restored = serializeDeserialize(original);

    assertEquals(111L, restored.collectionPosition);
    assertEquals((byte) 2, restored.recordType);
    assertEquals(333, restored.recordSize);
    assertEquals(444L, restored.recordVersion);
  }

  // --- SerializableStream round-trip (toStream/fromStream) ---

  @Test
  public void testStreamRoundTripPreservesAllFields() {
    // Verifies that toStream() produces a byte array and fromStream() restores
    // all four fields exactly.
    var original = new PhysicalPosition();
    original.collectionPosition = 987654321L;
    original.recordType = (byte) 7;
    original.recordSize = 1024;
    original.recordVersion = 55L;

    var bytes = original.toStream();

    var restored = new PhysicalPosition();
    restored.fromStream(bytes);

    assertEquals(original.collectionPosition, restored.collectionPosition);
    assertEquals(original.recordType, restored.recordType);
    assertEquals(original.recordSize, restored.recordSize);
    assertEquals(original.recordVersion, restored.recordVersion);
  }

  @Test
  public void testStreamRoundTripProducesFixedSizeBuffer() {
    // toStream() must produce exactly SIZE_LONG + SIZE_BYTE + SIZE_INT + SIZE_LONG
    // = 8 + 1 + 4 + 8 = 21 bytes regardless of field values.
    var pp = new PhysicalPosition();
    pp.collectionPosition = 1L;
    pp.recordType = (byte) 1;
    pp.recordSize = 1;
    pp.recordVersion = 1L;

    var bytes = pp.toStream();

    assertEquals(21, bytes.length);
  }

  @Test
  public void testStreamRoundTripFieldEncodingOrder() {
    // Distinct values verify that fields are encoded in the documented order:
    // collectionPosition, recordType, recordSize, recordVersion.
    var original = new PhysicalPosition();
    original.collectionPosition = 0x0102030405060708L;
    original.recordType = (byte) 0x09;
    original.recordSize = 0x0A0B0C0D;
    original.recordVersion = 0x0E0F1011_12131415L;

    var bytes = original.toStream();
    var restored = new PhysicalPosition();
    restored.fromStream(bytes);

    assertEquals(original.collectionPosition, restored.collectionPosition);
    assertEquals(original.recordType, restored.recordType);
    assertEquals(original.recordSize, restored.recordSize);
    assertEquals(original.recordVersion, restored.recordVersion);
  }

  @Test
  public void testStreamRoundTripWithZeroValues() {
    // Zero/default state round-trips without field corruption.
    var original = new PhysicalPosition();
    var bytes = original.toStream();
    var restored = new PhysicalPosition();
    restored.fromStream(bytes);

    assertEquals(0L, restored.collectionPosition);
    assertEquals((byte) 0, restored.recordType);
    assertEquals(0, restored.recordSize);
    assertEquals(0L, restored.recordVersion);
  }

  @Test
  public void testFromStreamReturnsSelf() {
    // fromStream() returns the same instance (SerializableStream contract).
    var pp = new PhysicalPosition();
    var result = pp.fromStream(pp.toStream());

    assertTrue(result == pp);
  }

  // --- Constructors ---

  @Test
  public void testConstructorWithCollectionPosition() {
    // PhysicalPosition(long) sets only collectionPosition; other fields default.
    var pp = new PhysicalPosition(7L);

    assertEquals(7L, pp.collectionPosition);
    assertEquals((byte) 0, pp.recordType);
    assertEquals(0L, pp.recordVersion);
    assertEquals(0, pp.recordSize);
  }

  @Test
  public void testConstructorWithRecordType() {
    // PhysicalPosition(byte) sets only recordType; other fields default.
    var pp = new PhysicalPosition((byte) 3);

    assertEquals(0L, pp.collectionPosition);
    assertEquals((byte) 3, pp.recordType);
    assertEquals(0L, pp.recordVersion);
    assertEquals(0, pp.recordSize);
  }

  @Test
  public void testConstructorWithPositionAndVersion() {
    // PhysicalPosition(long, long) sets collectionPosition and recordVersion.
    var pp = new PhysicalPosition(10L, 20L);

    assertEquals(10L, pp.collectionPosition);
    assertEquals(20L, pp.recordVersion);
    assertEquals((byte) 0, pp.recordType);
    assertEquals(0, pp.recordSize);
  }

  // --- equals / hashCode ---

  @Test
  public void testEqualsReturnsTrueForIdenticalFields() {
    var a = makePosition(1L, (byte) 2, 3, 4L);
    var b = makePosition(1L, (byte) 2, 3, 4L);

    assertTrue(a.equals(b));
  }

  @Test
  public void testEqualsReturnsFalseForNullArgument() {
    var a = makePosition(1L, (byte) 2, 3, 4L);

    assertFalse(a.equals(null));
  }

  @Test
  public void testEqualsReturnsFalseForDifferentType() {
    var a = makePosition(1L, (byte) 2, 3, 4L);

    assertFalse(a.equals("not a PhysicalPosition"));
  }

  @Test
  public void testEqualsReturnsFalseWhenCollectionPositionDiffers() {
    var a = makePosition(1L, (byte) 2, 3, 4L);
    var b = makePosition(99L, (byte) 2, 3, 4L);

    assertFalse(a.equals(b));
  }

  @Test
  public void testEqualsReturnsFalseWhenRecordTypeDiffers() {
    var a = makePosition(1L, (byte) 2, 3, 4L);
    var b = makePosition(1L, (byte) 9, 3, 4L);

    assertFalse(a.equals(b));
  }

  @Test
  public void testEqualsReturnsFalseWhenRecordSizeDiffers() {
    var a = makePosition(1L, (byte) 2, 3, 4L);
    var b = makePosition(1L, (byte) 2, 99, 4L);

    assertFalse(a.equals(b));
  }

  @Test
  public void testEqualsReturnsFalseWhenRecordVersionDiffers() {
    var a = makePosition(1L, (byte) 2, 3, 4L);
    var b = makePosition(1L, (byte) 2, 3, 99L);

    assertFalse(a.equals(b));
  }

  @Test
  public void testHashCodeIsConsistentForEqualObjects() {
    var a = makePosition(5L, (byte) 1, 100, 7L);
    var b = makePosition(5L, (byte) 1, 100, 7L);

    assertEquals(a.hashCode(), b.hashCode());
  }

  @Test
  public void testHashCodeDiffersForDifferentCollectionPositions() {
    // A change in collectionPosition must (generally) change the hash code.
    var a = makePosition(1L, (byte) 0, 0, 0L);
    var b = makePosition(2L, (byte) 0, 0, 0L);

    // Not a strict requirement, but the polynomial formula makes this reliable
    // for adjacent values.
    assertNotEquals(a.hashCode(), b.hashCode());
  }

  // --- copyFrom ---

  @Test
  public void testCopyFromCopiesAllFields() {
    // Verifies that copyFrom transfers all four fields from the source to the
    // destination instance.
    var source = makePosition(42L, (byte) 3, 256, 7L);
    var dest = new PhysicalPosition();

    dest.copyFrom(source);

    assertEquals(42L, dest.collectionPosition);
    assertEquals((byte) 3, dest.recordType);
    assertEquals(256, dest.recordSize);
    assertEquals(7L, dest.recordVersion);
  }

  @Test
  public void testCopyFromDoesNotModifySource() {
    // copyFrom must not mutate the source instance.
    var source = makePosition(42L, (byte) 3, 256, 7L);
    var dest = new PhysicalPosition();

    dest.copyFrom(source);

    assertEquals(42L, source.collectionPosition);
    assertEquals((byte) 3, source.recordType);
    assertEquals(256, source.recordSize);
    assertEquals(7L, source.recordVersion);
  }

  // --- toString ---

  @Test
  public void testToStringContainsCollectionPosition() {
    // toString() must include the collection position value so it's useful in
    // assertion failure messages and logs.
    var pp = makePosition(77L, (byte) 0, 0, 0L);

    assertTrue(pp.toString().contains("77"));
  }

  @Test
  public void testToStringContainsRecordTypeAndVersion() {
    // toString() must include recordType and recordVersion.
    var pp = makePosition(0L, (byte) 5, 0, 99L);

    assertTrue(pp.toString().contains("5"));
    assertTrue(pp.toString().contains("99"));
  }

  // --- RecordMetadata getters ---

  @Test
  public void testRecordMetadataGetRecordIdReturnsConstructorValue() {
    // Verifies that getRecordId() returns the RID passed to the constructor.
    var rid = new RecordId(1, 2L);
    var meta = new RecordMetadata(rid, 10L);

    assertEquals(rid, meta.getRecordId());
  }

  @Test
  public void testRecordMetadataGetVersionReturnsConstructorValue() {
    // Verifies that getVersion() returns the version passed to the constructor.
    var rid = new RecordId(3, 4L);
    var meta = new RecordMetadata(rid, 99L);

    assertEquals(99L, meta.getVersion());
  }

  @Test
  public void testRecordMetadataAcceptsNullRid() {
    // Verifies that RecordMetadata allows a null RID (e.g., placeholder entry).
    var meta = new RecordMetadata(null, 1L);

    assertNull(meta.getRecordId());
    assertEquals(1L, meta.getVersion());
  }

  @Test
  public void testRecordMetadataAcceptsZeroVersion() {
    // Version 0 is a valid initial version.
    var rid = new RecordId(0, 0L);
    var meta = new RecordMetadata(rid, 0L);

    assertEquals(0L, meta.getVersion());
  }

  @Test
  public void testRecordMetadataAcceptsNegativeVersion() {
    // Negative versions can represent special states (e.g., -1 = tombstone).
    var rid = new RecordId(0, 0L);
    var meta = new RecordMetadata(rid, -1L);

    assertEquals(-1L, meta.getVersion());
  }

  // --- helpers ---

  private static PhysicalPosition makePosition(
      long collectionPosition, byte recordType, int recordSize, long recordVersion) {
    var pp = new PhysicalPosition();
    pp.collectionPosition = collectionPosition;
    pp.recordType = recordType;
    pp.recordSize = recordSize;
    pp.recordVersion = recordVersion;
    return pp;
  }

  private static PhysicalPosition serializeDeserialize(PhysicalPosition src)
      throws IOException, ClassNotFoundException {
    var baos = new ByteArrayOutputStream();
    try (var oos = new ObjectOutputStream(baos)) {
      oos.writeObject(src);
    }
    try (var ois = new ObjectInputStream(new ByteArrayInputStream(baos.toByteArray()))) {
      return (PhysicalPosition) ois.readObject();
    }
  }
}
