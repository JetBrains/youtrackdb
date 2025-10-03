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

import com.jetbrains.youtrackdb.api.common.BasicDatabaseSession.STATUS;
import com.jetbrains.youtrackdb.api.exception.BaseException;
import com.jetbrains.youtrackdb.api.exception.DatabaseException;
import com.jetbrains.youtrackdb.api.record.Edge;
import com.jetbrains.youtrackdb.api.record.Entity;
import com.jetbrains.youtrackdb.api.record.RID;
import com.jetbrains.youtrackdb.api.record.Vertex;
import com.jetbrains.youtrackdb.internal.common.io.IOUtils;
import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.common.util.RawPair;
import com.jetbrains.youtrackdb.internal.core.command.CommandOutputListener;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.db.EntityFieldWalker;
import com.jetbrains.youtrackdb.internal.core.db.tool.importer.ConverterData;
import com.jetbrains.youtrackdb.internal.core.db.tool.importer.LinksRewriter;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.SessionMetadata;
import com.jetbrains.youtrackdb.internal.core.metadata.function.Function;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaManager;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaProperty;
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
import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.ParseException;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.zip.GZIPInputStream;
import javax.annotation.Nullable;
import org.apache.commons.lang.StringUtils;
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

  private int maxRidbagStringSizeBeforeLazyImport = 100_000_000;

  public DatabaseImport(
      final DatabaseSessionEmbedded database,
      final String fileName,
      final CommandOutputListener outputListener)
      throws IOException {
    super(database, fileName, outputListener);
    validateSessionImpl();

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
      final DatabaseSessionEmbedded database,
      final InputStream inputStream,
      final CommandOutputListener outputListener)
      throws IOException {
    super(database, "streaming", outputListener);
    validateSessionImpl();
    createJsonReaderDefaultListenerAndDeclareIntent(outputListener, inputStream);
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
    } else if (option.equalsIgnoreCase("-backwardCompatMode")) {
      jsonSerializer = Boolean.parseBoolean(items.getFirst()) ?
          JSONSerializerJackson.IMPORT_BACKWARDS_COMPAT_INSTANCE :
          JSONSerializerJackson.IMPORT_INSTANCE;
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

      removeDefaultNonSecurityClasses();

      var beforeImportSchemaSnapshot = session.getMetadata().getFastImmutableSchema();
      for (final var index : beforeImportSchemaSnapshot.getIndexes()) {
        indexesToRebuild.add(index.getName());
      }

      var collectionsImported = false;
      while (jsonReader.hasNext() && jsonReader.lastChar() != '}') {
        final var tag = jsonReader.readString(JSONReader.FIELD_ASSIGNMENT);

        switch (tag) {
          case "info" -> importInfo();
          case "schema" -> importSchema(collectionsImported);
          case "records" -> importRecords();
          case "indexes" -> importIndexes();
          case "brokenRids" -> processBrokenRids();
          default -> throw new DatabaseImportException(
              "Invalid format. Found unsupported tag '" + tag + "'");
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

        final var recordId = RecordIdInternal.fromString(jsonReader.getValue(), false);
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
    var schema = session.getMetadata().getFastImmutableSchema();

    listener.onMessage("\nRebuild of stale indexes...");
    for (var indexName : indexesToRebuild) {
      if (schema.getIndex(indexName) == null) {
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

    var schema = session.getMetadata().getSlowMutableSchema();
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
    final Schema schema = session.getMetadata().getSlowMutableSchema();
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
        if (exporterVersion < 14) {
          jsonSerializer = JSONSerializerJackson.IMPORT_BACKWARDS_COMPAT_INSTANCE;
        }
      } else {
        jsonReader.readNext(JSONReader.NEXT_IN_OBJECT);
      }
    }
    jsonReader.readNext(JSONReader.COMMA_SEPARATOR);

    listener.onMessage("OK");
  }

  private void removeDefaultNonSecurityClasses() {
    listener.onMessage(
        "\nNon merge mode (-merge=false): removing all default non security classes");

    final Schema schema = session.getMetadata().getSlowMutableSchema();
    final var classes = schema.getClasses();
    final var role = schema.getClass(Role.CLASS_NAME);
    final var user = schema.getClass(SecurityUserImpl.CLASS_NAME);
    final var identity = schema.getClass(Identity.CLASS_NAME);
    // final SchemaClass oSecurityPolicy = schema.getClass(SecurityPolicy.class.getSimpleName());
    final Map<String, SchemaClass> classesToDrop = new HashMap<>();
    final Set<String> indexNames = new HashSet<>();
    for (final var dbClass : classes) {
      final var className = dbClass.getName();
      if (!dbClass.isParentOf(role)
          && !dbClass.isParentOf(user)
          && !dbClass.isParentOf(
          identity) /*&& !dbClass.isParentClassOf(oSecurityPolicy)*/) {
        classesToDrop.put(className, dbClass);
        indexNames.addAll(dbClass.getIndexNames());
      }
    }

    for (final var indexName : indexNames) {
      schema.dropIndex(indexName);
    }

    var removedClasses = 0;
    while (!classesToDrop.isEmpty()) {
      final AbstractList<String> classesReadyToDrop = new ArrayList<>();
      for (final var className : classesToDrop.keySet()) {
        var isSuperClass = false;
        for (var dbClass : classesToDrop.values()) {
          final var parentClasses = dbClass.getParentClasses();
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
          .setLinkedClass(session.getMetadata().getSlowMutableSchema().getClass(
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

    jsonReader.checkContent("\"classes\"").readNext(JSONReader.BEGIN_COLLECTION);
    long classImported = 0;

    try {

      // creating V and E classes ahead of time, because they have to exist
      // before we start creating other vertex or edge classes.
      // we tried to fix this by making the export tool write these classes first,
      // but if the dump was created by an older version of the export tool,
      // it won't work.
      final var schema = session.getMetadata().getSlowMutableSchema();
      final var vertexClass = schema.existsClass(Vertex.CLASS_NAME) ?
          schema.getClass(Vertex.CLASS_NAME) : schema.createClass(Vertex.CLASS_NAME);
      final var edgeClass = schema.existsClass(Edge.CLASS_NAME) ?
          schema.getClass(Edge.CLASS_NAME) : schema.createClass(Edge.CLASS_NAME);
      do {
        jsonReader.readNext(JSONReader.BEGIN_OBJECT);
        var className =
            jsonReader
                .readNext(JSONReader.FIELD_ASSIGNMENT)
                .checkContent("\"name\"")
                .readString(JSONReader.COMMA_SEPARATOR);

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
        while (jsonReader.lastChar() == ',') {
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
              while (jsonReader.lastChar() != ']') {
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

              if (!superClassNames.isEmpty()) {
                superClasses.put(className, superClassNames);
              }
              jsonReader.readNext(JSONReader.NEXT_IN_OBJECT);
            }
            case "\"properties\"" -> {
              propertiesRaw = new ArrayList<>();
              // GET PROPERTIES
              jsonReader.readNext(JSONReader.BEGIN_COLLECTION);

              while (jsonReader.lastChar() != ']') {
                final var pRaw = jsonReader.readNext(JSONReader.NEXT_IN_ARRAY).getValue();
                if (StringUtils.isNotBlank(pRaw)) {
                  final var pMap = jsonSerializer.mapFromJson(pRaw);
                  propertiesRaw.add(pMap);
                }
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
                + "' exists but is not an edge class. It can't be made an edge class."
            );
          }
        } else {
          if (collectionsImported) {
            // other superclasses will be added later.
            final var superClassesToAdd =
                isVertex ? new SchemaClass[]{vertexClass} :
                    isEdge ? new SchemaClass[]{edgeClass} :
                        new SchemaClass[]{};
            cls = schema.createClass(className, superClassesToAdd);
          } else if (className.equalsIgnoreCase("ORestricted")) {
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
            importProperty(cls, propRaw);
          }
        }

        if (customFields != null) {
          for (var cf : customFields.entrySet()) {
            cls.setCustom(cf.getKey(), cf.getValue());
          }
        }

        classImported++;

        jsonReader.readNext(JSONReader.NEXT_IN_ARRAY);
      } while (jsonReader.lastChar() == ',');

      this.rebuildCompleteClassInheritance();
      this.setLinkedClasses();

      if (exporterVersion < 11) {
        var role = session.getMetadata().getSlowMutableSchema().getClass(Role.CLASS_NAME);
        role.dropProperty("rules");
      }

      listener.onMessage("OK (" + classImported + " classes)");
      jsonReader.readNext(JSONReader.END_OBJECT);
      jsonReader.readNext(JSONReader.COMMA_SEPARATOR);
    } catch (final Exception e) {
      LogManager.instance().error(this, "Error on importing schema", e);
      listener.onMessage("ERROR (" + classImported + " entries): " + e);
    }
  }

  private void rebuildCompleteClassInheritance() {
    for (final var entry : superClasses.entrySet()) {
      final var cls = session.getMetadata().getSlowMutableSchema().getClass(entry.getKey());

      for (final var superClassName : entry.getValue()) {
        final var superClass = session.getMetadata().getSlowMutableSchema()
            .getClass(superClassName);

        if (!cls.getParentClasses().contains(superClass)) {
          cls.addParentClass(superClass);
        }
      }
    }
  }

  private void importProperty(final SchemaClass iClass, Map<String, ?> propRaw) {

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
          (PropertyTypeInternal) null
      );
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
      prop.setLinkedType(linkedType);
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
        prop.setCustomProperty(entry.getKey(), entry.getValue());
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

  /**
   * From `exporterVersion` >= `13`, `fromStream()` will be used. However, the import is still of
   * type String, and thus has to be converted to InputStream, which can only be avoided by
   * introducing a new interface method.
   */
  @Nullable
  private RID importRecord() throws Exception {
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

  private void importRecords() throws Exception {
    final Schema schema = session.getMetadata().getSlowMutableSchema();
    if (schema.getClass(EXPORT_IMPORT_CLASS_NAME) != null) {
      schema.dropClass(EXPORT_IMPORT_CLASS_NAME);
    }

    final var cls = schema.createClass(EXPORT_IMPORT_CLASS_NAME);
    cls.createProperty("key", PropertyTypeInternal.STRING);
    cls.createProperty("value", PropertyTypeInternal.STRING);
    cls.createIndex(EXPORT_IMPORT_CLASS_NAME + "_key_unique", SchemaManager.INDEX_TYPE.UNIQUE,
        "key");
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
              session.getStorageInfo().getConfiguration().getSchemaRecordId(),
              false);
      final var indexMgrRecordId =
          RecordIdInternal.fromString(
              session.getStorageInfo().getConfiguration().getIndexMgrRecordId(),
              false);

      session.executeInTx(transaction -> {
        for (final var collectionName : session.getCollectionNames()) {
          if (collectionName.equals(SessionMetadata.COLLECTION_INTERNAL_NAME)) {
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
      while (jsonReader.lastChar() != ']') {
        rid = importRecord();

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
    var numberOfCreatedIndexes = 0;
    listener.onMessage("\nDone. Created " + numberOfCreatedIndexes + " indexes.");
    jsonReader.readNext(JSONReader.NEXT_IN_OBJECT);
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
            (colName, colSize, seenInCol, colDone, seenTotal, speed) ->
                listener.onMessage(
                    String.format(
                        "\n--- Migrated %,d of %,d records (%,.2f/sec) in collection '%s', done: %s",
                        seenInCol, colSize, speed, colName, colDone
                    )
                )
        )
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
            (colName, colSize, seenInCol, colDone, seenTotal, speed) ->
                listener.onMessage(
                    String.format(
                        "\n--- Recovered links for %,d of %,d records (%,.2f/sec) in collection '%s', done: %s",
                        seenInCol, colSize, speed, colName, colDone
                    )
                )
        )
        .walkEntitiesInTx(entity -> {
          entity.markAllLinksAsChanged();
          return true;
        });
    listener.onMessage(String.format("\nTotal links recovered: %,d", linksRecovered));

    listener.onMessage(String.format("\nTotal links updated: %,d", linksUpdated));
  }

  protected static void rewriteLinksInDocument(
      DatabaseSessionInternal session, EntityImpl entity, Set<RID> brokenRids) {
    doRewriteLinksInDocument(session, entity, brokenRids);
  }

  protected static void doRewriteLinksInDocument(
      DatabaseSessionInternal session, EntityImpl entity, Set<RID> brokenRids) {
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
