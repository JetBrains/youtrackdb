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

package com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.common.serialization.types.BinarySerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.BinaryTypeSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.BooleanSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.ByteSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.CharSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.DateSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.DateTimeSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.DecimalSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.DoubleSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.FloatSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.IntegerSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.LongSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.NullSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.ShortSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.StringSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.UTF8Serializer;
import com.jetbrains.youtrackdb.internal.core.exception.StorageException;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.impl.CompactedLinkSerializer;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.impl.LinkSerializer;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.impl.index.CompositeKeySerializer;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.stream.StreamSerializerRID;
import com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.multivalue.v2.MultiValueEntrySerializer;
import org.junit.Test;

/**
 * Direct coverage for {@link BinarySerializerFactory}. The factory is the central
 * registry for binary type serializers — every record write/read goes through
 * {@link BinarySerializerFactory#getObjectSerializer(byte)} or
 * {@link BinarySerializerFactory#getObjectSerializer(PropertyTypeInternal)}.
 *
 * <p>Test surface:
 * <ul>
 *   <li>Format-version constant ({@code CURRENT_BINARY_FORMAT_VERSION = 14}) and the
 *       {@link BinarySerializerFactory#currentBinaryFormatVersion()} accessor.
 *   <li>{@link BinarySerializerFactory#create(int)} happy path — registers the
 *       expected serializers under their canonical IDs and {@link PropertyTypeInternal}
 *       slots.
 *   <li>{@code create(int)} rejection for unsupported versions (StorageException).
 *   <li>{@link BinarySerializerFactory#registerSerializer} duplicate-id rejection.
 *   <li>{@link BinarySerializerFactory#getObjectSerializer(byte)} returns
 *       {@code null} for unknown ids (no exception, just absence).
 * </ul>
 */
public class BinarySerializerFactoryTest {

  // --- Format version constant ---

  @Test
  public void currentFormatVersionAccessorMatchesConstant() {
    assertEquals(14, BinarySerializerFactory.CURRENT_BINARY_FORMAT_VERSION);
    assertEquals(14, BinarySerializerFactory.currentBinaryFormatVersion());
  }

  @Test
  public void typeIdentifierSizeIsOneByte() {
    // Pin: the type-identifier prefix in every serialized payload is exactly 1 byte.
    // A change here shifts every persisted byte in every record file.
    assertEquals(1, BinarySerializerFactory.TYPE_IDENTIFIER_SIZE);
  }

  // --- create() rejection paths ---

  @Test
  public void createRejectsUnsupportedFormatVersion() {
    var ex = assertThrows(
        StorageException.class, () -> BinarySerializerFactory.create(13));
    assertTrue(
        "exception must mention the offending version",
        ex.getMessage() != null && ex.getMessage().contains("13"));
  }

  @Test
  public void createRejectsZeroVersion() {
    assertThrows(StorageException.class, () -> BinarySerializerFactory.create(0));
  }

  @Test
  public void createRejectsNegativeVersion() {
    assertThrows(StorageException.class, () -> BinarySerializerFactory.create(-1));
  }

  @Test
  public void createRejectsFutureVersion() {
    // Pin the rigid validation: the factory does not silently accept unknown future
    // versions. A relaxation here would require a deliberate plan-of-record.
    assertThrows(StorageException.class, () -> BinarySerializerFactory.create(15));
    assertThrows(StorageException.class, () -> BinarySerializerFactory.create(127));
  }

  // --- create() happy path ---

