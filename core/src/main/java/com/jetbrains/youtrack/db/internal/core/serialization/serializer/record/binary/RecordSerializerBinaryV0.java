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

package com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary;

import static com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.HelperClasses.MILLISEC_PER_DAY;
import static com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.HelperClasses.NULL_RECORD_ID;
import static com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.HelperClasses.bytesFromString;
import static com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.HelperClasses.convertDayToTimezone;
import static com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.HelperClasses.getGlobalProperty;
import static com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.HelperClasses.getTypeFromValueEmbedded;
import static com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.HelperClasses.readBinary;
import static com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.HelperClasses.readByte;
import static com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.HelperClasses.readInteger;
import static com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.HelperClasses.readLinkCollection;
import static com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.HelperClasses.readLong;
import static com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.HelperClasses.readOType;
import static com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.HelperClasses.readOptimizedLink;
import static com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.HelperClasses.readString;
import static com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.HelperClasses.stringFromBytesIntern;
import static com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.HelperClasses.writeBinary;
import static com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.HelperClasses.writeLinkCollection;
import static com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.HelperClasses.writeNullLink;
import static com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.HelperClasses.writeOType;
import static com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.HelperClasses.writeOptimizedLink;
import static com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.HelperClasses.writeString;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.exception.DatabaseException;
import com.jetbrains.youtrack.db.api.exception.ValidationException;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.schema.GlobalProperty;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.api.schema.SchemaProperty;
import com.jetbrains.youtrack.db.internal.common.collection.MultiValue;
import com.jetbrains.youtrack.db.internal.common.serialization.types.DecimalSerializer;
import com.jetbrains.youtrack.db.internal.common.serialization.types.IntegerSerializer;
import com.jetbrains.youtrack.db.internal.common.serialization.types.LongSerializer;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.record.LinkList;
import com.jetbrains.youtrack.db.internal.core.db.record.LinkMap;
import com.jetbrains.youtrack.db.internal.core.db.record.LinkSet;
import com.jetbrains.youtrack.db.internal.core.db.record.RecordElement;
import com.jetbrains.youtrack.db.internal.core.db.record.TrackedList;
import com.jetbrains.youtrack.db.internal.core.db.record.TrackedMap;
import com.jetbrains.youtrack.db.internal.core.db.record.TrackedSet;
import com.jetbrains.youtrack.db.internal.core.db.record.ridbag.RidBag;
import com.jetbrains.youtrack.db.internal.core.exception.SerializationException;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.ImmutableSchema;
import com.jetbrains.youtrack.db.internal.core.metadata.security.PropertyEncryption;
import com.jetbrains.youtrack.db.internal.core.record.RecordInternal;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityEntry;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImplEmbedded;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityInternalUtils;
import com.jetbrains.youtrack.db.internal.core.serialization.DocumentSerializable;
import com.jetbrains.youtrack.db.internal.core.serialization.SerializableStream;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.HelperClasses.MapRecordInfo;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.HelperClasses.RecordInfo;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.HelperClasses.Tuple;
import com.jetbrains.youtrack.db.internal.core.util.DateHelper;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeMap;

public class RecordSerializerBinaryV0 implements EntitySerializer {

  private final BinaryComparatorV0 comparator = new BinaryComparatorV0();

  public RecordSerializerBinaryV0() {
  }

  @Override
  public BinaryComparator getComparator() {
    return comparator;
  }

  @Override
  public void deserializePartial(
      DatabaseSessionInternal db, final EntityImpl entity, final BytesContainer bytes,
      final String[] iFields) {
    final String className = readString(bytes);
    if (!className.isEmpty()) {
      EntityInternalUtils.fillClassNameIfNeeded(entity, className);
    }

    // TRANSFORMS FIELDS FOM STRINGS TO BYTE[]
    final byte[][] fields = new byte[iFields.length][];
    for (int i = 0; i < iFields.length; ++i) {
      fields[i] = bytesFromString(iFields[i]);
    }

    String fieldName = null;
    int valuePos;
    PropertyType type;
    int unmarshalledFields = 0;

    while (true) {
      final int len = VarIntSerializer.readAsInteger(bytes);

      if (len == 0) {
        // SCAN COMPLETED
        break;
      } else if (len > 0) {
        // CHECK BY FIELD NAME SIZE: THIS AVOID EVEN THE UNMARSHALLING OF FIELD NAME
        boolean match = false;
        for (int i = 0; i < iFields.length; ++i) {
          if (fields[i] != null && fields[i].length == len) {
            boolean matchField = true;
            for (int j = 0; j < len; ++j) {
              if (bytes.bytes[bytes.offset + j] != fields[i][j]) {
                matchField = false;
                break;
              }
            }
            if (matchField) {
              fieldName = iFields[i];
              bytes.skip(len);
              match = true;
              break;
            }
          }
        }

        if (!match) {
          // FIELD NOT INCLUDED: SKIP IT
          bytes.skip(len + IntegerSerializer.INT_SIZE + 1);
          continue;
        }
        Tuple<Integer, PropertyType> pointerAndType = getPointerAndTypeFromCurrentPosition(bytes);
        valuePos = pointerAndType.getFirstVal();
        type = pointerAndType.getSecondVal();
      } else {
        // LOAD GLOBAL PROPERTY BY ID
        final GlobalProperty prop = getGlobalProperty(entity, len);
        fieldName = prop.getName();

        boolean matchField = false;
        for (String f : iFields) {
          if (fieldName.equals(f)) {
            matchField = true;
            break;
          }
        }

        if (!matchField) {
          // FIELD NOT INCLUDED: SKIP IT
          bytes.skip(IntegerSerializer.INT_SIZE + (prop.getType() != PropertyType.ANY ? 0 : 1));
          continue;
        }

        valuePos = readInteger(bytes);
        if (prop.getType() != PropertyType.ANY) {
          type = prop.getType();
        } else {
          type = readOType(bytes, false);
        }
      }

      if (valuePos != 0) {
        int headerCursor = bytes.offset;
        bytes.offset = valuePos;
        final Object value = deserializeValue(db, bytes, type, entity);
        bytes.offset = headerCursor;
        EntityInternalUtils.rawField(entity, fieldName, value, type);
      } else {
        EntityInternalUtils.rawField(entity, fieldName, null, null);
      }

      if (++unmarshalledFields == iFields.length)
      // ALL REQUESTED FIELDS UNMARSHALLED: EXIT
      {
        break;
      }
    }
  }

