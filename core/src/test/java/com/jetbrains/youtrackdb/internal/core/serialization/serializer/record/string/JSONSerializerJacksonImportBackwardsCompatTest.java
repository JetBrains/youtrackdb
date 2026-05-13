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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.exception.SerializationException;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.record.RecordAbstract;
import com.jetbrains.youtrackdb.internal.core.record.impl.EdgeEntityImpl;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.sql.executor.TestUtilsFixture;
import org.junit.Before;
import org.junit.Test;

/**
 * Mode-flag tests for {@link JSONSerializerJackson#IMPORT_BACKWARDS_COMPAT_INSTANCE}. The
 * backwards-compat instance is the production deserialiser used by
 * {@link com.jetbrains.youtrackdb.internal.core.db.tool.DatabaseImport} when (a) the user passes
 * {@code -backwardCompatMode=true} OR (b) the JSON export's {@code exporter-version} field is
 * less than 14 (i.e., a legacy 1.x export file). All four constructor flags differ from
 * {@link JSONSerializerJackson#INSTANCE}:
 *
 * <pre>
 *   readUnescapedControlChars        : true   (vs INSTANCE: false)
 *   readOldFieldTypesFormat          : true   (vs INSTANCE: false)
 *   readAllowGraphStructure          : true   (same as IMPORT_INSTANCE)
 *   readPrefixUnderscoreReplacements : {'$'}  (vs INSTANCE / IMPORT_INSTANCE: empty)
 * </pre>
 *
 * <h2>Test surface</h2>
 *
 * <p>Each of the four flag distinctions is pinned by a positive test (the flag's effect IS
 * observable) and a falsifiable parity test against {@link JSONSerializerJackson#INSTANCE} or
 * {@link JSONSerializerJackson#IMPORT_INSTANCE} (the same JSON behaves DIFFERENTLY through the
 * other path). Each pair makes a copy-paste regression on the constructor flags loud:
 *
 * <ul>
 *   <li><b>readOldFieldTypesFormat=true</b> — accepts the legacy CSV string form
 *       {@code "@fieldTypes":"name=t"}; the modern object form is also still accepted.
 *   <li><b>readPrefixUnderscoreReplacements={'$'}</b> — replaces a leading {@code $} with
 *       {@code _} <em>before</em> the {@link EntityImpl#isSystemProperty(String)} guard, so a
 *       JSON field named {@code $myField} lands under {@code _myField}. {@code IMPORT_INSTANCE}
 *       and {@code INSTANCE} reject the same JSON because no replacement happens and {@code $}
 *       triggers the system-property guard.
 *   <li><b>readUnescapedControlChars=true</b> — accepts a literal control char (raw tab 0x09)
 *       inside a quoted string; {@code IMPORT_INSTANCE} rejects it.
 *   <li><b>readAllowGraphStructure=true</b> — same effect as {@code IMPORT_INSTANCE}: edges can
 *       be materialised from JSON. Pinned here as parity so a regression that disabled the
 *       graph-structure path on the legacy code path would also fail.
 * </ul>
 *
 * <h2>Residual gap — legacy 1.x export files</h2>
 *
 * <p>{@link com.jetbrains.youtrackdb.internal.core.db.tool.DatabaseImport} flips the active
 * serialiser to {@code IMPORT_BACKWARDS_COMPAT_INSTANCE} when it parses {@code exporter-version
 * < 14} from the export's {@code info} block (DatabaseImport.java:416 trigger). Constructing a
 * full legacy 1.x export fixture in this track is out of scope — the trigger is documented here
 * for reachability. The deletion / hardening queue absorbs this residual; pinning the entire
 * 1.x export shape belongs in the database-tool track or in a deferred-cleanup follow-up.
 *
 * <h2>Test fixture pattern</h2>
 *
 * <p>Same shape as {@link JSONSerializerJacksonImportInstanceTest}: hand-crafted JSON,
 * {@code fromStringWithMetadata(session, json, null, true)} entry point with {@code
 * ignoreRid=true}, and {@code TestUtilsFixture}'s {@code @After rollbackIfLeftOpen}.
 */
public class JSONSerializerJacksonImportBackwardsCompatTest extends TestUtilsFixture {

  private static final String VERTEX_SUBCLASS = "MyVertex";
  private static final String EDGE_SUBCLASS = "MyEdge";
  private static final String REGULAR_CLASS = "Thing";

