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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.exception.SerializationException;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.record.RecordAbstract;
import com.jetbrains.youtrackdb.internal.core.record.impl.EdgeEntityImpl;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.record.impl.VertexEntityImpl;
import com.jetbrains.youtrackdb.internal.core.sql.executor.TestUtilsFixture;
import org.junit.Before;
import org.junit.Test;

/**
 * Mode-flag tests for {@link JSONSerializerJackson#IMPORT_INSTANCE}. The import instance is the
 * production deserialiser used by
 * {@link com.jetbrains.youtrackdb.internal.core.db.tool.DatabaseImport} when the JSON export is
 * exporter-version >=14 (the modern import path). Constructor flags vs the default
 * {@link JSONSerializerJackson#INSTANCE} are:
 *
 * <pre>
 *   readUnescapedControlChars        : false  (same as INSTANCE)
 *   readOldFieldTypesFormat          : false  (same as INSTANCE)
 *   readAllowGraphStructure          : true   (DIFFERENT — only flag that changes vs INSTANCE)
 *   readPrefixUnderscoreReplacements : empty  (same as INSTANCE)
 * </pre>
 *
 * <p>Three of the four flags match the default {@code INSTANCE}, so the behavioural surface
 * unique to {@code IMPORT_INSTANCE} is the {@code readAllowGraphStructure} branch. The other
 * three flags are pinned here as <em>parity</em> tests against {@code INSTANCE}: a regression
 * that flipped one of them on {@code IMPORT_INSTANCE} (e.g., copy-pasted
 * {@code IMPORT_BACKWARDS_COMPAT_INSTANCE}'s constructor args by mistake) would surface as a
 * falsifiable test break, even though the resulting behaviour is "same as default".
 *
 * <h2>Two production effects of {@code readAllowGraphStructure=true}</h2>
 *
 * <ol>
 *   <li><b>Edge record creation</b> — a JSON entity whose {@code @class} resolves to an edge-type
 *       schema class is materialised through {@code session.newEdgeInternal(className)} instead
 *       of throwing {@link java.io.UnsupportedEncodingException} (wrapped as
 *       {@link SerializationException} by the {@code fromStringWithMetadata} catch-all).
 *   <li><b>Property validation skipped</b> on graph fields (vertex {@code in_*}/{@code out_*} and
 *       edge {@code in}/{@code out}). The validation step would otherwise throw
 *       {@link IllegalArgumentException} ("is booked as a name that can be used to manage
 *       edges") because {@link VertexEntityImpl#checkPropertyName(String)} and
 *       {@link com.jetbrains.youtrackdb.internal.core.record.impl.EdgeInternal#checkPropertyName(String)}
 *       reject those names by default. Vertex {@code in_*}/{@code out_*} fields are also
 *       auto-promoted to {@code LINKBAG} type when the JSON does not carry an explicit type —
 *       this is observable indirectly via the success-vs-failure parity below.
 * </ol>
 *
 * <h2>Test fixture pattern</h2>
 *
 * <p>Each test constructs JSON by hand (rather than round-tripping through the serialiser) so the
 * mode-flag distinction is the single behavioural variable. Deserialisation goes through {@link
 * JSONSerializerJackson#fromStringWithMetadata(com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded,
 * String, RecordAbstract, boolean)} with {@code ignoreRid=true} so the deserialiser allocates a
 * fresh record instead of short-circuiting through {@code session.load(rid)} (Step 5 precedent).
 *
 * <p>Where graph fields cannot be observed via the public property API (the
 * {@link EntityImpl#getPropertyType(String)} and {@link EntityImpl#getProperty(String)}
 * accessors call {@link EntityImpl#validatePropertyName(String, boolean)}, which subclasses
 * override to reject {@code in}/{@code out}/{@code in_*}/{@code out_*}), the assertions use
 * {@link EntityImpl#hasProperty(String)} and {@link EntityImpl#getPropertyInternal(String)}
 * which bypass that override.
 */
public class JSONSerializerJacksonImportInstanceTest extends TestUtilsFixture {

  private static final String VERTEX_SUBCLASS = "MyVertex";
  private static final String EDGE_SUBCLASS = "MyEdge";
  private static final String REGULAR_CLASS = "Thing";

