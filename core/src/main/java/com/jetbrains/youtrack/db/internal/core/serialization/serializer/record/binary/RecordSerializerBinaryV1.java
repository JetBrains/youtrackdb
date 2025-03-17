package com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary;

import static com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.HelperClasses.MILLISEC_PER_DAY;
import static com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.HelperClasses.bytesFromString;
import static com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.HelperClasses.convertDayToTimezone;
import static com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.HelperClasses.getGlobalProperty;
import static com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.HelperClasses.getLinkedType;
import static com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.HelperClasses.getTypeFromValueEmbedded;
import static com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.HelperClasses.readBinary;
import static com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.HelperClasses.readByte;
import static com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.HelperClasses.readInteger;
import static com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.HelperClasses.readLinkCollection;
import static com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.HelperClasses.readLinkMap;
import static com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.HelperClasses.readLong;
import static com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.HelperClasses.readOType;
import static com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.HelperClasses.readOptimizedLink;
import static com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.HelperClasses.readString;
import static com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.HelperClasses.stringFromBytesIntern;
import static com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.HelperClasses.writeBinary;
import static com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.HelperClasses.writeLinkCollection;
import static com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.HelperClasses.writeLinkMap;
import static com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.HelperClasses.writeOType;
import static com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.HelperClasses.writeOptimizedLink;
import static com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.HelperClasses.writeString;

import com.jetbrains.youtrack.db.api.exception.DatabaseException;
import com.jetbrains.youtrack.db.api.exception.ValidationException;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.schema.GlobalProperty;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.api.schema.SchemaProperty;
import com.jetbrains.youtrack.db.internal.common.collection.MultiValue;
import com.jetbrains.youtrack.db.internal.common.serialization.types.ByteSerializer;
import com.jetbrains.youtrack.db.internal.common.serialization.types.DecimalSerializer;
import com.jetbrains.youtrack.db.internal.common.serialization.types.IntegerSerializer;
import com.jetbrains.youtrack.db.internal.common.serialization.types.LongSerializer;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.record.LinkList;
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
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeMap;

public class RecordSerializerBinaryV1 implements EntitySerializer {

  private final BinaryComparatorV0 comparator = new BinaryComparatorV0();

  private static int findMatchingFieldName(final BytesContainer bytes, int len, byte[][] fields) {
    for (var i = 0; i < fields.length; ++i) {
      if (fields[i] != null && fields[i].length == len) {
        var matchField = true;
        for (var j = 0; j < len; ++j) {
          if (bytes.bytes[bytes.offset + j] != fields[i][j]) {
            matchField = false;
            break;
          }
        }
        if (matchField) {
          return i;
        }
      }
    }

    return -1;
  }

  private static boolean checkIfPropertyNameMatchSome(GlobalProperty prop, final String[] fields) {
    var fieldName = prop.getName();

    for (var field : fields) {
      if (fieldName.equals(field)) {
        return true;
      }
    }

    return false;
  }

  public void deserializePartial(DatabaseSessionInternal db, EntityImpl entity,
      BytesContainer bytes, String[] iFields) {
    // TRANSFORMS FIELDS FOM STRINGS TO BYTE[]
    final var fields = new byte[iFields.length][];
    for (var i = 0; i < iFields.length; ++i) {
      fields[i] = bytesFromString(iFields[i]);
    }

    String fieldName;
    PropertyType type;
    var unmarshalledFields = 0;

    var headerLength = VarIntSerializer.readAsInteger(bytes);
    var headerStart = bytes.offset;
    var valuesStart = headerStart + headerLength;
    var currentValuePos = valuesStart;

    while (bytes.offset < valuesStart) {

      final var len = VarIntSerializer.readAsInteger(bytes);
      boolean found;
      int fieldLength;
      if (len > 0) {
        var fieldPos = findMatchingFieldName(bytes, len, fields);
        bytes.skip(len);
        var pointerAndType = getFieldSizeAndTypeFromCurrentPosition(bytes);
        fieldLength = pointerAndType.getFirstVal();
        type = pointerAndType.getSecondVal();

        if (fieldPos >= 0) {
          fieldName = iFields[fieldPos];
          found = true;
        } else {
          fieldName = null;
          found = false;
        }
      } else {
        // LOAD GLOBAL PROPERTY BY ID
        final var prop = getGlobalProperty(entity, len);
        found = checkIfPropertyNameMatchSome(prop, iFields);

        fieldLength = VarIntSerializer.readAsInteger(bytes);
        type = prop.getType();

        fieldName = prop.getName();
      }
      if (found) {
        if (fieldLength != 0) {
          var headerCursor = bytes.offset;
          bytes.offset = currentValuePos;
          final var value = deserializeValue(db, bytes, type, entity);
          bytes.offset = headerCursor;
          entity.setDeserializedPropertyInternal(fieldName, value, type);
        } else {
          // If pos us 0 the value is null just set it.
          entity.setDeserializedPropertyInternal(fieldName, null, null);
        }
        if (++unmarshalledFields == iFields.length)
        // ALL REQUESTED FIELDS UNMARSHALLED: EXIT
        {
          break;
        }
      }
      currentValuePos += fieldLength;
    }
  }