  @Override
  public BinaryField deserializeField(
      DatabaseSession session,
      BytesContainer bytes,
      SchemaClass iClass,
      String iFieldName,
      boolean embedded,
      ImmutableSchema schema,
      PropertyEncryption encryption) {
    // SKIP CLASS NAME
    final int classNameLen = VarIntSerializer.readAsInteger(bytes);
    bytes.skip(classNameLen);

    final byte[] field = iFieldName.getBytes();

    while (true) {
      final int len = VarIntSerializer.readAsInteger(bytes);

      if (len == 0) {
        // SCAN COMPLETED, NO FIELD FOUND
        return null;
      } else if (len > 0) {
        // CHECK BY FIELD NAME SIZE: THIS AVOID EVEN THE UNMARSHALLING OF FIELD NAME
        if (iFieldName.length() == len) {
          boolean match = true;
          for (int j = 0; j < len; ++j) {
            if (bytes.bytes[bytes.offset + j] != field[j]) {
              match = false;
              break;
            }
          }

          bytes.skip(len);
          Tuple<Integer, PropertyType> pointerAndType = getPointerAndTypeFromCurrentPosition(bytes);
          final int valuePos = pointerAndType.getFirstVal();
          final PropertyType type = pointerAndType.getSecondVal();

          if (valuePos == 0) {
            return null;
          }

          if (!match) {
            continue;
          }

          if (!getComparator().isBinaryComparable(type)) {
            return null;
          }

          bytes.offset = valuePos;
          return new BinaryField(iFieldName, type, bytes, null);
        }

        // SKIP IT
        bytes.skip(len + IntegerSerializer.INT_SIZE + 1);
      } else {
        // LOAD GLOBAL PROPERTY BY ID
        final int id = (len * -1) - 1;
        final GlobalProperty prop = schema.getGlobalPropertyById(id);
        if (iFieldName.equals(prop.getName())) {
          final int valuePos = readInteger(bytes);
          final PropertyType type;
          if (prop.getType() != PropertyType.ANY) {
            type = prop.getType();
          } else {
            type = readOType(bytes, false);
          }

          if (valuePos == 0) {
            return null;
          }

          if (!getComparator().isBinaryComparable(type)) {
            return null;
          }

          bytes.offset = valuePos;

          final SchemaProperty classProp = iClass.getProperty(session, iFieldName);
          return new BinaryField(
              iFieldName, type, bytes, classProp != null ? classProp.getCollate() : null);
        }
        bytes.skip(IntegerSerializer.INT_SIZE + (prop.getType() != PropertyType.ANY ? 0 : 1));
      }
    }
  }

  public void deserializeWithClassName(DatabaseSessionInternal db, final EntityImpl entity,
      final BytesContainer bytes) {
    deserialize(db, entity, bytes);
  }

  @Override
  public void deserialize(DatabaseSessionInternal db, final EntityImpl entity,
      final BytesContainer bytes) {
    final String className = readString(bytes);
    if (!className.isEmpty()) {
      EntityInternalUtils.fillClassNameIfNeeded(entity, className);
    }

    int last = 0;
    String fieldName;
    int valuePos;
    PropertyType type;
    while (true) {
      GlobalProperty prop;
      final int len = VarIntSerializer.readAsInteger(bytes);
      if (len == 0) {
        // SCAN COMPLETED
        break;
      } else if (len > 0) {
        // PARSE FIELD NAME
        fieldName = stringFromBytesIntern(bytes.bytes, bytes.offset, len);
        bytes.skip(len);
        Tuple<Integer, PropertyType> pointerAndType = getPointerAndTypeFromCurrentPosition(bytes);
        valuePos = pointerAndType.getFirstVal();
        type = pointerAndType.getSecondVal();
      } else {
        // LOAD GLOBAL PROPERTY BY ID
        prop = getGlobalProperty(entity, len);
        fieldName = prop.getName();
        valuePos = readInteger(bytes);
        if (prop.getType() != PropertyType.ANY) {
          type = prop.getType();
        } else {
          type = readOType(bytes, false);
        }
      }

      if (EntityInternalUtils.rawContainsField(entity, fieldName)) {
        continue;
      }

      if (valuePos != 0) {
        int headerCursor = bytes.offset;
        bytes.offset = valuePos;
        final Object value = deserializeValue(db, bytes, type, entity);
        if (bytes.offset > last) {
          last = bytes.offset;
        }
        bytes.offset = headerCursor;
        EntityInternalUtils.rawField(entity, fieldName, value, type);
      } else {
        EntityInternalUtils.rawField(entity, fieldName, null, null);
      }
    }

    RecordInternal.clearSource(entity);

    if (last > bytes.offset) {
      bytes.offset = last;
    }
  }

