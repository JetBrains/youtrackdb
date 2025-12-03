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
package com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.string;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.MapType;
import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.common.util.CommonConst;
import com.jetbrains.youtrackdb.internal.common.util.RawPair;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.db.record.EntityEmbeddedListImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.EntityEmbeddedMapImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.EntityEmbeddedSetImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.EntityLinkListImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.EntityLinkMapIml;
import com.jetbrains.youtrackdb.internal.core.db.record.EntityLinkSetImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.RecordElement;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Blob;
import com.jetbrains.youtrackdb.internal.core.db.record.record.DBRecord;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Edge;
import com.jetbrains.youtrackdb.internal.core.db.record.record.EmbeddedEntity;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Entity;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Vertex;
import com.jetbrains.youtrackdb.internal.core.db.record.ridbag.LinkBag;
import com.jetbrains.youtrackdb.internal.core.exception.BaseException;
import com.jetbrains.youtrackdb.internal.core.exception.SerializationException;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.MetadataDefault;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaProperty;
import com.jetbrains.youtrackdb.internal.core.record.RecordAbstract;
import com.jetbrains.youtrackdb.internal.core.record.impl.EmbeddedEntityImpl;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityHelper;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl.PropertyValidationMode;
import com.jetbrains.youtrackdb.internal.core.record.impl.StatefullEdgeEntityImpl;
import com.jetbrains.youtrackdb.internal.core.record.impl.VertexEntityImpl;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.apache.commons.lang.StringUtils;

public class JSONSerializerJackson {

  private final JsonFactory jsonFactory;

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final MapType mapTypeReference =
      objectMapper.getTypeFactory().constructMapType(HashMap.class, String.class, Object.class);

  /**
   * Default JSON serializer.
   */
  public static final JSONSerializerJackson INSTANCE =
      new JSONSerializerJackson(false, false, false, Set.of());

  /**
   * JSON serializer that is used by DatabaseImport tool. It allows creating graph structures
   * (vertex and edge records and their in/out properties).
   */
  public static final JSONSerializerJackson IMPORT_INSTANCE =
      new JSONSerializerJackson(false, false, true, Set.of());

  /**
   * JSON serializer with a set of relaxed rules that is used by DatabaseImport tool in backward
   * compatibility mode.
   */
  public static final JSONSerializerJackson IMPORT_BACKWARDS_COMPAT_INSTANCE =
      new JSONSerializerJackson(true, true, true, Set.of('$'));

  private final boolean readOldFieldTypesFormat;
  private final Set<Character> readPrefixUnderscoreReplacements;
  private final boolean readAllowGraphStructure; // allowing to import Vertex and Edge classes and create edges.

  private JSONSerializerJackson(
      boolean readUnescapedControlChars,
      boolean readOldFieldTypesFormat,
      boolean readAllowGraphStructure,
      Set<Character> readPrefixUnderscoreReplacements
  ) {
    this.readOldFieldTypesFormat = readOldFieldTypesFormat;
    this.readPrefixUnderscoreReplacements = readPrefixUnderscoreReplacements;
    this.readAllowGraphStructure = readAllowGraphStructure;
    final var b = JsonFactory.builder();
    if (readUnescapedControlChars) {
      b.enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS);
    }

