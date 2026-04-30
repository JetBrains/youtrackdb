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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.db.record.ridbag.LinkBag;
import com.jetbrains.youtrackdb.internal.core.exception.SerializationException;
import com.jetbrains.youtrackdb.internal.core.exception.ValidationException;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.record.impl.EmbeddedEntityImpl;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.RecordSerializer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Before;
import org.junit.Test;

/**
 * Round-trip tests for the recursion-heavy and link-resolution paths of
 * {@link RecordSerializerBinaryV1}: EMBEDDED, EMBEDDEDLIST, EMBEDDEDSET, EMBEDDEDMAP, LINK,
 * LINKLIST, LINKSET, LINKMAP, LINKBAG. Companion to
 * {@link RecordSerializerBinaryV1SimpleTypeRoundTripTest}, which covers scalar types only.
 *
 * <p><b>Two-tier discipline.</b> Where the byte sequence is small and unambiguous (empty
 * containers, fixed-shape link encodings) Tier 1 pins the canonical hex encoding by driving
 * {@link RecordSerializerBinaryV1#serializeValue} / {@code deserializeValue} against a fresh
 * {@link BytesContainer}. For value-shape that depends on transaction state — {@link LinkBag}
 * and {@link com.jetbrains.youtrackdb.internal.core.db.record.EntityLinkSetImpl} both require
 * an active transaction via {@code checkAndConvert} — Tier 1 falls back to round-trip
 * equality and the canonical structural check (config byte + size varint) on the produced
 * bytes. Tier 2 round-trips the same values through the full record path
 * ({@link RecordSerializerBinary#toStream} / {@link RecordSerializerBinary#fromStream}) to
 * confirm header dispatch and per-field type byte handling stay aligned.
 *
 * <p><b>Schema setup.</b> Three classes are created once in {@code @Before}:
 * <ul>
 *   <li>{@code Thing} — top-level holder used in every Tier 2 round-trip</li>
 *   <li>{@code Peer} — link target (must be {@code .save()}'d so the RID becomes
 *       persistent — see {@code linkSerializeFailsWhenLinkedRidIsNotPersistent} below)</li>
 *   <li>{@code Address} — abstract embedded class so the deserialiser's
 *       {@code cls.isAbstract()} branch flips the embedded marker (matches the JSON test's
 *       {@code prepareSchema} convention at {@code JSONSerializerJacksonInstanceRoundTripTest})</li>
 * </ul>
 *
 * <p><b>Persistent-link discipline.</b> {@link RecordSerializerBinaryV1#serializeValue}
 * rejects non-persistent links with an {@link IllegalStateException} for {@code LINK} and
 * with a Java {@code assert} for collections; the corresponding negative test
 * {@code linkRejectsNonPersistentValueAtSerializeValue} exercises only the public LINK path.
 *
 * <p><b>Adding a new collection-shape test:</b> place the byte-shape pin (if any) under the
 * matching {@code === Tier 1 ===} section and the record-level round-trip under
 * {@code === Tier 2 ===}. Edge cases (empty containers, deep nesting, null elements,
 * mixed-type schemaless collections) live in {@code === Edge cases ===} so a regression that
 * silently changes one branch is easy to localise.
 */
public class RecordSerializerBinaryV1CollectionRoundTripTest extends DbTestBase {

  private static final HexFormat HEX = HexFormat.of();

  private static final String THING_CLASS = "Thing";
  private static final String PEER_CLASS = "Peer";
  private static final String ADDRESS_CLASS = "Address";

  private RecordSerializerBinaryV1 v1;
  private RecordSerializer recordSerializer;

  @Before
  public void prepareSerializerAndSchema() {
    v1 = new RecordSerializerBinaryV1();
    recordSerializer = RecordSerializerBinary.INSTANCE;

    // DbTestBase drops and re-creates the in-memory database per test method, so each
    // call to this @Before runs against a fresh schema — the getOrCreateClass +
    // unconditional createProperty sequence below is correct without idempotency guards.
    var schema = session.getMetadata().getSchema();
    schema.getOrCreateClass(THING_CLASS);
    schema.getOrCreateClass(PEER_CLASS);
    var address = schema.getOrCreateClass(ADDRESS_CLASS);
    address.setAbstract(true);
    address.createProperty("street", PropertyType.STRING);
    address.createProperty("zip", PropertyType.INTEGER);
  }

  // ============================================================================
  // === Tier 1: byte-shape pins on serializeValue dispatch =====================
  // ============================================================================

  // --- LINK ----------------------------------------------------------------

  /**
   * LINK encodes as varint(zigzag(clusterId)) followed by varint(zigzag(clusterPosition)).
   * For a synthesised RID at #10:0 that is two single-byte varints (cluster 10 → 0x14,
   * position 0 → 0x00). Synthesising the RID via {@link RecordId} bypasses the
   * {@code session.refreshRid} branch in {@code writeOptimizedLink} since
   * {@code isPersistent()} is true for any (cluster ≥ 0, position ≥ 0) pair.
   */
  @Test
  public void linkAtClusterTenPositionZeroEncodesAsTwoSingleByteVarints() {
    var rid = new RecordId(10, 0);
    var encoded = serializeValueBytes(PropertyTypeInternal.LINK, rid);
    assertEquals("1400", HEX.formatHex(encoded));
    var decoded = (Identifiable) deserializeValueBytes(PropertyTypeInternal.LINK, encoded);
    assertNotNull(decoded);
    assertEquals(rid, decoded.getIdentity());
  }

  /**
   * LINK with a multi-byte position varint: zig-zag(1234) = 2468 → varint 0xA4 0x13.
   * Cluster 0 stays a single 0x00 byte. Pins the boundary where position outgrows one byte.
   */
  @Test
  public void linkAtClusterZeroPositionTwelveThirtyFourEncodesAsThreeBytes() {
    var rid = new RecordId(0, 1234);
    var encoded = serializeValueBytes(PropertyTypeInternal.LINK, rid);
    assertEquals("00a413", HEX.formatHex(encoded));
    var decoded = (Identifiable) deserializeValueBytes(PropertyTypeInternal.LINK, encoded);
    assertEquals(rid, decoded.getIdentity());
  }

  /**
   * Negative: a non-{@link Identifiable} value rejected with {@link ValidationException}
   * before any bytes are written. Pin the contract so a regression that silently coerced or
   * accepted the value would be caught.
   */
  @Test
  public void linkSerializeRejectsNonIdentifiableValueWithValidationException() {
    var bytes = new BytesContainer();
    var ex =
        assertThrows(
            ValidationException.class,
            () -> v1.serializeValue(session, bytes, "not-a-link",
                PropertyTypeInternal.LINK, null, null, null));
    assertTrue(
        "expected non-Identifiable diagnostic, got: " + ex.getMessage(),
        ex.getMessage().contains("not a Identifiable"));
  }

