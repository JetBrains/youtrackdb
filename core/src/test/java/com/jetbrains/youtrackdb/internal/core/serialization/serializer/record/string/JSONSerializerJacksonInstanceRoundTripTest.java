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
package com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.string;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Blob;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.exception.SerializationException;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.record.RecordAbstract;
import com.jetbrains.youtrackdb.internal.core.record.impl.EmbeddedEntityImpl;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.sql.executor.TestUtilsFixture;
import com.jetbrains.youtrackdb.internal.core.util.DateHelper;
import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Before;
import org.junit.Test;

/**
 * Round-trip tests for the default {@link JSONSerializerJackson#INSTANCE}. The default instance
 * is the production path used by {@link RecordAbstract#toJSON()},
 * {@link com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded#parseJSON},
 * {@link com.jetbrains.youtrackdb.internal.core.db.tool.DatabaseExport}, and several SQL methods;
 * the import-mode siblings ({@code IMPORT_INSTANCE}, {@code IMPORT_BACKWARDS_COMPAT_INSTANCE})
 * are covered by separate test classes.
 *
 * <h2>Per-property semantic equivalence</h2>
 *
 * <p>Identity-based {@code RecordAbstract.equals} cannot decide round-trip equality between two
 * distinct {@link EntityImpl} instances even when their property values match. Each test
 * therefore asserts at the property level using the per-type rules drafted in the track plan:
 * {@code Objects.equals} for the eight scalar / string types; {@code floatToIntBits} /
 * {@code doubleToLongBits} for floating-point (preserves {@code NaN} identity);
 * {@code BigDecimal.compareTo == 0} for {@code DECIMAL} (scale loss is intentional);
 * {@link Date#equals(Object)} after timezone normalisation for {@code DATETIME};
 * truncation to the database calendar's midnight for {@code DATE};
 * {@link java.util.Arrays#equals(byte[], byte[])} for {@code BINARY};
 * {@link Identifiable#getIdentity()} equality for {@code LINK}; element-wise recursion for
 * collections; and recursive per-property comparison for embedded entities. Every type test
 * pairs a positive round-trip with a falsifiable assertion that would catch a regression
 * defaulting to {@code Object.equals} (reference identity).
 *
 * <h2>Three public entry points exercised</h2>
 *
 * <ul>
 *   <li>{@link JSONSerializerJackson#fromString(com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded,
 *       String)} — new-record path.</li>
 *   <li>{@link JSONSerializerJackson#fromString(com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded,
 *       String, RecordAbstract)} — apply-onto-existing-record path.</li>
 *   <li>{@link JSONSerializerJackson#toString(com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded,
 *       com.jetbrains.youtrackdb.internal.core.db.record.record.DBRecord, StringWriter, String)}
 *       and {@link JSONSerializerJackson#recordToJson(com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded,
 *       com.jetbrains.youtrackdb.internal.core.db.record.record.DBRecord, JsonGenerator, String)}
 *       — serialise paths.</li>
 *   <li>{@link JSONSerializerJackson#fromStringWithMetadata(com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded,
 *       String, RecordAbstract, boolean)} — exercises the private {@code recordFromJson} helper
 *       indirectly and exposes the metadata pair.</li>
 *   <li>{@link JSONSerializerJackson#mapFromJson(String)} / {@link JSONSerializerJackson#mapToJson(Map)}
 *       and {@link JSONSerializerJackson#serializeEmbeddedMap(com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded,
 *       JsonGenerator, Map, String)} — the map-only entry points.</li>
 * </ul>
 *
 * <h2>{@code ignoreRid=true} round-trip discipline</h2>
 *
 * <p>{@code fromString(session, json)} short-circuits on a serialised {@code @rid} field by
 * issuing {@code session.load(rid)}, which returns the cached original object — making property-
 * level equality trivially pass for any "round-trip". To genuinely exercise the JSON-to-record
 * deserialisation path the tests below build the round-trip via
 * {@code fromStringWithMetadata(session, json, null, true)}, which forces the deserialiser to
 * allocate a fresh {@link EntityImpl} via {@code session.newEntity(...)} and parse properties
 * into it. Tests that need the cache-hit shape exercise it explicitly via {@code fromString}.
 */
public class JSONSerializerJacksonInstanceRoundTripTest extends TestUtilsFixture {

  private static final String CLASS_NAME = "Thing";
  private static final String PEER_CLASS = "Peer";
  private static final String EMBEDDED_CLASS = "Address";

  @Before
  public void prepareSchema() {
    // Schema changes are NOT transactional — create classes BEFORE any test opens its tx.
    var schema = session.getMetadata().getSchema();
    schema.getOrCreateClass(CLASS_NAME);
    schema.getOrCreateClass(PEER_CLASS);
    var address = schema.getOrCreateClass(EMBEDDED_CLASS);
    // Address must be abstract so the deserialiser's `cls.isAbstract()` branch in
    // parseRecordMetadata flips embeddedValue to true when the JSON does not carry the
    // explicit @embedded marker (DEFAULT_FORMAT does not include "markEmbeddedEntities").
    if (!address.isAbstract()) {
      address.setAbstract(true);
    }
    if (address.getProperty("street") == null) {
      address.createProperty("street", PropertyType.STRING);
      address.createProperty("zip", PropertyType.INTEGER);
    }
  }

  // ====================================================================== helpers

