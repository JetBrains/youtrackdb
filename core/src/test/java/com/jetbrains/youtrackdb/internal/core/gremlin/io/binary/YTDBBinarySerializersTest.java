package com.jetbrains.youtrackdb.internal.core.gremlin.io.binary;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.gremlin.YTDBVertexPropertyId;
import com.jetbrains.youtrackdb.internal.core.id.ChangeableRecordId;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.util.ReferenceCounted;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.tinkerpop.gremlin.structure.io.Buffer;
import org.apache.tinkerpop.gremlin.structure.io.binary.DataType;
import org.apache.tinkerpop.gremlin.structure.io.binary.GraphBinaryReader;
import org.apache.tinkerpop.gremlin.structure.io.binary.GraphBinaryWriter;
import org.apache.tinkerpop.gremlin.util.ser.NettyBufferFactory;
import org.apache.tinkerpop.gremlin.util.ser.SerializationException;
import org.junit.After;
import org.junit.Test;

/**
 * Direct tests for the GraphBinary serializers under
 * {@code io.youtrackdb.internal.core.gremlin.io.binary}. These bypass the IoRegistry plumbing
 * to exercise the serializers against a freshly-allocated Netty {@link Buffer} so that the
 * three corner-case branches in {@link YTDBAbstractCustomTypeSerializer} (non-zero
 * {@code custom_type_info}, non-positive value length, value length larger than readable bytes)
 * are reachable. Round-trip + {@code getTypeName} + {@link DataType#CUSTOM} pinning cover the
 * remaining happy-path responsibilities.
 *
 * <p>All Netty {@link ByteBuf}s allocated here are released in {@link #tearDown} to avoid
 * native memory leaks across the test suite.
 */
public class YTDBBinarySerializersTest {

  private static final NettyBufferFactory FACTORY = new NettyBufferFactory();
  private final List<ByteBuf> allocated = new ArrayList<>();

  @After
  public void tearDown() {
    allocated.forEach(ReferenceCounted::release);
    allocated.clear();
  }

  private Buffer fresh() {
    var raw = UnpooledByteBufAllocator.DEFAULT.buffer(64);
    allocated.add(raw);
    return FACTORY.create(raw);
  }

  /**
   * Round-trip a {@link RecordId} through {@link YTDBRecordIdBinarySerializer} —
   * {@code write} then {@code read} must reconstruct the same collection-id /
   * collection-position pair. This exercises {@code doWriteValue}, the type-info-prefix
   * branch in {@code write}, and the {@code doReadValue} reader.
   */
  @Test
  public void recordIdBinarySerializerRoundTripPreservesIdentity() throws IOException {
    var serializer = new YTDBRecordIdBinarySerializer();
    var buffer = fresh();
    var rid = new RecordId(7, 47);

    serializer.write(rid, buffer, new GraphBinaryWriter());
    var decoded = serializer.read(buffer, new GraphBinaryReader());

    assertEquals(rid.getCollectionId(), decoded.getCollectionId());
    assertEquals(rid.getCollectionPosition(), decoded.getCollectionPosition());
  }

  /** {@code getTypeName} is part of the GraphBinary protocol — pin the wire literal. */
  @Test
  public void recordIdBinarySerializerExposesStableTypeName() {
    assertEquals("ytdb.RecordId", new YTDBRecordIdBinarySerializer().getTypeName());
  }

  /** Every custom serializer must declare {@link DataType#CUSTOM}. */
  @Test
  public void recordIdBinarySerializerExposesCustomDataType() {
    assertEquals(DataType.CUSTOM, new YTDBRecordIdBinarySerializer().getDataType());
  }

  /**
   * {@link YTDBChangeableRecordIdBinarySerializer} round-trip — same shape as the
   * immutable {@link RecordId} serializer but writes to a {@link ChangeableRecordId} target.
   */
  @Test
  public void changeableRecordIdBinarySerializerRoundTripPreservesIdentity() throws IOException {
    var serializer = new YTDBChangeableRecordIdBinarySerializer();
    var buffer = fresh();
    var rid = new ChangeableRecordId(3, -42);

    serializer.write(rid, buffer, new GraphBinaryWriter());
    var decoded = serializer.read(buffer, new GraphBinaryReader());

    assertEquals(rid.getCollectionId(), decoded.getCollectionId());
    assertEquals(rid.getCollectionPosition(), decoded.getCollectionPosition());
  }