  @Before
  public void prepareSchema() {
    session.createVertexClass(VERTEX_SUBCLASS);
    session.createEdgeClass(EDGE_SUBCLASS);
    var thing = session.getMetadata().getSchema().getOrCreateClass(REGULAR_CLASS);
    if (thing.getProperty("name") == null) {
      thing.createProperty("name", PropertyType.STRING);
    }
    if (thing.getProperty("legacyDate") == null) {
      // Add a property that the legacy `@fieldTypes:"legacyDate=t"` will type as DATETIME on
      // parse; the schema entry confirms determineType reads from schema first.
      thing.createProperty("legacyDate", PropertyType.DATETIME);
    }
  }

  // ====================================================================== helpers

  private RecordAbstract parseBackwardsCompat(String json) {
    return JSONSerializerJackson.IMPORT_BACKWARDS_COMPAT_INSTANCE
        .fromStringWithMetadata(session, json, null, true)
        .first();
  }

  private RecordAbstract parseImport(String json) {
    return JSONSerializerJackson.IMPORT_INSTANCE
        .fromStringWithMetadata(session, json, null, true)
        .first();
  }

  private RecordAbstract parseDefault(String json) {
    return JSONSerializerJackson.INSTANCE
        .fromStringWithMetadata(session, json, null, true)
        .first();
  }

  private void inTx(Runnable body) {
    if (session.isTxActive()) {
      session.rollback();
    }
    session.begin();
    body.run();
  }

  private String persistVertexRid() {
    session.begin();
    var v = session.newVertex(VERTEX_SUBCLASS);
    session.commit();
    return v.getIdentity().toString();
  }

  // ====================================================================== readOldFieldTypesFormat=true

  @Test
  public void legacyStringFieldTypesFormatAcceptedByBackwardsCompatInstance() {
    // The CSV-string form `"@fieldTypes":"a=t,b=c"` is the legacy 1.x export format. The
    // BACKWARDS_COMPAT path's parseFieldTypes branch splits on `,` then on `=`, populating the
    // type map for downstream parseValue calls. Single-entry case (the simplest shape).
    var json = "{\"@type\":\"d\",\"@class\":\"" + REGULAR_CLASS
        + "\",\"@fieldTypes\":\"name=S\",\"name\":\"x\"}";
    inTx(() -> {
      var entity = (EntityImpl) parseBackwardsCompat(json);
      assertEquals("x", entity.<String>getProperty("name"));
    });
  }

  @Test
  public void legacyStringFieldTypesFormatPropagatesTypeToValueParsing() {
    // Multi-entry CSV with a date-typed field (`t` = DATETIME). The deserialiser must apply
    // the parsed type to the long-valued JSON int — the result is a Date with that exact ms,
    // not the raw Long. This catches a regression where the CSV format was parsed but the
    // resulting type map was discarded.
    var ms = 1_710_504_451_456L;
    var json = "{\"@type\":\"d\",\"@class\":\"" + REGULAR_CLASS
        + "\",\"@fieldTypes\":\"legacyDate=t,name=S\",\"legacyDate\":" + ms
        + ",\"name\":\"y\"}";
    inTx(() -> {
      var entity = (EntityImpl) parseBackwardsCompat(json);
      var dateValue = entity.<java.util.Date>getProperty("legacyDate");
      assertNotNull(
          "BACKWARDS_COMPAT must apply the legacy CSV @fieldTypes to value parsing",
          dateValue);
      assertEquals("date round-trip must preserve epoch ms exactly", ms, dateValue.getTime());
      assertEquals("y", entity.<String>getProperty("name"));
    });
  }

  @Test
  public void legacyStringFieldTypesFormatToleratesTrailingComma() {
    // Trailing comma in the CSV form must not blow up the parser. The implementation filters
    // empty splits via a length-2 check on each `=`-split, so `"name=S,"` produces just one
    // entry. Pinning this so a regression that started rejecting trailing-comma export files
    // would surface immediately.
    var json = "{\"@type\":\"d\",\"@class\":\"" + REGULAR_CLASS
        + "\",\"@fieldTypes\":\"name=S,\",\"name\":\"x\"}";
    inTx(() -> {
      var entity = (EntityImpl) parseBackwardsCompat(json);
      assertEquals("x", entity.<String>getProperty("name"));
    });
  }

  @Test
  public void modernObjectFieldTypesFormatAlsoAcceptedByBackwardsCompatInstance() {
    // Sanity: the BACKWARDS_COMPAT path keeps the modern object form as well — the
    // oldFieldTypesFormat flag is OR-additive, not exclusive. A regression that broke the
    // object branch when the flag flipped would fail this test.
    var json = "{\"@type\":\"d\",\"@class\":\"" + REGULAR_CLASS
        + "\",\"@fieldTypes\":{\"name\":\"S\"},\"name\":\"x\"}";
    inTx(() -> {
      var entity = (EntityImpl) parseBackwardsCompat(json);
      assertEquals("x", entity.<String>getProperty("name"));
    });
  }

