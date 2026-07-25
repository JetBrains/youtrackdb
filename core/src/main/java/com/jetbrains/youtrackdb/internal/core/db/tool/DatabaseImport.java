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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.common.io.IOUtils;
import com.jetbrains.youtrackdb.internal.common.listener.ProgressListener;
import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.common.util.ArrayUtils;
import com.jetbrains.youtrackdb.internal.common.util.RawPair;
import com.jetbrains.youtrackdb.internal.core.command.CommandOutputListener;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded.STATUS;
import com.jetbrains.youtrackdb.internal.core.db.EntityFieldWalker;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Edge;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Entity;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Vertex;
import com.jetbrains.youtrackdb.internal.core.db.tool.importer.ConverterData;
import com.jetbrains.youtrackdb.internal.core.db.tool.importer.LinksRewriter;
import com.jetbrains.youtrackdb.internal.core.exception.BaseException;
import com.jetbrains.youtrackdb.internal.core.exception.DatabaseException;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import com.jetbrains.youtrackdb.internal.core.index.IndexDefinition;
import com.jetbrains.youtrackdb.internal.core.index.IndexManagerEmbedded;
import com.jetbrains.youtrackdb.internal.core.index.SimpleKeyIndexDefinition;
import com.jetbrains.youtrackdb.internal.core.metadata.MetadataDefault;
import com.jetbrains.youtrackdb.internal.core.metadata.function.Function;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaClassImpl;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaClassInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaShared;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass.INDEX_TYPE;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaProperty;
import com.jetbrains.youtrackdb.internal.core.metadata.security.Identity;
import com.jetbrains.youtrackdb.internal.core.metadata.security.Role;
import com.jetbrains.youtrackdb.internal.core.metadata.security.Rule.ResourceGeneric;
import com.jetbrains.youtrackdb.internal.core.metadata.security.SecurityPolicy;
import com.jetbrains.youtrackdb.internal.core.metadata.security.SecurityUserImpl;
import com.jetbrains.youtrackdb.internal.core.record.RecordAbstract;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.JSONReader;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.StringSerializerHelper;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.string.JSONSerializerJackson;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.string.JSONSerializerJackson.RecordMetadata;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.text.ParseException;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import javax.annotation.Nullable;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Import data from a file into a database.
 */
public class DatabaseImport extends DatabaseImpExpAbstract<DatabaseSessionEmbedded> {

  private static final Logger logger = LoggerFactory.getLogger(DatabaseImport.class);
  public static final String EXPORT_IMPORT_CLASS_NAME = "___exportImportRIDMap";
  public static final String EXPORT_IMPORT_INDEX_NAME = EXPORT_IMPORT_CLASS_NAME + "Index";

  public static final int IMPORT_RECORD_DUMP_LAP_EVERY_MS = 5000;

  private final Map<SchemaProperty, String> linkedClasses = new HashMap<>();
  private final Map<String, List<String>> superClasses = new HashMap<>();
  private JSONReader jsonReader;
  private JSONSerializerJackson jsonSerializer = JSONSerializerJackson.IMPORT_INSTANCE;
  private int exporterVersion = -1;

  private boolean deleteRIDMapping = true;

  private boolean migrateLinks = true;
  private boolean rebuildIndexes = true;

  private final Set<String> indexesToRebuild = new HashSet<>();

  private static final int COLLECTION_NOT_FOUND_VALUE = -2;
  private final Int2IntOpenHashMap collectionToCollectionMapping = new Int2IntOpenHashMap();

  private int maxRidbagStringSizeBeforeLazyImport = 100_000_000;

  // --- Track 8 Step 5: v15 structural strictness state (design M2.b) ---

  /** Whether the source arrived GZIP-framed (vs the legacy plain-JSON fallback). */
  private boolean gzipFramed;

  /** The validated single-member decoder when gzip-framed; drives the whole-stream checks. */
  private ValidatedGZIPInputStream validatedGzipStream;

  /** The dump file's physical size; {@code -1} for the InputStream constructor (WI10a). */
  private long physicalSize = -1;

  /** The dump's info-section best-effort marker (written by a {@code -bestEffort} export). */
  private boolean bestEffortDump;

  /** The explicit operator acknowledgment for best-effort dumps ({@code -acceptBestEffortDump}). */
  private boolean acceptBestEffortDump;

  /** Whether the deferred import preamble (§A2/CS38) has run. */
  private boolean preambleExecuted;

  /** Captured by the deferred preamble; consumed by {@code importRecords}. */
  private Schema beforeImportSchemaSnapshot;

  /** Section-tag occurrence counts for the v15 presence/duplicate checks (WI10c). */
  private final Map<String, Integer> sectionOccurrences = new HashMap<>();

  // Importer-tallied consumption counts (CN51): what THIS import actually parsed from the
  // dump, cross-checked against the manifest's exporter-tallied declarations — never derived
  // from target-database queries.
  private long parsedSchemaClassCount;
  private long parsedIndexCount;
  private long parsedRecordCount;
  private long parsedBrokenRidCount;

  // The manifest's declared totals; -1 = not declared.
  private long manifestClasses = -1;
  private long manifestIndexes = -1;
  private long manifestRecords = -1;
  private long manifestBrokenRids = -1;

  // --- Track 8 Step 6: the ruled Q-M2/R4 info-field validation matrix state ---

  /**
   * Q-M2(2): the oldest dump schema version this release can import; the upper bound is
   * {@link SchemaShared#CURRENT_VERSION_NUMBER}. Exact equality today — expressed as a range
   * so the next format bump is a one-constant change.
   */
  public static final int MIN_IMPORTABLE_SCHEMA_VERSION = 6;

  /** The dump's declared schema-version; meaningful only when {@link #schemaVersionDeclared}. */
  private long declaredSchemaVersion;

  private boolean schemaVersionDeclared;

  /** Non-null when the dump's schema-version failed to parse as a number (raw token kept). */
  private String malformedSchemaVersionRaw;

  /** Unknown info fields, tolerated and logged at pre-flight (Q-M2(4)). */
  private final List<String> unknownInfoFields = new ArrayList<>();

  /** Known-optional-field type violations, collected at parse, judged at pre-flight (WI12b). */
  private final List<String> infoFieldTypeViolations = new ArrayList<>();

  public DatabaseImport(
      final DatabaseSessionEmbedded database,
      final String fileName,
      final CommandOutputListener outputListener)
      throws IOException {
    super(database, fileName, outputListener);
    validateSessionImpl();
    collectionToCollectionMapping.defaultReturnValue(COLLECTION_NOT_FOUND_VALUE);
    // TODO: check unclosed stream?
    final var fileInputStream = new FileInputStream(this.fileName);
    // CN62 (Track 8 cumulative review): the physical size is captured from the OPENED
    // descriptor (fstat-after-open) — a path-based stat BEFORE the open races a concurrent
    // re-export's atomic promote (old inode's size, new inode's bytes), and the step-(3)
    // size arithmetic would falsely condemn a healthy import. Size and stream now provably
    // refer to one inode.
    this.physicalSize = fileInputStream.getChannel().size();
    final var bufferedInputStream = new BufferedInputStream(fileInputStream);
    createJsonReaderDefaultListenerAndDeclareIntent(outputListener,
        detectFraming(bufferedInputStream));
  }

  public DatabaseImport(
      final DatabaseSessionEmbedded database,
      final InputStream inputStream,
      final CommandOutputListener outputListener) throws IOException {
    super(database, "streaming", outputListener);
    validateSessionImpl();
    collectionToCollectionMapping.defaultReturnValue(COLLECTION_NOT_FOUND_VALUE);
    // WI10a: the InputStream constructor detects the framing exactly like the file constructor
    // (a gzip-framed stream gets the validated single-member decoder and the CS43 steps (1)+(2)
    // at the end of a v15 import); the physical-size arithmetic (step (3)) requires a sizable
    // source and never applies here. A plain stream is the CALLER's framing choice on this
    // programmatic path — the non-gzip rejection (Q-M3) guards the file-based migration path.
    createJsonReaderDefaultListenerAndDeclareIntent(outputListener,
        detectFraming(new BufferedInputStream(inputStream)));
  }

  /**
   * Opens the source as GZIP when it is gzip-framed — through the validated single-member
   * decoder ({@link ValidatedGZIPInputStream}), which a v15 import later drives through the
   * CS43 whole-stream checks — falling back to the plain stream otherwise. Which arm was taken
   * is recorded: a v15 dump that arrived via the plain fallback is rejected at pre-flight
   * (Q-M3/M2.b-1); a declared-legacy dump keeps the lenient fallback behavior.
   */
  private InputStream detectFraming(BufferedInputStream bufferedInputStream) throws IOException {
    bufferedInputStream.mark(1024);
    try {
      validatedGzipStream = new ValidatedGZIPInputStream(bufferedInputStream, 16384); // 16KB
      gzipFramed = true;
      return validatedGzipStream;
    } catch (final Exception ignore) {
      bufferedInputStream.reset();
      gzipFramed = false;
      return bufferedInputStream;
    }
  }

  private void validateSessionImpl() {
    if (!(session instanceof DatabaseSessionEmbedded)) {
      throw new DatabaseImportException(
          "Session is not an embedded session, cannot import database with this utility.");
    }
  }

  private void createJsonReaderDefaultListenerAndDeclareIntent(
      final CommandOutputListener outputListener,
      final InputStream inputStream) {
    if (outputListener == null) {
      listener = text -> {
      };
    }
    jsonReader = new JSONReader(new InputStreamReader(inputStream));
  }

  @Override
  public DatabaseImport setOptions(final String options) {
    super.setOptions(options);
    return this;
  }

  @Override
  public void run() {
    importDatabase();
  }

  @Override
  protected void parseSetting(final String option, final List<String> items) {
    if (option.equalsIgnoreCase("-deleteRIDMapping")) {
      deleteRIDMapping = Boolean.parseBoolean(items.getFirst());
    } else if (option.equalsIgnoreCase("-migrateLinks")) {
      migrateLinks = Boolean.parseBoolean(items.getFirst());
    } else if (option.equalsIgnoreCase("-rebuildIndexes")) {
      rebuildIndexes = Boolean.parseBoolean(items.getFirst());
    } else if (option.equalsIgnoreCase("-acceptBestEffortDump")) {
      // M2.b-4: the operator's explicit acknowledgment that a best-effort dump may be
      // missing records; without it a best-effort-marked dump is rejected at pre-flight.
      acceptBestEffortDump = Boolean.parseBoolean(items.getFirst());
    } else if (option.equalsIgnoreCase("-backwardCompatMode")) {
      jsonSerializer = Boolean.parseBoolean(items.getFirst())
          ? JSONSerializerJackson.IMPORT_BACKWARDS_COMPAT_INSTANCE
          : JSONSerializerJackson.IMPORT_INSTANCE;
    } else {
      super.parseSetting(option, items);
    }
  }

