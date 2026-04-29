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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded.ATTRIBUTES;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.db.record.ridbag.LinkBag;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;

/**
 * Round-trip and corner-case coverage for {@link EntitySerializerDelta}, the binary
 * delta serializer used by the embedded transaction transport for record-update
 * propagation.
 *
 * <p>The pre-existing {@code EntitySerializerDeltaTest} (in
 * {@code core/record/impl}) covers the happy path for every collection / link /
 * embedded combination with end-to-end round-trips. This class is the targeted
 * complement: it fills the corner cases called out in the plan
 * (type-changed property, removed property, replaced RID, delta-of-delta) and
 * pins the wire-format constants and low-level helpers that the existing test
 * does not reach directly:
 *
 * <ul>
 *   <li>The {@code (-2, -2)} sentinel encoding used by
 *       {@link EntitySerializerDelta#writeOptimizedLink} /
 *       {@link EntitySerializerDelta#readOptimizedLink} for null links.</li>
 *   <li>The {@code mode_byte == 2} branch of {@code readLinkBag} /
 *       {@code readLinkSet} that wraps a stored B-tree pointer instead of an
 *       in-band RID list — exercised via the public {@code deserializeValue}
 *       dispatch with crafted bytes carrying an invalid pointer so the
 *       {@code IllegalStateException} guard fires.</li>
 *   <li>The wire-format constants {@code DELTA_RECORD_TYPE},
 *       {@code CREATED}, {@code REPLACED}, {@code CHANGED}, {@code REMOVED}
 *       so a regression that renumbers them fails loudly.</li>
 *   <li>The {@code writeNullableType(null)} {@code -1} sentinel and round-trip
 *       through {@code readNullableType}.</li>
 *   <li>The full-form {@code serialize} / {@code deserialize} entry points
 *       (the existing test focuses on {@code serializeDelta} /
 *       {@code deserializeDelta}).</li>
 * </ul>
 *
 * <p><b>Two-tier discipline.</b> Tier-1 tests drive
 * {@link EntitySerializerDelta#serializeValue} and
 * {@link EntitySerializerDelta#deserializeValue} (and the public static
 * helpers) directly against a fresh {@link BytesContainer}, with hex
 * byte-shape pins where the encoding is short and unambiguous. Tier-2 tests
 * round-trip through {@link EntitySerializerDelta#serialize} (full-form) and
 * {@link EntitySerializerDelta#serializeDelta} / {@code deserializeDelta}
 * (delta-form) at the public API to confirm dispatch and header writing
 * stay aligned end-to-end.
 *
 * <p><b>Timezone discipline.</b> {@code @Before} forces the storage timezone
 * to GMT so any DATE round-trip pin is deterministic regardless of the JVM's
 * default timezone (CET on most CI workers, GMT on others) — see the same
 * note in {@link RecordSerializerBinaryV1SimpleTypeRoundTripTest}.
 */
public class EntitySerializerDeltaRoundTripTest extends DbTestBase {

  private static final HexFormat HEX = HexFormat.of();

  private static final String TARGET_CLASS = "Target";
  private static final String EMBEDDED_CLASS = "EmbeddedTarget";
  private static final String PEER_CLASS = "Peer";

  private EntitySerializerDelta delta;

  @Before
  public void prepareSchemaAndSerializer() {
    delta = EntitySerializerDelta.instance();

    // Force a deterministic storage timezone so DATE round-trips do not depend on the
    // JVM default (CET on most CI hosts, GMT on others); see the equivalent note in
    // RecordSerializerBinaryV1SimpleTypeRoundTripTest.
    session.set(ATTRIBUTES.TIMEZONE, "GMT");

    // DbTestBase drops and re-creates the in-memory database per test method; each
    // @Before re-creates the schema from scratch.
    var schema = session.getMetadata().getSchema();
    var target = schema.getOrCreateClass(TARGET_CLASS);
    target.createProperty("schemaStr", PropertyType.STRING);
    target.createProperty("schemaInt", PropertyType.INTEGER);
    schema.getOrCreateClass(PEER_CLASS);
    var embedded = schema.getOrCreateClass(EMBEDDED_CLASS);
    embedded.setAbstract(true);
  }

  // ============================================================================
  // === Constants — wire-format markers must not silently shift ================
  // ============================================================================

  /**
   * The singleton accessor must always return the same instance. A regression that
   * silently recreated the singleton per call would defeat its purpose and would also
   * be invisible to a round-trip-only test.
   */
  @Test
  public void instanceReturnsSingleton() {
    var first = EntitySerializerDelta.instance();
    var second = EntitySerializerDelta.instance();
    assertSame(first, second);
  }

  /**
   * The four single-byte change-type markers are part of the delta wire format. Any
   * renumbering would silently corrupt deltas already in flight between transaction
   * peers; pin the values so the change is forced through this assertion.
   */
  @Test
  public void changeMarkerByteValuesAreStable() {
    assertEquals((byte) 1, EntitySerializerDelta.CREATED);
    assertEquals((byte) 2, EntitySerializerDelta.REPLACED);
    assertEquals((byte) 3, EntitySerializerDelta.CHANGED);
    assertEquals((byte) 4, EntitySerializerDelta.REMOVED);
  }