  /**
   * Negative: a non-persistent {@link Identifiable} (cluster id = -1) rejected with
   * {@link IllegalStateException} at {@code RecordSerializerBinaryV1.java} around the LINK
   * branch in {@code serializeValue}. The check fires before {@code writeOptimizedLink} can
   * try to {@code refreshRid} the value, so no bytes are written either.
   */
  @Test
  public void linkSerializeRejectsNonPersistentIdentifiableWithIllegalStateException() {
    var nonPersistent = new RecordId(-1, -1);
    var bytes = new BytesContainer();
    var ex =
        assertThrows(
            IllegalStateException.class,
            () -> v1.serializeValue(session, bytes, nonPersistent,
                PropertyTypeInternal.LINK, null, null, null));
    assertTrue(
        "expected non-persistent-link diagnostic, got: " + ex.getMessage(),
        ex.getMessage().contains("Non-persistent link"));
  }

  // --- LINKLIST ------------------------------------------------------------

  /**
   * Empty LINKLIST: writeLinkCollection allocates a single leading config byte (left as
   * the zero default written by {@link BytesContainer#alloc(int)}) and then writes the
   * size varint. Empty list → size 0 → varint 0x00. Total: {@code 0000}. Deserialise
   * happens against an owner entity because the read path constructs an
   * {@code EntityLinkListImpl(owner)} unconditionally; the deserialiser also rejects any
   * non-zero leading byte with {@code "Invalid type of embedded collection"} — pinned in
   * {@link #linkListReadRejectsNonZeroLeadingByte}.
   */
  @Test
  public void linkListEmptyEncodesAsLeadingZeroPlusSizeZero() {
    runInTx(() -> {
      List<Identifiable> empty = List.of();
      var encoded = serializeValueBytes(PropertyTypeInternal.LINKLIST, empty);
      assertEquals("0000", HEX.formatHex(encoded));
      var owner = (EntityImpl) session.newEntity(THING_CLASS);
      @SuppressWarnings("unchecked")
      Collection<Identifiable> decoded =
          (Collection<Identifiable>) deserializeValueBytesWithOwner(PropertyTypeInternal.LINKLIST,
              encoded, owner);
      assertNotNull(decoded);
      assertTrue(decoded.isEmpty());
    });
  }

  /**
   * Two-element LINKLIST: {@code 00 (config) + 04 (size=zigzag(2)) + 1400 (#10:0) +
   * 1402 (#10:1)}. Pins both the size encoding and the per-element link encoding. The
   * owner-dependent deserialise allocates {@code EntityLinkListImpl(owner)} via the
   * fresh test entity.
   */
  @Test
  public void linkListTwoElementsEncodesConcatenationOfOptimizedLinks() {
    runInTx(() -> {
      var rids = List.<Identifiable>of(new RecordId(10, 0), new RecordId(10, 1));
      var encoded = serializeValueBytes(PropertyTypeInternal.LINKLIST, rids);
      assertEquals("000414001402", HEX.formatHex(encoded));
      var owner = (EntityImpl) session.newEntity(THING_CLASS);
      @SuppressWarnings("unchecked")
      Collection<Identifiable> decoded =
          (Collection<Identifiable>) deserializeValueBytesWithOwner(PropertyTypeInternal.LINKLIST,
              encoded, owner);
      assertNotNull(decoded);
      assertEquals(2, decoded.size());
      var ids = new ArrayList<>();
      decoded.forEach(i -> ids.add(i.getIdentity()));
      assertEquals(List.of(new RecordId(10, 0), new RecordId(10, 1)), ids);
    });
  }

  /**
   * Negative: a non-zero leading byte during LINKLIST deserialise must be rejected with
   * {@link SerializationException}. Forge the encoding with a non-zero first byte so the
   * {@code if (type != 0) throw} branch in {@code readLinkCollection} fires. The owner
   * is required because the read path allocates {@code EntityLinkListImpl(owner)} before
   * inspecting the leading byte.
   */
  @Test
  public void linkListReadRejectsNonZeroLeadingByte() {
    runInTx(() -> {
      var owner = (EntityImpl) session.newEntity(THING_CLASS);
      var forged = new byte[] {(byte) 0x01, (byte) 0x00};
      var bytes = new BytesContainer(forged);
      var ex =
          assertThrows(
              SerializationException.class,
              () -> v1.deserializeValue(session, bytes,
                  PropertyTypeInternal.LINKLIST, owner, false, null));
      assertTrue(
          "expected invalid-type diagnostic, got: " + ex.getMessage(),
          ex.getMessage().contains("Invalid type of embedded collection"));
    });
  }

  // --- LINKMAP -------------------------------------------------------------

  /**
   * Empty LINKMAP: writeLinkMap allocates a 1-byte version-tag (zero) followed by the
   * size varint. Empty → size 0 → varint 0x00. Total: {@code 0000}. The reader rejects
   * any non-zero version tag — pinned in {@link #linkMapReadRejectsNonZeroVersionByte}.
   */
  @Test
  public void linkMapEmptyEncodesAsLeadingZeroPlusSizeZero() {
    runInTx(() -> {
      Map<Object, Identifiable> empty = Map.of();
      var encoded = serializeValueBytes(PropertyTypeInternal.LINKMAP, empty);
      assertEquals("0000", HEX.formatHex(encoded));
      var owner = (EntityImpl) session.newEntity(THING_CLASS);
      @SuppressWarnings("unchecked")
      Map<String, Identifiable> decoded =
          (Map<String, Identifiable>) deserializeValueBytesWithOwner(PropertyTypeInternal.LINKMAP,
              encoded, owner);
      assertNotNull(decoded);
      assertTrue(decoded.isEmpty());
    });
  }

  /**
   * One-entry LINKMAP {@code {"a" -> #10:0}}: version(0) + size(varint(1)=0x02) +
   * key-string(varint(1)=0x02 + 'a'=0x61) + writeOptimizedLink(0x14 0x00). Total bytes:
   * {@code 00 02 02 61 14 00}.
   */
  @Test
  public void linkMapOneEntryEncodesAsVersionPlusSizePlusStringPlusLink() {
    runInTx(() -> {
      Map<Object, Identifiable> map = new LinkedHashMap<>();
      map.put("a", new RecordId(10, 0));
      var encoded = serializeValueBytes(PropertyTypeInternal.LINKMAP, map);
      assertEquals("000202611400", HEX.formatHex(encoded));
      var owner = (EntityImpl) session.newEntity(THING_CLASS);
      @SuppressWarnings("unchecked")
      Map<String, Identifiable> decoded =
          (Map<String, Identifiable>) deserializeValueBytesWithOwner(PropertyTypeInternal.LINKMAP,
              encoded, owner);
      assertNotNull(decoded);
      assertEquals(1, decoded.size());
      assertEquals(new RecordId(10, 0), decoded.get("a").getIdentity());
    });
  }

  /**
   * Negative: LINKMAP read rejects any non-zero version-tag byte. Forge the encoding so
   * the version byte is non-zero and confirm the {@code "Invalid version of link map"}
   * branch in {@code HelperClasses.readLinkMap} fires.
   */
  @Test
  public void linkMapReadRejectsNonZeroVersionByte() {
    var forged = new byte[] {(byte) 0x01, (byte) 0x00};
    var bytes = new BytesContainer(forged);
    var ex =
        assertThrows(
            SerializationException.class,
            () -> v1.deserializeValue(session, bytes,
                PropertyTypeInternal.LINKMAP, null, false, null));
    assertTrue(
        "expected invalid-version diagnostic, got: " + ex.getMessage(),
        ex.getMessage().contains("Invalid version of link map"));
  }

