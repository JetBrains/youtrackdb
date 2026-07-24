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
package com.jetbrains.youtrackdb.internal.core.db.tool;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.common.io.FileUtils;
import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.core.YouTrackDBConstants;
import com.jetbrains.youtrackdb.internal.core.command.CommandOutputListener;
import com.jetbrains.youtrackdb.internal.core.config.StorageConfiguration;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.metadata.MetadataDefault;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaShared;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaProperty;
import com.jetbrains.youtrackdb.internal.core.record.RecordAbstract;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.string.JSONSerializerJackson;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.Deflater;
import java.util.zip.GZIPOutputStream;

/**
 * Export data from a database to a file.
 */
public class DatabaseExport extends DatabaseImpExpAbstract<DatabaseSessionEmbedded> {

  public static final int EXPORTER_VERSION = 15;

  protected JsonGenerator jsonGenerator;
  protected long recordExported;
  protected int compressionLevel = Deflater.BEST_SPEED;
  protected int compressionBuffer = 16384; // 16Kb

  private final JsonFactory jsonFactory = new JsonFactory();

  private final String tempFileName;

  /**
   * The directory the per-record rendering buffers spill to beyond the
   * {@link GlobalConfiguration#EXPORT_RECORD_SPILL_THRESHOLD} threshold: the dump's own
   * directory for file exports (same volume as the dump), the JVM temp directory for the
   * streaming variant.
   */
  private final Path spillDirectory;

  private final int recordSpillThreshold;

  /**
   * Best-effort mode (design M2.a-2, explicit opt-OUT of the fail-fast default via the
   * {@code -bestEffort=true} option): a record whose rendering fails is discarded WHOLE and
   * recorded in {@code brokenRids} instead of aborting the export. The choice is recorded as
   * the {@code best-effort} scalar marker in the dump's info section so the importer can
   * enforce its acknowledgment gate.
   */
  private boolean bestEffort = false;

  /**
   * The completion flag (design M2.a-4): set only after the LAST section (the manifest) was
   * written and the stream closed cleanly. Only a completed export promotes the temp file to
   * the final name; {@link #close()} without it never renames.
   */
  private boolean completed = false;

  // Exporter-tallied manifest provenance (design M2.a-5, CN51): each section counts what it
  // ACTUALLY wrote as it writes; the manifest is never re-derived from a fresh schema snapshot
  // (which under concurrent DDL would disagree with the dump's content).
  private long manifestClasses;
  private long manifestIndexes;
  private long manifestBrokenRids;

  // these classes will be exported first. import tool relies on this order.
  private static final Set<String> PRIORITY_EXPORT_CLASSES =
      Set.of(SchemaClass.VERTEX_CLASS_NAME, SchemaClass.EDGE_CLASS_NAME);

  public DatabaseExport(
      final DatabaseSessionEmbedded iDatabase,
      final String iFileName,
      final CommandOutputListener iListener)
      throws IOException {
    super(iDatabase, iFileName, iListener);
    if (fileName == null) {
      throw new IllegalArgumentException("file name missing");
    }

    if (!fileName.endsWith(".gz")) {
      fileName += ".gz";
    }
    // The final name is NOT touched here (design CS41): the operator's previous dump survives
    // until a verified, completed export replaces it atomically at promote time.
    final var finalPath = Paths.get(fileName).toAbsolutePath();
    final var parent = finalPath.getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    this.spillDirectory = parent != null ? parent : Paths.get(".");
    this.recordSpillThreshold = iDatabase.getConfiguration()
        .getValueAsInteger(GlobalConfiguration.EXPORT_RECORD_SPILL_THRESHOLD);

    // Per-export unique temp name opened CREATE_NEW (design CN52): two concurrent exporters of
    // the same target can never interleave bytes in one temp file — each promotes its own,
    // internally consistent dump.
    this.tempFileName = fileName + "." + UUID.randomUUID() + ".tmp";
    final var tempOut = Files.newOutputStream(Paths.get(tempFileName),
        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);

    try {
      final var gzipOS =
          new GZIPOutputStream(tempOut, compressionBuffer) {
            {
              def.setLevel(compressionLevel);
            }
          };

      jsonGenerator = jsonFactory.createGenerator(new OutputStreamWriter(gzipOS));
      jsonGenerator.writeStartObject();
    } catch (IOException | RuntimeException e) {
      // A constructor failure after the CREATE_NEW open (a full disk during the gzip header
      // write, a generator failure) must not orphan the temp file — the caller has no export
      // object to close.
      try {
        tempOut.close();
      } catch (Exception secondary) {
        e.addSuppressed(secondary);
      }
      try {
        Files.deleteIfExists(Paths.get(tempFileName));
      } catch (IOException secondary) {
        e.addSuppressed(secondary);
      }
      throw e;
    }
  }