  @Test
  public void changeableRecordIdBinarySerializerExposesStableTypeName() {
    assertEquals(
        "ytdb.ChangeableRecordId",
        new YTDBChangeableRecordIdBinarySerializer().getTypeName());
  }

  /**
   * {@link YTDBVertexPropertyIdBinarySerializer} round-trip — an embedded property key
   * (UTF-8 string of length N) is round-tripped together with the underlying RID. The
   * serialised length includes 2-byte key length + key bytes + 2-byte collection-id +
   * 8-byte collection-position; the {@code getSerializedLength} branch is also exercised
   * through the implicit length-write. Persistent (positive) collection-position branch.
   */
  @Test
  public void vertexPropertyIdBinarySerializerRoundTripPersistentRid() throws IOException {
    var serializer = new YTDBVertexPropertyIdBinarySerializer();
    var buffer = fresh();
    var id = new YTDBVertexPropertyId(new RecordId(11, 222), "color");

    serializer.write(id, buffer, new GraphBinaryWriter());
    var decoded = serializer.read(buffer, new GraphBinaryReader());

    assertEquals(11, decoded.rid().getCollectionId());
    assertEquals(222, decoded.rid().getCollectionPosition());
    assertEquals("color", decoded.key());
    assertTrue(
        "decoded rid for positive collection-position must be RecordId",
        decoded.rid() instanceof RecordId);
  }

  /**
   * The vertex-property-id reader picks {@link ChangeableRecordId} when the decoded
   * collection-position is negative — covers the {@code collectionPosition < 0} branch in
   * {@code doReadValue}.
   */
  @Test
  public void vertexPropertyIdBinarySerializerRoundTripChangeableRid() throws IOException {
    var serializer = new YTDBVertexPropertyIdBinarySerializer();
    var buffer = fresh();
    var id = new YTDBVertexPropertyId(new ChangeableRecordId(5, -1), "name");

    serializer.write(id, buffer, new GraphBinaryWriter());
    var decoded = serializer.read(buffer, new GraphBinaryReader());

    assertEquals(5, decoded.rid().getCollectionId());
    assertEquals(-1, decoded.rid().getCollectionPosition());
    assertEquals("name", decoded.key());
    assertTrue(
        "decoded rid for negative collection-position must be ChangeableRecordId",
        decoded.rid() instanceof ChangeableRecordId);
  }

  @Test
  public void vertexPropertyIdBinarySerializerExposesStableTypeName() {
    assertEquals(
        "ytdb.VertexPropertyId",
        new YTDBVertexPropertyIdBinarySerializer().getTypeName());
  }

  /**
   * Reading a payload whose 4-byte custom_type_info prefix is non-zero must throw
   * {@link SerializationException}. The serializers deliberately reserve the prefix as
   * "always 0" — this defends against a malformed peer that injects a non-zero prefix to
   * confuse the reader.
   */
  @Test
  public void readRejectsNonZeroCustomTypeInfo() {
    var serializer = new YTDBRecordIdBinarySerializer();
    var raw = Unpooled.buffer(16);
    allocated.add(raw);
    var buffer = FACTORY.create(raw);
    // type-info = 1 (would mean "extra info present"); reader should reject.
    buffer.writeInt(1);

    var thrown = assertThrows(
        IOException.class, () -> serializer.read(buffer, new GraphBinaryReader()));
    assertTrue(thrown instanceof SerializationException);
    assertTrue(
        "expected message to mention custom_type_info but was: " + thrown.getMessage(),
        thrown.getMessage().contains("custom_type_info"));
  }

  /**
   * After the type-info prefix, the value-length integer is also a hard-validation point:
   * {@code valueLength <= 0} signals a malformed payload. (For a non-nullable serializer
   * there is no nullable flag, so the reader hits the value-length branch immediately after
   * the type-info prefix.)
   */
  @Test
  public void readRejectsNonPositiveValueLength() {
    var serializer = new YTDBRecordIdBinarySerializer();
    var raw = Unpooled.buffer(16);
    allocated.add(raw);
    var buffer = FACTORY.create(raw);
    buffer.writeInt(0); // type-info: ok
    buffer.writeInt(0); // value length: invalid (must be > 0)

    var thrown = assertThrows(
        IOException.class, () -> serializer.read(buffer, new GraphBinaryReader()));
    assertTrue(thrown instanceof SerializationException);
    assertTrue(
        "expected message to mention value length but was: " + thrown.getMessage(),
        thrown.getMessage().contains("value length"));
  }

