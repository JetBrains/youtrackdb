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
package com.jetbrains.youtrack.db.internal.core.db.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jetbrains.youtrack.db.api.DatabaseSession.STATUS;
import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.api.exception.ConfigurationException;
import com.jetbrains.youtrack.db.api.exception.DatabaseException;
import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.api.schema.SchemaProperty;
import com.jetbrains.youtrack.db.internal.common.io.IOUtils;
import com.jetbrains.youtrack.db.internal.common.listener.ProgressListener;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.common.util.ArrayUtils;
import com.jetbrains.youtrack.db.internal.common.util.RawPair;
import com.jetbrains.youtrack.db.internal.core.command.CommandOutputListener;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.EntityFieldWalker;
import com.jetbrains.youtrack.db.internal.core.db.tool.importer.ConverterData;
import com.jetbrains.youtrack.db.internal.core.db.tool.importer.LinksRewriter;
import com.jetbrains.youtrack.db.internal.core.id.ChangeableRecordId;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.index.IndexDefinition;
import com.jetbrains.youtrack.db.internal.core.index.IndexManagerAbstract;
import com.jetbrains.youtrack.db.internal.core.index.SimpleKeyIndexDefinition;
import com.jetbrains.youtrack.db.internal.core.metadata.MetadataDefault;
import com.jetbrains.youtrack.db.internal.core.metadata.function.Function;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaClassEmbedded;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaClassImpl;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaClassInternal;
import com.jetbrains.youtrack.db.internal.core.metadata.security.Identity;
import com.jetbrains.youtrack.db.internal.core.metadata.security.Role;
import com.jetbrains.youtrack.db.internal.core.metadata.security.Rule;
import com.jetbrains.youtrack.db.internal.core.metadata.security.SecurityPolicy;
import com.jetbrains.youtrack.db.internal.core.metadata.security.SecurityUserImpl;
import com.jetbrains.youtrack.db.internal.core.record.RecordAbstract;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.JSONReader;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.StringSerializerHelper;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.string.JSONSerializerJackson;
import com.jetbrains.youtrack.db.internal.core.storage.PhysicalPosition;
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
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import javax.annotation.Nullable;

/**
 * Import data from a file into a database.
 */
public class DatabaseImport extends DatabaseImpExpAbstract {

  public static final String EXPORT_IMPORT_CLASS_NAME = "___exportImportRIDMap";
  public static final String EXPORT_IMPORT_INDEX_NAME = EXPORT_IMPORT_CLASS_NAME + "Index";

  public static final int IMPORT_RECORD_DUMP_LAP_EVERY_MS = 5000;

  private final Map<SchemaProperty, String> linkedClasses = new HashMap<>();
  private final Map<SchemaClass, List<String>> superClasses = new HashMap<>();
  private JSONReader jsonReader;
  private boolean schemaImported = false;
  private int exporterVersion = -1;
  private RID schemaRecordId;
  private RID indexMgrRecordId;

  private boolean deleteRIDMapping = true;

  private boolean preserveCollectionIDs = true;
  private boolean migrateLinks = true;
  private boolean rebuildIndexes = true;

  private final Set<String> indexesToRebuild = new HashSet<>();

  private final Int2IntOpenHashMap collectionToCollectionMapping = new Int2IntOpenHashMap();

  private int maxRidbagStringSizeBeforeLazyImport = 100_000_000;

  public DatabaseImport(
      final DatabaseSessionInternal database,
      final String fileName,
      final CommandOutputListener outputListener)
      throws IOException {
    super(database, fileName, outputListener);
    collectionToCollectionMapping.defaultReturnValue(-2);
    // TODO: check unclosed stream?
    final var bufferedInputStream =
        new BufferedInputStream(new FileInputStream(this.fileName));
    bufferedInputStream.mark(1024);
    InputStream inputStream;
    try {
      inputStream = new GZIPInputStream(bufferedInputStream, 16384); // 16KB
    } catch (final Exception ignore) {
      bufferedInputStream.reset();
      inputStream = bufferedInputStream;
    }
    createJsonReaderDefaultListenerAndDeclareIntent(outputListener, inputStream);
  }

