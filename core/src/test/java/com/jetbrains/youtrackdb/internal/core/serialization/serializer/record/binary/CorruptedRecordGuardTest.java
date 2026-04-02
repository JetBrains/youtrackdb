package com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB.LocalUserCredential;
import com.jetbrains.youtrackdb.api.YouTrackDB.PredefinedLocalRole;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.exception.CorruptedRecordException;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Comprehensive corruption injection tests for the deserialization guard checks. Serializes real
 * entities, corrupts specific bytes in the serialized form, and verifies that deserialization throws
 * {@link CorruptedRecordException} rather than OOM or runaway loops.
 */
public class CorruptedRecordGuardTest {

  private YouTrackDBImpl youTrackDB;
  private DatabaseSessionEmbedded session;

  @Before
  public void setUp() {
    youTrackDB =
        (YouTrackDBImpl) YourTracks.instance(
            "memory:corruptedRecGuardT" + System.nanoTime());
    youTrackDB.create(
        "corruptedRecGuardT",
        DatabaseType.MEMORY,
        new LocalUserCredential("admin", "adminpwd", PredefinedLocalRole.ADMIN));
    session =
        (DatabaseSessionEmbedded) youTrackDB.open(
            "corruptedRecGuardT", "admin", "adminpwd");
  }

  @After
  public void tearDown() {
    try {
      if (session != null && !session.isClosed()) {
        session.close();
      }
    } finally {
      if (youTrackDB != null) {
        try {
          if (youTrackDB.exists("corruptedRecGuardT")) {
            youTrackDB.drop("corruptedRecGuardT");
          }
        } finally {
          youTrackDB.close();
        }
      }
    }
  }

  // --- Header corruption ---

  @Test
  public void corruptHeaderLengthThrowsCorruptedRecordException() {
    // Serialize a simple entity, then corrupt the headerLength varint
    // (byte at index 1, right after the serializer version byte)
    session.begin();
    var serialized = serializeEntity(entity -> {
      entity.setProperty("name", "test");
      entity.setProperty("value", 42);
    });

    // Corrupt headerLength to a huge value by setting the varint bytes
    // Index 1 is the first byte of the serialized record (after version byte at 0)
    // Replace it with a varint encoding a very large number (0xFF 0xFF 0xFF 0xFF 0x07)
    var corrupted = serialized.clone();
    corrupted[1] = (byte) 0xFF;
    if (corrupted.length > 2) {
      corrupted[2] = (byte) 0xFF;
    }
    if (corrupted.length > 3) {
      corrupted[3] = (byte) 0xFF;
    }
    if (corrupted.length > 4) {
      corrupted[4] = (byte) 0xFF;
    }
    if (corrupted.length > 5) {
      corrupted[5] = (byte) 0x07;
    }

    assertThrows(CorruptedRecordException.class, () -> deserializeFull(corrupted));
    session.rollback();
  }

  @Test
  public void negativeHeaderLengthThrowsCorruptedRecordException() {
    // Encode a negative headerLength (zigzag varint for -1 = 0x01)
    session.begin();
    var serialized = serializeEntity(entity -> entity.setProperty("x", 1));

    var corrupted = serialized.clone();
    // zigzag encoding: -1 -> unsigned 1 -> varint byte 0x01
    corrupted[1] = 0x01;

    assertThrows(CorruptedRecordException.class, () -> deserializeFull(corrupted));
    session.rollback();
  }

  // --- Truncated record ---

  @Test
  public void truncatedRecordThrowsException() {
    // Serialize a real entity, then truncate to just a few bytes
    session.begin();
    var serialized = serializeEntity(entity -> {
      entity.setProperty("longString", "A".repeat(100));
      entity.setProperty("number", 999);
    });

    // Keep only version byte + partial header
    var truncated = new byte[3];
    System.arraycopy(serialized, 0, truncated, 0, Math.min(3, serialized.length));

    // A 3-byte truncation leaves only the version byte + partial headerLength varint.
    // The headerLength varint decodes to a value exceeding remaining bytes,
    // triggering the header length guard.
    assertThrows(CorruptedRecordException.class, () -> deserializeFull(truncated));
    session.rollback();
  }

  // --- Valid record with extra bytes succeeds ---

  @Test
  public void validRecordWithTrailingGarbageSucceeds() {
    // A valid serialized record followed by extra bytes should deserialize the
    // valid portion successfully (the deserializer reads only what it needs)
    session.begin();
    var serialized = serializeEntity(entity -> entity.setProperty("key", "value"));

    var padded = new byte[serialized.length + 100];
    System.arraycopy(serialized, 0, padded, 0, serialized.length);
    // Fill trailing bytes with garbage
    for (var i = serialized.length; i < padded.length; i++) {
      padded[i] = (byte) 0xDE;
    }

    var entity = deserializeFull(padded);
    assertEquals("value", entity.getProperty("key"));
    session.rollback();
  }

  // --- End-to-end: corrupt field value size ---

  @Test
  public void corruptFieldValueSizeThrowsException() {
    // Safety-net test: corrupt bytes inside a serialized record and verify that
    // deserialization fails cleanly (no OOM, no silent corruption). The corruption
    // injection is heuristic — the large varint may land on a guarded size field
    // (CorruptedRecordException) or on a non-guarded field entry that causes a
    // BufferUnderflowException. Either way, the deserializer must not allocate
    // unbounded memory or return silently corrupted data.
    session.begin();
    var serialized = serializeEntity(entity -> {
      entity.setProperty("name", "hello");
      entity.setProperty("data", "world");
    });

    // Corrupt bytes in the value area (after the header) to inject a huge varint
    var corrupted = corruptFirstVarIntAfterOffset(serialized, 1, 999_999);

    assertThrows(Exception.class, () -> deserializeFull(corrupted));
    session.rollback();
  }