  /**
   * Persist a fresh {@code Thing} entity inside a tx, run the supplied setup, commit, and then
   * open a fresh tx and return the loaded entity bound to it. The bound reference is needed
   * because subsequent {@link #serialize(RecordAbstract)} calls go through the session and
   * require records that are bound to an active transaction. The fixture's {@code @After
   * rollbackIfLeftOpen} guard from {@link TestUtilsFixture} cleans up the second tx.
   */
  private EntityImpl persistThing(java.util.function.Consumer<EntityImpl> setup) {
    session.begin();
    var entity = (EntityImpl) session.newEntity(CLASS_NAME);
    setup.accept(entity);
    session.commit();
    // Begin a fresh tx so the loaded entity is bound to it — the fixture's @After rolls back.
    session.begin();
    return (EntityImpl) session.load(entity.getIdentity());
  }

  private RID persistPeerWithName(String name) {
    session.begin();
    var peer = (EntityImpl) session.newEntity(PEER_CLASS);
    peer.setProperty("name", name);
    session.commit();
    return peer.getIdentity();
  }

  private String serialize(RecordAbstract record) {
    return JSONSerializerJackson.INSTANCE
        .toString(session, record, new StringWriter(), RecordAbstract.DEFAULT_FORMAT)
        .toString();
  }

  /**
   * Parse the supplied JSON into a fresh {@link EntityImpl} via {@code ignoreRid=true}, which
   * forces allocation of a new entity (otherwise the deserialiser would short-circuit by loading
   * the cached original from the session). The caller is responsible for opening a transaction
   * before calling — {@code session.newEntity} requires it. The {@code @After} guard inherited
   * from {@link TestUtilsFixture} rolls back any open tx on test exit.
   */
  private EntityImpl parseFreshAsEntity(String json) {
    return (EntityImpl) JSONSerializerJackson.INSTANCE
        .fromStringWithMetadata(session, json, null, true)
        .first();
  }