  public DatabaseImport importDatabase() {
    session.checkSecurity(ResourceGeneric.DATABASE, Role.PERMISSION_ALL);
    final var preValidation = session.isValidationEnabled();
    try {
      listener.onMessage(
          "\nStarted import of database '" + session.getURL() + "' from " + fileName + "...");
      final var time = System.nanoTime();

      jsonReader.readNext(JSONReader.BEGIN_OBJECT);
      session.setValidationEnabled(false);
      session.setUser(null);

      // The import preamble (the target mutations that used to run HERE, before anything of
      // the dump was read) is DEFERRED until the info section has parsed and the pre-flight
      // checks passed (§A2/CS38, block boundary pinned by WI11) — see
      // runDeferredImportPreamble. The `< 15` path defers identically: nothing between the old
      // block and the first section consumed the dropped classes, so this is
      // behavior-preserving for declared-legacy dumps.

      var collectionsImported = false;
      while (jsonReader.hasNext() && jsonReader.lastChar() != '}') {
        final var tag = jsonReader.readString(JSONReader.FIELD_ASSIGNMENT);

        // SR2 (trigger precise per gate-1 CS46): the rejection fires at the first non-`info`
        // section tag — or at end of stream, checked after the loop — if no parseable
        // exporter-version has been declared by then. Every legitimate exporter writes `info`
        // first, so this rejects only corrupt, truncated or hand-damaged dumps, BEFORE any
        // deferred preamble mutation can be unlocked (closing the loop with CS38).
        if (exporterVersion == -1 && !"info".equals(tag)) {
          throw new DatabaseImportException(
              "Import rejected: the dump reached its '" + tag + "' section without declaring"
                  + " an exporter version — refusing unverifiable input (a legitimate dump"
                  + " always begins with its info section)");
        }
        sectionOccurrences.merge(tag, 1, Integer::sum);

        switch (tag) {
          case "info" -> {
            importInfo();
            // The deferred preamble is unlocked only by a PARSEABLE declared version (SR2):
            // an info section without one leaves the target untouched, and the next tag (or
            // end of stream) rejects the dump.
            if (exporterVersion != -1) {
              runPreFlightChecks();
              runDeferredImportPreamble();
            }
          }
          case "collections", "clusters" -> {
            // BG25/WI10c: the legacy "clusters" alias is not part of the v15 dump shape (the
            // v15 exporter writes only "collections") — accepting it would let a spliced
            // alias-spelled section bypass the tag-keyed duplicate/presence tracking. The
            // declared-legacy path keeps the alias byte-for-byte.
            if (exporterVersion >= 15 && "clusters".equals(tag)) {
              throw new DatabaseImportException(
                  "Invalid format. Found unsupported tag 'clusters' (a v15 dump names its"
                      + " collections section 'collections')");
            }
            importCollections();
            collectionsImported = true;
          }
          case "schema" -> importSchema(collectionsImported);
          case "records" -> importRecords(beforeImportSchemaSnapshot);
          case "indexes" -> importIndexes();
          case "brokenRids" -> processBrokenRids();
          // The v15 exporter's trailing manifest section, version-gated per WI6: only a dump
          // DECLARING exporter version >= 15 may carry it — a declared-legacy dump with a
          // manifest tag keeps today's unsupported-tag rejection byte-for-byte.
          case "manifest" -> {
            if (exporterVersion >= 15) {
              importManifest();
            } else {
              throw new DatabaseImportException(
                  "Invalid format. Found unsupported tag 'manifest'");
            }
          }
          default -> throw new DatabaseImportException(
              "Invalid format. Found unsupported tag '" + tag + "'");
        }
      }
      // SR2's end-of-stream arm: a dump that ended without ever declaring a version.
      if (exporterVersion == -1) {
        throw new DatabaseImportException(
            "Import rejected: the dump ended without declaring an exporter version — refusing"
                + " unverifiable input");
      }
      // The v15 structural strictness (M2.b-2/3, SR1's condemn-target doctrine): these checks
      // run after the section loop — inherently post-mutation — and a rejection here CONDEMNS
      // the target (the operator procedure mandates import-into-a-fresh-database and
      // discard-on-any-failure).
      if (exporterVersion >= 15) {
        verifyV15StructuralStrictness();
      }
      if (rebuildIndexes) {
        rebuildIndexes();
      }

      // This is needed to insure functions loaded into an open
      // in memory database are available after the import.
      // see issue #5245
      session.getMetadata().reload();

      session.getStorage().synch();
      // status concept seems deprecated, but status `OPEN` is checked elsewhere
      session.setStatus(STATUS.OPEN);

      if (deleteRIDMapping) {
        removeExportImportRIDsMap();
      }
      listener.onMessage(
          "\n\nDatabase import completed in " + ((System.nanoTime() - time) / 1000000) + " ms");
    } catch (final Exception e) {
      final var writer = new StringWriter();
      writer.append("Error on database import happened just before line ")
          .append(String.valueOf(jsonReader.getLineNumber())).append(", column ")
          .append(String.valueOf(jsonReader.getColumnNumber())).append("\n");
      final var printWriter = new PrintWriter(writer);
      e.printStackTrace(printWriter);
      printWriter.flush();

      listener.onMessage(writer.toString());

      try {
        writer.close();
      } catch (final IOException e1) {
        throw new DatabaseExportException(
            "Error on importing database '" + session.getDatabaseName() + "' from file: "
                + fileName,
            e1);
      }
      throw new DatabaseExportException(
          "Error on importing database '" + session.getDatabaseName() + "' from file: " + fileName,
          e);
    } finally {
      session.setValidationEnabled(preValidation);
      close();
    }
    return this;
  }

  private void processBrokenRids() throws IOException, ParseException {
    final Set<RID> brokenRids = new HashSet<>();
    processBrokenRids(brokenRids);
    jsonReader.readNext(JSONReader.COMMA_SEPARATOR);
  }

  /**
   * The pre-flight checks a dump must pass BEFORE any target mutation is unlocked — they run
   * right after the info section parses, ahead of the deferred preamble, so a rejection here
   * leaves the target byte-for-byte untouched (CS38; SR1's genuinely pre-mutation set). Step 6
   * adds the full Q-M2 info-field validation matrix at this seam.
   */
  private void runPreFlightChecks() {
    // Q-M2(1) version dispatch: the >= 16 reject-with-redirect fires AHEAD of every v15 arm
    // — a dump produced by newer binaries never reaches the strictness checks, making the
    // >= 15-keyed arms below and in the section loop effectively == 15 (the end-state the
    // Step 5 as-built note anticipated). The message names both versions.
    if (exporterVersion >= 16) {
      throw new DatabaseImportException(
          "Import rejected: the dump declares exporter version " + exporterVersion
              + " — it was produced by newer binaries; this release imports dumps up to"
              + " exporter version " + DatabaseExport.EXPORTER_VERSION + ". Import the dump"
              + " with a release supporting exporter version " + exporterVersion);
    }
    if (exporterVersion >= 15) {
      // Q-M2(2): schema-version is MANDATORY in a v15 dump and must sit inside the
      // importable range; missing, malformed, or out-of-range — reject naming declared vs
      // supported. The declared-legacy path never reaches these arms (FM-M12).
      final var supportedRange = MIN_IMPORTABLE_SCHEMA_VERSION
          + ".." + SchemaShared.CURRENT_VERSION_NUMBER;
      if (malformedSchemaVersionRaw != null) {
        throw new DatabaseImportException(
            "Import rejected: the dump declares an unparseable schema version '"
                + malformedSchemaVersionRaw + "' — this release imports schema versions "
                + supportedRange);
      }
      if (!schemaVersionDeclared) {
        throw new DatabaseImportException(
            "Import rejected: the dump does not declare a schema version — a v15 dump always"
                + " carries one; this release imports schema versions " + supportedRange);
      }
      if (declaredSchemaVersion < MIN_IMPORTABLE_SCHEMA_VERSION
          || declaredSchemaVersion > SchemaShared.CURRENT_VERSION_NUMBER) {
        throw new DatabaseImportException(
            "Import rejected: the dump declares schema version " + declaredSchemaVersion
                + " but this release imports schema versions " + supportedRange
                + (declaredSchemaVersion > SchemaShared.CURRENT_VERSION_NUMBER
                    ? " — import the dump with a release supporting schema version "
                        + declaredSchemaVersion
                    : " — export the database again with a release producing a supported schema"
                        + " version"));
      }
      // Q-M2(3)/WI12b: known optional info fields present with a wrong type.
      if (!infoFieldTypeViolations.isEmpty()) {
        throw new DatabaseImportException(
            "Import rejected: " + String.join("; ", infoFieldTypeViolations)
                + " — the dump's info section is damaged");
      }
      // Q-M2(4): unknown extra fields are tolerated, logged — the exporter version is the
      // compatibility contract, not field enumeration.
      for (final var unknownField : unknownInfoFields) {
        listener.onMessage(
            "\nWARNING: unknown info field '" + unknownField + "' ignored");
      }
    }
    // Q-M3/M2.b-1: a v15 dump is gzip-framed — on the file-based migration path, having
    // arrived via the plain-JSON fallback is a hard failure with no override (a
    // manually-gunzipped dump carries no trailer or size to verify). The InputStream
    // constructor's caller owns the framing choice on that programmatic path (WI10a), so the
    // rejection is keyed on the sized (file) source. (The >= 16 redirect above fires first,
    // so this arm only ever sees == 15 — the "v15" wording is exact; CQ24 resolved by
    // ordering.)
    if (exporterVersion >= 15 && physicalSize >= 0 && !gzipFramed) {
      throw new DatabaseImportException(
          "Import rejected: the dump declares exporter version " + exporterVersion
              + " but is not GZIP-framed — a v15 dump is always gzip-framed and a manually"
              + " re-compressed copy cannot be verified; use the original export file");
    }
    // M2.b-4: the best-effort acknowledgment gate — a dump exported with -bestEffort=true is
    // refused unless the operator explicitly acknowledges its potential incompleteness.
    // Ruled at Step 6 (SR3, resolving review findings BG23/CQ26/CS66): the gate is
    // deliberately MARKER-KEYED, not version-gated — no honest legacy exporter writes the
    // marker, so the only affected input is a hand-edited dump, and rejecting one absent an
    // explicit acknowledgment is intended fail-closed behavior.
    if (bestEffortDump && !acceptBestEffortDump) {
      throw new DatabaseImportException(
          "Import rejected: the dump was exported in best-effort mode and may be missing"
              + " records. Re-run the import with -acceptBestEffortDump=true to acknowledge"
              + " the possible incompleteness and proceed");
    }
  }