  @Test
  public void legacyStringFieldTypesRejectedByImportInstanceParity() {
    // Falsifiable parity for the oldFieldTypesFormat flag: the same legacy JSON throws on
    // IMPORT_INSTANCE because its parseFieldTypes branch does not accept the string form.
    // A copy-paste regression that flipped IMPORT's flag to true would surface as a test
    // pass that contradicts this expectation.
    var json = "{\"@type\":\"d\",\"@class\":\"" + REGULAR_CLASS
        + "\",\"@fieldTypes\":\"name=S\",\"name\":\"x\"}";
    inTx(() -> {
      var ex = assertThrows(SerializationException.class, () -> parseImport(json));
      assertTrue(
          "IMPORT_INSTANCE rejects with bad-fieldTypes; cause was: " + ex.getCause(),
          ex.getCause() != null && ex.getCause().getMessage().contains("Bad @fieldTypes format"));
    });
  }

  // ====================================================================== readPrefixUnderscoreReplacements={'$'}

  @Test
  public void dollarPrefixFieldReplacedWithUnderscoreOnBackwardsCompatInstance() {
    // The replacement happens BEFORE the EntityImpl.isSystemProperty guard fires. So a JSON
    // field named `$myField` is stored under `_myField` (a non-system property name). This is
    // the production support for legacy 1.x export files that used `$` as the metadata prefix.
    var json = "{\"@type\":\"d\",\"@class\":\"" + REGULAR_CLASS
        + "\",\"$myField\":\"alpha\"}";
    inTx(() -> {
      var entity = (EntityImpl) parseBackwardsCompat(json);
      assertEquals(
          "BACKWARDS_COMPAT must replace leading $ with _ in field names",
          "alpha",
          entity.<String>getProperty("_myField"));
      // `hasProperty` bypasses the public validatePropertyName guard (which would reject
      // `$myField` as a non-letter-non-underscore start), so it cleanly distinguishes "field
      // present under the $ name" from "field present under the _ name". Reading this through
      // the public getProperty would throw before observing the desired distinction.
      assertFalse(
          "the original $-prefixed name must NOT be set (the replacement is destructive)",
          entity.hasProperty("$myField"));
    });
  }

  @Test
  public void dollarPrefixOnlyFieldNameReplacedToUnderscoreOnly() {
    // Edge case: a single-character field name `$` becomes `_`. The replacement is
    // `'_' + fieldName.substring(1)`, so `$` (length 1) becomes `_` (the substring is empty).
    var json = "{\"@type\":\"d\",\"@class\":\"" + REGULAR_CLASS
        + "\",\"$\":\"alpha\"}";
    inTx(() -> {
      var entity = (EntityImpl) parseBackwardsCompat(json);
      assertEquals("alpha", entity.<String>getProperty("_"));
    });
  }

  @Test
  public void nonDollarPrefixFieldNotReplaced() {
    // Negative pin: only `$` is in the replacements set on BACKWARDS_COMPAT — other
    // non-letter prefixes do NOT get replaced. The `%` prefix is also a system property
    // (non-letter, non-underscore) and so the JSON is rejected at the system-property guard.
    // The net effect: BACKWARDS_COMPAT replaces $ specifically, not "any non-letter prefix".
    var json = "{\"@type\":\"d\",\"@class\":\"" + REGULAR_CLASS
        + "\",\"%otherField\":\"x\"}";
    inTx(() -> {
      var ex = assertThrows(SerializationException.class, () -> parseBackwardsCompat(json));
      assertTrue(
          "expected system-property rejection; cause was: " + ex.getCause(),
          ex.getCause() != null && ex.getCause().getMessage().contains("System property"));
    });
  }

  @Test
  public void dollarPrefixRejectedByImportInstanceParity() {
    // Falsifiable parity: the SAME `$myField` JSON that BACKWARDS_COMPAT accepts must throw
    // on IMPORT_INSTANCE — empty replacements set means the system-property guard fires
    // unaltered. A regression that synced IMPORT's replacements to {'$'} would surface as
    // a test pass that contradicts this expectation.
    var json = "{\"@type\":\"d\",\"@class\":\"" + REGULAR_CLASS
        + "\",\"$myField\":\"alpha\"}";
    inTx(() -> {
      var ex = assertThrows(SerializationException.class, () -> parseImport(json));
      assertTrue(
          "IMPORT_INSTANCE must reject $-prefixed field as system property; cause: "
              + ex.getCause(),
          ex.getCause() != null && ex.getCause().getMessage().contains("System property"));
    });
  }