  /**
   * Schema setup runs <b>before</b> any test opens its tx — schema changes are not transactional
   * in YouTrackDB, so wrapping them in a tx adds no benefit and would mask leaks.
   */
  @Before
  public void prepareSchema() {
    session.createVertexClass(VERTEX_SUBCLASS);
    session.createEdgeClass(EDGE_SUBCLASS);
    var thing = session.getMetadata().getSchema().getOrCreateClass(REGULAR_CLASS);
    if (thing.getProperty("name") == null) {
      thing.createProperty("name", PropertyType.STRING);
    }
  }

  // ====================================================================== helpers

  /**
   * Deserialise via {@code IMPORT_INSTANCE} with {@code ignoreRid=true} so the deserialiser
   * allocates a fresh record instead of short-circuiting through {@code session.load(rid)}.
   * The caller is responsible for opening a tx — the deserialiser's {@code session.newEntity},
   * {@code session.newVertex}, and {@code session.newEdgeInternal} all require it.
   */
  private RecordAbstract parseImport(String json) {
    return JSONSerializerJackson.IMPORT_INSTANCE
        .fromStringWithMetadata(session, json, null, true)
        .first();
  }

  /**
   * Deserialise via the default {@code INSTANCE}. Used to pin <em>falsifiable</em> distinctions:
   * a JSON that {@code IMPORT_INSTANCE} accepts must surface a {@link SerializationException} (or
   * a different parsed shape) on {@code INSTANCE}.
   */
  private RecordAbstract parseDefault(String json) {
    return JSONSerializerJackson.INSTANCE
        .fromStringWithMetadata(session, json, null, true)
        .first();
  }

  /** Run the supplied body inside a fresh tx; the {@code @After} fixture rolls it back. */
  private void inTx(Runnable body) {
    if (session.isTxActive()) {
      session.rollback();
    }
    session.begin();
    body.run();
  }

  /** Persist a vertex of the test subclass and return its persistent identity literal. */
  private String persistVertexRid() {
    session.begin();
    var v = session.newVertex(VERTEX_SUBCLASS);
    session.commit();
    return v.getIdentity().toString();
  }

  // ====================================================================== readAllowGraphStructure: edge creation

  @Test
  public void edgeRecordCanBeCreatedFromJsonWhenAllowGraphStructureIsTrue() {
    // The signature behavioural distinction. With readAllowGraphStructure=true the JSON
    // path materialises an EdgeEntityImpl via session.newEdgeInternal(className); without
    // it, the same JSON throws UnsupportedEncodingException, surfaced as SerializationException.
    var inRid = persistVertexRid();
    var outRid = persistVertexRid();
    var json = "{\"@type\":\"e\",\"@class\":\"" + EDGE_SUBCLASS
        + "\",\"in\":\"" + inRid + "\",\"out\":\"" + outRid + "\"}";
    inTx(() -> {
      var parsed = parseImport(json);
      assertNotNull(parsed);
      assertTrue(
          "IMPORT_INSTANCE must materialise the edge as EdgeEntityImpl",
          parsed instanceof EdgeEntityImpl);
      assertEquals(EdgeEntityImpl.RECORD_TYPE, parsed.getRecordType());
      var edge = (EdgeEntityImpl) parsed;
      assertEquals(EDGE_SUBCLASS, edge.getSchemaClassName());
      // The in/out fields are reachable via the EdgeEntityImpl accessors that bypass the
      // validate-property-name override (which would otherwise throw "is booked").
      assertEquals(inRid, edge.getToLink().getIdentity().toString());
      assertEquals(outRid, edge.getFromLink().getIdentity().toString());
    });
  }