  @Override
  public String[] getFieldNames(EntityImpl reference, final BytesContainer bytes,
      boolean embedded) {
    // SKIP CLASS NAME
    final int classNameLen = VarIntSerializer.readAsInteger(bytes);
    bytes.skip(classNameLen);

    final List<String> result = new ArrayList<>();

    String fieldName;
    while (true) {
      GlobalProperty prop;
      final int len = VarIntSerializer.readAsInteger(bytes);
      if (len == 0) {
        // SCAN COMPLETED
        break;
      } else if (len > 0) {
        // PARSE FIELD NAME
        fieldName = stringFromBytesIntern(bytes.bytes, bytes.offset, len);
        result.add(fieldName);

        // SKIP THE REST
        bytes.skip(len + IntegerSerializer.INT_SIZE + 1);
      } else {
        // LOAD GLOBAL PROPERTY BY ID
        final int id = (len * -1) - 1;
        prop = EntityInternalUtils.getGlobalPropertyById(reference, id);
        if (prop == null) {
          throw new SerializationException(
              "Missing property definition for property id '" + id + "'");
        }
        result.add(prop.getName());

        // SKIP THE REST
        bytes.skip(IntegerSerializer.INT_SIZE + (prop.getType() != PropertyType.ANY ? 0 : 1));
      }
    }

    return result.toArray(new String[result.size()]);
  }

  public void serialize(DatabaseSessionInternal session, final EntityImpl entity,
      final BytesContainer bytes) {
    ImmutableSchema schema = EntityInternalUtils.getImmutableSchema(entity);
    PropertyEncryption encryption = EntityInternalUtils.getPropertyEncryption(entity);
    serialize(session, entity, bytes, schema, encryption);
  }

  public void serialize(
      DatabaseSessionInternal session, final EntityImpl entity,
      final BytesContainer bytes,
      ImmutableSchema schema,
      PropertyEncryption encryption) {

    final SchemaClass clazz = serializeClass(entity, bytes);

    final Map<String, SchemaProperty> props = clazz != null ? clazz.propertiesMap(session) : null;

    final Set<Entry<String, EntityEntry>> fields = EntityInternalUtils.rawEntries(entity);

    final int[] pos = new int[fields.size()];

    int i = 0;

    final Entry<String, EntityEntry>[] values = new Entry[fields.size()];
    for (Entry<String, EntityEntry> entry : fields) {
      EntityEntry docEntry = entry.getValue();
      if (!docEntry.exists()) {
        continue;
      }
      if (docEntry.property == null && props != null) {
        SchemaProperty prop = props.get(entry.getKey());
        if (prop != null && docEntry.type == prop.getType()) {
          docEntry.property = prop;
        }
      }

      if (docEntry.property != null) {
        VarIntSerializer.write(bytes, (docEntry.property.getId() + 1) * -1);
        if (docEntry.property.getType() != PropertyType.ANY) {
          pos[i] = bytes.alloc(IntegerSerializer.INT_SIZE);
        } else {
          pos[i] = bytes.alloc(IntegerSerializer.INT_SIZE + 1);
        }
      } else {
        writeString(bytes, entry.getKey());
        pos[i] = bytes.alloc(IntegerSerializer.INT_SIZE + 1);
      }
      values[i] = entry;
      i++;
    }
    writeEmptyString(bytes);
    int size = i;

    for (i = 0; i < size; i++) {
      int pointer = 0;
      final Object value = values[i].getValue().value;
      if (value != null) {
        final PropertyType type = getFieldType(values[i].getValue());
        if (type == null) {
          throw new SerializationException(
              "Impossible serialize value of type "
                  + value.getClass()
                  + " with the EntityImpl binary serializer");
        }
        pointer =
            serializeValue(session,
                bytes,
                value,
                type,
                getLinkedType(session, entity, type, values[i].getKey()),
                schema, encryption);
        IntegerSerializer.INSTANCE.serializeLiteral(pointer, bytes.bytes, pos[i]);
        if (values[i].getValue().property == null
            || values[i].getValue().property.getType() == PropertyType.ANY) {
          writeOType(bytes, (pos[i] + IntegerSerializer.INT_SIZE), type);
        }
      }
    }
  }

  @Override
  public Object deserializeValue(
      DatabaseSessionInternal session, final BytesContainer bytes, final PropertyType type,
      final RecordElement owner) {
    RecordElement entity = owner;
    while (!(entity instanceof EntityImpl) && entity != null) {
      entity = entity.getOwner();
    }
    ImmutableSchema schema = EntityInternalUtils.getImmutableSchema((EntityImpl) entity);
    return deserializeValue(session, bytes, type, owner,
        true, -1, false, schema);
  }