  // --- EMBEDDEDLIST / EMBEDDEDSET (writeEmbeddedCollection) ----------------

  /**
   * Empty EMBEDDEDLIST without a linked type: size varint (0x00) followed by the default
   * type marker EMBEDDED (id 9 → 0x09). Total: {@code 0009}. The default-EMBEDDED branch
   * is exercised when {@code linkedType} is null and the collection is empty (no per-item
   * type byte writes).
   */
  @Test
  public void embeddedListEmptyDefaultsLinkedTypeMarkerToEmbedded() {
    var encoded = serializeValueBytes(PropertyTypeInternal.EMBEDDEDLIST, List.of());
    assertEquals("0009", HEX.formatHex(encoded));
    @SuppressWarnings("unchecked")
    Collection<Object> decoded =
        (Collection<Object>) deserializeValueBytes(
            PropertyTypeInternal.EMBEDDEDLIST, encoded);
    assertNotNull(decoded);
    assertTrue(decoded.isEmpty());
  }

  /**
   * Empty EMBEDDEDLIST with linkedType=STRING: the linkedType marker becomes 0x07 (STRING)
   * instead of the default 0x09 (EMBEDDED). Pins the linkedType branch in
   * {@code writeEmbeddedCollection} and round-trips back through the value dispatch so a
   * read-side regression that ignored the linkedType marker would also be caught.
   */
  @Test
  public void embeddedListEmptyWithStringLinkedTypeWritesStringTypeMarker() {
    var encoded = serializeValueBytesWithLinkedType(
        PropertyTypeInternal.EMBEDDEDLIST, List.of(), PropertyTypeInternal.STRING);
    assertEquals("0007", HEX.formatHex(encoded));
    @SuppressWarnings("unchecked")
    Collection<Object> decoded =
        (Collection<Object>) deserializeValueBytes(PropertyTypeInternal.EMBEDDEDLIST, encoded);
    assertNotNull(decoded);
    assertTrue(decoded.isEmpty());
  }

  /**
   * One-element EMBEDDEDLIST with linkedType=STRING: size(varint(1)=0x02) +
   * linkedTypeMarker(STRING=0x07) + perItemTypeMarker(STRING=0x07) +
   * serializeValue(STRING, "x") = writeString("x") (varint(1)=0x02 + 0x78). Total:
   * {@code 02 07 07 02 78}. Round-trips back through the value dispatch to confirm the
   * read side honours the linkedType marker rather than defaulting to EMBEDDED.
   */
  @Test
  public void embeddedListSingleStringWithLinkedTypeEncodesAsExpectedSequence() {
    var encoded = serializeValueBytesWithLinkedType(
        PropertyTypeInternal.EMBEDDEDLIST, List.of("x"), PropertyTypeInternal.STRING);
    assertEquals("0207070278", HEX.formatHex(encoded));
    @SuppressWarnings("unchecked")
    Collection<Object> decoded =
        (Collection<Object>) deserializeValueBytes(PropertyTypeInternal.EMBEDDEDLIST, encoded);
    assertNotNull(decoded);
    assertEquals(List.of("x"), new ArrayList<>(decoded));
  }

  /**
   * EMBEDDEDSET shares {@code writeEmbeddedCollection} with EMBEDDEDLIST. The byte shape is
   * therefore identical for an empty set, default-EMBEDDED linked type. Pin the symmetry so
   * a regression that diverges the two branches surfaces immediately, plus round-trip the
   * encoded bytes back through the EMBEDDEDSET dispatch so a read-side break is also caught.
   */
  @Test
  public void embeddedSetEmptyEncodesIdenticallyToEmbeddedListEmpty() {
    var encoded = serializeValueBytes(PropertyTypeInternal.EMBEDDEDSET, Set.of());
    assertEquals("0009", HEX.formatHex(encoded));
    @SuppressWarnings("unchecked")
    Collection<Object> decoded =
        (Collection<Object>) deserializeValueBytes(PropertyTypeInternal.EMBEDDEDSET, encoded);
    assertNotNull(decoded);
    assertTrue(decoded.isEmpty());
  }

  // --- EMBEDDEDMAP ---------------------------------------------------------

  /**
   * Empty EMBEDDEDMAP: writeEmbeddedMap writes only the size varint (no type marker). For
   * empty: size 0 → varint 0x00. Total: {@code 00}. The deserialiser parses zero entries
   * and returns an empty {@code EntityEmbeddedMapImpl}.
   */
  @Test
  public void embeddedMapEmptyEncodesAsSingleZeroByte() {
    var encoded = serializeValueBytes(PropertyTypeInternal.EMBEDDEDMAP, Map.of());
    assertEquals("00", HEX.formatHex(encoded));
    @SuppressWarnings("unchecked")
    Map<String, Object> decoded =
        (Map<String, Object>) deserializeValueBytes(PropertyTypeInternal.EMBEDDEDMAP, encoded);
    assertNotNull(decoded);
    assertTrue(decoded.isEmpty());
  }

  /**
   * Negative: EMBEDDEDMAP rejects null keys with a {@link SerializationException}. The
   * {@code if (key == null)} branch in {@code writeEmbeddedMap} fires before any per-entry
   * bytes are written.
   *
   * <p>The corresponding full-record-path null-key rejection is not testable end-to-end:
   * the null key is rejected earlier inside {@code entity.newEmbeddedMap} when
   * {@code EntityEmbeddedMapImpl} iterates the source-map entries to copy them across, so
   * the {@link SerializationException} from {@code writeEmbeddedMap} is unreachable from
   * a normal public-API call sequence. This Tier-1 test pins the writer-side contract
   * directly.
   */
  @Test
  public void embeddedMapWriteRejectsNullKeyWithSerializationException() {
    Map<Object, Object> map = new HashMap<>();
    map.put(null, "value");
    var bytes = new BytesContainer();
    var ex =
        assertThrows(
            SerializationException.class,
            () -> v1.serializeValue(session, bytes, map,
                PropertyTypeInternal.EMBEDDEDMAP, null, null, null));
    assertTrue(
        "expected null-key diagnostic, got: " + ex.getMessage(),
        ex.getMessage().contains("Maps with null keys are not supported"));
  }

  // ============================================================================
  // === Tier 2: full record round-trips =======================================
  // ============================================================================

  // --- EMBEDDED (single embedded entity) -----------------------------------