  /**
   * The import preamble deferred by §A2 (CS38; block boundary pinned by WI11): every target
   * mutation that used to run before anything of the dump was read — the default-class drop,
   * the index-manager reload with the auto-index rebuild snapshot, and the order-coupled
   * before-import schema snapshot, which must move WITH the drop it is taken after (the
   * record import classifies leftover system records against it). Runs exactly once, only
   * after the info section parsed and the pre-flight checks passed.
   */
  private void runDeferredImportPreamble() {
    if (preambleExecuted) {
      return;
    }
    preambleExecuted = true;

    removeDefaultNonSecurityClasses();
    session.getSharedContext().getIndexManager().reload(session);

    for (final var index : session.getSharedContext().getIndexManager().getIndexes()) {
      if (index.isAutomatic()) {
        indexesToRebuild.add(index.getName());
      }
    }

    beforeImportSchemaSnapshot = session.getMetadata().getImmutableSchemaSnapshot();
  }

  /**
   * Parses the v15 trailing manifest section's declared totals for the post-loop cross-check
   * against the importer's own consumption tallies (M2.b-3, CN51). Unknown manifest fields
   * are skipped — the exporter version is the compatibility contract, not the field set.
   */
  private void importManifest() throws IOException, ParseException {
    listener.onMessage("\nReading the manifest...");
    jsonReader.readNext(JSONReader.BEGIN_OBJECT);
    // BG30/CS78: EOF-bounded like the importInfo field loop — a dump truncated inside the
    // manifest object spun forever on stale reader state; it now rejects loudly below.
    while (jsonReader.hasNext() && jsonReader.lastChar() != '}') {
      final var fieldName = jsonReader.readString(JSONReader.FIELD_ASSIGNMENT);
      switch (fieldName) {
        // BG20: the exporter tallies these totals as longs — an int-range parse would
        // falsely reject an honest dump with more than 2^31-1 entries.
        case "classes" -> manifestClasses = jsonReader.readLong(JSONReader.NEXT_IN_OBJECT);
        case "indexes" -> manifestIndexes = jsonReader.readLong(JSONReader.NEXT_IN_OBJECT);
        case "records" -> manifestRecords = jsonReader.readLong(JSONReader.NEXT_IN_OBJECT);
        case "brokenRids" ->
            manifestBrokenRids = jsonReader.readLong(JSONReader.NEXT_IN_OBJECT);
        default -> jsonReader.readNext(JSONReader.NEXT_IN_OBJECT);
      }
    }
    if (jsonReader.lastChar() != '}') {
      throw truncatedDump("its manifest section");
    }
    jsonReader.readNext(JSONReader.NEXT_IN_OBJECT);
    listener.onMessage("OK");
  }

  /**
   * CS80 (track-cumulative review): the loud rejection every reader loop's EOF bound throws.
   * The reader returns STALE state forever once the stream is exhausted mid-structure, so an
   * unbounded `lastChar()`-keyed loop spins (or replays stale tokens) instead of failing —
   * every section loop is bounded with {@code hasNext()} and converts an EOF-mid-structure
   * exit into this rejection. Ungated by version: a mid-structure-truncated dump of ANY
   * version could never import (it hung or desynced), so acceptance is unchanged. The checks
   * key on the missing terminator, never on {@code hasNext()} itself, so an honestly-closed
   * structure can never false-trip. The DEDICATED type (gate RG7) lets tolerance catches
   * rethrow truncation — see {@link TruncatedDumpImportException}.
   */
  private TruncatedDumpImportException truncatedDump(String where) {
    return new TruncatedDumpImportException(
        "Import rejected: the dump ends inside " + where + " — the dump is truncated");
  }

  /**
   * The v15 structural whole-stream strictness (M2.b-2/3). These checks run after the section
   * loop — inherently post-mutation — so per SR1's condemn-target doctrine a rejection here
   * condemns the partially imported target (the operator procedure mandates
   * import-into-a-fresh-database and discard-on-any-failure). Checks: section presence
   * including duplicates (WI10c), manifest totals vs the importer's own consumption tallies
   * (CN51), non-empty brokenRids without the best-effort marker (WI10b — an honest
   * default-mode v15 export aborts instead of producing broken RIDs, so the combination
   * proves tampering or corruption), and the CS43 gzip full-consumption sequence.
   */
  private void verifyV15StructuralStrictness() throws IOException {
    for (final var required : List.of(
        "info", "collections", "schema", "records", "indexes", "brokenRids", "manifest")) {
      final var occurrences = sectionOccurrences.getOrDefault(required, 0);
      if (occurrences == 0) {
        throw new DatabaseImportException(
            "Import rejected: the v15 dump is missing its '" + required + "' section — the"
                + " dump is incomplete; the partially imported target database is condemned");
      }
      if (occurrences > 1) {
        throw new DatabaseImportException(
            "Import rejected: the v15 dump carries its '" + required + "' section "
                + occurrences + " times — the dump is malformed; the partially imported target"
                + " database is condemned");
      }
    }

    verifyManifestCount("classes", manifestClasses, parsedSchemaClassCount);
    verifyManifestCount("indexes", manifestIndexes, parsedIndexCount);
    verifyManifestCount("records", manifestRecords, parsedRecordCount);
    verifyManifestCount("brokenRids", manifestBrokenRids, parsedBrokenRidCount);

    if (parsedBrokenRidCount > 0 && !bestEffortDump) {
      throw new DatabaseImportException(
          "Import rejected: the v15 dump carries " + parsedBrokenRidCount + " broken RID(s)"
              + " without the best-effort marker — an honest fail-fast export cannot produce"
              + " this combination; the dump is inconsistent and the partially imported target"
              + " database is condemned");
    }

    if (validatedGzipStream != null) {
      // CS43 step (1): drain the DECOMPRESSED stream to end of stream. The JSON reader
      // stopped at the dump root's closing brace, and the single member's trailer (CRC32 +
      // ISIZE) is only read and verified by driving the decoder to its end.
      final var buffer = new byte[8192];
      //noinspection StatementWithEmptyBody
      while (validatedGzipStream.read(buffer) != -1) {
        // draining
      }
      // CS43 step (2): the single member must be fully consumed with no in-window residue.
      validatedGzipStream.verifyFullyConsumed();
      // CS43 step (3): the physical-size arithmetic — only the sized file source can assert
      // that nothing trails the member on disk (WI10a: never applies to the InputStream
      // constructor).
      if (physicalSize >= 0) {
        validatedGzipStream.verifyPhysicalSize(physicalSize);
      }
    }
  }

  /** One CN51 manifest-vs-tally cross-check; a mismatch condemns the target (SR1). */
  private void verifyManifestCount(String entry, long declared, long consumed) {
    if (declared != consumed) {
      throw new DatabaseImportException(
          "Import rejected: the v15 dump's manifest declares " + declared + " " + entry
              + " but the import consumed " + consumed + " — the dump is truncated or"
              + " tampered; the partially imported target database is condemned");
    }
  }

  // just read collection so import process can continue
  private void processBrokenRids(final Set<RID> brokenRids) throws IOException, ParseException {
    if (exporterVersion >= 12) {
      listener.onMessage(
          "Reading of set of RIDs of records which were detected as broken during database"
              + " export\n");
      jsonReader.readNext(JSONReader.BEGIN_COLLECTION);

      do {
        jsonReader.readNext(JSONReader.NEXT_IN_ARRAY);

        var value = jsonReader.getValue();
        if (value != null) {
          // The v15 exporter writes the rids as QUOTED JSON strings and the reader keeps the
          // quotes in the raw token — strip them (an unquoted legacy token passes through).
          value = value.trim();
          if (value.length() >= 2 && value.charAt(0) == '"'
              && value.charAt(value.length() - 1) == '"') {
            value = value.substring(1, value.length() - 1).trim();
          }
        }
        if (value != null && !value.isEmpty()) {
          // CN51 consumption tally: only real rid tokens count — an EMPTY brokenRids array
          // still parses as one empty token (which fromString maps to a placeholder rid).
          parsedBrokenRidCount++;
        }
        final var recordId = RecordIdInternal.fromString(value, false);
        brokenRids.add(recordId);

        // CS80: EOF-bounded — see truncatedDump
      } while (jsonReader.lastChar() != ']' && jsonReader.hasNext());
      if (jsonReader.lastChar() != ']') {
        throw truncatedDump("its brokenRids section");
      }
    }
    if (migrateLinks) {
      if (exporterVersion >= 12) {
        listener.onMessage(
            brokenRids.size()
                + " were detected as broken during database export, links on those records will be"
                + " removed from result database");
      }
      migrateLinksInImportedDocuments(brokenRids);
    }
  }

  public void rebuildIndexes() {
    session.getSharedContext().getIndexManager().reload(session);

    var indexManager = session.getSharedContext().getIndexManager();

    listener.onMessage("\nRebuild of stale indexes...");
    for (var indexName : indexesToRebuild) {

      if (indexManager.getIndex(indexName) == null) {
        listener.onMessage(
            "\nIndex " + indexName + " is skipped because it is absent in imported DB.");
        continue;
      }

      listener.onMessage("\nStart rebuild index " + indexName);
      session.execute("rebuild index " + indexName).close();
      listener.onMessage("\nRebuild  of index " + indexName + " is completed.");
    }
    listener.onMessage("\nStale indexes were rebuilt...");
  }

  public void removeExportImportRIDsMap() {
    listener.onMessage("\nDeleting RID Mapping table...");

    Schema schema = session.getMetadata().getSchema();
    if (schema.getClass(EXPORT_IMPORT_CLASS_NAME) != null) {
      schema.dropClass(EXPORT_IMPORT_CLASS_NAME);
    }

    listener.onMessage("OK\n");
  }

  public void close() {
    // Releases the validated decoder's native inflater; the plain fallback stream keeps the
    // historical lifecycle (owned by the reader until the process lets it go).
    if (validatedGzipStream != null) {
      try {
        validatedGzipStream.close();
      } catch (final IOException ignore) {
        // closing a fully drained (or condemned) source — nothing to recover
      }
      validatedGzipStream = null;
    }
  }

  @SuppressWarnings("unused")
  public boolean isMigrateLinks() {
    return migrateLinks;
  }

  @SuppressWarnings("unused")
  public void setMigrateLinks(boolean migrateLinks) {
    this.migrateLinks = migrateLinks;
  }

  @SuppressWarnings("unused")
  public boolean isRebuildIndexes() {
    return rebuildIndexes;
  }

  @SuppressWarnings("unused")
  public void setRebuildIndexes(boolean rebuildIndexes) {
    this.rebuildIndexes = rebuildIndexes;
  }

  public void setDeleteRIDMapping(boolean deleteRIDMapping) {
    this.deleteRIDMapping = deleteRIDMapping;
  }

  public void setOption(final String option, String value) {
    parseSetting("-" + option, Collections.singletonList(value));
  }

