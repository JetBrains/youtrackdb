package com.jetbrains.youtrackdb.internal.core.db.tool;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass.INDEX_TYPE;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.junit.Test;

/**
 * Pins the Track 8 Step 6 ruled Q-M2/SR2 info-validation matrix (design M2.b-5, ruling R4,
 * gate WI12a/b) at the pre-flight seam: the exporter-version dispatch with the {@code >= 16}
 * reject-with-redirect, the mandatory schema-version range, known-optional-field type checks,
 * unknown-field tolerance with logging, dangling-field parse rejection (FM-M10), the
 * declared-legacy lenient preservation (FM-M12), the end-to-end migration rehearsal (pin
 * M.5 #12), and the operator-procedure page's existence with its mandated content (pin #18).
 * Every pre-flight rejection also asserts the target was left unmutated (CS38/SR1 scope).
 */
public class DatabaseImportInfoMatrixTest extends DbTestBase {

  private Path dumpDirectory() throws IOException {
    var dir = Path.of(DbTestBase.getBaseDirectoryPathStr(getClass()), "dumps",
        name.getMethodName());
    Files.createDirectories(dir);
    return dir;
  }

  /** Exports a small dump with a marker class and three records (the shared fixture). */
  private Path exportSmallDump() throws IOException {
    session.getMetadata().getSchema().createClass("Matrix");
    session.executeInTx(transaction -> {
      for (var i = 0; i < 3; i++) {
        session.newEntity("Matrix").setInt("i", i);
      }
    });
    var dump = dumpDirectory().resolve("dump.json.gz");
    new DatabaseExport(session, dump.toString(), text -> {
    }).exportDatabase();
    return dump;
  }

  private DatabaseSessionEmbedded createTargetDatabase(String targetName) {
    youTrackDB.create(targetName, dbType, "admin", ADMIN_PASSWORD, "admin");
    return youTrackDB.open(targetName, "admin", ADMIN_PASSWORD);
  }

  private static void runImport(DatabaseSessionEmbedded target, Path dump, String... options)
      throws IOException {
    runImportCapturing(target, dump, new StringBuilder(), options);
  }

  private static void runImportCapturing(DatabaseSessionEmbedded target, Path dump,
      StringBuilder listenerOutput, String... options) throws IOException {
    var importer = new DatabaseImport(target, dump.toString(), listenerOutput::append);
    for (var option : options) {
      importer.setOptions(option);
    }
    importer.importDatabase();
  }

  /**
   * Runs the import and returns the loud rejection, or {@code null} when the import succeeded
   * ({@code importDatabase} wraps failures into {@code DatabaseExportException}).
   */
  private static RuntimeException importExpectingRejection(
      DatabaseSessionEmbedded target, Path dump) throws IOException {
    try {
      runImport(target, dump);
    } catch (DatabaseImportException | DatabaseExportException e) {
      return e;
    }
    return null;
  }

  /** Asserts a message fragment somewhere in the rejection's cause chain. */
  private static void assertRejectionMentions(RuntimeException rejection, String fragment) {
    var messages = new StringBuilder();
    for (Throwable t = rejection; t != null; t = t.getCause()) {
      if (t.getMessage() != null) {
        messages.append(t.getMessage()).append('\n');
      }
    }
    assertTrue("the rejection must mention '" + fragment + "' but was:\n" + messages,
        messages.toString().contains(fragment));
  }

  /** CS38/SR1: a pre-flight rejection leaves the target byte-for-byte unmutated. */
  private static void assertTargetUnmutated(DatabaseSessionEmbedded target) {
    assertTrue("the pre-flight rejection must leave the target unmutated",
        target.getMetadata().getSchema().existsClass("PreFlightMarker"));
    assertFalse("no dump content may reach the target before pre-flight passes",
        target.getMetadata().getSchema().existsClass("Matrix"));
  }

  private static byte[] gunzip(Path dump) throws IOException {
    try (var in = new GZIPInputStream(Files.newInputStream(dump))) {
      return in.readAllBytes();
    }
  }