  /**
   * A {@link PropertyType#EMBEDDED} property carrying a populated abstract-class entity
   * survives the full {@code toStream}/{@code fromStream} cycle: the embedded entity's
   * properties are restored, its {@code isEmbedded()} flag stays true, and the embedded
   * marker still reflects the abstract-class shape.
   */
  @Test
  public void embeddedEntityRoundTripPreservesPropertiesAndEmbeddedFlag() {
    runInTx(() -> {
      var entity = (EntityImpl) session.newEntity(THING_CLASS);
      var addr = (EmbeddedEntityImpl) session.newEmbeddedEntity(ADDRESS_CLASS);
      addr.setProperty("street", "Main 1");
      addr.setProperty("zip", 12345);
      entity.setProperty("address", addr, PropertyType.EMBEDDED);

      var extracted = roundTripFullRecord(entity);
      var got = extracted.<EntityImpl>getProperty("address");
      assertNotNull(got);
      assertTrue(
          "embedded property must round-trip with isEmbedded() == true",
          got.isEmbedded());
      assertEquals("Main 1", got.<String>getProperty("street"));
      assertEquals(Integer.valueOf(12345), got.<Integer>getProperty("zip"));
    });
  }

  /**
   * Embedded entity carrying no properties round-trips to a still-embedded but empty
   * entity. Pins the {@code class-name + empty-header} branch in {@code serializeWithClassName}.
   */
  @Test
  public void embeddedEntityWithNoPropertiesRoundTripsAsEmptyEmbedded() {
    runInTx(() -> {
      var entity = (EntityImpl) session.newEntity(THING_CLASS);
      var addr = (EmbeddedEntityImpl) session.newEmbeddedEntity(ADDRESS_CLASS);
      entity.setProperty("address", addr, PropertyType.EMBEDDED);

      var extracted = roundTripFullRecord(entity);
      var got = extracted.<EntityImpl>getProperty("address");
      assertNotNull(got);
      assertTrue(got.isEmbedded());
      assertTrue(got.getPropertyNames().isEmpty());
    });
  }

  // --- EMBEDDEDLIST --------------------------------------------------------

  @Test
  public void embeddedListEmptyRoundTripsToEmptyContainer() {
    runInTx(() -> {
      var entity = (EntityImpl) session.newEntity(THING_CLASS);
      entity.newEmbeddedList("items");

      var extracted = roundTripFullRecord(entity);
      Collection<?> got = extracted.getProperty("items");
      assertNotNull("empty embeddedList must round-trip to a non-null container", got);
      assertTrue("must round-trip empty, got: " + got, got.isEmpty());
    });
  }

  @Test
  public void embeddedListOfStringsPreservesOrderAndValues() {
    runInTx(() -> {
      var entity = (EntityImpl) session.newEntity(THING_CLASS);
      entity.newEmbeddedList("items", List.of("alpha", "beta", "gamma"));

      var extracted = roundTripFullRecord(entity);
      Collection<?> got = extracted.getProperty("items");
      assertEquals(List.of("alpha", "beta", "gamma"), new ArrayList<>(got));
    });
  }

  @Test
  public void embeddedListOfIntegersPreservesNumericValues() {
    runInTx(() -> {
      var entity = (EntityImpl) session.newEntity(THING_CLASS);
      entity.newEmbeddedList("items", List.of(1, 2, 3, 4, 5));

      var extracted = roundTripFullRecord(entity);
      Collection<?> got = extracted.getProperty("items");
      assertEquals(List.of(1, 2, 3, 4, 5), new ArrayList<>(got));
    });
  }

  /**
   * Schemaless EMBEDDEDLIST mixing types: {@code linkedType} is null, so each item gets
   * its per-element type byte inferred via {@code getTypeFromValueEmbedded}. Round-trip
   * preserves both order and per-element type — pin both halves.
   */
  @Test
  public void embeddedListMixedTypesPreservesPerElementTypeWithoutLinkedType() {
    runInTx(() -> {
      var entity = (EntityImpl) session.newEntity(THING_CLASS);
      var input = new ArrayList<Object>();
      input.add("text");
      input.add(42);
      input.add(Boolean.TRUE);
      input.add(3.14d);
      entity.newEmbeddedList("items", input);

      var extracted = roundTripFullRecord(entity);
      Collection<?> got = extracted.getProperty("items");
      var roundTripped = new ArrayList<>(got);
      assertEquals(4, roundTripped.size());
      assertEquals("text", roundTripped.get(0));
      assertEquals(42, roundTripped.get(1));
      assertEquals(Boolean.TRUE, roundTripped.get(2));
      assertEquals(Double.valueOf(3.14d), roundTripped.get(3));
    });
  }

  // --- EMBEDDEDSET ---------------------------------------------------------

  @Test
  public void embeddedSetEmptyRoundTripsToEmptyContainer() {
    runInTx(() -> {
      var entity = (EntityImpl) session.newEntity(THING_CLASS);
      entity.newEmbeddedSet("items");

      var extracted = roundTripFullRecord(entity);
      Collection<?> got = extracted.getProperty("items");
      assertNotNull(got);
      assertTrue(got.isEmpty());
    });
  }

  @Test
  public void embeddedSetOfStringsPreservesUniqueValues() {
    runInTx(() -> {
      var entity = (EntityImpl) session.newEntity(THING_CLASS);
      entity.newEmbeddedSet("items", new LinkedHashSet<>(List.of("a", "b", "c")));

      var extracted = roundTripFullRecord(entity);
      Collection<?> got = extracted.getProperty("items");
      assertEquals("EMBEDDEDSET round-trip must preserve element count", 3, got.size());
      assertEquals(Set.of("a", "b", "c"), new HashSet<>(got));
    });
  }

  /**
   * Pin the size and content of an EMBEDDEDSET round-tripped through {@code newEmbeddedSet}
   * with a {@link LinkedHashSet} input — the input deduplicates eagerly, so the serialiser
   * receives a 2-element set. A regression that swelled or shrank the post-round-trip count
   * surfaces here. Note that this test does NOT exercise the serialiser-side deduplication
   * contract (the serialiser never sees the pre-dedup duplicates); a hypothetical regression
   * that allowed duplicate elements to pass through {@code writeEmbeddedCollection} would
   * not be detected by this test alone.
   */
  @Test
  public void embeddedSetWithDeduplicatedInputRoundTripsToTwoElements() {
    runInTx(() -> {
      var entity = (EntityImpl) session.newEntity(THING_CLASS);
      var input = new LinkedHashSet<String>();
      input.add("dup");
      input.add("dup");
      input.add("once");
      assertEquals(
          "test precondition: LinkedHashSet must dedup before reaching the serialiser",
          2, input.size());
      entity.newEmbeddedSet("items", input);

      var extracted = roundTripFullRecord(entity);
      Collection<?> got = extracted.getProperty("items");
      assertEquals(2, got.size());
      assertEquals(Set.of("dup", "once"), new HashSet<>(got));
    });
  }

  // --- EMBEDDEDMAP ---------------------------------------------------------

  @Test
  public void embeddedMapEmptyRoundTripsToEmptyContainer() {
    runInTx(() -> {
      var entity = (EntityImpl) session.newEntity(THING_CLASS);
      entity.newEmbeddedMap("props");

      var extracted = roundTripFullRecord(entity);
      Map<?, ?> got = extracted.getProperty("props");
      assertNotNull("empty embeddedMap must round-trip to a non-null container", got);
      assertTrue(got.isEmpty());
    });
  }