  @Test
  public void createReturnsFactoryWithRegisteredScalarSerializers() {
    var f = BinarySerializerFactory.create(BinarySerializerFactory.CURRENT_BINARY_FORMAT_VERSION);
    assertSame(BooleanSerializer.INSTANCE, f.getObjectSerializer(BooleanSerializer.ID));
    assertSame(IntegerSerializer.INSTANCE, f.getObjectSerializer(IntegerSerializer.ID));
    assertSame(ShortSerializer.INSTANCE, f.getObjectSerializer(ShortSerializer.ID));
    assertSame(LongSerializer.INSTANCE, f.getObjectSerializer(LongSerializer.ID));
    assertSame(FloatSerializer.INSTANCE, f.getObjectSerializer(FloatSerializer.ID));
    assertSame(DoubleSerializer.INSTANCE, f.getObjectSerializer(DoubleSerializer.ID));
    assertSame(DateTimeSerializer.INSTANCE, f.getObjectSerializer(DateTimeSerializer.ID));
    assertSame(StringSerializer.INSTANCE, f.getObjectSerializer(StringSerializer.ID));
    assertSame(ByteSerializer.INSTANCE, f.getObjectSerializer(ByteSerializer.ID));
    assertSame(DateSerializer.INSTANCE, f.getObjectSerializer(DateSerializer.ID));
    assertSame(LinkSerializer.INSTANCE, f.getObjectSerializer(LinkSerializer.ID));
    assertSame(BinaryTypeSerializer.INSTANCE, f.getObjectSerializer(BinaryTypeSerializer.ID));
    assertSame(DecimalSerializer.INSTANCE, f.getObjectSerializer(DecimalSerializer.ID));
    // The remaining six serializers are registered with type=null (id-only lookup):
    // CharSerializer, CompositeKeySerializer, StreamSerializerRID,
    // CompactedLinkSerializer, UTF8Serializer, MultiValueEntrySerializer. A regression
    // that drops any of these registrations would silently break id-based dispatch
    // for that slot — pin them all here so the failure mode is "unit test fails" not
    // "deep NPE in record deserialization three layers down".
    assertSame(CharSerializer.INSTANCE, f.getObjectSerializer(CharSerializer.ID));
    assertSame(CompositeKeySerializer.INSTANCE, f.getObjectSerializer(CompositeKeySerializer.ID));
    assertSame(StreamSerializerRID.INSTANCE, f.getObjectSerializer(StreamSerializerRID.ID));
    assertSame(CompactedLinkSerializer.INSTANCE,
        f.getObjectSerializer(CompactedLinkSerializer.ID));
    assertSame(UTF8Serializer.INSTANCE, f.getObjectSerializer(UTF8Serializer.ID));
    assertSame(MultiValueEntrySerializer.INSTANCE,
        f.getObjectSerializer((byte) MultiValueEntrySerializer.ID));
  }

  @Test
  public void createBindsScalarTypesToTheirSerializers() {
    var f = BinarySerializerFactory.create(BinarySerializerFactory.CURRENT_BINARY_FORMAT_VERSION);
    assertSame(BooleanSerializer.INSTANCE, f.getObjectSerializer(PropertyTypeInternal.BOOLEAN));
    assertSame(IntegerSerializer.INSTANCE, f.getObjectSerializer(PropertyTypeInternal.INTEGER));
    assertSame(ShortSerializer.INSTANCE, f.getObjectSerializer(PropertyTypeInternal.SHORT));
    assertSame(LongSerializer.INSTANCE, f.getObjectSerializer(PropertyTypeInternal.LONG));
    assertSame(FloatSerializer.INSTANCE, f.getObjectSerializer(PropertyTypeInternal.FLOAT));
    assertSame(DoubleSerializer.INSTANCE, f.getObjectSerializer(PropertyTypeInternal.DOUBLE));
    assertSame(DateTimeSerializer.INSTANCE,
        f.getObjectSerializer(PropertyTypeInternal.DATETIME));
    assertSame(StringSerializer.INSTANCE, f.getObjectSerializer(PropertyTypeInternal.STRING));
    assertSame(ByteSerializer.INSTANCE, f.getObjectSerializer(PropertyTypeInternal.BYTE));
    assertSame(DateSerializer.INSTANCE, f.getObjectSerializer(PropertyTypeInternal.DATE));
    assertSame(LinkSerializer.INSTANCE, f.getObjectSerializer(PropertyTypeInternal.LINK));
    assertSame(BinaryTypeSerializer.INSTANCE,
        f.getObjectSerializer(PropertyTypeInternal.BINARY));
    assertSame(DecimalSerializer.INSTANCE,
        f.getObjectSerializer(PropertyTypeInternal.DECIMAL));
    // EMBEDDED is bound to the sentinel MockSerializer — pinned in MockSerializerDeadCodeTest.
    assertSame(MockSerializer.INSTANCE, f.getObjectSerializer(PropertyTypeInternal.EMBEDDED));
  }