  protected void removeDefaultCollections() {
    listener.onMessage(
        "\nWARN: Exported database does not support manual index separation."
            + " Manual index collection will be dropped.");
    final Schema schema = session.getMetadata().getSchema();
    if (schema.existsClass(SecurityUserImpl.CLASS_NAME)) {
      schema.dropClass(SecurityUserImpl.CLASS_NAME);
    }
    if (schema.existsClass(Role.CLASS_NAME)) {
      schema.dropClass(Role.CLASS_NAME);
    }
    if (schema.existsClass(Function.CLASS_NAME)) {
      schema.dropClass(Function.CLASS_NAME);
    }
    if (schema.existsClass("ORIDs")) {
      schema.dropClass("ORIDs");
    }

    session.getSharedContext().getSecurity().create(session);
  }

  private void importInfo() throws IOException, ParseException {
    listener.onMessage("\nImporting database info...");

    jsonReader.readNext(JSONReader.BEGIN_OBJECT);
    // BG30/CS78: the field loop is EOF-bounded — on a dump truncated mid-info the reader
    // returns stale state forever (a silent spin, with the unknown-field list growing toward
    // OOM); hasNext() turns false exactly when the stale returns begin, and the post-loop
    // check below converts the truncation into a loud, still-pre-mutation rejection. Ungated
    // by version deliberately: a mid-info-truncated dump of ANY version could never import
    // (it hung), so acceptance is unchanged — the hang becomes a rejection.
    while (jsonReader.hasNext() && jsonReader.lastChar() != '}') {
      final var fieldName = jsonReader.readString(JSONReader.FIELD_ASSIGNMENT);
      // R4 parse-level strictness (FM-M10): a DANGLING field — name written, value missing,
      // the mid-write crash shape — makes the until-the-colon field-name read swallow the
      // object close and the following section into the "name". A legal info field name
      // never contains structural JSON characters, and no honest dump of ANY version
      // produces one that does (the shape can never parse into a clean import — the reader
      // desyncs), so rejecting it here only converts guaranteed parse chaos into a clean,
      // still-pre-mutation rejection.
      if (fieldName.isEmpty() || fieldName.chars().anyMatch(c -> "\"{}[],:".indexOf(c) >= 0)) {
        throw new DatabaseImportException(
            "Import rejected: the dump's info section carries a dangling or malformed field"
                + " (parsed as '" + fieldName + "') — the dump is damaged");
      }
      switch (fieldName) {
        case "exporter-version" -> {
          final var raw = readInfoFieldRawValue(fieldName);
          final int declaredVersion;
          try {
            declaredVersion = Integer.parseInt(raw);
          } catch (final NumberFormatException e) {
            // WI12a: an unparseable exporter-version is rejected fail-closed — the same
            // outcome as an undeclared one (SR2): without a version there is no dispatch.
            throw new DatabaseImportException(
                "Import rejected: the dump declares an unparseable exporter version '" + raw
                    + "' — refusing unverifiable input (same outcome as an undeclared"
                    + " version)");
          }
          // CS63: the FIRST declared exporter version is latched — the strictness gate reads
          // this field only after the section loop, so a trailing re-declaration (duplicate
          // info section or repeated field) with a DIFFERING value could otherwise disarm the
          // whole version-keyed matrix after the strict-armed parse already ran. Reject the
          // re-declaration the moment it parses (fail-closed); a same-value duplicate info
          // section is still caught by the WI10c duplicate-section check under v15, and stays
          // tolerated on the declared-legacy path as before.
          if (exporterVersion != -1 && declaredVersion != exporterVersion) {
            throw new DatabaseImportException(
                "Import rejected: the dump re-declares its exporter version (" + exporterVersion
                    + " -> " + declaredVersion + ") — refusing tampered input");
          }
          exporterVersion = declaredVersion;
          if (exporterVersion < 14) {
            jsonSerializer = JSONSerializerJackson.IMPORT_BACKWARDS_COMPAT_INSTANCE;
          }
        }
        case "schema-version" -> {
          // Q-M2(2): mandatory in v15 dumps; captured here, judged at pre-flight (the
          // version gate keeps the declared-legacy path untouched — FM-M12).
          final var raw = readInfoFieldRawValue(fieldName);
          try {
            declaredSchemaVersion = Long.parseLong(raw);
            schemaVersionDeclared = true;
          } catch (final NumberFormatException e) {
            malformedSchemaVersionRaw = raw;
          }
        }
        case "best-effort" -> {
          // The Step 4 exporter's best-effort marker — feeds the M2.b-4 acknowledgment gate
          // and the WI10b brokenRids consistency check.
          final var raw = readInfoFieldRawValue(fieldName);
          checkKnownInfoFieldIsBoolean(fieldName, raw);
          // BG29/CS76: the MARKER parses from the quote-stripped token (the parent's
          // readBoolean parity) — a hand-edited QUOTED "true" on a declared-legacy dump must
          // still arm the SR3 marker-keyed gate (fail-closed); under v15 the type check
          // above still rejects the quoted form as a WI12b violation.
          bestEffortDump = Boolean.parseBoolean(stripSurroundingQuotes(raw));
        }
        // Q-M2(3)/WI12b: the known OPTIONAL info fields the v15 exporter writes are
        // type-checked if present but not required; violations are collected here (the raw
        // token keeps its quotes, so strings are distinguishable) and judged at pre-flight,
        // v15-only.
        case "name", "engine-version", "engine-build", "schemaRecordId", "indexMgrRecordId" ->
            checkKnownInfoFieldIsString(fieldName, readInfoFieldRawValue(fieldName));
        case "storage-config-version" ->
            checkKnownInfoFieldIsNumber(fieldName, readInfoFieldRawValue(fieldName));
        default -> {
          // Q-M2(4): unknown extra fields are tolerated — the exporter version is the
          // compatibility contract, not field enumeration — and logged at pre-flight.
          unknownInfoFields.add(fieldName);
          readInfoFieldRawValue(fieldName);
        }
      }
    }
    // BG30/CS78's post-loop half: the loop exited on EOF, not on the object close.
    if (jsonReader.lastChar() != '}') {
      throw truncatedDump("its info section");
    }
    jsonReader.readNext(JSONReader.COMMA_SEPARATOR);

    listener.onMessage("OK");
  }

  /**
   * Reads an info field's raw value token (quotes preserved for strings), trimmed.
   *
   * <p>CS75 (recorded scalar-only rule): info-field values are SCALARS in every dump shape
   * any exporter has ever written. A '{'- or '['-led value desyncs the reader's
   * until-the-separator scan — a nested closing brace is indistinguishable from the info
   * object's own close, so the field loop would exit early, pre-flight would pass on a
   * truncated capture, and the import would mutate the target before failing (an
   * SR1-boundary violation). A structured value is therefore rejected HERE — pre-mutation,
   * loud, naming the field. This deliberately narrows Q-M2(4)'s unknown-field tolerance to
   * scalar VALUES (field NAMES stay unconstrained); the version number remains the
   * compatibility contract. Ungated by version: no honest dump of any version writes a
   * structured info value, and the mid-section shape could never import cleanly anyway.
   */
  private String readInfoFieldRawValue(String fieldName) throws IOException, ParseException {
    final var raw = jsonReader.readNext(JSONReader.NEXT_IN_OBJECT).getValue().trim();
    if (raw.startsWith("{") || raw.startsWith("[")) {
      throw new DatabaseImportException(
          "Import rejected: the dump's info field '" + fieldName + "' carries a structured"
              + " (non-scalar) value — no dump shape writes structured info fields; the dump"
              + " is damaged or hand-edited");
    }
    return raw;
  }

  /** Strips one pair of surrounding double quotes from a raw token, if present. */
  private static String stripSurroundingQuotes(String raw) {
    if (raw.length() >= 2 && raw.charAt(0) == '"' && raw.charAt(raw.length() - 1) == '"') {
      return raw.substring(1, raw.length() - 1).trim();
    }
    return raw;
  }

  private void checkKnownInfoFieldIsString(String fieldName, String raw) {
    if (raw.length() < 2 || raw.charAt(0) != '"' || raw.charAt(raw.length() - 1) != '"') {
      infoFieldTypeViolations.add(
          "info field '" + fieldName + "' must be a string but is '" + raw + "'");
    }
  }

  private void checkKnownInfoFieldIsNumber(String fieldName, String raw) {
    try {
      Long.parseLong(raw);
    } catch (final NumberFormatException e) {
      infoFieldTypeViolations.add(
          "info field '" + fieldName + "' must be a number but is '" + raw + "'");
    }
  }

  private void checkKnownInfoFieldIsBoolean(String fieldName, String raw) {
    if (!"true".equals(raw) && !"false".equals(raw)) {
      infoFieldTypeViolations.add(
          "info field '" + fieldName + "' must be a boolean but is '" + raw + "'");
    }
  }

  private void removeDefaultNonSecurityClasses() {
    listener.onMessage(
        "\nNon merge mode (-merge=false): removing all default non security classes");

    final Schema schema = session.getMetadata().getSchema();
    final var classes = schema.getClasses();
    final var role = schema.getClass(Role.CLASS_NAME);
    final var user = schema.getClass(SecurityUserImpl.CLASS_NAME);
    final var identity = schema.getClass(Identity.CLASS_NAME);
    // final SchemaClass oSecurityPolicy = schema.getClass(SecurityPolicy.class.getSimpleName());
    final Map<String, SchemaClass> classesToDrop = new HashMap<>();
    final Set<String> indexNames = new HashSet<>();
    for (final var dbClass : classes) {
      final var className = dbClass.getName();
      if (!dbClass.isSuperClassOf(role)
          && !dbClass.isSuperClassOf(user)
          && !dbClass.isSuperClassOf(
              identity) /*&& !dbClass.isSuperClassOf(oSecurityPolicy)*/) {
        classesToDrop.put(className, dbClass);
        indexNames.addAll(((SchemaClassInternal) dbClass).getIndexes());
      }
    }

    final var indexManager = session.getSharedContext()
        .getIndexManager();
    for (final var indexName : indexNames) {
      indexManager.dropIndex(session, indexName);
    }

    var removedClasses = 0;
    while (!classesToDrop.isEmpty()) {
      final AbstractList<String> classesReadyToDrop = new ArrayList<>();
      for (final var className : classesToDrop.keySet()) {
        var isSuperClass = false;
        for (var dbClass : classesToDrop.values()) {
          final var parentClasses = dbClass.getSuperClasses();
          if (parentClasses != null) {
            for (var parentClass : parentClasses) {
              if (className.equals(parentClass.getName())) {
                isSuperClass = true;
                break;
              }
            }
          }
        }
        if (!isSuperClass) {
          classesReadyToDrop.add(className);
        }
      }
      for (final var className : classesReadyToDrop) {
        schema.dropClass(className);
        classesToDrop.remove(className);
        removedClasses++;
        listener.onMessage("\n- Class " + className + " was removed.");
      }
    }
    listener.onMessage("\nRemoved " + removedClasses + " classes.");
  }

