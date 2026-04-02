package com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary;

import static org.junit.Assert.assertThrows;

import com.jetbrains.youtrackdb.internal.core.exception.CorruptedRecordException;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import org.junit.Test;

/**
 * Tests that RecordSerializerBinaryV1 ReadBytesContainer overloads throw {@link
 * CorruptedRecordException} when stream-driven sizes exceed remaining buffer capacity. Covers
 * collection readers, value readers, and link bag readers.
 */
public class RecordSerializerBinaryV1GuardTest {

  private final RecordSerializerBinaryV1 serializer = new RecordSerializerBinaryV1();

  // --- readEmbeddedSet guards ---

  @Test
  public void readEmbeddedSetThrowsOnOversizedItems() {
    // items varint = 1_000_000, then 1 byte for type — far more items than bytes
    var wc = new BytesContainer();
    VarIntSerializer.write(wc, 1_000_000);
    var bytes = wc.fitBytes();
    var rbc = new ReadBytesContainer(bytes);
    assertThrows(
        CorruptedRecordException.class,
        () -> serializer.readEmbeddedSet(null, rbc, null));
  }

  @Test
  public void readEmbeddedSetThrowsOnNegativeItems() {
    var wc = new BytesContainer();
    VarIntSerializer.write(wc, -1);
    var bytes = wc.fitBytes();
    var rbc = new ReadBytesContainer(bytes);
    assertThrows(
        CorruptedRecordException.class,
        () -> serializer.readEmbeddedSet(null, rbc, null));
  }

  // --- readEmbeddedList guards ---

  @Test
  public void readEmbeddedListThrowsOnOversizedItems() {
    var wc = new BytesContainer();
    VarIntSerializer.write(wc, 1_000_000);
    var bytes = wc.fitBytes();
    var rbc = new ReadBytesContainer(bytes);
    assertThrows(
        CorruptedRecordException.class,
        () -> serializer.readEmbeddedList(null, rbc, null));
  }

  @Test
  public void readEmbeddedListThrowsOnNegativeItems() {
    var wc = new BytesContainer();
    VarIntSerializer.write(wc, -1);
    var bytes = wc.fitBytes();
    var rbc = new ReadBytesContainer(bytes);
    assertThrows(
        CorruptedRecordException.class,
        () -> serializer.readEmbeddedList(null, rbc, null));
  }

  // --- readEmbeddedMap guards ---

  @Test
  public void readEmbeddedMapThrowsOnOversizedElements() {
    var wc = new BytesContainer();
    VarIntSerializer.write(wc, 1_000_000);
    var bytes = wc.fitBytes();
    var rbc = new ReadBytesContainer(bytes);
    assertThrows(
        CorruptedRecordException.class,
        () -> serializer.readEmbeddedMap(null, rbc, null));
  }

  @Test
  public void readEmbeddedMapThrowsOnNegativeSize() {
    var wc = new BytesContainer();
    VarIntSerializer.write(wc, -1);
    var bytes = wc.fitBytes();
    var rbc = new ReadBytesContainer(bytes);
    assertThrows(
        CorruptedRecordException.class,
        () -> serializer.readEmbeddedMap(null, rbc, null));
  }

  // --- readLinkSet guards ---

  @Test
  public void readLinkSetThrowsOnOversizedBagSize() {
    // configByte (1 byte, embedded=true) + oversized linkBagSize varint
    var wc = new BytesContainer();
    var pos = wc.alloc(1);
    wc.bytes[pos] = 1; // embedded flag set
    VarIntSerializer.write(wc, Integer.MAX_VALUE);
    var bytes = wc.fitBytes();
    var rbc = new ReadBytesContainer(bytes);
    assertThrows(
        CorruptedRecordException.class,
        () -> RecordSerializerBinaryV1.readLinkSet(null, rbc));
  }

  @Test
  public void readLinkSetThrowsOnNegativeBagSize() {
    var wc = new BytesContainer();
    var pos = wc.alloc(1);
    wc.bytes[pos] = 1;
    VarIntSerializer.write(wc, -1);
    var bytes = wc.fitBytes();
    var rbc = new ReadBytesContainer(bytes);
    assertThrows(
        CorruptedRecordException.class,
        () -> RecordSerializerBinaryV1.readLinkSet(null, rbc));
  }