    jsonFactory = b.build();
  }

  public Map<String, Object> mapFromJson(@Nonnull String value) {
    try {
      return objectMapper.readValue(value, mapTypeReference);
    } catch (JsonProcessingException e) {
      throw BaseException.wrapException(
          new SerializationException("Error on unmarshalling JSON content"), e, (String) null);
    }
  }

  public Map<String, Object> mapFromJson(@Nonnull InputStream stream) throws IOException {
    try {
      return objectMapper.readValue(stream, mapTypeReference);
    } catch (JsonProcessingException e) {
      throw BaseException.wrapException(
          new SerializationException("Error on unmarshalling JSON content"), e, (String) null);
    }
  }

  @Nonnull
  public String mapToJson(@Nonnull Map<String, ?> value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw BaseException.wrapException(
          new SerializationException("Error on marshalling JSON content"), e, (String) null);
    }
  }

  public RecordAbstract fromString(
      @Nonnull DatabaseSessionInternal session,
      @Nonnull String source
  ) {
    return fromStringWithMetadata(session, source, null, false).first();
  }

  public RecordAbstract fromString(
      @Nonnull DatabaseSessionInternal session,
      @Nonnull String source,
      @Nullable RecordAbstract record
  ) {
    return fromStringWithMetadata(session, source, record, false).first();
  }

  @Nonnull
  public RawPair<RecordAbstract, RecordMetadata> fromStringWithMetadata(
      @Nonnull DatabaseSessionInternal session,
      @Nonnull String source,
      @Nullable RecordAbstract record,
      boolean ignoreRid
  ) {
    try (var jsonParser = jsonFactory.createParser(source)) {
      return recordFromJson(session, record, jsonParser, ignoreRid);
    } catch (Exception e) {
      if (record != null && record.getIdentity().isValidPosition()) {
        throw BaseException.wrapException(
            new SerializationException(session,
                "Error on unmarshalling JSON content for record " + record.getIdentity()),
            e, session);
      } else {
        throw BaseException.wrapException(
            new SerializationException(session,
                "Error on unmarshalling JSON content for record: " + source),
            e, session);
      }
    }
  }

  @Nonnull
  private RawPair<RecordAbstract, RecordMetadata> recordFromJson(
      @Nonnull DatabaseSessionInternal session,
      @Nullable RecordAbstract record,
      @Nonnull JsonParser jsonParser,
      boolean ignoreRid
  ) throws IOException {
    var token = jsonParser.nextToken();
    if (token != JsonToken.START_OBJECT) {
      throw new SerializationException(session, "Start of the object is expected");
    }

    String defaultClassName;

    var defaultRecordType = record != null ? record.getRecordType() : EntityImpl.RECORD_TYPE;
    if (record instanceof EntityImpl entity) {
      defaultClassName = entity.getSchemaClassName();
    } else if (record == null) {
      defaultClassName = EntityImpl.DEFAULT_CLASS_NAME;
    } else {
      defaultClassName = null;
    }

    var recordMetaData =
        parseRecordMetadata(session, jsonParser, defaultClassName, defaultRecordType, false);
    if (recordMetaData == null) {
      final var recordId = record != null ? record.getIdentity() : null;
      recordMetaData = new RecordMetadata(
          defaultRecordType,
          recordId,
          defaultClassName,
          Collections.emptyMap(),
          false,
          record != null ? record.getVersion() : null,
          null
      );
    }

    var result =
        createRecordFromJsonAfterMetadata(session, record, recordMetaData, jsonParser, ignoreRid);
    final var next = jsonParser.nextToken();
    if (next != null) {
      throw new SerializationException(session,
          "End of the JSON object is expected, encountered: " + next);
    }

    return new RawPair<>(result, recordMetaData);
  }

  private RecordAbstract createRecordFromJsonAfterMetadata(
      DatabaseSessionInternal session,
      RecordAbstract record,
      RecordMetadata recordMetaData,
      JsonParser jsonParser,
      boolean ignoreRid
  ) throws IOException {
    //initialize record first and then validate the rest of the found metadata
    if (recordMetaData.isEmbedded) {
      throw new SerializationException(
          "Embedded records should be parsed as part of the parent record");
    }

    if (record == null) {
      if (!ignoreRid && recordMetaData.recordId != null) {
        record = session.load(recordMetaData.recordId);
      } else {
        if (EntityHelper.isEntity(recordMetaData.recordType)) {
          if (recordMetaData.className == null) {
            if (recordMetaData.entityType.isInternal()) {
              record = session.newInternalInstance();
            } else {
              record = session.newInstance();
            }
          } else {
            var schemaSnapshot = session.getMetadata().getImmutableSchemaSnapshot();
            var schemaClass = schemaSnapshot.getClass(recordMetaData.className);
            if (schemaClass == null) {
              throw new SerializationException(session,
                  "Class not found: " + recordMetaData.className);
            }
            if (schemaClass.isVertexType()) {
              record = (RecordAbstract) session.newVertex(schemaClass);
            } else if (schemaClass.isEdgeType()) {
              if (readAllowGraphStructure) {
                record = session.newStatefulEdgeInternal(schemaClass.getName());
              } else {
                throw new UnsupportedEncodingException("Edges can not be created from JSON");
              }
            } else {
              record = (RecordAbstract) session.newEntity(schemaClass);
            }
          }
        } else if (recordMetaData.recordType == Blob.RECORD_TYPE) {
          record = (RecordAbstract) session.newBlob();
        } else {
          throw new SerializationException(session,
              "Unsupported record type: " + recordMetaData.recordType);
        }
      }
    } else {
      if (record.getRecordType() != recordMetaData.recordType) {
        throw new SerializationException(session,
            "Record type mismatch: " + record.getRecordType() + " != " + recordMetaData.recordType);
      }
      if (recordMetaData.recordVersion != null
          && record.getVersion() != recordMetaData.recordVersion) {
        throw new SerializationException(session,
            "Record version mismatch: " + record.getVersion() + " != "
                + recordMetaData.recordVersion);
      }
      if (record instanceof EntityImpl entity) {
        var className = entity.getSchemaClassName();

        if (!Objects.equals(className, recordMetaData.className)) {
          if (recordMetaData.className == null) {
            throw new SerializationException(session,
                "Record class name mismatch: " + className + " != " + null);
          }

          var schemaSnapshot = session.getMetadata().getImmutableSchemaSnapshot();
          var schemaClass = schemaSnapshot.getClass(recordMetaData.className);

          if (schemaClass == null) {
            throw new SerializationException(session,
                "Class not found: " + recordMetaData.className);
          }

          var entitySchemaClass = entity.getSchemaClass();
          if (!entitySchemaClass.equals(schemaClass)) {
            throw new SerializationException(session,
                "Record class name mismatch: " + className + " != " + recordMetaData.className);
          }
        }
      }
    }

    if (record instanceof EntityImpl entity && !Objects.equals(entity.getSchemaClassName(),
        recordMetaData.className)) {
      throw new SerializationException(session,
          "Record class name mismatch: " + entity.getSchemaClassName() + " != "
              + recordMetaData.className);
    }
    if (!ignoreRid && recordMetaData.recordId != null && !record.getIdentity()
        .equals(recordMetaData.recordId)) {
      throw new SerializationException(session,
          "Record id mismatch: " + record.getIdentity() + " != " + recordMetaData.recordId);
    }

    parseProperties(session, record, recordMetaData, jsonParser);
    return record;
  }

  private void parseProperties(DatabaseSessionInternal session, RecordAbstract record,
      RecordMetadata recordMetaData, JsonParser jsonParser) throws IOException {
    JsonToken token;
    token = jsonParser.currentToken();

    while (token != JsonToken.END_OBJECT) {
      if (token != JsonToken.FIELD_NAME) {
        throw new SerializationException(session, "Expected field name");
      }
      var fieldName = jsonParser.currentName();
      jsonParser.nextToken();//jump to value

      if (readOldFieldTypesFormat && "@fieldTypes".equals(fieldName)) {
        // ignore the value
        parseValue(session, null, jsonParser, PropertyTypeInternal.STRING, null);
      } else if (!fieldName.isEmpty() && fieldName.charAt(0) == '@') {
        throw new SerializationException(session, "Invalid property name: " + fieldName);
      } else {
        parseProperty(session, recordMetaData.fieldTypes, record, jsonParser, fieldName);
      }

      token = jsonParser.nextToken();
    }
  }

  @Nullable
  private RecordMetadata parseRecordMetadata(
      @Nonnull DatabaseSessionInternal session,
      @Nullable JsonParser jsonParser,
      @Nullable String defaultClassName,
      Byte defaultRecordType,
      boolean asValue
  ) throws IOException {

    var token = jsonParser.nextToken();
    RecordIdInternal recordId = null;
    var recordType = defaultRecordType;
    var className = defaultClassName;
    Map<String, String> fieldTypes = new HashMap<>();
    var entityType = EntityType.PUBLIC;
    Boolean embeddedFlag = null;
    Integer recordVersion = null;

    var fieldsCount = 0;

    while (token != JsonToken.END_OBJECT) {
      if (token == JsonToken.FIELD_NAME) {
        var fieldName = jsonParser.currentName();
        if (fieldName.charAt(0) != '@') {
          break;
        }

        fieldsCount++;
        switch (fieldName) {
          case FieldTypesString.ATTRIBUTE_FIELD_TYPES -> {
            fieldTypes = parseFieldTypes(jsonParser);
            token = jsonParser.nextToken();
          }
          case EntityHelper.ATTRIBUTE_TYPE -> {
            token = jsonParser.nextToken();
            if (token != JsonToken.VALUE_STRING) {
              throw new SerializationException(session,
                  "Expected field value as string.");
            }
            var fieldValueAsString = jsonParser.getText();
            if (fieldValueAsString.length() != 1) {
              throw new SerializationException(session,
                  "Invalid record type: " + fieldValueAsString);
            }
            recordType = (byte) fieldValueAsString.charAt(0);
            token = jsonParser.nextToken();
          }
          case EntityHelper.ATTRIBUTE_RID -> {
            token = jsonParser.nextToken();
            if (token != JsonToken.VALUE_STRING) {
              throw new SerializationException(session,
                  "Expected field value as string");
            }
            var fieldValueAsString = jsonParser.getText();
            if (!fieldValueAsString.isEmpty()) {
              recordId = RecordIdInternal.fromString(fieldValueAsString, false);
            }
            token = jsonParser.nextToken();
          }
          case EntityHelper.ATTRIBUTE_CLASS -> {
            token = jsonParser.nextToken();
            if (token != JsonToken.VALUE_STRING) {
              throw new SerializationException(session,
                  "Expected field value as string");
            }
            var fieldValueAsString = jsonParser.getText();
            className = "null".equals(fieldValueAsString) ? null : fieldValueAsString;
            token = jsonParser.nextToken();
          }
          case EntityHelper.ATTRIBUTE_INTERNAL_ENTITY -> {
            token = jsonParser.nextToken();
            if (token != JsonToken.VALUE_TRUE && token != JsonToken.VALUE_FALSE) {
              throw new SerializationException(session,
                  "Expected field value as boolean");
            }
            var internalRecord = jsonParser.getBooleanValue();
            if (internalRecord) {
              entityType = EntityType.INTERNAL;
            }
            token = jsonParser.nextToken();
          }
          case EntityHelper.ATTRIBUTE_INDEX_MANAGER_ENTITY -> {
            token = jsonParser.nextToken();
            if (token != JsonToken.VALUE_TRUE && token != JsonToken.VALUE_FALSE) {
              throw new SerializationException(session,
                  "Expected field value as boolean");
            }
            var internalRecord = jsonParser.getBooleanValue();
            if (internalRecord) {
              if (entityType != EntityType.PUBLIC) {
                throw new SerializationException(
                    "Entity type already marked as internal : " + entityType);
              }
              entityType = EntityType.INDEX_MANAGER;
            }
            token = jsonParser.nextToken();
          }

          case EntityHelper.ATTRIBUTE_SCHEMA_MANAGER_ENTITY -> {
            token = jsonParser.nextToken();
            if (token != JsonToken.VALUE_TRUE && token != JsonToken.VALUE_FALSE) {
              throw new SerializationException(session,
                  "Expected field value as boolean");
            }
            var internalRecord = jsonParser.getBooleanValue();
            if (internalRecord) {
              if (entityType != EntityType.PUBLIC) {
                throw new SerializationException(
                    "Entity type already marked as internal : " + entityType);
              }
              entityType = EntityType.SCHEMA_MANAGER;
            }
            token = jsonParser.nextToken();
          }
          case EntityHelper.ATTRIBUTE_EMBEDDED -> {
            token = jsonParser.nextToken();
            if (token != JsonToken.VALUE_TRUE && token != JsonToken.VALUE_FALSE) {
              throw new SerializationException(session,
                  "Expected field value as boolean");
            }
            embeddedFlag = jsonParser.getBooleanValue();
            token = jsonParser.nextToken();
          }
          case EntityHelper.ATTRIBUTE_VERSION -> {
            token = jsonParser.nextToken();
            if (token != JsonToken.VALUE_NUMBER_INT) {
              throw new SerializationException(session,
                  "Expected field value integer");
            }
            recordVersion = jsonParser.getIntValue();
            token = jsonParser.nextToken();
          }
          default -> throw new SerializationException(session,
              "Unexpected field name: " + fieldName);
        }
      } else {
        throw new SerializationException(session, "Expected field name");
      }
    }

    if (fieldsCount == 0) {
      return null;
    }

    var schema = session.getMetadata().getImmutableSchemaSnapshot();
    SchemaClass schemaClass = null;
    if (className == null && defaultClassName == null && recordId != null) {
      schemaClass = schema.getClassByCollectionId(recordId.getCollectionId());

      if (schemaClass != null) {
        className = schemaClass.getName();
      }
    }

    if (recordType == null) {
      if (schemaClass == null && className != null) {
        schemaClass = schema.getClass(className);

        if (schemaClass == null) {
          throw new SerializationException(session,
              "Class not found: " + className);
        }
      }

      if (schemaClass != null) {
        if (schemaClass.isVertexType()) {
          recordType = VertexEntityImpl.RECORD_TYPE;
        } else if (schemaClass.isEdgeType()) {
          recordType = StatefullEdgeEntityImpl.RECORD_TYPE;
        } else {
          recordType = EntityImpl.RECORD_TYPE;
        }
      } else {
        recordType = EntityImpl.RECORD_TYPE;
      }
    }

    boolean embeddedValue;
    if (embeddedFlag == null) {
      if (className == null) {
        embeddedValue = asValue && recordId == null;
      } else {
        var cls = session.getMetadata().getImmutableSchemaSnapshot().getClass(className);
        if (cls != null) {
          embeddedValue = cls.isAbstract();
        } else {
          throw new SerializationException(session,
              "Class not found: " + className);
        }
      }
    } else {
      embeddedValue = embeddedFlag;
    }

    return new RecordMetadata(
        recordType,
        recordId,
        entityType.isInternal() ? null : className,
        fieldTypes, embeddedValue, recordVersion, entityType
    );
  }

  private Map<String, String> parseFieldTypes(JsonParser jsonParser) throws IOException {
    var map = new HashMap<String, String>();

    var token = jsonParser.nextToken();
    if (token == JsonToken.START_OBJECT) {

      while (jsonParser.nextToken() != JsonToken.END_OBJECT) {
        var fieldName = jsonParser.currentName();
        token = jsonParser.nextToken();
        if (token != JsonToken.VALUE_STRING) {
          throw new SerializationException("Expected field value as string");
        }

        map.put(fieldName, jsonParser.getText());
      }
    } else if (readOldFieldTypesFormat && token == JsonToken.VALUE_STRING) {
      // old format

      Arrays
          .stream(StringUtils.split(jsonParser.getText(), ','))
          .map(ft -> StringUtils.split(ft, '='))
          .filter(ft -> ft != null && ft.length == 2)
          .forEach(ft -> map.put(ft[0], ft[1]));

    } else {
      throw new SerializationException("Bad @fieldTypes format");
    }

    return map;
  }

  private void parseProperty(
      DatabaseSessionInternal session,
      Map<String, String> fieldTypes,
      RecordAbstract record,
      JsonParser jsonParser,
      String fieldName) throws IOException {

    if (!fieldName.isEmpty() && readPrefixUnderscoreReplacements.contains(fieldName.charAt(0))) {
      fieldName = '_' + fieldName.substring(1);
    }

    // RECORD ATTRIBUTES
    if (!(record instanceof EntityImpl entity)) {
      if (fieldName.equals("value")) {
        var nextToken = jsonParser.currentToken();
        if (nextToken == JsonToken.VALUE_STRING) {
          var fieldValue = jsonParser.getText();
          if (fieldValue == null || "null".equals(fieldValue)) {
            record.fromStream(CommonConst.EMPTY_BYTE_ARRAY);
          } else if (record instanceof Blob) {
            // BYTES
            // we can do better here: read and decode base64 at the same time without creating
            // intermediate structures.
            final var iBuffer = jsonParser.getBinaryValue();
            if (iBuffer != null && iBuffer.length > 0) {
              ((Blob) record).fromInputStream(new ByteArrayInputStream(iBuffer));
            }
          } else {
            throw new SerializationException(session,
                "Unsupported type of record : " + record.getClass().getName());
          }
        } else {
          throw new SerializationException(session,
              "Expected JSON token is a string token, but found " + nextToken);
        }
      } else {
        throw new SerializationException(session,
            "Expected field -> 'value'. JSON content");
      }
    } else {
      var schemaClass = entity.getImmutableSchemaClass(session);
      var schemaProperty = schemaClass != null ? schemaClass.getProperty(fieldName) : null;

      if (EntityImpl.isSystemProperty(fieldName)) {
        throw new SerializationException(
            "System property can not be updated from JSON: " + fieldName);
      }

      var type =
          determineType(entity, fieldName, fieldTypes.get(fieldName), schemaProperty);

      final var isVertex = entity.isVertex();
      final var isEdge = entity.isEdge();

      final var isGraphField = readAllowGraphStructure && (
          isVertex && (
              fieldName.startsWith(Vertex.DIRECTION_IN_PREFIX) ||
                  fieldName.startsWith(Vertex.DIRECTION_OUT_PREFIX)
          ) ||
              isEdge && (
                  fieldName.equals(Edge.DIRECTION_IN) ||
                      fieldName.equals(Edge.DIRECTION_OUT)
              )
      );

      final var value = parseValue(session, entity, jsonParser, type, schemaProperty);

      if (isGraphField && isVertex && type == null) {
        type = PropertyTypeInternal.LINKBAG;
      }

      // skipping validation when importing graph fields
      entity.setPropertyInternal(fieldName, value, type, null,
          isGraphField ? PropertyValidationMode.SKIP : PropertyValidationMode.FULL);
    }
  }

  public StringWriter toString(
      DatabaseSessionInternal session, final DBRecord record,
      final StringWriter output,
      final String format) {
    try (var jsonGenerator = jsonFactory.createGenerator(output)) {
      final var settings = new FormatSettings(format);
      recordToJson(session, record, jsonGenerator, settings);
      return output;
    } catch (final IOException e) {
      throw BaseException.wrapException(
          new SerializationException(session, "Error on marshalling of record to JSON"), e,
          session);
    }
  }

  public void recordToJson(DatabaseSessionInternal session, DBRecord record,
      JsonGenerator jsonGenerator,
      @Nullable String format) {
    try {
      final var settings = new FormatSettings(format);
      recordToJson(session, record, jsonGenerator, settings);
    } catch (final IOException e) {
      throw BaseException.wrapException(
          new SerializationException(session, "Error on marshalling of record to JSON"), e,
          session);
    }
  }

  private void recordToJson(DatabaseSessionInternal session, DBRecord record,
      JsonGenerator jsonGenerator,
      FormatSettings formatSettings) throws IOException {
    jsonGenerator.writeStartObject();
    writeMetadata(session, jsonGenerator, (RecordAbstract) record, formatSettings);

    if (record instanceof EntityImpl entity) {
      for (var propertyName : entity.getPropertyNamesInternal(false, true)) {
        jsonGenerator.writeFieldName(propertyName);
        var propertyValue = entity.getPropertyInternal(propertyName);

        serializeValue(session, jsonGenerator, propertyValue, formatSettings);
      }
    } else if (record instanceof Blob recordBlob) {
      // BYTES
      jsonGenerator.writeFieldName("value");
      jsonGenerator.writeBinary(((RecordAbstract) recordBlob).toStream());
    } else {
      throw new SerializationException(session,
          "Error on marshalling record of type '"
              + record.getClass()
              + "' to JSON. The record type cannot be exported to JSON");
    }
    jsonGenerator.writeEndObject();
  }


  private static void serializeLink(JsonGenerator jsonGenerator, RID rid)
      throws IOException {
    if (!rid.isPersistent()) {
      throw new SerializationException(
          "Cannot serialize non-persistent link: " + rid);
    }
    jsonGenerator.writeString(rid.toString());
  }

  private static void writeMetadata(@Nonnull DatabaseSessionInternal session,
      JsonGenerator jsonGenerator,
      RecordAbstract record, FormatSettings formatSettings)
      throws IOException {
    if (formatSettings.includeVersion && !(record instanceof EmbeddedEntity)) {
      jsonGenerator.writeFieldName(EntityHelper.ATTRIBUTE_VERSION);
      jsonGenerator.writeNumber(record.isDirty() ? record.getVersion() + 1 : record.getVersion());
    }
    if (record instanceof EntityImpl entity) {
      if (!entity.isEmbedded()) {
        if (formatSettings.includeId) {
          jsonGenerator.writeFieldName(EntityHelper.ATTRIBUTE_RID);
          serializeLink(jsonGenerator, entity.getIdentity());
        }
      } else if (formatSettings.markEmbeddedEntities) {
        jsonGenerator.writeFieldName(EntityHelper.ATTRIBUTE_EMBEDDED);
        jsonGenerator.writeBoolean(true);
      }

      if (formatSettings.includeType) {
        jsonGenerator.writeFieldName(EntityHelper.ATTRIBUTE_TYPE);
        jsonGenerator.writeString(Character.toString(record.getRecordType()));
      }

      var schemaClass = entity.getImmutableSchemaClass(session);
      if (schemaClass != null) {
        if (formatSettings.includeClazz) {
          jsonGenerator.writeFieldName(EntityHelper.ATTRIBUTE_CLASS);
          jsonGenerator.writeString(schemaClass.getName());
        }
      } else if (formatSettings.internalRecords && !entity.isEmbedded()) {
        var collectionName = session.getCollectionName(record);

        if (collectionName.equals(MetadataDefault.COLLECTION_INTERNAL_NAME)) {
          var metadata = session.getMetadata();
          var schema = metadata.getSchemaInternal();
          var indexManager = session.getSharedContext().getIndexManager();

          if (schema.getIdentity().equals(record.getIdentity())) {
            jsonGenerator.writeFieldName(EntityHelper.ATTRIBUTE_SCHEMA_MANAGER_ENTITY);
            jsonGenerator.writeBoolean(true);
          } else if (indexManager.getIdentity().equals(record.getIdentity())) {
            jsonGenerator.writeFieldName(EntityHelper.ATTRIBUTE_INDEX_MANAGER_ENTITY);
            jsonGenerator.writeBoolean(true);
          } else {
            jsonGenerator.writeFieldName(EntityHelper.ATTRIBUTE_INTERNAL_ENTITY);
            jsonGenerator.writeBoolean(true);
          }
        }
      }

      if (formatSettings.keepTypes) {
        var fieldTypes = new HashMap<String, String>();
        for (var propertyName : entity.getPropertyNames()) {
          var type = fetchPropertyType(entity,
              propertyName, schemaClass);

          if (type != null) {
            var charType = charType(type);
            if (charType != null) {
              fieldTypes.put(propertyName, charType);
            }
          }
        }

        jsonGenerator.writeFieldName(FieldTypesString.ATTRIBUTE_FIELD_TYPES);
        jsonGenerator.writeStartObject();
        for (var entry : fieldTypes.entrySet()) {
          jsonGenerator.writeFieldName(entry.getKey());
          jsonGenerator.writeString(entry.getValue());
        }

        jsonGenerator.writeEndObject();
      }
    } else {
      if (formatSettings.includeType) {
        jsonGenerator.writeFieldName(EntityHelper.ATTRIBUTE_TYPE);
        jsonGenerator.writeString(String.valueOf((char) record.getRecordType()));
      }

      if (formatSettings.includeId) {
        jsonGenerator.writeFieldName(EntityHelper.ATTRIBUTE_RID);
        jsonGenerator.writeString(record.getIdentity().toString());
      }
    }
  }

  private static PropertyTypeInternal fetchPropertyType(
      EntityImpl entity,
      String propertyName,
      SchemaClass schemaClass
  ) {
    PropertyTypeInternal type = null;
    if (schemaClass != null) {
      var property = schemaClass.getProperty(propertyName);

      if (property != null) {
        type = PropertyTypeInternal.convertFromPublicType(property.getType());
      }
    }

    if (type == null) {
      type = PropertyTypeInternal.convertFromPublicType(entity.getPropertyType(propertyName));
    }

    if (type == null) {
      type = PropertyTypeInternal.getTypeByValue(entity.getPropertyInternal(propertyName));
    }

    return type;
  }

  @Nullable
  private static String charType(PropertyTypeInternal type) {
    return switch (type) {
      case FLOAT -> "f";
      case DECIMAL -> "c";
      case LONG -> "l";
      case BYTE -> "y";
      case BINARY -> "b";
      case DOUBLE -> "d";
      case DATE -> "a";
      case DATETIME -> "t";
      case SHORT -> "s";
      case EMBEDDEDSET -> "e";
      case EMBEDDED -> "w";
      case LINKBAG -> "g";
      case LINKLIST -> "z";
      case LINKMAP -> "m";
      case LINK -> "x";
      case LINKSET -> "n";
      default -> null;
    };
  }

  @Override
  public String toString() {
    return "jackson";
  }

  @Nullable
  private static PropertyTypeInternal determineType(
      EntityImpl entity,
      String fieldName,
      String charType,
      SchemaProperty schemaProperty) {
    PropertyTypeInternal type = null;

    if (schemaProperty != null) {
      type = PropertyTypeInternal.convertFromPublicType(schemaProperty.getType());
    }

    if (type != null) {
      return type;
    }

    type = switch (charType) {
      case "f" -> PropertyTypeInternal.FLOAT;
      case "c" -> PropertyTypeInternal.DECIMAL;
      case "l" -> PropertyTypeInternal.LONG;
      case "b" -> PropertyTypeInternal.BINARY;
      case "y" -> PropertyTypeInternal.BYTE;
      case "d" -> PropertyTypeInternal.DOUBLE;
      case "a" -> PropertyTypeInternal.DATE;
      case "t" -> PropertyTypeInternal.DATETIME;
      case "s" -> PropertyTypeInternal.SHORT;
      case "e" -> PropertyTypeInternal.EMBEDDEDSET;
      case "g" -> PropertyTypeInternal.LINKBAG;
      case "z" -> PropertyTypeInternal.LINKLIST;
      case "m" -> PropertyTypeInternal.LINKMAP;
      case "x" -> PropertyTypeInternal.LINK;
      case "n" -> PropertyTypeInternal.LINKSET;
      case "w" -> PropertyTypeInternal.EMBEDDED;
      case null -> null;
      default -> throw new IllegalArgumentException("Invalid type: " + charType);
    };

    if (type != null) {
      return type;
    }

    return entity.getPropertyTypeInternal(fieldName);
  }

  private void serializeValue(DatabaseSessionInternal session, JsonGenerator jsonGenerator,
      Object propertyValue,
      FormatSettings formatSettings)
      throws IOException {
    if (propertyValue != null) {
      switch (propertyValue) {
        case String string -> jsonGenerator.writeString(string);

        case Integer integer -> jsonGenerator.writeNumber(integer);
        case Long longValue -> jsonGenerator.writeNumber(longValue);
        case Float floatValue -> jsonGenerator.writeNumber(floatValue);
        case Double doubleValue -> jsonGenerator.writeNumber(doubleValue);
        case Short shortValue -> jsonGenerator.writeNumber(shortValue);
        case Byte byteValue -> jsonGenerator.writeNumber(byteValue);
        case BigDecimal bigDecimal -> jsonGenerator.writeNumber(bigDecimal);

        case Boolean booleanValue -> jsonGenerator.writeBoolean(booleanValue);

        case byte[] byteArray -> jsonGenerator.writeBinary(byteArray);
        case Blob blob -> serializeLink(jsonGenerator, blob.getIdentity());
        case Entity entityValue -> {
          if (entityValue.isEmbedded()) {
            recordToJson(session, entityValue, jsonGenerator, formatSettings);
          } else {
            serializeLink(jsonGenerator, entityValue.getIdentity());
          }
        }

        case RID link -> serializeLink(jsonGenerator, link);
        case EntityLinkListImpl linkList -> {
          jsonGenerator.writeStartArray();
          for (var link : linkList) {
            serializeLink(jsonGenerator, link.getIdentity());
          }
          jsonGenerator.writeEndArray();
        }
        case EntityLinkSetImpl linkSet -> {
          jsonGenerator.writeStartArray();
          for (var link : linkSet) {
            serializeLink(jsonGenerator, link.getIdentity());
          }
          jsonGenerator.writeEndArray();
        }
        case LinkBag linkBag -> {
          jsonGenerator.writeStartArray();
          for (var link : linkBag) {
            serializeLink(jsonGenerator, link.getIdentity());
          }
          jsonGenerator.writeEndArray();
        }
        case EntityLinkMapIml linkMap -> {
          jsonGenerator.writeStartObject();
          for (var entry : linkMap.entrySet()) {
            jsonGenerator.writeFieldName(entry.getKey());
            serializeLink(jsonGenerator, entry.getValue().getIdentity());
          }
          jsonGenerator.writeEndObject();
        }

        case EntityEmbeddedListImpl<?> trackedList -> {
          jsonGenerator.writeStartArray();
          for (var value : trackedList) {
            serializeValue(session, jsonGenerator, value, formatSettings);
          }
          jsonGenerator.writeEndArray();
        }
        case EntityEmbeddedSetImpl<?> trackedSet -> {
          jsonGenerator.writeStartArray();
          for (var value : trackedSet) {
            serializeValue(session, jsonGenerator, value, formatSettings);
          }
          jsonGenerator.writeEndArray();
        }
        case EntityEmbeddedMapImpl<?> trackedMap -> {
          serializeEmbeddedMap(session, jsonGenerator, formatSettings, trackedMap);
        }

        case Date date -> jsonGenerator.writeNumber(date.getTime());
        default -> throw new SerializationException(session,
            "Error on marshalling of record to JSON. Unsupported value: " + propertyValue);
      }
    } else {
      jsonGenerator.writeNull();
    }
  }

  public void serializeEmbeddedMap(DatabaseSessionInternal session,
      JsonGenerator jsonGenerator,
      Map<String, ?> trackedMap, String format) {
    try {
      final var settings = new FormatSettings(format);
      serializeEmbeddedMap(session, jsonGenerator, settings, trackedMap);
    } catch (final IOException e) {
      throw BaseException.wrapException(
          new SerializationException(session, "Error on marshalling of record to JSON"), e,
          session);
    }
  }

  private void serializeEmbeddedMap(DatabaseSessionInternal db, JsonGenerator jsonGenerator,
      FormatSettings formatSettings,
      Map<String, ?> trackedMap) throws IOException {
    jsonGenerator.writeStartObject();
    for (var entry : trackedMap.entrySet()) {
      jsonGenerator.writeFieldName(entry.getKey());
      serializeValue(db, jsonGenerator, entry.getValue(), formatSettings);
    }
    jsonGenerator.writeEndObject();
  }

  @Nullable
  private Object parseValue(
      @Nonnull DatabaseSessionInternal session,
      @Nullable final EntityImpl entity,
      @Nonnull JsonParser jsonParser,
      @Nullable PropertyTypeInternal type,
      @Nullable SchemaProperty schemaProperty
  ) throws IOException {
    var token = jsonParser.currentToken();
    return switch (token) {
      case VALUE_NULL -> null;
      case VALUE_STRING -> {
        yield switch (type) {
          case LINK -> RecordIdInternal.fromString(jsonParser.getText(), false);
          case BINARY -> {
            var text = jsonParser.getText();
            if (!text.isEmpty() && text.length() <= 3) {
              yield PropertyTypeInternal.BYTE.convert(text, null, null, session);
            }

            yield Base64.getDecoder().decode(text);
          }
          case null -> {
            var text = jsonParser.getText();
            if (!text.isEmpty() && text.charAt(0) == '#') {
              try {
                yield RecordIdInternal.fromString(text, false);
              } catch (IllegalArgumentException e) {
                yield text;
              }
            } else {
              yield text;
            }
          }
          default -> type.convert(jsonParser.getText(),
              schemaProperty != null ? PropertyTypeInternal.convertFromPublicType(
                  schemaProperty.getLinkedType()) : null,
              schemaProperty != null ? schemaProperty.getLinkedClass() : null, session);
        };
      }

      case VALUE_NUMBER_INT, VALUE_NUMBER_FLOAT -> {
        Object num = jsonParser.getNumberValue();
        if (type != null) {
          num = type.convert(num, session);
        }
        yield num;
      }
      case VALUE_FALSE -> false;
      case VALUE_TRUE -> true;

      case START_ARRAY -> switch (type) {
        case EMBEDDEDLIST -> parseEmbeddedList(session, entity, jsonParser, null, schemaProperty);
        case EMBEDDEDSET -> parseEmbeddedSet(session, entity, jsonParser, schemaProperty);

        case LINKLIST -> parseLinkList(entity, jsonParser, null);
        case LINKSET -> parseLinkSet(entity, jsonParser);
        case LINKBAG -> parseLinkBag(entity, jsonParser);

        case null -> parseAnyList(session, entity, jsonParser, schemaProperty);

        default -> throw new SerializationException(session, "Unexpected value type: " + type);
      };

      case START_OBJECT -> switch (type) {
        case EMBEDDED -> parseEmbeddedEntity(session, jsonParser, null, schemaProperty);
        case EMBEDDEDMAP -> parseEmbeddedMap(session, entity, jsonParser, null, schemaProperty);
        case LINKMAP -> parseLinkMap(entity, jsonParser, null);
        case LINK -> recordFromJson(session, null, jsonParser, false);

        case null -> parseObjectOrMap(session, entity, jsonParser, schemaProperty);

        default -> throw new SerializationException(session, "Unexpected value type: " + type);
      };

      default -> throw new SerializationException(session, "Unexpected token: " + token);
    };
  }

  private RecordElement parseAnyList(@Nonnull DatabaseSessionInternal session,
      @Nullable EntityImpl entity, @Nonnull JsonParser jsonParser,
      @Nullable SchemaProperty schemaProperty) throws IOException {

    if (jsonParser.nextToken() == JsonToken.END_ARRAY) {
      return new EntityEmbeddedListImpl<>();
    }

    var firstElem = parseValue(session, null, jsonParser, null, null);

    if (firstElem instanceof Identifiable identifiable && !(firstElem instanceof EmbeddedEntity)) {
      final var list = new EntityLinkListImpl(entity);
      list.add(identifiable);
      return parseLinkList(entity, jsonParser, list);
    } else {
      final var list = new EntityEmbeddedListImpl<>(entity);
      list.add(firstElem);
      return parseEmbeddedList(session, entity, jsonParser, list, schemaProperty);
    }
  }

  private RecordElement parseObjectOrMap(@Nonnull DatabaseSessionInternal session,
      @Nullable EntityImpl entity, @Nonnull JsonParser jsonParser,
      @Nullable SchemaProperty schemaProperty) throws IOException {
    var recordMetaData =
        parseRecordMetadata(session, jsonParser, null, null, true);

    if (recordMetaData != null) {
      if (recordMetaData.isEmbedded) {
        return parseEmbeddedEntity(session, jsonParser, recordMetaData, schemaProperty);
      }

      return createRecordFromJsonAfterMetadata(session, null, recordMetaData, jsonParser, false);
    }

    if (jsonParser.currentToken() == JsonToken.END_OBJECT) {
      // empty map
      return new EntityEmbeddedMapImpl<>(entity);
    }

    //we have read the filed name already, so we need to read the value
    var fieldName = jsonParser.currentName();
    jsonParser.nextToken();

    var value = parseValue(session, null, jsonParser, null, null);

    if (value instanceof Identifiable identifiable && !(value instanceof EmbeddedEntity)) {

      final var map = new EntityLinkMapIml(entity);
      map.put(fieldName, identifiable);

      return parseLinkMap(entity, jsonParser, map);
    } else {
      final var map = new EntityEmbeddedMapImpl<>(entity);
      map.put(fieldName, value);

      return parseEmbeddedMap(session, entity, jsonParser, map, schemaProperty);
    }
  }

  private static EntityLinkMapIml parseLinkMap(EntityImpl entity, JsonParser jsonParser,
      EntityLinkMapIml map)
      throws IOException {
    if (map == null) {
      map = new EntityLinkMapIml(entity);
    }
    while (jsonParser.nextToken() != JsonToken.END_OBJECT) {
      var fieldName = jsonParser.currentName();
      jsonParser.nextToken();
      var value = RecordIdInternal.fromString(jsonParser.getText(), false);
      map.put(fieldName, value);
    }
    return map;
  }

  @Nonnull
  private EmbeddedEntityImpl parseEmbeddedEntity(@Nonnull DatabaseSessionInternal db,
      @Nonnull JsonParser jsonParser, @Nullable RecordMetadata metadata,
      SchemaProperty schemaProperty) throws IOException {

    if (metadata == null) {
      metadata = parseRecordMetadata(db, jsonParser, null, null, true);

      if (metadata == null) {
        var linkedClass = schemaProperty != null ? schemaProperty.getLinkedClass() : null;
        metadata = new RecordMetadata(
            EntityImpl.RECORD_TYPE, null,
            linkedClass != null ? linkedClass.getName() : null,
            Collections.emptyMap(), true, null, null
        );
      }
    }

    if (!metadata.isEmbedded) {
      throw new SerializationException(db, "Expected embedded record");
    }

    EmbeddedEntityImpl embedded;
    if (metadata.className != null) {
      embedded = (EmbeddedEntityImpl) db.newEmbeddedEntity(metadata.className);
    } else {
      embedded = (EmbeddedEntityImpl) db.newEmbeddedEntity();
    }

    parseProperties(db, embedded, metadata, jsonParser);

    return embedded;
  }

  private EntityEmbeddedMapImpl<Object> parseEmbeddedMap(
      @Nonnull DatabaseSessionInternal session,
      @Nullable EntityImpl entity,
      @Nonnull JsonParser jsonParser,
      @Nullable EntityEmbeddedMapImpl<Object> map,
      @Nullable SchemaProperty schemaProperty)
      throws IOException {
    if (map == null) {
      map = new EntityEmbeddedMapImpl<>(entity);
    }

    while (jsonParser.nextToken() != JsonToken.END_OBJECT) {
      var fieldName = jsonParser.currentName();
      jsonParser.nextToken();
      var value = parseValue(
          session, null, jsonParser,
          schemaProperty != null ? PropertyTypeInternal.convertFromPublicType(
              schemaProperty.getLinkedType()) : null,
          null);
      map.put(fieldName, value);
    }

    return map;
  }

  private static EntityLinkListImpl parseLinkList(
      EntityImpl entity,
      JsonParser jsonParser,
      @Nullable EntityLinkListImpl list
  ) throws IOException {
    if (list == null) {
      list = new EntityLinkListImpl(entity);
    }

    while (jsonParser.nextToken() != JsonToken.END_ARRAY) {
      var ridText = jsonParser.getText();
      list.add(RecordIdInternal.fromString(ridText, false));
    }

    return list;
  }

  private static EntityLinkSetImpl parseLinkSet(EntityImpl entity, JsonParser jsonParser)
      throws IOException {
    var list = new EntityLinkSetImpl(entity);

    while (jsonParser.nextToken() != JsonToken.END_ARRAY) {
      var ridText = jsonParser.getText();
      list.add(RecordIdInternal.fromString(ridText, false));
    }

    return list;
  }

  private static LinkBag parseLinkBag(EntityImpl entity, JsonParser jsonParser)
      throws IOException {
    var bag = new LinkBag(entity.getSession());

    while (jsonParser.nextToken() != JsonToken.END_ARRAY) {
      var ridText = jsonParser.getText();
      bag.add(RecordIdInternal.fromString(ridText, false));
    }

    return bag;
  }

  private EntityEmbeddedListImpl<Object> parseEmbeddedList(
      DatabaseSessionInternal session,
      EntityImpl entity,
      JsonParser jsonParser,
      @Nullable EntityEmbeddedListImpl<Object> list,
      @Nullable SchemaProperty schemaProperty
  ) throws IOException {
    if (list == null) {
      list = new EntityEmbeddedListImpl<>(entity);
    }

    while (jsonParser.nextToken() != JsonToken.END_ARRAY) {
      list.add(parseValue(
          session, null, jsonParser,
          schemaProperty != null ? PropertyTypeInternal.convertFromPublicType(
              schemaProperty.getLinkedType()) : null,
          null));
    }

    return list;
  }

  private EntityEmbeddedSetImpl<Object> parseEmbeddedSet(DatabaseSessionInternal session,
      EntityImpl entity,
      JsonParser jsonParser, SchemaProperty schemaProperty) throws IOException {
    var list = new EntityEmbeddedSetImpl<>(entity);

    while (jsonParser.nextToken() != JsonToken.END_ARRAY) {
      list.add(parseValue(
          session, null, jsonParser,
          schemaProperty != null ? PropertyTypeInternal.convertFromPublicType(
              schemaProperty.getLinkedType()) : null,
          null));
    }

    return list;
  }

  public record RecordMetadata(
      byte recordType,
      @Nullable RecordIdInternal recordId,
      @Nullable String className,
      Map<String, String> fieldTypes,
      boolean isEmbedded,
      @Nullable Integer recordVersion,
      @Nullable EntityType entityType
  ) {

  }

  public enum EntityType {
    INTERNAL, PUBLIC, INDEX_MANAGER, SCHEMA_MANAGER;

    boolean isInternal() {
      return this == INTERNAL || this == INDEX_MANAGER || this == SCHEMA_MANAGER;
    }
  }

  public static class FormatSettings {

    public boolean internalRecords;
    public boolean includeType;
    public boolean includeId;
    public boolean includeClazz;
    public boolean keepTypes = true;
    public boolean markEmbeddedEntities = true;
    public boolean includeVersion = true;

    public FormatSettings(final String stringFormat) {
      if (stringFormat == null) {
        includeType = true;
        includeId = true;
        includeClazz = true;
        internalRecords = false;
      } else {
        includeType = false;
        includeId = false;
        includeClazz = false;
        keepTypes = false;
        markEmbeddedEntities = false;

        if (!stringFormat.isEmpty()) {
          final var format = stringFormat.split(",");
          for (var f : format) {
            switch (f) {
              case "type" -> includeType = true;
              case "rid" -> includeId = true;
              case "class" -> includeClazz = true;
              case "keepTypes" -> keepTypes = true;
              case "internal" -> internalRecords = true;
              case "markEmbeddedEntities" -> markEmbeddedEntities = true;
              case "version" -> includeVersion = true;
              default -> LogManager.instance().warn(this, "Unknown format option: %s. "
                      + "Expected: type, rid, class, keepTypes, internal, markEmbeddedEntities,version",
                  null, f);
            }
          }
        }
      }
    }
  }
}