  private void setLinkedClasses() {
    for (final var linkedClass : linkedClasses.entrySet()) {
      linkedClass
          .getKey()
          .setLinkedClass(session.getMetadata().getSchema().getClass(
              linkedClass.getValue()));
    }
  }

  private void importSchema(boolean collectionsImported) throws IOException, ParseException {
    if (!collectionsImported) {
      removeDefaultCollections();
    }

    listener.onMessage("\nImporting database schema...");

    jsonReader.readNext(JSONReader.BEGIN_OBJECT);
    @SuppressWarnings("unused")
    var schemaVersion =
        jsonReader
            .readNext(JSONReader.FIELD_ASSIGNMENT)
            .checkContent("\"version\"")
            .readNumber(JSONReader.ANY_NUMBER, true);
    jsonReader.readNext(JSONReader.COMMA_SEPARATOR);
    jsonReader.readNext(JSONReader.FIELD_ASSIGNMENT);
    // This can be removed after the M1 expires
    if (jsonReader.getValue().equals("\"globalProperties\"")) {
      jsonReader.readNext(JSONReader.BEGIN_COLLECTION);
      do {
        jsonReader.readNext(JSONReader.BEGIN_OBJECT);
        jsonReader.readNext(JSONReader.FIELD_ASSIGNMENT).checkContent("\"name\"");
        jsonReader.readString(JSONReader.NEXT_IN_OBJECT);
        jsonReader.readNext(JSONReader.FIELD_ASSIGNMENT).checkContent("\"global-id\"");
        jsonReader.readString(JSONReader.NEXT_IN_OBJECT);
        jsonReader.readNext(JSONReader.FIELD_ASSIGNMENT).checkContent("\"type\"");
        jsonReader.readString(JSONReader.NEXT_IN_OBJECT);
        jsonReader.readNext(JSONReader.NEXT_IN_ARRAY);
        // CS80: EOF-bounded — see truncatedDump
      } while (jsonReader.lastChar() == ',' && jsonReader.hasNext());
      if (jsonReader.lastChar() == ',') {
        throw truncatedDump("the schema's globalProperties list");
      }
      jsonReader.readNext(JSONReader.COMMA_SEPARATOR);
      jsonReader.readNext(JSONReader.FIELD_ASSIGNMENT);
    }

    if (jsonReader.getValue().equals("\"blob-collections\"") ||
        jsonReader.getValue().equals("\"blob-clusters\"")) {
      var blobCollectionIds = jsonReader.readString(JSONReader.END_COLLECTION, true).trim();
      blobCollectionIds = blobCollectionIds.substring(1, blobCollectionIds.length() - 1);

      if (!blobCollectionIds.isEmpty()) {
        // READ BLOB COLLECTION IDS. The ids live in the DUMP's id space: resolve them through
        // the collections-section mapping (dump id -> target id) built by importCollections
        // (§A3/WI1) — resolving them raw in the TARGET id space misclassifies under the R3
        // layout change (a legacy dump's high blob id lands on a target class collection,
        // registering it as a blob collection — FM-M16).
        for (var i : StringSerializerHelper.split(
            blobCollectionIds, StringSerializerHelper.RECORD_SEPARATOR)) {
          var dumpCollectionId = Integer.parseInt(i.trim());
          var targetCollectionId = collectionToCollectionMapping.get(dumpCollectionId);
          if (targetCollectionId == COLLECTION_NOT_FOUND_VALUE) {
            // No collections-section entry maps this id — nothing safe to register (never
            // fall back to the raw id: that is exactly the FM-M16 misclassification).
            listener.onMessage(
                "\nWARNING: blob collection with dump id " + dumpCollectionId
                    + " has no matching entry in the dump's collections section;"
                    + " its registration is skipped");
            continue;
          }
          if (!ArrayUtils.contains(session.getBlobCollectionIds(), targetCollectionId)) {
            var name = session.getCollectionNameById(targetCollectionId);
            session.addBlobCollection(name);
          }
        }
      }

      jsonReader.readNext(JSONReader.COMMA_SEPARATOR);
      jsonReader.readNext(JSONReader.FIELD_ASSIGNMENT);
    }

    jsonReader.checkContent("\"classes\"").readNext(JSONReader.BEGIN_COLLECTION);

    long classImported = 0;

    try {

      // creating V and E classes ahead of time, because they have to exist
      // before we start creating other vertex or edge classes.
      // we tried to fix this by making the export tool write these classes first,
      // but if the dump was created by an older version of the export tool,
      // it won't work.
      final var schema = session.getMetadata().getSchema();
      final var vertexClass = schema.existsClass(Vertex.CLASS_NAME)
          ? schema.getClass(Vertex.CLASS_NAME) : schema.createClass(Vertex.CLASS_NAME);
      final var edgeClass = schema.existsClass(Edge.CLASS_NAME) ? schema.getClass(Edge.CLASS_NAME)
          : schema.createClass(Edge.CLASS_NAME);
      do {
        jsonReader.readNext(JSONReader.BEGIN_OBJECT);
        var className =
            jsonReader
                .readNext(JSONReader.FIELD_ASSIGNMENT)
                .checkContent("\"name\"")
                .readString(JSONReader.COMMA_SEPARATOR);
        // CN51 consumption tally: every class OBJECT parsed from the dump counts.
        parsedSchemaClassCount++;

        final var collectionIdsTag =
            exporterVersion >= 14 ? "\"collection-ids\"" : "\"cluster-ids\"";
        final var collectionIdsStr = jsonReader
            .readNext(JSONReader.FIELD_ASSIGNMENT)
            .checkContent(collectionIdsTag)
            .readString(JSONReader.END_COLLECTION, true)
            .trim();

        final var originalCollectionIds =
            StringSerializerHelper.splitIntArray(
                collectionIdsStr.substring(1, collectionIdsStr.length() - 1));

        // it's important to use previously created collections here because later the indexes
        // are created on collections (not on classes).
        final var newCollectionIds =
            Arrays.stream(originalCollectionIds)
                .map(collectionToCollectionMapping::get)
                .filter(cid -> cid != COLLECTION_NOT_FOUND_VALUE)
                .toArray();

        jsonReader.readNext(JSONReader.NEXT_IN_OBJECT);
        if (className.contains(".")) {
          // MIGRATE OLD NAME WITH . TO _
          final var newClassName = className.replace('.', '_');
          listener.onMessage(
              "\nWARNING: class '" + className + "' has been renamed in '" + newClassName + "'\n");

          className = newClassName;
        }

        Boolean strictMode = null;
        Boolean isAbstract = null;
        var isVertex = false;
        var isEdge = false;
        Map<String, String> customFields = null;
        List<Map<String, Object>> propertiesRaw = null;

        String value;
        // CS80: EOF-bounded — see truncatedDump
        while (jsonReader.lastChar() == ',' && jsonReader.hasNext()) {
          jsonReader.readNext(JSONReader.FIELD_ASSIGNMENT);
          value = jsonReader.getValue();

          switch (value) {
            case "\"strictMode\"" -> strictMode = jsonReader.readBoolean(JSONReader.NEXT_IN_OBJECT);
            case "\"abstract\"" -> isAbstract = jsonReader.readBoolean(JSONReader.NEXT_IN_OBJECT);
            case "\"super-class\"" -> {
              // @compatibility <2.1 SINGLE CLASS ONLY
              final var classSuper = jsonReader.readString(JSONReader.NEXT_IN_OBJECT);

              if (SchemaClass.VERTEX_CLASS_NAME.equals(classSuper)) {
                isVertex = true;
              } else if (SchemaClass.EDGE_CLASS_NAME.equals(classSuper)) {
                isEdge = true;
              } else {
                final List<String> superClassNames = new ArrayList<>();
                superClassNames.add(classSuper);
                superClasses.put(className, superClassNames);
              }
            }
            case "\"super-classes\"" -> {
              // MULTIPLE CLASSES
              jsonReader.readNext(JSONReader.BEGIN_COLLECTION);

              final List<String> superClassNames = new ArrayList<>();
              // CS80: EOF-bounded — see truncatedDump
              while (jsonReader.lastChar() != ']' && jsonReader.hasNext()) {
                jsonReader.readNext(JSONReader.NEXT_IN_ARRAY);

                final var clsName =
                    IOUtils.getStringContent(StringUtils.trim(jsonReader.getValue()));

                if (SchemaClass.VERTEX_CLASS_NAME.equals(clsName)) {
                  isVertex = true;
                } else if (SchemaClass.EDGE_CLASS_NAME.equals(clsName)) {
                  isEdge = true;
                } else {
                  superClassNames.add(clsName);
                }
              }

              if (jsonReader.lastChar() != ']') {
                throw truncatedDump("a class's super-classes list");
              }
              if (!superClassNames.isEmpty()) {
                superClasses.put(className, superClassNames);
              }
              jsonReader.readNext(JSONReader.NEXT_IN_OBJECT);
            }
            case "\"properties\"" -> {
              propertiesRaw = new ArrayList<>();
              // GET PROPERTIES
              jsonReader.readNext(JSONReader.BEGIN_COLLECTION);

              // CS80: EOF-bounded — see truncatedDump
              while (jsonReader.lastChar() != ']' && jsonReader.hasNext()) {
                final var pRaw = jsonReader.readNext(JSONReader.NEXT_IN_ARRAY).getValue();
                if (StringUtils.isNotBlank(pRaw)) {
                  final var pMap = jsonSerializer.mapFromJson(pRaw);
                  propertiesRaw.add(pMap);
                }
              }
              if (jsonReader.lastChar() != ']') {
                throw truncatedDump("a class's properties list");
              }
              jsonReader.readNext(JSONReader.NEXT_IN_OBJECT);
            }
            case "\"cluster-selection\"" ->
                // ignoring old property
                jsonReader.readNext(JSONReader.NEXT_IN_OBJECT);
            case "\"customFields\"" -> {
              customFields = importCustomFields();
            }
          }
        }
        if (jsonReader.lastChar() == ',') {
          throw truncatedDump("a class definition");
        }

        if (isVertex && isEdge) {
          throw new DatabaseImportException(
              "Class '" + className + "' cannot be both vertex and edge.");
        }

        var cls = schema.getClass(className);

        if (cls != null) {
          if (isVertex && !cls.isVertexType()) {
            throw new DatabaseImportException("Class '" + className
                + "' exists but is not a vertex class. It can't be made a vertex class.");
          } else if (isEdge && !cls.isEdgeType()) {
            throw new DatabaseImportException("Class '" + className
                + "' exists but is not an edge class. It can't be made an edge class.");
          }
        } else {
          if (collectionsImported) {
            // other superclasses will be added later.
            final var superClassesToAdd =
                isVertex ? new SchemaClass[] {vertexClass}
                    : isEdge ? new SchemaClass[] {edgeClass} : new SchemaClass[] {};
            cls = schema.createClass(className, newCollectionIds, superClassesToAdd);
          } else if (className.equals("ORestricted")) {
            cls = schema.createAbstractClass(className);
          } else {
            cls = schema.createClass(className);
          }
        }

        if (strictMode != null) {
          cls.setStrictMode(strictMode);
        }
        if (isAbstract != null) {
          cls.setAbstract(isAbstract);
        }

        if (propertiesRaw != null) {
          for (var propRaw : propertiesRaw) {
            importProperty((SchemaClassInternal) cls, propRaw);
          }
        }

        if (customFields != null) {
          for (var cf : customFields.entrySet()) {
            cls.setCustom(cf.getKey(), cf.getValue());
          }
        }

        classImported++;

        jsonReader.readNext(JSONReader.NEXT_IN_ARRAY);
        // CS80: EOF-bounded — see truncatedDump
      } while (jsonReader.lastChar() == ',' && jsonReader.hasNext());
      if (jsonReader.lastChar() == ',') {
        throw truncatedDump("the schema's classes list");
      }

      this.rebuildCompleteClassInheritance();
      this.setLinkedClasses();

      if (exporterVersion < 11) {
        var role = session.getMetadata().getSchema().getClass(Role.CLASS_NAME);
        role.dropProperty("rules");
      }

      listener.onMessage("OK (" + classImported + " classes)");
      jsonReader.readNext(JSONReader.END_OBJECT);
      jsonReader.readNext(JSONReader.COMMA_SEPARATOR);
    } catch (final TruncatedDumpImportException truncation) {
      // RG7: a truncation rejection stays LOUD on every path and version — the schema-family
      // EOF bounds throw inside this legacy-tolerance swallow, and the legacy (< 15) path has
      // no post-loop structural check to catch the damage later, so swallowing here turned a
      // truncated legacy dump into an exit-0 import with an ERROR log line. No honest dump of
      // any version is truncated (the dangling-name-guard precedent), so rethrowing costs no
      // honest acceptance.
      throw truncation;
    } catch (final Exception e) {
      LogManager.instance().error(this, "Error on importing schema", e);
      listener.onMessage("ERROR (" + classImported + " entries): " + e);
    }
  }

