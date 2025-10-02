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
import com.jetbrains.youtrackdb.api.record.RID;
import com.jetbrains.youtrackdb.internal.common.io.FileUtils;
import com.jetbrains.youtrackdb.internal.common.io.YTIOException;
import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.core.YouTrackDBConstants;
import com.jetbrains.youtrackdb.internal.core.command.CommandOutputListener;
import com.jetbrains.youtrackdb.internal.core.config.StorageConfiguration;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.metadata.SessionMetadata;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaProperty;
import com.jetbrains.youtrackdb.internal.core.record.RecordAbstract;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.string.JSONSerializerJackson;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.Deflater;
import java.util.zip.GZIPOutputStream;

/**
 * Export data from a database to a file.
 */
public class DatabaseExport extends DatabaseImpExpAbstract<DatabaseSessionEmbedded> {

  public static final int EXPORTER_VERSION = 14;

  protected JsonGenerator jsonGenerator;
  protected long recordExported;
  protected int compressionLevel = Deflater.BEST_SPEED;
  protected int compressionBuffer = 16384; // 16Kb

  private final String tempFileName;

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
    FileUtils.prepareForFileCreationOrReplacement(Paths.get(fileName), this, "exporting");

    this.tempFileName = fileName + ".tmp";
    FileUtils.prepareForFileCreationOrReplacement(Paths.get(tempFileName), this, "exporting");

    final var gzipOS =
        new GZIPOutputStream(new FileOutputStream(tempFileName), compressionBuffer) {
          {
            def.setLevel(compressionLevel);
          }
        };

    var factory = new JsonFactory();
    jsonGenerator = factory.createGenerator(new OutputStreamWriter(gzipOS));
    jsonGenerator.writeStartObject();
  }

  public DatabaseExport(
      final DatabaseSessionEmbedded iDatabase,
      final OutputStream iOutputStream,
      final CommandOutputListener iListener)
      throws IOException {
    super(iDatabase, "streaming", iListener);
    this.tempFileName = null;

    var factory = new JsonFactory();
    jsonGenerator = factory.createGenerator(new OutputStreamWriter(iOutputStream));
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
        } catch (IOException e) {
          throw new DatabaseExportException(
              "Error on exporting database '" + session.getDatabaseName() + "' to: " + fileName, e);
        }
      });

      listener.onMessage(
          "\n\nDatabase export completed in " + ((System.nanoTime() - time) / 1000000) + "ms");

      jsonGenerator.flush();
    } catch (Exception e) {
      LogManager.instance()
          .error(this, "Error on exporting database '%s' to: %s", e, session.getDatabaseName(),
              fileName);
      throw new DatabaseExportException(
          "Error on exporting database '" + session.getDatabaseName() + "' to: " + fileName, e);
    } finally {
      close();
    }
    return this;
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

      if (SessionMetadata.COLLECTION_INTERNAL_NAME.equals(collectionName)) {
        continue;
      }

      exportedCollections++;

      long collectionExportedRecordsTot = 0;
      if (collectionName != null) {
        // CHECK IF THE COLLECTION IS INCLUDED
        collectionExportedRecordsTot = session.countCollectionElements(collectionName);
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
          var it = session.browseCollection(collectionName);

          while (it.hasNext()) {
            rec = it.next();
            if (rec instanceof EntityImpl entity) {
              // CHECK IF THE CLASS OF THE DOCUMENT IS INCLUDED
              final var className =
                  entity.getSchemaClassName() != null
                      ? entity.getSchemaClassName().toUpperCase(Locale.ENGLISH)
                      : null;
            }

            if (exportRecord(
                collectionExportedRecordsTot, collectionExportedRecordsCurrent, rec, brokenRids)) {
              collectionExportedRecordsCurrent++;
            }
          }
        } catch (YTIOException e) {
          LogManager.instance()
              .error(
                  this,
                  "\nError on exporting record %s because of I/O problems",
                  e,
                  rec == null ? null : rec.getIdentity());
          // RE-THROW THE EXCEPTION UP
          throw e;
        } catch (Exception t) {
          if (rec != null) {
            final var buffer = rec.toStream();

            LogManager.instance()
                .error(
                    this,
                    """
                        
                        Error on exporting record %s. It seems corrupted; size: %d bytes, raw\
                         content (as string):
                        ==========
                        %s
                        ==========""",
                    t,
                    rec.getIdentity(),
                    buffer.length,
                    new String(buffer));
          }
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
  }

  public void close() {

    if (jsonGenerator == null) {
      return;
    }

    try {
      jsonGenerator.writeEndObject();
      jsonGenerator.close();
      jsonGenerator = null;
    } catch (IOException e) {
      LogManager.instance()
          .error(this, "Error on exporting database '%s' to: %s", e, session.getDatabaseName(),
              fileName);
      throw new DatabaseExportException(
          "Error on exporting database '" + session.getDatabaseName() + "' to: " + fileName, e);
    }

    if (tempFileName != null) // may be null if writing to an output stream w/o file
    {
      try {
        FileUtils.atomicMoveWithFallback(Paths.get(tempFileName), Paths.get(fileName), this);
      } catch (IOException e) {
        LogManager.instance()
            .error(
                this, "Error on exporting database '%s' to: %s", e, session.getDatabaseName(),
                fileName);
        throw new DatabaseExportException(
            "Error on exporting database '" + session.getDatabaseName() + "' to: " + fileName, e);
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
        session.getStorageInfo().getConfiguration().getSchemaRecordId());
    jsonGenerator.writeStringField("indexMgrRecordId",
        session.getStorageInfo().getConfiguration().getIndexMgrRecordId());
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
    var schema = session.getMetadata().getFastImmutableSchema();
    //noinspection deprecation
    jsonGenerator.writeNumberField("version", schema.getVersion());

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

        jsonGenerator.writeStringField("name", cls.getName());

        jsonGenerator.writeArrayFieldStart("collection-ids");
        for (var collectionId : cls.getCollectionIds()) {
          jsonGenerator.writeNumber(collectionId);
        }
        jsonGenerator.writeEndArray();

        if (cls.isStrictMode()) {
          jsonGenerator.writeBooleanField("strictMode", cls.isStrictMode());
        }
        if (!cls.getParents().isEmpty()) {
          jsonGenerator.writeArrayFieldStart("super-classes");
          for (var superClass : cls.getParents()) {
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
        final var customKeys = cls.getCustomPopertiesNames();
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
      long recordTot, long recordNum, RecordAbstract rec, Set<RID> brokenRids) {
    if (rec != null) {
      try {
        final var format = "rid,version,class,type,keepTypes,internal,markEmbeddedEntities";
        JSONSerializerJackson.INSTANCE.recordToJson(session, rec, jsonGenerator, format);

        recordExported++;
        recordNum++;

        if (recordTot > 10 && (recordNum + 1) % (recordTot / 10) == 0) {
          listener.onMessage(".");
        }

        return true;
      } catch (final Exception t) {
        final RID rid = rec.getIdentity().copy();

        if (rid != null) {
          brokenRids.add(rid);
        }

        final var buffer = rec.toStream();

        LogManager.instance()
            .error(
                this,
                """
                    
                    Error on exporting record %s. It seems corrupted; size: %d bytes, raw\
                     content (as string):
                    ==========
                    %s
                    ==========""",
                t,
                rec.getIdentity(),
                buffer.length,
                new String(buffer));
      }
    }

    return false;
  }
}