  /**
   * Run the supplied assertions inside a fresh transaction. Closes any tx left open by
   * {@link #persistThing} first so that the body runs in a clean tx (avoids nested-tx semantics
   * that would otherwise stack on top of the persistThing tx). Used by tests that round-trip
   * via {@code parseFreshAsEntity} — the deserialiser calls {@code session.newEntity} which
   * requires an active tx.
   */
  private void inFreshTx(Runnable body) {
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

  // ====================================================================== INTEGER / LONG / SHORT / BYTE

  @Test
  public void integerRoundTripPreservesValueExactly() {
    var original = persistThing(e -> e.setProperty("v", 12345, PropertyType.INTEGER));
    var json = serialize(original);
    inFreshTx(() -> {
      var parsed = parseFreshAsEntity(json);
      assertEquals(Integer.valueOf(12345), parsed.<Integer>getProperty("v"));
      assertEquals(PropertyType.INTEGER, parsed.getPropertyType("v"));
    });
  }

  @Test
  public void longRoundTripPreservesValueExactly() {
    var original = persistThing(e -> e.setProperty("v", 9_876_543_210L, PropertyType.LONG));
    var json = serialize(original);
    inFreshTx(() -> {
      var parsed = parseFreshAsEntity(json);
      assertEquals(Long.valueOf(9_876_543_210L), parsed.<Long>getProperty("v"));
      assertEquals(PropertyType.LONG, parsed.getPropertyType("v"));
    });
  }

  @Test
  public void shortRoundTripPreservesValueExactly() {
    var original = persistThing(e -> e.setProperty("v", (short) -7, PropertyType.SHORT));
    var json = serialize(original);
    inFreshTx(() -> {
      var parsed = parseFreshAsEntity(json);
      assertEquals(Short.valueOf((short) -7), parsed.<Short>getProperty("v"));
      assertEquals(PropertyType.SHORT, parsed.getPropertyType("v"));
    });
  }

  @Test
  public void byteRoundTripPreservesValueExactly() {
    var original = persistThing(e -> e.setProperty("v", (byte) 42, PropertyType.BYTE));
    var json = serialize(original);
    inFreshTx(() -> {
      var parsed = parseFreshAsEntity(json);
      assertEquals(Byte.valueOf((byte) 42), parsed.<Byte>getProperty("v"));
      assertEquals(PropertyType.BYTE, parsed.getPropertyType("v"));
    });
  }

  // ====================================================================== BOOLEAN / STRING

  @Test
  public void booleanRoundTripPreservesTrueAndFalse() {
    var original = persistThing(e -> {
      e.setProperty("t", Boolean.TRUE, PropertyType.BOOLEAN);
      e.setProperty("f", Boolean.FALSE, PropertyType.BOOLEAN);
    });
    var json = serialize(original);
    inFreshTx(() -> {
      var parsed = parseFreshAsEntity(json);
      assertEquals(Boolean.TRUE, parsed.<Boolean>getProperty("t"));
      assertEquals(Boolean.FALSE, parsed.<Boolean>getProperty("f"));
    });
  }

  @Test
  public void stringRoundTripPreservesUnicodeAndEscapeChars() {
    // The escape-bearing string would fail a naive String comparison if either side were
    // double-decoded or single-decoded — Jackson's default behaviour preserves it once.
    var input = "line1\n\"quoted\" tab\t\\backslash unicode é 中";
    var original = persistThing(e -> e.setProperty("s", input, PropertyType.STRING));
    var json = serialize(original);
    inFreshTx(() -> {
      var parsed = parseFreshAsEntity(json);
      assertEquals(input, parsed.<String>getProperty("s"));
    });
  }

  // ====================================================================== FLOAT / DOUBLE

  @Test
  public void floatRoundTripPreservesValueViaIntBits() {
    // Use floatToIntBits so a regression that drops to Float.equals (and incorrectly identifies
    // -0.0f with +0.0f) would fail.
    var input = -0.0f;
    var original = persistThing(e -> e.setProperty("v", input, PropertyType.FLOAT));
    var json = serialize(original);
    inFreshTx(() -> {
      var parsed = parseFreshAsEntity(json);
      var got = parsed.<Float>getProperty("v");
      assertEquals(
          "floatToIntBits round-trip",
          Float.floatToIntBits(input),
          Float.floatToIntBits(got));
    });
  }

  @Test
  public void floatRoundTripPreservesPositiveAndNegativeInfinity() {
    var original = persistThing(e -> {
      e.setProperty("pos", Float.POSITIVE_INFINITY, PropertyType.FLOAT);
      e.setProperty("neg", Float.NEGATIVE_INFINITY, PropertyType.FLOAT);
    });
    var json = serialize(original);
    inFreshTx(() -> {
      var parsed = parseFreshAsEntity(json);
      assertEquals(
          Float.floatToIntBits(Float.POSITIVE_INFINITY),
          Float.floatToIntBits(parsed.<Float>getProperty("pos")));
      assertEquals(
          Float.floatToIntBits(Float.NEGATIVE_INFINITY),
          Float.floatToIntBits(parsed.<Float>getProperty("neg")));
    });
  }

  @Test
  public void doubleRoundTripPreservesValueViaLongBits() {
    var input = -0.0d;
    var original = persistThing(e -> e.setProperty("v", input, PropertyType.DOUBLE));
    var json = serialize(original);
    inFreshTx(() -> {
      var parsed = parseFreshAsEntity(json);
      assertEquals(
          "doubleToLongBits round-trip",
          Double.doubleToLongBits(input),
          Double.doubleToLongBits(parsed.<Double>getProperty("v")));
    });
  }

  @Test
  public void doubleRoundTripPreservesLargeMagnitudeWithoutOverflow() {
    // Pick an exactly-representable double to side-step any text-format precision question.
    var input = 1.7976931348623157E308d; // Double.MAX_VALUE
    var original = persistThing(e -> e.setProperty("v", input, PropertyType.DOUBLE));
    var json = serialize(original);
    inFreshTx(() -> {
      var parsed = parseFreshAsEntity(json);
      assertEquals(
          Double.doubleToLongBits(input),
          Double.doubleToLongBits(parsed.<Double>getProperty("v")));
    });
  }

  // ====================================================================== DECIMAL

  @Test
  public void decimalRoundTripCompareIsZeroEvenAcrossScale() {
    // The DECIMAL equivalence rule is BigDecimal.compareTo == 0, NOT Object.equals — the latter
    // would fail on scale changes. Pin the rule with a value that has a non-canonical scale
    // (`123.4500` has scale 4 but compares equal to `123.45` which has scale 2). The deserialiser
    // is free to normalise the scale through Double; compareTo accepts that. The actual
    // precision boundary of the JSON path is documented in the round-trip-large-decimal pin
    // below; this test pins the scale-tolerance contract specifically.
    var input = new BigDecimal("123.4500");
    var original = persistThing(e -> e.setProperty("v", input, PropertyType.DECIMAL));
    var json = serialize(original);
    inFreshTx(() -> {
      var parsed = parseFreshAsEntity(json);
      var got = parsed.<BigDecimal>getProperty("v");
      assertNotNull(got);
      assertEquals(
          "BigDecimal.compareTo must be 0 — naive Object.equals would fail on scale change",
          0,
          input.compareTo(got));
      // Falsifiable per-rule: a regression that bypassed compareTo and used equals() would still
      // pass the line above when scales matched; force a non-matching scale to surface the rule.
      assertNotEquals(
          "scales must differ to make the compareTo-vs-equals distinction falsifiable",
          input,
          got);
    });
  }

  @Test
  public void decimalRoundTripPreservesNegativeAndZero() {
    var pos = new BigDecimal("3.14");
    var neg = new BigDecimal("-100.500");
    var zero = BigDecimal.ZERO;
    var original = persistThing(e -> {
      e.setProperty("pos", pos, PropertyType.DECIMAL);
      e.setProperty("neg", neg, PropertyType.DECIMAL);
      e.setProperty("zero", zero, PropertyType.DECIMAL);
    });
    var json = serialize(original);
    inFreshTx(() -> {
      var parsed = parseFreshAsEntity(json);
      assertEquals(0, pos.compareTo(parsed.<BigDecimal>getProperty("pos")));
      assertEquals(0, neg.compareTo(parsed.<BigDecimal>getProperty("neg")));
      assertEquals(0, zero.compareTo(parsed.<BigDecimal>getProperty("zero")));
    });
  }

  // ====================================================================== DATE / DATETIME

  @Test
  public void dateRoundTripTruncatesToDbCalendarMidnight() {
    // A non-midnight Date is stored — the DATE round-trip must truncate to midnight in the DB
    // calendar timezone (not the JVM default).
    var dbCal = DateHelper.getDatabaseCalendar(session);
    dbCal.set(2024, Calendar.MARCH, 15, 13, 27, 31);
    dbCal.set(Calendar.MILLISECOND, 456);
    var nonMidnight = dbCal.getTime();

    var expectedCal = DateHelper.getDatabaseCalendar(session);
    expectedCal.setTime(nonMidnight);
    expectedCal.set(Calendar.HOUR_OF_DAY, 0);
    expectedCal.set(Calendar.MINUTE, 0);
    expectedCal.set(Calendar.SECOND, 0);
    expectedCal.set(Calendar.MILLISECOND, 0);
    var expectedMidnight = expectedCal.getTime();

    var original = persistThing(e -> e.setProperty("d", nonMidnight, PropertyType.DATE));
    var json = serialize(original);
    inFreshTx(() -> {
      var parsed = parseFreshAsEntity(json);
      var got = parsed.<Date>getProperty("d");
      assertEquals(expectedMidnight, got);
      assertNotEquals(
          "DATE round-trip must truncate — pin so a regression that returned the raw Date fails",
          nonMidnight,
          got);
    });
  }

  @Test
  public void datetimeRoundTripPreservesExactEpochMillis() {
    // DATETIME does NOT truncate — it preserves epoch ms exactly. Round-trip via
    // Date.equals after deserialisation.
    var input = new Date(1_710_504_451_456L);
    var original = persistThing(e -> e.setProperty("d", input, PropertyType.DATETIME));
    var json = serialize(original);
    inFreshTx(() -> {
      var parsed = parseFreshAsEntity(json);
      assertEquals(input, parsed.<Date>getProperty("d"));
      assertEquals(input.getTime(), parsed.<Date>getProperty("d").getTime());
    });
  }

  @Test
  public void datetimeAndDateAreSerializedDifferently() {
    // DATETIME ms != DATE ms (the latter is truncated). Pin the difference so a regression that
    // collapsed both to the same code path is caught.
    var dbCal = new GregorianCalendar(DateHelper.getDatabaseTimeZone(session));
    dbCal.set(2024, Calendar.MARCH, 15, 13, 27, 31);
    dbCal.set(Calendar.MILLISECOND, 456);
    var nonMidnight = dbCal.getTime();

    var original = persistThing(e -> {
      e.setProperty("d", nonMidnight, PropertyType.DATE);
      e.setProperty("t", nonMidnight, PropertyType.DATETIME);
    });
    var json = serialize(original);
    inFreshTx(() -> {
      var parsed = parseFreshAsEntity(json);
      var date = parsed.<Date>getProperty("d");
      var datetime = parsed.<Date>getProperty("t");
      assertNotEquals(
          "DATE and DATETIME must round-trip differently — DATE truncates to midnight",
          datetime,
          date);
      assertEquals(nonMidnight.getTime(), datetime.getTime());
    });
  }

  // ====================================================================== BINARY

  @Test
  public void binaryRoundTripPreservesAllBytesIncludingExtremes() {
    // The extremes (-128, 0, 127) plus a few mid-range bytes shake out any narrowing bug in the
    // base64 round-trip.
    var bytes = new byte[] {-128, -1, 0, 1, 42, 127, 0x55};
    var original = persistThing(e -> e.setProperty("b", bytes, PropertyType.BINARY));
    var json = serialize(original);
    inFreshTx(() -> {
      var parsed = parseFreshAsEntity(json);
      var got = parsed.<byte[]>getProperty("b");
      assertNotNull(got);
      assertNotSame(
          "deserialisation must allocate a fresh byte[] — regression to alias would tie producer "
              + "and consumer lifecycles",
          bytes,
          got);
      assertArrayEquals(bytes, got);
    });
  }

  @Test
  public void binaryEmptyByteArrayRoundTripsToEmptyByteArray() {
    // Edge case: zero-length BINARY. The serialiser uses Jackson writeBinary which encodes an
    // empty array as the empty base64 string ""; the deserialiser's BINARY branch picks up the
    // length<=3 BYTE-conversion shortcut for inputs of 1-3 chars and falls through to base64 for
    // empty / >3-char inputs — pin the empty-string fall-through.
    var bytes = new byte[0];
    var original = persistThing(e -> e.setProperty("b", bytes, PropertyType.BINARY));
    var json = serialize(original);
    inFreshTx(() -> {
      var parsed = parseFreshAsEntity(json);
      var got = parsed.<byte[]>getProperty("b");
      assertArrayEquals(bytes, got);
    });
  }

  // ====================================================================== LINK / LINKLIST / LINKSET / LINKMAP

  @Test
  public void linkRoundTripPreservesIdentity() {
    var peer1 = persistPeerWithName("alpha");
    var original = persistThing(e -> e.setProperty("ref", peer1, PropertyType.LINK));
    var json = serialize(original);
    inFreshTx(() -> {
      var parsed = parseFreshAsEntity(json);
      var got = parsed.<Identifiable>getProperty("ref");
      assertNotNull(got);
      assertEquals(peer1, got.getIdentity());
    });
  }

  @Test
  public void linkListRoundTripPreservesOrderedIdentities() {
    var p1 = persistPeerWithName("first");
    var p2 = persistPeerWithName("second");
    var p3 = persistPeerWithName("third");
    var original = persistThing(e -> e.newLinkList("links", List.of(p1, p2, p3)));
    var json = serialize(original);
    inFreshTx(() -> {
      var parsed = parseFreshAsEntity(json);
      Collection<? extends Identifiable> got = parsed.getProperty("links");
      assertNotNull(got);
      var ids = new ArrayList<RID>();
      got.forEach(i -> ids.add(i.getIdentity()));
      assertEquals(List.of(p1, p2, p3), ids);
    });
  }

  @Test
  public void linkSetRoundTripPreservesIdentitySet() {
    var p1 = persistPeerWithName("s1");
    var p2 = persistPeerWithName("s2");
    var original =
        persistThing(e -> e.newLinkSet("links", new LinkedHashSet<>(List.of(p1, p2))));
    var json = serialize(original);
    inFreshTx(() -> {
      var parsed = parseFreshAsEntity(json);
      Collection<? extends Identifiable> got = parsed.getProperty("links");
      assertNotNull(got);
      var ids = new LinkedHashSet<RID>();
      got.forEach(i -> ids.add(i.getIdentity()));
      assertEquals(Set.of(p1, p2), ids);
    });
  }

  @Test
  public void linkMapRoundTripPreservesKeyToIdentityMapping() {
    var p1 = persistPeerWithName("m1");
    var p2 = persistPeerWithName("m2");
    var original = persistThing(e -> {
      var sourceMap = new LinkedHashMap<String, Identifiable>();
      sourceMap.put("a", p1);
      sourceMap.put("b", p2);
      e.newLinkMap("links", sourceMap);
    });
    var json = serialize(original);
    inFreshTx(() -> {
      var parsed = parseFreshAsEntity(json);
      Map<String, ? extends Identifiable> got = parsed.getProperty("links");
      assertNotNull(got);
      assertEquals(2, got.size());
      assertEquals(p1, got.get("a").getIdentity());
      assertEquals(p2, got.get("b").getIdentity());
    });
  }

  @Test
  public void linkSerializeFailsWhenLinkedRidIsNotPersistent() {
    // The serialiser explicitly refuses non-persistent RIDs (cluster position == -1 etc.). Pin
    // the contract by committing the holder (its RID becomes persistent) and then attaching a
    // freshly-allocated, uncommitted entity as a LINK — the serialise call must fail with the
    // canonical diagnostic.
    var holder = persistThing(e -> {
    });
    var holderId = holder.getIdentity();
    inFreshTx(() -> {
      var dangling = (EntityImpl) session.newEntity(PEER_CLASS);
      var loaded = (EntityImpl) session.load(holderId);
      loaded.setProperty("ref", dangling, PropertyType.LINK);
      var ex = assertThrows(SerializationException.class, () -> serialize(loaded));
      var msg = chainMessages(ex);
      assertTrue(
          "expected non-persistent-link rejection, got: " + msg,
          msg.contains("non-persistent link"));
    });
  }

  // ====================================================================== EMBEDDED entity / collections

  @Test
  public void embeddedEntityRoundTripPreservesAllProperties() {
    // Embedded entities recurse — their properties round-trip just like the top-level record's.
    // DEFAULT_FORMAT does NOT include "markEmbeddedEntities", so no @embedded:true marker is
    // emitted; the deserialiser instead infers the embedded shape from the abstract class
    // schema (see prepareSchema). This pair pins both halves of that contract.
    var original = persistThing(e -> {
      var addr = (EmbeddedEntityImpl) session.newEmbeddedEntity(EMBEDDED_CLASS);
      addr.setProperty("street", "Main 1");
      addr.setProperty("zip", 12345);
      e.setProperty("address", addr, PropertyType.EMBEDDED);
    });
    var json = serialize(original);
    assertFalse(
        "DEFAULT_FORMAT must NOT emit @embedded marker — pin so a regression that flipped the "
            + "default would surface: " + json,
        json.contains("\"@embedded\":true"));
    inFreshTx(() -> {
      var parsed = parseFreshAsEntity(json);
      var addr = parsed.<EntityImpl>getProperty("address");
      assertNotNull(addr);
      assertTrue(
          "embedded property must round-trip into an embedded entity",
          addr.isEmbedded());
      assertEquals("Main 1", addr.<String>getProperty("street"));
      assertEquals(Integer.valueOf(12345), addr.<Integer>getProperty("zip"));
    });
  }

  @Test
  public void embeddedEntityRoundTripWithExplicitMarkerFormatEmitsMarker() {
    // The "markEmbeddedEntities" format token explicitly opts into the @embedded:true marker.
    // Pin both the marker emission and that the parsed entity is still embedded.
    var original = persistThing(e -> {
      var addr = (EmbeddedEntityImpl) session.newEmbeddedEntity(EMBEDDED_CLASS);
      addr.setProperty("street", "Main 2");
      addr.setProperty("zip", 67890);
      e.setProperty("address", addr, PropertyType.EMBEDDED);
    });
    var json = JSONSerializerJackson.INSTANCE
        .toString(
            session,
            original,
            new StringWriter(),
            "rid,version,class,type,keepTypes,markEmbeddedEntities")
        .toString();
    assertTrue(
        "expected @embedded:true marker when format includes markEmbeddedEntities, got: " + json,
        json.contains("\"@embedded\":true"));
    inFreshTx(() -> {
      var parsed = parseFreshAsEntity(json);
      var addr = parsed.<EntityImpl>getProperty("address");
      assertNotNull(addr);
      assertTrue(addr.isEmbedded());
      assertEquals("Main 2", addr.<String>getProperty("street"));
      assertEquals(Integer.valueOf(67890), addr.<Integer>getProperty("zip"));
    });
  }

  @Test
  public void embeddedListRoundTripPreservesOrderAndElementValues() {
    var input = List.of("a", "b", "c");
    var original = persistThing(e -> e.newEmbeddedList("list", input));
    var json = serialize(original);
    inFreshTx(() -> {
      var parsed = parseFreshAsEntity(json);
      Collection<?> got = parsed.getProperty("list");
      assertNotNull(got);
      assertEquals(input, new ArrayList<>(got));
    });
  }

  @Test
  public void embeddedSetRoundTripPreservesElementValues() {
    var input = new LinkedHashSet<>(List.of(1, 2, 3));
    var original = persistThing(e -> e.newEmbeddedSet("set", input));
    var json = serialize(original);
    inFreshTx(() -> {
      var parsed = parseFreshAsEntity(json);
      Collection<?> got = parsed.getProperty("set");
      assertNotNull(got);
      assertEquals(input, new LinkedHashSet<>(got));
    });
  }

  @Test
  public void embeddedMapRoundTripPreservesEntries() {
    var input = new LinkedHashMap<String, Object>();
    input.put("name", "Alpha");
    input.put("count", 7);
    var original = persistThing(e -> e.newEmbeddedMap("map", input));
    var json = serialize(original);
    inFreshTx(() -> {
      var parsed = parseFreshAsEntity(json);
      Map<?, ?> got = parsed.getProperty("map");
      assertNotNull(got);
      assertEquals(2, got.size());
      assertEquals("Alpha", got.get("name"));
      // The numeric-typing fall-through reads JSON integer literals through Jackson's
      // getNumberValue, which returns Integer for values that fit in 32 bits — the
      // EMBEDDEDMAP branch does NOT apply per-value PropertyType conversion since linkedType is
      // unset. Pin the observed shape so a regression that started forcing LONG/Double through
      // an unwanted conversion path is caught.
      assertEquals(Integer.valueOf(7), got.get("count"));
      assertEquals("Integer must be the concrete class for small JSON integer literals",
          Integer.class, got.get("count").getClass());
    });
  }

  // ====================================================================== null property

  @Test
  public void nullPropertySerializesAsJsonNullAndRoundTrips() {
    var original = persistThing(e -> {
      e.setProperty("kept", "value", PropertyType.STRING);
      e.setProperty("nulled", null, PropertyType.STRING);
    });
    var json = serialize(original);
    assertTrue("expected null literal in serialised JSON: " + json,
        json.contains("\"nulled\":null"));
    inFreshTx(() -> {
      var parsed = parseFreshAsEntity(json);
      assertEquals("value", parsed.<String>getProperty("kept"));
      assertNull(parsed.<Object>getProperty("nulled"));
    });
  }

  // ====================================================================== entry-point tests

  @Test
  public void fromStringNewRecordPathLoadsExistingRecordViaRid() {
    // Without ignoreRid the deserialiser short-circuits to session.load(rid) — verify the
    // contract by feeding back the JSON of a committed record and getting back an instance bound
    // to the same RID. session.load requires an active tx, so we wrap the assertion in one.
    var original = persistThing(e -> e.setProperty("name", "loaded", PropertyType.STRING));
    var originalId = original.getIdentity();
    var json = serialize(original);
    inFreshTx(() -> {
      var got = JSONSerializerJackson.INSTANCE.fromString(session, json);
      assertNotNull(got);
      assertEquals(
          "fromString(session, json) must return a record with the same RID — session.load path",
          originalId,
          got.getIdentity());
    });
  }

  @Test
  public void fromStringApplyOntoExistingRecordOverwritesProperties() {
    // The 3-arg overload applies the JSON onto the supplied record in place. Build an entity,
    // serialise it, mutate it locally, then re-apply the JSON onto it and assert that the
    // mutation was overwritten.
    var original = persistThing(e -> e.setProperty("v", 100, PropertyType.INTEGER));
    var originalId = original.getIdentity();
    var json = serialize(original);
    inFreshTx(() -> {
      var reloaded = (EntityImpl) session.load(originalId);
      reloaded.setProperty("v", 999, PropertyType.INTEGER);
      var got = JSONSerializerJackson.INSTANCE.fromString(session, json, reloaded);
      assertSame("must mutate the same record instance", reloaded, got);
      assertEquals(
          "in-place apply must restore the JSON's value over the local mutation",
          Integer.valueOf(100),
          ((EntityImpl) got).<Integer>getProperty("v"));
    });
  }

  @Test
  public void fromStringWithMetadataExposesRidAndClassMetadata() {
    var original = persistThing(e -> {
      e.setProperty("v", 1, PropertyType.INTEGER);
    });
    var json = serialize(original);
    inFreshTx(() -> {
      var pair = JSONSerializerJackson.INSTANCE
          .fromStringWithMetadata(session, json, null, true);
      assertNotNull(pair.first());
      assertNotNull(pair.second());
      var meta = pair.second();
      assertEquals(CLASS_NAME, meta.className());
      assertEquals(EntityImpl.RECORD_TYPE, meta.recordType());
      // ignoreRid was true, but parseRecordMetadata still parses @rid into the metadata pair —
      // pin the preservation so a regression that dropped it is caught.
      assertNotNull("@rid must surface in metadata even when ignoreRid is true", meta.recordId());
      assertEquals(original.getIdentity(), meta.recordId());
    });
  }

  @Test
  public void recordToJsonViaJsonGeneratorMatchesToStringOutput() throws Exception {
    var original = persistThing(e -> e.setProperty("x", 5, PropertyType.INTEGER));

    // toString output as the reference.
    var fromToString = serialize(original);

    // recordToJson produces the same bytes when handed a generator built off a StringWriter and
    // the same DEFAULT_FORMAT — pin the equivalence between the two public entry points.
    var sw = new StringWriter();
    var factory = new JsonFactory();
    try (JsonGenerator gen = factory.createGenerator(sw)) {
      JSONSerializerJackson.INSTANCE
          .recordToJson(session, original, gen, RecordAbstract.DEFAULT_FORMAT);
    }
    assertEquals(fromToString, sw.toString());
  }

  @Test
  public void mapToJsonAndMapFromJsonRoundTripPlainMap() {
    var input = new LinkedHashMap<String, Object>();
    input.put("k1", "v1");
    input.put("k2", 42);
    input.put("k3", true);
    input.put("k4", null);
    var json = JSONSerializerJackson.INSTANCE.mapToJson(input);
    var got = JSONSerializerJackson.INSTANCE.mapFromJson(json);
    assertEquals(input.keySet(), got.keySet());
    assertEquals("v1", got.get("k1"));
    // Jackson decodes JSON integer literals to Integer in this map shape — pin the observed type.
    assertEquals(42, got.get("k2"));
    assertEquals(true, got.get("k3"));
    assertNull(got.get("k4"));
  }

  @Test
  public void mapFromJsonWrapsParsingFailureAsSerializationException() {
    var ex = assertThrows(
        SerializationException.class,
        () -> JSONSerializerJackson.INSTANCE.mapFromJson("{not-valid-json"));
    assertTrue(
        "expected unmarshalling-content message, got: " + ex.getMessage(),
        chainMessages(ex).toLowerCase().contains("unmarshal"));
  }

  @Test
  public void mapFromJsonInputStreamRoundTripsAgainstMapToJson() throws Exception {
    var input = new HashMap<String, Object>();
    input.put("k", 1);
    input.put("list", List.of("a", "b"));
    var json = JSONSerializerJackson.INSTANCE.mapToJson(input);
    var got = JSONSerializerJackson.INSTANCE
        .mapFromJson(new ByteArrayInputStream(json.getBytes("UTF-8")));
    assertEquals(1, got.get("k"));
    assertEquals(List.of("a", "b"), got.get("list"));
  }

  @Test
  public void serializeEmbeddedMapPublicOverloadEmitsObjectShape() throws Exception {
    var sw = new StringWriter();
    var factory = new JsonFactory();
    var input = new LinkedHashMap<String, Object>();
    input.put("a", 1);
    input.put("b", "two");
    try (JsonGenerator gen = factory.createGenerator(sw)) {
      JSONSerializerJackson.INSTANCE
          .serializeEmbeddedMap(session, gen, input, RecordAbstract.DEFAULT_FORMAT);
    }
    var written = sw.toString();
    assertTrue(
        "expected object-shape JSON for embedded map, got: " + written,
        written.startsWith("{") && written.endsWith("}"));
    assertTrue(written.contains("\"a\":1"));
    assertTrue(written.contains("\"b\":\"two\""));
  }

  // ====================================================================== Blob

  @Test
  public void blobRoundTripPreservesContentBytes() throws Exception {
    var bytes = new byte[] {1, 2, 3, 4, -1, 127};
    session.begin();
    var blob = session.newBlob();
    blob.fromInputStream(new ByteArrayInputStream(bytes));
    session.commit();
    var blobId = blob.getIdentity();

    // Open a fresh tx for loading: the blob reference loses its tx binding after commit.
    session.begin();
    var loaded = (Blob) session.load(blobId);
    var json = serialize((RecordAbstract) loaded);
    assertTrue(
        "expected @type:b record-type marker for Blob, got: " + json,
        json.contains("\"@type\":\"b\""));
    inFreshTx(() -> {
      var parsed = (Blob) JSONSerializerJackson.INSTANCE
          .fromStringWithMetadata(session, json, null, true)
          .first();
      assertNotNull(parsed);
      assertArrayEquals(bytes, ((RecordAbstract) parsed).toStream());
    });
  }

  // ====================================================================== error paths

  @Test
  public void fromStringRejectsJsonNotStartingWithObject() {
    // The deserialiser requires the top-level token to be START_OBJECT — anything else is a
    // SerializationException.
    var ex = assertThrows(
        SerializationException.class,
        () -> JSONSerializerJackson.INSTANCE.fromString(session, "[1,2,3]"));
    var msg = chainMessages(ex);
    assertTrue(
        "expected start-of-object diagnostic, got: " + msg,
        msg.toLowerCase().contains("start of the object"));
  }

  @Test
  public void fromStringRejectsTrailingTokensAfterClosingObject() {
    // Anything after the closing brace is a SerializationException. Wrap in a tx because the
    // deserialiser successfully parses the metadata first and calls session.newInstance(...)
    // before its trailing-token check fires — the newInstance call requires an active tx.
    inFreshTx(() -> {
      var ex = assertThrows(
          SerializationException.class,
          () -> JSONSerializerJackson.INSTANCE.fromString(session, "{\"@class\":\"Thing\"} 99"));
      var msg = chainMessages(ex);
      assertTrue(
          "expected end-of-JSON-object diagnostic, got: " + msg,
          msg.toLowerCase().contains("end of the json object"));
    });
  }

  @Test
  public void fromStringRejectsUnknownAtAttribute() {
    // The recordMetadata parser rejects any @-prefixed field name it does not recognise.
    var ex = assertThrows(
        SerializationException.class,
        () -> JSONSerializerJackson.INSTANCE.fromString(session, "{\"@bogus\":\"x\"}"));
    var msg = chainMessages(ex);
    assertTrue(
        "expected unexpected-field diagnostic, got: " + msg,
        msg.toLowerCase().contains("unexpected field"));
  }

  @Test
  public void fromStringRejectsUnknownClassName() {
    var ex = assertThrows(
        SerializationException.class,
        () -> JSONSerializerJackson.INSTANCE.fromString(
            session, "{\"@class\":\"NoSuchClass\",\"v\":1}"));
    var msg = chainMessages(ex);
    assertTrue(
        "expected class-not-found diagnostic, got: " + msg,
        msg.toLowerCase().contains("class not found"));
  }

  @Test
  public void fromStringRejectsSystemPropertyName() {
    // Property names starting with '@' that are not record metadata keywords are forbidden.
    var ex = assertThrows(
        SerializationException.class,
        () -> JSONSerializerJackson.INSTANCE.fromString(
            session, "{\"@class\":\"Thing\",\"@version\":1,\"@notMeta\":1}"));
    var msg = chainMessages(ex);
    assertTrue(
        "expected unexpected-field or invalid-property diagnostic, got: " + msg,
        msg.toLowerCase().contains("unexpected field")
            || msg.toLowerCase().contains("invalid property"));
  }

  @Test
  public void fromStringApplyOntoExistingRecordRejectsClassMismatch() {
    var thing = persistThing(e -> e.setProperty("v", 1, PropertyType.INTEGER));
    var thingId = thing.getIdentity();
    var jsonForOtherClass = "{\"@class\":\"Peer\",\"name\":\"x\"}";
    inFreshTx(() -> {
      var reloaded = (EntityImpl) session.load(thingId);
      var ex = assertThrows(
          SerializationException.class,
          () -> JSONSerializerJackson.INSTANCE
              .fromString(session, jsonForOtherClass, reloaded));
      var msg = chainMessages(ex);
      assertTrue(
          "expected class-name-mismatch diagnostic, got: " + msg,
          msg.toLowerCase().contains("class name mismatch"));
    });
  }

  // ====================================================================== misc

  @Test
  public void toStringIdentifierReturnsJacksonLabel() {
    // The serializer overrides toString to advertise itself as "jackson" — pin so a future
    // rename surfaces in tests rather than silently going through.
    assertEquals("jackson", JSONSerializerJackson.INSTANCE.toString());
  }

  @Test
  public void multipleHeterogeneousPropertiesRoundTripTogether() {
    // A small mixed-type record exercises the whole property loop in a single pass — pin so a
    // regression that broke ordering, the generator close, or the field-name boundary is caught.
    var original = persistThing(e -> {
      e.setProperty("i", 7, PropertyType.INTEGER);
      e.setProperty("s", "tag", PropertyType.STRING);
      e.setProperty("d", new BigDecimal("0.5"), PropertyType.DECIMAL);
      e.setProperty("flag", true, PropertyType.BOOLEAN);
    });
    var json = serialize(original);
    inFreshTx(() -> {
      var parsed = parseFreshAsEntity(json);
      assertEquals(Integer.valueOf(7), parsed.<Integer>getProperty("i"));
      assertEquals("tag", parsed.<String>getProperty("s"));
      assertEquals(0, new BigDecimal("0.5").compareTo(parsed.<BigDecimal>getProperty("d")));
      assertEquals(Boolean.TRUE, parsed.<Boolean>getProperty("flag"));
    });
  }

  @Test
  public void floatDoubleSentinelsSurviveAcrossRoundTripWithoutCoercion() {
    // Pin that FLOAT and DOUBLE round-trip independently: a regression that collapsed both onto
    // the same code path would surface as a type-class change between the two properties.
    var original = persistThing(e -> {
      e.setProperty("f", 1.5f, PropertyType.FLOAT);
      e.setProperty("d", 1.5d, PropertyType.DOUBLE);
    });
    var json = serialize(original);
    inFreshTx(() -> {
      var parsed = parseFreshAsEntity(json);
      assertEquals(Float.class, parsed.<Object>getProperty("f").getClass());
      assertEquals(Double.class, parsed.<Object>getProperty("d").getClass());
      assertEquals(1.5f, parsed.<Float>getProperty("f"), 0.0f);
      assertEquals(1.5d, parsed.<Double>getProperty("d"), 0.0d);
    });
  }

  @Test
  public void linkSetRoundTripDoesNotPreserveDuplicates() {
    // LinkedHashSet de-dupes on insertion before serialisation — pin the behaviour so a
    // regression that started preserving structurally-identical RIDs as duplicates is caught.
    var p1 = persistPeerWithName("dup");
    var input = new LinkedHashSet<Identifiable>();
    input.add(p1);
    input.add(p1);
    assertEquals("LinkedHashSet itself must dedupe pre-serialisation", 1, input.size());
    var original = persistThing(e -> e.newLinkSet("links", input));
    var json = serialize(original);
    inFreshTx(() -> {
      var parsed = parseFreshAsEntity(json);
      Collection<? extends Identifiable> got = parsed.getProperty("links");
      assertEquals(1, got.size());
    });
  }

  // Walk a wrapped exception chain into a single colon-joined diagnostic. The serializer wraps
  // the underlying parser failure inside SerializationException, then again with database
  // context — message-text assertions should look at the whole chain.
  private static String chainMessages(Throwable t) {
    var sb = new StringBuilder();
    var cur = t;
    while (cur != null) {
      if (sb.length() > 0) {
        sb.append(" :: ");
      }
      sb.append(cur.getClass().getSimpleName()).append(": ").append(cur.getMessage());
      cur = cur.getCause();
    }
    return sb.toString();
  }
}