  /**
   * {@code DELTA_RECORD_TYPE = 10} is the public marker used by transaction transport
   * to recognise delta records on the wire; pin it so a renumbering forces a code
   * review.
   */
  @Test
  public void deltaRecordTypeConstantIsTen() {
    assertEquals((byte) 10, EntitySerializerDelta.DELTA_RECORD_TYPE);
  }

  // ============================================================================
  // === Tier 1: writeNullableType / readNullableType direct pins ===============
  // ============================================================================

  /**
   * {@code writeNullableType(null)} writes a single {@code -1} (0xFF) sentinel byte;
   * {@code readNullableType} reads it back as null. The encoding is the per-property
   * type marker used in every delta entry, so the byte value must not drift.
   */
  @Test
  public void writeNullableTypeNullEncodesAsMinusOneSentinel() {
    var bytes = new BytesContainer();
    EntitySerializerDelta.writeNullableType(bytes, null);
    assertEquals("ff", HEX.formatHex(bytes.fitBytes()));

    var reread = new BytesContainer(bytes.fitBytes());
    assertNull(EntitySerializerDelta.readNullableType(reread));
  }

  /**
   * For a non-null type the encoding is {@code (byte) type.getId()}; pin the STRING
   * mapping (id=7 → 0x07) and the round-trip through {@code readNullableType}.
   */
  @Test
  public void writeNullableTypeStringEncodesAsIdSeven() {
    var bytes = new BytesContainer();
    EntitySerializerDelta.writeNullableType(bytes, PropertyTypeInternal.STRING);
    assertEquals("07", HEX.formatHex(bytes.fitBytes()));

    var reread = new BytesContainer(bytes.fitBytes());
    assertEquals(PropertyTypeInternal.STRING, EntitySerializerDelta.readNullableType(reread));
  }

  /**
   * Pin a sample of integer-family ids (INTEGER=1, LONG=3, BOOLEAN=0, DECIMAL=21) so a
   * regression that renumbered any of them in {@link PropertyTypeInternal} would
   * surface here. The wire format is index-by-id so any drift breaks compatibility.
   */
  @Test
  public void writeNullableTypeIdMappingsAreStable() {
    assertEquals("00", encodeNullableType(PropertyTypeInternal.BOOLEAN));
    assertEquals("01", encodeNullableType(PropertyTypeInternal.INTEGER));
    assertEquals("03", encodeNullableType(PropertyTypeInternal.LONG));
    assertEquals("07", encodeNullableType(PropertyTypeInternal.STRING));
    assertEquals("0d", encodeNullableType(PropertyTypeInternal.LINK));
    assertEquals("0e", encodeNullableType(PropertyTypeInternal.LINKLIST));
    assertEquals("15", encodeNullableType(PropertyTypeInternal.DECIMAL));
    assertEquals("16", encodeNullableType(PropertyTypeInternal.LINKBAG));
  }

  // ============================================================================
  // === Tier 1: writeOptimizedLink / readOptimizedLink direct pins =============
  // ============================================================================

  /**
   * A null link encodes as two consecutive {@code varint(zigzag(-2))} = {@code 03 03}
   * sentinels; {@code readOptimizedLink} returns null when both halves match. Pinning
   * both directions catches any future "use a different sentinel" regression.
   */
  @Test
  public void writeOptimizedLinkNullEncodesAsTwoZigzagThreeSentinels() {
    var bytes = new BytesContainer();
    EntitySerializerDelta.writeOptimizedLink(session, bytes, null);
    assertEquals("0303", HEX.formatHex(bytes.fitBytes()));

    var reread = new BytesContainer(bytes.fitBytes());
    assertNull(EntitySerializerDelta.readOptimizedLink(session, reread));
  }

  /**
   * Persistent RID at #10:0 encodes as {@code 14 00} (cluster=10 → varint 0x14,
   * position=0 → varint 0x00). Round-trip through {@code readOptimizedLink} must
   * preserve identity. The non-persistent refresh branch is bypassed because
   * {@code (10, 0).isPersistent()} is true.
   */
  @Test
  public void writeOptimizedLinkPersistentRidEncodesAsTwoSingleByteVarints() {
    var rid = new RecordId(10, 0);
    var bytes = new BytesContainer();
    EntitySerializerDelta.writeOptimizedLink(session, bytes, rid);
    assertEquals("1400", HEX.formatHex(bytes.fitBytes()));

    var reread = new BytesContainer(bytes.fitBytes());
    var decoded = EntitySerializerDelta.readOptimizedLink(session, reread);
    assertEquals(rid, decoded);
  }

  /**
   * Forge the null-sentinel manually by emitting {@code 03 03} and confirm
   * {@code readOptimizedLink} round-trips back to null without consulting any
   * session machinery — the early-return guard at the top of the method must fire
   * before the {@code refreshRid} branch is reached.
   */
  @Test
  public void readOptimizedLinkRecognisesSentinelByteShapeAsNull() {
    var bytes = new BytesContainer(new byte[] {0x03, 0x03});
    assertNull(EntitySerializerDelta.readOptimizedLink(session, bytes));
  }

  // ============================================================================
  // === Tier 1: serializeValue / deserializeValue dispatch round-trips =========
  // ============================================================================