  @Test
  public void getObjectSerializerByByteReturnsNullForUnregisteredId() {
    var f = BinarySerializerFactory.create(BinarySerializerFactory.CURRENT_BINARY_FORMAT_VERSION);
    // Pin: lookup for an unregistered id returns null rather than throwing. Callers
    // depend on this for fall-through dispatch logic.
    BinarySerializer<?> result = f.getObjectSerializer((byte) 100);
    assertNull(result);
  }

  @Test
  public void getObjectSerializerByTypeReturnsNullForUnregisteredType() {
    var f = BinarySerializerFactory.create(BinarySerializerFactory.CURRENT_BINARY_FORMAT_VERSION);
    // LINKBAG is registered as a type-id but the factory does not bind it to a
    // serializer in create() — pin the no-binding by-type lookup contract.
    BinarySerializer<?> result = f.getObjectSerializer(PropertyTypeInternal.LINKBAG);
    assertNull(result);
  }

  @Test
  public void nullSerializerIsRegisteredButNotBoundToAType() {
    // NullSerializer.ID = 11. Note: BinarySerializerFactory.create() registers a
    // FRESH NullSerializer instance (`new NullSerializer()`) rather than the
    // singleton `NullSerializer.INSTANCE`. Pin both invariants — the lookup returns
    // a NullSerializer, but it is NOT the singleton.
    var f = BinarySerializerFactory.create(BinarySerializerFactory.CURRENT_BINARY_FORMAT_VERSION);
    var resolved = f.getObjectSerializer(NullSerializer.ID);
    assertNotNull(resolved);
    assertEquals(NullSerializer.class, resolved.getClass());
    // WHEN-FIXED: deferred-cleanup track — change the registration in
    // BinarySerializerFactory.create() to use NullSerializer.INSTANCE for consistency
    // with every other serializer in the factory; flip this assertion to assertSame.
    assertNotSame(
        "today create() registers a fresh NullSerializer rather than the INSTANCE singleton —"
            + " WHEN-FIXED flip to assertSame against NullSerializer.INSTANCE",
        NullSerializer.INSTANCE,
        resolved);
  }

  @Test
  public void multipleCreateInvocationsProduceIndependentFactories() {
    // The factory is intended to be per-database (or per-network-protocol). Pin
    // independence: two invocations of create() build two different objects with
    // their own internal maps. A regression that started caching/sharing would
    // make per-database type-id remappings impossible.
    var a = BinarySerializerFactory.create(BinarySerializerFactory.CURRENT_BINARY_FORMAT_VERSION);
    var b = BinarySerializerFactory.create(BinarySerializerFactory.CURRENT_BINARY_FORMAT_VERSION);
    assertNotNull(a);
    assertNotNull(b);
    assertNotSame("create() must return a fresh factory each time", a, b);
    // Mutating one (registering a new id) must not affect the other.
    var fresh = new IdStub((byte) 99);
    a.registerSerializer(fresh, null);
    assertSame(fresh, a.getObjectSerializer((byte) 99));
    assertNull("b is independent of a", b.getObjectSerializer((byte) 99));
  }

  // --- registerSerializer duplicate-id rejection ---

  @Test
  public void registerSerializerRejectsDuplicateId() {
    var f = BinarySerializerFactory.create(BinarySerializerFactory.CURRENT_BINARY_FORMAT_VERSION);
    // BooleanSerializer.INSTANCE is already registered (id=1); a second registration
    // must be rejected.
    var ex = assertThrows(IllegalArgumentException.class,
        () -> f.registerSerializer(BooleanSerializer.INSTANCE, PropertyTypeInternal.BOOLEAN));
    // Pin the duplicate-id reference AND the rejection wording. Asserting just
    // contains("1") is too loose — many unrelated rewordings would still match.
    var msg = ex.getMessage();
    assertNotNull("rejection message must not be null", msg);
    assertTrue(
        "exception must mention the duplicate id and the rejection: " + msg,
        msg.contains("id 1") && msg.contains("already registered"));
  }