  // --- Header guard on deserializePartial ---

  @Test
  public void corruptHeaderInDeserializePartialThrowsCorruptedRecordException() {
    session.begin();
    var serialized = serializeEntity(entity -> {
      entity.setProperty("a", 1);
      entity.setProperty("b", "hello");
    });

    var corrupted = serialized.clone();
    // Corrupt headerLength varint to huge value
    corrupted[1] = (byte) 0xFF;
    if (corrupted.length > 2) {
      corrupted[2] = (byte) 0xFF;
    }
    if (corrupted.length > 3) {
      corrupted[3] = (byte) 0xFF;
    }
    if (corrupted.length > 4) {
      corrupted[4] = (byte) 0xFF;
    }
    if (corrupted.length > 5) {
      corrupted[5] = (byte) 0x07;
    }

    assertThrows(
        CorruptedRecordException.class, () -> deserializePartial(corrupted, "a"));
    session.rollback();
  }

  // --- Direct unit tests for header parsing guards ---

  @Test
  public void deserializeThrowsOnNegativeHeaderLength() {
    // Craft a buffer with just a negative varint as headerLength
    session.begin();
    var wc = new BytesContainer();
    VarIntSerializer.write(wc, -5); // negative headerLength
    var bytes = wc.fitBytes();
    var rbc = new ReadBytesContainer(bytes);
    var serializer = new RecordSerializerBinaryV1();
    var entity = (EntityImpl) session.newEntity();
    assertThrows(
        CorruptedRecordException.class,
        () -> serializer.deserialize(session, entity, rbc));
    session.rollback();
  }

  @Test
  public void deserializeThrowsOnOversizedHeaderLength() {
    session.begin();
    var wc = new BytesContainer();
    VarIntSerializer.write(wc, Integer.MAX_VALUE);
    var bytes = wc.fitBytes();
    var rbc = new ReadBytesContainer(bytes);
    var serializer = new RecordSerializerBinaryV1();
    var entity = (EntityImpl) session.newEntity();
    assertThrows(
        CorruptedRecordException.class,
        () -> serializer.deserialize(session, entity, rbc));
    session.rollback();
  }

  @Test
  public void deserializeFieldTypedLoopThrowsOnNegativeHeaderLength() {
    var wc = new BytesContainer();
    VarIntSerializer.write(wc, -1);
    var bytes = wc.fitBytes();
    var rbc = new ReadBytesContainer(bytes);
    var serializer = new RecordSerializerBinaryV1();
    assertThrows(
        CorruptedRecordException.class,
        () -> serializer.deserializeFieldTypedLoopAndReturn(
            null, rbc, "field", null, null));
  }

  @Test
  public void deserializeFieldTypedLoopThrowsOnOversizedHeaderLength() {
    var wc = new BytesContainer();
    VarIntSerializer.write(wc, Integer.MAX_VALUE);
    var bytes = wc.fitBytes();
    var rbc = new ReadBytesContainer(bytes);
    var serializer = new RecordSerializerBinaryV1();
    assertThrows(
        CorruptedRecordException.class,
        () -> serializer.deserializeFieldTypedLoopAndReturn(
            null, rbc, "field", null, null));
  }

  // --- Helpers ---

  private byte[] serializeEntity(EntityConfigurer configurer) {
    var entity = (EntityImpl) session.newEntity();
    configurer.configure(entity);
    return RecordSerializerBinary.INSTANCE.toStream(session, entity);
  }

  private EntityImpl deserializeFull(byte[] serialized) {
    var container = new ReadBytesContainer(serialized, 1);
    var entity = (EntityImpl) session.newEntity();
    var serializer = RecordSerializerBinary.INSTANCE.getSerializer(serialized[0]);
    serializer.deserialize(session, entity, container);
    return entity;
  }

  private EntityImpl deserializePartial(byte[] serialized, String... fields) {
    var container = new ReadBytesContainer(serialized, 1);
    var entity = (EntityImpl) session.newEntity();
    var serializer = RecordSerializerBinary.INSTANCE.getSerializer(serialized[0]);
    serializer.deserializePartial(session, entity, container, fields);
    return entity;
  }

  /**
   * Corrupts the first varint found after the given offset in a serialized byte array. Replaces
   * the varint with a 5-byte encoding of the given large value.
   */
  private byte[] corruptFirstVarIntAfterOffset(byte[] data, int afterOffset, int largeValue) {
    var corrupted = data.clone();
    // Write a large varint at the location of the first varint after afterOffset.
    // This overwrites whatever was there, which may corrupt the rest of the record too —
    // that's the point.
    var wc = new BytesContainer();
    VarIntSerializer.write(wc, largeValue);
    var varintBytes = wc.fitBytes();
    // Find a reasonable position — the headerLength varint starts at afterOffset
    var pos = afterOffset;
    // Skip the headerLength varint first
    while (pos < corrupted.length && (corrupted[pos] & 0x80) != 0) {
      pos++;
    }
    pos++; // skip the last byte of headerLength varint
    // Now pos should be inside the header — overwrite with large varint
    if (pos + varintBytes.length <= corrupted.length) {
      System.arraycopy(varintBytes, 0, corrupted, pos, varintBytes.length);
    }
    return corrupted;
  }

  @FunctionalInterface
  private interface EntityConfigurer {
    void configure(EntityImpl entity);
  }
}