  /**
   * INTEGER round-trip via the public static {@code serializeValue}/instance
   * {@code deserializeValue}. Pins the dispatch + the zig-zag varint encoding chain
   * (covered separately by {@link VarIntSerializerTest}, but reaffirmed here so a
   * regression localised to the delta dispatch table is caught directly).
   */
  @Test
  public void integerRoundTripsViaValueDispatch() {
    runInTx(() -> {
      var bytes = new BytesContainer();
      EntitySerializerDelta.serializeValue(session, bytes, 42, PropertyTypeInternal.INTEGER, null);
      var reread = new BytesContainer(bytes.fitBytes());
      var decoded = delta.deserializeValue(session, reread, PropertyTypeInternal.INTEGER, null);
      assertEquals(42, decoded);
    });
  }

  /** LONG dispatch — pins the {@code (Number) value).longValue()} branch. */
  @Test
  public void longRoundTripsViaValueDispatch() {
    runInTx(() -> {
      var bytes = new BytesContainer();
      EntitySerializerDelta.serializeValue(session, bytes, 1234567890123L,
          PropertyTypeInternal.LONG, null);
      var decoded = delta.deserializeValue(session,
          new BytesContainer(bytes.fitBytes()), PropertyTypeInternal.LONG, null);
      assertEquals(1234567890123L, decoded);
    });
  }

  /** SHORT dispatch — same Number branch but a different read path on the way back. */
  @Test
  public void shortRoundTripsViaValueDispatch() {
    runInTx(() -> {
      var bytes = new BytesContainer();
      EntitySerializerDelta.serializeValue(session, bytes, (short) -7,
          PropertyTypeInternal.SHORT, null);
      var decoded = delta.deserializeValue(session,
          new BytesContainer(bytes.fitBytes()), PropertyTypeInternal.SHORT, null);
      assertEquals((short) -7, decoded);
    });
  }

  /**
   * DOUBLE dispatch via {@link Double#doubleToLongBits} → 8 fixed bytes. Use a
   * non-trivial value plus -0.0 + NaN + ±∞ to confirm the bit-exact round-trip
   * (compareTo-style equality would mask -0.0 vs +0.0 drift; raw bit equality is the
   * stronger pin).
   */
  @Test
  public void doubleRoundTripsViaValueDispatchPreservesBitPattern() {
    runInTx(() -> {
      double[] values = {0.0d, 1.5d, -0.0d, Double.NaN, Double.POSITIVE_INFINITY,
          Double.NEGATIVE_INFINITY, Double.MIN_VALUE, Double.MAX_VALUE};
      for (var v : values) {
        var bytes = new BytesContainer();
        EntitySerializerDelta.serializeValue(session, bytes, v, PropertyTypeInternal.DOUBLE, null);
        var decoded = (Double) delta.deserializeValue(session,
            new BytesContainer(bytes.fitBytes()), PropertyTypeInternal.DOUBLE, null);
        assertEquals("bit-exact round-trip for " + v,
            Double.doubleToLongBits(v), Double.doubleToLongBits(decoded));
      }
    });
  }

  /** FLOAT dispatch via {@link Float#floatToIntBits} — analogous to the DOUBLE pin. */
  @Test
  public void floatRoundTripsViaValueDispatchPreservesBitPattern() {
    runInTx(() -> {
      float[] values = {0.0f, 1.5f, -0.0f, Float.NaN, Float.POSITIVE_INFINITY,
          Float.NEGATIVE_INFINITY, Float.MIN_VALUE, Float.MAX_VALUE};
      for (var v : values) {
        var bytes = new BytesContainer();
        EntitySerializerDelta.serializeValue(session, bytes, v, PropertyTypeInternal.FLOAT, null);
        var decoded = (Float) delta.deserializeValue(session,
            new BytesContainer(bytes.fitBytes()), PropertyTypeInternal.FLOAT, null);
        assertEquals("bit-exact round-trip for " + v,
            Float.floatToIntBits(v), Float.floatToIntBits(decoded));
      }
    });
  }

  /** BOOLEAN dispatch — single-byte 0x01/0x00 pin. */
  @Test
  public void booleanRoundTripsViaValueDispatchAsSingleByte() {
    runInTx(() -> {
      var bytesTrue = new BytesContainer();
      EntitySerializerDelta.serializeValue(session, bytesTrue, Boolean.TRUE,
          PropertyTypeInternal.BOOLEAN, null);
      assertArrayEquals(new byte[] {0x01}, bytesTrue.fitBytes());
      assertEquals(Boolean.TRUE, delta.deserializeValue(session,
          new BytesContainer(bytesTrue.fitBytes()), PropertyTypeInternal.BOOLEAN, null));

      var bytesFalse = new BytesContainer();
      EntitySerializerDelta.serializeValue(session, bytesFalse, Boolean.FALSE,
          PropertyTypeInternal.BOOLEAN, null);
      assertArrayEquals(new byte[] {0x00}, bytesFalse.fitBytes());
      assertEquals(Boolean.FALSE, delta.deserializeValue(session,
          new BytesContainer(bytesFalse.fitBytes()), PropertyTypeInternal.BOOLEAN, null));
    });
  }

  /** BYTE dispatch — single-byte raw write. */
  @Test
  public void byteRoundTripsViaValueDispatch() {
    runInTx(() -> {
      var bytes = new BytesContainer();
      EntitySerializerDelta.serializeValue(session, bytes, (byte) -42,
          PropertyTypeInternal.BYTE, null);
      assertArrayEquals(new byte[] {(byte) -42}, bytes.fitBytes());
      assertEquals((byte) -42, delta.deserializeValue(session,
          new BytesContainer(bytes.fitBytes()), PropertyTypeInternal.BYTE, null));
    });
  }