  @Test
  public void edgeRecordCreationFailsForDefaultInstanceWithSameJson() {
    // Falsifiable parity: the same edge JSON that IMPORT_INSTANCE accepts MUST throw on the
    // default INSTANCE. A regression that copied IMPORT's allowGraphStructure=true into INSTANCE
    // would silently start materialising edges through the default path, breaking the contract
    // that DatabaseExport-emitted JSON is the single producer of edge records.
    var inRid = persistVertexRid();
    var outRid = persistVertexRid();
    var json = "{\"@type\":\"e\",\"@class\":\"" + EDGE_SUBCLASS
        + "\",\"in\":\"" + inRid + "\",\"out\":\"" + outRid + "\"}";
    inTx(() -> {
      var ex = assertThrows(SerializationException.class, () -> parseDefault(json));
      // The wrapped cause is UnsupportedEncodingException with the documented message.
      var cause = ex.getCause();
      assertNotNull("default INSTANCE must wrap a cause", cause);
      assertTrue(
          "expected UnsupportedEncodingException, got " + cause.getClass(),
          cause instanceof java.io.UnsupportedEncodingException);
      assertEquals("Edges can not be created from JSON", cause.getMessage());
    });
  }

  // ====================================================================== readAllowGraphStructure: vertex graph fields

  @Test
  public void vertexInPrefixFieldAcceptedByImportInstance() {
    // The vertex `in_*` graph-field arm. Under IMPORT_INSTANCE, parseAnyList returns a
    // LinkList from a RID-only array, then the import path overrides type=LINKBAG and skips
    // FULL property-name validation — so the deserialisation succeeds. The default INSTANCE
    // would reject the same JSON because the unskipped validatePropertyName override on
    // VertexEntityImpl rejects `in_*` as booked.
    var ridA = persistVertexRid();
    var ridB = persistVertexRid();
    var json = "{\"@type\":\"v\",\"@class\":\"" + VERTEX_SUBCLASS
        + "\",\"in_Edge\":[\"" + ridA + "\",\"" + ridB + "\"]}";
    inTx(() -> {
      var parsed = parseImport(json);
      assertNotNull(parsed);
      assertTrue(parsed instanceof VertexEntityImpl);
      var vertex = (EntityImpl) parsed;
      // Internal accessor — the public getPropertyType/getProperty would throw "is booked".
      assertTrue(
          "IMPORT_INSTANCE must store the in_* field internally",
          vertex.hasProperty("in_Edge"));
      assertNotNull(vertex.getPropertyInternal("in_Edge"));
    });
  }

  @Test
  public void vertexOutPrefixFieldAcceptedByImportInstance() {
    // Symmetric pin for the out_* prefix arm — exercises the OR branch of the graph-field
    // gate independently from the in_* branch.
    var rid = persistVertexRid();
    var json = "{\"@type\":\"v\",\"@class\":\"" + VERTEX_SUBCLASS
        + "\",\"out_Edge\":[\"" + rid + "\"]}";
    inTx(() -> {
      var vertex = (EntityImpl) parseImport(json);
      assertTrue(vertex.hasProperty("out_Edge"));
    });
  }

  @Test
  public void vertexInPrefixFieldRejectedByDefaultInstance() {
    // Falsifiable parity: same JSON via default INSTANCE must throw because FULL property-name
    // validation rejects `in_*` as booked. This is the per-property analogue of the
    // edge-creation parity test above — a regression that flipped readAllowGraphStructure=true
    // on INSTANCE would silently start accepting graph fields on the default path.
    var rid = persistVertexRid();
    var json = "{\"@type\":\"v\",\"@class\":\"" + VERTEX_SUBCLASS
        + "\",\"in_Edge\":[\"" + rid + "\"]}";
    inTx(() -> {
      var ex = assertThrows(SerializationException.class, () -> parseDefault(json));
      var cause = ex.getCause();
      assertNotNull(cause);
      assertTrue(
          "expected IllegalArgumentException for booked graph-field name, got " + cause,
          cause instanceof IllegalArgumentException);
      assertTrue(
          "message should mention the booked-name reservation; was: " + cause.getMessage(),
          cause.getMessage().contains("booked"));
    });
  }

  @Test
  public void nonGraphFieldOnVertexAcceptedByDefaultInstance() {
    // Negative pin: a regular non-graph field on a vertex (no in_/out_ prefix) is accepted by
    // BOTH instances. The graph-field gate must require the prefix to fire — otherwise it
    // would short-circuit FULL validation on every vertex field.
    var json = "{\"@type\":\"v\",\"@class\":\"" + VERTEX_SUBCLASS
        + "\",\"label\":\"test-vertex\"}";
    inTx(() -> {
      var importParsed = (EntityImpl) parseImport(json);
      assertEquals("test-vertex", importParsed.<String>getProperty("label"));
      // And on the default instance, since there is no graph-field prefix, FULL validation
      // does not throw — the gate is correctly per-field not per-record.
      var defaultParsed = (EntityImpl) parseDefault(json);
      assertEquals("test-vertex", defaultParsed.<String>getProperty("label"));
    });
  }