  public DatabaseExport(
      final DatabaseSessionEmbedded iDatabase,
      final OutputStream iOutputStream,
      final CommandOutputListener iListener)
      throws IOException {
    super(iDatabase, "streaming", iListener);
    this.tempFileName = null;
    this.spillDirectory = Paths.get(System.getProperty("java.io.tmpdir"));
    this.recordSpillThreshold = iDatabase.getConfiguration()
        .getValueAsInteger(GlobalConfiguration.EXPORT_RECORD_SPILL_THRESHOLD);

    jsonGenerator = jsonFactory.createGenerator(new OutputStreamWriter(iOutputStream));
    jsonGenerator.writeStartObject();
  }

  @Override
  public void run() {
    exportDatabase();
  }

  @Override
  public DatabaseExport setOptions(final String s) {
    super.setOptions(s);
    return this;
  }

  public DatabaseExport exportDatabase() {
    try {
      listener.onMessage(
          "\nStarted export of database '" + session.getDatabaseName() + "' to " + fileName
              + "...");

      var time = System.nanoTime();

      session.executeInTx(transaction -> {
        try {
          exportInfo();
          exportCollections();
          exportSchema();
          exportRecords();
          exportIndexDefinitions();
          // The manifest is the LAST section (design M2.a-5/R2): a dump carrying it declares
          // that every earlier section was written completely.
          exportManifest();
        } catch (IOException e) {
          throw new DatabaseExportException(
              "Error on exporting database '" + session.getDatabaseName() + "' to: " + fileName, e);
        }
      });

      listener.onMessage(
          "\n\nDatabase export completed in " + ((System.nanoTime() - time) / 1000000) + "ms");

      // Finish the stream cleanly (closing brace, generator close flushing the gzip trailer),
      // promote, and only then mark the export completed. A failure anywhere above — including
      // the promote itself — routes through the catch: nothing (further) is promoted, the temp
      // file is deleted, and because the flag stays unset a later close() retries the temp
      // cleanup (a failed promote whose inline delete also failed is not orphaned forever).
      jsonGenerator.writeEndObject();
      jsonGenerator.close();
      jsonGenerator = null;
      promote();
      completed = true;
    } catch (Exception e) {
      LogManager.instance()
          .error(this, "Error on exporting database '%s' to: %s", e, session.getDatabaseName(),
              fileName);
      // Primary-exception preservation (design M2.a-6): the scan/render failure is the primary
      // cause; cleanup secondaries are attached as suppressed, never replacing it.
      final var primary =
          e instanceof DatabaseExportException alreadyWrapped
              ? alreadyWrapped
              : new DatabaseExportException(
                  "Error on exporting database '" + session.getDatabaseName() + "' to: "
                      + fileName,
                  e);
      cleanUpOnFailure(primary);
      throw primary;
    } finally {
      // Belt for non-Exception throwables (OOM, assertion errors): close() is completion-gated
      // — a no-op after success, an abort (close stream, delete temp, never rename) otherwise —
      // so the finally cannot promote and cannot mask a primary.
      close();
    }
    return this;
  }

  /**
   * Promotes the completed dump to the final name with the durable CS40 recipe (file fsync
   * through a reopened channel, {@code ATOMIC_MOVE}+{@code REPLACE_EXISTING} rename, parent
   * directory fsync; fail-closed — no copy fallback). A no-op for the streaming variant, whose
   * completion marker is the manifest section itself.
   */
  private void promote() throws IOException {
    if (tempFileName == null) {
      return;
    }
    FileUtils.durableAtomicMove(Paths.get(tempFileName), Paths.get(fileName), this);
  }