  private static void gzipTo(Path dump, byte[] json) throws IOException {
    try (var out = new GZIPOutputStream(Files.newOutputStream(dump))) {
      out.write(json);
    }
  }

  /** Applies a JSON-level mutation to a dump file (decompress, mutate, recompress). */
  private static void mutateDump(Path dump, Consumer<ObjectNode> mutation) throws IOException {
    var mapper = new ObjectMapper();
    var root = (ObjectNode) mapper.readTree(gunzip(dump));
    mutation.accept(root);
    gzipTo(dump, mapper.writeValueAsBytes(root));
  }

  /**
   * Design pin M.5 #14 (Q-M2(1); red-first): a dump declaring exporter version 16 — produced
   * by NEWER binaries — must be rejected with the redirect naming BOTH versions, ahead of
   * every v15 strictness arm and before any target mutation. RED at HEAD: the {@code >= 15}
   * arms accept the well-formed v16 dump and the import completes silently.
   */
  @Test
  public void v16DumpIsRejectedWithRedirectNamingBothVersions() throws Exception {
    var dump = exportSmallDump();
    mutateDump(dump, root -> ((ObjectNode) root.get("info")).put("exporter-version", 16));

    try (var target = createTargetDatabase("v16Target")) {
      target.getMetadata().getSchema().createClass("PreFlightMarker");

      var rejection = importExpectingRejection(target, dump);
      assertNotNull("a v16 dump must be rejected with a redirect", rejection);
      assertRejectionMentions(rejection, "exporter version 16");
      assertRejectionMentions(rejection,
          "up to exporter version " + DatabaseExport.EXPORTER_VERSION);
      assertTargetUnmutated(target);
    }
  }

  /**
   * Design pin M.5 #14 (Q-M2(2); red-first): a v15 dump MISSING its schema-version — a
   * mandatory info field — must be rejected naming the supported range. RED at HEAD: the
   * field is skipped unread and the import completes silently.
   */
  @Test
  public void schemaVersionMissingIsRejected() throws Exception {
    var dump = exportSmallDump();
    mutateDump(dump, root -> ((ObjectNode) root.get("info")).remove("schema-version"));

    try (var target = createTargetDatabase("noSchemaVersionTarget")) {
      target.getMetadata().getSchema().createClass("PreFlightMarker");

      var rejection = importExpectingRejection(target, dump);
      assertNotNull("a v15 dump without a schema version must be rejected", rejection);
      assertRejectionMentions(rejection, "does not declare a schema version");
      assertTargetUnmutated(target);
    }
  }

  /**
   * Design pin M.5 #14 (Q-M2(2); red-first): a v15 dump whose schema-version is MALFORMED
   * (not a number) must be rejected naming the declared value and the supported range. RED at
   * HEAD: skipped unread, silent success.
   */
  @Test
  public void schemaVersionMalformedIsRejected() throws Exception {
    var dump = exportSmallDump();
    mutateDump(dump, root -> ((ObjectNode) root.get("info")).put("schema-version", "six"));

    try (var target = createTargetDatabase("badSchemaVersionTarget")) {
      target.getMetadata().getSchema().createClass("PreFlightMarker");

      var rejection = importExpectingRejection(target, dump);
      assertNotNull("a v15 dump with a malformed schema version must be rejected", rejection);
      assertRejectionMentions(rejection, "unparseable schema version");
      assertTargetUnmutated(target);
    }
  }

  /**
   * Design pin M.5 #14 (Q-M2(2); red-first): a schema-version BELOW the importable range is
   * rejected naming declared vs supported. RED at HEAD: silent success.
   */
  @Test
  public void schemaVersionBelowRangeIsRejected() throws Exception {
    var dump = exportSmallDump();
    mutateDump(dump, root -> ((ObjectNode) root.get("info")).put("schema-version", 5));

    try (var target = createTargetDatabase("lowSchemaVersionTarget")) {
      target.getMetadata().getSchema().createClass("PreFlightMarker");

      var rejection = importExpectingRejection(target, dump);
      assertNotNull("a below-range schema version must be rejected", rejection);
      assertRejectionMentions(rejection, "schema version 5");
      assertTargetUnmutated(target);
    }
  }