  private static boolean checkMatchForLargerThenZero(
      final BytesContainer bytes, final byte[] field, int len) {
    if (field.length != len) {
      return false;
    }
    var match = true;
    for (var j = 0; j < len; ++j) {
      if (bytes.bytes[bytes.offset + j] != field[j]) {
        match = false;
        break;
      }
    }

    return match;
  }

  public BinaryField deserializeField(
      DatabaseSessionInternal session, final BytesContainer bytes,
      final SchemaClass iClass,
      final String iFieldName,
      boolean embedded,
      ImmutableSchema schema,
      PropertyEncryption encryption) {

    if (embedded) {
      // skip class name bytes
      final var classNameLen = VarIntSerializer.readAsInteger(bytes);
      bytes.skip(classNameLen);
    }
    final var field = iFieldName.getBytes();

    var headerLength = VarIntSerializer.readAsInteger(bytes);
    var headerStart = bytes.offset;
    var valuesStart = headerStart + headerLength;
    var currentValuePos = valuesStart;

    while (bytes.offset < valuesStart) {

      final var len = VarIntSerializer.readAsInteger(bytes);

      if (len > 0) {

        var match = checkMatchForLargerThenZero(bytes, field, len);

        bytes.skip(len);
        var pointerAndType = getFieldSizeAndTypeFromCurrentPosition(bytes);
        final int fieldLength = pointerAndType.getFirstVal();
        final var type = pointerAndType.getSecondVal();

        if (match) {
          if (fieldLength == 0 || !getComparator().isBinaryComparable(type)) {
            return null;
          }

          bytes.offset = currentValuePos;
          return new BinaryField(iFieldName, type, bytes, null);
        }
        currentValuePos += fieldLength;
      } else {

        final var id = (len * -1) - 1;
        final var prop = schema.getGlobalPropertyById(id);
        final var fieldLength = VarIntSerializer.readAsInteger(bytes);
        final PropertyType type;
        type = prop.getType();

        if (iFieldName.equals(prop.getName())) {
          if (fieldLength == 0 || !getComparator().isBinaryComparable(type)) {
            return null;
          }
          bytes.offset = currentValuePos;
          final var classProp = iClass.getProperty(iFieldName);
          return new BinaryField(
              iFieldName, type, bytes, classProp != null ? classProp.getCollate() : null);
        }
        currentValuePos += fieldLength;
      }
    }
    return null;
  }