  /**
   * The failure-path cleanup: closes the stream and deletes the unique temp file — the final
   * name is NEVER touched, so the operator's previous dump survives every failed export.
   * Cleanup failures are attached to the primary as suppressed (design M2.a-6).
   */
  private void cleanUpOnFailure(Exception primary) {
    if (jsonGenerator != null) {
      try {
        jsonGenerator.close();
      } catch (Exception secondary) {
        primary.addSuppressed(secondary);
      }
      jsonGenerator = null;
    }
    if (tempFileName != null) {
      try {
        Files.deleteIfExists(Paths.get(tempFileName));
      } catch (IOException secondary) {
        primary.addSuppressed(secondary);
      }
    }
  }

  /**
   * The trailing manifest section (design M2.a-5, shape pinned by WI8c): TOTAL counts of the
   * classes, indexes, records and broken RIDs this export ACTUALLY wrote, tallied by the
   * writing loops themselves (CN51 provenance) — so a best-effort dump's manifest matches what
   * is present, and a dump written under concurrent DDL stays self-consistent.
   */
  private void exportManifest() throws IOException {
    listener.onMessage("\nWriting the manifest...");

    jsonGenerator.writeObjectFieldStart("manifest");
    jsonGenerator.writeNumberField("classes", manifestClasses);
    jsonGenerator.writeNumberField("indexes", manifestIndexes);
    jsonGenerator.writeNumberField("records", recordExported);
    jsonGenerator.writeNumberField("brokenRids", manifestBrokenRids);
    jsonGenerator.writeEndObject();

    listener.onMessage("OK");
  }

  private void exportRecords() throws IOException {
    long totalFoundRecords = 0;
    long totalExportedRecords = 0;

    listener.onMessage("\nExporting records...");

    final Set<RID> brokenRids = new HashSet<>();

    jsonGenerator.writeFieldName("records");
    jsonGenerator.writeStartArray();

    var exportedCollections = 0;
    var maxCollectionId = getMaxCollectionId();
    for (var i = 0; exportedCollections <= maxCollectionId; ++i) {
      var collectionName = session.getCollectionNameById(i);

      if (MetadataDefault.COLLECTION_INTERNAL_NAME.equals(collectionName)) {
        continue;
      }

      exportedCollections++;

      long collectionExportedRecordsTot = 0;
      if (collectionName != null) {
        // CHECK IF THE COLLECTION IS INCLUDED
        collectionExportedRecordsTot = session.getApproximateCollectionCount(collectionName);
      }

      listener.onMessage(
          "\n- Collection "
              + (collectionName != null ? "'" + collectionName + "'" : "NULL")
              + " (id="
              + i
              + ")...");

      long collectionExportedRecordsCurrent = 0;
      if (collectionName != null) {
        RecordAbstract rec = null;
        try {
          var it = browseCollectionRecords(collectionName);

          while (it.hasNext()) {
            rec = it.next();

            if (exportRecord(
                collectionExportedRecordsTot, collectionExportedRecordsCurrent, rec, brokenRids)) {
              collectionExportedRecordsCurrent++;
            }
          }
        } catch (DatabaseExportException e) {
          // Already wrapped by the per-record arm (default-mode render abort) — rethrow as is.
          throw e;
        } catch (Exception t) {
          // The collection-scan arm ALWAYS rethrows (design M2.a-2/FM-M1): a mid-scan failure
          // must abort the export loudly — the pre-hardening code logged it and continued into
          // a success exit that promoted an incomplete dump.
          LogManager.instance()
              .error(
                  this,
                  "\nError on exporting collection '%s'%s",
                  t,
                  collectionName,
                  rec == null ? "" : " (last record: " + rec.getIdentity() + ")");
          throw new DatabaseExportException(
              "Error on exporting collection '"
                  + collectionName
                  + "' of database '"
                  + session.getDatabaseName()
                  + "'",
              t);
        }
      }

      listener.onMessage(
          "OK (records="
              + collectionExportedRecordsCurrent + "/" + collectionExportedRecordsTot + ")");

      totalExportedRecords += collectionExportedRecordsCurrent;
      totalFoundRecords += collectionExportedRecordsTot;
    }
    jsonGenerator.writeEndArray();

    listener.onMessage(
        "\n\nDone. Exported "
            + totalExportedRecords
            + " of total "
            + totalFoundRecords
            + " records. "
            + brokenRids.size()
            + " records were detected as broken\n");

    jsonGenerator.writeFieldName("brokenRids");
    jsonGenerator.writeStartArray();

    for (final var rid : brokenRids) {
      jsonGenerator.writeString(rid.toString());
    }
    jsonGenerator.writeEndArray();
    manifestBrokenRids = brokenRids.size();
  }