  @Test
  public void embeddedMapWithStringValuesPreservesEntries() {
    runInTx(() -> {
      var entity = (EntityImpl) session.newEntity(THING_CLASS);
      var input = new LinkedHashMap<String, Object>();
      input.put("k1", "v1");
      input.put("k2", "v2");
      entity.newEmbeddedMap("props", input);

      var extracted = roundTripFullRecord(entity);
      Map<?, ?> got = extracted.getProperty("props");
      assertEquals(2, got.size());
      assertEquals("v1", got.get("k1"));
      assertEquals("v2", got.get("k2"));
    });
  }

  @Test
  public void embeddedMapWithMixedValueTypesPreservesPerEntryType() {
    runInTx(() -> {
      var entity = (EntityImpl) session.newEntity(THING_CLASS);
      var input = new LinkedHashMap<String, Object>();
      input.put("s", "text");
      input.put("i", 7);
      input.put("b", Boolean.FALSE);
      input.put("d", 1.25d);
      entity.newEmbeddedMap("props", input);

      var extracted = roundTripFullRecord(entity);
      Map<?, ?> got = extracted.getProperty("props");
      assertEquals(4, got.size());
      assertEquals("text", got.get("s"));
      assertEquals(7, got.get("i"));
      assertEquals(Boolean.FALSE, got.get("b"));
      assertEquals(Double.valueOf(1.25d), got.get("d"));
    });
  }

  /**
   * EMBEDDEDMAP encodes a null value with the type-tombstone byte ({@code -1}). The
   * deserialiser branches on that tombstone and inserts {@code null} into the map without
   * trying to call {@code deserializeValue}.
   */
  @Test
  public void embeddedMapWithNullValuePreservesNullViaTypeTombstone() {
    runInTx(() -> {
      var entity = (EntityImpl) session.newEntity(THING_CLASS);
      var input = new LinkedHashMap<String, Object>();
      input.put("present", "yes");
      input.put("absent", null);
      entity.newEmbeddedMap("props", input);

      var extracted = roundTripFullRecord(entity);
      Map<?, ?> got = extracted.getProperty("props");
      assertEquals(2, got.size());
      assertEquals("yes", got.get("present"));
      assertTrue(
          "key for null-valued entry must survive the round-trip",
          got.containsKey("absent"));
      assertNull(got.get("absent"));
    });
  }

  // --- LINK ----------------------------------------------------------------

  @Test
  public void linkRoundTripPreservesIdentity() {
    var peerId = persistPeerWithName("link-target");
    runInTx(() -> {
      var entity = (EntityImpl) session.newEntity(THING_CLASS);
      entity.setProperty("ref", peerId, PropertyType.LINK);

      var extracted = roundTripFullRecord(entity);
      var got = extracted.<Identifiable>getProperty("ref");
      assertNotNull(got);
      assertEquals(peerId, got.getIdentity());
    });
  }

  /**
   * The full-record path also surfaces the non-persistent-link rejection — pin that the
   * exception originates from {@code serializeValue} (rather than being silently
   * substituted with NULL_RECORD_ID, for instance) by asserting the diagnostic message
   * fragment so an unrelated layer throwing {@link IllegalStateException} for a different
   * reason cannot satisfy the assertion.
   */
  @Test
  public void linkSerializeFailsWhenLinkedRidIsNotPersistentViaFullRecordPath() {
    runInTx(() -> {
      var entity = (EntityImpl) session.newEntity(THING_CLASS);
      var dangling = (EntityImpl) session.newEntity(PEER_CLASS);
      assertFalse(
          "test precondition: dangling entity must not yet be persistent",
          dangling.getIdentity().isPersistent());
      entity.setProperty("ref", dangling, PropertyType.LINK);
      var ex = assertThrows(
          IllegalStateException.class, () -> recordSerializer.toStream(session, entity));
      assertTrue(
          "expected non-persistent-link diagnostic, got: " + ex.getMessage(),
          ex.getMessage().contains("Non-persistent link"));
    });
  }

  // --- LINKLIST ------------------------------------------------------------

  @Test
  public void linkListRoundTripPreservesOrderedIdentities() {
    var p1 = persistPeerWithName("ll1");
    var p2 = persistPeerWithName("ll2");
    var p3 = persistPeerWithName("ll3");
    runInTx(() -> {
      var entity = (EntityImpl) session.newEntity(THING_CLASS);
      entity.newLinkList("links", List.of(
          session.load(p1), session.load(p2), session.load(p3)));

      var extracted = roundTripFullRecord(entity);
      Collection<? extends Identifiable> got = extracted.getProperty("links");
      assertNotNull(got);
      var ids = new ArrayList<>();
      got.forEach(i -> ids.add(i.getIdentity()));
      assertEquals(List.of(p1, p2, p3), ids);
    });
  }

  @Test
  public void linkListEmptyRoundTripsToEmptyContainer() {
    runInTx(() -> {
      var entity = (EntityImpl) session.newEntity(THING_CLASS);
      entity.newLinkList("links", List.of());

      var extracted = roundTripFullRecord(entity);
      Collection<? extends Identifiable> got = extracted.getProperty("links");
      assertNotNull("empty linkList must round-trip to a non-null container", got);
      assertTrue(got.isEmpty());
    });
  }

  // --- LINKSET -------------------------------------------------------------

  @Test
  public void linkSetRoundTripPreservesIdentitySet() {
    var p1 = persistPeerWithName("ls1");
    var p2 = persistPeerWithName("ls2");
    runInTx(() -> {
      var entity = (EntityImpl) session.newEntity(THING_CLASS);
      entity.newLinkSet(
          "links", new LinkedHashSet<>(List.of(session.load(p1), session.load(p2))));

      var extracted = roundTripFullRecord(entity);
      Collection<? extends Identifiable> got = extracted.getProperty("links");
      assertNotNull(got);
      assertEquals("LINKSET round-trip must preserve element count", 2, got.size());
      var ids = new LinkedHashSet<>();
      got.forEach(i -> ids.add(i.getIdentity()));
      assertEquals(Set.of(p1, p2), ids);
    });
  }

  @Test
  public void linkSetEmptyRoundTripsToEmptyContainer() {
    runInTx(() -> {
      var entity = (EntityImpl) session.newEntity(THING_CLASS);
      entity.newLinkSet("links", new LinkedHashSet<>());

      var extracted = roundTripFullRecord(entity);
      Collection<? extends Identifiable> got = extracted.getProperty("links");
      assertNotNull("empty linkSet must round-trip to a non-null container", got);
      assertTrue(got.isEmpty());
    });
  }

  // --- LINKMAP -------------------------------------------------------------

  @Test
  public void linkMapRoundTripPreservesKeyToIdentityMapping() {
    var p1 = persistPeerWithName("lm1");
    var p2 = persistPeerWithName("lm2");
    runInTx(() -> {
      var entity = (EntityImpl) session.newEntity(THING_CLASS);
      var input = new LinkedHashMap<String, Identifiable>();
      input.put("a", session.load(p1));
      input.put("b", session.load(p2));
      entity.newLinkMap("links", input);

      var extracted = roundTripFullRecord(entity);
      Map<?, ? extends Identifiable> got = extracted.getProperty("links");
      assertNotNull(got);
      assertEquals(2, got.size());
      assertEquals(p1, got.get("a").getIdentity());
      assertEquals(p2, got.get("b").getIdentity());
    });
  }