  /**
   * Design pin M.5 #14 (Q-M2(2); red-first): a schema-version ABOVE the importable range is
   * rejected naming declared vs supported. RED at HEAD: silent success.
   */
  @Test
  public void schemaVersionAboveRangeIsRejected() throws Exception {
    var dump = exportSmallDump();
    mutateDump(dump, root -> ((ObjectNode) root.get("info")).put("schema-version", 7));

    try (var target = createTargetDatabase("highSchemaVersionTarget")) {
      target.getMetadata().getSchema().createClass("PreFlightMarker");

      var rejection = importExpectingRejection(target, dump);
      assertNotNull("an above-range schema version must be rejected", rejection);
      assertRejectionMentions(rejection, "schema version 7");
      assertTargetUnmutated(target);
    }
  }

  /**
   * Design pin M.5 #14 (WI12a; red-first on the message): a dump whose exporter-version is
   * UNPARSEABLE is rejected fail-closed with the same outcome as an undeclared version,
   * naming the malformed value — RED at HEAD: the rejection is a bare
   * {@code NumberFormatException} ({@code For input string}) that names nothing.
   */
  @Test
  public void malformedExporterVersionIsRejectedFailClosed() throws Exception {
    var dump = exportSmallDump();
    mutateDump(dump, root -> ((ObjectNode) root.get("info")).put("exporter-version", "fifteen"));

    try (var target = createTargetDatabase("badExporterVersionTarget")) {
      target.getMetadata().getSchema().createClass("PreFlightMarker");

      var rejection = importExpectingRejection(target, dump);
      assertNotNull("an unparseable exporter version must be rejected", rejection);
      assertRejectionMentions(rejection, "unparseable exporter version");
      assertTargetUnmutated(target);
    }
  }

  /**
   * Design pin M.5 #15 (Q-M2(3)/WI12b; red-first): a KNOWN optional info field present with a
   * WRONG type (here: 'name' as a number) is a type-check rejection under v15. RED at HEAD:
   * the field is skipped unread and the import completes silently.
   */
  @Test
  public void knownOptionalInfoFieldWrongTypeIsRejected() throws Exception {
    var dump = exportSmallDump();
    mutateDump(dump, root -> ((ObjectNode) root.get("info")).put("name", 42));

    try (var target = createTargetDatabase("wrongTypeTarget")) {
      target.getMetadata().getSchema().createClass("PreFlightMarker");

      var rejection = importExpectingRejection(target, dump);
      assertNotNull("a known info field with a wrong type must be rejected", rejection);
      assertRejectionMentions(rejection, "'name'");
      assertTargetUnmutated(target);
    }
  }

  /**
   * Design pin M.5 #15 (Q-M2(4); red-first on the logging half): an UNKNOWN extra info field
   * is tolerated — the exporter version is the compatibility contract, not field enumeration
   * — and logged. RED at HEAD: tolerated but never logged.
   */
  @Test
  public void unknownInfoFieldIsToleratedAndLogged() throws Exception {
    var dump = exportSmallDump();
    mutateDump(dump, root -> ((ObjectNode) root.get("info")).put("future-field", "x"));

    try (var target = createTargetDatabase("unknownFieldTarget")) {
      var listenerOutput = new StringBuilder();
      runImportCapturing(target, dump, listenerOutput);
      assertTrue("the unknown-field dump must import",
          target.getMetadata().getSchema().existsClass("Matrix"));
      assertTrue("the unknown info field must be logged, but the listener saw:\n"
          + listenerOutput, listenerOutput.toString().contains("future-field"));
    }
  }