  @Test
  public void registerSerializerRejectsAnotherSerializerWithSameId() {
    var f = BinarySerializerFactory.create(BinarySerializerFactory.CURRENT_BINARY_FORMAT_VERSION);
    // A custom stub with id collide-on-purpose with an existing one.
    var collidingStub = new IdStub((byte) BooleanSerializer.ID);
    assertThrows(
        IllegalArgumentException.class,
        () -> f.registerSerializer(collidingStub, null));
  }

  // --- A test-only stub for duplicate-id rejection ---

  /**
   * Tiny stub implementing only {@code getId()} to drive
   * {@link BinarySerializerFactory#registerSerializer} duplicate-id rejection.
   * Other methods are unused by the rejection path — they throw if invoked, so a
   * regression that exercised them in the rejection path would fail loudly.
   */
  private static final class IdStub implements BinarySerializer<Object> {

    private final byte id;

    private IdStub(byte id) {
      this.id = id;
    }

    @Override
    public byte getId() {
      return id;
    }

    @Override
    public int getObjectSize(BinarySerializerFactory f, Object o, Object... h) {
      throw new UnsupportedOperationException("stub");
    }

    @Override
    public int getObjectSize(BinarySerializerFactory f, byte[] s, int p) {
      throw new UnsupportedOperationException("stub");
    }

    @Override
    public void serialize(Object o, BinarySerializerFactory f, byte[] s, int p, Object... h) {
      throw new UnsupportedOperationException("stub");
    }

    @Override
    public Object deserialize(BinarySerializerFactory f, byte[] s, int p) {
      throw new UnsupportedOperationException("stub");
    }

    @Override
    public boolean isFixedLength() {
      throw new UnsupportedOperationException("stub");
    }

    @Override
    public int getFixedLength() {
      throw new UnsupportedOperationException("stub");
    }

    @Override
    public void serializeNativeObject(Object o, BinarySerializerFactory f, byte[] s, int p,
        Object... h) {
      throw new UnsupportedOperationException("stub");
    }

    @Override
    public Object deserializeNativeObject(BinarySerializerFactory f, byte[] s, int p) {
      throw new UnsupportedOperationException("stub");
    }

    @Override
    public int getObjectSizeNative(BinarySerializerFactory f, byte[] s, int p) {
      throw new UnsupportedOperationException("stub");
    }

    @Override
    public Object preprocess(BinarySerializerFactory f, Object value, Object... h) {
      throw new UnsupportedOperationException("stub");
    }

    @Override
    public void serializeInByteBufferObject(BinarySerializerFactory f, Object o,
        java.nio.ByteBuffer b, Object... h) {
      throw new UnsupportedOperationException("stub");
    }

    @Override
    public Object deserializeFromByteBufferObject(BinarySerializerFactory f,
        java.nio.ByteBuffer b) {
      throw new UnsupportedOperationException("stub");
    }

    @Override
    public Object deserializeFromByteBufferObject(BinarySerializerFactory f, int o,
        java.nio.ByteBuffer b) {
      throw new UnsupportedOperationException("stub");
    }

    @Override
    public int getObjectSizeInByteBuffer(BinarySerializerFactory f, java.nio.ByteBuffer b) {
      throw new UnsupportedOperationException("stub");
    }

    @Override
    public int getObjectSizeInByteBuffer(BinarySerializerFactory f, int o,
        java.nio.ByteBuffer b) {
      throw new UnsupportedOperationException("stub");
    }

    @Override
    public Object deserializeFromByteBufferObject(BinarySerializerFactory f,
        java.nio.ByteBuffer b,
        com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALChanges w,
        int o) {
      throw new UnsupportedOperationException("stub");
    }

    @Override
    public int getObjectSizeInByteBuffer(java.nio.ByteBuffer b,
        com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALChanges w,
        int o) {
      throw new UnsupportedOperationException("stub");
    }
  }
}