  /**
   * Closes the export. After a successful {@link #exportDatabase()} the stream is already
   * closed and the dump promoted, so this is a no-op. A close WITHOUT a completed export is an
   * abort: the stream is closed and the unique temp file deleted — the final name is never
   * touched (an unflagged close never renames; design M2.a-4, pin M.5 #17).
   */
  public void close() {
    if (completed) {
      return;
    }

    if (jsonGenerator != null) {
      try {
        jsonGenerator.close();
      } catch (Exception e) {
        LogManager.instance()
            .warn(this, "Error on closing the aborted export of database '%s'", e,
                session.getDatabaseName());
      }
      jsonGenerator = null;
    }
    if (tempFileName != null) {
      try {
        Files.deleteIfExists(Paths.get(tempFileName));
      } catch (IOException e) {
        LogManager.instance()
            .warn(this, "Error on deleting the aborted export temp file '%s'", e, tempFileName);
      }
    }
  }

  private int getMaxCollectionId() {
    var totalCollection = -1;
    for (var collectionName : session.getCollectionNames()) {
      if (session.getCollectionIdByName(collectionName) > totalCollection) {
        totalCollection = session.getCollectionIdByName(collectionName);
      }
    }
    return totalCollection;
  }

  @Override
  protected void parseSetting(final String option, final List<String> items) {
    if (option.equalsIgnoreCase("-compressionLevel")) {
      compressionLevel = Integer.parseInt(items.getFirst());
    } else if (option.equalsIgnoreCase("-compressionBuffer")) {
      compressionBuffer = Integer.parseInt(items.getFirst());
    } else if (option.equalsIgnoreCase("-bestEffort")) {
      bestEffort = Boolean.parseBoolean(items.getFirst());
    } else {
      super.parseSetting(option, items);
    }
  }

  private void exportCollections() throws IOException {
    listener.onMessage("\nExporting collections...");

    jsonGenerator.writeFieldName("collections");
    jsonGenerator.writeStartArray();
    var exportedCollections = 0;

    var maxCollectionId = getMaxCollectionId();

    for (var collectionId = 0; collectionId <= maxCollectionId; ++collectionId) {

      final var collectionName = session.getCollectionNameById(collectionId);

      // exclude removed collections
      if (collectionName == null) {
        continue;
      }

      // CHECK IF THE COLLECTION IS INCLUDED
      jsonGenerator.writeStartObject();
      jsonGenerator.writeFieldName("name");
      jsonGenerator.writeString(collectionName);

      jsonGenerator.writeFieldName("id");
      jsonGenerator.writeNumber(collectionId);

      exportedCollections++;
      jsonGenerator.writeEndObject();
    }

    listener.onMessage("OK (" + exportedCollections + " collections)");

    jsonGenerator.writeEndArray();
  }

  private void exportInfo() throws IOException {
    listener.onMessage("\nExporting database info...");

    jsonGenerator.writeObjectFieldStart("info");
    jsonGenerator.writeFieldName("name");
    jsonGenerator.writeString(session.getDatabaseName().replace('\\', '/'));

    jsonGenerator.writeFieldName("exporter-version");
    jsonGenerator.writeNumber(EXPORTER_VERSION);

    jsonGenerator.writeFieldName("engine-version");
    jsonGenerator.writeString(YouTrackDBConstants.getVersion());

    final var engineBuild = YouTrackDBConstants.getBuildNumber();
    if (engineBuild != null) {
      jsonGenerator.writeFieldName("engine-build");
      jsonGenerator.writeString(engineBuild);
    }

    jsonGenerator.writeNumberField("storage-config-version",
        StorageConfiguration.CURRENT_VERSION);
    jsonGenerator.writeNumberField("schema-version", SchemaShared.CURRENT_VERSION_NUMBER);
    jsonGenerator.writeStringField("schemaRecordId",
        session.getStorage().getSchemaRecordId());
    jsonGenerator.writeStringField("indexMgrRecordId",
        session.getStorage().getIndexMgrRecordId());
    if (bestEffort) {
      // The best-effort scalar marker (design M2.a-2): a v15-aware importer refuses a
      // best-effort dump unless the operator acknowledges it explicitly.
      jsonGenerator.writeBooleanField("best-effort", true);
    }
    jsonGenerator.writeEndObject();

    listener.onMessage("OK");
  }