  /**
   * Design pin M.5 #9 (FM-M10): a DANGLING info field — name written, value missing, the
   * mid-write crash shape — is a parse rejection, not a tolerated tail. (Already loud at
   * HEAD: the reader's field parse throws; this pin keeps it loud and pre-mutation.)
   */
  @Test
  public void danglingInfoFieldIsRejected() throws Exception {
    var dump = exportSmallDump();
    var text = new String(gunzip(dump), StandardCharsets.UTF_8);
    var tampered =
        text.replaceFirst("(\"indexMgrRecordId\":\"[^\"]*\")", "$1,\"dangling-crash\"");
    assertTrue("the splice must have applied", !tampered.equals(text));
    gzipTo(dump, tampered.getBytes(StandardCharsets.UTF_8));

    try (var target = createTargetDatabase("danglingFieldTarget")) {
      target.getMetadata().getSchema().createClass("PreFlightMarker");

      var rejection = importExpectingRejection(target, dump);
      assertNotNull("a dangling info field must be a parse rejection", rejection);
      assertTargetUnmutated(target);
    }
  }

  /**
   * Review finding F4/TQ29 (ordering pin): a v16 dump that ALSO lacks its schema-version
   * must produce the REDIRECT message — the `>= 16` dispatch fires ahead of every v15 arm,
   * so no schema-version complaint (with its hardcoded supported-range wording) may surface
   * for a dump this release cannot speak at all. Green at HEAD by construction; discriminates
   * a reordering of the pre-flight arms.
   */
  @Test
  public void v16RedirectFiresAheadOfSchemaVersionArms() throws Exception {
    var dump = exportSmallDump();
    mutateDump(dump, root -> {
      ((ObjectNode) root.get("info")).put("exporter-version", 16);
      ((ObjectNode) root.get("info")).remove("schema-version");
    });

    try (var target = createTargetDatabase("v16OrderingTarget")) {
      target.getMetadata().getSchema().createClass("PreFlightMarker");

      var rejection = importExpectingRejection(target, dump);
      assertNotNull("the v16 dump must be rejected", rejection);
      assertRejectionMentions(rejection,
          "up to exporter version " + DatabaseExport.EXPORTER_VERSION);
      assertTargetUnmutated(target);
    }
  }

  /**
   * Review finding F1 (BG29=CS76; red-first): a hand-edited declared-LEGACY dump carrying
   * the best-effort marker as a QUOTED string (`"best-effort": "true"`) must still arm the
   * SR3 marker-keyed acknowledgment gate — the parent's quote-stripping parse refused it
   * absent the ack flag, and the raw-token rework silently disarmed the gate (fail-open on
   * exactly the hand-edited inputs SR3 rules must fail closed). RED at HEAD: imports
   * silently.
   */
  @Test
  public void quotedBestEffortMarkerStillArmsTheAckGateOnLegacyDumps() throws Exception {
    var dump = exportSmallDump();
    mutateDump(dump, root -> {
      ((ObjectNode) root.get("info")).put("exporter-version", 14);
      ((ObjectNode) root.get("info")).put("best-effort", "true");
      root.remove("manifest");
    });

    try (var target = createTargetDatabase("quotedMarkerTarget")) {
      var rejection = importExpectingRejection(target, dump);
      assertNotNull("a quoted best-effort marker must still arm the ack gate", rejection);
      assertRejectionMentions(rejection, "-acceptBestEffortDump=true");
    }
  }

  /**
   * Review finding F1's v15 companion (kept-as-is half): under v15 the QUOTED marker is a
   * WI12b type violation — rejected naming the field — regardless of the gate.
   */
  @Test
  public void quotedBestEffortMarkerIsATypeViolationOnV15() throws Exception {
    var dump = exportSmallDump();
    mutateDump(dump, root -> ((ObjectNode) root.get("info")).put("best-effort", "true"));

    try (var target = createTargetDatabase("quotedMarkerV15Target")) {
      target.getMetadata().getSchema().createClass("PreFlightMarker");

      var rejection = importExpectingRejection(target, dump);
      assertNotNull("a quoted v15 best-effort marker must be a type violation", rejection);
      assertRejectionMentions(rejection, "'best-effort'");
      assertTargetUnmutated(target);
    }
  }

