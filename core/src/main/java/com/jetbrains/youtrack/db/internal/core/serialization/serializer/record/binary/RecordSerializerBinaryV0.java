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

import com.jetbrains.youtrack.db.api.exception.DatabaseException;
import com.jetbrains.youtrack.db.api.exception.ValidationException;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.schema.GlobalProperty;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
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
import com.jetbrains.youtrack.db.internal.core.metadata.schema.ImmutableSchema;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaImmutableClass;
import com.jetbrains.youtrack.db.internal.core.metadata.security.PropertyEncryption;
import com.jetbrains.youtrack.db.internal.core.record.RecordInternal;
import com.jetbrains.youtrack.db.internal.core.record.impl.EmbeddedEntityImpl;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityEntry;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.serialization.EntitySerializable;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.HelperClasses.MapRecordInfo;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.HelperClasses.RecordInfo;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.HelperClasses.Tuple;
import com.jetbrains.youtrack.db.internal.core.util.DateHelper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TimeZone;
import java.util.TreeMap;
import javax.annotation.Nonnull;

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
    final var className = readString(bytes);
    if (!className.isEmpty()) {
      entity.fillClassIfNeed(className);
    }

    // TRANSFORMS FIELDS FOM STRINGS TO BYTE[]
    final var fields = new byte[iFields.length][];
    for (var i = 0; i < iFields.length; ++i) {
      fields[i] = bytesFromString(iFields[i]);
    }

    String fieldName = null;
    int valuePos;
    PropertyType type;
    var unmarshalledFields = 0;

    while (true) {
      final var len = VarIntSerializer.readAsInteger(bytes);

      if (len == 0) {
        // SCAN COMPLETED
        break;
      } else if (len > 0) {
        // CHECK BY FIELD NAME SIZE: THIS AVOID EVEN THE UNMARSHALLING OF FIELD NAME
        var match = false;
        for (var i = 0; i < iFields.length; ++i) {
          if (fields[i] != null && fields[i].length == len) {
            var matchField = true;
            for (var j = 0; j < len; ++j) {
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
        var pointerAndType = getPointerAndTypeFromCurrentPosition(bytes);
        valuePos = pointerAndType.getFirstVal();
        type = pointerAndType.getSecondVal();
      } else {
        // LOAD GLOBAL PROPERTY BY ID
        final var prop = getGlobalProperty(entity, len);
        fieldName = prop.getName();

        var matchField = false;
        for (var f : iFields) {
          if (fieldName.equals(f)) {
            matchField = true;
            break;
          }
        }

        if (!matchField) {
          // FIELD NOT INCLUDED: SKIP IT
          bytes.skip(IntegerSerializer.INT_SIZE);
          continue;
        }

        valuePos = readInteger(bytes);
        type = prop.getType();
      }

      if (valuePos != 0) {
        var headerCursor = bytes.offset;
        bytes.offset = valuePos;
        final var value = deserializeValue(db, bytes, type, entity);
        bytes.offset = headerCursor;
        entity.setDeserializedPropertyInternal(fieldName, value, type);
      } else {
        entity.setDeserializedPropertyInternal(fieldName, null, null);
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
      DatabaseSessionInternal session, BytesContainer bytes,
      SchemaClass iClass,
      String iFieldName,
      boolean embedded,
      ImmutableSchema schema,
      PropertyEncryption encryption) {
    // SKIP CLASS NAME
    final var classNameLen = VarIntSerializer.readAsInteger(bytes);
    bytes.skip(classNameLen);

    final var field = iFieldName.getBytes();

    while (true) {
      final var len = VarIntSerializer.readAsInteger(bytes);

      if (len == 0) {
        // SCAN COMPLETED, NO FIELD FOUND
        return null;
      } else if (len > 0) {
        // CHECK BY FIELD NAME SIZE: THIS AVOID EVEN THE UNMARSHALLING OF FIELD NAME
        if (iFieldName.length() == len) {
          var match = true;
          for (var j = 0; j < len; ++j) {
            if (bytes.bytes[bytes.offset + j] != field[j]) {
              match = false;
              break;
            }
          }

          bytes.skip(len);
          var pointerAndType = getPointerAndTypeFromCurrentPosition(bytes);
          final int valuePos = pointerAndType.getFirstVal();
          final var type = pointerAndType.getSecondVal();

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
        final var id = (len * -1) - 1;
        final var prop = schema.getGlobalPropertyById(id);
        if (iFieldName.equals(prop.getName())) {
          final var valuePos = readInteger(bytes);
          final var type = prop.getType();

          if (valuePos == 0) {
            return null;
          }

          if (!getComparator().isBinaryComparable(type)) {
            return null;
          }

          bytes.offset = valuePos;
          final var classProp = iClass.getProperty(iFieldName);
          return new BinaryField(
              iFieldName, type, bytes, classProp != null ? classProp.getCollate() : null);
        }
        bytes.skip(IntegerSerializer.INT_SIZE);
      }
    }
  }

  public void deserializeWithClassName(DatabaseSessionInternal db, final EntityImpl entity,
      final BytesContainer bytes) {
    deserialize(db, entity, bytes);
  }

  @Override
  public void deserialize(DatabaseSessionInternal session, final EntityImpl entity,
      final BytesContainer bytes) {
    final var className = readString(bytes);
    if (!className.isEmpty()) {
      entity.fillClassIfNeed(className);
    }

    var last = 0;
    String fieldName;
    int valuePos;
    PropertyType type;
    while (true) {
      GlobalProperty prop;
      final var len = VarIntSerializer.readAsInteger(bytes);
      if (len == 0) {
        // SCAN COMPLETED
        break;
      } else if (len > 0) {
        // PARSE FIELD NAME
        fieldName = stringFromBytesIntern(session, bytes.bytes, bytes.offset, len);
        bytes.skip(len);
        var pointerAndType = getPointerAndTypeFromCurrentPosition(bytes);
        valuePos = pointerAndType.getFirstVal();
        type = pointerAndType.getSecondVal();
      } else {
        // LOAD GLOBAL PROPERTY BY ID
        prop = getGlobalProperty(entity, len);
        fieldName = prop.getName();
        valuePos = readInteger(bytes);
        type = prop.getType();
      }

      if (entity.rawContainsProperty(fieldName)) {
        continue;
      }

      if (valuePos != 0) {
        var headerCursor = bytes.offset;
        bytes.offset = valuePos;
        final var value = deserializeValue(session, bytes, type, entity);
        if (bytes.offset > last) {
          last = bytes.offset;
        }
        bytes.offset = headerCursor;
        entity.setDeserializedPropertyInternal(fieldName, value, type);
      } else {
        entity.setDeserializedPropertyInternal(fieldName, null, null);
      }
    }

    RecordInternal.clearSource(entity);

    if (last > bytes.offset) {
      bytes.offset = last;
    }
  }

  @Override
  public String[] getFieldNames(DatabaseSessionInternal session, EntityImpl reference,
      final BytesContainer bytes,
      boolean embedded) {
    // SKIP CLASS NAME
    final var classNameLen = VarIntSerializer.readAsInteger(bytes);
    bytes.skip(classNameLen);

    final List<String> result = new ArrayList<>();

    String fieldName;
    while (true) {
      GlobalProperty prop;
      final var len = VarIntSerializer.readAsInteger(bytes);
      if (len == 0) {
        // SCAN COMPLETED
        break;
      } else if (len > 0) {
        // PARSE FIELD NAME
        fieldName = stringFromBytesIntern(session, bytes.bytes, bytes.offset, len);
        result.add(fieldName);

        // SKIP THE REST
        bytes.skip(len + IntegerSerializer.INT_SIZE + 1);
      } else {
        // LOAD GLOBAL PROPERTY BY ID
        final var id = (len * -1) - 1;
        prop = reference.getGlobalPropertyById(id);
        if (prop == null) {
          throw new SerializationException(session.getDatabaseName(),
              "Missing property definition for property id '" + id + "'");
        }
        result.add(prop.getName());

        // SKIP THE REST
        bytes.skip(IntegerSerializer.INT_SIZE);
      }
    }

    return result.toArray(new String[result.size()]);
  }

  public void serialize(DatabaseSessionInternal session, final EntityImpl entity,
      final BytesContainer bytes) {
    ImmutableSchema schema = null;
    if (entity != null) {
      schema = entity.getImmutableSchema();
    }
    var encryption = entity.propertyEncryption;
    serialize(session, entity, bytes, schema, encryption);
  }

  public void serialize(
      DatabaseSessionInternal session, final EntityImpl entity,
      final BytesContainer bytes,
      ImmutableSchema schema,
      PropertyEncryption encryption) {

    final var clazz = serializeClass(session, entity, bytes);

    final var props = clazz != null ? clazz.propertiesMap() : null;

    final var fields = entity.getRawEntries();

    final var pos = new int[fields.size()];

    var i = 0;

    final Entry<String, EntityEntry>[] values = new Entry[fields.size()];
    for (var entry : fields) {
      var docEntry = entry.getValue();
      if (!docEntry.exists()) {
        continue;
      }
      if (docEntry.property == null && props != null) {
        var prop = props.get(entry.getKey());
        if (prop != null && docEntry.type == prop.getType()) {
          docEntry.property = prop;
        }
      }

      if (docEntry.property != null) {
        VarIntSerializer.write(bytes, (docEntry.property.getId() + 1) * -1);
        pos[i] = bytes.alloc(IntegerSerializer.INT_SIZE);
      } else {
        writeString(bytes, entry.getKey());
        pos[i] = bytes.alloc(IntegerSerializer.INT_SIZE + 1);
      }
      values[i] = entry;
      i++;
    }
    writeEmptyString(bytes);
    var size = i;

    for (i = 0; i < size; i++) {
      var pointer = 0;
      final var value = values[i].getValue().value;
      if (value != null) {
        final var type = getFieldType(session, values[i].getValue());
        if (type == null) {
          throw new SerializationException(session.getDatabaseName(),
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
        IntegerSerializer.serializeLiteral(pointer, bytes.bytes, pos[i]);
        if (values[i].getValue().property == null) {
          writeOType(bytes, (pos[i] + IntegerSerializer.INT_SIZE), type);
        }
      }
    }
  }

  @Override
  public Object deserializeValue(
      DatabaseSessionInternal db, final BytesContainer bytes, final PropertyType type,
      final RecordElement owner) {
    var entity = owner;
    while (!(entity instanceof EntityImpl) && entity != null) {
      entity = entity.getOwner();
    }
    ImmutableSchema schema = null;
    if (entity != null) {
      schema = ((EntityImpl) entity).getImmutableSchema();
    }
    return deserializeValue(db, bytes, type, owner,
        true, -1, false, schema);
  }

  protected Object deserializeEmbeddedAsDocument(
      DatabaseSessionInternal db, final BytesContainer bytes, final RecordElement owner) {
    Object value = new EmbeddedEntityImpl(db);
    deserializeWithClassName(db, (EntityImpl) value, bytes);
    if (((EntityImpl) value).hasProperty(EntitySerializable.CLASS_NAME)) {
      String className = ((EntityImpl) value).getProperty(EntitySerializable.CLASS_NAME);
      try {
        var clazz = Class.forName(className);
        var newValue = (EntitySerializable) clazz.newInstance();
        newValue.fromDocument((EntityImpl) value);
        value = newValue;
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    } else {
      ((EntityImpl) value).setOwner(owner);
    }
    return value;
  }

  protected List<MapRecordInfo> getPositionsFromEmbeddedMap(
      DatabaseSessionInternal db, final BytesContainer bytes, ImmutableSchema schema) {
    List<MapRecordInfo> retList = new ArrayList<>();

    var numberOfElements = VarIntSerializer.readAsInteger(bytes);

    for (var i = 0; i < numberOfElements; i++) {
      var keyType = readOType(bytes, false);
      var key = readString(bytes);
      var valuePos = readInteger(bytes);
      var valueType = readOType(bytes, false);
      var recordInfo = new MapRecordInfo();
      recordInfo.fieldStartOffset = valuePos;
      recordInfo.fieldType = valueType;
      recordInfo.key = key;
      recordInfo.keyType = keyType;
      var currentOffset = bytes.offset;
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

    var numberOfElements = VarIntSerializer.readAsInteger(bytes);
    // read collection type
    readByte(bytes);

    for (var i = 0; i < numberOfElements; i++) {
      // read element

      // read data type
      var dataType = readOType(bytes, false);
      var fieldStart = bytes.offset;

      var fieldInfo = new RecordInfo();
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
    var fieldsInfo = getPositionsFromEmbeddedCollection(db, bytes, schema);
    for (var fieldInfo : fieldsInfo) {
      if (fieldInfo.fieldType.isEmbedded()) {
        var result =
            new ResultBinary(db,
                schema, bytes.bytes, fieldInfo.fieldStartOffset, fieldInfo.fieldLength, this);
        retVal.add(result);
      } else {
        var currentOffset = bytes.offset;
        bytes.offset = fieldInfo.fieldStartOffset;
        var value = deserializeValue(db, bytes, fieldInfo.fieldType, null);
        retVal.add(value);
        bytes.offset = currentOffset;
      }
    }

    return retVal;
  }

  protected Map<String, Object> deserializeEmbeddedMapAsMapOfBytes(
      DatabaseSessionInternal db, final BytesContainer bytes, ImmutableSchema schema) {
    Map<String, Object> retVal = new TreeMap<>();
    var positionsWithLengths = getPositionsFromEmbeddedMap(db, bytes, schema);
    for (var recordInfo : positionsWithLengths) {
      var key = recordInfo.key;
      Object value;
      if (recordInfo.fieldType != null && recordInfo.fieldType.isEmbedded()) {
        value =
            new ResultBinary(db,
                schema, bytes.bytes, recordInfo.fieldStartOffset, recordInfo.fieldLength, this);
      } else if (recordInfo.fieldStartOffset != 0) {
        var currentOffset = bytes.offset;
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
    var startOffset = bytes.offset;
    return new ResultBinary(db, schema, bytes.bytes, startOffset, valueLength, this);
  }

  protected Object deserializeValue(
      DatabaseSessionInternal session, final BytesContainer bytes,
      final PropertyType type,
      final RecordElement owner,
      boolean embeddedAsDocument,
      int valueLengthInBytes,
      boolean justRunThrough,
      ImmutableSchema schema) {
    if (type == null) {
      throw new DatabaseException(session.getDatabaseName(), "Invalid type value: null");
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
          var length = VarIntSerializer.readAsInteger(bytes);
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
          var savedTime = VarIntSerializer.readAsLong(bytes) * MILLISEC_PER_DAY;
          savedTime =
              convertDayToTimezone(
                  TimeZone.getTimeZone("GMT"), DateHelper.getDatabaseTimeZone(session), savedTime);
          value = new Date(savedTime);
        }
        break;
      case EMBEDDED:
        if (embeddedAsDocument) {
          value = deserializeEmbeddedAsDocument(session, bytes, owner);
        } else {
          value = deserializeEmbeddedAsBytes(session, bytes, valueLengthInBytes, schema);
        }
        break;
      case EMBEDDEDSET:
        if (embeddedAsDocument) {
          value = readEmbeddedSet(session, bytes, owner);
        } else {
          value = deserializeEmbeddedCollectionAsCollectionOfBytes(session, bytes, schema);
        }
        break;
      case EMBEDDEDLIST:
        if (embeddedAsDocument) {
          value = readEmbeddedList(session, bytes, owner);
        } else {
          value = deserializeEmbeddedCollectionAsCollectionOfBytes(session, bytes, schema);
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
          var len = VarIntSerializer.readAsInteger(bytes);
          bytes.skip(len);
        } else {
          value = readBinary(bytes);
        }
        break;
      case LINK:
        value = readOptimizedLink(bytes, justRunThrough);
        break;
      case LINKMAP:
        value = readLinkMap(session, bytes, owner, justRunThrough, schema);
        break;
      case EMBEDDEDMAP:
        if (embeddedAsDocument) {
          value = readEmbeddedMap(session, bytes, owner);
        } else {
          value = deserializeEmbeddedMapAsMapOfBytes(session, bytes, schema);
        }
        break;
      case DECIMAL:
        value = DecimalSerializer.staticDeserialize(bytes.bytes, bytes.offset);
        bytes.skip(DecimalSerializer.staticGetObjectSize(bytes.bytes, bytes.offset));
        break;
      case LINKBAG:
        var bag = readRidbag(session, bytes);
        bag.setOwner(owner);
        value = bag;
        break;
    }
    return value;
  }

  protected static RidBag readRidbag(DatabaseSessionInternal session, BytesContainer bytes) {
    var bag = new RidBag(session);
    bag.fromStream(session, bytes);
    return bag;
  }

  protected static SchemaClass serializeClass(DatabaseSessionInternal session,
      final EntityImpl entity,
      final BytesContainer bytes) {
    SchemaImmutableClass result = null;
    if (entity != null) {
      result = entity.getImmutableSchemaClass(session);
    }
    final SchemaClass clazz = result;
    if (clazz != null) {
      writeString(bytes, clazz.getName());
    } else {
      writeEmptyString(bytes);
    }
    return clazz;
  }

  protected static int writeLinkMap(DatabaseSessionInternal db, final BytesContainer bytes,
      final Map<Object, Identifiable> map) {
    final var fullPos = VarIntSerializer.write(bytes, map.size());
    for (var entry : map.entrySet()) {
      final var type = PropertyType.STRING;
      writeOType(bytes, bytes.alloc(1), type);
      writeString(bytes, entry.getKey().toString());
      if (entry.getValue() == null) {
        writeNullLink(bytes);
      } else {
        writeOptimizedLink(db, bytes, entry.getValue());
      }
    }
    return fullPos;
  }

  protected Map<String, Identifiable> readLinkMap(
      DatabaseSessionInternal db, final BytesContainer bytes,
      final RecordElement owner,
      boolean justRunThrough,
      ImmutableSchema schema) {
    var size = VarIntSerializer.readAsInteger(bytes);
    LinkMap result = null;
    if (!justRunThrough) {
      result = new LinkMap(owner);
    }
    while ((size--) > 0) {
      final var keyType = readOType(bytes, justRunThrough);
      final var key = deserializeValue(db, bytes, keyType, result, true, -1, justRunThrough,
          schema);
      final var value = readOptimizedLink(bytes, justRunThrough);
      if (value.equals(NULL_RECORD_ID)) {
        result.putInternal(key.toString(), null);
      } else {
        result.putInternal(key.toString(), value);
      }
    }
    return result;
  }

  protected Object readEmbeddedMap(DatabaseSessionInternal db, final BytesContainer bytes,
      final RecordElement owner) {
    var size = VarIntSerializer.readAsInteger(bytes);
    final var result = new TrackedMap<>(owner);

    var last = 0;
    while ((size--) > 0) {
      var keyType = readOType(bytes, false);
      var key = deserializeValue(db, bytes, keyType, result);
      final var valuePos = readInteger(bytes);
      final var type = readOType(bytes, false);
      if (valuePos != 0) {
        var headerCursor = bytes.offset;
        bytes.offset = valuePos;
        var value = deserializeValue(db, bytes, type, result);
        if (bytes.offset > last) {
          last = bytes.offset;
        }
        bytes.offset = headerCursor;
        result.putInternal(key.toString(), value);
      } else {
        result.putInternal(key.toString(), null);
      }
    }
    if (last > bytes.offset) {
      bytes.offset = last;
    }
    return result;
  }

  protected Collection<?> readEmbeddedSet(DatabaseSessionInternal db, final BytesContainer bytes,
      final RecordElement owner) {

    final var items = VarIntSerializer.readAsInteger(bytes);
    var type = readOType(bytes, false);

    if (type != null) {
      final var found = new TrackedSet<>(owner);
      for (var i = 0; i < items; i++) {
        var itemType = readOType(bytes, false);
        if (itemType == null) {
          found.addInternal(null);
        } else {
          found.addInternal(deserializeValue(db, bytes, itemType, found));
        }
      }
      return found;
    }

    return null;
  }

  protected Collection<?> readEmbeddedList(DatabaseSessionInternal db, final BytesContainer bytes,
      final RecordElement owner) {

    final var items = VarIntSerializer.readAsInteger(bytes);
    var type = readOType(bytes, false);
    if (type == null) {
      return null;
    }

    final var found = new TrackedList<>(owner);
    for (var i = 0; i < items; i++) {
      var itemType = readOType(bytes, false);
      if (itemType == null) {
        found.addInternal(null);
      } else {
        found.addInternal(deserializeValue(db, bytes, itemType, found));
      }
    }

    return found;
  }

  protected static PropertyType getLinkedType(DatabaseSessionInternal session, EntityImpl entity,
      PropertyType type, String key) {
    if (type != PropertyType.EMBEDDEDLIST && type != PropertyType.EMBEDDEDSET
        && type != PropertyType.EMBEDDEDMAP) {
      return null;
    }
    SchemaImmutableClass result = null;
    if (entity != null) {
      result = entity.getImmutableSchemaClass(session);
    }
    SchemaClass immutableClass = result;
    if (immutableClass != null) {
      var prop = immutableClass.getProperty(key);
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
    var pointer = 0;
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
        var dg = Double.doubleToLongBits(((Number) value).doubleValue());
        pointer = bytes.alloc(LongSerializer.LONG_SIZE);
        LongSerializer.serializeLiteral(dg, bytes.bytes, pointer);
        break;
      case FLOAT:
        var fg = Float.floatToIntBits(((Number) value).floatValue());
        pointer = bytes.alloc(IntegerSerializer.INT_SIZE);
        IntegerSerializer.serializeLiteral(fg, bytes.bytes, pointer);
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
                DateHelper.getDatabaseTimeZone(session), TimeZone.getTimeZone("GMT"), dateValue);
        pointer = VarIntSerializer.write(bytes, dateValue / MILLISEC_PER_DAY);
        break;
      case EMBEDDED:
        pointer = bytes.offset;
        if (value instanceof EntitySerializable) {
          var cur = ((EntitySerializable) value).toEntity(session);
          cur.setProperty(EntitySerializable.CLASS_NAME, value.getClass().getName());
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
        var decimalValue = (BigDecimal) value;
        pointer = bytes.alloc(DecimalSerializer.staticGetObjectSize(decimalValue));
        DecimalSerializer.staticSerialize(decimalValue, bytes.bytes, pointer);
        break;
      case BINARY:
        pointer = writeBinary(bytes, (byte[]) (value));
        break;
      case LINKSET:
      case LINKLIST:
        var ridCollection = (Collection<Identifiable>) value;
        pointer = writeLinkCollection(session, bytes, ridCollection);
        break;
      case LINK:
        if (!(value instanceof Identifiable)) {
          throw new ValidationException(session.getDatabaseName(),
              "Value '" + value + "' is not a Identifiable");
        }

        pointer = writeOptimizedLink(session, bytes, (Identifiable) value);
        break;
      case LINKMAP:
        pointer = writeLinkMap(session, bytes, (Map<Object, Identifiable>) value);
        break;
      case EMBEDDEDMAP:
        pointer = writeEmbeddedMap(session, bytes, (Map<Object, Object>) value, schema, encryption);
        break;
      case LINKBAG:
        pointer = writeRidBag(session, bytes, (RidBag) value);
        break;
    }
    return pointer;
  }

  protected int writeRidBag(DatabaseSessionInternal db, BytesContainer bytes, RidBag ridbag) {
    return ridbag.toStream(db, bytes);
  }

  @SuppressWarnings("unchecked")
  protected int writeEmbeddedMap(
      DatabaseSessionInternal session, BytesContainer bytes,
      Map<Object, Object> map,
      ImmutableSchema schema,
      PropertyEncryption encryption) {
    final var pos = new int[map.size()];
    var i = 0;
    Entry<Object, Object>[] values = new Entry[map.size()];
    final var fullPos = VarIntSerializer.write(bytes, map.size());
    for (var entry : map.entrySet()) {
      var type = PropertyType.STRING;
      writeOType(bytes, bytes.alloc(1), type);
      writeString(bytes, entry.getKey().toString());
      pos[i] = bytes.alloc(IntegerSerializer.INT_SIZE + 1);
      values[i] = entry;
      i++;
    }

    for (i = 0; i < values.length; i++) {
      final var value = values[i].getValue();
      if (value != null) {
        final var type = getTypeFromValueEmbedded(value);
        if (type == null) {
          throw new SerializationException(session.getDatabaseName(),
              "Impossible serialize value of type "
                  + value.getClass()
                  + " with the EntityImpl binary serializer");
        }
        var pointer = serializeValue(session, bytes, value, type, null, schema, encryption);
        IntegerSerializer.serializeLiteral(pointer, bytes.bytes, pos[i]);
        writeOType(bytes, (pos[i] + IntegerSerializer.INT_SIZE), type);
      } else {
        // signal for null value
        IntegerSerializer.serializeLiteral(0, bytes.bytes, pos[i]);
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
    final var pos = VarIntSerializer.write(bytes, value.size());
    writeOType(bytes, bytes.alloc(1), linkedType != null ? linkedType : PropertyType.EMBEDDED);
    for (var itemValue : value) {
      if (itemValue == null) {
        writeOType(bytes, bytes.alloc(1), null);
        continue;
      }
      PropertyType type;
      if (linkedType == null) {
        type = getTypeFromValueEmbedded(itemValue);
      } else {
        type = linkedType;
      }
      if (type != null) {
        writeOType(bytes, bytes.alloc(1), type);
        serializeValue(session, bytes, itemValue, type, null, schema, encryption);
      } else {
        throw new SerializationException(session.getDatabaseName(),
            "Impossible serialize value of type "
                + value.getClass()
                + " with the EntityImpl binary serializer");
      }
    }
    return pos;
  }

  protected static PropertyType getFieldType(@Nonnull DatabaseSessionInternal session,
      final EntityEntry entry) {
    var type = entry.type;
    if (type == null) {
      final var prop = entry.property;
      if (prop != null) {
        type = prop.getType();
      }
    }
    if (type == null) {
      type = PropertyType.getTypeByValue(entry.value);
    }
    return type;
  }

  protected static int writeEmptyString(final BytesContainer bytes) {
    return VarIntSerializer.write(bytes, 0);
  }

  @Override
  public boolean isSerializingClassNameByDefault() {
    return true;
  }

  protected static void skipClassName(BytesContainer bytes) {
    final var classNameLen = VarIntSerializer.readAsInteger(bytes);
    bytes.skip(classNameLen);
  }

  private int getEmbeddedFieldSize(
      DatabaseSessionInternal db, BytesContainer bytes, int currentFieldDataPos, PropertyType type,
      ImmutableSchema schema) {
    var startOffset = bytes.offset;
    try {
      // try to read next position in header if exists
      var len = VarIntSerializer.readAsInteger(bytes);
      if (len != 0) {
        if (len > 0) {
          // skip name bytes
          bytes.skip(len);
        }
        var nextFieldPos = readInteger(bytes);
        return nextFieldPos - currentFieldDataPos;
      }

      // this means that this is last field so field length have to be calculated
      // by deserializing the value
      bytes.offset = currentFieldDataPos;
      deserializeValue(db, bytes, type, new EntityImpl(db), true, -1, true, schema);
      var fieldDataLength = bytes.offset - currentFieldDataPos;
      return fieldDataLength;
    } finally {
      bytes.offset = startOffset;
    }
  }

  protected <RET> RET deserializeFieldTypedLoopAndReturn(
      DatabaseSessionInternal db, BytesContainer bytes, String iFieldName,
      ImmutableSchema schema) {
    final var field = iFieldName.getBytes();

    while (true) {
      var len = VarIntSerializer.readAsInteger(bytes);

      if (len == 0) {
        // SCAN COMPLETED, NO FIELD FOUND
        return null;
      } else if (len > 0) {
        // CHECK BY FIELD NAME SIZE: THIS AVOID EVEN THE UNMARSHALLING OF FIELD NAME
        if (iFieldName.length() == len) {
          var match = true;
          for (var j = 0; j < len; ++j) {
            if (bytes.bytes[bytes.offset + j] != field[j]) {
              match = false;
              break;
            }
          }

          bytes.skip(len);
          var pointerAndType = getPointerAndTypeFromCurrentPosition(bytes);
          int valuePos = pointerAndType.getFirstVal();
          var type = pointerAndType.getSecondVal();

          if (valuePos == 0) {
            return null;
          }

          if (!match) {
            continue;
          }

          // find start of the next field offset so current field byte length can be calculated
          // actual field byte length is only needed for embedded fields
          var fieldDataLength = -1;
          if (type.isEmbedded()) {
            fieldDataLength = getEmbeddedFieldSize(db, bytes, valuePos, type, schema);
          }

          bytes.offset = valuePos;
          var value = deserializeValue(db, bytes, type, null, false, fieldDataLength, false,
              schema);
          return (RET) value;
        }

        // skip Pointer and data type
        bytes.skip(len + IntegerSerializer.INT_SIZE + 1);

      } else {
        // LOAD GLOBAL PROPERTY BY ID
        final var id = (len * -1) - 1;
        final var prop = schema.getGlobalPropertyById(id);
        if (iFieldName.equals(prop.getName())) {
          final var valuePos = readInteger(bytes);
          var type = prop.getType();
          var fieldDataLength = -1;
          if (type.isEmbedded()) {
            fieldDataLength = getEmbeddedFieldSize(db, bytes, valuePos, type, schema);
          }

          if (valuePos == 0) {
            return null;
          }

          bytes.offset = valuePos;

          var value = deserializeValue(db, bytes, type, null, false, fieldDataLength, false,
              schema);
          return (RET) value;
        }
        bytes.skip(IntegerSerializer.INT_SIZE);
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
    var valuePos = readInteger(bytes);
    var type = readOType(bytes, false);
    return new Tuple<>(valuePos, type);
  }

  @Override
  public void deserializeDebug(
      DatabaseSessionInternal session, BytesContainer bytes,
      RecordSerializationDebug debugInfo,
      ImmutableSchema schema) {

    debugInfo.properties = new ArrayList<>();
    var last = 0;
    String fieldName;
    int valuePos;
    PropertyType type;
    while (true) {
      var debugProperty = new RecordSerializationDebugProperty();
      GlobalProperty prop = null;
      try {
        final var len = VarIntSerializer.readAsInteger(bytes);
        if (len != 0) {
          debugInfo.properties.add(debugProperty);
        }
        if (len == 0) {
          // SCAN COMPLETED
          break;
        } else if (len > 0) {
          // PARSE FIELD NAME
          fieldName = stringFromBytesIntern(session, bytes.bytes, bytes.offset, len);
          bytes.skip(len);

          var valuePositionAndType = getPointerAndTypeFromCurrentPosition(
              bytes);
          valuePos = valuePositionAndType.getFirstVal();
          type = valuePositionAndType.getSecondVal();
        } else {
          // LOAD GLOBAL PROPERTY BY ID
          final var id = (len * -1) - 1;
          debugProperty.globalId = id;
          prop = schema.getGlobalPropertyById(id);
          valuePos = readInteger(bytes);
          debugProperty.valuePos = valuePos;
          if (prop != null) {
            fieldName = prop.getName();
            type = prop.getType();
          } else {
            continue;
          }
        }
        debugProperty.name = fieldName;
        debugProperty.type = type;

        if (valuePos != 0) {
          var headerCursor = bytes.offset;
          bytes.offset = valuePos;
          try {
            debugProperty.value = deserializeValue(session, bytes, type, new EntityImpl(session));
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
