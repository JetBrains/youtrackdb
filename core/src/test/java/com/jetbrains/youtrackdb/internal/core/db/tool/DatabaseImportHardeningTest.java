package com.jetbrains.youtrackdb.internal.core.db.tool;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.config.YouTrackDBConfig;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.junit.Test;

/**
 * Pins the Track 8 Step 5 import hardening (design M2.b as amended; failure modes
 * FM-M6/M7/M8/M13/M14/M16): a v15 dump that is truncated, tampered, incomplete or
 * unacknowledged-best-effort can never import silently; ruled pre-flight rejections precede
 * all target mutation (CS38/SR1); the declared-legacy path stays lenient (R1); and the dump's
 * blob-collection ids are mapped through the collections section instead of being resolved raw
 * in the target id space (§A3/WI1).
 */
public class DatabaseImportHardeningTest extends DbTestBase {

  private Path dumpDirectory() throws IOException {
    var dir = Path.of(DbTestBase.getBaseDirectoryPathStr(getClass()), "dumps",
        name.getMethodName());
    Files.createDirectories(dir);
    return dir;
  }

  /** Exports the test session's database as a v15 dump and returns the dump path. */
  private Path exportDump() throws IOException {
    var dump = dumpDirectory().resolve("dump.json.gz");
    new DatabaseExport(session, dump.toString(), text -> {
    }).exportDatabase();
    return dump;
  }

  private DatabaseSessionEmbedded createTargetDatabase(String targetName) {
    youTrackDB.create(targetName, dbType, "admin", ADMIN_PASSWORD, "admin");
    return youTrackDB.open(targetName, "admin", ADMIN_PASSWORD);
  }

  /**
   * Runs the import and returns the loud rejection, or {@code null} when the import succeeded.
   * {@code importDatabase} wraps failures into {@code DatabaseExportException} (historical
   * naming), so both exception types count as the loud rejection.
   */
  private static RuntimeException importExpectingRejection(
      DatabaseSessionEmbedded target, Path dump, String... options) throws IOException {
    try {
      runImport(target, dump, options);
    } catch (DatabaseImportException | DatabaseExportException e) {
      return e;
    }
    return null;
  }

  /**
   * Asserts that the rejection (or any exception in its cause chain — importDatabase wraps
   * the specific failure) carries the expected message fragment.
   */
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

  private static void runImport(DatabaseSessionEmbedded target, Path dump, String... options)
      throws IOException {
    var importer = new DatabaseImport(target, dump.toString(), text -> {
    });
    for (var option : options) {
      importer.setOptions(option);
    }
    importer.importDatabase();
  }