  @Test
  public void linkMapEmptyRoundTripsToEmptyContainer() {
    runInTx(() -> {
      var entity = (EntityImpl) session.newEntity(THING_CLASS);
      entity.newLinkMap("links", new LinkedHashMap<>());

      var extracted = roundTripFullRecord(entity);
      Map<?, ? extends Identifiable> got = extracted.getProperty("links");
      assertNotNull("empty linkMap must round-trip to a non-null container", got);
      assertTrue(got.isEmpty());
    });
  }

  // --- LINKBAG -------------------------------------------------------------

  @Test
  public void linkBagEmptyRoundTripsToEmptyBag() {
    runInTx(() -> {
      var entity = (EntityImpl) session.newEntity(THING_CLASS);
      var bag = new LinkBag(session);
      entity.setProperty("bag", bag, PropertyType.LINKBAG);

      var extracted = roundTripFullRecord(entity);
      var got = extracted.<LinkBag>getProperty("bag");
      assertNotNull("empty linkBag must round-trip to a non-null bag", got);
      assertEquals(0, got.size());
    });
  }

  @Test
  public void linkBagRoundTripPreservesAddedIdentities() {
    var p1 = persistPeerWithName("lb1");
    var p2 = persistPeerWithName("lb2");
    var p3 = persistPeerWithName("lb3");
    runInTx(() -> {
      var entity = (EntityImpl) session.newEntity(THING_CLASS);
      var bag = new LinkBag(session);
      bag.add(p1);
      bag.add(p2);
      bag.add(p3);
      entity.setProperty("bag", bag, PropertyType.LINKBAG);

      var extracted = roundTripFullRecord(entity);
      var got = extracted.<LinkBag>getProperty("bag");
      assertNotNull(got);
      assertEquals(3, got.size());

      var seen = new LinkedHashSet<>();
      got.iterator().forEachRemaining(pair -> seen.add(pair.primaryRid()));
      assertEquals(Set.of(p1, p2, p3), seen);
    });
  }

  /**
   * Empty LINKBAG: writeLinkBag for an embedded bag writes config(0x01) + size varint(0x00)
   * + writeEmbeddedLinkBagDelegate's trailing terminator(0x00) — total {@code 010000}.
   * Pin pairs the empty round-trip ({@link #linkBagEmptyRoundTripsToEmptyBag}) so a
   * regression that flipped the embedded-default branch would surface.
   */
  @Test
  public void linkBagEmptyEncodesAsEmbeddedConfigByteSizeZeroAndTerminator() {
    runInTx(() -> {
      var bag = new LinkBag(session);
      var bytes = new BytesContainer();
      v1.serializeValue(session, bytes, bag,
          PropertyTypeInternal.LINKBAG, null, null, null);
      var encoded = bytes.fitBytes();
      assertEquals("010000", HEX.formatHex(encoded));
    });
  }

  @Test
  public void linkBagWithSingleEntryEncodesEmbeddedConfigByteSizeOneAndTerminator() {
    // Tier-1 byte-shape spot-check on the LINKBAG path. Bag is embedded by default for
    // small sizes so the leading config byte is 0x01; size 1 → varint 0x02; the
    // change-list entry starts with continue marker 0x01; entries close with terminator
    // byte 0x00. Pin the leading prelude AND the trailing terminator — middle bytes
    // depend on the change-tracker's secondary-RID allocation and are not asserted.
    var p1 = persistPeerWithName("lb-shape");
    runInTx(() -> {
      var bag = new LinkBag(session);
      bag.add(p1);

      var bytes = new BytesContainer();
      v1.serializeValue(session, bytes, bag,
          PropertyTypeInternal.LINKBAG, null, null, null);
      var encoded = bytes.fitBytes();
      assertTrue(
          "LINKBAG encoding too short to verify embedded prelude, got: "
              + HEX.formatHex(encoded),
          encoded.length >= 4);
      assertEquals(
          "embedded LINKBAG must start with config byte 0x01",
          (byte) 0x01, encoded[0]);
      assertEquals(
          "size 1 must encode as varint 0x02 (zigzag(1))",
          (byte) 0x02, encoded[1]);
      assertEquals(
          "single embedded change-entry must start with continue marker 0x01",
          (byte) 0x01, encoded[2]);
      assertEquals(
          "embedded LINKBAG change-list must end with terminator byte 0x00",
          (byte) 0x00, encoded[encoded.length - 1]);
    });
  }

  // ============================================================================
  // === Edge cases ============================================================
  // ============================================================================

  /**
   * Mirrors the empty-typed-embedded-collection round-trip pin in the string-serializer
   * Jackson suite: empty <i>typed</i> EMBEDDED collections must round-trip to a non-null
   * empty container of the same kind. Cover all four embedded/link container types so a
   * regression that swapped the empty-branch for any one of them surfaces here.
   */
  @Test
  public void emptyTypedEmbeddedCollectionsAllRoundTripToEmptyContainersOfSameKind() {
    runInTx(() -> {
      var entity = (EntityImpl) session.newEntity(THING_CLASS);
      entity.newEmbeddedList("eList");
      entity.newEmbeddedSet("eSet");
      entity.newEmbeddedMap("eMap");
      entity.newLinkList("lList", List.of());
      entity.newLinkSet("lSet", new LinkedHashSet<>());
      entity.newLinkMap("lMap", new LinkedHashMap<>());

      var extracted = roundTripFullRecord(entity);
      // Bind each property and assertNotNull before isEmpty so a regression that dropped
      // a property entirely surfaces as a clean diagnostic rather than as an NPE on the
      // chained isEmpty() call.
      Collection<?> eList = extracted.getProperty("eList");
      Collection<?> eSet = extracted.getProperty("eSet");
      Map<?, ?> eMap = extracted.getProperty("eMap");
      Collection<?> lList = extracted.getProperty("lList");
      Collection<?> lSet = extracted.getProperty("lSet");
      Map<?, ?> lMap = extracted.getProperty("lMap");
      assertNotNull("eList must round-trip to a non-null container", eList);
      assertNotNull("eSet must round-trip to a non-null container", eSet);
      assertNotNull("eMap must round-trip to a non-null container", eMap);
      assertNotNull("lList must round-trip to a non-null container", lList);
      assertNotNull("lSet must round-trip to a non-null container", lSet);
      assertNotNull("lMap must round-trip to a non-null container", lMap);
      assertTrue(eList.isEmpty());
      assertTrue(eSet.isEmpty());
      assertTrue(eMap.isEmpty());
      assertTrue(lList.isEmpty());
      assertTrue(lSet.isEmpty());
      assertTrue(lMap.isEmpty());
    });
  }