  private void exportIndexDefinitions() throws IOException {
    listener.onMessage("\nExporting index info...");

    jsonGenerator.writeArrayFieldStart("indexes");

    final var indexManager = session.getSharedContext().getIndexManager();
    indexManager.reload(session);

    final var indexes = indexManager.getIndexes();

    for (var index : indexes) {
      final var clsName =
          index.getDefinition() != null ? index.getDefinition().getClassName() : null;
      if (DatabaseImport.EXPORT_IMPORT_CLASS_NAME.equals(clsName)) {
        continue;
      }

      // CHECK TO FILTER CLASS
      listener.onMessage("\n- Index " + index.getName() + "...");
      jsonGenerator.writeStartObject();
      manifestIndexes++;

      jsonGenerator.writeStringField("name", index.getName());
      jsonGenerator.writeStringField("type", index.getType());

      if (index.getAlgorithm() != null) {
        jsonGenerator.writeStringField("algorithm", index.getAlgorithm());
      }

      if (!index.getCollections().isEmpty()) {
        jsonGenerator.writeArrayFieldStart("collectionsToIndex");
        for (var collection : index.getCollections()) {
          jsonGenerator.writeString(collection);
        }
        jsonGenerator.writeEndArray();
      }

      if (index.getDefinition() != null) {
        jsonGenerator.writeObjectFieldStart("definition");
        jsonGenerator.writeStringField("defClass", index.getDefinition().getClass().getName());

        jsonGenerator.writeFieldName("stream");
        index.getDefinition().toJson(jsonGenerator);
        jsonGenerator.writeEndObject();
      }

      final var metadata = index.getMetadata();
      if (metadata != null) {
        jsonGenerator.writeFieldName("metadata");
        JSONSerializerJackson.INSTANCE.serializeEmbeddedMap(session, jsonGenerator, metadata, null);
      }

      jsonGenerator.writeEndObject();
      listener.onMessage("OK");
    }

    jsonGenerator.writeEndArray();
    listener.onMessage("\nOK (" + indexes.size() + " indexes)");
  }