  public void deserialize(DatabaseSessionInternal session, final EntityImpl entity,
      final BytesContainer bytes) {
    var headerLength = VarIntSerializer.readAsInteger(bytes);
    var headerStart = bytes.offset;
    var valuesStart = headerStart + headerLength;
    var last = 0;
    String fieldName;
    PropertyType type;
    var cumulativeSize = valuesStart;
    while (bytes.offset < valuesStart) {
      GlobalProperty prop;
      final var len = VarIntSerializer.readAsInteger(bytes);
      int fieldLength;
      if (len > 0) {
        // PARSE FIELD NAME
        fieldName = stringFromBytesIntern(session, bytes.bytes, bytes.offset, len);
        bytes.skip(len);
        var pointerAndType = getFieldSizeAndTypeFromCurrentPosition(bytes);
        fieldLength = pointerAndType.getFirstVal();
        type = pointerAndType.getSecondVal();
      } else {
        // LOAD GLOBAL PROPERTY BY ID
        prop = getGlobalProperty(entity, len);
        fieldName = prop.getName();
        fieldLength = VarIntSerializer.readAsInteger(bytes);
        type = prop.getType();
      }

      if (!entity.rawContainsProperty(fieldName)) {
        if (fieldLength != 0) {
          var headerCursor = bytes.offset;

          bytes.offset = cumulativeSize;
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

      cumulativeSize += fieldLength;
    }

    RecordInternal.clearSource(entity);

    if (last > bytes.offset) {
      bytes.offset = last;
    }
  }

  public void deserializeWithClassName(DatabaseSessionInternal db, final EntityImpl entity,
      final BytesContainer bytes) {

    final var className = readString(bytes);
    if (!className.isEmpty()) {
      entity.fillClassIfNeed(className);
    }

    deserialize(db, entity, bytes);
  }

  public String[] getFieldNames(DatabaseSessionInternal session, EntityImpl reference,
      final BytesContainer bytes,
      boolean embedded) {
    // SKIP CLASS NAME
    if (embedded) {
      final var classNameLen = VarIntSerializer.readAsInteger(bytes);
      bytes.skip(classNameLen);
    }

    // skip header length
    var headerLength = VarIntSerializer.readAsInteger(bytes);
    var headerStart = bytes.offset;

    final List<String> result = new ArrayList<>();

    String fieldName;
    while (bytes.offset < headerStart + headerLength) {
      GlobalProperty prop;
      final var len = VarIntSerializer.readAsInteger(bytes);
      if (len > 0) {
        // PARSE FIELD NAME
        fieldName = stringFromBytesIntern(session, bytes.bytes, bytes.offset, len);
        result.add(fieldName);

        // SKIP THE REST
        bytes.skip(len);
        VarIntSerializer.readAsInteger(bytes);
        bytes.skip(1);
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
        VarIntSerializer.readAsInteger(bytes);
      }
    }

    return result.toArray(new String[0]);
  }

  private void serializeValues(
      DatabaseSessionInternal session, final BytesContainer headerBuffer,
      final BytesContainer valuesBuffer,
      final EntityImpl entity,
      Set<Entry<String, EntityEntry>> fields,
      final Map<String, SchemaProperty> props,
      ImmutableSchema schema,
      PropertyEncryption encryption) {
    SchemaImmutableClass result = null;
    if (entity != null) {
      result = entity.getImmutableSchemaClass(session);
    }
    SchemaClass oClass = result;
    for (var field : fields) {
      var docEntry = field.getValue();
      if (!field.getValue().exists()) {
        continue;
      }
      if (docEntry.property == null && props != null) {
        var prop = props.get(field.getKey());
        if (prop != null && docEntry.type == prop.getType()) {
          docEntry.property = prop;
        }
      }

      if (docEntry.property == null) {
        var fieldName = field.getKey();
        writeString(headerBuffer, fieldName);
      } else {
        VarIntSerializer.write(headerBuffer, (docEntry.property.getId() + 1) * -1);
      }

      final var value = field.getValue().value;

      final PropertyType type;
      if (value != null) {
        type = DocumentSerializerDelta.getFieldType(field.getValue());
        if (type == null) {
          throw new SerializationException(session.getDatabaseName(),
              "Impossible serialize value of type "
                  + value.getClass()
                  + " with the EntityImpl binary serializer");
        }
        var startOffset = valuesBuffer.offset;
        serializeValue(session,
            valuesBuffer,
            value,
            type,
            getLinkedType(session, oClass, type, field.getKey()),
            schema, encryption);
        var valueLength = valuesBuffer.offset - startOffset;
        VarIntSerializer.write(headerBuffer, valueLength);
      } else {
        // handle null fields
        VarIntSerializer.write(headerBuffer, 0);
        type = null;
      }

      // write type. Type should be written both for regular and null fields
      if (field.getValue().property == null) {
        var typeOffset = headerBuffer.alloc(ByteSerializer.BYTE_SIZE);
        if (type != null) {
          headerBuffer.bytes[typeOffset] = (byte) type.getId();
        } else {
          headerBuffer.bytes[typeOffset] = (byte) -1;
        }
      }
    }
  }

  private static void merge(
      BytesContainer destinationBuffer,
      BytesContainer sourceBuffer1,
      BytesContainer sourceBuffer2) {
    destinationBuffer.offset =
        destinationBuffer.allocExact(sourceBuffer1.offset + sourceBuffer2.offset);
    System.arraycopy(
        sourceBuffer1.bytes,
        0,
        destinationBuffer.bytes,
        destinationBuffer.offset,
        sourceBuffer1.offset);
    System.arraycopy(
        sourceBuffer2.bytes,
        0,
        destinationBuffer.bytes,
        destinationBuffer.offset + sourceBuffer1.offset,
        sourceBuffer2.offset);
    destinationBuffer.offset += sourceBuffer1.offset + sourceBuffer2.offset;
  }

  private void serializeDocument(
      DatabaseSessionInternal session, final EntityImpl entity,
      final BytesContainer bytes,
      final SchemaClass clazz,
      ImmutableSchema schema,
      PropertyEncryption encryption) {
    // allocate space for header length

    final var props = clazz != null ? clazz.propertiesMap() : null;
    final var fields = entity.getRawEntries();

    var valuesBuffer = new BytesContainer();
    var headerBuffer = new BytesContainer();

    serializeValues(session, headerBuffer, valuesBuffer, entity, fields, props, schema,
        encryption);
    var headerLength = headerBuffer.offset;
    // write header length as soon as possible
    VarIntSerializer.write(bytes, headerLength);

    merge(bytes, headerBuffer, valuesBuffer);
  }

  public void serializeWithClassName(DatabaseSessionInternal session, final EntityImpl entity,
      final BytesContainer bytes) {
    ImmutableSchema schema = null;
    if (entity != null) {
      schema = entity.getImmutableSchema();
    }
    SchemaImmutableClass result = null;
    if (entity != null) {
      result = entity.getImmutableSchemaClass(session);
    }
    final SchemaClass clazz = result;
    if (clazz != null && entity.isEmbedded()) {
      writeString(bytes, clazz.getName());
    } else {
      writeEmptyString(bytes);
    }
    var encryption = entity.propertyEncryption;
    serializeDocument(session, entity, bytes, clazz, schema, encryption);
  }

  public void serialize(DatabaseSessionInternal session, final EntityImpl entity,
      final BytesContainer bytes) {
    ImmutableSchema schema = null;
    if (entity != null) {
      schema = entity.getImmutableSchema();
    }
    var encryption = entity.propertyEncryption;
    var clazz = entity.getImmutableSchemaClass(session);
    serializeDocument(session, entity, bytes, clazz, schema, encryption);
  }

  public boolean isSerializingClassNameByDefault() {
    return false;
  }

  public <RET> RET deserializeFieldTyped(
      DatabaseSessionInternal session, BytesContainer bytes,
      String iFieldName,
      boolean isEmbedded,
      ImmutableSchema schema,
      PropertyEncryption encryption) {
    if (isEmbedded) {
      skipClassName(bytes);
    }
    return deserializeFieldTypedLoopAndReturn(session, bytes, iFieldName, schema, encryption);
  }

  protected <RET> RET deserializeFieldTypedLoopAndReturn(
      DatabaseSessionInternal session, BytesContainer bytes,
      String iFieldName,
      final ImmutableSchema schema,
      PropertyEncryption encryption) {
    final var field = iFieldName.getBytes();

    var headerLength = VarIntSerializer.readAsInteger(bytes);
    var headerStart = bytes.offset;
    var valuesStart = headerStart + headerLength;
    var cumulativeLength = valuesStart;

    while (bytes.offset < valuesStart) {

      var len = VarIntSerializer.readAsInteger(bytes);

      if (len > 0) {
        // CHECK BY FIELD NAME SIZE: THIS AVOID EVEN THE UNMARSHALLING OF FIELD NAME
        var match = checkMatchForLargerThenZero(bytes, field, len);

        bytes.skip(len);
        var pointerAndType = getFieldSizeAndTypeFromCurrentPosition(bytes);
        int fieldLength = pointerAndType.getFirstVal();
        var type = pointerAndType.getSecondVal();

        if (match) {
          if (fieldLength == 0) {
            return null;
          }

          bytes.offset = cumulativeLength;
          var value = deserializeValue(session, bytes, type, null, false, fieldLength, false,
              schema);
          //noinspection unchecked
          return (RET) value;
        }
        cumulativeLength += fieldLength;
      } else {
        // LOAD GLOBAL PROPERTY BY ID
        final var id = (len * -1) - 1;
        final var prop = schema.getGlobalPropertyById(id);
        final var fieldLength = VarIntSerializer.readAsInteger(bytes);
        var type = prop.getType();

        if (iFieldName.equals(prop.getName())) {

          if (fieldLength == 0) {
            return null;
          }

          bytes.offset = cumulativeLength;

          var value = deserializeValue(session, bytes, type, null,
              false, fieldLength, false,
              schema);
          //noinspection unchecked
          return (RET) value;
        }
        cumulativeLength += fieldLength;
      }
    }
    return null;
  }

  /**
   * use only for named fields
   */
  private static Tuple<Integer, PropertyType> getFieldSizeAndTypeFromCurrentPosition(
      BytesContainer bytes) {
    var fieldSize = VarIntSerializer.readAsInteger(bytes);
    var type = readOType(bytes, false);
    return new Tuple<>(fieldSize, type);
  }

  public void deserializeDebug(
      DatabaseSessionInternal session, BytesContainer bytes,
      RecordSerializationDebug debugInfo,
      ImmutableSchema schema) {

    var headerLength = VarIntSerializer.readAsInteger(bytes);
    var headerPos = bytes.offset;

    debugInfo.properties = new ArrayList<>();
    var last = 0;
    String fieldName;
    PropertyType type;
    var cumulativeLength = 0;
    while (true) {
      var debugProperty = new RecordSerializationDebugProperty();
      GlobalProperty prop;

      int fieldLength;

      try {
        if (bytes.offset >= headerPos + headerLength) {
          break;
        }

        final var len = VarIntSerializer.readAsInteger(bytes);
        debugInfo.properties.add(debugProperty);
        if (len > 0) {
          // PARSE FIELD NAME
          fieldName = stringFromBytesIntern(session, bytes.bytes, bytes.offset, len);
          bytes.skip(len);

          var valuePositionAndType =
              getFieldSizeAndTypeFromCurrentPosition(bytes);
          fieldLength = valuePositionAndType.getFirstVal();
          type = valuePositionAndType.getSecondVal();
        } else {
          // LOAD GLOBAL PROPERTY BY ID
          final var id = (len * -1) - 1;
          debugProperty.globalId = id;
          prop = schema.getGlobalPropertyById(id);
          fieldLength = VarIntSerializer.readAsInteger(bytes);
          debugProperty.valuePos = headerPos + headerLength + cumulativeLength;
          if (prop != null) {
            fieldName = prop.getName();
            type = prop.getType();
          } else {
            cumulativeLength += fieldLength;
            continue;
          }
        }
        debugProperty.name = fieldName;
        debugProperty.type = type;

        int valuePos;
        if (fieldLength > 0) {
          valuePos = headerPos + headerLength + cumulativeLength;
        } else {
          valuePos = 0;
        }

        cumulativeLength += fieldLength;

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

  protected int writeEmbeddedMap(
      DatabaseSessionInternal session, BytesContainer bytes,
      Map<Object, Object> map,
      ImmutableSchema schema,
      PropertyEncryption encryption) {
    final var fullPos = VarIntSerializer.write(bytes, map.size());
    for (var entry : map.entrySet()) {
      // TODO:check skip of complex types
      // FIXME: changed to support only string key on map
      var type = PropertyType.STRING;
      writeOType(bytes, bytes.alloc(1), type);
      var key = entry.getKey();
      if (key == null) {
        throw new SerializationException(session.getDatabaseName(),
            "Maps with null keys are not supported");
      }
      writeString(bytes, entry.getKey().toString());
      final var value = entry.getValue();
      if (value != null) {
        type = getTypeFromValueEmbedded(value);
        if (type == null) {
          throw new SerializationException(session.getDatabaseName(),
              "Impossible serialize value of type "
                  + value.getClass()
                  + " with the EntityImpl binary serializer");
        }
        writeOType(bytes, bytes.alloc(1), type);
        serializeValue(session, bytes, value, type, null, schema, encryption);
      } else {
        // signal for null value
        var pointer = bytes.alloc(1);
        bytes.bytes[pointer] = -1;
      }
    }

    return fullPos;
  }

  protected Object readEmbeddedMap(DatabaseSessionInternal db, final BytesContainer bytes,
      final RecordElement owner) {
    var size = VarIntSerializer.readAsInteger(bytes);
    final var result = new TrackedMap<Object>(owner);
    for (var i = 0; i < size; i++) {
      var keyType = readOType(bytes, false);
      var key = deserializeValue(db, bytes, keyType, result);
      final var type = HelperClasses.readType(bytes);
      if (type != null) {
        var value = deserializeValue(db, bytes, type, result);
        result.putInternal(key.toString(), value);
      } else {
        result.putInternal(key.toString(), null);
      }
    }
    return result;
  }

  protected List<MapRecordInfo> getPositionsFromEmbeddedMap(
      DatabaseSessionInternal session, final BytesContainer bytes, ImmutableSchema schema) {
    List<MapRecordInfo> retList = new ArrayList<>();

    var numberOfElements = VarIntSerializer.readAsInteger(bytes);

    for (var i = 0; i < numberOfElements; i++) {
      var keyType = readOType(bytes, false);
      var key = readString(bytes);
      var valueType = HelperClasses.readType(bytes);
      var recordInfo = new MapRecordInfo();
      recordInfo.fieldType = valueType;
      recordInfo.key = key;
      recordInfo.keyType = keyType;
      var currentOffset = bytes.offset;

      if (valueType != null) {
        recordInfo.fieldStartOffset = bytes.offset;
        deserializeValue(session, bytes, valueType, null, true,
            -1, true, schema);
        recordInfo.fieldLength = bytes.offset - currentOffset;
        retList.add(recordInfo);
      } else {
        recordInfo.fieldStartOffset = 0;
        recordInfo.fieldLength = 0;
        retList.add(recordInfo);
      }
    }

    return retList;
  }

  protected static int writeRidBag(DatabaseSessionInternal session, BytesContainer bytes,
      RidBag ridbag) {
    var positionOffset = bytes.offset;
    HelperClasses.writeRidBag(session, bytes, ridbag);
    return positionOffset;
  }

  protected static RidBag readRidbag(DatabaseSessionInternal session, BytesContainer bytes) {
    return HelperClasses.readRidbag(session, bytes);
  }

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
    return deserializeValue(db, bytes, type, owner, true, -1, false, schema);
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
        value = readLinkMap(bytes, owner, justRunThrough);
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

  protected static void writeEmptyString(final BytesContainer bytes) {
    VarIntSerializer.write(bytes, 0);
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
          serializeWithClassName(session, cur, bytes);
        } else {
          serializeWithClassName(session, (EntityImpl) value, bytes);
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
        throw new SerializationException(session,
            "Impossible serialize value of type "
                + value.getClass()
                + " with the EntityImpl binary serializer");
      }
    }
    return pos;
  }

  protected List<?> deserializeEmbeddedCollectionAsCollectionOfBytes(
      DatabaseSessionInternal session, final BytesContainer bytes, ImmutableSchema schema) {
    var retVal = new ArrayList<>();
    var fieldsInfo = getPositionsFromEmbeddedCollection(session, bytes, schema);
    for (var fieldInfo : fieldsInfo) {
      if (fieldInfo.fieldType.isEmbedded()) {
        var result =
            new ResultBinary(session,
                schema, bytes.bytes, fieldInfo.fieldStartOffset, fieldInfo.fieldLength, this);
        retVal.add(result);
      } else {
        var currentOffset = bytes.offset;
        bytes.offset = fieldInfo.fieldStartOffset;
        var value = deserializeValue(session, bytes, fieldInfo.fieldType, null);
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
      var entity = (EntityImpl) value;
      entity.setOwner(owner);
      RecordInternal.unsetDirty(entity);
    }

    return value;
  }

  // returns begin position and length for each value in embedded collection
  private List<RecordInfo> getPositionsFromEmbeddedCollection(
      DatabaseSessionInternal session, final BytesContainer bytes, ImmutableSchema schema) {
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
      deserializeValue(session, bytes, dataType, null, true,
          -1, true, schema);
      fieldInfo.fieldLength = bytes.offset - fieldStart;
      retList.add(fieldInfo);
    }

    return retList;
  }

  protected ResultBinary deserializeEmbeddedAsBytes(
      DatabaseSessionInternal db, final BytesContainer bytes, int valueLength,
      ImmutableSchema schema) {
    var startOffset = bytes.offset;
    return new ResultBinary(db, schema, bytes.bytes, startOffset, valueLength, this);
  }

  protected Collection<?> readEmbeddedSet(DatabaseSessionInternal db, final BytesContainer bytes,
      final RecordElement owner) {

    final var items = VarIntSerializer.readAsInteger(bytes);
    var type = readOType(bytes, false);

    if (type != null) {
      var found = new TrackedSet<>(owner);
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
    // TODO: manage case where type is known
    return null;
  }

  protected Collection<?> readEmbeddedList(DatabaseSessionInternal db, final BytesContainer bytes,
      final RecordElement owner) {

    final var items = VarIntSerializer.readAsInteger(bytes);
    var type = readOType(bytes, false);

    if (type != null) {
      var found = new TrackedList<>(owner);
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
    // TODO: manage case where type is known
    return null;
  }

  protected static void skipClassName(BytesContainer bytes) {
    RecordSerializerBinaryV0.skipClassName(bytes);
  }

  @Override
  public BinaryComparator getComparator() {
    return comparator;
  }
}