  /**
   * Deeply nested embedded entity (3 levels): {@code Thing.address ↦ Address.nested ↦
   * Address.deepest}. Pins that {@code serializeWithClassName} recurses correctly through
   * the nested EMBEDDED case in {@code serializeValue} and the deserialiser's matching
   * {@code deserializeEmbeddedAsDocument} call recurses to the same depth.
   */
  @Test
  public void deeplyNestedEmbeddedEntitiesRoundTripWithIdentityAtAllLevels() {
    runInTx(() -> {
      var top = (EntityImpl) session.newEntity(THING_CLASS);
      var lvl1 = (EmbeddedEntityImpl) session.newEmbeddedEntity(ADDRESS_CLASS);
      lvl1.setProperty("street", "level-1");
      var lvl2 = (EmbeddedEntityImpl) session.newEmbeddedEntity(ADDRESS_CLASS);
      lvl2.setProperty("street", "level-2");
      var lvl3 = (EmbeddedEntityImpl) session.newEmbeddedEntity(ADDRESS_CLASS);
      lvl3.setProperty("street", "level-3");

      lvl2.setProperty("nested", lvl3, PropertyType.EMBEDDED);
      lvl1.setProperty("nested", lvl2, PropertyType.EMBEDDED);
      top.setProperty("address", lvl1, PropertyType.EMBEDDED);

      var extracted = roundTripFullRecord(top);
      var got1 = extracted.<EntityImpl>getProperty("address");
      assertNotNull(got1);
      assertEquals("level-1", got1.<String>getProperty("street"));
      var got2 = got1.<EntityImpl>getProperty("nested");
      assertNotNull(got2);
      assertEquals("level-2", got2.<String>getProperty("street"));
      var got3 = got2.<EntityImpl>getProperty("nested");
      assertNotNull(got3);
      assertEquals("level-3", got3.<String>getProperty("street"));
    });
  }

  /**
   * Symmetric to {@link #embeddedListWithNullElementsPreservesNullsAndOrder}: the read side
   * for EMBEDDEDSET also has a dedicated {@code if (itemType == null)} null-tombstone branch
   * — pin that the EMBEDDEDSET path preserves a null member rather than silently dropping
   * it or throwing. The serialiser writes the same per-element type-tombstone byte
   * ({@code -1}) for null members, regardless of LIST vs SET shape.
   */
  @Test
  public void embeddedSetWithNullElementPreservesNullViaTypeTombstone() {
    runInTx(() -> {
      var entity = (EntityImpl) session.newEntity(THING_CLASS);
      var input = new LinkedHashSet<Object>();
      input.add("a");
      input.add(null);
      entity.newEmbeddedSet("items", input);

      var extracted = roundTripFullRecord(entity);
      Collection<?> got = extracted.getProperty("items");
      assertNotNull(got);
      assertEquals(2, got.size());
      assertTrue("string element 'a' must round-trip", got.contains("a"));
      assertTrue("null element must round-trip in EMBEDDEDSET", got.contains(null));
    });
  }

  /**
   * EMBEDDEDLIST with element count crossing the single-byte size-varint boundary: the
   * size prefix is {@code VarIntSerializer.write(zigzag(size))}, and zig-zag(63)=126 fits
   * one byte while zig-zag(64)=128 spills into two bytes. Round-trip just past the
   * boundary (sizes 63, 64, 65, 200) so a regression in the size-varint encoding /
   * decoding path is caught — the simple-type test class pins the same boundary for
   * STRING/BINARY length prefixes; this is the collection-side equivalent.
   */
  @Test
  public void embeddedListAtAndPastSingleByteSizeVarintBoundaryRoundTrips() {
    int[] sizes = {63, 64, 65, 200};
    for (int size : sizes) {
      runInTx(() -> {
        var entity = (EntityImpl) session.newEntity(THING_CLASS);
        var input = new ArrayList<Object>();
        for (var i = 0; i < size; i++) {
          input.add(i);
        }
        entity.newEmbeddedList("items_" + size, input);

        var extracted = roundTripFullRecord(entity);
        Collection<?> got = extracted.getProperty("items_" + size);
        assertNotNull("size " + size + " must round-trip to a non-null container", got);
        assertEquals(
            "size " + size + " must round-trip to the same element count",
            size, got.size());
        var roundTripped = new ArrayList<>(got);
        assertEquals(
            "size " + size + " first element must be 0",
            0, roundTripped.get(0));
        assertEquals(
            "size " + size + " last element must be size-1",
            size - 1, roundTripped.get(size - 1));
      });
    }
  }

  /**
   * Latent inconsistency on the LINKLIST read path: {@code HelperClasses.readLinkCollection}
   * has an {@code if (id.equals(NULL_RECORD_ID)) found.addInternal(null)} branch, but
   * {@code EntityLinkListImpl.addInternal(null)} routes through
   * {@code LinkTrackedMultiValue.checkValue} which rejects null with a
   * {@code SchemaException("Cannot add a non-identifiable entity to a link based data
   * container")}. The result: a forged payload with the sentinel NULL_RECORD_ID encoding
   * (cluster {@code -2}, position {@code -1} per {@code HelperClasses.NULL_RECORD_ID})
   * always throws — the null-conversion branch is dead-on-arrival. Pin today's behavior
   * so a future fix that either reaches the null-branch or drops it produces a loud
   * failure; the production contradiction is forwarded to the deferred-cleanup track.
   *
   * <p>Forged bytes: {@code 00 02 03 01} — leading config byte, varint(zigzag(1))=0x02 size,
   * then varint(zigzag(-2))=0x03 for the cluster id and varint(zigzag(-1))=0x01 for the
   * position. WHEN-FIXED: when the LINKLIST read path is harmonised so the null sentinel
   * round-trips cleanly, replace this assertion with {@code assertNull(decoded.iterator()
   * .next())} to pin the new contract.
   */
  @Test
  public void linkListReadOfNullRecordIdSentinelThrowsSchemaExceptionPendingFix() {
    runInTx(() -> {
      var owner = (EntityImpl) session.newEntity(THING_CLASS);
      var forged = new byte[] {(byte) 0x00, (byte) 0x02, (byte) 0x03, (byte) 0x01};
      var ex = assertThrows(
          com.jetbrains.youtrackdb.internal.core.exception.SchemaException.class,
          () -> deserializeValueBytesWithOwner(
              PropertyTypeInternal.LINKLIST, forged, owner));
      assertTrue(
          "expected non-identifiable rejection diagnostic, got: " + ex.getMessage(),
          ex.getMessage().contains("non-identifiable"));
    });
  }

  /**
   * Null elements inside an EMBEDDEDLIST must survive the round-trip — they are encoded
   * with the type-tombstone byte ({@code -1}) by {@code writeEmbeddedCollection} and the
   * read side restores {@code null} at the corresponding index. Pin the contract so a
   * regression that dropped or substituted nulls would surface immediately.
   */
  @Test
  public void embeddedListWithNullElementsPreservesNullsAndOrder() {
    runInTx(() -> {
      var entity = (EntityImpl) session.newEntity(THING_CLASS);
      var input = new ArrayList<Object>();
      input.add("a");
      input.add(null);
      input.add("c");
      entity.newEmbeddedList("items", input);

      var extracted = roundTripFullRecord(entity);
      Collection<?> got = extracted.getProperty("items");
      var roundTripped = new ArrayList<>(got);
      assertEquals(3, roundTripped.size());
      assertEquals("a", roundTripped.get(0));
      assertNull("null at index 1 must round-trip", roundTripped.get(1));
      assertEquals("c", roundTripped.get(2));
    });
  }