  private void rebuildCompleteClassInheritance() {
    for (final var entry : superClasses.entrySet()) {
      final var cls = session.getMetadata().getSchema().getClass(entry.getKey());

      for (final var superClassName : entry.getValue()) {
        final var superClass = session.getMetadata().getSchema().getClass(superClassName);

        if (!cls.getSuperClasses().contains(superClass)) {
          cls.addSuperClass(superClass);
        }
      }
    }
  }

  private void importProperty(final SchemaClassInternal iClass, Map<String, ?> propRaw) {

    final var propName = (String) propRaw.get("name");

    final var type = PropertyTypeInternal.valueOf(((String) propRaw.get("type")));

    final var min = (String) propRaw.get("min");
    final var max = (String) propRaw.get("max");
    final var linkedClass = (String) propRaw.get("linked-class");
    final var linkedType =
        propRaw.containsKey("linked-type") ? PropertyTypeInternal.valueOf(
            (String) propRaw.get("linked-type")) : null;
    final var mandatory = propRaw.containsKey("mandatory") && (boolean) propRaw.get("mandatory");
    final var readonly = propRaw.containsKey("readonly") && (boolean) propRaw.get("readonly");
    final var notNull = propRaw.containsKey("not-null") && (boolean) propRaw.get("not-null");
    final var collate = (String) propRaw.get("collate");
    final var regexp = (String) propRaw.get("regexp");
    final var defaultValue = (String) propRaw.get("default-value");
    final var customFields = (Map<String, String>) propRaw.get("customFields");

    var prop = iClass.getProperty(propName);
    if (prop == null) {
      // CREATE IT
      prop = iClass.createProperty(propName, type,
          (PropertyTypeInternal) null,
          true);
    }
    prop.setMandatory(mandatory);
    prop.setReadonly(readonly);
    prop.setNotNull(notNull);

    if (min != null) {
      prop.setMin(min);
    }
    if (max != null) {
      prop.setMax(max);
    }
    if (linkedClass != null) {
      linkedClasses.put(prop, linkedClass);
    }
    if (linkedType != null) {
      prop.setLinkedType(linkedType.getPublicPropertyType());
    }
    if (collate != null) {
      prop.setCollate(collate);
    }
    if (regexp != null) {
      prop.setRegexp(regexp);
    }
    if (defaultValue != null) {
      prop.setDefaultValue(defaultValue);
    }
    if (customFields != null) {
      for (var entry : customFields.entrySet()) {
        prop.setCustom(entry.getKey(), entry.getValue());
      }
    }
  }

  private Map<String, String> importCustomFields() throws ParseException, IOException {
    Map<String, String> result = new HashMap<>();

    jsonReader.readNext(JSONReader.BEGIN_OBJECT);

    // CS80: EOF-bounded — see truncatedDump
    while (jsonReader.hasNext() && jsonReader.lastChar() != '}') {
      final var key = jsonReader.readString(JSONReader.FIELD_ASSIGNMENT);
      final var value = jsonReader.readString(JSONReader.NEXT_IN_OBJECT);

      result.put(key, value);
    }
    if (jsonReader.lastChar() != '}') {
      throw truncatedDump("a class's customFields object");
    }

    jsonReader.readString(JSONReader.NEXT_IN_OBJECT);

    return result;
  }

  private void importCollections() throws ParseException, IOException {
    listener.onMessage("\nImporting collections...");

    long total = 0;

    jsonReader.readNext(JSONReader.BEGIN_COLLECTION);

    if (exporterVersion <= 4) {
      removeDefaultCollections();
    }

    // CS80: EOF-bounded — see truncatedDump
    while (jsonReader.hasNext() && jsonReader.lastChar() != ']') {
      jsonReader.readNext(JSONReader.BEGIN_OBJECT);

      var name =
          jsonReader
              .readNext(JSONReader.FIELD_ASSIGNMENT)
              .checkContent("\"name\"")
              .readString(JSONReader.COMMA_SEPARATOR);

      if (name.isEmpty()) {
        name = null;
      }

      name = SchemaClassImpl.decodeClassName(name);

      if (exporterVersion <= 13 && name != null &&
          (name.equals("index") || name.equals("manindex") || name.equals("default"))) {
        listener.onMessage(
            "\nWARNING: collection '" + name + "' cannot be imported. It will be skipped.");
        jsonReader.readNext(JSONReader.NEXT_IN_ARRAY);
        continue;
      }

      int collectionIdFromJson;
      if (exporterVersion < 9) {
        collectionIdFromJson =
            jsonReader
                .readNext(JSONReader.FIELD_ASSIGNMENT)
                .checkContent("\"id\"")
                .readInteger(JSONReader.COMMA_SEPARATOR);
        jsonReader
            .readNext(JSONReader.FIELD_ASSIGNMENT)
            .checkContent("\"type\"")
            .readString(JSONReader.NEXT_IN_OBJECT);
      } else {
        collectionIdFromJson =
            jsonReader
                .readNext(JSONReader.FIELD_ASSIGNMENT)
                .checkContent("\"id\"")
                .readInteger(JSONReader.NEXT_IN_OBJECT);
      }

      if (jsonReader.lastChar() == ',') {
        jsonReader
            .readNext(JSONReader.FIELD_ASSIGNMENT)
            .checkContent("\"type\"")
            .readString(JSONReader.NEXT_IN_OBJECT);
      }

      if (jsonReader.lastChar() == ',') {
        jsonReader
            .readNext(JSONReader.FIELD_ASSIGNMENT)
            .checkContent("\"rid\"")
            .readString(JSONReader.NEXT_IN_OBJECT);
      }

      listener.onMessage(
          "\n- Creating collection " + (name != null ? "'" + name + "'" : "NULL") + "...");

      var createdCollectionId = name == null ? -1 : session.getCollectionIdByName(name);
      if (createdCollectionId == -1) {
        createdCollectionId = session.addCollection(name);
      }

      collectionToCollectionMapping.put(collectionIdFromJson, createdCollectionId);

      listener.onMessage(
          "OK, assigned id=" + createdCollectionId + ", was " + collectionIdFromJson);

      total++;

      jsonReader.readNext(JSONReader.NEXT_IN_ARRAY);
    }
    if (jsonReader.lastChar() != ']') {
      throw truncatedDump("its collections section");
    }
    jsonReader.readNext(JSONReader.COMMA_SEPARATOR);

    listener.onMessage("\nRebuilding indexes of truncated collections ...");

    for (final var indexName : indexesToRebuild) {
      session
          .getSharedContext()
          .getIndexManager()
          .getIndex(indexName)
          .rebuild(session,
              new ProgressListener() {
                private long last = 0;

                @Override
                public void onBegin(Object iTask, long iTotal, Object metadata) {
                  listener.onMessage(
                      "\n- Collection content was updated: rebuilding index '" + indexName
                          + "'...");
                }

                @Override
                public boolean onProgress(Object iTask, long iCounter, float iPercent) {
                  final var now = System.currentTimeMillis();
                  if (last == 0) {
                    last = now;
                  } else {
                    if (now - last > 1000) {
                      listener.onMessage(
                          String.format(
                              "\nIndex '%s' is rebuilding (%.2f/100)", indexName, iPercent));
                      last = now;
                    }
                  }
                  return true;
                }

                @Override
                public void onCompletition(DatabaseSessionEmbedded session, Object iTask,
                    boolean iSucceed) {
                  listener.onMessage(" Index " + indexName + " was successfully rebuilt.");
                }
              });
    }
    listener.onMessage("\nDone " + indexesToRebuild.size() + " indexes were rebuilt.");
    listener.onMessage("\nDone. Imported " + total + " collections");
  }