  @Test
  public void edgeInOutFieldsAccessibleViaEdgeAccessors() {
    // The IMPORT_INSTANCE path sets the `in`/`out` properties on the new EdgeEntityImpl
    // through setPropertyInternal with SKIP validation; the EdgeEntityImpl-side accessors
    // (getFromLink/getToLink, which use getLinkPropertyInternal — bypassing the
    // validatePropertyName override) read them back.
    var inRid = persistVertexRid();
    var outRid = persistVertexRid();
    var json = "{\"@type\":\"e\",\"@class\":\"" + EDGE_SUBCLASS
        + "\",\"in\":\"" + inRid + "\",\"out\":\"" + outRid + "\"}";
    inTx(() -> {
      var edge = (EdgeEntityImpl) parseImport(json);
      assertEquals(inRid, ((Identifiable) edge.getToLink()).getIdentity().toString());
      assertEquals(outRid, ((Identifiable) edge.getFromLink()).getIdentity().toString());
      // hasProperty bypasses the validate-name override and confirms the internal map carries
      // the parsed in/out entries.
      assertTrue(edge.hasProperty("in"));
      assertTrue(edge.hasProperty("out"));
    });
  }

  // ====================================================================== readOldFieldTypesFormat=false (parity with INSTANCE)

  @Test
  public void legacyStringFieldTypesFormatRejectedByImportInstance() {
    // IMPORT_INSTANCE shares INSTANCE's readOldFieldTypesFormat=false. A JSON whose @fieldTypes
    // is the legacy CSV string form must be rejected — the parseFieldTypes branch hits
    // "Bad @fieldTypes format". This pins the parity flag: a regression that flipped IMPORT's
    // oldFieldTypesFormat to true (e.g., copy-paste from BACKWARDS_COMPAT) would silently
    // start accepting legacy export files via the wrong code path.
    var json = "{\"@type\":\"d\",\"@class\":\"" + REGULAR_CLASS
        + "\",\"@fieldTypes\":\"name=t\",\"name\":\"x\"}";
    inTx(() -> {
      var ex = assertThrows(SerializationException.class, () -> parseImport(json));
      var cause = ex.getCause();
      assertNotNull(cause);
      assertTrue(
          "expected SerializationException with bad-fieldTypes message, got " + cause,
          cause instanceof SerializationException);
      assertTrue(
          "message should report the bad @fieldTypes shape; was: " + cause.getMessage(),
          cause.getMessage().contains("Bad @fieldTypes format"));
    });
  }

  @Test
  public void objectFieldTypesFormatStillAcceptedByImportInstance() {
    // Positive parity pin: the modern object form of @fieldTypes is unaffected by the
    // oldFieldTypesFormat flag. Both INSTANCE and IMPORT_INSTANCE accept it, which means a
    // regression that broke the modern form on IMPORT (e.g., flipping oldFieldTypesFormat=true
    // and erroneously dropping the object branch) would also fail the round-trip.
    var json = "{\"@type\":\"d\",\"@class\":\"" + REGULAR_CLASS
        + "\",\"@fieldTypes\":{\"name\":\"S\"},\"name\":\"x\"}";
    inTx(() -> {
      var entity = (EntityImpl) parseImport(json);
      assertEquals("x", entity.<String>getProperty("name"));
    });
  }

  // ====================================================================== readPrefixUnderscoreReplacements=empty (parity with INSTANCE)

  @Test
  public void dollarPrefixFieldRejectedByImportInstance() {
    // IMPORT_INSTANCE shares INSTANCE's empty replacements set. A `$`-prefixed field name is
    // NOT replaced with `_` (which is what BACKWARDS_COMPAT_INSTANCE does), so the field
    // reaches EntityImpl.isSystemProperty (line 3209) — `$` is non-letter and non-underscore,
    // so it is classified as a system property and rejected. The parity break is observable:
    // BACKWARDS_COMPAT replaces first, then the now-`_`-prefixed name passes the same check.
    var json = "{\"@type\":\"d\",\"@class\":\"" + REGULAR_CLASS
        + "\",\"$myField\":\"alpha\"}";
    inTx(() -> {
      var ex = assertThrows(SerializationException.class, () -> parseImport(json));
      var cause = ex.getCause();
      assertNotNull(cause);
      assertTrue(
          "expected wrapped SerializationException; was: " + cause,
          cause instanceof SerializationException);
      assertTrue(
          "message should mention system property; was: " + cause.getMessage(),
          cause.getMessage().contains("System property"));
    });
  }