  private void exportSchema() throws IOException {
    listener.onMessage("\nExporting schema...");

    jsonGenerator.writeObjectFieldStart("schema");
    final Schema schema = session.getMetadata().getImmutableSchemaSnapshot();
    // The schema-format version (design M2.a-7): the section keeps its slot for format
    // stability but writes the on-disk format constant — schema.getVersion() is a process-wide
    // generation token since the tx-aware snapshot work, meaningless to consumers.
    jsonGenerator.writeNumberField("version", SchemaShared.CURRENT_VERSION_NUMBER);
    jsonGenerator.writeArrayFieldStart("blob-collections");
    for (var collectionId : session.getBlobCollectionIds()) {
      jsonGenerator.writeNumber(collectionId);
    }
    jsonGenerator.writeEndArray();

    if (!schema.getClasses().isEmpty()) {
      jsonGenerator.writeArrayFieldStart("classes");

      final List<SchemaClass> classes = new ArrayList<>(schema.getClasses());
      classes.sort(Comparator.comparing(SchemaClass::getName, (n1, n2) -> {
        final var n1priority = PRIORITY_EXPORT_CLASSES.contains(n1);
        final var n2priority = PRIORITY_EXPORT_CLASSES.contains(n2);
        if (n1priority == n2priority) {
          return n1.compareTo(n2);
        } else {
          return n1priority ? -1 : 1;
        }
      }));

      for (var cls : classes) {
        // CHECK TO FILTER CLASS
        jsonGenerator.writeStartObject();
        manifestClasses++;

        jsonGenerator.writeStringField("name", cls.getName());

        jsonGenerator.writeArrayFieldStart("collection-ids");
        for (var collectionId : cls.getCollectionIds()) {
          jsonGenerator.writeNumber(collectionId);
        }
        jsonGenerator.writeEndArray();

        if (cls.isStrictMode()) {
          jsonGenerator.writeBooleanField("strictMode", cls.isStrictMode());
        }
        if (!cls.getSuperClasses().isEmpty()) {
          jsonGenerator.writeArrayFieldStart("super-classes");
          for (var superClass : cls.getSuperClasses()) {
            jsonGenerator.writeString(superClass.getName());
          }
          jsonGenerator.writeEndArray();
        }
        if (cls.isAbstract()) {
          jsonGenerator.writeBooleanField("abstract", cls.isAbstract());
        }

        if (!cls.getProperties().isEmpty()) {
          jsonGenerator.writeArrayFieldStart("properties");

          final List<SchemaProperty> properties = new ArrayList<>(cls.getDeclaredProperties());
          properties.sort(Comparator.comparing(SchemaProperty::getName));

          for (var p : properties) {
            jsonGenerator.writeStartObject();
            jsonGenerator.writeStringField("name", p.getName());
            jsonGenerator.writeStringField("type", p.getType().toString());
            if (p.isMandatory()) {
              jsonGenerator.writeBooleanField("mandatory", p.isMandatory());

            }
            if (p.isReadonly()) {
              jsonGenerator.writeBooleanField("readonly", p.isReadonly());
            }
            if (p.isNotNull()) {
              jsonGenerator.writeBooleanField("not-null", p.isNotNull());
            }
            if (p.getLinkedClass() != null) {
              jsonGenerator.writeStringField("linked-class",
                  p.getLinkedClass().getName());
            }
            if (p.getLinkedType() != null) {
              jsonGenerator.writeStringField("linked-type", p.getLinkedType().toString());
            }
            if (p.getMin() != null) {
              jsonGenerator.writeStringField("min", p.getMin());
            }
            if (p.getMax() != null) {
              jsonGenerator.writeStringField("max", p.getMax());
            }
            if (p.getCollate() != null) {
              jsonGenerator.writeStringField("collate", p.getCollate().getName());
            }
            if (p.getDefaultValue() != null) {
              jsonGenerator.writeStringField("default-value", p.getDefaultValue());
            }
            if (p.getRegexp() != null) {
              jsonGenerator.writeStringField("regexp", p.getRegexp());
            }
            final var customKeys = p.getCustomKeys();
            final Map<String, String> custom = new HashMap<>();
            for (var key : customKeys) {
              custom.put(key, p.getCustom(key));
            }

            if (!custom.isEmpty()) {
              jsonGenerator.writeObjectFieldStart("customFields");
              for (var entry : custom.entrySet()) {
                jsonGenerator.writeStringField(entry.getKey(), entry.getValue());
              }
              jsonGenerator.writeEndObject();
            }
            jsonGenerator.writeEndObject();
          }
          jsonGenerator.writeEndArray();
        }
        final var customKeys = cls.getCustomKeys();
        final Map<String, String> custom = new HashMap<>();
        for (var key : customKeys) {
          custom.put(key, cls.getCustom(key));
        }

        if (!custom.isEmpty()) {
          jsonGenerator.writeObjectFieldStart("customFields");
          for (var entry : custom.entrySet()) {
            jsonGenerator.writeStringField(entry.getKey(), entry.getValue());
          }
          jsonGenerator.writeEndObject();
        }
        jsonGenerator.writeEndObject();
      }
      jsonGenerator.writeEndArray();
    }

    jsonGenerator.writeEndObject();
    listener.onMessage("OK (" + schema.getClasses().size() + " classes)");
  }