  /**
   * From `exporterVersion` >= `13`, `fromStream()` will be used. However, the import is still of
   * type String, and thus has to be converted to InputStream, which can only be avoided by
   * introducing a new interface method.
   */
  @Nullable private RID importRecord(
      HashSet<RID> recordsBeforeImport,
      Schema beforeImportSchemaSnapshot) throws Exception {

    session.disableLinkConsistencyCheck();
    session.begin();
    var ok = true;
    RID rid = null;
    RID originalRid = null;
    try {

      // commenting this out for now, because it can clear large LinkBags:
      // var recordJson = jsonReader.readRecordString(this.maxRidbagStringSizeBeforeLazyImport).getKey().trim();
      var recordJson = jsonReader.readNext(JSONReader.NEXT_IN_ARRAY).getValue();

      if (recordJson.isEmpty()) {
        return null;
      }
      // CN51 consumption tally: every record ENTRY parsed from the dump's array counts,
      // whatever the import later decides about it (the empty token above is the array end).
      parsedRecordCount++;
      RawPair<RecordAbstract, RecordMetadata> parsed;
      parsed = jsonSerializer.fromStringWithMetadata(session, recordJson, null, true);
      final var record = parsed.first();
      final var metadata = parsed.second();
      rid = record.getIdentity();
      originalRid = metadata.recordId();

      if (exporterVersion <= 13 &&
          record instanceof Entity entity &&
          Role.CLASS_NAME.equals(entity.getSchemaClassName())) {
        fixRoleRulesAndPolicies(entity.getEmbeddedMap("rules"));
        fixRoleRulesAndPolicies(entity.getLinkMap("policies"));
      }

      switch (metadata.entityType()) {
        case SCHEMA_MANAGER, INDEX_MANAGER -> {
          record.delete();
          rid = null;
        }
        default -> {
          final var collectionId = rid.getCollectionId();

          if (isSystemRecord(beforeImportSchemaSnapshot, collectionId)) {

            final var entity = (Entity) record;
            final var name = entity.getString("name");
            final var recordMap = entity.toMap(false);

            //or we will find ourselves.
            record.delete();
            var systemRecord =
                findRelatedSystemRecord(beforeImportSchemaSnapshot, collectionId, name);
            if (systemRecord != null) {
              if (!record.getClass().isAssignableFrom(systemRecord.getClass())) {
                throw new IllegalStateException(
                    "Imported record and record stored in database under id "
                        + rid
                        + " have different types. "
                        + "Stored record class is : "
                        + record.getClass()
                        + " and imported "
                        + systemRecord.getClass()
                        + " .");
              }

              systemRecord.updateFromMap(recordMap);
              recordsBeforeImport.remove(systemRecord.getIdentity());
              rid = systemRecord.getIdentity();
            } else {

              // parse it again, because we've removed it earlier
              rid = jsonSerializer
                  .fromStringWithMetadata(session, recordJson, null, true)
                  .first()
                  .getIdentity();
            }
          }
        }
      }

    } catch (Throwable t) {
      ok = false;

      LogManager.instance()
          .error(
              this,
              "Error importing record " + rid + "." +
                  "Source line " + jsonReader.getLineNumber() + ", "
                  + "column " + jsonReader.getColumnNumber(),
              t);

      if (!(t instanceof DatabaseException)) {
        throw t;
      }
    } finally {
      try {
        if (ok) {
          session.commit();
        } else {
          session.rollback();
        }
      } finally {
        session.enableLinkConsistencyCheck();
      }
    }

    if (rid != null && originalRid != null && !originalRid.equals(rid)) {
      assert originalRid.isPersistent();
      assert rid.isPersistent();
      final var originalRidFinal = originalRid;
      final var ridFinal = rid;

      session.executeInTx(tx -> {
        final var ridEntity = tx.newEntity(EXPORT_IMPORT_CLASS_NAME);
        ridEntity.setString("key", originalRidFinal.toString());
        ridEntity.setString("value", ridFinal.toString());
      });
    }

    return rid;
  }

  private static <E> void fixRoleRulesAndPolicies(Map<String, E> roleRules) {
    if (roleRules == null) {
      return;
    }

    // replacing "cluster" with "collection"
    for (var rule : new ArrayList<>(roleRules.entrySet())) {
      if (rule.getKey().startsWith("database.cluster")) {
        roleRules.remove(rule.getKey());
        roleRules.put("database.collection" + rule.getKey().substring(16), rule.getValue());
      } else if (rule.getKey().startsWith("database.systemclusters")) {
        roleRules.remove(rule.getKey());
        roleRules.put("database.systemcollections" + rule.getKey().substring(23),
            rule.getValue());
      }
    }
  }

  private @Nullable EntityImpl findRelatedSystemRecord(
      Schema beforeImportSchemaSnapshot, int collectionId, String name) {

    var cls = beforeImportSchemaSnapshot.getClassByCollectionId(collectionId);
    if (cls == null || (cls.getName().equals("V") || cls.getName().equals("E"))) {
      return null;
    }

    EntityImpl systemRecord = null;
    if (cls.getName().equals(SecurityUserImpl.CLASS_NAME)) {
      try (var resultSet =
          session.query(
              "select from " + SecurityUserImpl.CLASS_NAME + " where name = ?", name)) {
        if (resultSet.hasNext()) {
          systemRecord = (EntityImpl) resultSet.next().asEntity();
        }
      }
    } else if (cls.getName().equals(Role.CLASS_NAME)) {
      try (var resultSet =
          session.query(
              "select from " + Role.CLASS_NAME + " where name = ?", name)) {
        if (resultSet.hasNext()) {
          systemRecord = (EntityImpl) resultSet.next().asEntity();
        }
      }
    } else if (cls.getName().equals(SecurityPolicy.CLASS_NAME)) {
      try (var resultSet =
          session.query(
              "select from " + SecurityPolicy.CLASS_NAME + " where name = ?", name)) {
        if (resultSet.hasNext()) {
          systemRecord = (EntityImpl) resultSet.next().asEntity();
        }
      }
    } else {
      throw new IllegalStateException(
          "Class " + cls.getName() + " is not supported.");
    }
    return systemRecord;
  }

  private static boolean isSystemRecord(Schema beforeImportSchemaSnapshot, int collectionId) {
    var cls = beforeImportSchemaSnapshot.getClassByCollectionId(collectionId);
    if (cls != null) {
      if (cls.getName().equals(SecurityUserImpl.CLASS_NAME)) {
        return true;
      }
      if (cls.getName().equals(Role.CLASS_NAME)) {
        return true;
      }
      return cls.getName().equals(SecurityPolicy.class.getSimpleName());
    }

    return false;
  }

  private void importRecords(Schema beforeImportSchemaSnapshot) throws Exception {
    final Schema schema = session.getMetadata().getSchema();
    if (schema.getClass(EXPORT_IMPORT_CLASS_NAME) != null) {
      schema.dropClass(EXPORT_IMPORT_CLASS_NAME);
    }

    final var cls = schema.createClass(EXPORT_IMPORT_CLASS_NAME);
    cls.createProperty("key", PropertyType.STRING);
    cls.createProperty("value", PropertyType.STRING);
    cls.createIndex(EXPORT_IMPORT_CLASS_NAME + "_key_unique", INDEX_TYPE.UNIQUE, "key");
    final var begin = System.currentTimeMillis();

    long totalRecords = 0;
    try {
      long total = 0;
      jsonReader.readNext(JSONReader.BEGIN_COLLECTION);

      listener.onMessage("\n\nImporting records...");

      // the only security records are left at this moment so we need to overwrite them
      // and then remove left overs
      final var recordsBeforeImport = new HashSet<RID>();

      // just in case they are not in the internal collection (possibly redundant logic)
      final var schemaRecordId =
          RecordIdInternal.fromString(
              session.getStorage().getSchemaRecordId(),
              false);
      final var indexMgrRecordId =
          RecordIdInternal.fromString(
              session.getStorage().getIndexMgrRecordId(),
              false);

      session.executeInTx(transaction -> {
        for (final var collectionName : session.getCollectionNames()) {
          if (collectionName.equals(MetadataDefault.COLLECTION_INTERNAL_NAME)) {
            // don't want to mess with the internal collection
            continue;
          }
          var recordIterator = session.browseCollection(collectionName);
          while (recordIterator.hasNext()) {
            var identity = recordIterator.next().getIdentity();
            if (identity.equals(schemaRecordId)) {
              continue;
            } else if (identity.equals(indexMgrRecordId)) {
              continue;
            }

            recordsBeforeImport.add(identity);
          }
        }
      });

      RID rid;
      RID lastRid = new RecordId(RID.COLLECTION_ID_INVALID, RID.COLLECTION_POS_INVALID);

      long lastLapRecords = 0;
      var last = begin;
      Set<String> involvedCollections = new HashSet<>();

      if (logger.isDebugEnabled()) {
        LogManager.instance().debug(this, "Detected exporter version " + exporterVersion + ".",
            logger);
      }
      // CS80: EOF-bounded — see truncatedDump (a stale-token replay here would otherwise
      // re-import the same record forever; today's replays happen to die loudly on the
      // rid-map unique key, but that is protection by accident, not construction)
      while (jsonReader.hasNext() && jsonReader.lastChar() != ']') {
        rid = importRecord(recordsBeforeImport, beforeImportSchemaSnapshot);

        total++;
        if (rid != null) {
          ++lastLapRecords;
          ++totalRecords;

          if (rid.getCollectionId() != lastRid.getCollectionId() || involvedCollections.isEmpty()) {
            involvedCollections.add(session.getCollectionNameById(rid.getCollectionId()));
          }
          lastRid = rid;
        }

        final var now = System.currentTimeMillis();
        if (now - last > IMPORT_RECORD_DUMP_LAP_EVERY_MS) {
          final List<String> sortedCollections = new ArrayList<>(involvedCollections);
          Collections.sort(sortedCollections);

          listener.onMessage(
              String.format(
                  "\n"
                      + "- Imported %,d records into collections: %s. Total JSON records imported so for"
                      + " %,d .Total records imported so far: %,d (%,.2f/sec)",
                  lastLapRecords,
                  total,
                  sortedCollections.size(),
                  totalRecords,
                  (float) lastLapRecords * 1000 / (float) IMPORT_RECORD_DUMP_LAP_EVERY_MS));

          // RESET LAP COUNTERS
          last = now;
          lastLapRecords = 0;
          involvedCollections.clear();
        }
      }

      if (jsonReader.lastChar() != ']') {
        throw truncatedDump("its records section");
      }

      // remove all records which were absent in new database but
      // exist in old database
      session.executeInTx(transaction -> {
        for (final var leftOverRid : recordsBeforeImport) {
          var record = session.load(leftOverRid);
          session.delete(record);
        }
      });
    } catch (Exception e) {
      listener.onMessage("ERROR: " + e);
      throw BaseException.wrapException(new DatabaseImportException("Error on importing records"),
          e, session);
    }

    session.getMetadata().reload();

    final Set<RID> brokenRids = new HashSet<>();
    // This consumes the dump's brokenRids SECTION inline — it directly follows the records
    // section in every >= 12 dump, so its tag never passes through the section loop. Record
    // the occurrence here for the v15 presence/duplicate tracking (WI10c); a SECOND
    // brokenRids section spliced elsewhere in the dump goes through the loop and trips the
    // duplicate check.
    if (exporterVersion >= 12) {
      sectionOccurrences.merge("brokenRids", 1, Integer::sum);
    }
    processBrokenRids(brokenRids);

    listener.onMessage(
        String.format(
            "\n\nDone. Imported %,d records in %,.2f secs\n",
            totalRecords, ((float) (System.currentTimeMillis() - begin)) / 1000));

    jsonReader.readNext(JSONReader.COMMA_SEPARATOR);
  }