  /**
   * Review finding F2 (BG30=CS78; red-first): a dump TRUNCATED inside its info section — the
   * mid-write crash shape at the JSON level, valid gzip around it — must be rejected loudly.
   * RED at HEAD: the guardless field loop spins forever on stale reader state (the test
   * times out), growing the unknown-field list toward OOM.
   */
  @Test(timeout = 120_000)
  public void truncatedInfoSectionIsRejectedNotHung() throws Exception {
    var dump = dumpDirectory().resolve("truncated-info.json.gz");
    gzipTo(dump, "{\"info\":{\"name\":\"x\",".getBytes(StandardCharsets.UTF_8));

    try (var target = createTargetDatabase("truncatedInfoTarget")) {
      target.getMetadata().getSchema().createClass("PreFlightMarker");

      var rejection = importExpectingRejection(target, dump);
      assertNotNull("a dump truncated inside its info section must be rejected", rejection);
      assertTrue("the pre-flight rejection must leave the target unmutated",
          target.getMetadata().getSchema().existsClass("PreFlightMarker"));
    }
  }

  /**
   * Review finding F2's manifest companion (CS78; red-first): a v15 dump TRUNCATED inside
   * its manifest section must be rejected loudly — the manifest field loop shared the
   * guardless-loop shape. RED at HEAD: stale-read spin (timeout).
   */
  @Test(timeout = 120_000)
  public void truncatedManifestSectionIsRejectedNotHung() throws Exception {
    // JUnit's timeout runs the test body on a spawned thread; the DbTestBase session is
    // bound to the main thread, so this test builds its own source database in-thread.
    youTrackDB.create("manifestSrc", dbType, "admin", ADMIN_PASSWORD, "admin");
    Path dump;
    try (var source = youTrackDB.open("manifestSrc", "admin", ADMIN_PASSWORD)) {
      source.getMetadata().getSchema().createClass("Matrix");
      dump = dumpDirectory().resolve("dump.json.gz");
      new DatabaseExport(source, dump.toString(), text -> {
      }).exportDatabase();
    }
    var text = new String(gunzip(dump), StandardCharsets.UTF_8);
    var cutAt = text.lastIndexOf("\"manifest\"");
    assertTrue("the dump must carry a manifest section", cutAt > 0);
    // keep the manifest tag and its opening brace plus one field, then cut — EOF mid-object
    var openBrace = text.indexOf('{', cutAt);
    var firstComma = text.indexOf(',', openBrace);
    gzipTo(dump, text.substring(0, firstComma + 1).getBytes(StandardCharsets.UTF_8));

    try (var target = createTargetDatabase("truncatedManifestTarget")) {
      var rejection = importExpectingRejection(target, dump);
      assertNotNull("a dump truncated inside its manifest must be rejected", rejection);
    }
  }

  /**
   * Review finding F3 (CS75; red-first): an unknown info field with a JSON-OBJECT value,
   * placed MID-SECTION, desynced the reader (the nested closing brace was misread as the
   * info object's close), so the import passed pre-flight on a truncated capture, MUTATED
   * the target, and rejected post-mutation — an SR1-boundary violation. The value read is
   * now rejected at parse: pre-mutation, naming the field. RED at HEAD: the rejection is a
   * misleading "unsupported tag" AFTER the preamble dropped the marker class.
   */
  @Test
  public void objectValuedInfoFieldMidSectionIsRejectedPreMutation() throws Exception {
    var dump = exportSmallDump();
    var text = new String(gunzip(dump), StandardCharsets.UTF_8);
    var tampered = text.replaceFirst("\"schemaRecordId\"",
        "\"future-field\":\\{\"a\":1\\},\"schemaRecordId\"");
    assertTrue("the splice must have applied", !tampered.equals(text));
    gzipTo(dump, tampered.getBytes(StandardCharsets.UTF_8));

    try (var target = createTargetDatabase("objectValueMidTarget")) {
      target.getMetadata().getSchema().createClass("PreFlightMarker");

      var rejection = importExpectingRejection(target, dump);
      assertNotNull("an object-valued info field must be rejected", rejection);
      assertRejectionMentions(rejection, "'future-field'");
      assertTargetUnmutated(target);
    }
  }

