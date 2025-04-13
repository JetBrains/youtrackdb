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

import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.api.record.DBRecord;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.api.schema.SchemaProperty;
import com.jetbrains.youtrack.db.internal.common.collection.MultiCollectionIterator;
import com.jetbrains.youtrack.db.internal.common.collection.MultiValue;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseRecordThreadLocal;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.record.LinkMap;
import com.jetbrains.youtrack.db.internal.core.db.record.LinkSet;
import com.jetbrains.youtrack.db.internal.core.db.record.ridbag.RidBag;
import com.jetbrains.youtrack.db.internal.core.exception.SerializationException;
import com.jetbrains.youtrack.db.internal.core.record.RecordAbstract;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityInternalUtils;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.StringSerializerHelper;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class RecordSerializerSchemaAware2CSV extends RecordSerializerCSVAbstract {

  public static final String NAME = "ORecordDocument2csv";
  public static final RecordSerializerSchemaAware2CSV INSTANCE =
      new RecordSerializerSchemaAware2CSV();
  private static final long serialVersionUID = 1L;

  @Override
  public String toString() {
    return NAME;
  }

  @Override
  public int getCurrentVersion() {
    return 0;
  }

  @Override
  public int getMinSupportedVersion() {
    return 0;
  }

  public String getClassName(String content) {
    content = content.trim();

    if (content.length() == 0) {
      return null;
    }

    final int posFirstValue = content.indexOf(StringSerializerHelper.ENTRY_SEPARATOR);
    final int pos = content.indexOf(StringSerializerHelper.CLASS_SEPARATOR);

    if (pos > -1 && (pos < posFirstValue || posFirstValue == -1)) {
      return content.substring(0, pos);
    }

    return null;
  }

  @Override
  public RecordAbstract fromString(
      DatabaseSessionInternal db, String iContent, final RecordAbstract iRecord,
      final String[] iFields) {
    iContent = iContent.trim();

    if (iContent.length() == 0) {
      return iRecord;
    }

    // UNMARSHALL THE CLASS NAME
    final EntityImpl record = (EntityImpl) iRecord;

    int pos;
    final DatabaseSessionInternal database = DatabaseRecordThreadLocal.instance().getIfDefined();
    final int posFirstValue = iContent.indexOf(StringSerializerHelper.ENTRY_SEPARATOR);
    pos = iContent.indexOf(StringSerializerHelper.CLASS_SEPARATOR);
    if (pos > -1 && (pos < posFirstValue || posFirstValue == -1)) {
      if ((record.getIdentity().getClusterId() < 0 || database == null)) {
        EntityInternalUtils.fillClassNameIfNeeded(((EntityImpl) iRecord),
            iContent.substring(0, pos));
      }
      iContent = iContent.substring(pos + 1);
    } else {
      record.setClassNameIfExists(null);
    }

    if (iFields != null && iFields.length == 1 && iFields[0].equals("@class"))
    // ONLY THE CLASS NAME HAS BEEN REQUESTED: RETURN NOW WITHOUT UNMARSHALL THE ENTIRE RECORD
    {
      return iRecord;
    }

    final List<String> fields =
        StringSerializerHelper.smartSplit(
            iContent, StringSerializerHelper.RECORD_SEPARATOR, true, true);

    String fieldName = null;
    String fieldValue;
    PropertyType type;
    SchemaClass linkedClass;
    PropertyType linkedType;
    SchemaProperty prop;

    final Set<String> fieldSet;

    if (iFields != null && iFields.length > 0) {
      fieldSet = new HashSet<String>(iFields.length);
      Collections.addAll(fieldSet, iFields);
    } else {
      fieldSet = null;
    }

    // UNMARSHALL ALL THE FIELDS
    for (String fieldEntry : fields) {
      fieldEntry = fieldEntry.trim();
      boolean uncertainType = false;

      try {
        pos = fieldEntry.indexOf(FIELD_VALUE_SEPARATOR);
        if (pos > -1) {
          // GET THE FIELD NAME
          fieldName = fieldEntry.substring(0, pos);

          // CHECK IF THE FIELD IS REQUESTED TO BEING UNMARSHALLED
          if (fieldSet != null && !fieldSet.contains(fieldName)) {
            continue;
          }

          if (record.containsField(fieldName))
          // ALREADY UNMARSHALLED: DON'T OVERWRITE IT
          {
            continue;
          }

          // GET THE FIELD VALUE
          fieldValue = fieldEntry.length() > pos + 1 ? fieldEntry.substring(pos + 1) : null;

          boolean setFieldType = false;

          // SEARCH FOR A CONFIGURED PROPERTY
          if (EntityInternalUtils.getImmutableSchemaClass(record) != null) {
            prop = EntityInternalUtils.getImmutableSchemaClass(record)
                .getProperty(database, fieldName);
          } else {
            prop = null;
          }
          if (prop != null && prop.getType() != PropertyType.ANY) {
            // RECOGNIZED PROPERTY
            type = prop.getType();
            linkedClass = prop.getLinkedClass(db);
            linkedType = prop.getLinkedType();

          } else {
            // SCHEMA PROPERTY NOT FOUND FOR THIS FIELD: TRY TO AUTODETERMINE THE BEST TYPE
            type = record.fieldType(fieldName);
            if (type == PropertyType.ANY) {
              type = null;
            }
            if (type != null) {
              setFieldType = true;
            }
            linkedClass = null;
            linkedType = null;

            // NOT FOUND: TRY TO DETERMINE THE TYPE FROM ITS CONTENT
            if (fieldValue != null && type == null) {
              if (fieldValue.length() > 1
                  && fieldValue.charAt(0) == '"'
                  && fieldValue.charAt(fieldValue.length() - 1) == '"') {
                type = PropertyType.STRING;
              } else if (fieldValue.startsWith(StringSerializerHelper.LINKSET_PREFIX)) {
                type = PropertyType.LINKSET;
              } else if (fieldValue.charAt(0) == StringSerializerHelper.LIST_BEGIN
                  && fieldValue.charAt(fieldValue.length() - 1)
                  == StringSerializerHelper.LIST_END
                  || fieldValue.charAt(0) == StringSerializerHelper.SET_BEGIN
                  && fieldValue.charAt(fieldValue.length() - 1)
                  == StringSerializerHelper.SET_END) {
                // EMBEDDED LIST/SET
                type =
                    fieldValue.charAt(0) == StringSerializerHelper.LIST_BEGIN
                        ? PropertyType.EMBEDDEDLIST
                        : PropertyType.EMBEDDEDSET;

                final String value = fieldValue.substring(1, fieldValue.length() - 1);

                if (!value.isEmpty()) {
                  if (value.charAt(0) == StringSerializerHelper.LINK) {
                    // TODO replace with regex
                    // ASSURE ALL THE ITEMS ARE RID
                    int max = value.length();
                    boolean allLinks = true;
                    boolean checkRid = true;
                    for (int i = 0; i < max; ++i) {
                      char c = value.charAt(i);
                      if (checkRid) {
                        if (c != '#') {
                          allLinks = false;
                          break;
                        }
                        checkRid = false;
                      } else if (c == ',') {
                        checkRid = true;
                      }
                    }

                    if (allLinks) {
                      type =
                          fieldValue.charAt(0) == StringSerializerHelper.LIST_BEGIN
                              ? PropertyType.LINKLIST
                              : PropertyType.LINKSET;
                      linkedType = PropertyType.LINK;
                    }
                  } else if (value.charAt(0) == StringSerializerHelper.EMBEDDED_BEGIN) {
                    linkedType = PropertyType.EMBEDDED;
                  } else if (value.charAt(0) == StringSerializerHelper.CUSTOM_TYPE) {
                    linkedType = PropertyType.CUSTOM;
                  } else if (Character.isDigit(value.charAt(0))
                      || value.charAt(0) == '+'
                      || value.charAt(0) == '-') {
                    String[] items = value.split(",");
                    linkedType = getType(items[0]);
                  } else if (value.charAt(0) == '\'' || value.charAt(0) == '"') {
                    linkedType = PropertyType.STRING;
                  }
                } else {
                  uncertainType = true;
                }

              } else if (fieldValue.charAt(0) == StringSerializerHelper.MAP_BEGIN
                  && fieldValue.charAt(fieldValue.length() - 1)
                  == StringSerializerHelper.MAP_END) {
                type = PropertyType.EMBEDDEDMAP;
              } else if (fieldValue.charAt(0) == StringSerializerHelper.LINK) {
                type = PropertyType.LINK;
              } else if (fieldValue.charAt(0) == StringSerializerHelper.EMBEDDED_BEGIN) {
                // TEMPORARY PATCH
                if (fieldValue.startsWith("(ORIDs")) {
                  type = PropertyType.LINKSET;
                } else {
                  type = PropertyType.EMBEDDED;
                }
              } else if (fieldValue.charAt(0) == StringSerializerHelper.BAG_BEGIN) {
                type = PropertyType.LINKBAG;
              } else if (fieldValue.equals("true") || fieldValue.equals("false")) {
                type = PropertyType.BOOLEAN;
              } else {
                type = getType(fieldValue);
              }
            }
          }
          final Object value =
              fieldFromStream(db, iRecord, type, linkedClass, linkedType, fieldName, fieldValue);
          if ("@class".equals(fieldName)) {
            EntityInternalUtils.fillClassNameIfNeeded(((EntityImpl) iRecord), value.toString());
          } else {
            record.field(fieldName, value, type);
          }

          if (uncertainType) {
            record.setFieldType(fieldName, null);
          }
        }
      } catch (Exception e) {
        throw BaseException.wrapException(
            new SerializationException(
                "Error on unmarshalling field '"
                    + fieldName
                    + "' in record "
                    + iRecord.getIdentity()
                    + " with value: "
                    + fieldEntry),
            e);
      }
    }

    return iRecord;
  }

  @Override
  public byte[] toStream(DatabaseSessionInternal session, RecordAbstract iRecord) {
    final byte[] result = super.toStream(session, iRecord);
    if (result == null || result.length > 0) {
      return result;
    }

    // Fix of nasty IBM JDK bug. In case of very depth recursive graph serialization
    // EntityImpl#_source property may be initialized incorrectly.
    final EntityImpl recordSchemaAware = (EntityImpl) iRecord;
    if (recordSchemaAware.fields() > 0) {
      return null;
    }

    return result;
  }

  public byte[] writeClassOnly(DBRecord iSource) {
    final EntityImpl record = (EntityImpl) iSource;
    StringBuilder iOutput = new StringBuilder();
    if (EntityInternalUtils.getImmutableSchemaClass(record) != null) {
      iOutput.append(EntityInternalUtils.getImmutableSchemaClass(record).getStreamableName());
      iOutput.append(StringSerializerHelper.CLASS_SEPARATOR);
    }
    return iOutput.toString().getBytes(StandardCharsets.UTF_8);
  }

  @Override
  protected StringBuilder toString(
      DBRecord iRecord,
      final StringBuilder iOutput,
      final String iFormat,
      final boolean autoDetectCollectionType) {
    if (iRecord == null) {
      throw new SerializationException("Expected a record but was null");
    }

    if (!(iRecord instanceof EntityImpl record)) {
      throw new SerializationException(
          "Cannot marshall a record of type " + iRecord.getClass().getSimpleName());
    }

    if (EntityInternalUtils.getImmutableSchemaClass(record) != null) {
      iOutput.append(EntityInternalUtils.getImmutableSchemaClass(record).getStreamableName());
      iOutput.append(StringSerializerHelper.CLASS_SEPARATOR);
    }

    SchemaProperty prop;
    PropertyType type;
    SchemaClass linkedClass;
    PropertyType linkedType;
    String fieldClassName;
    int i = 0;

    final String[] fieldNames = record.fieldNames();

    // MARSHALL ALL THE FIELDS OR DELTA IF TRACKING IS ENABLED
    for (String fieldName : fieldNames) {
      Object fieldValue = record.rawField(fieldName);
      if (i > 0) {
        iOutput.append(StringSerializerHelper.RECORD_SEPARATOR);
      }

      // SEARCH FOR A CONFIGURED PROPERTY
      if (EntityInternalUtils.getImmutableSchemaClass(record) != null) {
        prop = EntityInternalUtils.getImmutableSchemaClass(record).getProperty(record.getSession(),
            fieldName);
      } else {
        prop = null;
      }
      fieldClassName = getClassName(fieldValue);

      type = record.fieldType(fieldName);
      if (type == PropertyType.ANY) {
        type = null;
      }

      linkedClass = null;
      linkedType = null;

      if (prop != null && prop.getType() != PropertyType.ANY) {
        // RECOGNIZED PROPERTY
        type = prop.getType();
        linkedClass = prop.getLinkedClass(record.getSession());
        linkedType = prop.getLinkedType();

      } else if (fieldValue != null) {
        // NOT FOUND: TRY TO DETERMINE THE TYPE FROM ITS CONTENT
        if (type == null) {
          if (fieldValue.getClass() == byte[].class) {
            type = PropertyType.BINARY;
          } else if (DatabaseRecordThreadLocal.instance().isDefined()
              && fieldValue instanceof DBRecord) {
            if (type == null)
            // DETERMINE THE FIELD TYPE
            {
              if (fieldValue instanceof EntityImpl && ((EntityImpl) fieldValue).hasOwners()) {
                type = PropertyType.EMBEDDED;
              } else {
                type = PropertyType.LINK;
              }
            }

            linkedClass = getLinkInfo(DatabaseRecordThreadLocal.instance().get(), fieldClassName);
          } else if (fieldValue instanceof RID)
          // DETERMINE THE FIELD TYPE
          {
            type = PropertyType.LINK;
          } else if (fieldValue instanceof Date) {
            type = PropertyType.DATETIME;
          } else if (fieldValue instanceof String) {
            type = PropertyType.STRING;
          } else if (fieldValue instanceof Integer || fieldValue instanceof BigInteger) {
            type = PropertyType.INTEGER;
          } else if (fieldValue instanceof Long) {
            type = PropertyType.LONG;
          } else if (fieldValue instanceof Float) {
            type = PropertyType.FLOAT;
          } else if (fieldValue instanceof Short) {
            type = PropertyType.SHORT;
          } else if (fieldValue instanceof Byte) {
            type = PropertyType.BYTE;
          } else if (fieldValue instanceof Double) {
            type = PropertyType.DOUBLE;
          } else if (fieldValue instanceof BigDecimal) {
            type = PropertyType.DECIMAL;
          } else if (fieldValue instanceof RidBag) {
            type = PropertyType.LINKBAG;
          }

          if (fieldValue instanceof MultiCollectionIterator<?>) {
            type =
                ((MultiCollectionIterator<?>) fieldValue).isEmbedded()
                    ? PropertyType.EMBEDDEDLIST
                    : PropertyType.LINKLIST;
            linkedType =
                ((MultiCollectionIterator<?>) fieldValue).isEmbedded()
                    ? PropertyType.EMBEDDED
                    : PropertyType.LINK;
          } else if (fieldValue instanceof Collection<?> || fieldValue.getClass().isArray()) {
            final int size = MultiValue.getSize(fieldValue);

            if (autoDetectCollectionType) {
              if (size > 0) {
                final Object firstValue = MultiValue.getFirstValue(fieldValue);

                if (firstValue != null) {
                  if (firstValue instanceof RID) {
                    linkedClass = null;
                    linkedType = PropertyType.LINK;
                    if (fieldValue instanceof Set<?>) {
                      type = PropertyType.LINKSET;
                    } else {
                      type = PropertyType.LINKLIST;
                    }
                  } else if (DatabaseRecordThreadLocal.instance().isDefined()
                      && (firstValue instanceof EntityImpl
                      && !((EntityImpl) firstValue).isEmbedded())
                      && (firstValue instanceof DBRecord)) {
                    linkedClass =
                        getLinkInfo(
                            DatabaseRecordThreadLocal.instance().get(), getClassName(firstValue));
                    if (type == null) {
                      // LINK: GET THE CLASS
                      linkedType = PropertyType.LINK;

                      if (fieldValue instanceof Set<?>) {
                        type = PropertyType.LINKSET;
                      } else {
                        type = PropertyType.LINKLIST;
                      }
                    } else {
                      linkedType = PropertyType.EMBEDDED;
                    }
                  } else {
                    // EMBEDDED COLLECTION
                    if (firstValue instanceof EntityImpl
                        && ((((EntityImpl) firstValue).hasOwners())
                        || type == PropertyType.EMBEDDEDSET
                        || type == PropertyType.EMBEDDEDLIST
                        || type == PropertyType.EMBEDDEDMAP)) {
                      linkedType = PropertyType.EMBEDDED;
                    } else if (firstValue instanceof Enum<?>) {
                      linkedType = PropertyType.STRING;
                    } else {
                      linkedType = PropertyType.getTypeByClass(firstValue.getClass());

                      if (linkedType != PropertyType.LINK)
                      // EMBEDDED FOR SURE DON'T USE THE LINKED TYPE
                      {
                        linkedType = null;
                      }
                    }

                    if (type == null) {
                      if (fieldValue instanceof LinkSet) {
                        type = PropertyType.LINKSET;
                      } else if (fieldValue instanceof Set<?>) {
                        type = PropertyType.EMBEDDEDSET;
                      } else {
                        type = PropertyType.EMBEDDEDLIST;
                      }
                    }
                  }
                }
              } else if (type == null) {
                type = PropertyType.EMBEDDEDLIST;
              }
            }
          } else if (fieldValue instanceof Map<?, ?> && type == null) {
            final int size = MultiValue.getSize(fieldValue);

            Boolean autoConvertLinks = null;
            if (fieldValue instanceof LinkMap) {
              autoConvertLinks = ((LinkMap) fieldValue).isAutoConvertToRecord();
              if (autoConvertLinks)
              // DISABLE AUTO CONVERT
              {
                ((LinkMap) fieldValue).setAutoConvertToRecord(false);
              }
            }

            if (size > 0) {
              final Object firstValue = MultiValue.getFirstValue(fieldValue);

              if (firstValue != null) {
                if (DatabaseRecordThreadLocal.instance().isDefined()
                    && (firstValue instanceof EntityImpl
                    && !((EntityImpl) firstValue).isEmbedded())
                    && (firstValue instanceof DBRecord)) {
                  linkedClass =
                      getLinkInfo(
                          DatabaseRecordThreadLocal.instance().get(), getClassName(firstValue));
                  // LINK: GET THE CLASS
                  linkedType = PropertyType.LINK;
                  type = PropertyType.LINKMAP;
                }
              }
            }

            if (type == null) {
              type = PropertyType.EMBEDDEDMAP;
            }

            if (fieldValue instanceof LinkMap && autoConvertLinks)
            // REPLACE PREVIOUS SETTINGS
            {
              ((LinkMap) fieldValue).setAutoConvertToRecord(true);
            }
          }
        }
      }

      if (type == PropertyType.TRANSIENT)
      // TRANSIENT FIELD
      {
        continue;
      }

      if (type == null) {
        type = PropertyType.EMBEDDED;
      }

      iOutput.append(fieldName);
      iOutput.append(FIELD_VALUE_SEPARATOR);
      fieldToStream(record, iOutput, type, linkedClass, linkedType, fieldName, fieldValue,
          true);

      i++;
    }

    return iOutput;
  }

  private String getClassName(final Object iValue) {
    if (iValue instanceof EntityImpl) {
      return ((EntityImpl) iValue).getClassName();
    }

    return iValue != null ? iValue.getClass().getSimpleName() : null;
  }

  private SchemaClass getLinkInfo(
      final DatabaseSessionInternal iDatabase, final String iFieldClassName) {
    if (iDatabase == null || iDatabase.isClosed() || iFieldClassName == null) {
      return null;
    }

    SchemaClass linkedClass =
        iDatabase.getMetadata().getImmutableSchemaSnapshot().getClass(iFieldClassName);

    return linkedClass;
  }

  @Override
  public String getName() {
    return NAME;
  }
}
