package com.jetbrains.youtrackdb.internal.core.db.tool;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.command.CommandOutputListener;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.record.RecordAbstract;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

/**
 * Pins the Track 8 Step 4 export hardening (design M2.a; failure modes FM-M1..M5, M9, M15,
 * M17): a failed export aborts loudly with the scan failure as the primary cause, never
 * promotes anything, and preserves the operator's previous dump at the final name.
 */
public class DatabaseExportHardeningTest extends DbTestBase {

  private static final byte[] PREVIOUS_DUMP_SENTINEL =
      "PREVIOUS-GOOD-DUMP".getBytes(StandardCharsets.UTF_8);

  private Path exportDirectory() throws IOException {
    var dir = Path.of(DbTestBase.getBaseDirectoryPathStr(getClass()), "exports",
        name.getMethodName());
    Files.createDirectories(dir);
    return dir;
  }

  /**
   * Design test pin M.5 #1 (red-first; FM-M1/M3 + CS41): a failure injected into the record
   * scan aborts the export loudly \u2014 the thrown {@code DatabaseExportException} carries the
   * injected failure in its cause chain \u2014 and the final name is UNTOUCHED: the pre-existing
   * dump is preserved (never deleted upfront, never overwritten by a partial dump) and no temp
   * file is left behind. RED at HEAD: the scan failure is swallowed into a success exit and
   * {@code close()} in the {@code finally} promotes the partial dump over the previous one
   * (which the constructor had already deleted upfront).
   */
  @Test
  public void injectedScanFailureAbortsWithoutTouchingFinalName() throws Exception {
    var cls = session.getMetadata().getSchema().createClass("ScanFail");
    session.executeInTx(transaction -> {
      for (var i = 0; i < 30; i++) {
        session.newEntity("ScanFail").setInt("i", i);
      }
    });
    var scanFailCollection =
        session.getCollectionNameById(cls.getCollectionIds()[0]);

    var exportDir = exportDirectory();
    var finalFile = exportDir.resolve("scanfail.json.gz");
    Files.write(finalFile, PREVIOUS_DUMP_SENTINEL);

    var injected = new IllegalStateException("injected scan failure");
    // The exporter reports each collection's record scan through the listener; throwing there
    // injects a genuine mid-records-phase failure through a production seam (after the info,
    // collections and schema sections were already written to the temp stream).
    final CommandOutputListener listener = text -> {
      if (text != null && text.contains("- Collection '" + scanFailCollection + "'")) {
        throw injected;
      }
    };

    DatabaseExportException thrown = null;
    try {
      var export = new DatabaseExport(session, finalFile.toString(), listener);
      export.exportDatabase();
    } catch (DatabaseExportException e) {
      thrown = e;
    }

    assertNotNull("a mid-scan failure must abort the export loudly", thrown);
    var causeChainCarriesInjection = false;
    for (Throwable t = thrown; t != null; t = t.getCause()) {
      if (t == injected) {
        causeChainCarriesInjection = true;
        break;
      }
    }
    assertTrue("the injected scan failure must be the export failure's primary cause, saw: "
        + thrown, causeChainCarriesInjection);

    assertArrayEquals("the pre-existing dump at the final name must be preserved on failure",
        PREVIOUS_DUMP_SENTINEL, Files.readAllBytes(finalFile));
    try (var files = Files.list(exportDir)) {
      var residue = files.filter(p -> !p.equals(finalFile)).toList();
      assertTrue("a failed export must leave no temp residue, saw: " + residue,
          residue.isEmpty());
    }
  }

  /** A test exporter that injects a render failure for exactly one record. */
  private static final class RenderFailureExport extends DatabaseExport {

    private final RID failingRid;

    RenderFailureExport(DatabaseSessionEmbedded session, String fileName,
        CommandOutputListener listener, RID failingRid) throws IOException {
      super(session, fileName, listener);
      this.failingRid = failingRid;
    }

    @Override
    protected void renderRecord(RecordAbstract rec, JsonGenerator recordGenerator)
        throws IOException {
      if (rec.getIdentity().equals(failingRid)) {
        throw new IllegalStateException("injected render failure for " + failingRid);
      }
      super.renderRecord(rec, recordGenerator);
    }
  }

  /**
   * Parses a dump through the validated-gzip primitive with the full CS43 sequence — every
   * dump these tests promote must survive it end-to-end.
   */
  private static JsonNode parseDump(Path dump) throws IOException {
    try (var in = new ValidatedGZIPInputStream(Files.newInputStream(dump))) {
      var bytes = in.readAllBytes();
      in.verifyPhysicalSize(Files.size(dump));
      return new ObjectMapper().readTree(bytes);
    }
  }