  /**
   * Review finding F3 (CS75's trailing shape; red-first): the SAME object-valued unknown
   * field in TRAILING position was silently ACCEPTED at HEAD (the desync landed on the
   * closing brace by luck). Under the recorded scalar-only rule it is rejected pre-mutation.
   */
  @Test
  public void objectValuedTrailingInfoFieldIsRejected() throws Exception {
    var dump = exportSmallDump();
    // Jackson appends the new field LAST inside info — the trailing shape
    mutateDump(dump, root -> ((ObjectNode) root.get("info")).putObject("future-field")
        .put("a", 1));

    try (var target = createTargetDatabase("objectValueTrailingTarget")) {
      target.getMetadata().getSchema().createClass("PreFlightMarker");

      var rejection = importExpectingRejection(target, dump);
      assertNotNull("a trailing object-valued info field must be rejected", rejection);
      assertRejectionMentions(rejection, "'future-field'");
      assertTargetUnmutated(target);
    }
  }

  /**
   * Design pin M.5 #14's lenient cell + FM-M12 (R1): a DECLARED-v14 dump is untouched by the
   * matrix — even an alien schema-version (99) rides the legacy lenient path unchanged.
   * Green at HEAD and green after the matrix lands (the pin proves the version gating).
   */
  @Test
  public void v14DumpWithAlienSchemaVersionStaysLenient() throws Exception {
    var dump = exportSmallDump();
    mutateDump(dump, root -> {
      ((ObjectNode) root.get("info")).put("exporter-version", 14);
      ((ObjectNode) root.get("info")).put("schema-version", 99);
      root.remove("manifest");
    });

    try (var target = createTargetDatabase("lenientTarget")) {
      runImport(target, dump);
      assertTrue("the declared-v14 dump must ride the lenient path regardless of its"
          + " schema-version field", target.getMetadata().getSchema().existsClass("Matrix"));
    }
  }

  /**
   * Design pin M.5 #12 (as-built — see the episode's blocked-letter record): the end-to-end
   * migration rehearsal — export a populated database (typed schema, unique index, plain and
   * linked records, a blob), import into a fresh one, and require LOGICAL equivalence:
   * class set, per-class record counts, record contents including the link topology, the
   * user index, and the blob's bytes. The import succeeding at all also proves the manifest
   * verified against the importer's consumption tallies. (The pin's literal
   * `DatabaseCompare` comparison is id-keyed — same-rid record lookups, per-collection-id
   * counts — which the renumbering import makes structurally unsatisfiable; the historical
   * `DbImportExportTest` using that pattern is @Disabled for the same reason.)
   */
  @Test
  public void endToEndMigrationRehearsalPreservesLogicalContent() throws Exception {
    var schema = session.getMetadata().getSchema();
    var person = schema.createClass("Person");
    person.createProperty("name", PropertyType.STRING);
    person.createIndex("Person.name", INDEX_TYPE.UNIQUE, "name");
    schema.createClass("Note");
    session.executeInTx(transaction -> {
      var alice = session.newEntity("Person");
      alice.setString("name", "alice");
      var bob = session.newEntity("Person");
      bob.setString("name", "bob");
      var note = session.newEntity("Note");
      note.setString("text", "hello");
      note.setLink("author", alice);
      alice.setLink("knows", bob);
      session.newBlob("payload".getBytes(StandardCharsets.UTF_8));
    });
    var dump = dumpDirectory().resolve("rehearsal.json.gz");
    new DatabaseExport(session, dump.toString(), text -> {
    }).exportDatabase();

    try (var target = createTargetDatabase("rehearsalTarget")) {
      runImport(target, dump);

      // schema: classes, the typed property, and the user index survived
      var targetSchema = target.getMetadata().getSchema();
      assertTrue(targetSchema.existsClass("Person"));
      assertTrue(targetSchema.existsClass("Note"));
      assertTrue("the typed property must survive",
          targetSchema.getClass("Person").getProperty("name") != null);
      assertNotNull("the user index must survive",
          target.getSharedContext().getIndexManager().getIndex("Person.name"));

      target.begin();
      try {
        // per-class record counts
        assertTrue("Person count", target.countClass("Person") == 2);
        assertTrue("Note count", target.countClass("Note") == 1);
        // record contents + link topology (author -> alice, alice.knows -> bob)
        try (var rs = target.query("select from Note")) {
          var note = rs.next().asEntity();
          assertTrue("hello".equals(note.getString("text")));
          var author = note.getEntity("author");
          assertNotNull("the Note->Person link must resolve", author);
          assertTrue("alice".equals(author.getString("name")));
          var known = author.getEntity("knows");
          assertNotNull("the Person->Person link must resolve", known);
          assertTrue("bob".equals(known.getString("name")));
        }
        // the blob's bytes round-trip
        var blobCount = 0;
        for (var blobCollectionId : target.getBlobCollectionIds()) {
          var iterator = target.browseCollection(
              target.getCollectionNameById(blobCollectionId));
          while (iterator.hasNext()) {
            var record = iterator.next();
            assertTrue("the blob bytes must round-trip", java.util.Arrays.equals(
                "payload".getBytes(StandardCharsets.UTF_8),
                ((com.jetbrains.youtrackdb.internal.core.record.impl.RecordBytes) record)
                    .toStream()));
            blobCount++;
          }
        }
        assertTrue("exactly one blob must survive", blobCount == 1);
      } finally {
        target.rollback();
      }
    }
  }