  private boolean exportRecord(
      long recordTot, long recordNum, RecordAbstract rec, Set<RID> brokenRids)
      throws IOException {
    if (rec == null) {
      return false;
    }
    // Whole-or-discarded rendering (design M2.a-3): the record renders into a per-record
    // bounded buffer with its OWN generator — never directly into the shared dump stream — so
    // a mid-render failure can never leave partial JSON in the dump. Beyond the configured
    // threshold the buffer spills to a transient file (deleted on every path by the
    // try-with-resources), so memory stays bounded and an oversized-but-healthy record is
    // exported, not shed.
    try (var buffer = new SpillableRecordBuffer(recordSpillThreshold, spillDirectory)) {
      try {
        var recordGenerator =
            jsonFactory.createGenerator(new OutputStreamWriter(buffer, StandardCharsets.UTF_8));
        // The generator's close must NOT cascade into the buffer: the buffer outlives the
        // rendering (its content is copied out below) and its own close deletes the spill
        // file — the pinned lifecycle. FLUSH_PASSED_TO_STREAM (default on) still flushes the
        // writer on close, so the buffer holds the complete rendering.
        recordGenerator.configure(JsonGenerator.Feature.AUTO_CLOSE_TARGET, false);
        try (recordGenerator) {
          renderRecord(rec, recordGenerator);
        }
      } catch (final Exception t) {
        final RID rid = rec.getIdentity().copy();
        if (!bestEffort) {
          // Fail-fast default (design M2.a-2/FM-M2): a record that cannot be rendered aborts
          // the export; skip-and-continue is the explicit -bestEffort=true opt-out only.
          throw new DatabaseExportException(
              "Error on exporting record "
                  + rid
                  + " of database '"
                  + session.getDatabaseName()
                  + "'; the record seems corrupted (re-run with -bestEffort=true to skip broken"
                  + " records and register them in brokenRids)",
              t);
        }
        brokenRids.add(rid);
        logBrokenRecord(rec, t);
        return false;
      }

      // Copy-out is whole-or-fatal (design M2.a-3): any I/O failure here aborts the export
      // (the enclosing catch deletes the temp file and promotes nothing), so a partially
      // copied record can never reach a promoted dump.
      copyRawValue(buffer);
      recordExported++;
      recordNum++;

      if (recordTot > 10 && (recordNum + 1) % (recordTot / 10) == 0) {
        listener.onMessage(".");
      }

      return true;
    }
  }

  /**
   * The record iteration over one collection, extracted as a protected seam so tests can
   * inject deterministic mid-scan iterator failures into the collection-scan arm (the FM-M1
   * remedy's rethrow path); production behavior is exactly the session browse.
   */
  protected Iterator<RecordAbstract> browseCollectionRecords(String collectionName) {
    return session.browseCollection(collectionName);
  }

  /**
   * Renders one record's JSON into the given per-record generator. Extracted as a protected
   * seam so tests can inject deterministic render failures for the whole-or-discarded
   * contract; production behavior is exactly the single serializer call.
   */
  protected void renderRecord(RecordAbstract rec, JsonGenerator recordGenerator)
      throws IOException {
    final var format = "rid,version,class,type,keepTypes,internal,markEmbeddedEntities";
    JSONSerializerJackson.INSTANCE.recordToJson(session, rec, recordGenerator, format);
  }

  /**
   * Streams the buffered record JSON into the dump as ONE raw array value: the empty
   * {@code writeRawValue} participates in the array context (writing the separating comma),
   * then the buffered content is copied through in bounded chunks — never materialized as one
   * string, so a spilled record stays memory-bounded on the way out too.
   */
  private void copyRawValue(SpillableRecordBuffer buffer) throws IOException {
    jsonGenerator.writeRawValue("");
    try (var reader =
        new InputStreamReader(buffer.openContent(), StandardCharsets.UTF_8)) {
      final var chunk = new char[8192];
      int read;
      while ((read = reader.read(chunk)) > 0) {
        jsonGenerator.writeRaw(chunk, 0, read);
      }
    }
  }

  /** Diagnostic log for a best-effort-skipped record; the raw-content dump is itself guarded. */
  private void logBrokenRecord(RecordAbstract rec, Exception failure) {
    byte[] raw = null;
    try {
      raw = rec.toStream();
    } catch (Exception ignored) {
      // the raw content is diagnostics only; a record too corrupt to serialize is logged bare
    }
    LogManager.instance()
        .error(
            this,
            """

                Error on exporting record %s; skipped in best-effort mode. Size: %s bytes, raw\
                 content (as string):
                ==========
                %s
                ==========""",
            failure,
            rec.getIdentity(),
            raw == null ? "?" : String.valueOf(raw.length),
            raw == null ? "<unavailable>" : new String(raw));
  }
}