  public DatabaseImport(
      final DatabaseSessionInternal database,
      final InputStream inputStream,
      final CommandOutputListener outputListener)
      throws IOException {
    super(database, "streaming", outputListener);
    collectionToCollectionMapping.defaultReturnValue(-2);
    createJsonReaderDefaultListenerAndDeclareIntent(outputListener, inputStream);
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
    } else {
      if (option.equalsIgnoreCase("-preserveCollectionIDs")) {
        preserveCollectionIDs = Boolean.parseBoolean(items.getFirst());
      } else {

        if (option.equalsIgnoreCase("-migrateLinks")) {
          migrateLinks = Boolean.parseBoolean(items.getFirst());
        } else {
          if (option.equalsIgnoreCase("-rebuildIndexes")) {
            rebuildIndexes = Boolean.parseBoolean(items.getFirst());
          } else {
            super.parseSetting(option, items);
          }
        }
      }
    }
  }

  public DatabaseImport importDatabase() {
    session.checkSecurity(Rule.ResourceGeneric.DATABASE, Role.PERMISSION_ALL);
    final var preValidation = session.isValidationEnabled();
    try {
      listener.onMessage(
          "\nStarted import of database '" + session.getURL() + "' from " + fileName + "...");
      final var time = System.nanoTime();

      jsonReader.readNext(JSONReader.BEGIN_OBJECT);
      session.setValidationEnabled(false);
      session.setUser(null);

      removeDefaultNonSecurityClasses();
      session.getMetadata().getIndexManagerInternal().reload(session);

      for (final var index :
          session.getMetadata().getIndexManagerInternal().getIndexes(session)) {
        if (index.isAutomatic()) {
          indexesToRebuild.add(index.getName());
        }
      }

      var beforeImportSchemaSnapshot = session.getMetadata().getImmutableSchemaSnapshot();

      var collectionsImported = false;
      while (jsonReader.hasNext() && jsonReader.lastChar() != '}') {
        final var tag = jsonReader.readString(JSONReader.FIELD_ASSIGNMENT);

        if (tag.equals("info")) {
          importInfo();
        } else {
          if (tag.equals("collections")) {
            importCollections();
            collectionsImported = true;
          } else {
            if (tag.equals("schema")) {
              importSchema(collectionsImported);
            } else {
              if (tag.equals("records")) {
                importRecords(beforeImportSchemaSnapshot);
              } else {
                if (tag.equals("indexes")) {
                  importIndexes();
                } else {

                  if (tag.equals("brokenRids")) {
                    processBrokenRids();
                  } else {
                    throw new DatabaseImportException(
                        "Invalid format. Found unsupported tag '" + tag + "'");
                  }
                }
              }
            }
          }
        }
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
                + fileName, e1);
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

  // just read collection so import process can continue
  private void processBrokenRids(final Set<RID> brokenRids) throws IOException, ParseException {
    if (exporterVersion >= 12) {
      listener.onMessage(
          "Reading of set of RIDs of records which were detected as broken during database"
              + " export\n");
      jsonReader.readNext(JSONReader.BEGIN_COLLECTION);

      do {
        jsonReader.readNext(JSONReader.NEXT_IN_ARRAY);

        final var recordId = new RecordId(jsonReader.getValue());
        brokenRids.add(recordId);

      } while (jsonReader.lastChar() != ']');
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
    session.getMetadata().getIndexManagerInternal().reload(session);

    var indexManager = session.getMetadata().getIndexManagerInternal();

    listener.onMessage("\nRebuild of stale indexes...");
    for (var indexName : indexesToRebuild) {

      if (indexManager.getIndex(session, indexName) == null) {
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
    while (jsonReader.lastChar() != '}') {
      final var fieldName = jsonReader.readString(JSONReader.FIELD_ASSIGNMENT);
      if (fieldName.equals("exporter-version")) {
        exporterVersion = jsonReader.readInteger(JSONReader.NEXT_IN_OBJECT);
      } else {
        if (fieldName.equals("schemaRecordId")) {
          schemaRecordId = new RecordId(jsonReader.readString(JSONReader.NEXT_IN_OBJECT));
        } else {
          if (fieldName.equals("indexMgrRecordId")) {
            indexMgrRecordId = new RecordId(jsonReader.readString(JSONReader.NEXT_IN_OBJECT));
          } else {
            jsonReader.readNext(JSONReader.NEXT_IN_OBJECT);
          }
        }
      }
    }
    jsonReader.readNext(JSONReader.COMMA_SEPARATOR);

    if (schemaRecordId == null) {
      schemaRecordId =
          new RecordId(session.getStorageInfo().getConfiguration().getSchemaRecordId());
    }

    if (indexMgrRecordId == null) {
      indexMgrRecordId =
          new RecordId(session.getStorageInfo().getConfiguration().getIndexMgrRecordId());
    }

    listener.onMessage("OK");
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

    final var indexManager = session.getMetadata().getIndexManagerInternal();
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
              if (className.equalsIgnoreCase(parentClass.getName())) {
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
      } while (jsonReader.lastChar() == ',');
      jsonReader.readNext(JSONReader.COMMA_SEPARATOR);
      jsonReader.readNext(JSONReader.FIELD_ASSIGNMENT);
    }

    if (jsonReader.getValue().equals("\"blob-collections\"")) {
      var blobCollectionIds = jsonReader.readString(JSONReader.END_COLLECTION, true).trim();
      blobCollectionIds = blobCollectionIds.substring(1, blobCollectionIds.length() - 1);

      if (!blobCollectionIds.isEmpty()) {
        // READ BLOB COLLECTION IDS
        for (var i :
            StringSerializerHelper.split(
                blobCollectionIds, StringSerializerHelper.RECORD_SEPARATOR)) {
          var collection = Integer.parseInt(i);
          if (!ArrayUtils.contains(session.getBlobCollectionIds(), collection)) {
            var name = session.getCollectionNameById(collection);
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
      do {
        jsonReader.readNext(JSONReader.BEGIN_OBJECT);
        var className =
            jsonReader
                .readNext(JSONReader.FIELD_ASSIGNMENT)
                .checkContent("\"name\"")
                .readString(JSONReader.COMMA_SEPARATOR);

        var collectionIdsStr = jsonReader
            .readNext(JSONReader.FIELD_ASSIGNMENT)
            .checkContent("\"collection-ids\"")
            .readString(JSONReader.END_COLLECTION, true)
            .trim();

        var classCollectionIds =
            StringSerializerHelper.splitIntArray(
                collectionIdsStr.substring(1, collectionIdsStr.length() - 1));

        jsonReader.readNext(JSONReader.NEXT_IN_OBJECT);
        if (className.contains(".")) {
          // MIGRATE OLD NAME WITH . TO _
          final var newClassName = className.replace('.', '_');
          listener.onMessage(
              "\nWARNING: class '" + className + "' has been renamed in '" + newClassName + "'\n");

          className = newClassName;
        }

        var cls = session.getMetadata().getSchema().getClass(className);

        if (cls == null) {
          if (collectionsImported) {
            cls =
                session
                    .getMetadata()
                    .getSchema()
                    .createClass(className, classCollectionIds);
          } else {
            if (className.equalsIgnoreCase("ORestricted")) {
              cls = session.getMetadata().getSchema()
                  .createAbstractClass(className);
            } else {
              cls = session.getMetadata().getSchema().createClass(className);
            }
          }
        }

        String value;
        while (jsonReader.lastChar() == ',') {
          jsonReader.readNext(JSONReader.FIELD_ASSIGNMENT);
          value = jsonReader.getValue();

          switch (value) {
            case "\"strictMode\"" ->
                cls.setStrictMode(jsonReader.readBoolean(JSONReader.NEXT_IN_OBJECT));
            case "\"abstract\"" ->
                cls.setAbstract(jsonReader.readBoolean(JSONReader.NEXT_IN_OBJECT));
            case "\"super-class\"" -> {
              // @compatibility <2.1 SINGLE CLASS ONLY
              final var classSuper = jsonReader.readString(JSONReader.NEXT_IN_OBJECT);
              final List<String> superClassNames = new ArrayList<>();
              superClassNames.add(classSuper);
              superClasses.put(cls, superClassNames);
            }
            case "\"super-classes\"" -> {
              // MULTIPLE CLASSES
              jsonReader.readNext(JSONReader.BEGIN_COLLECTION);

              final List<String> superClassNames = new ArrayList<>();
              while (jsonReader.lastChar() != ']') {
                jsonReader.readNext(JSONReader.NEXT_IN_ARRAY);

                final var clsName = jsonReader.getValue();

                superClassNames.add(IOUtils.getStringContent(clsName));
              }
              jsonReader.readNext(JSONReader.NEXT_IN_OBJECT);

              superClasses.put(cls, superClassNames);
            }
            case "\"properties\"" -> {
              // GET PROPERTIES
              jsonReader.readNext(JSONReader.BEGIN_COLLECTION);

              while (jsonReader.lastChar() != ']') {
                importProperty((SchemaClassInternal) cls);

                if (jsonReader.lastChar() == '}') {
                  jsonReader.readNext(JSONReader.NEXT_IN_ARRAY);
                }
              }
              jsonReader.readNext(JSONReader.NEXT_IN_OBJECT);
            }
            case "\"customFields\"" -> {
              var customFields = importCustomFields();
              for (var entry : customFields.entrySet()) {
                cls.setCustom(entry.getKey(), entry.getValue());
              }
            }
          }
        }

        classImported++;

        jsonReader.readNext(JSONReader.NEXT_IN_ARRAY);
      } while (jsonReader.lastChar() == ',');

      this.rebuildCompleteClassInheritence();
      this.setLinkedClasses();

      if (exporterVersion < 11) {
        var role = session.getMetadata().getSchema().getClass(Role.CLASS_NAME);
        role.dropProperty("rules");
      }

      listener.onMessage("OK (" + classImported + " classes)");
      schemaImported = true;
      jsonReader.readNext(JSONReader.END_OBJECT);
      jsonReader.readNext(JSONReader.COMMA_SEPARATOR);
    } catch (final Exception e) {
      LogManager.instance().error(this, "Error on importing schema", e);
      listener.onMessage("ERROR (" + classImported + " entries): " + e);
    }
  }

  private void rebuildCompleteClassInheritence() {
    for (final var entry : superClasses.entrySet()) {
      for (final var superClassName : entry.getValue()) {
        final var superClass = session.getMetadata().getSchema().getClass(superClassName);

        if (!entry.getKey().getSuperClasses().contains(superClass)) {
          entry.getKey().addSuperClass(superClass);
        }
      }
    }
  }

  private void importProperty(final SchemaClassInternal iClass) throws IOException, ParseException {
    jsonReader.readNext(JSONReader.NEXT_OBJ_IN_ARRAY);

    if (jsonReader.lastChar() == ']') {
      return;
    }

    final var propName =
        jsonReader
            .readNext(JSONReader.FIELD_ASSIGNMENT)
            .checkContent("\"name\"")
            .readString(JSONReader.COMMA_SEPARATOR);

    var next = jsonReader.readNext(JSONReader.FIELD_ASSIGNMENT).getValue();

    if (next.equals("\"id\"")) {
      // @COMPATIBILITY 1.0rc4 IGNORE THE ID
      jsonReader.readString(JSONReader.COMMA_SEPARATOR);
      jsonReader.readNext(JSONReader.FIELD_ASSIGNMENT);
    }
    next = jsonReader.checkContent("\"type\"").readString(JSONReader.NEXT_IN_OBJECT);

    final var type = PropertyTypeInternal.valueOf(next);

    String attrib;
    String value = null;

    String min = null;
    String max = null;
    String linkedClass = null;
    PropertyTypeInternal linkedType = null;
    var mandatory = false;
    var readonly = false;
    var notNull = false;
    String collate = null;
    String regexp = null;
    String defaultValue = null;

    Map<String, String> customFields = null;

    while (jsonReader.lastChar() == ',') {
      jsonReader.readNext(JSONReader.FIELD_ASSIGNMENT);

      attrib = jsonReader.getValue();
      if (!attrib.equals("\"customFields\"")) {
        value =
            jsonReader.readString(
                JSONReader.NEXT_IN_OBJECT, false, JSONReader.DEFAULT_JUMP, null, false);
      }

      if (attrib.equals("\"min\"")) {
        min = value;
      } else {
        if (attrib.equals("\"max\"")) {
          max = value;
        } else {
          if (attrib.equals("\"linked-class\"")) {
            linkedClass = value;
          } else {
            if (attrib.equals("\"mandatory\"")) {
              mandatory = Boolean.parseBoolean(value);
            } else {
              if (attrib.equals("\"readonly\"")) {
                readonly = Boolean.parseBoolean(value);
              } else {
                if (attrib.equals("\"not-null\"")) {
                  notNull = Boolean.parseBoolean(value);
                } else {
                  if (attrib.equals("\"linked-type\"")) {
                    linkedType = PropertyTypeInternal.valueOf(value);
                  } else {
                    if (attrib.equals("\"collate\"")) {
                      collate = value;
                    } else {
                      if (attrib.equals("\"default-value\"")) {
                        defaultValue = value;
                      } else {
                        if (attrib.equals("\"customFields\"")) {
                          customFields = importCustomFields();
                        } else {
                          if (attrib.equals("\"regexp\"")) {
                            regexp = value;
                          }
                        }
                      }
                    }
                  }
                }
              }
            }
          }
        }
      }
    }

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
      prop.setDefaultValue(value);
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

    while (jsonReader.lastChar() != '}') {
      final var key = jsonReader.readString(JSONReader.FIELD_ASSIGNMENT);
      final var value = jsonReader.readString(JSONReader.NEXT_IN_OBJECT);

      result.put(key, value);
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

    while (jsonReader.lastChar() != ']') {
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

      var createdCollectionId = name != null ? session.getCollectionIdByName(name) : -1;
      if (createdCollectionId == -1) {
        // CREATE IT
        if (!preserveCollectionIDs) {
          createdCollectionId = session.addCollection(name);
        } else {
          if (getDatabase().getCollectionNameById(collectionIdFromJson) == null) {
            createdCollectionId = session.addCollection(name, collectionIdFromJson, null);
            assert createdCollectionId == collectionIdFromJson;
          } else {
            createdCollectionId = session.addCollection(name);
            listener.onMessage(
                "\n- WARNING collection with id " + collectionIdFromJson + " already exists");
          }
        }
      }

      if (createdCollectionId != collectionIdFromJson) {
        if (!preserveCollectionIDs) {
          if (session.countCollectionElements(createdCollectionId - 1) == 0) {
            listener.onMessage("Found previous version: migrating old collections...");
            session.dropCollection(name);
            session.addCollection("temp_" + createdCollectionId);
            createdCollectionId = session.addCollection(name);
          } else {
            throw new ConfigurationException(
                session.getDatabaseName(), "Imported collection '"
                + name
                + "' has id="
                + createdCollectionId
                + " different from the original: "
                + collectionIdFromJson
                + ". To continue the import drop the collection '"
                + session.getCollectionNameById(createdCollectionId - 1)
                + "' that has "
                + session.countCollectionElements(createdCollectionId - 1)
                + " records");
          }
        } else {

          final var clazz =
              session.getMetadata().getSchema().getClassByCollectionId(createdCollectionId);
          if (clazz instanceof SchemaClassEmbedded) {
            throw new DatabaseImportException("Can not drop collection with id " + createdCollectionId
                + " because it is used by class " + clazz.getName());
          }

          session.dropCollection(createdCollectionId);
          createdCollectionId = session.addCollection(name, collectionIdFromJson, null);
        }
      }
      collectionToCollectionMapping.put(collectionIdFromJson, createdCollectionId);

      listener.onMessage("OK, assigned id=" + createdCollectionId + ", was " + collectionIdFromJson);

      total++;

      jsonReader.readNext(JSONReader.NEXT_IN_ARRAY);
    }
    jsonReader.readNext(JSONReader.COMMA_SEPARATOR);

    listener.onMessage("\nRebuilding indexes of truncated collections ...");

    for (final var indexName : indexesToRebuild) {
      session
          .getMetadata()
          .getIndexManagerInternal()
          .getIndex(session, indexName)
          .rebuild(session,
              new ProgressListener() {
                private long last = 0;

                @Override
                public void onBegin(Object iTask, long iTotal, Object metadata) {
                  listener.onMessage(
                      "\n- Collection content was updated: rebuilding index '" + indexName + "'...");
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
                public void onCompletition(DatabaseSessionInternal session, Object iTask,
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
  @Nullable
  private RID importRecord(HashSet<RID> recordsBeforeImport,
      Schema beforeImportSchemaSnapshot)
      throws Exception {
    RawPair<RecordAbstract, JSONSerializerJackson.RecordMetadata> recordWithMetadata = null;
    session.begin();
    var ok = false;
    try {
      var recordParse =
          jsonReader.readRecordString(this.maxRidbagStringSizeBeforeLazyImport);
      var value = recordParse.getKey().trim();
      if (value.isEmpty()) {
        return null;
      }
      try {
        recordWithMetadata =
            JSONSerializerJackson.fromStringWithMetadata(session,
                value,
                null
            );

        var record = recordWithMetadata.first;
        var metadata = recordWithMetadata.second;

        switch (metadata.internalRecordType()) {
          case SCHEMA -> {
            recordsBeforeImport.remove(schemaRecordId);
            if (!schemaImported) {
              return null;
            }

            var schemaRecord = (RecordAbstract) session.load(schemaRecordId);
            schemaRecord.fromStream(record.toStream());
            record.delete();

            return schemaRecordId;
          }
          case INDEX_MANAGER -> {
            recordsBeforeImport.remove(indexMgrRecordId);
            return null;
          }
          case null -> {
            final RID rid = record.getIdentity();
            final var collectionId = rid.getCollectionId();

            if (isSystemRecord(beforeImportSchemaSnapshot, collectionId)) {
              var recordStream = record.toStream();
              //or we will find ourselves.
              var name = ((Entity) record).getString("name");
              record.delete();
              var systemRecord = findRelatedSystemRecord(beforeImportSchemaSnapshot, collectionId,
                  name);
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

                systemRecord.fromStream(recordStream);
                recordsBeforeImport.remove(systemRecord.getIdentity());
              } else {
                JSONSerializerJackson.fromStringWithMetadata(session,
                    value,
                    null
                );
              }
            }
          }
        }
      } catch (Exception t) {
        if (recordWithMetadata != null) {
          var record = recordWithMetadata.first;

          LogManager.instance()
              .error(
                  this,
                  "Error importing record "
                      + record.getIdentity()
                      + ". Source line "
                      + jsonReader.getLineNumber()
                      + ", column "
                      + jsonReader.getColumnNumber(),
                  t);
        } else {
          LogManager.instance()
              .error(
                  this,
                  "Error importing record. Source line "
                      + jsonReader.getLineNumber()
                      + ", column "
                      + jsonReader.getColumnNumber(),
                  t);
        }

        if (!(t instanceof DatabaseException)) {
          throw t;
        }
      }

      ok = true;
      return recordWithMetadata.first.getIdentity();
    } finally {
      if (ok) {
        session.commit();
      } else {
        session.rollback();
      }
    }
  }

  private EntityImpl findRelatedSystemRecord(Schema beforeImportSchemaSnapshot, int collectionId,
      String name) {
    EntityImpl systemRecord = null;
    var cls = beforeImportSchemaSnapshot.getClassByCollectionId(collectionId);
    if (cls != null) {

      if (cls.getName().equals(SecurityUserImpl.CLASS_NAME)) {
        try (var resultSet =
            session.query(
                "select from " + SecurityUserImpl.CLASS_NAME + " where name = ?",
                name)) {
          if (resultSet.hasNext()) {
            systemRecord = (EntityImpl) resultSet.next().asEntity();
          }
        }
      } else if (cls.getName().equals(Role.CLASS_NAME)) {
        try (var resultSet =
            session.query(
                "select from " + Role.CLASS_NAME + " where name = ?",
                name)) {
          if (resultSet.hasNext()) {
            systemRecord = (EntityImpl) resultSet.next().asEntity();
          }
        }
      } else if (cls.getName().equals(SecurityPolicy.class.getSimpleName())) {
        try (var resultSet =
            session.query(
                "select from " + SecurityPolicy.class.getSimpleName() + " where name = ?",
                name)) {
          if (resultSet.hasNext()) {
            systemRecord = (EntityImpl) resultSet.next().asEntity();
          }
        }
      } else //noinspection StatementWithEmptyBody
        if (cls.getName().equals("V") || cls.getName().equals("E")) {
          // skip it
        } else {
          throw new IllegalStateException(
              "Class " + cls.getName() + " is not supported.");
        }
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
    final var begin = System.currentTimeMillis();

    long totalRecords = 0;
    try {
      long total = 0;
      jsonReader.readNext(JSONReader.BEGIN_COLLECTION);

      listener.onMessage("\n\nImporting records...");

      // the only security records are left at this moment so we need to overwrite them
      // and then remove left overs
      final var recordsBeforeImport = new HashSet<RID>();

      session.executeInTx(transaction -> {
        for (final var collectionName : session.getCollectionNames()) {
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
      RID lastRid = new ChangeableRecordId();

      long lastLapRecords = 0;
      var last = begin;
      Set<String> involvedCollections = new HashSet<>();

      LogManager.instance().debug(this, "Detected exporter version " + exporterVersion + ".");
      while (jsonReader.lastChar() != ']') {
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
    processBrokenRids(brokenRids);

    listener.onMessage(
        String.format(
            "\n\nDone. Imported %,d records in %,.2f secs\n",
            totalRecords, ((float) (System.currentTimeMillis() - begin)) / 1000));

    jsonReader.readNext(JSONReader.COMMA_SEPARATOR);
  }

  private void importIndexes() throws IOException, ParseException {
    listener.onMessage("\n\nImporting indexes ...");

    var indexManager = session.getMetadata().getIndexManagerInternal();
    indexManager.reload(session);

    jsonReader.readNext(JSONReader.BEGIN_COLLECTION);

    var numberOfCreatedIndexes = 0;
    while (jsonReader.lastChar() != ']') {
      jsonReader.readNext(JSONReader.NEXT_OBJ_IN_ARRAY);
      if (jsonReader.lastChar() == ']') {
        break;
      }

      String indexName = null;
      String indexType = null;
      String indexAlgorithm = null;
      Set<String> collectionsToIndex = new HashSet<>();
      IndexDefinition indexDefinition = null;
      Map<String, Object> metadata = null;
      var objectMapper = new ObjectMapper();
      var typeRef = new TypeReference<HashMap<String, Object>>() {
      };

      while (jsonReader.lastChar() != '}') {
        final var fieldName = jsonReader.readString(JSONReader.FIELD_ASSIGNMENT);
        if (fieldName.equals("name")) {
          indexName = jsonReader.readString(JSONReader.NEXT_IN_OBJECT);
        } else {
          if (fieldName.equals("type")) {
            indexType = jsonReader.readString(JSONReader.NEXT_IN_OBJECT);
          } else {
            if (fieldName.equals("algorithm")) {
              indexAlgorithm = jsonReader.readString(JSONReader.NEXT_IN_OBJECT);
            } else {
              if (fieldName.equals("collectionsToIndex")) {
                collectionsToIndex = importCollectionsToIndex();
              } else {
                if (fieldName.equals("definition")) {
                  indexDefinition = importIndexDefinition(objectMapper);
                  jsonReader.readNext(JSONReader.NEXT_IN_OBJECT);
                } else {
                  if (fieldName.equals("metadata")) {
                    final var jsonMetadata = jsonReader.readString(JSONReader.END_OBJECT, true);
                    metadata = objectMapper.readValue(jsonMetadata, typeRef);
                  } else {
                    if (fieldName.equals("engineProperties")) {
                      final var jsonEngineProperties =
                          jsonReader.readString(JSONReader.END_OBJECT, true);
                      var entity = new EntityImpl(session);
                      entity.updateFromJSON(jsonEngineProperties);
                      Map<String, ?> map = entity.toMap();
                      map.replaceAll((k, v) -> v);
                      jsonReader.readNext(JSONReader.NEXT_IN_OBJECT);
                    }
                  }
                }
              }
            }
          }
        }
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
    listener.onMessage("\nDone. Created " + numberOfCreatedIndexes + " indexes.");
    jsonReader.readNext(JSONReader.NEXT_IN_OBJECT);
  }

  private int dropAutoCreatedIndexesAndCountCreatedIndexes(
      final IndexManagerAbstract indexManager,
      int numberOfCreatedIndexes,
      final String indexName,
      final String indexType,
      final String indexAlgorithm,
      final Set<String> collectionsToIndex,
      IndexDefinition indexDefinition,
      final Map<String, Object> metadata) {
    if (indexName == null) {
      throw new IllegalArgumentException("Index name is missing");
    }

    // drop automatically created indexes
    if (!indexName.equalsIgnoreCase(EXPORT_IMPORT_INDEX_NAME)) {
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

    while (jsonReader.lastChar() != ']') {
      final var collectionToIndex = jsonReader.readString(JSONReader.NEXT_IN_ARRAY);
      collectionsToIndex.add(collectionToIndex);
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
    } catch (final ClassNotFoundException | NoSuchMethodException | InvocationTargetException |
                   InstantiationException | IllegalAccessException e) {
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

    final var begin = System.currentTimeMillis();
    final var last = new long[]{begin};
    final var entitiesLastLap = new long[1];

    var totalEntities = new long[1];
    var collectionNames = session.getCollectionNames();
    for (var collectionName : collectionNames) {
      if (MetadataDefault.COLLECTION_INTERNAL_NAME.equals(collectionName)) {
        continue;
      }

      final var entities = new long[1];
      final var prefix = new String[]{""};

      listener.onMessage("\n- Collection " + collectionName + "...");

      final var collectionId = session.getCollectionIdByName(collectionName);
      final var collectionRecords = session.countCollectionElements(collectionId);
      var storage = session.getStorage();

      var positions =
          storage.ceilingPhysicalPositions(session, collectionId, new PhysicalPosition(0),
              Integer.MAX_VALUE);
      while (positions.length > 0) {
        for (var position : positions) {
          session.executeInTx(transaction -> {
            var record = session.load(new RecordId(collectionId, position.collectionPosition));
            if (record instanceof EntityImpl entity) {
              rewriteLinksInDocument(session, entity, brokenRids);

              entities[0]++;
              entitiesLastLap[0]++;
              totalEntities[0]++;

              final var now = System.currentTimeMillis();
              if (now - last[0] > IMPORT_RECORD_DUMP_LAP_EVERY_MS) {
                listener.onMessage(
                    String.format(
                        "\n--- Migrated %,d of %,d records (%,.2f/sec)",
                        entities[0],
                        collectionRecords,
                        (float) entitiesLastLap[0] * 1000
                            / (float) IMPORT_RECORD_DUMP_LAP_EVERY_MS));

                // RESET LAP COUNTERS
                last[0] = now;
                entitiesLastLap[0] = 0;
                prefix[0] = "\n---";
              }
            }
          });
        }

        positions = storage.higherPhysicalPositions(session, collectionId,
            positions[positions.length - 1], Integer.MAX_VALUE);
      }

      listener.onMessage(
          String.format(
              "%s Completed migration of %,d records in current collection", prefix[0], entities[0]));
    }

    listener.onMessage(String.format("\nTotal links updated: %,d", totalEntities[0]));
  }

  protected static void rewriteLinksInDocument(
      DatabaseSessionInternal session, EntityImpl entity, Set<RID> brokenRids) {
    entity = doRewriteLinksInDocument(session, entity, brokenRids);

    if (!entity.isDirty()) {
      // nothing changed
    }
  }

  protected static EntityImpl doRewriteLinksInDocument(
      DatabaseSessionInternal session, EntityImpl entity, Set<RID> brokenRids) {
    final var rewriter = new LinksRewriter(new ConverterData(session, brokenRids));
    final var entityFieldWalker = new EntityFieldWalker();
    return entityFieldWalker.walkDocument(session, entity, rewriter);
  }

  @SuppressWarnings("unused")
  public int getMaxRidbagStringSizeBeforeLazyImport() {
    return maxRidbagStringSizeBeforeLazyImport;
  }

  public void setMaxRidbagStringSizeBeforeLazyImport(int maxRidbagStringSizeBeforeLazyImport) {
    this.maxRidbagStringSizeBeforeLazyImport = maxRidbagStringSizeBeforeLazyImport;
  }
}