  /**
   * Design test pin M.5 #2, the fail-fast default (FM-M2): a record whose RENDERING fails
   * aborts the export — nothing is promoted, the failure carries the render exception, and the
   * message points at the best-effort opt-out.
   */
  @Test
  public void renderFailureAbortsByDefault() throws Exception {
    session.getMetadata().getSchema().createClass("RenderFail");
    var failingRid = session.computeInTx(transaction -> {
      var poisoned = session.newEntity("RenderFail");
      poisoned.setString("marker", "poisoned");
      for (var i = 0; i < 5; i++) {
        session.newEntity("RenderFail").setInt("i", i);
      }
      return poisoned.getIdentity();
    });

    var exportDir = exportDirectory();
    var finalFile = exportDir.resolve("renderfail.json.gz");

    DatabaseExportException thrown = null;
    try {
      new RenderFailureExport(session, finalFile.toString(), text -> {
      }, failingRid).exportDatabase();
    } catch (DatabaseExportException e) {
      thrown = e;
    }
    assertNotNull("a render failure must abort the export by default", thrown);
    assertTrue("the abort must point at the best-effort opt-out, saw: " + thrown.getMessage(),
        thrown.getMessage().contains("-bestEffort=true"));

    assertTrue("nothing may be promoted on a default-mode render failure",
        Files.notExists(finalFile));
    try (var files = Files.list(exportDir)) {
      assertTrue("no temp residue may remain", files.findAny().isEmpty());
    }
  }

  /**
   * Design test pin M.5 #2, the best-effort half: with the explicit {@code -bestEffort=true}
   * opt-out the failing record is discarded WHOLE — the dump parses end-to-end, carries the
   * skipped RID in {@code brokenRids}, records the opt-out as the info-section marker, and the
   * manifest's exporter-tallied counts match the dump's actual content.
   */
  @Test
  public void bestEffortDiscardsWholeRecordAndRecordsTheMarker() throws Exception {
    session.getMetadata().getSchema().createClass("BestEffort");
    var failingRid = session.computeInTx(transaction -> {
      var poisoned = session.newEntity("BestEffort");
      poisoned.setString("marker", "poisoned");
      for (var i = 0; i < 5; i++) {
        session.newEntity("BestEffort").setInt("i", i);
      }
      return poisoned.getIdentity();
    });

    var exportDir = exportDirectory();
    var finalFile = exportDir.resolve("besteffort.json.gz");

    var export = new RenderFailureExport(session, finalFile.toString(), text -> {
    }, failingRid);
    export.setOptions(" -bestEffort=true");
    export.exportDatabase();

    assertTrue("the best-effort export must promote", Files.exists(finalFile));
    var dump = parseDump(finalFile);

    assertTrue("the opt-out must be recorded as the info-section marker",
        dump.get("info").get("best-effort").asBoolean());

    var brokenRids = dump.get("brokenRids");
    assertEquals("the skipped record must be registered in brokenRids", 1, brokenRids.size());
    assertEquals(failingRid.toString(), brokenRids.get(0).asText());

    // The discarded record must be WHOLLY absent from the records array.
    for (var record : dump.get("records")) {
      assertFalse("the discarded record must not appear in the dump",
          failingRid.toString().equals(record.path("@rid").asText()));
    }

    assertManifestMatchesContent(dump);
  }

  /**
   * Design test pin M.5 #8 (FM-M9 + Q-M1): a record larger than the spill threshold spills to
   * a transient file, is present WHOLE in the promoted dump, and no spill file survives the
   * export.
   */
  @Test
  public void oversizedRecordSpillsAndIsExportedWhole() throws Exception {
    session.getMetadata().getSchema().createClass("BigRecord");
    var bigValue = "x".repeat(64 * 1024);
    session
        .executeInTx(transaction -> session.newEntity("BigRecord").setString("payload", bigValue));

    var exportDir = exportDirectory();
    var finalFile = exportDir.resolve("spill.json.gz");

    var previousThreshold = GlobalConfiguration.EXPORT_RECORD_SPILL_THRESHOLD.getValue();
    GlobalConfiguration.EXPORT_RECORD_SPILL_THRESHOLD.setValue(1024);
    try {
      new DatabaseExport(session, finalFile.toString(), text -> {
      }).exportDatabase();
    } finally {
      GlobalConfiguration.EXPORT_RECORD_SPILL_THRESHOLD.setValue(previousThreshold);
    }

    var dump = parseDump(finalFile);
    var bigRecordSeen = false;
    for (var record : dump.get("records")) {
      if (bigValue.equals(record.path("payload").asText())) {
        bigRecordSeen = true;
        break;
      }
    }
    assertTrue("the oversized record must be present WHOLE in the dump", bigRecordSeen);

    try (var files = Files.list(exportDir)) {
      var residue = files.filter(p -> !p.equals(finalFile)).toList();
      assertTrue("no spill or temp file may survive the export, saw: " + residue,
          residue.isEmpty());
    }
  }