  // --- readLinkBag guards ---

  @Test
  public void readLinkBagThrowsOnOversizedBagSize() {
    var wc = new BytesContainer();
    var pos = wc.alloc(1);
    wc.bytes[pos] = 1; // embedded flag
    VarIntSerializer.write(wc, Integer.MAX_VALUE);
    var bytes = wc.fitBytes();
    var rbc = new ReadBytesContainer(bytes);
    assertThrows(
        CorruptedRecordException.class,
        () -> RecordSerializerBinaryV1.readLinkBag(null, rbc));
  }

  @Test
  public void readLinkBagThrowsOnNegativeBagSize() {
    var wc = new BytesContainer();
    var pos = wc.alloc(1);
    wc.bytes[pos] = 1;
    VarIntSerializer.write(wc, -1);
    var bytes = wc.fitBytes();
    var rbc = new ReadBytesContainer(bytes);
    assertThrows(
        CorruptedRecordException.class,
        () -> RecordSerializerBinaryV1.readLinkBag(null, rbc));
  }

  // --- getPositionsFromEmbeddedMap guards ---

  @Test
  public void getPositionsFromEmbeddedMapThrowsOnOversizedCount() {
    var wc = new BytesContainer();
    VarIntSerializer.write(wc, 1_000_000);
    var bytes = wc.fitBytes();
    var rbc = new ReadBytesContainer(bytes);
    assertThrows(
        CorruptedRecordException.class,
        () -> serializer.getPositionsFromEmbeddedMap(null, rbc, null));
  }

  @Test
  public void getPositionsFromEmbeddedMapThrowsOnNegativeCount() {
    var wc = new BytesContainer();
    VarIntSerializer.write(wc, -1);
    var bytes = wc.fitBytes();
    var rbc = new ReadBytesContainer(bytes);
    assertThrows(
        CorruptedRecordException.class,
        () -> serializer.getPositionsFromEmbeddedMap(null, rbc, null));
  }

  // --- deserializeValue BINARY justRunThrough guard ---

  @Test
  public void deserializeValueBinaryJustRunThroughThrowsOnOversizedLength() {
    // BINARY justRunThrough: reads length varint then skips — guard catches oversized
    var wc = new BytesContainer();
    VarIntSerializer.write(wc, 1_000_000); // oversized length
    var bytes = wc.fitBytes();
    var rbc = new ReadBytesContainer(bytes);
    assertThrows(
        CorruptedRecordException.class,
        () -> serializer.deserializeValue(
            null, rbc, PropertyTypeInternal.BINARY, null, true, null));
  }

  // --- deserializeValue STRING justRunThrough guard ---

  @Test
  public void deserializeValueStringJustRunThroughThrowsOnOversizedLength() {
    // STRING justRunThrough: reads length varint then skips — guard catches oversized
    var wc = new BytesContainer();
    VarIntSerializer.write(wc, 1_000_000); // oversized length
    var bytes = wc.fitBytes();
    var rbc = new ReadBytesContainer(bytes);
    assertThrows(
        CorruptedRecordException.class,
        () -> serializer.deserializeValue(
            null, rbc, PropertyTypeInternal.STRING, null, true, null));
  }

  // --- deserializeValue DECIMAL guard ---

  @Test
  public void deserializeValueDecimalThrowsOnOversizedUnscaledLen() {
    // DECIMAL layout: int(scale) + int(unscaledLen) — craft with huge unscaledLen
    var wc = new BytesContainer();
    // Write scale (4 bytes)
    var pos = wc.alloc(4);
    com.jetbrains.youtrackdb.internal.common.serialization.types.IntegerSerializer
        .serializeLiteral(0, wc.bytes, pos);
    // Write unscaledLen = Integer.MAX_VALUE (4 bytes, big-endian)
    pos = wc.alloc(4);
    com.jetbrains.youtrackdb.internal.common.serialization.types.IntegerSerializer
        .serializeLiteral(Integer.MAX_VALUE, wc.bytes, pos);
    var bytes = wc.fitBytes();
    var rbc = new ReadBytesContainer(bytes);
    assertThrows(
        CorruptedRecordException.class,
        () -> serializer.deserializeValue(
            null, rbc, PropertyTypeInternal.DECIMAL, null, false, null));
  }
}