  // ====================================================================== readUnescapedControlChars=false (parity with INSTANCE)

  @Test
  public void unescapedControlCharInStringRejectedByImportInstance() {
    // IMPORT_INSTANCE shares INSTANCE's readUnescapedControlChars=false. A literal control char
    // (raw tab 0x09) inside a quoted string must be rejected by the underlying Jackson parser
    // — the JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS feature is OFF. This pins the parity
    // flag: a regression that enabled it on IMPORT would silently start accepting legacy
    // export files via the wrong code path.
    var json = "{\"@type\":\"d\",\"@class\":\"" + REGULAR_CLASS
        + "\",\"name\":\"alpha	beta\"}";
    inTx(() -> {
      var ex = assertThrows(SerializationException.class, () -> parseImport(json));
      // Pin the cause-chain reason so a regression where the rejection happens in an unrelated
      // code path (e.g., schema lookup, missing-brace gate) would surface — without this, only
      // the umbrella SerializationException type was being checked.
      var chain = chainMessagesOf(ex).toLowerCase();
      assertTrue(
          "expected unescaped-control-char diagnostic in cause chain, got: " + chainMessagesOf(ex),
          chain.contains("illegal unquoted character") || chain.contains("control character"));
    });
  }

  /**
   * Walk the {@link Throwable#getCause()} chain and concatenate every non-null message. Used by
   * exception-pin tests that want to assert on the underlying Jackson diagnostic rather than the
   * outer wrap message — the cause chain is more stable across re-wraps.
   */
  private static String chainMessagesOf(Throwable t) {
    var sb = new StringBuilder();
    for (Throwable c = t; c != null; c = c.getCause()) {
      if (c.getMessage() != null) {
        sb.append(c.getMessage()).append(" | ");
      }
    }
    return sb.toString();
  }

  @Test
  public void escapedControlCharStillAcceptedByImportInstance() {
    // Positive parity pin: a properly-escaped \t is always accepted, regardless of the
    // unescaped-control-chars flag. Documents the flag's gate by example — "the flag controls
    // ONLY the unescaped form".
    var json = "{\"@type\":\"d\",\"@class\":\"" + REGULAR_CLASS
        + "\",\"name\":\"alpha\\tbeta\"}";
    inTx(() -> {
      var entity = (EntityImpl) parseImport(json);
      assertEquals("alpha\tbeta", entity.<String>getProperty("name"));
    });
  }

  // ====================================================================== sanity: regular Entity round-trip

  @Test
  public void regularEntityRoundTripsViaImportInstance() {
    // Sanity: the import-mode flags don't break the default Entity round-trip path. The
    // graph-structure branch is gated by isVertex/isEdge/Edge schema lookup, so a non-graph
    // Entity flows through identically to the INSTANCE path.
    var json = "{\"@type\":\"d\",\"@class\":\"" + REGULAR_CLASS
        + "\",\"name\":\"hello\"}";
    inTx(() -> {
      var entity = (EntityImpl) parseImport(json);
      assertEquals(EntityImpl.RECORD_TYPE, entity.getRecordType());
      assertEquals(REGULAR_CLASS, entity.getSchemaClassName());
      assertEquals("hello", entity.<String>getProperty("name"));
      // And the regular case has nothing to do with graph-field promotion — the property type
      // for a STRING-typed schema property comes from the schema, not from a LINKBAG override.
      assertEquals(PropertyType.STRING, entity.getPropertyType("name"));
      // Negative pin: hasProperty does NOT spuriously report graph-field membership for
      // non-graph fields.
      assertFalse(entity.hasProperty("in_Edge"));
      assertFalse(entity.hasProperty("out_Edge"));
    });
  }
}