  /**
   * If the declared value length exceeds the buffer's readable bytes the reader must reject
   * the payload (rather than overrun the buffer).
   */
  @Test
  public void readRejectsValueLengthLargerThanReadableBytes() {
    var serializer = new YTDBRecordIdBinarySerializer();
    var raw = Unpooled.buffer(16);
    allocated.add(raw);
    var buffer = FACTORY.create(raw);
    buffer.writeInt(0); // type-info: ok
    buffer.writeInt(1024); // declared value length larger than buffer's remaining content

    var thrown = assertThrows(
        IOException.class, () -> serializer.read(buffer, new GraphBinaryReader()));
    assertTrue(thrown instanceof SerializationException);
    assertTrue(
        "expected message to mention readable bytes but was: " + thrown.getMessage(),
        thrown.getMessage().contains("readable bytes"));
  }

  /**
   * Writing {@code null} via a non-nullable serializer must throw — the
   * {@code YTDBRecordIdBinarySerializer} ctor passes {@code nullable=false}, so the null
   * branch in {@code writeValue} is the rejection-on-null path.
   */
  @Test
  public void writeRejectsNullOnNonNullableSerializer() throws IOException {
    var serializer = new YTDBRecordIdBinarySerializer();
    var buffer = fresh();

    var thrown = assertThrows(
        SerializationException.class,
        () -> serializer.write(null, buffer, new GraphBinaryWriter()));
    assertTrue(
        "expected message to mention nullable but was: " + thrown.getMessage(),
        thrown.getMessage().contains("nullable"));
  }

  /**
   * A nullable variant of {@link YTDBAbstractCustomTypeSerializer} must accept {@code null}
   * by writing only the null-flag byte and reading it back as {@code null}. The shipped
   * serializers are all non-nullable; we instantiate a tiny subclass here purely to exercise
   * the {@code nullable == true} branches in {@code readValue}/{@code writeValue}.
   */
  @Test
  public void nullableSerializerRoundTripsNullAsNull() throws IOException {
    var serializer = new NullableMarkerSerializer();
    var buffer = fresh();
    serializer.write(null, buffer, new GraphBinaryWriter());

    var decoded = serializer.read(buffer, new GraphBinaryReader());
    assertNull("null on a nullable serializer must round-trip as null", decoded);
  }

  /**
   * The nullable subclass also round-trips a non-null value — pin the
   * {@code writeValueFlagNone} → {@code readValue(nullable=true) non-null} branch.
   */
  @Test
  public void nullableSerializerRoundTripsValue() throws IOException {
    var serializer = new NullableMarkerSerializer();
    var buffer = fresh();
    serializer.write(new byte[] {1, 2, 3}, buffer, new GraphBinaryWriter());

    var decoded = serializer.read(buffer, new GraphBinaryReader());
    assertNotNull(decoded);
    assertArrayEquals(new byte[] {1, 2, 3}, decoded);
  }

  /**
   * Minimal {@link YTDBAbstractCustomTypeSerializer} with {@code nullable == true}, used only
   * to drive the nullable-branch coverage. Wire format: 4-byte length-prefix followed by
   * {@code length} raw bytes.
   */
  private static final class NullableMarkerSerializer
      extends YTDBAbstractCustomTypeSerializer<byte[]> {

    NullableMarkerSerializer() {
      super(true);
    }

    @Override
    protected byte[] doReadValue(Buffer buffer, GraphBinaryReader context) {
      var length = buffer.readInt();
      var bytes = new byte[length];
      buffer.readBytes(bytes);
      return bytes;
    }

    @Override
    protected Map<String, Object> initWriteContextMap(byte[] value) {
      return Map.of("len", value.length);
    }

    @Override
    protected int getSerializedLength(byte[] value, Map<String, Object> context) {
      // 4-byte explicit length prefix + payload bytes
      return Integer.BYTES + (Integer) context.get("len");
    }

    @Override
    protected void doWriteValue(byte[] value, Buffer buffer, GraphBinaryWriter context,
        Map<String, Object> contextMap) {
      buffer.writeInt(value.length);
      buffer.writeBytes(value);
    }

    @Override
    public String getTypeName() {
      return "test.NullableMarker";
    }
  }
}