  @Test
  public void leadingDollarOnlyAffectsFirstChar() {
    // The replacement code path only swaps the FIRST char (`'_' + fieldName.substring(1)`).
    // Embedded `$` in the middle of a field name is untouched. This pins the first-char
    // semantics so a regression that started replacing every `$` would be caught.
    var json = "{\"@type\":\"d\",\"@class\":\"" + REGULAR_CLASS
        + "\",\"$xyz$abc\":\"alpha\"}";
    inTx(() -> {
      var entity = (EntityImpl) parseBackwardsCompat(json);
      assertEquals(
          "only the leading $ is replaced — embedded $ is preserved verbatim",
          "alpha",
          entity.<String>getProperty("_xyz$abc"));
    });
  }

  // ====================================================================== readUnescapedControlChars=true

  @Test
  public void unescapedTabInStringValueAcceptedByBackwardsCompatInstance() {
    // Production motivation: the legacy 1.x exporter wrote raw tab characters inside JSON
    // string literals without escaping. BACKWARDS_COMPAT enables Jackson's
    // ALLOW_UNESCAPED_CONTROL_CHARS so these inputs parse without throwing.
    var json = "{\"@type\":\"d\",\"@class\":\"" + REGULAR_CLASS
        + "\",\"name\":\"alpha	beta\"}";
    inTx(() -> {
      var entity = (EntityImpl) parseBackwardsCompat(json);
      assertEquals("alpha\tbeta", entity.<String>getProperty("name"));
    });
  }

  @Test
  public void unescapedNewlineInStringValueAcceptedByBackwardsCompatInstance() {
    // The flag covers the broader class of unescaped control chars (0x00 — 0x1F), not just
    // tab. Pin the newline case so a regression that whitelist-narrowed the flag (e.g.,
    // accepting only tab) would be caught.
    var json = "{\"@type\":\"d\",\"@class\":\"" + REGULAR_CLASS
        + "\",\"name\":\"alpha\nbeta\"}";
    inTx(() -> {
      var entity = (EntityImpl) parseBackwardsCompat(json);
      assertEquals("alpha\nbeta", entity.<String>getProperty("name"));
    });
  }

