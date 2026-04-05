package com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import com.jetbrains.youtrackdb.internal.core.exception.CorruptedRecordException;
import org.junit.Test;

/**
 * Tests that HelperClasses ReadBytesContainer overloads throw {@link CorruptedRecordException} when
 * stream-driven sizes exceed remaining buffer capacity. Each test crafts a ReadBytesContainer with
 * a varint-encoded size that is invalid relative to the available data.
 */
public class HelperClassesGuardTest {

  // --- readBinary guards ---

  @Test
  public void readBinaryThrowsOnSizeExceedingRemaining() {
    // Encode size = remaining() + 1 (just one byte too many)
    var writeContainer = new BytesContainer();
    VarIntSerializer.write(writeContainer, 100); // claims 100 bytes of payload
    var bytes = writeContainer.fitBytes();
    // The buffer has only the varint bytes, no actual payload — 100 > remaining
    var rbc = new ReadBytesContainer(bytes);
    assertThrows(CorruptedRecordException.class, () -> HelperClasses.readBinary(rbc));
  }

  @Test
  public void readBinaryThrowsOnNegativeSize() {
    // Encode size = -1 (zigzag-encoded negative)
    var writeContainer = new BytesContainer();
    VarIntSerializer.write(writeContainer, -1);
    var bytes = writeContainer.fitBytes();
    var rbc = new ReadBytesContainer(bytes);
    assertThrows(CorruptedRecordException.class, () -> HelperClasses.readBinary(rbc));
  }

  @Test
  public void readBinaryThrowsOnMaxIntSize() {
    // Encode Integer.MAX_VALUE — far exceeds any realistic buffer
    var writeContainer = new BytesContainer();
    VarIntSerializer.write(writeContainer, Integer.MAX_VALUE);
    var bytes = writeContainer.fitBytes();
    var rbc = new ReadBytesContainer(bytes);
    assertThrows(CorruptedRecordException.class, () -> HelperClasses.readBinary(rbc));
  }

  @Test
  public void readBinarySucceedsOnExactFit() {
    // Encode size = 5 followed by exactly 5 bytes of payload
    var payload = new byte[] {1, 2, 3, 4, 5};
    var writeContainer = new BytesContainer();
    HelperClasses.writeBinary(writeContainer, payload);
    var bytes = writeContainer.fitBytes();
    var rbc = new ReadBytesContainer(bytes);
    assertArrayEquals(payload, HelperClasses.readBinary(rbc));
    assertEquals(0, rbc.remaining());
  }

  @Test
  public void readBinarySucceedsOnZeroSize() {
    // Encode size = 0 — valid empty binary
    var writeContainer = new BytesContainer();
    HelperClasses.writeBinary(writeContainer, new byte[0]);
    var bytes = writeContainer.fitBytes();
    var rbc = new ReadBytesContainer(bytes);
    assertArrayEquals(new byte[0], HelperClasses.readBinary(rbc));
  }

  // --- readString guards ---

  @Test
  public void readStringThrowsOnSizeExceedingRemaining() {
    // Encode len = 500, but only provide varint bytes (no string payload)
    var writeContainer = new BytesContainer();
    VarIntSerializer.write(writeContainer, 500);
    var bytes = writeContainer.fitBytes();
    var rbc = new ReadBytesContainer(bytes);
    assertThrows(CorruptedRecordException.class, () -> HelperClasses.readString(rbc));
  }

  @Test
  public void readStringThrowsOnNegativeLength() {
    var writeContainer = new BytesContainer();
    VarIntSerializer.write(writeContainer, -1);
    var bytes = writeContainer.fitBytes();
    var rbc = new ReadBytesContainer(bytes);
    assertThrows(CorruptedRecordException.class, () -> HelperClasses.readString(rbc));
  }

  @Test
  public void readStringSucceedsOnValidData() {
    var writeContainer = new BytesContainer();
    HelperClasses.writeString(writeContainer, "hello");
    var bytes = writeContainer.fitBytes();
    var rbc = new ReadBytesContainer(bytes);
    assertEquals("hello", HelperClasses.readString(rbc));
  }

  @Test
  public void readStringSucceedsOnEmptyString() {
    // len = 0 takes the early return path, no guard needed
    var writeContainer = new BytesContainer();
    HelperClasses.writeString(writeContainer, "");
    var bytes = writeContainer.fitBytes();
    var rbc = new ReadBytesContainer(bytes);
    assertEquals("", HelperClasses.readString(rbc));
  }

  // --- readLinkCollection guards ---

  @Test
  public void readLinkCollectionThrowsOnSizeExceedingRemaining() {
    // Build buffer: type byte (0) + varint encoding huge items count.
    // Use justRunThrough=true so the collection parameter is never accessed —
    // the guard fires before the loop regardless.
    var writeContainer = new BytesContainer();
    var pos = writeContainer.alloc(1);
    writeContainer.bytes[pos] = 0; // valid type byte
    VarIntSerializer.write(writeContainer, Integer.MAX_VALUE); // huge items count
    var bytes = writeContainer.fitBytes();
    var rbc = new ReadBytesContainer(bytes);
    assertThrows(
        CorruptedRecordException.class,
        () -> HelperClasses.readLinkCollection(rbc, null, true));
  }

  @Test
  public void readLinkCollectionThrowsOnNegativeSize() {
    var writeContainer = new BytesContainer();
    var pos = writeContainer.alloc(1);
    writeContainer.bytes[pos] = 0;
    VarIntSerializer.write(writeContainer, -1);
    var bytes = writeContainer.fitBytes();
    var rbc = new ReadBytesContainer(bytes);
    assertThrows(
        CorruptedRecordException.class,
        () -> HelperClasses.readLinkCollection(rbc, null, true));
  }

  // --- readLinkMap guards ---

  @Test
  public void readLinkMapThrowsOnSizeExceedingRemaining() {
    // Build buffer: version byte (0) + varint encoding huge size
    var writeContainer = new BytesContainer();
    var pos = writeContainer.alloc(1);
    writeContainer.bytes[pos] = 0; // valid version byte
    VarIntSerializer.write(writeContainer, 1_000_000);
    var bytes = writeContainer.fitBytes();
    var rbc = new ReadBytesContainer(bytes);
    assertThrows(
        CorruptedRecordException.class,
        () -> HelperClasses.readLinkMap(rbc, null, false));
  }

  @Test
  public void readLinkMapThrowsOnNegativeSize() {
    var writeContainer = new BytesContainer();
    var pos = writeContainer.alloc(1);
    writeContainer.bytes[pos] = 0;
    VarIntSerializer.write(writeContainer, -1);
    var bytes = writeContainer.fitBytes();
    var rbc = new ReadBytesContainer(bytes);
    assertThrows(
        CorruptedRecordException.class,
        () -> HelperClasses.readLinkMap(rbc, null, false));
  }
}