  /** STRING dispatch round-trip with multibyte content. */
  @Test
  public void stringRoundTripsViaValueDispatchPreservesUtf8() {
    runInTx(() -> {
      var input = "Привет, 🌍 — мир!";
      var bytes = new BytesContainer();
      EntitySerializerDelta.serializeValue(session, bytes, input,
          PropertyTypeInternal.STRING, null);
      var decoded = (String) delta.deserializeValue(session,
          new BytesContainer(bytes.fitBytes()), PropertyTypeInternal.STRING, null);
      assertEquals(input, decoded);
    });
  }

  /** BINARY dispatch — pins varint length + raw bytes copy through. */
  @Test
  public void binaryRoundTripsViaValueDispatch() {
    runInTx(() -> {
      var input = new byte[] {(byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF, 0x00, 0x7F};
      var bytes = new BytesContainer();
      EntitySerializerDelta.serializeValue(session, bytes, input,
          PropertyTypeInternal.BINARY, null);
      var decoded = (byte[]) delta.deserializeValue(session,
          new BytesContainer(bytes.fitBytes()), PropertyTypeInternal.BINARY, null);
      assertArrayEquals(input, decoded);
    });
  }

  /**
   * DECIMAL dispatch round-trip — covers the {@code DecimalSerializer} integration
   * branch and the read-side advance via {@code bytes.skip(getObjectSize())}. Use a
   * scale-1 value so the unscaled-bytes path is non-trivial.
   */
  @Test
  public void decimalRoundTripsViaValueDispatchPreservesScale() {
    runInTx(() -> {
      var input = new BigDecimal("3.14");
      var bytes = new BytesContainer();
      EntitySerializerDelta.serializeValue(session, bytes, input,
          PropertyTypeInternal.DECIMAL, null);
      var decoded = (BigDecimal) delta.deserializeValue(session,
          new BytesContainer(bytes.fitBytes()), PropertyTypeInternal.DECIMAL, null);
      assertEquals(input, decoded);
      assertEquals(input.scale(), decoded.scale());
    });
  }

  /**
   * DATETIME dispatch from a raw {@code Long} (the polymorphic branch
   * {@code value instanceof Long longVal}). Pins the alternative encoding path that
   * does not allocate a {@link Date}.
   */
  @Test
  public void datetimeFromLongRoundTripsViaValueDispatch() {
    runInTx(() -> {
      long when = 1_700_000_000_000L;
      var bytes = new BytesContainer();
      EntitySerializerDelta.serializeValue(session, bytes, when,
          PropertyTypeInternal.DATETIME, null);
      var decoded = (Date) delta.deserializeValue(session,
          new BytesContainer(bytes.fitBytes()), PropertyTypeInternal.DATETIME, null);
      assertEquals(when, decoded.getTime());
    });
  }

  /** DATETIME dispatch from a {@link Date} value — the {@code (Date) value} branch. */
  @Test
  public void datetimeFromDateRoundTripsViaValueDispatch() {
    runInTx(() -> {
      var input = new Date(1_700_000_000_000L);
      var bytes = new BytesContainer();
      EntitySerializerDelta.serializeValue(session, bytes, input,
          PropertyTypeInternal.DATETIME, null);
      var decoded = (Date) delta.deserializeValue(session,
          new BytesContainer(bytes.fitBytes()), PropertyTypeInternal.DATETIME, null);
      assertEquals(input, decoded);
    });
  }

  /**
   * DATE dispatch round-trip with the storage timezone forced to GMT so the
   * {@code convertDayToTimezone} call is the identity transform. The encoded value
   * is the integer day count since the epoch.
   */
  @Test
  public void dateRoundTripsViaValueDispatchUnderGmtTimezone() {
    runInTx(() -> {
      var input = new Date(86_400_000L); // exactly day 1 since epoch, GMT-anchored
      var bytes = new BytesContainer();
      EntitySerializerDelta.serializeValue(session, bytes, input,
          PropertyTypeInternal.DATE, null);
      var decoded = (Date) delta.deserializeValue(session,
          new BytesContainer(bytes.fitBytes()), PropertyTypeInternal.DATE, null);
      assertEquals(input, decoded);
    });
  }

  /** DATE dispatch from a raw {@code Long} value (parallel to the DATETIME variant). */
  @Test
  public void dateFromLongRoundTripsViaValueDispatch() {
    runInTx(() -> {
      long whenMillis = 86_400_000L;
      var bytes = new BytesContainer();
      EntitySerializerDelta.serializeValue(session, bytes, whenMillis,
          PropertyTypeInternal.DATE, null);
      var decoded = (Date) delta.deserializeValue(session,
          new BytesContainer(bytes.fitBytes()), PropertyTypeInternal.DATE, null);
      assertEquals(whenMillis, decoded.getTime());
    });
  }

  // ============================================================================
  // === Tier 2: full-form serialize / deserialize ==============================
  // ============================================================================

  /**
   * Empty schemaless entity full-form round-trip. The deserialised entity must carry
   * no user properties; the class name resolves to {@code Entity.DEFAULT_CLASS_NAME}
   * (which {@link com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded#newEntity()}
   * assigns and the serialiser then writes through). Pins the property-less round-trip
   * shape — the entry count varint encodes 0 and the deserialise loop skips the body.
   */
  @Test
  public void emptyEntityFullFormRoundTrips() {
    runInTx(() -> {
      var entity = (EntityImpl) session.newEntity();
      var defaultClassName = entity.getSchemaClassName();
      var encoded = EntitySerializerDelta.serialize(session, entity);

      var rebuilt = (EntityImpl) session.newEntity();
      delta.deserialize(session, encoded, rebuilt);
      assertEquals(0, rebuilt.getPropertiesCount());
      assertEquals(defaultClassName, rebuilt.getSchemaClassName());
    });
  }

  /**
   * Entity with class but no properties full-form round-trip. The class name must
   * survive the round-trip via the {@code writeString} / {@code readString} pair in
   * {@code serializeClass}.
   */
  @Test
  public void entityWithClassNameOnlyFullFormRoundTrips() {
    runInTx(() -> {
      var entity = (EntityImpl) session.newEntity(TARGET_CLASS);
      var encoded = EntitySerializerDelta.serialize(session, entity);

      var rebuilt = (EntityImpl) session.newEntity();
      delta.deserialize(session, encoded, rebuilt);
      assertEquals(TARGET_CLASS, rebuilt.getSchemaClassName());
    });
  }

  /**
   * Multi-property full-form round-trip with a mix of explicitly-typed and
   * value-derived properties plus a null property — exercises the
   * {@code value != null ? writeNullableType(type) + serializeValue : writeNullableType(null)}
   * branch in {@code serialize} and the corresponding {@code if (type == null) value = null}
   * branch in {@code deserialize}.
   */
  @Test
  public void entityWithSimpleAndNullPropertiesFullFormRoundTrips() {
    runInTx(() -> {
      var entity = (EntityImpl) session.newEntity(TARGET_CLASS);
      entity.setProperty("schemaStr", "hello");
      entity.setProperty("schemaInt", 42);
      entity.setProperty("dynBool", Boolean.TRUE);
      entity.setProperty("dynNull", null);

      var encoded = EntitySerializerDelta.serialize(session, entity);
      var rebuilt = (EntityImpl) session.newEntity();
      delta.deserialize(session, encoded, rebuilt);
      assertEquals(TARGET_CLASS, rebuilt.getSchemaClassName());
      assertEquals("hello", rebuilt.getProperty("schemaStr"));
      assertEquals(Integer.valueOf(42), rebuilt.getProperty("schemaInt"));
      assertEquals(Boolean.TRUE, rebuilt.getProperty("dynBool"));
      assertTrue(rebuilt.hasProperty("dynNull"));
      assertNull(rebuilt.getProperty("dynNull"));
    });
  }

  /**
   * Schemaless entity with a value-only property exercises the
   * {@code getFieldType} value-derivation fallback (entry.type and entry.property
   * both null → {@code PropertyTypeInternal.getTypeByValue(value)}). The full-form
   * round-trip must still preserve the integer value because the type is reconstructed
   * from the value before serialisation.
   */
  @Test
  public void schemalessEntityRoundTripsViaTypeByValueFallback() {
    runInTx(() -> {
      var entity = (EntityImpl) session.newEntity();
      entity.setProperty("dynInt", 99);
      entity.setProperty("dynStr", "abc");

      var encoded = EntitySerializerDelta.serialize(session, entity);
      var rebuilt = (EntityImpl) session.newEntity();
      delta.deserialize(session, encoded, rebuilt);
      assertEquals(Integer.valueOf(99), rebuilt.getProperty("dynInt"));
      assertEquals("abc", rebuilt.getProperty("dynStr"));
    });
  }

  // ============================================================================
  // === Plan-mandated delta scenarios ==========================================
  // ============================================================================

  /**
   * Type-changed property: a property that initially holds a STRING value is overwritten
   * with an INTEGER value within the same transaction. {@code serializeDelta} must emit
   * the new type and value; {@code deserializeDelta} must then store the integer at the
   * same property name. Pins the contract that delta encoding carries the new effective
   * type per change record.
   */
  @Test
  public void typeChangedPropertyRoundTripsViaDelta() {
    var startRid = persistEntity(e -> {
      e.setProperty("dynProp", "originalString");
    });

    session.begin();
    var loaded = session.<EntityImpl>load(startRid);
    loaded.removeProperty("dynProp");
    loaded.setProperty("dynProp", 1234);
    var deltaBytes = EntitySerializerDelta.serializeDelta(session, loaded);
    session.rollback();

    session.begin();
    var target = session.<EntityImpl>load(startRid);
    delta.deserializeDelta(session, deltaBytes, target);
    Object value = target.getProperty("dynProp");
    assertNotNull(value);
    assertEquals(Integer.class, value.getClass());
    assertEquals(Integer.valueOf(1234), value);
    session.rollback();
  }

  /**
   * Replaced LINK delta: a persisted property holds a link to peer A, gets reassigned
   * to peer B inside a transaction, the delta is serialised and then applied to a
   * freshly-loaded copy of the original. The rebuilt link must match peer B.
   */
  @Test
  public void replacedRidLinkRoundTripsViaDelta() {
    RID peerA = persistPeer("alpha");
    RID peerB = persistPeer("beta");
    var startRid = persistEntity(e -> {
      e.setProperty("link", peerA, PropertyType.LINK);
    });

    session.begin();
    var loaded = session.<EntityImpl>load(startRid);
    loaded.setProperty("link", peerB, PropertyType.LINK);
    var deltaBytes = EntitySerializerDelta.serializeDelta(session, loaded);
    session.rollback();

    session.begin();
    var target = session.<EntityImpl>load(startRid);
    delta.deserializeDelta(session, deltaBytes, target);
    Identifiable rebuilt = target.getProperty("link");
    assertEquals(peerB, rebuilt.getIdentity());
    session.rollback();
  }

  /**
   * Removed-property delta: a property present on disk is removed in a transaction and
   * the resulting delta must drop the property when applied to a fresh load. Pins the
   * REMOVED change-marker dispatch in {@code serializeDelta} and the matching
   * {@code removePropertyInternal} branch in {@code deserializeDelta}.
   */
  @Test
  public void removedPropertyDeltaRoundTrips() {
    var startRid = persistEntity(e -> {
      e.setProperty("schemaStr", "to-remove");
      e.setProperty("schemaInt", 7);
    });

    session.begin();
    var loaded = session.<EntityImpl>load(startRid);
    loaded.removeProperty("schemaStr");
    var deltaBytes = EntitySerializerDelta.serializeDelta(session, loaded);
    session.rollback();

    session.begin();
    var target = session.<EntityImpl>load(startRid);
    assertEquals("to-remove", target.getProperty("schemaStr"));
    delta.deserializeDelta(session, deltaBytes, target);
    assertFalse(target.hasProperty("schemaStr"));
    assertEquals(Integer.valueOf(7), target.getProperty("schemaInt"));
    session.rollback();
  }

  /**
   * Delta-of-delta: apply a first delta, then mutate the freshly-rebuilt entity in a
   * second transaction, serialise that delta, and apply it on top. The cumulative
   * application must arrive at the same state as a single transaction would have. This
   * exercises the {@code REPLACED} dispatch on a property already touched by an earlier
   * delta (the entry's tx-state must reset between transactions).
   */
  @Test
  public void deltaOfDeltaAppliesCumulatively() {
    var startRid = persistEntity(e -> {
      e.setProperty("schemaStr", "v0");
      e.setProperty("schemaInt", 0);
    });

    // First transaction: schemaStr v0 → v1
    session.begin();
    var first = session.<EntityImpl>load(startRid);
    first.setProperty("schemaStr", "v1");
    var firstDelta = EntitySerializerDelta.serializeDelta(session, first);
    session.commit();

    // Second transaction: schemaStr v1 → v2, schemaInt 0 → 99
    session.begin();
    var second = session.<EntityImpl>load(startRid);
    second.setProperty("schemaStr", "v2");
    second.setProperty("schemaInt", 99);
    var secondDelta = EntitySerializerDelta.serializeDelta(session, second);
    session.rollback();

    // Apply first then second delta on a fresh copy outside a transaction context that
    // owns those mutations — the cumulative state must match the in-transaction one.
    session.begin();
    var rebuilt = session.<EntityImpl>load(startRid);
    delta.deserializeDelta(session, firstDelta, rebuilt);
    assertEquals("v1", rebuilt.getProperty("schemaStr"));
    assertEquals(Integer.valueOf(0), rebuilt.getProperty("schemaInt"));
    delta.deserializeDelta(session, secondDelta, rebuilt);
    assertEquals("v2", rebuilt.getProperty("schemaStr"));
    assertEquals(Integer.valueOf(99), rebuilt.getProperty("schemaInt"));
    session.rollback();
  }

  /**
   * Nested-embedded delta where only the inner property changed: the outer entity is
   * untouched, but the embedded entity has one property mutated. The serialised delta
   * must propagate the inner property's new value when applied to a fresh copy.
   */
  @Test
  public void nestedEmbeddedInnerPropertyChangeDeltaRoundTrips() {
    var startRid = persistEntity(e -> {
      var nested = (EntityImpl) session.newEmbeddedEntity(EMBEDDED_CLASS);
      nested.setProperty("inner", "before");
      e.setProperty("nest", nested, PropertyType.EMBEDDED);
    });

    session.begin();
    var loaded = session.<EntityImpl>load(startRid);
    EntityImpl nested = loaded.getProperty("nest");
    nested.setProperty("inner", "after");
    loaded.setProperty("nest", nested, PropertyType.EMBEDDED);
    var deltaBytes = EntitySerializerDelta.serializeDelta(session, loaded);
    session.rollback();

    session.begin();
    var target = session.<EntityImpl>load(startRid);
    delta.deserializeDelta(session, deltaBytes, target);
    EntityImpl rebuilt = target.getProperty("nest");
    assertEquals("after", rebuilt.getProperty("inner"));
    session.rollback();
  }

  /**
   * Embedded list element add then commit-or-apply: append an element in a delta and
   * confirm the rebuilt list has the new element appended in order.
   */
  @Test
  public void embeddedListAppendDeltaRoundTrips() {
    var startRid = persistEntity(e -> {
      var list = e.newEmbeddedList("items");
      list.add("a");
      list.add("b");
    });

    session.begin();
    var loaded = session.<EntityImpl>load(startRid);
    List<Object> list = loaded.getProperty("items");
    list.add("c");
    var deltaBytes = EntitySerializerDelta.serializeDelta(session, loaded);
    session.rollback();

    session.begin();
    var target = session.<EntityImpl>load(startRid);
    delta.deserializeDelta(session, deltaBytes, target);
    List<Object> rebuilt = target.getProperty("items");
    assertEquals(List.of("a", "b", "c"), new ArrayList<>(rebuilt));
    session.rollback();
  }

  /**
   * Embedded list element removed at position: remove the middle element via
   * {@code list.remove(1)}; the delta encodes a REMOVED change at position 1; the
   * rebuilt list must contain exactly the surviving elements in order.
   */
  @Test
  public void embeddedListRemoveAtPositionDeltaRoundTrips() {
    var startRid = persistEntity(e -> {
      var list = e.newEmbeddedList("items");
      list.add("a");
      list.add("b");
      list.add("c");
    });

    session.begin();
    var loaded = session.<EntityImpl>load(startRid);
    List<Object> list = loaded.getProperty("items");
    list.remove(1);
    var deltaBytes = EntitySerializerDelta.serializeDelta(session, loaded);
    session.rollback();

    session.begin();
    var target = session.<EntityImpl>load(startRid);
    delta.deserializeDelta(session, deltaBytes, target);
    List<Object> rebuilt = target.getProperty("items");
    assertEquals(List.of("a", "c"), new ArrayList<>(rebuilt));
    session.rollback();
  }

  /**
   * Embedded map key replaced: change the value mapped to an existing key; the delta
   * encodes REPLACED for the map root; the rebuilt map preserves all other entries
   * unchanged.
   */
  @Test
  public void embeddedMapKeyReplacedDeltaRoundTrips() {
    var startRid = persistEntity(e -> {
      var map = e.newEmbeddedMap("kv");
      map.put("k1", "v1");
      map.put("k2", "v2");
    });

    session.begin();
    var loaded = session.<EntityImpl>load(startRid);
    Map<String, Object> map = loaded.getProperty("kv");
    map.put("k1", "v1prime");
    var deltaBytes = EntitySerializerDelta.serializeDelta(session, loaded);
    session.rollback();

    session.begin();
    var target = session.<EntityImpl>load(startRid);
    delta.deserializeDelta(session, deltaBytes, target);
    Map<String, Object> rebuilt = target.getProperty("kv");
    assertEquals("v1prime", rebuilt.get("k1"));
    assertEquals("v2", rebuilt.get("k2"));
    session.rollback();
  }

  /**
   * Embedded set: add a value, remove a value — the delta encodes a CREATED + REMOVED
   * pair for the set root and the rebuilt set has both mutations applied.
   */
  @Test
  public void embeddedSetAddAndRemoveDeltaRoundTrips() {
    var startRid = persistEntity(e -> {
      var set = e.newEmbeddedSet("tags");
      set.add("alpha");
      set.add("beta");
    });

    session.begin();
    var loaded = session.<EntityImpl>load(startRid);
    java.util.Set<Object> set = loaded.getProperty("tags");
    set.add("gamma");
    set.remove("alpha");
    var deltaBytes = EntitySerializerDelta.serializeDelta(session, loaded);
    session.rollback();

    session.begin();
    var target = session.<EntityImpl>load(startRid);
    delta.deserializeDelta(session, deltaBytes, target);
    java.util.Set<Object> rebuilt = target.getProperty("tags");
    assertEquals(new HashSet<>(Arrays.asList("beta", "gamma")), new HashSet<>(rebuilt));
    session.rollback();
  }

  /**
   * Link list delta: add a peer; the rebuilt list contains the new link in addition to
   * the existing entries. Exercises the {@code serializeDeltaLinkList} ADD branch and
   * {@code deserializeDeltaLinkList} CREATED branch via the public delta API.
   */
  @Test
  public void linkListAddDeltaRoundTrips() {
    RID peerA = persistPeer("alpha");
    RID peerB = persistPeer("beta");
    var startRid = persistEntity(e -> {
      var links = e.newLinkList("links");
      links.add(peerA);
    });

    session.begin();
    var loaded = session.<EntityImpl>load(startRid);
    List<Identifiable> links = loaded.getProperty("links");
    links.add(peerB);
    var deltaBytes = EntitySerializerDelta.serializeDelta(session, loaded);
    session.rollback();

    session.begin();
    var target = session.<EntityImpl>load(startRid);
    delta.deserializeDelta(session, deltaBytes, target);
    List<Identifiable> rebuilt = target.getProperty("links");
    assertEquals(2, rebuilt.size());
    assertEquals(peerA, rebuilt.get(0).getIdentity());
    assertEquals(peerB, rebuilt.get(1).getIdentity());
    session.rollback();
  }

  /**
   * Link map delta: replace the link bound to an existing key; the rebuilt map carries
   * the new RID for that key while leaving other keys untouched.
   */
  @Test
  public void linkMapReplaceDeltaRoundTrips() {
    RID peerA = persistPeer("alpha");
    RID peerB = persistPeer("beta");
    var startRid = persistEntity(e -> {
      var lm = e.newLinkMap("byName");
      lm.put("a", peerA);
    });

    session.begin();
    var loaded = session.<EntityImpl>load(startRid);
    Map<String, Identifiable> lm = loaded.getProperty("byName");
    lm.put("a", peerB);
    var deltaBytes = EntitySerializerDelta.serializeDelta(session, loaded);
    session.rollback();

    session.begin();
    var target = session.<EntityImpl>load(startRid);
    delta.deserializeDelta(session, deltaBytes, target);
    Map<String, Identifiable> rebuilt = target.getProperty("byName");
    assertEquals(peerB, rebuilt.get("a").getIdentity());
    session.rollback();
  }

  /**
   * Link bag (RID bag) delta with both add and remove in the same transaction. Pins
   * the {@code serializeDeltaLinkBag} ADD + REMOVE encoding and the
   * {@code deserializeDeltaLinkBag} CREATED + REMOVED branches via public delta API.
   */
  @Test
  public void linkBagAddRemoveDeltaRoundTrips() {
    RID peerA = persistPeer("alpha");
    RID peerB = persistPeer("beta");
    RID peerC = persistPeer("gamma");
    var startRid = persistEntity(e -> {
      var bag = new LinkBag(session);
      bag.add(peerA);
      bag.add(peerB);
      e.setProperty("bag", bag, PropertyType.LINKBAG);
    });

    session.begin();
    var loaded = session.<EntityImpl>load(startRid);
    LinkBag bag = loaded.getProperty("bag");
    bag.add(peerC);
    bag.remove(peerA);
    var deltaBytes = EntitySerializerDelta.serializeDelta(session, loaded);
    session.rollback();

    session.begin();
    var target = session.<EntityImpl>load(startRid);
    delta.deserializeDelta(session, deltaBytes, target);
    LinkBag rebuilt = target.getProperty("bag");
    var ids = new HashSet<RID>();
    rebuilt.forEach(p -> ids.add(p.primaryRid()));
    assertEquals(new HashSet<>(Arrays.asList(peerB, peerC)), ids);
    session.rollback();
  }

  // ============================================================================
  // === B-tree-mode read pins for LinkBag / LinkSet ============================
  // ============================================================================

  /**
   * The {@code mode_byte == 2} branch of {@code readLinkBag} treats the remaining
   * payload as a {@link com.jetbrains.youtrackdb.internal.core.storage.ridbag.LinkBagPointer}
   * triple ({@code size}, {@code fileId}, {@code linkBagId}). When the pointer values
   * are negative the constructed pointer is invalid and the read path must throw
   * {@code IllegalStateException("LinkBag with invalid pointer was found")}. Forge
   * the bytes manually because the in-memory test storage does not have a B-tree
   * collection manager that would emit this shape naturally.
   *
   * <p>Encoding: mode=0x02, size=zigzag(0)=0x00, fileId=zigzag(-1)=0x01,
   * linkBagId=zigzag(-1)=0x01.
   */
  @Test
  public void linkBagReadModeTwoInvalidPointerThrowsIllegalState() {
    runInTx(() -> {
      var owner = (EntityImpl) session.newEntity(TARGET_CLASS);
      var bytes = new BytesContainer(new byte[] {0x02, 0x00, 0x01, 0x01});
      var ex = assertThrows(IllegalStateException.class,
          () -> delta.deserializeValue(session, bytes, PropertyTypeInternal.LINKBAG, owner));
      assertTrue("expected invalid-pointer diagnostic, got: " + ex.getMessage(),
          ex.getMessage().contains("invalid pointer"));
    });
  }

  /**
   * Symmetrical mode-2 invalid-pointer pin for LINKSET — same encoding shape, but the
   * dispatch lands in {@code readLinkSet}, which throws
   * {@code IllegalStateException("LinkSet with invalid pointer was found")}.
   */
  @Test
  public void linkSetReadModeTwoInvalidPointerThrowsIllegalState() {
    runInTx(() -> {
      var owner = (EntityImpl) session.newEntity(TARGET_CLASS);
      var bytes = new BytesContainer(new byte[] {0x02, 0x00, 0x01, 0x01});
      var ex = assertThrows(IllegalStateException.class,
          () -> delta.deserializeValue(session, bytes, PropertyTypeInternal.LINKSET, owner));
      assertTrue("expected invalid-pointer diagnostic, got: " + ex.getMessage(),
          ex.getMessage().contains("invalid pointer"));
    });
  }

  // ============================================================================
  // === Helpers ================================================================
  // ============================================================================

  private String encodeNullableType(PropertyTypeInternal type) {
    var bytes = new BytesContainer();
    EntitySerializerDelta.writeNullableType(bytes, type);
    return HEX.formatHex(bytes.fitBytes());
  }

  /**
   * Runs {@code body} inside a fresh transaction, rolling back on exit so the test
   * leaves no persistent state behind. Mirrors the helper used by other Track-13
   * round-trip tests.
   */
  private void runInTx(Runnable body) {
    if (session.isTxActive()) {
      session.rollback();
    }
    session.begin();
    try {
      body.run();
    } finally {
      if (session.isTxActive()) {
        session.rollback();
      }
    }
  }

  /**
   * Persist an entity of class {@code TARGET_CLASS}, applying {@code populate} during
   * the transaction, and return its committed RID. Subsequent transactions can
   * {@code session.load(rid)} to obtain a fresh, loaded copy.
   */
  private RID persistEntity(java.util.function.Consumer<EntityImpl> populate) {
    session.begin();
    var entity = (EntityImpl) session.newEntity(TARGET_CLASS);
    populate.accept(entity);
    session.commit();
    return entity.getIdentity();
  }

  /**
   * Persist a peer entity of class {@code PEER_CLASS} with a single {@code name}
   * property and return its committed RID.
   */
  private RID persistPeer(String name) {
    session.begin();
    var peer = (EntityImpl) session.newEntity(PEER_CLASS);
    peer.setProperty("name", name);
    session.commit();
    return peer.getIdentity();
  }
}