  @Test
  public void unescapedTabRejectedByImportInstanceParity() {
    // Falsifiable parity: the SAME literal-tab JSON that BACKWARDS_COMPAT accepts MUST throw
    // on IMPORT_INSTANCE — its readUnescapedControlChars=false leaves the strict default
    // active. A regression that flipped IMPORT's flag to true would surface as a test pass
    // that contradicts this expectation.
    var json = "{\"@type\":\"d\",\"@class\":\"" + REGULAR_CLASS
        + "\",\"name\":\"alpha	beta\"}";
    inTx(() -> {
      var ex = assertThrows(SerializationException.class, () -> parseImport(json));
      // Pin the cause-chain reason so the parity claim doesn't degrade to "any error counts".
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

  // ====================================================================== readAllowGraphStructure=true

  @Test
  public void edgeRecordCanBeCreatedFromJsonViaBackwardsCompatInstance() {
    // Parity with IMPORT_INSTANCE: BACKWARDS_COMPAT also enables graph structure, so edges can
    // still be created. The legacy export path used the same edge JSON shape as the modern
    // path — this pin would catch a regression that disabled graph structure on the legacy
    // code path (e.g., an attempt to "simplify" by sharing flags with INSTANCE).
    var inRid = persistVertexRid();
    var outRid = persistVertexRid();
    var json = "{\"@type\":\"e\",\"@class\":\"" + EDGE_SUBCLASS
        + "\",\"in\":\"" + inRid + "\",\"out\":\"" + outRid + "\"}";
    inTx(() -> {
      var parsed = parseBackwardsCompat(json);
      assertNotNull(parsed);
      assertTrue(parsed instanceof EdgeEntityImpl);
      var edge = (EdgeEntityImpl) parsed;
      assertEquals(inRid, edge.getToLink().getIdentity().toString());
      assertEquals(outRid, edge.getFromLink().getIdentity().toString());
    });
  }

  @Test
  public void edgeRecordCreationFailsForDefaultInstanceParity() {
    // Falsifiable parity for the graph-structure flag: same edge JSON throws on default
    // INSTANCE. This is symmetric with IMPORT_INSTANCE-side test in the sibling class —
    // pinning it from the BACKWARDS_COMPAT side too means that a regression flipping
    // INSTANCE's allowGraphStructure to true would fail BOTH tests, doubling the safety net.
    var inRid = persistVertexRid();
    var outRid = persistVertexRid();
    var json = "{\"@type\":\"e\",\"@class\":\"" + EDGE_SUBCLASS
        + "\",\"in\":\"" + inRid + "\",\"out\":\"" + outRid + "\"}";
    inTx(() -> {
      var ex = assertThrows(SerializationException.class, () -> parseDefault(json));
      assertTrue(
          "expected UnsupportedEncodingException cause; was: " + ex.getCause(),
          ex.getCause() instanceof java.io.UnsupportedEncodingException);
    });
  }

  // ====================================================================== combined-flag snapshot

  @Test
  public void allFourBackwardsCompatFlagsObservableInOneShape() {
    // A single JSON that exercises all four BACKWARDS_COMPAT flags simultaneously: legacy CSV
    // @fieldTypes, leading-$ field name, unescaped tab in a string value, and an edge class.
    // Built from the union of the prior single-flag tests so a multi-regression that flipped
    // any one flag would still cause a failure here even if the single-flag test were
    // "accidentally" updated to match.
    var inRid = persistVertexRid();
    var outRid = persistVertexRid();
    var ms = 1_710_504_451_456L;
    // Place the edge's `in`/`out` first since the @fieldTypes parser is order-sensitive in
    // the legacy form (it must precede the typed values to apply types correctly). The edge
    // class has LINK-typed in/out from createEdgeClass.
    var json = "{\"@type\":\"e\",\"@class\":\"" + EDGE_SUBCLASS
        + "\",\"@fieldTypes\":\"$myField=S,legacyDate=t\""
        + ",\"in\":\"" + inRid + "\",\"out\":\"" + outRid + "\""
        + ",\"$myField\":\"alpha	beta\""
        + ",\"legacyDate\":" + ms + "}";
    inTx(() -> {
      var parsed = parseBackwardsCompat(json);
      assertTrue("edge created via allowGraphStructure", parsed instanceof EdgeEntityImpl);
      var edge = (EdgeEntityImpl) parsed;
      assertEquals(inRid, edge.getToLink().getIdentity().toString());
      assertEquals(outRid, edge.getFromLink().getIdentity().toString());
      // Legacy CSV @fieldTypes applied: legacyDate is a Date, not a Long; $myField is replaced
      // with _myField and contains the unescaped tab.
      assertEquals("alpha\tbeta", edge.<String>getProperty("_myField"));
      var date = edge.<java.util.Date>getProperty("legacyDate");
      assertNotNull(date);
      assertEquals(ms, date.getTime());
    });
  }

  // ====================================================================== negative pin: BACKWARDS_COMPAT IS still strict on real garbage

  @Test
  public void invalidJsonStillRejectedByBackwardsCompatInstance() {
    // The relaxed flags do not turn the parser into a "best-effort" parser — actual structural
    // garbage still throws. Without this pin, a regression that swallowed parse errors silently
    // (returning a null record) could go undetected. The garbage here is a missing closing
    // brace; Jackson's parseEnd-of-object handling triggers regardless of relaxed flags.
    var json = "{\"@type\":\"d\",\"@class\":\"" + REGULAR_CLASS + "\",\"name\":\"x\"";
    inTx(() -> {
      var ex = assertThrows(SerializationException.class, () -> parseBackwardsCompat(json));
      // Pin the cause-chain reason — without this, the test could pass for any unrelated error
      // (e.g., schema lookup failure) that happens to surface as SerializationException.
      var chain = chainMessagesOf(ex).toLowerCase();
      assertTrue(
          "expected unexpected-end-of-input diagnostic in cause chain, got: "
              + chainMessagesOf(ex),
          chain.contains("end-of-input")
              || chain.contains("end of input")
              || chain.contains("unexpected end")
              || chain.contains("unexpected eof"));
    });
  }

  @Test
  public void unknownClassStillRejectedByBackwardsCompatInstance() {
    // Schema lookup is unaffected by the BACKWARDS_COMPAT flags. A `@class` value that does
    // not resolve in the snapshot must throw — pin so a regression that started lazily
    // creating classes for unknown imports would be caught.
    var json = "{\"@type\":\"d\",\"@class\":\"NoSuchClassEverDefined\",\"name\":\"x\"}";
    inTx(() -> {
      var ex = assertThrows(SerializationException.class, () -> parseBackwardsCompat(json));
      assertNotEquals(
          "the cause must surface; should not be wrapped without a cause",
          null,
          ex.getCause());
      assertTrue(
          "expected class-not-found message; was: " + ex.getCause().getMessage(),
          ex.getCause().getMessage().contains("Class not found"));
    });
  }
}