  /**
   * Design test pin M.5 #17, the unflagged-close half (M2.a-4): a {@code close()} without a
   * completed export NEVER renames — the final name stays untouched and the unique temp file
   * is deleted.
   */
  @Test
  public void unflaggedCloseNeverRenames() throws Exception {
    var exportDir = exportDirectory();
    var finalFile = exportDir.resolve("unflagged.json.gz");
    Files.write(finalFile, PREVIOUS_DUMP_SENTINEL);

    var export = new DatabaseExport(session, finalFile.toString(), text -> {
    });
    export.close();

    assertArrayEquals("an unflagged close must leave the final name untouched",
        PREVIOUS_DUMP_SENTINEL, Files.readAllBytes(finalFile));
    try (var files = Files.list(exportDir)) {
      var residue = files.filter(p -> !p.equals(finalFile)).toList();
      assertTrue("an unflagged close must delete its temp file, saw: " + residue,
          residue.isEmpty());
    }
  }

  /**
   * Design test pin M.5 #17, the CN52 half: two exporters of the SAME final name constructed
   * concurrently hold two distinct {@code CREATE_NEW} temp files (no byte interleaving is
   * possible), both promotes succeed, the surviving dump parses end-to-end, and no temp
   * residue remains.
   */
  @Test
  public void concurrentExportersUseUniqueTempFilesAndPromoteConsistentDumps() throws Exception {
    session.getMetadata().getSchema().createClass("Concurrent");
    session.executeInTx(transaction -> session.newEntity("Concurrent").setInt("i", 1));

    var exportDir = exportDirectory();
    var finalFile = exportDir.resolve("concurrent.json.gz");

    // Both constructed BEFORE either exports: with a fixed temp name the second CREATE_NEW
    // would collide; with per-export unique names both coexist.
    var first = new DatabaseExport(session, finalFile.toString(), text -> {
    });
    var second = new DatabaseExport(session, finalFile.toString(), text -> {
    });
    try (var files = Files.list(exportDir)) {
      assertEquals("both exporters must hold their own temp file", 2, files.count());
    }

    first.exportDatabase();
    second.exportDatabase();

    var dump = parseDump(finalFile);
    assertManifestMatchesContent(dump);
    try (var files = Files.list(exportDir)) {
      var residue = files.filter(p -> !p.equals(finalFile)).toList();
      assertTrue("no temp residue may remain after both promotes, saw: " + residue,
          residue.isEmpty());
    }
  }

  /**
   * Design test pin M.5 #17, the manifest-provenance half (CN51/FM-M17): the manifest's counts
   * are the exporter's OWN tallies of what it wrote — verified by comparing them against the
   * dump's actual section contents — and they stay self-consistent when concurrent DDL (a
   * class created mid-export from another session) changes the live schema under the export.
   */
  @Test
  public void manifestStaysSelfConsistentUnderConcurrentDdl() throws Exception {
    session.getMetadata().getSchema().createClass("BeforeExport");
    session.executeInTx(transaction -> session.newEntity("BeforeExport").setInt("i", 1));

    var exportDir = exportDirectory();
    var finalFile = exportDir.resolve("concurrentddl.json.gz");

    // Deterministic mid-export DDL: when the exporter reports the records phase, a SECOND
    // session creates a class on the live schema (the legacy top-level path).
    var ddlFired = new AtomicBoolean();
    final CommandOutputListener listener = text -> {
      if (text != null && text.contains("Exporting records") && ddlFired.compareAndSet(false,
          true)) {
        try (var other = openDatabase()) {
          other.getMetadata().getSchema().createClass("MidExportClass");
        }
      }
    };

    new DatabaseExport(session, finalFile.toString(), listener).exportDatabase();
    assertTrue("the mid-export DDL must have fired", ddlFired.get());

    assertManifestMatchesContent(parseDump(finalFile));
  }

  /** CN51: the manifest's counts equal the dump's ACTUAL section contents. */
  private static void assertManifestMatchesContent(JsonNode dump) {
    var manifest = dump.get("manifest");
    assertNotNull("the dump must carry the trailing manifest section", manifest);
    assertEquals("manifest.classes must match the schema section's content",
        dump.get("schema").get("classes").size(), manifest.get("classes").asInt());
    assertEquals("manifest.records must match the records section's content",
        dump.get("records").size(), manifest.get("records").asInt());
    assertEquals("manifest.indexes must match the indexes section's content",
        dump.get("indexes").size(), manifest.get("indexes").asInt());
    assertEquals("manifest.brokenRids must match the brokenRids section's content",
        dump.get("brokenRids").size(), manifest.get("brokenRids").asInt());
  }
}