  /**
   * EMBEDDEDLIST holding embedded entities (recursive case): each list element is an
   * abstract-class {@link EmbeddedEntityImpl} whose own properties round-trip through the
   * same {@code serializeValue} dispatch. Pin both the per-element entity preservation and
   * the collection-level ordering.
   */
  @Test
  public void embeddedListOfEmbeddedEntitiesPreservesPerEntityProperties() {
    runInTx(() -> {
      var entity = (EntityImpl) session.newEntity(THING_CLASS);
      var addr1 = (EmbeddedEntityImpl) session.newEmbeddedEntity(ADDRESS_CLASS);
      addr1.setProperty("street", "first");
      addr1.setProperty("zip", 1);
      var addr2 = (EmbeddedEntityImpl) session.newEmbeddedEntity(ADDRESS_CLASS);
      addr2.setProperty("street", "second");
      addr2.setProperty("zip", 2);
      var addrs = new ArrayList<EntityImpl>();
      addrs.add(addr1);
      addrs.add(addr2);
      entity.newEmbeddedList("addresses", addrs);

      var extracted = roundTripFullRecord(entity);
      Collection<?> got = extracted.getProperty("addresses");
      var roundTripped = new ArrayList<>(got);
      assertEquals(2, roundTripped.size());
      var first = (EntityImpl) roundTripped.get(0);
      var second = (EntityImpl) roundTripped.get(1);
      assertTrue("nested entities must remain embedded", first.isEmbedded());
      assertTrue("nested entities must remain embedded", second.isEmbedded());
      assertEquals("first", first.<String>getProperty("street"));
      assertEquals(Integer.valueOf(1), first.<Integer>getProperty("zip"));
      assertEquals("second", second.<String>getProperty("street"));
      assertEquals(Integer.valueOf(2), second.<Integer>getProperty("zip"));
    });
  }

  /**
   * A LINKLIST containing a mix of persisted and locally-loaded references must still
   * round-trip via the {@code session.load(rid)} path, since {@code newLinkList} stores
   * the loaded entities and {@code writeLinkCollection} extracts identities at write time.
   */
  @Test
  public void linkListMixedPersistedAndLoadedReferencesRoundTripsCorrectly() {
    var p1 = persistPeerWithName("mixed1");
    var p2 = persistPeerWithName("mixed2");
    runInTx(() -> {
      var entity = (EntityImpl) session.newEntity(THING_CLASS);
      // Mix raw RID and loaded EntityImpl — both must surface as Identifiable on the read
      // side. The newLinkList API accepts Collection<? extends Identifiable>; RID itself
      // implements Identifiable, so both shapes are valid input.
      var input = new ArrayList<Identifiable>();
      input.add(p1);
      input.add((EntityImpl) session.load(p2));
      entity.newLinkList("links", input);

      var extracted = roundTripFullRecord(entity);
      Collection<? extends Identifiable> got = extracted.getProperty("links");
      var ids = new ArrayList<>();
      got.forEach(i -> ids.add(i.getIdentity()));
      assertEquals(List.of(p1, p2), ids);
    });
  }

  // ============================================================================
  // === Helpers ===============================================================
  // ============================================================================

  /**
   * Persist a fresh {@code Peer} entity in its own transaction and return the resulting
   * persistent RID. The commit makes the RID safe for downstream LINK / LINKLIST / etc.
   * tests (the serialiser rejects any non-persistent identity).
   */
  private RID persistPeerWithName(String name) {
    session.begin();
    var peer = (EntityImpl) session.newEntity(PEER_CLASS);
    peer.setProperty("name", name);
    session.commit();
    return peer.getIdentity();
  }

  /**
   * Run the supplied body inside a fresh transaction; any active transaction left over
   * from {@link #persistPeerWithName} is rolled back first. Always rolls back the body's
   * transaction on exit so successive tests do not stack open transactions.
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
   * Tier-1 helper: serialise a single {@code value} through {@link RecordSerializerBinaryV1}
   * value dispatch into a fresh {@link BytesContainer}, returning the produced byte slice.
   * Schema and encryption are unused for the collection types covered here.
   */
  private byte[] serializeValueBytes(PropertyTypeInternal type, Object value) {
    var bytes = new BytesContainer();
    v1.serializeValue(session, bytes, value, type, null, null, null);
    return bytes.fitBytes();
  }

  /** Variant of {@link #serializeValueBytes(PropertyTypeInternal, Object)} that passes a
   * non-null {@code linkedType} — used by EMBEDDEDLIST / EMBEDDEDSET tests that pin the
   * homogeneous-typed encoding branch. */
  private byte[] serializeValueBytesWithLinkedType(
      PropertyTypeInternal type, Object value, PropertyTypeInternal linkedType) {
    var bytes = new BytesContainer();
    v1.serializeValue(session, bytes, value, type, linkedType, null, null);
    return bytes.fitBytes();
  }

  /**
   * Tier-1 helper: deserialise {@code encoded} as a single value of {@code type} via the
   * value dispatch. Sibling of {@link #serializeValueBytes(PropertyTypeInternal, Object)}.
   * The {@code owner} is null — usable only for value types whose deserialise path does
   * not allocate an owner-bound wrapper. Collection types that allocate
   * {@code EntityLinkListImpl(owner)} / {@code EntityLinkMapIml(owner)} must instead use
   * {@link #deserializeValueBytesWithOwner(PropertyTypeInternal, byte[], EntityImpl)}.
   */
  private Object deserializeValueBytes(PropertyTypeInternal type, byte[] encoded) {
    var bytes = new BytesContainer(encoded);
    return v1.deserializeValue(session, bytes, type, null, false, null);
  }

  /**
   * Owner-passing variant of {@link #deserializeValueBytes(PropertyTypeInternal, byte[])}.
   * Required for LINK collection types (LINKLIST / LINKMAP) whose deserialise path
   * unconditionally allocates an owner-bound wrapper before inspecting the encoded bytes,
   * and which therefore NPEs with a null owner.
   */
  private Object deserializeValueBytesWithOwner(
      PropertyTypeInternal type, byte[] encoded, EntityImpl owner) {
    var bytes = new BytesContainer(encoded);
    return v1.deserializeValue(session, bytes, type, owner, false, null);
  }

  /**
   * Tier-2 helper: serialise the supplied {@link EntityImpl} through
   * {@link RecordSerializerBinary#toStream}, then deserialise into a fresh entity via
   * {@link RecordSerializerBinary#fromStream} and return it. The empty {@code String[]}
   * passed to {@code fromStream} signals "deserialise all fields" (the partial-fields
   * branch is exercised separately in the simple-type test class).
   */
  private EntityImpl roundTripFullRecord(EntityImpl entity) {
    var serialized = recordSerializer.toStream(session, entity);
    if (serialized.length == 0) {
      fail("recordSerializer.toStream returned an empty byte array");
    }
    var extracted = (EntityImpl) session.newEntity();
    recordSerializer.fromStream(session, serialized, extracted, new String[] {});
    return extracted;
  }
}