  /**
   * Design pin M.5 #18 (WI3, folds CS44): the operator migration-procedure page exists under
   * `docs/` with the mandated content — the export-exit-status gate, the fresh out-of-service
   * target, the condemn-on-any-failure doctrine (incl. crash-during-import and the
   * no-in-DB-signal warning), the recorded exit-0 completeness phrasing, the best-effort
   * acknowledgment flag, the genesis-incomplete guidance (CN59), and the crash-residue
   * cleanup note (CS59/FM-M18) — and is indexed in `docs/README.md`. RED at HEAD: the page
   * does not exist.
   */
  @Test
  public void operatorMigrationProcedureDocExistsWithMandatedContent() throws Exception {
    // surefire's working directory is the module dir (core/); docs/ sits at the repo root
    var docsDir = Path.of("..", "docs");
    assertTrue("the docs directory must be reachable from the module dir",
        Files.isDirectory(docsDir));
    var page = docsDir.resolve("operator-migration-procedure.md");
    assertTrue("the operator migration-procedure page must exist at docs/"
        + page.getFileName(), Files.exists(page));

    // whitespace-normalized so the mandated multi-word phrases match across line wraps
    var content = Files.readString(page).replaceAll("\\s+", " ");
    for (var mandated : new String[] {
        // CS44/WI3: the export-exit-status gate and the fresh out-of-service target
        "exit status", "fresh", "out of service",
        // WI60/WI61 (review-fix): the REAL invocation surface and the real observable —
        // the programmatic tools, which signal failure by throwing
        "DatabaseExport", "DatabaseImport", "without throwing",
        // SR1: any failure condemns the target; a condemned target carries no in-DB signal
        "condemned", "no in-database signal", "crash",
        // WI62 (review-fix): the discard is bound to the drop surface
        "drop(\"mydb\")",
        // the recorded exit-0 completeness phrasing (Step-5 gate carry-forward)
        "every dump entry was consumed and verified against the manifest",
        // WC61/WC62 (review-fix): legacy dumps get NO structural verification; the
        // schema-version rejection row is scoped to v15 dumps
        "no structural verification", "supported range (v15 dumps)",
        // WC63 (review-fix): only a KILLED/CRASHED export orphans temp files
        "killed or crashed",
        // M2.b-4: the best-effort acknowledgment flag
        "-acceptBestEffortDump=true",
        // CN59: genesis-incomplete refusal guidance incl. the OSystem case
        "genesis", "OSystem",
        // CS59/FM-M18: crash-orphaned export residue is deletable
        ".tmp", "ytdb-export-record-"}) {
      assertTrue("the page must contain '" + mandated + "'", content.contains(mandated));
    }

    var index = Files.readString(docsDir.resolve("README.md"));
    assertTrue("docs/README.md must index the migration-procedure page",
        index.contains("operator-migration-procedure.md"));
  }
}