  private void importIndexes() throws IOException, ParseException {
    listener.onMessage("\n\nImporting indexes ...");

    var indexManager = session.getSharedContext().getIndexManager();
    indexManager.reload(session);

    jsonReader.readNext(JSONReader.BEGIN_COLLECTION);

    var numberOfCreatedIndexes = 0;
    // CS80: EOF-bounded — see truncatedDump
    while (jsonReader.hasNext() && jsonReader.lastChar() != ']') {
      jsonReader.readNext(JSONReader.NEXT_OBJ_IN_ARRAY);
      if (jsonReader.lastChar() == ']') {
        break;
      }
      // CN51 consumption tally: every index OBJECT parsed from the dump's array counts,
      // whatever the import later decides about it.
      parsedIndexCount++;

      String indexName = null;
      String indexType = null;
      String indexAlgorithm = null;
      Set<String> collectionsToIndex = new HashSet<>();
      IndexDefinition indexDefinition = null;
      Map<String, Object> metadata = null;
      var objectMapper = new ObjectMapper();
      var typeRef = new TypeReference<HashMap<String, Object>>() {
      };

      // CS80: EOF-bounded — see truncatedDump
      while (jsonReader.hasNext() && jsonReader.lastChar() != '}') {
        final var fieldName = jsonReader.readString(JSONReader.FIELD_ASSIGNMENT);
        switch (fieldName) {
          case "name" -> indexName = jsonReader.readString(JSONReader.NEXT_IN_OBJECT);
          case "type" -> indexType = jsonReader.readString(JSONReader.NEXT_IN_OBJECT);
          case "algorithm" -> indexAlgorithm = jsonReader.readString(JSONReader.NEXT_IN_OBJECT);
          case "collectionsToIndex", "clustersToIndex" ->
              collectionsToIndex = importCollectionsToIndex();
          case "definition" -> {
            indexDefinition = importIndexDefinition(objectMapper);
            jsonReader.readNext(JSONReader.NEXT_IN_OBJECT);
          }
          case "metadata" -> {
            final var jsonMetadata = jsonReader.readString(JSONReader.END_OBJECT, true);
            metadata = objectMapper.readValue(jsonMetadata, typeRef);
          }
          default -> {
            if (fieldName.equals("engineProperties")) {
              jsonReader.readString(JSONReader.END_OBJECT, true);
              jsonReader.readNext(JSONReader.NEXT_IN_OBJECT);
            }
          }
        }

      }
      if (jsonReader.lastChar() != '}') {
        throw truncatedDump("an index definition");
      }
      jsonReader.readNext(JSONReader.NEXT_IN_ARRAY);

      numberOfCreatedIndexes =
          dropAutoCreatedIndexesAndCountCreatedIndexes(
              indexManager,
              numberOfCreatedIndexes,
              indexName,
              indexType,
              indexAlgorithm,
              collectionsToIndex,
              indexDefinition,
              metadata);
    }
    if (jsonReader.lastChar() != ']') {
      throw truncatedDump("its indexes section");
    }
    listener.onMessage("\nDone. Created " + numberOfCreatedIndexes + " indexes.");
    jsonReader.readNext(JSONReader.NEXT_IN_OBJECT);
  }

  private int dropAutoCreatedIndexesAndCountCreatedIndexes(
      final IndexManagerEmbedded indexManager,
      int numberOfCreatedIndexes,
      final String indexName,
      String indexType,
      String indexAlgorithm,
      final Set<String> collectionsToIndex,
      IndexDefinition indexDefinition,
      final Map<String, Object> metadata) {
    if (indexName == null) {
      throw new IllegalArgumentException("Index name is missing");
    }

    if ("CELL_BTREE".equals(indexAlgorithm) || "HASH_INDEX".equals(indexAlgorithm)) {
      indexAlgorithm = "BTREE";
    }

    if ("UNIQUE_HASH_INDEX".equals(indexType)) {
      indexType = "UNIQUE";
    }

    // drop automatically created indexes
    if (!indexName.equals(EXPORT_IMPORT_INDEX_NAME)) {
      listener.onMessage("\n- Index '" + indexName + "'...");

      indexManager.dropIndex(session, indexName);
      indexesToRebuild.remove(indexName);
      var collectionIds = new IntArrayList();

      for (final var collectionName : collectionsToIndex) {
        var id = session.getCollectionIdByName(collectionName);
        if (id != -1) {
          collectionIds.add(id);
        } else {
          listener.onMessage(
              String.format(
                  "found not existent collection '%s' in index '%s' configuration, skipping",
                  collectionName, indexName));
        }
      }
      var collectionIdsToIndex = new int[collectionIds.size()];

      var i = 0;
      for (var n = 0; n < collectionIds.size(); n++) {
        var collectionId = collectionIds.getInt(n);
        collectionIdsToIndex[i] = collectionId;
        i++;
      }

      if (indexDefinition == null) {
        indexDefinition = new SimpleKeyIndexDefinition(PropertyTypeInternal.STRING);
      }

      var oldValue = GlobalConfiguration.INDEX_IGNORE_NULL_VALUES_DEFAULT.getValueAsBoolean();
      GlobalConfiguration.INDEX_IGNORE_NULL_VALUES_DEFAULT.setValue(
          indexDefinition.isNullValuesIgnored());
      indexManager.createIndex(
          session,
          indexName,
          indexType,
          indexDefinition,
          collectionIdsToIndex,
          null,
          metadata,
          indexAlgorithm);
      GlobalConfiguration.INDEX_IGNORE_NULL_VALUES_DEFAULT.setValue(oldValue);
      numberOfCreatedIndexes++;
      listener.onMessage("OK");
    }
    return numberOfCreatedIndexes;
  }

  private Set<String> importCollectionsToIndex() throws IOException, ParseException {
    final Set<String> collectionsToIndex = new HashSet<>();

    jsonReader.readNext(JSONReader.BEGIN_COLLECTION);

    // CS80: EOF-bounded — see truncatedDump
    while (jsonReader.hasNext() && jsonReader.lastChar() != ']') {
      final var collectionToIndex = jsonReader.readString(JSONReader.NEXT_IN_ARRAY);
      collectionsToIndex.add(collectionToIndex);
    }
    if (jsonReader.lastChar() != ']') {
      throw truncatedDump("an index's collectionsToIndex list");
    }

    jsonReader.readString(JSONReader.NEXT_IN_OBJECT);
    return collectionsToIndex;
  }

  private IndexDefinition importIndexDefinition(ObjectMapper mapper)
      throws IOException, ParseException {
    jsonReader.readString(JSONReader.BEGIN_OBJECT);
    jsonReader.readNext(JSONReader.FIELD_ASSIGNMENT);

    final var className = jsonReader.readString(JSONReader.NEXT_IN_OBJECT);

    jsonReader.readNext(JSONReader.FIELD_ASSIGNMENT);

    final var value = jsonReader.readString(JSONReader.END_OBJECT, true);
    final IndexDefinition indexDefinition;
    TypeReference<HashMap<String, Object>> typeRef = new TypeReference<>() {
    };
    var indexDefinitionMap = mapper.readValue(value, typeRef);
    try {
      final var indexDefClass = Class.forName(className);
      indexDefinition = (IndexDefinition) indexDefClass.getDeclaredConstructor().newInstance();
      indexDefinition.fromMap(indexDefinitionMap);
    } catch (final ClassNotFoundException | NoSuchMethodException | InvocationTargetException
        | InstantiationException | IllegalAccessException e) {
      throw new IOException("Error during deserialization of index definition", e);
    }

    jsonReader.readNext(JSONReader.NEXT_IN_OBJECT);

    return indexDefinition;
  }

  private void migrateLinksInImportedDocuments(Set<RID> brokenRids) {
    listener.onMessage(
        """


            Started migration of links (-migrateLinks=true). Links are going to be updated\
             according to new RIDs:""");

    final var ridMapCollections =
        IntStream
            .of(session.getSchema().getClass(EXPORT_IMPORT_CLASS_NAME).getCollectionIds())
            .boxed()
            .map(session::getCollectionNameById)
            .collect(Collectors.toSet());

    final var linksUpdated = new DatabaseRecordWalker(
        session, ridMapCollections)
        .onProgressPeriodically(
            IMPORT_RECORD_DUMP_LAP_EVERY_MS,
            (colName, colSize, seenInCol, colDone, seenTotal, speed) -> listener.onMessage(
                String.format(
                    "\n--- Migrated %,d of %,d records (%,.2f/sec) in collection '%s', done: %s",
                    seenInCol, colSize, speed, colName, colDone)))
        .walkEntitiesInTx(true, entity -> {
          rewriteLinksInDocument(session, entity, brokenRids);
          entity.clearSystemProps();
          return true;
        });
    listener.onMessage(String.format("\nTotal links updated: %,d", linksUpdated));

    final var linksRecovered = new DatabaseRecordWalker(
        session, ridMapCollections)
        .onProgressPeriodically(
            IMPORT_RECORD_DUMP_LAP_EVERY_MS,
            (colName, colSize, seenInCol, colDone, seenTotal, speed) -> listener.onMessage(
                String.format(
                    "\n--- Recovered links for %,d of %,d records (%,.2f/sec) in collection '%s', done: %s",
                    seenInCol, colSize, speed, colName, colDone)))
        .walkEntitiesInTx(entity -> {
          entity.markAllLinksAsChanged();
          return true;
        });
    listener.onMessage(String.format("\nTotal links recovered: %,d", linksRecovered));

    listener.onMessage(String.format("\nTotal links updated: %,d", linksUpdated));
  }

  protected static void rewriteLinksInDocument(
      DatabaseSessionEmbedded session, EntityImpl entity, Set<RID> brokenRids) {
    doRewriteLinksInDocument(session, entity, brokenRids);
  }

  protected static void doRewriteLinksInDocument(
      DatabaseSessionEmbedded session, EntityImpl entity, Set<RID> brokenRids) {
    final var rewriter = new LinksRewriter(new ConverterData(session, brokenRids));
    final var entityFieldWalker = new EntityFieldWalker();
    entityFieldWalker.walkDocument(session, entity, rewriter);
  }

  @SuppressWarnings("unused")
  public int getMaxRidbagStringSizeBeforeLazyImport() {
    return maxRidbagStringSizeBeforeLazyImport;
  }

  public void setMaxRidbagStringSizeBeforeLazyImport(int maxRidbagStringSizeBeforeLazyImport) {
    this.maxRidbagStringSizeBeforeLazyImport = maxRidbagStringSizeBeforeLazyImport;
  }
}