  /**
   * Walks the rejection's cause chain and asserts none of the messages resembles a SPURIOUS
   * structural complaint — used by tests whose rejection must come from one specific check.
   */
  private static void assertRejectionDoesNotMention(RuntimeException rejection, String fragment) {
    for (Throwable t = rejection; t != null; t = t.getCause()) {
      if (t.getMessage() != null && t.getMessage().contains(fragment)) {
        throw new AssertionError(
            "the rejection must not stem from '" + fragment + "' but was: " + t.getMessage());
      }
    }
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
  private static void mutateDump(Path dump, java.util.function.Consumer<ObjectNode> mutation)
      throws IOException {
    var mapper = new ObjectMapper();
    var root = (ObjectNode) mapper.readTree(gunzip(dump));
    mutation.accept(root);
    gzipTo(dump, mapper.writeValueAsBytes(root));
  }

  /**
   * Design test pin M.5 #3 (red-first; FM-M6): a v15 dump whose GZIP TRAILER was truncated —
   * the deflate data (and so the whole JSON) still decodes, only the 8 trailing verification
   * bytes are gone, exactly the shape a crashed copy or transfer produces — must be rejected
   * loudly. RED at HEAD: the import stops reading at the JSON root's closing brace, never
   * reaches the missing trailer, and completes silently.
   */
  @Test
  public void truncatedGzipTrailerDumpIsRejected() throws Exception {
    session.getMetadata().getSchema().createClass("Trunc");
    session.executeInTx(transaction -> {
      for (var i = 0; i < 3; i++) {
        session.newEntity("Trunc").setInt("i", i);
      }
    });
    var dump = exportDump();
    var bytes = Files.readAllBytes(dump);
    Files.write(dump, Arrays.copyOf(bytes, bytes.length - 8));

    try (var target = createTargetDatabase("truncTarget")) {
      var rejection = importExpectingRejection(target, dump);
      assertNotNull("a truncated v15 dump must be rejected loudly", rejection);
      // The rejection must come from the CS43 whole-stream validation (the drain hits the
      // missing trailer) — not from an unrelated structural accident.
      assertRejectionMentions(rejection, "Truncated GZIP trailer");
    }
  }

  /**
   * Design test pin M.5 #13 (red-first; FM-M16, §A3/WI1): a v14-layout dump WITH blob content
   * whose blob collection sits at a HIGH id (the pre-Track-8 layout) imported into an
   * R3-renumbered target must classify the blob records as blobs and must NOT register a class
   * collection as a blob collection. The fixture is a real v15 export (from a source created
   * with a single blob collection) rewritten to the v14 layout: exporter-version 14, no
   * manifest, and the blob collection renumbered to the id a CLASS collection occupies in the
   * target. RED at HEAD: the importer resolves the dump's blob id raw in the target id space
   * and registers that class collection as a blob collection.
   */
  @Test
  public void crossLayoutBlobDumpRegistersBlobsByMappingNotRawId() throws Exception {
    // Source database with exactly ONE blob collection ($blob0 at id 1) and one blob record.
    var sourceName = "blobSource";
    var sourceConfig = YouTrackDBConfig.builder()
        .addGlobalConfigurationParameter(GlobalConfiguration.STORAGE_BLOB_COLLECTIONS_COUNT, 1)
        .build();
    youTrackDB.create(sourceName, dbType, sourceConfig, "admin", ADMIN_PASSWORD, "admin");
    Path dump;
    try (var source = youTrackDB.open(sourceName, "admin", ADMIN_PASSWORD)) {
      source.begin();
      source.newBlob("blob-payload".getBytes(StandardCharsets.UTF_8));
      source.commit();
      dump = dumpDirectory().resolve("v14blob.json.gz");
      new DatabaseExport(source, dump.toString(), text -> {
      }).exportDatabase();
    } finally {
      youTrackDB.drop(sourceName);
    }

    // The TARGET is created with a single blob collection too, so the layouts agree
    // everywhere EXCEPT the deliberately renumbered blob id: the dump's system-record link
    // rids (#2:x etc.) then resolve to the target's own security collections (real
    // entities) instead of pointing into a multi-blob target's blob-id range — a stale
    // mid-import link that happens to resolve to an imported blob record trips a
    // pre-existing security-predicate-init cast outside this pin's scope (was flaky,
    // 1-in-blob-count).
    var targetConfig = YouTrackDBConfig.builder()
        .addGlobalConfigurationParameter(GlobalConfiguration.STORAGE_BLOB_COLLECTIONS_COUNT, 1)
        .build();
    youTrackDB.create("blobTarget", dbType, targetConfig, "admin", ADMIN_PASSWORD, "admin");
    try (var target = youTrackDB.open("blobTarget", "admin", ADMIN_PASSWORD)) {
      var mapper = new ObjectMapper();
      var root = (ObjectNode) mapper.readTree(gunzip(dump));
      ((ObjectNode) root.get("info")).put("exporter-version", 14);
      root.remove("manifest");
      // The id the blob collection is rewritten to: one above the HIGHEST id the source dump
      // uses (so it collides with nothing inside the dump). The identically-shaped target
      // has no collection there either, so a raw NON-blob collection named 'keeper' is
      // pre-created at exactly that id — keeping the pin discriminating: a regression to
      // raw-id resolution registers 'keeper' as a blob collection. (A plain collection, not
      // a class: the deferred import preamble drops non-security CLASSES, which would
      // otherwise remove the id before the schema section resolves it.)
      var rewrittenBlobId = 0;
      for (JsonNode collection : root.get("collections")) {
        rewrittenBlobId = Math.max(rewrittenBlobId, collection.get("id").asInt() + 1);
      }
      var keeperId = target.addCollection("keeper");
      assertTrue("the 'keeper' collection must occupy the rewritten blob id (got " + keeperId
          + ", expected " + rewrittenBlobId + ")", keeperId == rewrittenBlobId);
      for (JsonNode collection : root.get("collections")) {
        if ("$blob0".equals(collection.get("name").asText())) {
          ((ObjectNode) collection).put("id", rewrittenBlobId);
        }
      }
      var schema = (ObjectNode) root.get("schema");
      var blobCollections = schema.putArray("blob-collections");
      blobCollections.add(rewrittenBlobId);
      // Rewrite the blob records' rids into the renumbered collection ($blob0 was id 1).
      var json = mapper.writeValueAsString(root)
          .replace("\"#1:", "\"#" + rewrittenBlobId + ":");
      gzipTo(dump, json.getBytes(StandardCharsets.UTF_8));

      runImport(target, dump);

      // The blob registration must cover ONLY $blob* collections — a class collection
      // registered as a blob collection is the FM-M16 misclassification.
      for (var blobCollectionId : target.getBlobCollectionIds()) {
        var collectionName = target.getCollectionNameById(blobCollectionId);
        assertTrue("blob-registered collection '" + collectionName
            + "' (id " + blobCollectionId + ") must be a $blob* collection",
            collectionName != null && collectionName.startsWith("$blob"));
      }
      // The blob record itself must have landed in a blob collection.
      target.begin();
      var blobCount = target.countCollectionElements(target.getBlobCollectionIds());
      target.rollback();
      assertTrue("the dump's blob record must land in a blob collection", blobCount >= 1);
    }
  }

  /** Exports a small dump with a marker class and three records (the shared fixture). */
  private Path exportSmallDump() throws IOException {
    session.getMetadata().getSchema().createClass("Hardened");
    session.executeInTx(transaction -> {
      for (var i = 0; i < 3; i++) {
        session.newEntity("Hardened").setInt("i", i);
      }
    });
    return exportDump();
  }

  /**
   * Design test pin M.5 #3 (companion): a v15 dump truncated MID-STREAM (inside the deflate
   * data) must also be rejected loudly — this shape already failed loudly before Step 5 (the
   * decoder hits a broken deflate stream); the pin keeps it loud.
   */
  @Test
  public void midStreamTruncatedDumpIsRejected() throws Exception {
    var dump = exportSmallDump();
    var bytes = Files.readAllBytes(dump);
    Files.write(dump, Arrays.copyOf(bytes, bytes.length / 2));

    try (var target = createTargetDatabase("midTruncTarget")) {
      var rejection = importExpectingRejection(target, dump);
      assertNotNull("a mid-stream-truncated v15 dump must be rejected loudly", rejection);
    }
  }

  /**
   * Design test pin M.5 #4a (FM-M7): garbage bytes APPENDED after a valid v15 dump's gzip
   * member — a concatenation or copy accident — must be rejected: only the physical-size
   * arithmetic (CS43 step (3), file source) can see past the decoder's clean member end.
   */
  @Test
  public void trailingGarbageAfterDumpIsRejected() throws Exception {
    var dump = exportSmallDump();
    var bytes = Files.readAllBytes(dump);
    var extended = Arrays.copyOf(bytes, bytes.length + 16);
    Arrays.fill(extended, bytes.length, extended.length, (byte) 0x5A);
    Files.write(dump, extended);

    try (var target = createTargetDatabase("garbageTarget")) {
      var rejection = importExpectingRejection(target, dump);
      assertNotNull("trailing garbage after the gzip member must be rejected", rejection);
      // Must stem from the whole-stream checks, not from a section-parsing accident.
      assertRejectionDoesNotMention(rejection, "section");
    }
  }

  /**
   * Design test pin M.5 #4b (FM-M7): a MULTI-MEMBER gzip file (a second complete member
   * concatenated after the dump) must be rejected — the validated single-member decoder
   * stops at the first member's end, and the physical-size arithmetic exposes the rest.
   */
  @Test
  public void multiMemberGzipDumpIsRejected() throws Exception {
    var dump = exportSmallDump();
    var secondMember = new java.io.ByteArrayOutputStream();
    try (var out = new GZIPOutputStream(secondMember)) {
      out.write("{\"smuggled\":true}".getBytes(StandardCharsets.UTF_8));
    }
    Files.write(dump, secondMember.toByteArray(), java.nio.file.StandardOpenOption.APPEND);

    try (var target = createTargetDatabase("multiMemberTarget")) {
      var rejection = importExpectingRejection(target, dump);
      assertNotNull("a multi-member gzip dump must be rejected", rejection);
      // Must stem from the whole-stream checks, not from a section-parsing accident.
      assertRejectionDoesNotMention(rejection, "section");
    }
  }

  /**
   * Design test pin M.5 #5a (FM-M8) and #16 (SR1): a v15 dump missing a required section
   * (here: 'indexes' — the shape a tampered or hand-assembled dump produces) must be rejected
   * loudly, naming the missing section. Per SR1's condemn-target doctrine this rejection is
   * post-mutation — the test asserts ONLY the loud failure, deliberately NOT a clean target
   * (the operator procedure discards the target on any failure).
   */
  @Test
  public void missingSectionDumpIsRejected() throws Exception {
    var dump = exportSmallDump();
    mutateDump(dump, root -> root.remove("indexes"));

    try (var target = createTargetDatabase("missingSectionTarget")) {
      var rejection = importExpectingRejection(target, dump);
      assertNotNull("a v15 dump missing its indexes section must be rejected", rejection);
      assertRejectionMentions(rejection, "missing its 'indexes' section");
    }
  }

  /**
   * Design test pin M.5 #5b (WI10c): a v15 dump carrying a DUPLICATED section (spliced second
   * 'brokenRids') must be rejected — the occurrence tracker counts, not just presence.
   */
  @Test
  public void duplicatedSectionDumpIsRejected() throws Exception {
    var dump = exportSmallDump();
    var text = new String(gunzip(dump), StandardCharsets.UTF_8);
    var spliceAt = text.lastIndexOf("\"manifest\"");
    assertTrue("the dump must carry a manifest section to splice before", spliceAt > 0);
    var tampered =
        text.substring(0, spliceAt) + "\"brokenRids\":[]," + text.substring(spliceAt);
    gzipTo(dump, tampered.getBytes(StandardCharsets.UTF_8));

    try (var target = createTargetDatabase("duplicateSectionTarget")) {
      var rejection = importExpectingRejection(target, dump);
      assertNotNull("a v15 dump with a duplicated section must be rejected", rejection);
      assertRejectionMentions(rejection, "'brokenRids' section 2 times");
    }
  }

  /**
   * Design test pin M.5 #5c (CN51) and #16 (SR1): a v15 dump whose manifest DISAGREES with
   * the sections' actual content (declared record count bumped by one — the shape a truncated
   * records array with an intact manifest produces) must be rejected loudly, naming both
   * numbers. Post-mutation rejection: the target is condemned, not asserted clean.
   */
  @Test
  public void manifestCountMismatchIsRejected() throws Exception {
    var dump = exportSmallDump();
    mutateDump(dump, root -> {
      var manifest = (ObjectNode) root.get("manifest");
      manifest.put("records", manifest.get("records").asLong() + 1);
    });

    try (var target = createTargetDatabase("manifestMismatchTarget")) {
      var rejection = importExpectingRejection(target, dump);
      assertNotNull("a manifest/content count mismatch must be rejected", rejection);
      assertRejectionMentions(rejection, "manifest declares");
    }
  }

  /**
   * Design test pin M.5 #6a (Q-M3/M2.b-1) and CS38: a manually-gunzipped v15 dump (plain JSON
   * on the file path) is ALWAYS rejected — with no override — and the rejection is genuinely
   * PRE-FLIGHT: the target keeps its pre-import state byte-for-byte (asserted via a marker
   * class and the absence of the dump's class).
   */
  @Test
  public void plainJsonV15DumpIsRejectedBeforeAnyMutation() throws Exception {
    var dump = exportSmallDump();
    var plain = dumpDirectory().resolve("plain.json");
    Files.write(plain, gunzip(dump));

    try (var target = createTargetDatabase("plainV15Target")) {
      target.getMetadata().getSchema().createClass("PreFlightMarker");

      var rejection = importExpectingRejection(target, plain);
      assertNotNull("a plain-JSON v15 dump must be rejected", rejection);
      assertRejectionMentions(rejection, "not GZIP-framed");
      // CS38: pre-flight rejections precede ALL target mutation.
      assertTrue("the pre-flight rejection must leave the target unmutated",
          target.getMetadata().getSchema().existsClass("PreFlightMarker"));
      assertTrue("no dump content may reach the target before pre-flight passes",
          !target.getMetadata().getSchema().existsClass("Hardened"));
    }
  }

  /**
   * Design test pin M.5 #6b and the #11 lenient half (R1): the same dump content DECLARING
   * exporter version 14 rides the legacy plain-JSON fallback unchanged — declared-legacy
   * dumps keep today's lenient acceptance byte-for-byte.
   */
  @Test
  public void plainJsonDeclaredV14DumpIsAccepted() throws Exception {
    var dump = exportSmallDump();
    var mapper = new ObjectMapper();
    var root = (ObjectNode) mapper.readTree(gunzip(dump));
    ((ObjectNode) root.get("info")).put("exporter-version", 14);
    root.remove("manifest");
    var plain = dumpDirectory().resolve("plain-v14.json");
    Files.write(plain, mapper.writeValueAsBytes(root));

    try (var target = createTargetDatabase("plainV14Target")) {
      runImport(target, plain);
      assertTrue("the declared-v14 plain dump must import through the lenient path",
          target.getMetadata().getSchema().existsClass("Hardened"));
    }
  }

  /**
   * Design test pin M.5 #7 (M2.b-4): the best-effort acknowledgment gate, both directions —
   * a best-effort-marked dump is refused without the explicit flag (pre-flight: target
   * unmutated), and imports once the operator passes -acceptBestEffortDump=true.
   */
  @Test
  public void bestEffortDumpRequiresExplicitAcknowledgment() throws Exception {
    var dump = exportSmallDump();
    mutateDump(dump, root -> ((ObjectNode) root.get("info")).put("best-effort", true));

    try (var target = createTargetDatabase("bestEffortTarget")) {
      target.getMetadata().getSchema().createClass("PreFlightMarker");

      var rejection = importExpectingRejection(target, dump);
      assertNotNull("an unacknowledged best-effort dump must be rejected", rejection);
      assertRejectionMentions(rejection, "-acceptBestEffortDump=true");
      assertTrue("the pre-flight rejection must leave the target unmutated",
          target.getMetadata().getSchema().existsClass("PreFlightMarker"));

      runImport(target, dump, "-acceptBestEffortDump=true");
      assertTrue("the acknowledged best-effort dump must import",
          target.getMetadata().getSchema().existsClass("Hardened"));
    }
  }

  /**
   * Design test pin WI10b: a v15 dump carrying broken RIDs WITHOUT the best-effort marker is
   * structurally impossible from an honest exporter (default mode aborts instead of recording
   * broken rids) — it must be rejected as inconsistent. The manifest is kept consistent with
   * the tampered brokenRids array so ONLY the marker check can fire.
   */
  @Test
  public void brokenRidsWithoutBestEffortMarkerIsRejected() throws Exception {
    var dump = exportSmallDump();
    mutateDump(dump, root -> {
      root.putArray("brokenRids").add("#99:1");
      ((ObjectNode) root.get("manifest")).put("brokenRids", 1);
    });

    try (var target = createTargetDatabase("brokenRidsTarget")) {
      var rejection = importExpectingRejection(target, dump);
      assertNotNull("broken RIDs without the best-effort marker must be rejected", rejection);
      assertRejectionMentions(rejection, "without the best-effort marker");
    }
  }

  /**
   * Design test pin M.5 #7 (companion) and WI10b's legitimate direction: a best-effort dump
   * WITH broken RIDs — the honest shape a damaged source produces — imports once
   * acknowledged: the marker legitimizes the brokenRids, the manifest agrees, and the v15
   * exporter's QUOTED rid tokens parse.
   */
  @Test
  public void acknowledgedBestEffortDumpWithBrokenRidsImports() throws Exception {
    var dump = exportSmallDump();
    mutateDump(dump, root -> {
      ((ObjectNode) root.get("info")).put("best-effort", true);
      root.putArray("brokenRids").add("#99:1");
      ((ObjectNode) root.get("manifest")).put("brokenRids", 1);
    });

    try (var target = createTargetDatabase("ackBrokenTarget")) {
      runImport(target, dump, "-acceptBestEffortDump=true");
      assertTrue("the acknowledged best-effort dump with broken RIDs must import",
          target.getMetadata().getSchema().existsClass("Hardened"));
    }
  }

  /**
   * Design test pin SR2 (trigger per CS46) and CS38: a dump whose info section declares NO
   * exporter version is rejected fail-closed at the first non-info section tag — BEFORE the
   * deferred preamble mutates anything (the marker class survives).
   */
  @Test
  public void undeclaredExporterVersionIsRejectedBeforeMutation() throws Exception {
    var dump = exportSmallDump();
    mutateDump(dump, root -> {
      ((ObjectNode) root.get("info")).remove("exporter-version");
      // a version-less dump may not carry the (version-gated) manifest tag either — remove it
      // so the SR2 rejection is what fires, not the manifest gate
      root.remove("manifest");
    });

    try (var target = createTargetDatabase("noVersionTarget")) {
      target.getMetadata().getSchema().createClass("PreFlightMarker");

      var rejection = importExpectingRejection(target, dump);
      assertNotNull("a dump without a declared exporter version must be rejected", rejection);
      assertRejectionMentions(rejection, "without declaring");
      assertTrue("the SR2 rejection must precede all target mutation",
          target.getMetadata().getSchema().existsClass("PreFlightMarker"));
      assertTrue("no dump content may reach the target",
          !target.getMetadata().getSchema().existsClass("Hardened"));
    }
  }

  /**
   * Review finding CS63 (regression): a trailing duplicate info section re-declaring a
   * LOWER exporter version — spliced after the manifest so every section parses under the
   * v15-strict arms — must not disarm the version-keyed structural strictness. The first
   * declared version is latched; a differing re-declaration is rejected the moment it
   * parses, and a same-value duplicate info section still trips the WI10c duplicate check.
   */
  @Test
  public void trailingInfoVersionDowngradeIsRejected() throws Exception {
    var dump = exportSmallDump();
    var text = new String(gunzip(dump), StandardCharsets.UTF_8);
    var rootClose = text.lastIndexOf('}');
    var tampered = text.substring(0, rootClose)
        + ",\"info\":{\"exporter-version\":14}" + text.substring(rootClose);
    gzipTo(dump, tampered.getBytes(StandardCharsets.UTF_8));

    try (var target = createTargetDatabase("downgradeTarget")) {
      var rejection = importExpectingRejection(target, dump);
      assertNotNull("a trailing exporter-version downgrade must be rejected", rejection);
      assertRejectionMentions(rejection, "re-declares its exporter version");
    }
  }

  /**
   * Review finding BG25/BG24/CS65 (regression): the legacy "clusters" alias is not part of
   * the v15 dump shape (the v15 exporter writes only "collections") — a spliced
   * alias-spelled section bypassed the tag-keyed WI10c duplicate/presence tracking and
   * imported silently, smuggling collections. Under v15 the alias is rejected outright.
   */
  @Test
  public void splicedClustersAliasSectionIsRejected() throws Exception {
    var dump = exportSmallDump();
    var text = new String(gunzip(dump), StandardCharsets.UTF_8);
    var spliceAt = text.lastIndexOf("\"manifest\"");
    assertTrue("the dump must carry a manifest section to splice before", spliceAt > 0);
    var tampered = text.substring(0, spliceAt)
        + "\"clusters\":[{\"name\":\"Smuggled\",\"id\":90}]," + text.substring(spliceAt);
    gzipTo(dump, tampered.getBytes(StandardCharsets.UTF_8));

    try (var target = createTargetDatabase("clustersAliasTarget")) {
      var rejection = importExpectingRejection(target, dump);
      assertNotNull("a spliced 'clusters' alias section must be rejected under v15", rejection);
      assertRejectionMentions(rejection, "'clusters'");
    }
  }

  /**
   * Review finding BG20/BG27/CS69 (regression): manifest totals are 64-bit on the exporter
   * side — an int-range parse would falsely reject an honest dump with more than 2^31-1
   * records with a bare NumberFormatException. The totals must parse as longs and flow into
   * the ordinary CN51 count comparison (which here rejects with the mismatch message naming
   * the declared 64-bit value — proving the long-parse).
   */
  @Test
  public void manifestTotalsBeyondIntRangeParseAsLong() throws Exception {
    var dump = exportSmallDump();
    mutateDump(dump, root -> ((ObjectNode) root.get("manifest")).put("records", 3000000000L));

    try (var target = createTargetDatabase("longManifestTarget")) {
      var rejection = importExpectingRejection(target, dump);
      assertNotNull("the count mismatch must still be rejected", rejection);
      assertRejectionMentions(rejection, "manifest declares 3000000000");
    }
  }

  /**
   * Review finding TQ22/TQ25 (WI10a steps (1)+(2) on the InputStream constructor): a
   * GZIP-FRAMED v15 dump fed through the stream constructor imports successfully — the
   * validated decoder and the whole-stream drain/verification run with no physical size
   * (step (3) never applies to streams).
   */
  @Test
  public void gzipStreamImportRoundTrips() throws Exception {
    var dump = exportSmallDump();

    try (var target = createTargetDatabase("gzipStreamTarget")) {
      try (var stream = java.nio.file.Files.newInputStream(dump)) {
        new DatabaseImport(target, stream, text -> {
        }).importDatabase();
      }
      assertTrue("the gzip-framed v15 stream import must round-trip",
          target.getMetadata().getSchema().existsClass("Hardened"));
    }
  }

  /**
   * Review finding TQ22/TQ25 (rejecting half): the SAME gzip-framed v15 dump with its
   * trailer truncated, fed through the InputStream constructor, must be rejected loudly by
   * the CS43 drain — pinning that the whole-stream verification is NOT gated on having a
   * physical size.
   */
  @Test
  public void truncatedGzipStreamIsRejected() throws Exception {
    var dump = exportSmallDump();
    var bytes = Files.readAllBytes(dump);
    var truncated = Arrays.copyOf(bytes, bytes.length - 8);

    try (var target = createTargetDatabase("truncStreamTarget")) {
      RuntimeException rejection = null;
      try {
        new DatabaseImport(target, new java.io.ByteArrayInputStream(truncated), text -> {
        }).importDatabase();
      } catch (DatabaseImportException | DatabaseExportException e) {
        rejection = e;
      }
      assertNotNull("a truncated gzip stream must be rejected loudly", rejection);
      assertRejectionMentions(rejection, "Truncated GZIP trailer");
    }
  }

  /**
   * Review finding TQ28 (SR2's end-of-stream arm): a dump that is exactly an info section
   * WITHOUT an exporter version, with the root closing right after — the section loop exits
   * at the root brace and only the post-loop end-of-stream arm can reject it.
   */
  @Test
  public void versionlessInfoOnlyDumpIsRejectedAtEndOfStream() throws Exception {
    var dump = dumpDirectory().resolve("info-only.json.gz");
    gzipTo(dump, "{\"info\":{\"name\":\"x\"}}".getBytes(StandardCharsets.UTF_8));

    try (var target = createTargetDatabase("infoOnlyTarget")) {
      target.getMetadata().getSchema().createClass("PreFlightMarker");

      var rejection = importExpectingRejection(target, dump);
      assertNotNull("a versionless info-only dump must be rejected", rejection);
      assertRejectionMentions(rejection, "ended without declaring");
      assertTrue("the SR2 rejection must precede all target mutation",
          target.getMetadata().getSchema().existsClass("PreFlightMarker"));
    }
  }

  /**
   * Design test pin SR2 (degenerate shape): an (effectively) empty dump — `{}` — never
   * declares a version and is rejected fail-closed without touching the target.
   */
  @Test
  public void emptyDumpIsRejected() throws Exception {
    var dump = dumpDirectory().resolve("empty.json.gz");
    gzipTo(dump, "{}".getBytes(StandardCharsets.UTF_8));

    try (var target = createTargetDatabase("emptyDumpTarget")) {
      target.getMetadata().getSchema().createClass("PreFlightMarker");

      var rejection = importExpectingRejection(target, dump);
      assertNotNull("an empty dump must be rejected", rejection);
      assertTrue("the rejection must precede all target mutation",
          target.getMetadata().getSchema().existsClass("PreFlightMarker"));
    }
  }
}