  protected Object deserializeEmbeddedAsDocument(
      DatabaseSessionInternal db, final BytesContainer bytes, final RecordElement owner) {
    Object value = new EntityImplEmbedded();
    deserializeWithClassName(db, (EntityImpl) value, bytes);
    if (((EntityImpl) value).containsField(DocumentSerializable.CLASS_NAME)) {
      String className = ((EntityImpl) value).field(DocumentSerializable.CLASS_NAME);
      try {
        Class<?> clazz = Class.forName(className);
        DocumentSerializable newValue = (DocumentSerializable) clazz.newInstance();
        newValue.fromDocument((EntityImpl) value);
        value = newValue;
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    } else {
      EntityInternalUtils.addOwner((EntityImpl) value, owner);
    }
    return value;
  }

  protected List<MapRecordInfo> getPositionsFromEmbeddedMap(
      DatabaseSessionInternal db, final BytesContainer bytes, ImmutableSchema schema) {
    List<MapRecordInfo> retList = new ArrayList<>();

    int numberOfElements = VarIntSerializer.readAsInteger(bytes);

    for (int i = 0; i < numberOfElements; i++) {
      PropertyType keyType = readOType(bytes, false);
      String key = readString(bytes);
      int valuePos = readInteger(bytes);
      PropertyType valueType = readOType(bytes, false);
      MapRecordInfo recordInfo = new MapRecordInfo();
      recordInfo.fieldStartOffset = valuePos;
      recordInfo.fieldType = valueType;
      recordInfo.key = key;
      recordInfo.keyType = keyType;
      int currentOffset = bytes.offset;
      bytes.offset = valuePos;

      // get field length
      bytes.offset = valuePos;
      deserializeValue(db, bytes, valueType, null, true, -1, true, schema);
      recordInfo.fieldLength = bytes.offset - valuePos;

      bytes.offset = currentOffset;
      retList.add(recordInfo);
    }

    return retList;
  }

  // returns begin position and length for each value in embedded collection
  private List<RecordInfo> getPositionsFromEmbeddedCollection(
      DatabaseSessionInternal db, final BytesContainer bytes, ImmutableSchema schema) {
    List<RecordInfo> retList = new ArrayList<>();

    int numberOfElements = VarIntSerializer.readAsInteger(bytes);
    // read collection type
    readByte(bytes);

    for (int i = 0; i < numberOfElements; i++) {
      // read element

      // read data type
      PropertyType dataType = readOType(bytes, false);
      int fieldStart = bytes.offset;

      RecordInfo fieldInfo = new RecordInfo();
      fieldInfo.fieldStartOffset = fieldStart;
      fieldInfo.fieldType = dataType;

      // TODO find better way to skip data bytes;
      deserializeValue(db, bytes, dataType, null, true, -1, true, schema);
      fieldInfo.fieldLength = bytes.offset - fieldStart;
      retList.add(fieldInfo);
    }

    return retList;
  }

  protected List deserializeEmbeddedCollectionAsCollectionOfBytes(
      DatabaseSessionInternal db, final BytesContainer bytes, ImmutableSchema schema) {
    List retVal = new ArrayList();
    List<RecordInfo> fieldsInfo = getPositionsFromEmbeddedCollection(db, bytes, schema);
    for (RecordInfo fieldInfo : fieldsInfo) {
      if (fieldInfo.fieldType.isEmbedded()) {
        ResultBinary result =
            new ResultBinary(db,
                schema, bytes.bytes, fieldInfo.fieldStartOffset, fieldInfo.fieldLength, this);
        retVal.add(result);
      } else {
        int currentOffset = bytes.offset;
        bytes.offset = fieldInfo.fieldStartOffset;
        Object value = deserializeValue(db, bytes, fieldInfo.fieldType, null);
        retVal.add(value);
        bytes.offset = currentOffset;
      }
    }

    return retVal;
  }

  protected Map<String, Object> deserializeEmbeddedMapAsMapOfBytes(
      DatabaseSessionInternal db, final BytesContainer bytes, ImmutableSchema schema) {
    Map<String, Object> retVal = new TreeMap<>();
    List<MapRecordInfo> positionsWithLengths = getPositionsFromEmbeddedMap(db, bytes, schema);
    for (MapRecordInfo recordInfo : positionsWithLengths) {
      String key = recordInfo.key;
      Object value;
      if (recordInfo.fieldType != null && recordInfo.fieldType.isEmbedded()) {
        value =
            new ResultBinary(db,
                schema, bytes.bytes, recordInfo.fieldStartOffset, recordInfo.fieldLength, this);
      } else if (recordInfo.fieldStartOffset != 0) {
        int currentOffset = bytes.offset;
        bytes.offset = recordInfo.fieldStartOffset;
        value = deserializeValue(db, bytes, recordInfo.fieldType, null);
        bytes.offset = currentOffset;
      } else {
        value = null;
      }
      retVal.put(key, value);
    }
    return retVal;
  }

  protected ResultBinary deserializeEmbeddedAsBytes(
      DatabaseSessionInternal db, final BytesContainer bytes, int valueLength,
      ImmutableSchema schema) {
    int startOffset = bytes.offset;
    return new ResultBinary(db, schema, bytes.bytes, startOffset, valueLength, this);
  }

  protected Object deserializeValue(
      DatabaseSessionInternal db, final BytesContainer bytes,
      final PropertyType type,
      final RecordElement owner,
      boolean embeddedAsDocument,
      int valueLengthInBytes,
      boolean justRunThrough,
      ImmutableSchema schema) {
    if (type == null) {
      throw new DatabaseException("Invalid type value: null");
    }
    Object value = null;
    switch (type) {
      case INTEGER:
        value = VarIntSerializer.readAsInteger(bytes);
        break;
      case LONG:
        value = VarIntSerializer.readAsLong(bytes);
        break;
      case SHORT:
        value = VarIntSerializer.readAsShort(bytes);
        break;
      case STRING:
        if (justRunThrough) {
          int length = VarIntSerializer.readAsInteger(bytes);
          bytes.skip(length);
        } else {
          value = readString(bytes);
        }
        break;
      case DOUBLE:
        if (justRunThrough) {
          bytes.skip(LongSerializer.LONG_SIZE);
        } else {
          value = Double.longBitsToDouble(readLong(bytes));
        }
        break;
      case FLOAT:
        if (justRunThrough) {
          bytes.skip(IntegerSerializer.INT_SIZE);
        } else {
          value = Float.intBitsToFloat(readInteger(bytes));
        }
        break;
      case BYTE:
        if (justRunThrough) {
          bytes.offset++;
        } else {
          value = readByte(bytes);
        }
        break;
      case BOOLEAN:
        if (justRunThrough) {
          bytes.offset++;
        } else {
          value = readByte(bytes) == 1;
        }
        break;
      case DATETIME:
        if (justRunThrough) {
          VarIntSerializer.readAsLong(bytes);
        } else {
          value = new Date(VarIntSerializer.readAsLong(bytes));
        }
        break;
      case DATE:
        if (justRunThrough) {
          VarIntSerializer.readAsLong(bytes);
        } else {
          long savedTime = VarIntSerializer.readAsLong(bytes) * MILLISEC_PER_DAY;
          savedTime =
              convertDayToTimezone(
                  TimeZone.getTimeZone("GMT"), DateHelper.getDatabaseTimeZone(), savedTime);
          value = new Date(savedTime);
        }
        break;
      case EMBEDDED:
        if (embeddedAsDocument) {
          value = deserializeEmbeddedAsDocument(db, bytes, owner);
        } else {
          value = deserializeEmbeddedAsBytes(db, bytes, valueLengthInBytes, schema);
        }
        break;
      case EMBEDDEDSET:
        if (embeddedAsDocument) {
          value = readEmbeddedSet(db, bytes, owner);
        } else {
          value = deserializeEmbeddedCollectionAsCollectionOfBytes(db, bytes, schema);
        }
        break;
      case EMBEDDEDLIST:
        if (embeddedAsDocument) {
          value = readEmbeddedList(db, bytes, owner);
        } else {
          value = deserializeEmbeddedCollectionAsCollectionOfBytes(db, bytes, schema);
        }
        break;
      case LINKSET:
        LinkSet collectionSet = null;
        if (!justRunThrough) {
          collectionSet = new LinkSet(owner);
        }
        value = readLinkCollection(bytes, collectionSet, justRunThrough);
        break;
      case LINKLIST:
        LinkList collectionList = null;
        if (!justRunThrough) {
          collectionList = new LinkList(owner);
        }
        value = readLinkCollection(bytes, collectionList, justRunThrough);
        break;
      case BINARY:
        if (justRunThrough) {
          int len = VarIntSerializer.readAsInteger(bytes);
          bytes.skip(len);
        } else {
          value = readBinary(bytes);
        }
        break;
      case LINK:
        value = readOptimizedLink(bytes, justRunThrough);
        break;
      case LINKMAP:
        value = readLinkMap(db, bytes, owner, justRunThrough, schema);
        break;
      case EMBEDDEDMAP:
        if (embeddedAsDocument) {
          value = readEmbeddedMap(db, bytes, owner);
        } else {
          value = deserializeEmbeddedMapAsMapOfBytes(db, bytes, schema);
        }
        break;
      case DECIMAL:
        value = DecimalSerializer.INSTANCE.deserialize(bytes.bytes, bytes.offset);
        bytes.skip(DecimalSerializer.INSTANCE.getObjectSize(bytes.bytes, bytes.offset));
        break;
      case LINKBAG:
        RidBag bag = readRidbag(db, bytes);
        bag.setOwner(owner);
        value = bag;
        break;
      case TRANSIENT:
        break;
      case CUSTOM:
        try {
          String className = readString(bytes);
          Class<?> clazz = Class.forName(className);
          SerializableStream stream = (SerializableStream) clazz.newInstance();
          byte[] bytesRepresentation = readBinary(bytes);
          if (embeddedAsDocument) {
            stream.fromStream(bytesRepresentation);
            if (stream instanceof SerializableWrapper) {
              value = ((SerializableWrapper) stream).getSerializable();
            } else {
              value = stream;
            }
          } else {
            ResultBinary retVal =
                new ResultBinary(db, schema, bytesRepresentation, 0, bytesRepresentation.length,
                    this);
            return retVal;
          }
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
        break;
      case ANY:
        break;
    }
    return value;
  }

  protected RidBag readRidbag(DatabaseSessionInternal db, BytesContainer bytes) {
    RidBag bag = new RidBag(db);
    bag.fromStream(bytes);
    return bag;
  }

  protected SchemaClass serializeClass(final EntityImpl entity, final BytesContainer bytes) {
    final SchemaClass clazz = EntityInternalUtils.getImmutableSchemaClass(entity);
    if (clazz != null) {
      writeString(bytes, clazz.getName());
    } else {
      writeEmptyString(bytes);
    }
    return clazz;
  }

  protected int writeLinkMap(final BytesContainer bytes, final Map<Object, Identifiable> map) {
    final int fullPos = VarIntSerializer.write(bytes, map.size());
    for (Map.Entry<Object, Identifiable> entry : map.entrySet()) {
      // TODO:check skip of complex types
      // FIXME: changed to support only string key on map
      final PropertyType type = PropertyType.STRING;
      writeOType(bytes, bytes.alloc(1), type);
      writeString(bytes, entry.getKey().toString());
      if (entry.getValue() == null) {
        writeNullLink(bytes);
      } else {
        writeOptimizedLink(bytes, entry.getValue());
      }
    }
    return fullPos;
  }

  protected Map<Object, Identifiable> readLinkMap(
      DatabaseSessionInternal db, final BytesContainer bytes,
      final RecordElement owner,
      boolean justRunThrough,
      ImmutableSchema schema) {
    int size = VarIntSerializer.readAsInteger(bytes);
    LinkMap result = null;
    if (!justRunThrough) {
      result = new LinkMap(owner);
    }
    while ((size--) > 0) {
      final PropertyType keyType = readOType(bytes, justRunThrough);
      final Object key = deserializeValue(db, bytes, keyType, result, true, -1, justRunThrough,
          schema);
      final RecordId value = readOptimizedLink(bytes, justRunThrough);
      if (value.equals(NULL_RECORD_ID)) {
        result.putInternal(key, null);
      } else {
        result.putInternal(key, value);
      }
    }
    return result;
  }

  protected Object readEmbeddedMap(DatabaseSessionInternal db, final BytesContainer bytes,
      final RecordElement owner) {
    int size = VarIntSerializer.readAsInteger(bytes);
    final TrackedMap<Object> result = new TrackedMap<>(owner);

    int last = 0;
    while ((size--) > 0) {
      PropertyType keyType = readOType(bytes, false);
      Object key = deserializeValue(db, bytes, keyType, result);
      final int valuePos = readInteger(bytes);
      final PropertyType type = readOType(bytes, false);
      if (valuePos != 0) {
        int headerCursor = bytes.offset;
        bytes.offset = valuePos;
        Object value = deserializeValue(db, bytes, type, result);
        if (bytes.offset > last) {
          last = bytes.offset;
        }
        bytes.offset = headerCursor;
        result.putInternal(key, value);
      } else {
        result.putInternal(key, null);
      }
    }
    if (last > bytes.offset) {
      bytes.offset = last;
    }
    return result;
  }

  protected Collection<?> readEmbeddedSet(DatabaseSessionInternal db, final BytesContainer bytes,
      final RecordElement owner) {

    final int items = VarIntSerializer.readAsInteger(bytes);
    PropertyType type = readOType(bytes, false);

    if (type == PropertyType.ANY) {
      final TrackedSet found = new TrackedSet<>(owner);
      for (int i = 0; i < items; i++) {
        PropertyType itemType = readOType(bytes, false);
        if (itemType == PropertyType.ANY) {
          found.addInternal(null);
        } else {
          found.addInternal(deserializeValue(db, bytes, itemType, found));
        }
      }
      return found;
    }
    // TODO: manage case where type is known
    return null;
  }

  protected Collection<?> readEmbeddedList(DatabaseSessionInternal db, final BytesContainer bytes,
      final RecordElement owner) {

    final int items = VarIntSerializer.readAsInteger(bytes);
    PropertyType type = readOType(bytes, false);

    if (type == PropertyType.ANY) {
      final TrackedList found = new TrackedList<>(owner);
      for (int i = 0; i < items; i++) {
        PropertyType itemType = readOType(bytes, false);
        if (itemType == PropertyType.ANY) {
          found.addInternal(null);
        } else {
          found.addInternal(deserializeValue(db, bytes, itemType, found));
        }
      }
      return found;
    }
    // TODO: manage case where type is known
    return null;
  }

  protected PropertyType getLinkedType(DatabaseSession session, EntityImpl entity,
      PropertyType type, String key) {
    if (type != PropertyType.EMBEDDEDLIST && type != PropertyType.EMBEDDEDSET
        && type != PropertyType.EMBEDDEDMAP) {
      return null;
    }
    SchemaClass immutableClass = EntityInternalUtils.getImmutableSchemaClass(entity);
    if (immutableClass != null) {
      SchemaProperty prop = immutableClass.getProperty(session, key);
      if (prop != null) {
        return prop.getLinkedType();
      }
    }
    return null;
  }

  @SuppressWarnings("unchecked")
  @Override
  public int serializeValue(
      DatabaseSessionInternal session, final BytesContainer bytes,
      Object value,
      final PropertyType type,
      final PropertyType linkedType,
      ImmutableSchema schema,
      PropertyEncryption encryption) {
    int pointer = 0;
    switch (type) {
      case INTEGER:
      case LONG:
      case SHORT:
        pointer = VarIntSerializer.write(bytes, ((Number) value).longValue());
        break;
      case STRING:
        pointer = writeString(bytes, value.toString());
        break;
      case DOUBLE:
        long dg = Double.doubleToLongBits(((Number) value).doubleValue());
        pointer = bytes.alloc(LongSerializer.LONG_SIZE);
        LongSerializer.INSTANCE.serializeLiteral(dg, bytes.bytes, pointer);
        break;
      case FLOAT:
        int fg = Float.floatToIntBits(((Number) value).floatValue());
        pointer = bytes.alloc(IntegerSerializer.INT_SIZE);
        IntegerSerializer.INSTANCE.serializeLiteral(fg, bytes.bytes, pointer);
        break;
      case BYTE:
        pointer = bytes.alloc(1);
        bytes.bytes[pointer] = ((Number) value).byteValue();
        break;
      case BOOLEAN:
        pointer = bytes.alloc(1);
        bytes.bytes[pointer] = ((Boolean) value) ? (byte) 1 : (byte) 0;
        break;
      case DATETIME:
        if (value instanceof Number) {
          pointer = VarIntSerializer.write(bytes, ((Number) value).longValue());
        } else {
          pointer = VarIntSerializer.write(bytes, ((Date) value).getTime());
        }
        break;
      case DATE:
        long dateValue;
        if (value instanceof Number) {
          dateValue = ((Number) value).longValue();
        } else {
          dateValue = ((Date) value).getTime();
        }
        dateValue =
            convertDayToTimezone(
                DateHelper.getDatabaseTimeZone(), TimeZone.getTimeZone("GMT"), dateValue);
        pointer = VarIntSerializer.write(bytes, dateValue / MILLISEC_PER_DAY);
        break;
      case EMBEDDED:
        pointer = bytes.offset;
        if (value instanceof DocumentSerializable) {
          EntityImpl cur = ((DocumentSerializable) value).toDocument();
          cur.field(DocumentSerializable.CLASS_NAME, value.getClass().getName());
          serialize(session, cur, bytes, schema, encryption);
        } else {
          serialize(session, (EntityImpl) value, bytes, schema, encryption);
        }
        break;
      case EMBEDDEDSET:
      case EMBEDDEDLIST:
        if (value.getClass().isArray()) {
          pointer =
              writeEmbeddedCollection(session,
                  bytes, Arrays.asList(MultiValue.array(value)), linkedType, schema, encryption);
        } else {
          pointer =
              writeEmbeddedCollection(session, bytes, (Collection<?>) value, linkedType, schema,
                  encryption);
        }
        break;
      case DECIMAL:
        BigDecimal decimalValue = (BigDecimal) value;
        pointer = bytes.alloc(DecimalSerializer.INSTANCE.getObjectSize(decimalValue));
        DecimalSerializer.INSTANCE.serialize(decimalValue, bytes.bytes, pointer);
        break;
      case BINARY:
        pointer = writeBinary(bytes, (byte[]) (value));
        break;
      case LINKSET:
      case LINKLIST:
        Collection<Identifiable> ridCollection = (Collection<Identifiable>) value;
        pointer = writeLinkCollection(bytes, ridCollection);
        break;
      case LINK:
        if (!(value instanceof Identifiable)) {
          throw new ValidationException("Value '" + value + "' is not a Identifiable");
        }

        pointer = writeOptimizedLink(bytes, (Identifiable) value);
        break;
      case LINKMAP:
        pointer = writeLinkMap(bytes, (Map<Object, Identifiable>) value);
        break;
      case EMBEDDEDMAP:
        pointer = writeEmbeddedMap(session, bytes, (Map<Object, Object>) value, schema, encryption);
        break;
      case LINKBAG:
        pointer = writeRidBag(bytes, (RidBag) value);
        break;
      case CUSTOM:
        if (!(value instanceof SerializableStream)) {
          value = new SerializableWrapper((Serializable) value);
        }
        pointer = writeString(bytes, value.getClass().getName());
        writeBinary(bytes, ((SerializableStream) value).toStream());
        break;
      case TRANSIENT:
        break;
      case ANY:
        break;
    }
    return pointer;
  }

  protected int writeRidBag(BytesContainer bytes, RidBag ridbag) {
    return ridbag.toStream(bytes);
  }

  @SuppressWarnings("unchecked")
  protected int writeEmbeddedMap(
      DatabaseSessionInternal session, BytesContainer bytes,
      Map<Object, Object> map,
      ImmutableSchema schema,
      PropertyEncryption encryption) {
    final int[] pos = new int[map.size()];
    int i = 0;
    Entry<Object, Object>[] values = new Entry[map.size()];
    final int fullPos = VarIntSerializer.write(bytes, map.size());
    for (Entry<Object, Object> entry : map.entrySet()) {
      // TODO:check skip of complex types
      // FIXME: changed to support only string key on map
      PropertyType type = PropertyType.STRING;
      writeOType(bytes, bytes.alloc(1), type);
      writeString(bytes, entry.getKey().toString());
      pos[i] = bytes.alloc(IntegerSerializer.INT_SIZE + 1);
      values[i] = entry;
      i++;
    }

    for (i = 0; i < values.length; i++) {
      final Object value = values[i].getValue();
      if (value != null) {
        final PropertyType type = getTypeFromValueEmbedded(value);
        if (type == null) {
          throw new SerializationException(
              "Impossible serialize value of type "
                  + value.getClass()
                  + " with the EntityImpl binary serializer");
        }
        int pointer = serializeValue(session, bytes, value, type, null, schema, encryption);
        IntegerSerializer.INSTANCE.serializeLiteral(pointer, bytes.bytes, pos[i]);
        writeOType(bytes, (pos[i] + IntegerSerializer.INT_SIZE), type);
      } else {
        // signal for null value
        IntegerSerializer.INSTANCE.serializeLiteral(0, bytes.bytes, pos[i]);
      }
    }
    return fullPos;
  }

  protected int writeEmbeddedCollection(
      DatabaseSessionInternal session, final BytesContainer bytes,
      final Collection<?> value,
      final PropertyType linkedType,
      ImmutableSchema schema,
      PropertyEncryption encryption) {
    final int pos = VarIntSerializer.write(bytes, value.size());
    // TODO manage embedded type from schema and auto-determined.
    writeOType(bytes, bytes.alloc(1), PropertyType.ANY);
    for (Object itemValue : value) {
      // TODO:manage in a better way null entry
      if (itemValue == null) {
        writeOType(bytes, bytes.alloc(1), PropertyType.ANY);
        continue;
      }
      PropertyType type;
      if (linkedType == null || linkedType == PropertyType.ANY) {
        type = getTypeFromValueEmbedded(itemValue);
      } else {
        type = linkedType;
      }
      if (type != null) {
        writeOType(bytes, bytes.alloc(1), type);
        serializeValue(session, bytes, itemValue, type, null, schema, encryption);
      } else {
        throw new SerializationException(
            "Impossible serialize value of type "
                + value.getClass()
                + " with the EntityImpl binary serializer");
      }
    }
    return pos;
  }

  protected PropertyType getFieldType(final EntityEntry entry) {
    PropertyType type = entry.type;
    if (type == null) {
      final SchemaProperty prop = entry.property;
      if (prop != null) {
        type = prop.getType();
      }
    }
    if (type == null || PropertyType.ANY == type) {
      type = PropertyType.getTypeByValue(entry.value);
    }
    return type;
  }

  protected int writeEmptyString(final BytesContainer bytes) {
    return VarIntSerializer.write(bytes, 0);
  }

  @Override
  public boolean isSerializingClassNameByDefault() {
    return true;
  }

  protected void skipClassName(BytesContainer bytes) {
    final int classNameLen = VarIntSerializer.readAsInteger(bytes);
    bytes.skip(classNameLen);
  }

  private int getEmbeddedFieldSize(
      DatabaseSessionInternal db, BytesContainer bytes, int currentFieldDataPos, PropertyType type,
      ImmutableSchema schema) {
    int startOffset = bytes.offset;
    try {
      // try to read next position in header if exists
      int len = VarIntSerializer.readAsInteger(bytes);
      if (len != 0) {
        if (len > 0) {
          // skip name bytes
          bytes.skip(len);
        }
        int nextFieldPos = readInteger(bytes);
        return nextFieldPos - currentFieldDataPos;
      }

      // this means that this is last field so field length have to be calculated
      // by deserializing the value
      bytes.offset = currentFieldDataPos;
      deserializeValue(db, bytes, type, new EntityImpl(), true, -1, true, schema);
      int fieldDataLength = bytes.offset - currentFieldDataPos;
      return fieldDataLength;
    } finally {
      bytes.offset = startOffset;
    }
  }

  protected <RET> RET deserializeFieldTypedLoopAndReturn(
      DatabaseSessionInternal db, BytesContainer bytes, String iFieldName,
      ImmutableSchema schema) {
    final byte[] field = iFieldName.getBytes();

    while (true) {
      int len = VarIntSerializer.readAsInteger(bytes);

      if (len == 0) {
        // SCAN COMPLETED, NO FIELD FOUND
        return null;
      } else if (len > 0) {
        // CHECK BY FIELD NAME SIZE: THIS AVOID EVEN THE UNMARSHALLING OF FIELD NAME
        if (iFieldName.length() == len) {
          boolean match = true;
          for (int j = 0; j < len; ++j) {
            if (bytes.bytes[bytes.offset + j] != field[j]) {
              match = false;
              break;
            }
          }

          bytes.skip(len);
          Tuple<Integer, PropertyType> pointerAndType = getPointerAndTypeFromCurrentPosition(bytes);
          int valuePos = pointerAndType.getFirstVal();
          PropertyType type = pointerAndType.getSecondVal();

          if (valuePos == 0) {
            return null;
          }

          if (!match) {
            continue;
          }

          // find start of the next field offset so current field byte length can be calculated
          // actual field byte length is only needed for embedded fields
          int fieldDataLength = -1;
          if (type.isEmbedded()) {
            fieldDataLength = getEmbeddedFieldSize(db, bytes, valuePos, type, schema);
          }

          bytes.offset = valuePos;
          Object value = deserializeValue(db, bytes, type, null, false, fieldDataLength, false,
              schema);
          return (RET) value;
        }

        // skip Pointer and data type
        bytes.skip(len + IntegerSerializer.INT_SIZE + 1);

      } else {
        // LOAD GLOBAL PROPERTY BY ID
        final int id = (len * -1) - 1;
        final GlobalProperty prop = schema.getGlobalPropertyById(id);
        if (iFieldName.equals(prop.getName())) {
          final int valuePos = readInteger(bytes);
          PropertyType type;
          if (prop.getType() != PropertyType.ANY) {
            type = prop.getType();
          } else {
            type = readOType(bytes, false);
          }

          int fieldDataLength = -1;
          if (type.isEmbedded()) {
            fieldDataLength = getEmbeddedFieldSize(db, bytes, valuePos, type, schema);
          }

          if (valuePos == 0) {
            return null;
          }

          bytes.offset = valuePos;

          Object value = deserializeValue(db, bytes, type, null, false, fieldDataLength, false,
              schema);
          return (RET) value;
        }
        bytes.skip(IntegerSerializer.INT_SIZE + (prop.getType() != PropertyType.ANY ? 0 : 1));
      }
    }
  }

  @Override
  public <RET> RET deserializeFieldTyped(
      DatabaseSessionInternal session, BytesContainer bytes,
      String iFieldName,
      boolean isEmbedded,
      ImmutableSchema schema,
      PropertyEncryption encryption) {
    // SKIP CLASS NAME
    skipClassName(bytes);
    return deserializeFieldTypedLoopAndReturn(session, bytes, iFieldName, schema);
  }

  public static Tuple<Integer, PropertyType> getPointerAndTypeFromCurrentPosition(
      BytesContainer bytes) {
    int valuePos = readInteger(bytes);
    PropertyType type = readOType(bytes, false);
    return new Tuple<>(valuePos, type);
  }

  @Override
  public void deserializeDebug(
      DatabaseSessionInternal db, BytesContainer bytes,
      RecordSerializationDebug debugInfo,
      ImmutableSchema schema) {

    debugInfo.properties = new ArrayList<>();
    int last = 0;
    String fieldName;
    int valuePos;
    PropertyType type;
    while (true) {
      RecordSerializationDebugProperty debugProperty = new RecordSerializationDebugProperty();
      GlobalProperty prop = null;
      try {
        final int len = VarIntSerializer.readAsInteger(bytes);
        if (len != 0) {
          debugInfo.properties.add(debugProperty);
        }
        if (len == 0) {
          // SCAN COMPLETED
          break;
        } else if (len > 0) {
          // PARSE FIELD NAME
          fieldName = stringFromBytesIntern(bytes.bytes, bytes.offset, len);
          bytes.skip(len);

          Tuple<Integer, PropertyType> valuePositionAndType = getPointerAndTypeFromCurrentPosition(
              bytes);
          valuePos = valuePositionAndType.getFirstVal();
          type = valuePositionAndType.getSecondVal();
        } else {
          // LOAD GLOBAL PROPERTY BY ID
          final int id = (len * -1) - 1;
          debugProperty.globalId = id;
          prop = schema.getGlobalPropertyById(id);
          valuePos = readInteger(bytes);
          debugProperty.valuePos = valuePos;
          if (prop != null) {
            fieldName = prop.getName();
            if (prop.getType() != PropertyType.ANY) {
              type = prop.getType();
            } else {
              type = readOType(bytes, false);
            }
          } else {
            continue;
          }
        }
        debugProperty.name = fieldName;
        debugProperty.type = type;

        if (valuePos != 0) {
          int headerCursor = bytes.offset;
          bytes.offset = valuePos;
          try {
            debugProperty.value = deserializeValue(db, bytes, type, new EntityImpl());
          } catch (RuntimeException ex) {
            debugProperty.faildToRead = true;
            debugProperty.readingException = ex;
            debugProperty.failPosition = bytes.offset;
          }
          if (bytes.offset > last) {
            last = bytes.offset;
          }
          bytes.offset = headerCursor;
        } else {
          debugProperty.value = null;
        }
      } catch (RuntimeException ex) {
        debugInfo.readingFailure = true;
        debugInfo.readingException = ex;
        debugInfo.failPosition = bytes.offset;
      }
    }
  }
}
