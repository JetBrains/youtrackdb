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
package com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.string;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.api.exception.RecordNotFoundException;
import com.jetbrains.youtrack.db.api.record.Blob;
import com.jetbrains.youtrack.db.api.record.DBRecord;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.api.schema.SchemaProperty;
import com.jetbrains.youtrack.db.internal.common.collection.MultiValue;
import com.jetbrains.youtrack.db.internal.common.io.IOUtils;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.common.parser.StringParser;
import com.jetbrains.youtrack.db.internal.common.util.CommonConst;
import com.jetbrains.youtrack.db.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseRecordThreadLocal;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.record.LinkList;
import com.jetbrains.youtrack.db.internal.core.db.record.LinkSet;
import com.jetbrains.youtrack.db.internal.core.db.record.TrackedList;
import com.jetbrains.youtrack.db.internal.core.db.record.TrackedSet;
import com.jetbrains.youtrack.db.internal.core.db.record.ridbag.RidBag;
import com.jetbrains.youtrack.db.internal.core.exception.SerializationException;
import com.jetbrains.youtrack.db.internal.core.fetch.FetchHelper;
import com.jetbrains.youtrack.db.internal.core.fetch.FetchPlan;
import com.jetbrains.youtrack.db.internal.core.fetch.json.JSONFetchContext;
import com.jetbrains.youtrack.db.internal.core.fetch.json.JSONFetchListener;
import com.jetbrains.youtrack.db.internal.core.id.ChangeableRecordId;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.record.RecordAbstract;
import com.jetbrains.youtrack.db.internal.core.record.RecordInternal;
import com.jetbrains.youtrack.db.internal.core.record.RecordStringable;
import com.jetbrains.youtrack.db.internal.core.record.impl.DocumentHelper;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImplEmbedded;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityInternalUtils;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.JSONWriter;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.StringSerializerHelper;
import com.jetbrains.youtrack.db.internal.core.util.DateHelper;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.StringWriter;
import java.text.ParseException;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class RecordSerializerJSON extends RecordSerializerStringAbstract {

  public static final String NAME = "json";
  public static final RecordSerializerJSON INSTANCE = new RecordSerializerJSON();
  public static final char[] PARAMETER_SEPARATOR = new char[]{':', ','};
  public static final int INITIAL_SIZE = 5000;
  private static final Long MAX_INT = (long) Integer.MAX_VALUE;
  private static final Long MIN_INT = (long) Integer.MIN_VALUE;

  private interface CollectionItemVisitor {

    void visitItem(Object item);
  }

  public static class FormatSettings {

    public boolean includeVer;
    public boolean includeType;
    public boolean includeId;
    public boolean includeClazz;
    public boolean attribSameRow;
    public boolean alwaysFetchEmbeddedDocuments;
    public int indentLevel;
    public String fetchPlan = null;
    public boolean keepTypes = true;
    public boolean dateAsLong = false;
    public boolean prettyPrint = false;

    public FormatSettings(final String iFormat) {
      if (iFormat == null) {
        includeType = true;
        includeVer = true;
        includeId = true;
        includeClazz = true;
        attribSameRow = true;
        indentLevel = 0;
        fetchPlan = "";
        alwaysFetchEmbeddedDocuments = true;
      } else {
        includeType = false;
        includeVer = false;
        includeId = false;
        includeClazz = false;
        attribSameRow = false;
        alwaysFetchEmbeddedDocuments = false;
        indentLevel = 0;
        keepTypes = false;

        if (!iFormat.isEmpty()) {
          final String[] format = iFormat.split(",");
          for (String f : format) {
            if (f.equals("type")) {
              includeType = true;
            } else {
              if (f.equals("rid")) {
                includeId = true;
              } else {
                if (f.equals("version")) {
                  includeVer = true;
                } else {
                  if (f.equals("class")) {
                    includeClazz = true;
                  } else {
                    if (f.equals("attribSameRow")) {
                      attribSameRow = true;
                    } else {
                      if (f.startsWith("indent")) {
                        indentLevel = Integer.parseInt(f.substring(f.indexOf(':') + 1));
                      } else {
                        if (f.startsWith("fetchPlan")) {
                          fetchPlan = f.substring(f.indexOf(':') + 1);
                        } else {
                          if (f.startsWith("keepTypes")) {
                            keepTypes = true;
                          } else {
                            if (f.startsWith("alwaysFetchEmbedded")) {
                              alwaysFetchEmbeddedDocuments = true;
                            } else {
                              if (f.startsWith("dateAsLong")) {
                                dateAsLong = true;
                              } else {
                                if (f.startsWith("prettyPrint")) {
                                  prettyPrint = true;
                                } else {
                                  if (f.startsWith("graph") || f.startsWith("shallow"))
                                    // SUPPORTED IN OTHER PARTS
                                    ;
                                  else {
                                    throw new IllegalArgumentException(
                                        "Unrecognized JSON formatting option: " + f);
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
          }
        }
      }
    }
  }

  @Override
  public int getCurrentVersion() {
    return 0;
  }

  @Override
  public int getMinSupportedVersion() {
    return 0;
  }

  public RecordAbstract fromString(
      DatabaseSessionInternal db, String source, RecordAbstract record, final String[] fields,
      boolean needReload) {
    return fromString(db, source, record, fields, null, needReload);
  }

  @Override
  public RecordAbstract fromString(DatabaseSessionInternal db, String source,
      RecordAbstract record, final String[] fields) {
    return fromString(db, source, record, fields, null, false);
  }

  public RecordAbstract fromString(
      DatabaseSessionInternal db, String source,
      RecordAbstract record,
      final String[] fields,
      final String options,
      boolean needReload) {
    return fromString(db, source, record, fields, options, needReload, -1, new IntOpenHashSet());
  }

  public RecordAbstract fromString(
      DatabaseSessionInternal db, String source,
      RecordAbstract record,
      final String[] iFields,
      final String iOptions,
      boolean needReload,
      int maxRidbagSizeBeforeSkip,
      IntSet skippedPartsIndexes) {
    return this.fromStringV0(db,
        source, record, iOptions, needReload, maxRidbagSizeBeforeSkip, skippedPartsIndexes);
  }

  public RecordAbstract fromStringV0(
      DatabaseSessionInternal db, String source,
      RecordAbstract record,
      final String iOptions,
      boolean needReload,
      int maxRidbagSizeBeforeSkip,
      IntSet skippedPartsIndexes) {
    source = source.trim();
    boolean brackets = source.startsWith("{") && source.endsWith("}");

    String className = null;
    boolean noMap = false;
    if (iOptions != null) {
      final String[] format = iOptions.split(",");
      for (String f : format) {
        if (f.equalsIgnoreCase("noMap")) {
          noMap = true;
          break;
        }
      }
    }

    if (record != null)
    // RESET ALL THE FIELDS
    {
      record.clear();
    }

    final List<String> fields =
        StringSerializerHelper.smartSplit(
            source,
            PARAMETER_SEPARATOR,
            brackets ? 1 : 0,
            brackets ? (source.length() - 2) : -1,
            true,
            true,
            false,
            false,
            maxRidbagSizeBeforeSkip,
            skippedPartsIndexes,
            ' ',
            '\n',
            '\r',
            '\t');

    if (fields.size() % 2 != 0) {
      throw new SerializationException(
          "Error on unmarshalling JSON content: wrong format \""
              + source
              + "\". Use <field> : <value>");
    }

    Map<String, Character> fieldTypes = null;

    if (!fields.isEmpty()) {
      // SEARCH FOR FIELD TYPES IF ANY
      for (int i = 0; i < fields.size(); i += 2) {
        final String fieldName = IOUtils.getStringContent(fields.get(i));
        final String fieldValue = fields.get(i + 1);
        final String fieldValueAsString = IOUtils.getStringContent(fieldValue);

        if (fieldName.equals(FieldTypesString.ATTRIBUTE_FIELD_TYPES)
            && record instanceof EntityImpl) {
          fieldTypes = FieldTypesString.loadFieldTypesV0(fieldTypes, fieldValueAsString);
        } else {
          if (fieldName.equals(DocumentHelper.ATTRIBUTE_TYPE)) {
            if (record == null
                || RecordInternal.getRecordType(record) != fieldValueAsString.charAt(0)) {
              // CREATE THE RIGHT RECORD INSTANCE
              record =
                  YouTrackDBEnginesManager.instance()
                      .getRecordFactoryManager()
                      .newInstance(
                          (byte) fieldValueAsString.charAt(0),
                          new ChangeableRecordId(),
                          DatabaseRecordThreadLocal.instance().getIfDefined());
            }
          } else {
            if (needReload
                && fieldName.equals(DocumentHelper.ATTRIBUTE_RID)
                && record instanceof EntityImpl) {
              if (fieldValue != null && !fieldValue.isEmpty()) {
                try {
                  record =
                      DatabaseRecordThreadLocal.instance()
                          .get()
                          .load(new RecordId(fieldValueAsString));

                } catch (RecordNotFoundException e) {
                  // ignore
                }
              }
            } else {
              if (fieldName.equals(DocumentHelper.ATTRIBUTE_CLASS)
                  && record instanceof EntityImpl) {
                className = "null".equals(fieldValueAsString) ? null : fieldValueAsString;
                EntityInternalUtils.fillClassNameIfNeeded(((EntityImpl) record), className);
              }
            }
          }
        }
      }

      if (record == null) {
        record = new EntityImpl();
        RecordInternal.unsetDirty(record);
      }

      try {
        for (int i = 0; i < fields.size(); i += 2) {
          final String fieldName = IOUtils.getStringContent(fields.get(i));
          final String fieldValue = fields.get(i + 1);
          final String fieldValueAsString = IOUtils.getStringContent(fieldValue);

          processRecordsV0(db,
              record, fieldTypes, noMap, iOptions, fieldName, fieldValue, fieldValueAsString);
        }
        if (className != null) {
          // Trigger the default value
          ((EntityImpl) record).setClassName(className);
        }
      } catch (Exception e) {
        if (record.getIdentity().isValid()) {
          throw BaseException.wrapException(
              new SerializationException(
                  "Error on unmarshalling JSON content for record " + record.getIdentity()),
              e);
        } else {
          throw BaseException.wrapException(
              new SerializationException(
                  "Error on unmarshalling JSON content for record: " + source),
              e);
        }
      }
    }
    return record;
  }

  private void processRecordsV0(
      DatabaseSessionInternal db, RecordAbstract record,
      Map<String, Character> fieldTypes,
      boolean noMap,
      String iOptions,
      String fieldName,
      String fieldValue,
      String fieldValueAsString) {
    // RECORD ATTRIBUTES
    if (fieldName.equals(DocumentHelper.ATTRIBUTE_RID)) {
      RecordInternal.setIdentity(record, new RecordId(fieldValueAsString));
    } else {
      if (fieldName.equals(DocumentHelper.ATTRIBUTE_VERSION)) {
        RecordInternal.setVersion(record, Integer.parseInt(fieldValue));
      } else {
        if (fieldName.equals(DocumentHelper.ATTRIBUTE_CLASS)) {
        } else {
          if (fieldName.equals(DocumentHelper.ATTRIBUTE_TYPE)) {
          } else {
            if (fieldName.equals(FieldTypesString.ATTRIBUTE_FIELD_TYPES)
                && record instanceof EntityImpl) {
            } else {
              if (fieldName.equals("value") && !(record instanceof EntityImpl)) {
                // RECORD VALUE(S)
                if ("null".equals(fieldValue)) {
                  record.fromStream(CommonConst.EMPTY_BYTE_ARRAY);
                } else {
                  if (record instanceof Blob) {
                    // BYTES
                    RecordInternal.unsetDirty(record);
                    RecordInternal.fill(
                        record,
                        record.getIdentity(),
                        record.getVersion(),
                        Base64.getDecoder().decode(fieldValueAsString),
                        true);
                  } else {
                    if (record instanceof RecordStringable) {
                      ((RecordStringable) record).value(fieldValueAsString);
                    } else {
                      throw new IllegalArgumentException("unsupported type of record");
                    }
                  }
                }
              } else {
                if (record instanceof EntityImpl entity) {

                  // DETERMINE THE TYPE FROM THE SCHEMA
                  PropertyType type = determineType(entity, fieldName, db);

                  final Object v;
                  if (StringSerializerHelper.SKIPPED_VALUE.equals(fieldValue)) {
                    v = new RidBag(db);
                  } else {
                    v =
                        getValueV0(db,
                            entity,
                            fieldName,
                            fieldValue,
                            fieldValueAsString,
                            type,
                            null,
                            fieldTypes,
                            noMap, iOptions);
                  }

                  if (v != null) {
                    if (v instanceof Collection<?> && !((Collection<?>) v).isEmpty()) {
                      // CHECK IF THE COLLECTION IS EMBEDDED
                      if (type == null) {
                        // TRY TO UNDERSTAND BY FIRST ITEM
                        Object first = ((Collection<?>) v).iterator().next();
                        if (first instanceof DBRecord
                            && !((RecordAbstract) first).getIdentity().isValid()) {
                          type = v instanceof Set<?> ? PropertyType.EMBEDDEDSET
                              : PropertyType.EMBEDDEDLIST;
                        }
                      }

                      if (type != null) {
                        // TREAT IT AS EMBEDDED
                        entity.setPropertyInternal(fieldName, v, type);
                        return;
                      }
                    } else {
                      if (v instanceof Map<?, ?> && !((Map<?, ?>) v).isEmpty()) {
                        // CHECK IF THE MAP IS EMBEDDED
                        Object first = ((Map<?, ?>) v).values().iterator().next();
                        if (first instanceof DBRecord
                            && !((RecordAbstract) first).getIdentity().isValid()) {
                          entity.setProperty(fieldName, v, PropertyType.EMBEDDEDMAP);
                          return;
                        }
                      } else {
                        if (v instanceof EntityImpl && type != null && type.isLink()) {
                          String className1 = ((EntityImpl) v).getClassName();
                          if (className1 != null && !className1.isEmpty()) {
                            ((EntityImpl) v).save();
                          }
                        }
                      }
                    }
                  }

                  if (type == null && fieldTypes != null && fieldTypes.containsKey(fieldName)) {
                    type = FieldTypesString.getType(fieldValue, fieldTypes.get(fieldName));
                  }

                  if (v instanceof TrackedSet<?>) {
                    if (v instanceof LinkSet || MultiValue.getFirstValue(
                        v) instanceof Identifiable) {
                      type = PropertyType.LINKSET;
                    }
                  } else {
                    if (v instanceof TrackedList<?>) {
                      if (MultiValue.getFirstValue(v) instanceof Identifiable) {
                        type = PropertyType.LINKLIST;
                      }
                    }
                  }

                  if (type != null) {
                    entity.setPropertyInternal(fieldName, v, type);
                  } else {
                    entity.setPropertyInternal(fieldName, v);
                  }
                }
              }
            }
          }
        }
      }
    }
  }

  public void toString(DatabaseSessionInternal session, final DBRecord iRecord,
      final JSONWriter json, final String iFormat) {
    try {
      final FormatSettings settings = new FormatSettings(iFormat);

      json.beginObject();

      JSONFetchContext context = new JSONFetchContext(json, settings);
      context.writeSignature(json, iRecord);

      if (iRecord instanceof EntityImpl) {
        final FetchPlan fp = FetchHelper.buildFetchPlan(settings.fetchPlan);

        FetchHelper.fetch(iRecord, null, fp, new JSONFetchListener(), context, iFormat);
      } else {
        if (iRecord instanceof RecordStringable record) {

          // STRINGABLE
          json.writeAttribute(settings.indentLevel, true, "value", record.value());

        } else {
          if (iRecord instanceof Blob record) {
            // BYTES
            json.writeAttribute(
                settings.indentLevel,
                true,
                "value",
                Base64.getEncoder().encodeToString(((RecordAbstract) record).toStream()));
          } else {
            throw new SerializationException(
                "Error on marshalling record of type '"
                    + iRecord.getClass()
                    + "' to JSON. The record type cannot be exported to JSON");
          }
        }
      }

      json.endObject(settings.indentLevel, true);
    } catch (IOException e) {
      throw BaseException.wrapException(
          new SerializationException("Error on marshalling of record to JSON"), e);
    }
  }

  @Override
  public StringBuilder toString(
      final DatabaseSessionInternal session,
      final DBRecord record,
      final StringBuilder output,
      final String format,
      boolean autoDetectCollectionType) {
    try {
      final StringWriter buffer = new StringWriter(INITIAL_SIZE);
      final JSONWriter json = new JSONWriter(buffer, format);
      final FormatSettings settings = new FormatSettings(format);

      json.beginObject();

      final JSONFetchContext context = new JSONFetchContext(json, settings);
      context.writeSignature(json, record);

      if (record instanceof EntityImpl) {
        final FetchPlan fp = FetchHelper.buildFetchPlan(settings.fetchPlan);

        FetchHelper.fetch(record, null, fp, new JSONFetchListener(), context, format);
      } else {
        if (record instanceof RecordStringable recordStringable) {
          // STRINGABLE
          json.writeAttribute(settings.indentLevel, true, "value", recordStringable.value());
        } else {
          if (record instanceof Blob recordBlob) {
            // BYTES
            json.writeAttribute(
                settings.indentLevel,
                true,
                "value",
                Base64.getEncoder().encodeToString(((RecordAbstract) recordBlob).toStream()));
          } else {
            throw new SerializationException(
                "Error on marshalling record of type '"
                    + record.getClass()
                    + "' to JSON. The record type cannot be exported to JSON");
          }
        }
      }
      json.endObject(settings.indentLevel, true);

      output.append(buffer);
      return output;
    } catch (final IOException e) {
      throw BaseException.wrapException(
          new SerializationException("Error on marshalling of record to JSON"), e);
    }
  }

  @Override
  public String toString() {
    return NAME;
  }

  private PropertyType determineType(EntityImpl entity, String fieldName,
      DatabaseSessionInternal session) {
    PropertyType type = null;
    final SchemaClass cls = EntityInternalUtils.getImmutableSchemaClass(entity);
    if (cls != null) {
      final SchemaProperty prop = cls.getProperty(session, fieldName);
      if (prop != null) {
        type = prop.getType();
      }
    }
    return type;
  }

  private Object getValueV0(
      DatabaseSessionInternal db, final EntityImpl iRecord,
      String iFieldName,
      String iFieldValue,
      String iFieldValueAsString,
      PropertyType iType,
      PropertyType iLinkedType,
      final Map<String, Character> iFieldTypes,
      final boolean iNoMap,
      final String iOptions) {
    if (iFieldValue.equals("null")) {
      return null;
    }

    if (iFieldName != null && EntityInternalUtils.getImmutableSchemaClass(iRecord) != null) {
      final SchemaProperty p =
          EntityInternalUtils.getImmutableSchemaClass(iRecord).getProperty(db, iFieldName);
      if (p != null) {
        iType = p.getType();
        iLinkedType = p.getLinkedType();
      }
    }

    if (iType == null && iFieldTypes != null && iFieldTypes.containsKey(iFieldName)) {
      iType = FieldTypesString.getType(iFieldValue, iFieldTypes.get(iFieldName));
    }

    if (iFieldValue.startsWith("{") && iFieldValue.endsWith("}")) {
      // Json object
      return getValueAsObjectOrMapV0(db,
          iRecord, iFieldValue, iType, iLinkedType, iFieldTypes, iNoMap, iOptions);
    } else {
      if (iFieldValue.startsWith("[") && iFieldValue.endsWith("]")) {
        // Json array
        return getValueAsCollectionV0(db,
            iRecord, iFieldValue, iType, iLinkedType, iFieldTypes, iNoMap, iOptions);
      }
    }

    if (iType == null || iType == PropertyType.ANY) {
      // TRY TO DETERMINE THE CONTAINED TYPE from THE FIRST VALUE
      if (iFieldValue.charAt(0) != '\"' && iFieldValue.charAt(0) != '\'') {
        if (iFieldValue.equalsIgnoreCase("false") || iFieldValue.equalsIgnoreCase("true")) {
          iType = PropertyType.BOOLEAN;
        } else {
          Character c = null;
          if (iFieldTypes != null) {
            c = iFieldTypes.get(iFieldName);
            if (c != null) {
              iType = RecordSerializerStringAbstract.getType(iFieldValue + c);
            }
          }

          if (c == null) {
            // TRY TO AUTODETERMINE THE BEST TYPE
            if (RecordId.isA(iFieldValue)) {
              iType = PropertyType.LINK;
            } else {
              if (iFieldValue.matches(".*[.Ee].*")) {
                // DECIMAL FORMAT: DETERMINE IF DOUBLE OR FLOAT
                return Double.valueOf(IOUtils.getStringContent(iFieldValue));
                // REMOVED TRUNK to float
                // if (canBeTrunkedToFloat(v))
                // return v.floatValue();
                // else
                // return v;
              } else {
                final Long v = Long.valueOf(IOUtils.getStringContent(iFieldValue));
                // INTEGER FORMAT: DETERMINE IF DOUBLE OR FLOAT

                if (canBeTrunkedToInt(v)) {
                  return v.intValue();
                } else {
                  return v;
                }
              }
            }
          }
        }
      } else {
        if (iFieldValue.startsWith("{") && iFieldValue.endsWith("}")) {
          iType = PropertyType.EMBEDDED;
        } else {
          if (RecordId.isA(iFieldValueAsString)) {
            iType = PropertyType.LINK;
          }

          if (iFieldTypes != null) {
            Character c = iFieldTypes.get(iFieldName);
            if (c != null) {
              iType = FieldTypesString.getType(iFieldValueAsString, c);
            }
          }

          if (iType == null) {
            iType = PropertyType.STRING;
          }
        }
      }
    }

    if (iType != null) {
      switch (iType) {
        case STRING:
          return decodeJSON(iFieldValueAsString);
        case LINK:
          final int pos = iFieldValueAsString.indexOf('@');
          if (pos > -1)
          // CREATE DOCUMENT
          {
            return new EntityImpl(
                iFieldValueAsString.substring(1, pos),
                new RecordId(iFieldValueAsString.substring(pos + 1)));
          } else {
            // CREATE SIMPLE RID
            return new RecordId(iFieldValueAsString);
          }
        case EMBEDDED:
          return fromString(db, iFieldValueAsString, new EntityImplEmbedded(), null);
        case DATE:
          if (iFieldValueAsString == null || iFieldValueAsString.isEmpty()) {
            return null;
          }
          try {
            // TRY TO PARSE AS LONG
            return Long.parseLong(iFieldValueAsString);
          } catch (NumberFormatException e) {
            try {
              // TRY TO PARSE AS DATE
              return DateHelper.getDateFormatInstance().parseObject(iFieldValueAsString);
            } catch (ParseException ex) {
              LogManager.instance()
                  .error(this, "Exception is suppressed, original exception is ", e);
              throw BaseException.wrapException(
                  new SerializationException(
                      "Unable to unmarshall date (format="
                          + DateHelper.getDateFormat()
                          + ") : "
                          + iFieldValueAsString),
                  ex);
            }
          }
        case DATETIME:
          if (iFieldValueAsString == null || iFieldValueAsString.isEmpty()) {
            return null;
          }
          try {
            // TRY TO PARSE AS LONG
            return Long.parseLong(iFieldValueAsString);
          } catch (NumberFormatException e) {
            try {
              // TRY TO PARSE AS DATETIME
              return DateHelper.getDateTimeFormatInstance().parseObject(iFieldValueAsString);
            } catch (ParseException ex) {
              LogManager.instance()
                  .error(this, "Exception is suppressed, original exception is ", e);
              throw BaseException.wrapException(
                  new SerializationException(
                      "Unable to unmarshall datetime (format="
                          + DateHelper.getDateTimeFormat()
                          + ") : "
                          + iFieldValueAsString),
                  ex);
            }
          }
        case BINARY:
          return StringSerializerHelper.fieldTypeFromStream(db, iRecord, iType,
              iFieldValueAsString);
        case CUSTOM: {
          try {
            ByteArrayInputStream bais =
                new ByteArrayInputStream(Base64.getDecoder().decode(iFieldValueAsString));
            ObjectInputStream input = new ObjectInputStream(bais);
            return input.readObject();
          } catch (IOException | ClassNotFoundException e) {
            throw BaseException.wrapException(
                new SerializationException("Error on custom field deserialization"), e);
          }
        }
        default:
          return StringSerializerHelper.fieldTypeFromStream(db, iRecord, iType, iFieldValue);
      }
    }
    return iFieldValueAsString;
  }

  private boolean canBeTrunkedToInt(Long v) {
    return (v > 0) ? v.compareTo(MAX_INT) <= 0 : v.compareTo(MIN_INT) >= 0;
  }

  /**
   * OBJECT OR MAP. CHECK THE TYPE ATTRIBUTE TO KNOW IT.
   */
  private Object getValueAsObjectOrMapV0(
      DatabaseSessionInternal db, EntityImpl iRecord,
      String iFieldValue,
      PropertyType iType,
      PropertyType iLinkedType,
      Map<String, Character> iFieldTypes,
      boolean iNoMap,
      String iOptions) {
    final String[] fields =
        StringParser.getWords(iFieldValue.substring(1, iFieldValue.length() - 1), ":,", true);

    if (fields.length == 0) {
      if (iNoMap) {
        EntityImpl res = new EntityImpl();
        EntityInternalUtils.addOwner(res, iRecord);
        return res;
      } else {
        return new HashMap<String, Object>();
      }
    }

    if (iNoMap || hasTypeField(fields)) {
      return getObjectValuesAsRecordV0(db, iRecord, iFieldValue, iType, iOptions, fields);
    } else {
      return getObjectValuesAsMapV0(db,
          iRecord, iFieldValue, iLinkedType, iFieldTypes, iOptions, fields);
    }
  }

  private Object getObjectValuesAsMapV0(
      DatabaseSessionInternal db, EntityImpl iRecord,
      String iFieldValue,
      PropertyType iLinkedType,
      Map<String, Character> iFieldTypes,
      String iOptions,
      String[] fields) {
    if (fields.length % 2 == 1) {
      throw new SerializationException(
          "Bad JSON format on map. Expected pairs of field:value but received '"
              + iFieldValue
              + "'");
    }
    final Map<String, Object> embeddedMap = new LinkedHashMap<>();

    for (int i = 0; i < fields.length; i += 2) {
      String iFieldName = fields[i];
      if (iFieldName.length() >= 2) {
        iFieldName = iFieldName.substring(1, iFieldName.length() - 1);
      }
      iFieldValue = fields[i + 1];
      final String valueAsString = IOUtils.getStringContent(iFieldValue);

      embeddedMap.put(
          iFieldName,
          getValueV0(db,
              iRecord,
              null,
              iFieldValue,
              valueAsString,
              iLinkedType,
              null,
              iFieldTypes,
              false, iOptions));
    }
    return embeddedMap;
  }

  private Object getObjectValuesAsRecordV0(
      DatabaseSessionInternal db, EntityImpl iRecord, String iFieldValue, PropertyType iType,
      String iOptions, String[] fields) {
    RID rid = new RecordId(IOUtils.getStringContent(getFieldValue("@rid", fields)));
    boolean shouldReload = rid.isTemporary();

    final EntityImpl recordInternal =
        (EntityImpl) fromString(db, iFieldValue, new EntityImpl(), null, iOptions,
            shouldReload);

    if (shouldBeDeserializedAsEmbedded(recordInternal, iType)) {
      EntityInternalUtils.addOwner(recordInternal, iRecord);
    } else {
      DatabaseSession database = DatabaseRecordThreadLocal.instance().getIfDefined();

      if (rid.isPersistent() && database != null) {
        EntityImpl documentToMerge = database.load(rid);
        documentToMerge.merge(recordInternal, false, false);
        return documentToMerge;
      }
    }
    return recordInternal;
  }

  private Object getValueAsCollectionV0(
      DatabaseSessionInternal db, EntityImpl iRecord,
      String iFieldValue,
      PropertyType iType,
      PropertyType iLinkedType,
      Map<String, Character> iFieldTypes,
      boolean iNoMap,
      String iOptions) {
    // remove square brackets
    iFieldValue = iFieldValue.substring(1, iFieldValue.length() - 1);

    if (iType == PropertyType.LINKBAG) {
      final RidBag bag = new RidBag(db);

      parseRidBagV0(db,
          iRecord,
          iFieldValue,
          iType,
          iFieldTypes,
          iNoMap,
          iOptions, new CollectionItemVisitor() {
            @Override
            public void visitItem(Object item) {
              bag.add((Identifiable) item);
            }
          });

      return bag;
    } else {
      if (iType == PropertyType.LINKSET) {
        return getValueAsLinkedCollectionV0(db,
            new LinkSet(iRecord),
            iRecord,
            iFieldValue,
            iType,
            iLinkedType,
            iFieldTypes,
            iNoMap, iOptions);
      } else {
        if (iType == PropertyType.LINKLIST) {
          return getValueAsLinkedCollectionV0(db,
              new LinkList(iRecord),
              iRecord,
              iFieldValue,
              iType,
              iLinkedType,
              iFieldTypes,
              iNoMap, iOptions);
        } else {
          if (iType == PropertyType.EMBEDDEDSET) {
            return getValueAsEmbeddedCollectionV0(db,
                new TrackedSet<>(iRecord),
                iRecord,
                iFieldValue,
                iType,
                iLinkedType,
                iFieldTypes,
                iNoMap, iOptions);
          } else {
            return getValueAsEmbeddedCollectionV0(db,
                new TrackedList<>(iRecord),
                iRecord,
                iFieldValue,
                iType,
                iLinkedType,
                iFieldTypes,
                iNoMap, iOptions);
          }
        }
      }
    }
  }

  private Object getValueAsLinkedCollectionV0(
      DatabaseSessionInternal db, final Collection<Identifiable> collection,
      EntityImpl iRecord,
      String iFieldValue,
      PropertyType iType,
      PropertyType iLinkedType,
      Map<String, Character> iFieldTypes,
      boolean iNoMap,
      String iOptions) {

    parseCollectionV0(db,
        iRecord,
        iFieldValue,
        iType,
        iLinkedType,
        iFieldTypes,
        iNoMap,
        iOptions, new CollectionItemVisitor() {
          @Override
          public void visitItem(Object item) {
            collection.add((Identifiable) item);
          }
        });

    return collection;
  }

  private Object getValueAsEmbeddedCollectionV0(
      DatabaseSessionInternal db, final Collection<Object> collection,
      EntityImpl iRecord,
      String iFieldValue,
      PropertyType iType,
      PropertyType iLinkedType,
      Map<String, Character> iFieldTypes,
      boolean iNoMap,
      String iOptions) {

    parseCollectionV0(db,
        iRecord,
        iFieldValue,
        iType,
        iLinkedType,
        iFieldTypes,
        iNoMap,
        iOptions, new CollectionItemVisitor() {
          @Override
          public void visitItem(Object item) {
            collection.add(item);
          }
        });
    return collection;
  }

  private void parseRidBagV0(
      DatabaseSessionInternal db, EntityImpl iRecord,
      String iFieldValue,
      PropertyType iType,
      Map<String, Character> iFieldTypes,
      boolean iNoMap,
      String iOptions,
      CollectionItemVisitor visitor) {
    if (!iFieldValue.isEmpty()) {
      int lastCommaPosition = -1;
      for (int i = 1; i < iFieldValue.length(); i++) {
        if (iFieldValue.charAt(i) == ',' || i == iFieldValue.length() - 1) {
          String item;
          if (i == iFieldValue.length() - 1) {
            item = iFieldValue.substring(lastCommaPosition + 1);
          } else {
            item = iFieldValue.substring(lastCommaPosition + 1, i);
          }
          lastCommaPosition = i;
          final String itemValue = item.trim();
          if (itemValue.isEmpty()) {
            continue;
          }

          final Object collectionItem =
              getValueV0(db,
                  iRecord,
                  null,
                  itemValue,
                  IOUtils.getStringContent(itemValue),
                  PropertyType.LINK,
                  null,
                  iFieldTypes,
                  iNoMap, iOptions);

          // TODO: redundant in some cases, owner is already added by getValueV0 in some cases
          if (shouldBeDeserializedAsEmbedded(collectionItem, iType)) {
            EntityInternalUtils.addOwner((EntityImpl) collectionItem, iRecord);
          }
          if (collectionItem != null) {
            visitor.visitItem(collectionItem);
          }
        }
      }
    }
  }

  private void parseCollectionV0(
      DatabaseSessionInternal db, EntityImpl iRecord,
      String iFieldValue,
      PropertyType iType,
      PropertyType iLinkedType,
      Map<String, Character> iFieldTypes,
      boolean iNoMap,
      String iOptions,
      CollectionItemVisitor visitor) {
    if (!iFieldValue.isEmpty()) {
      for (String item : StringSerializerHelper.smartSplit(iFieldValue, ',')) {
        final String itemValue = item.trim();
        if (itemValue.isEmpty()) {
          continue;
        }

        final Object collectionItem =
            getValueV0(db,
                iRecord,
                null,
                itemValue,
                IOUtils.getStringContent(itemValue),
                iLinkedType,
                null,
                iFieldTypes,
                iNoMap, iOptions);

        // TODO redundant in some cases, owner is already added by getValueV0 in some cases
        if (shouldBeDeserializedAsEmbedded(collectionItem, iType)) {
          EntityInternalUtils.addOwner((EntityImpl) collectionItem, iRecord);
        }

        visitor.visitItem(collectionItem);
      }
    }
  }

  private boolean shouldBeDeserializedAsEmbedded(Object record, PropertyType iType) {
    return record instanceof EntityImpl
        && !((EntityImpl) record).getIdentity().isTemporary()
        && !((EntityImpl) record).getIdentity().isPersistent()
        && (iType == null || !iType.isLink());
  }

  private String decodeJSON(String iFieldValueAsString) {
    if (iFieldValueAsString == null) {
      return null;
    }
    StringBuilder builder = new StringBuilder(iFieldValueAsString.length());
    boolean quoting = false;
    for (char c : iFieldValueAsString.toCharArray()) {
      if (quoting) {
        if (c != '\\' && c != '\"' && c != '/') {
          builder.append('\\');
        }
        builder.append(c);
        quoting = false;
      } else {
        if (c == '\\') {
          quoting = true;
        } else {
          builder.append(c);
        }
      }
    }
    return builder.toString();
  }

  private boolean hasTypeField(final String[] fields) {
    return hasField(fields);
  }

  /**
   * Checks if given collection of fields contain field with specified name.
   *
   * @param fields collection of fields where search
   * @return true if collection contain specified field, false otherwise.
   */
  private boolean hasField(final String[] fields) {
    return getFieldValue("@type", fields) != null;
  }

  private String getFieldValue(final String field, final String[] fields) {
    String doubleQuotes = "\"" + field + "\"";
    String singleQuotes = "'" + field + "'";
    for (int i = 0; i < fields.length; i = i + 2) {
      if (fields[i].equals(doubleQuotes) || fields[i].equals(singleQuotes)) {
        return fields[i + 1];
      }
    }
    return null;
  }

  @Override
  public String getName() {
    return NAME;
  }
}
