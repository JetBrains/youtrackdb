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

import com.jetbrains.youtrack.db.api.DatabaseSession;
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

public class RecordSerializerBinaryV1 implements EntitySerializer {

  private final BinaryComparatorV0 comparator = new BinaryComparatorV0();

  private static int findMatchingFieldName(final BytesContainer bytes, int len, byte[][] fields) {
    for (int i = 0; i < fields.length; ++i) {
      if (fields[i] != null && fields[i].length == len) {
        boolean matchField = true;
        for (int j = 0; j < len; ++j) {
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
    String fieldName = prop.getName();

    for (String field : fields) {
      if (fieldName.equals(field)) {
        return true;
      }
    }

    return false;
  }

  public void deserializePartial(DatabaseSessionInternal db, EntityImpl entity,
      BytesContainer bytes, String[] iFields) {
    // TRANSFORMS FIELDS FOM STRINGS TO BYTE[]
    final byte[][] fields = new byte[iFields.length][];
    for (int i = 0; i < iFields.length; ++i) {
      fields[i] = bytesFromString(iFields[i]);
    }

    String fieldName;
    PropertyType type;
    int unmarshalledFields = 0;

    int headerLength = VarIntSerializer.readAsInteger(bytes);
    int headerStart = bytes.offset;
    int valuesStart = headerStart + headerLength;
    int currentValuePos = valuesStart;

    while (bytes.offset < valuesStart) {

      final int len = VarIntSerializer.readAsInteger(bytes);
      boolean found;
      int fieldLength;
      if (len > 0) {
        int fieldPos = findMatchingFieldName(bytes, len, fields);
        bytes.skip(len);
        Tuple<Integer, PropertyType> pointerAndType = getFieldSizeAndTypeFromCurrentPosition(bytes);
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
        final GlobalProperty prop = getGlobalProperty(entity, len);
        found = checkIfPropertyNameMatchSome(prop, iFields);

        fieldLength = VarIntSerializer.readAsInteger(bytes);
        type = getPropertyTypeFromStream(prop, bytes);

        fieldName = prop.getName();
      }
      if (found) {
        if (fieldLength != 0) {
          int headerCursor = bytes.offset;
          bytes.offset = currentValuePos;
          final Object value = deserializeValue(db, bytes, type, entity);
          bytes.offset = headerCursor;
          EntityInternalUtils.rawField(entity, fieldName, value, type);
        } else {
          // If pos us 0 the value is null just set it.
          EntityInternalUtils.rawField(entity, fieldName, null, null);
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

  private boolean checkMatchForLargerThenZero(
      final BytesContainer bytes, final byte[] field, int len) {
    if (field.length != len) {
      return false;
    }
    boolean match = true;
    for (int j = 0; j < len; ++j) {
      if (bytes.bytes[bytes.offset + j] != field[j]) {
        match = false;
        break;
      }
    }

    return match;
  }

  private PropertyType getPropertyTypeFromStream(final GlobalProperty prop,
      final BytesContainer bytes) {
    final PropertyType type;
    if (prop.getType() != PropertyType.ANY) {
      type = prop.getType();
    } else {
      type = readOType(bytes, false);
    }

    return type;
  }

  @Override
  public BinaryField deserializeField(
      DatabaseSession session,
      final BytesContainer bytes,
      final SchemaClass iClass,
      final String iFieldName,
      boolean embedded,
      ImmutableSchema schema,
      PropertyEncryption encryption) {

    if (embedded) {
      // skip class name bytes
      final int classNameLen = VarIntSerializer.readAsInteger(bytes);
      bytes.skip(classNameLen);
    }
    final byte[] field = iFieldName.getBytes();

    int headerLength = VarIntSerializer.readAsInteger(bytes);
    int headerStart = bytes.offset;
    int valuesStart = headerStart + headerLength;
    int currentValuePos = valuesStart;

    while (bytes.offset < valuesStart) {

      final int len = VarIntSerializer.readAsInteger(bytes);

      if (len > 0) {

        boolean match = checkMatchForLargerThenZero(bytes, field, len);

        bytes.skip(len);
        Tuple<Integer, PropertyType> pointerAndType = getFieldSizeAndTypeFromCurrentPosition(bytes);
        final int fieldLength = pointerAndType.getFirstVal();
        final PropertyType type = pointerAndType.getSecondVal();

        if (match) {
          if (fieldLength == 0 || !getComparator().isBinaryComparable(type)) {
            return null;
          }

          bytes.offset = currentValuePos;
          return new BinaryField(iFieldName, type, bytes, null);
        }
        currentValuePos += fieldLength;
      } else {

        final int id = (len * -1) - 1;
        final GlobalProperty prop = schema.getGlobalPropertyById(id);
        final int fieldLength = VarIntSerializer.readAsInteger(bytes);
        final PropertyType type;
        type = getPropertyTypeFromStream(prop, bytes);

        if (iFieldName.equals(prop.getName())) {
          if (fieldLength == 0 || !getComparator().isBinaryComparable(type)) {
            return null;
          }
          bytes.offset = currentValuePos;
          final SchemaProperty classProp = iClass.getProperty(session, iFieldName);
          return new BinaryField(
              iFieldName, type, bytes, classProp != null ? classProp.getCollate() : null);
        }
        currentValuePos += fieldLength;
      }
    }
    return null;
  }

  public void deserialize(DatabaseSessionInternal db, final EntityImpl entity,
      final BytesContainer bytes) {
    int headerLength = VarIntSerializer.readAsInteger(bytes);
    int headerStart = bytes.offset;
    int valuesStart = headerStart + headerLength;
    int last = 0;
    String fieldName;
    PropertyType type;
    int cumulativeSize = valuesStart;
    while (bytes.offset < valuesStart) {
      GlobalProperty prop;
      final int len = VarIntSerializer.readAsInteger(bytes);
      int fieldLength;
      if (len > 0) {
        // PARSE FIELD NAME
        fieldName = stringFromBytesIntern(bytes.bytes, bytes.offset, len);
        bytes.skip(len);
        Tuple<Integer, PropertyType> pointerAndType = getFieldSizeAndTypeFromCurrentPosition(bytes);
        fieldLength = pointerAndType.getFirstVal();
        type = pointerAndType.getSecondVal();
      } else {
        // LOAD GLOBAL PROPERTY BY ID
        prop = getGlobalProperty(entity, len);
        fieldName = prop.getName();
        fieldLength = VarIntSerializer.readAsInteger(bytes);
        type = getPropertyTypeFromStream(prop, bytes);
      }

      if (!EntityInternalUtils.rawContainsField(entity, fieldName)) {
        if (fieldLength != 0) {
          int headerCursor = bytes.offset;

          bytes.offset = cumulativeSize;
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

      cumulativeSize += fieldLength;
    }

    RecordInternal.clearSource(entity);

    if (last > bytes.offset) {
      bytes.offset = last;
    }
  }

  public void deserializeWithClassName(DatabaseSessionInternal db, final EntityImpl entity,
      final BytesContainer bytes) {

    final String className = readString(bytes);
    if (!className.isEmpty()) {
      EntityInternalUtils.fillClassNameIfNeeded(entity, className);
    }

    deserialize(db, entity, bytes);
  }

  public String[] getFieldNames(EntityImpl reference, final BytesContainer bytes,
      boolean embedded) {
    // SKIP CLASS NAME
    if (embedded) {
      final int classNameLen = VarIntSerializer.readAsInteger(bytes);
      bytes.skip(classNameLen);
    }

    // skip header length
    int headerLength = VarIntSerializer.readAsInteger(bytes);
    int headerStart = bytes.offset;

    final List<String> result = new ArrayList<>();

    String fieldName;
    while (bytes.offset < headerStart + headerLength) {
      GlobalProperty prop;
      final int len = VarIntSerializer.readAsInteger(bytes);
      if (len > 0) {
        // PARSE FIELD NAME
        fieldName = stringFromBytesIntern(bytes.bytes, bytes.offset, len);
        result.add(fieldName);

        // SKIP THE REST
        bytes.skip(len);
        VarIntSerializer.readAsInteger(bytes);
        bytes.skip(1);
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
        VarIntSerializer.readAsInteger(bytes);
        if (prop.getType() == PropertyType.ANY) {
          bytes.skip(1);
        }
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
    SchemaClass oClass = EntityInternalUtils.getImmutableSchemaClass(entity);
    for (Entry<String, EntityEntry> field : fields) {
      EntityEntry docEntry = field.getValue();
      if (!field.getValue().exists()) {
        continue;
      }
      if (docEntry.property == null && props != null) {
        SchemaProperty prop = props.get(field.getKey());
        if (prop != null && docEntry.type == prop.getType()) {
          docEntry.property = prop;
        }
      }

      if (docEntry.property == null) {
        String fieldName = field.getKey();
        writeString(headerBuffer, fieldName);
      } else {
        VarIntSerializer.write(headerBuffer, (docEntry.property.getId() + 1) * -1);
      }

      final Object value = field.getValue().value;

      final PropertyType type;
      if (value != null) {
        type = getFieldType(field.getValue());
        if (type == null) {
          throw new SerializationException(
              "Impossible serialize value of type "
                  + value.getClass()
                  + " with the EntityImpl binary serializer");
        }
        int startOffset = valuesBuffer.offset;
        serializeValue(session,
            valuesBuffer,
            value,
            type,
            getLinkedType(session, oClass, type, field.getKey()),
            schema, encryption);
        int valueLength = valuesBuffer.offset - startOffset;
        VarIntSerializer.write(headerBuffer, valueLength);
      } else {
        // handle null fields
        VarIntSerializer.write(headerBuffer, 0);
        type = PropertyType.ANY;
      }

      // write type. Type should be written both for regular and null fields
      if (field.getValue().property == null
          || field.getValue().property.getType() == PropertyType.ANY) {
        int typeOffset = headerBuffer.alloc(ByteSerializer.BYTE_SIZE);
        ByteSerializer.INSTANCE.serialize((byte) type.getId(), headerBuffer.bytes, typeOffset);
      }
    }
  }

  private void merge(
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

    final Map<String, SchemaProperty> props = clazz != null ? clazz.propertiesMap(session) : null;
    final Set<Entry<String, EntityEntry>> fields = EntityInternalUtils.rawEntries(entity);

    BytesContainer valuesBuffer = new BytesContainer();
    BytesContainer headerBuffer = new BytesContainer();

    serializeValues(session, headerBuffer, valuesBuffer, entity, fields, props, schema,
        encryption);
    int headerLength = headerBuffer.offset;
    // write header length as soon as possible
    VarIntSerializer.write(bytes, headerLength);

    merge(bytes, headerBuffer, valuesBuffer);
  }

  public void serializeWithClassName(DatabaseSessionInternal session, final EntityImpl entity,
      final BytesContainer bytes) {
    ImmutableSchema schema = EntityInternalUtils.getImmutableSchema(entity);
    final SchemaClass clazz = EntityInternalUtils.getImmutableSchemaClass(entity);
    if (clazz != null && entity.isEmbedded()) {
      writeString(bytes, clazz.getName());
    } else {
      writeEmptyString(bytes);
    }
    PropertyEncryption encryption = EntityInternalUtils.getPropertyEncryption(entity);
    serializeDocument(session, entity, bytes, clazz, schema, encryption);
  }

  public void serialize(DatabaseSessionInternal session, final EntityImpl entity,
      final BytesContainer bytes) {
    ImmutableSchema schema = EntityInternalUtils.getImmutableSchema(entity);
    PropertyEncryption encryption = EntityInternalUtils.getPropertyEncryption(entity);
    final SchemaClass clazz = EntityInternalUtils.getImmutableSchemaClass(entity);
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
    final byte[] field = iFieldName.getBytes();

    int headerLength = VarIntSerializer.readAsInteger(bytes);
    int headerStart = bytes.offset;
    int valuesStart = headerStart + headerLength;
    int cumulativeLength = valuesStart;

    while (bytes.offset < valuesStart) {

      int len = VarIntSerializer.readAsInteger(bytes);

      if (len > 0) {
        // CHECK BY FIELD NAME SIZE: THIS AVOID EVEN THE UNMARSHALLING OF FIELD NAME
        boolean match = checkMatchForLargerThenZero(bytes, field, len);

        bytes.skip(len);
        Tuple<Integer, PropertyType> pointerAndType = getFieldSizeAndTypeFromCurrentPosition(bytes);
        int fieldLength = pointerAndType.getFirstVal();
        PropertyType type = pointerAndType.getSecondVal();

        if (match) {
          if (fieldLength == 0) {
            return null;
          }

          bytes.offset = cumulativeLength;
          Object value = deserializeValue(session, bytes, type, null, false, fieldLength, false,
              schema);
          //noinspection unchecked
          return (RET) value;
        }
        cumulativeLength += fieldLength;
      } else {
        // LOAD GLOBAL PROPERTY BY ID
        final int id = (len * -1) - 1;
        final GlobalProperty prop = schema.getGlobalPropertyById(id);
        final int fieldLength = VarIntSerializer.readAsInteger(bytes);
        PropertyType type = getPropertyTypeFromStream(prop, bytes);

        if (iFieldName.equals(prop.getName())) {

          if (fieldLength == 0) {
            return null;
          }

          bytes.offset = cumulativeLength;

          Object value = deserializeValue(session, bytes, type, null,
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
  private Tuple<Integer, PropertyType> getFieldSizeAndTypeFromCurrentPosition(
      BytesContainer bytes) {
    int fieldSize = VarIntSerializer.readAsInteger(bytes);
    PropertyType type = readOType(bytes, false);
    return new Tuple<>(fieldSize, type);
  }

  public void deserializeDebug(
      DatabaseSessionInternal db, BytesContainer bytes,
      RecordSerializationDebug debugInfo,
      ImmutableSchema schema) {

    int headerLength = VarIntSerializer.readAsInteger(bytes);
    int headerPos = bytes.offset;

    debugInfo.properties = new ArrayList<>();
    int last = 0;
    String fieldName;
    PropertyType type;
    int cumulativeLength = 0;
    while (true) {
      RecordSerializationDebugProperty debugProperty = new RecordSerializationDebugProperty();
      GlobalProperty prop;

      int fieldLength;

      try {
        if (bytes.offset >= headerPos + headerLength) {
          break;
        }

        final int len = VarIntSerializer.readAsInteger(bytes);
        debugInfo.properties.add(debugProperty);
        if (len > 0) {
          // PARSE FIELD NAME
          fieldName = stringFromBytesIntern(bytes.bytes, bytes.offset, len);
          bytes.skip(len);

          Tuple<Integer, PropertyType> valuePositionAndType =
              getFieldSizeAndTypeFromCurrentPosition(bytes);
          fieldLength = valuePositionAndType.getFirstVal();
          type = valuePositionAndType.getSecondVal();
        } else {
          // LOAD GLOBAL PROPERTY BY ID
          final int id = (len * -1) - 1;
          debugProperty.globalId = id;
          prop = schema.getGlobalPropertyById(id);
          fieldLength = VarIntSerializer.readAsInteger(bytes);
          debugProperty.valuePos = headerPos + headerLength + cumulativeLength;
          if (prop != null) {
            fieldName = prop.getName();
            type = getPropertyTypeFromStream(prop, bytes);
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

  @SuppressWarnings("unchecked")
  protected int writeEmbeddedMap(
      DatabaseSessionInternal session, BytesContainer bytes,
      Map<Object, Object> map,
      ImmutableSchema schema,
      PropertyEncryption encryption) {
    final int fullPos = VarIntSerializer.write(bytes, map.size());
    for (Entry<Object, Object> entry : map.entrySet()) {
      // TODO:check skip of complex types
      // FIXME: changed to support only string key on map
      PropertyType type = PropertyType.STRING;
      writeOType(bytes, bytes.alloc(1), type);
      var key = entry.getKey();
      if (key == null) {
        throw new SerializationException("Maps with null keys are not supported");
      }
      writeString(bytes, entry.getKey().toString());
      final Object value = entry.getValue();
      if (value != null) {
        type = getTypeFromValueEmbedded(value);
        if (type == null) {
          throw new SerializationException(
              "Impossible serialize value of type "
                  + value.getClass()
                  + " with the EntityImpl binary serializer");
        }
        writeOType(bytes, bytes.alloc(1), type);
        serializeValue(session, bytes, value, type, null, schema, encryption);
      } else {
        // signal for null value
        int pointer = bytes.alloc(1);
        ByteSerializer.INSTANCE.serialize((byte) -1, bytes.bytes, pointer);
      }
    }

    return fullPos;
  }

  protected Object readEmbeddedMap(DatabaseSessionInternal db, final BytesContainer bytes,
      final RecordElement owner) {
    int size = VarIntSerializer.readAsInteger(bytes);
    final TrackedMap<Object> result = new TrackedMap<>(owner);
    for (int i = 0; i < size; i++) {
      PropertyType keyType = readOType(bytes, false);
      Object key = deserializeValue(db, bytes, keyType, result);
      final PropertyType type = HelperClasses.readType(bytes);
      if (type != null) {
        Object value = deserializeValue(db, bytes, type, result);
        result.putInternal(key, value);
      } else {
        result.putInternal(key, null);
      }
    }
    return result;
  }

  protected List<MapRecordInfo> getPositionsFromEmbeddedMap(
      DatabaseSessionInternal session, final BytesContainer bytes, ImmutableSchema schema) {
    List<MapRecordInfo> retList = new ArrayList<>();

    int numberOfElements = VarIntSerializer.readAsInteger(bytes);

    for (int i = 0; i < numberOfElements; i++) {
      PropertyType keyType = readOType(bytes, false);
      String key = readString(bytes);
      PropertyType valueType = HelperClasses.readType(bytes);
      MapRecordInfo recordInfo = new MapRecordInfo();
      recordInfo.fieldType = valueType;
      recordInfo.key = key;
      recordInfo.keyType = keyType;
      int currentOffset = bytes.offset;

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

  protected int writeRidBag(BytesContainer bytes, RidBag ridbag) {
    int positionOffset = bytes.offset;
    HelperClasses.writeRidBag(bytes, ridbag);
    return positionOffset;
  }

  protected RidBag readRidbag(DatabaseSessionInternal session, BytesContainer bytes) {
    return HelperClasses.readRidbag(session, bytes);
  }

  public Object deserializeValue(
      DatabaseSessionInternal session, final BytesContainer bytes, final PropertyType type,
      final RecordElement owner) {
    RecordElement entity = owner;
    while (!(entity instanceof EntityImpl) && entity != null) {
      entity = entity.getOwner();
    }
    ImmutableSchema schema = EntityInternalUtils.getImmutableSchema((EntityImpl) entity);
    return deserializeValue(session, bytes, type, owner, true, -1, false, schema);
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
        value = DecimalSerializer.INSTANCE.deserialize(bytes.bytes, bytes.offset);
        bytes.skip(DecimalSerializer.INSTANCE.getObjectSize(bytes.bytes, bytes.offset));
        break;
      case LINKBAG:
        RidBag bag = readRidbag(session, bytes);
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
                new ResultBinary(session, schema, bytesRepresentation, 0,
                    bytesRepresentation.length, this);
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
      var entity = (EntityImpl) value;
      EntityInternalUtils.addOwner(entity, owner);
      RecordInternal.unsetDirty(entity);
    }

    return value;
  }

  // returns begin position and length for each value in embedded collection
  private List<RecordInfo> getPositionsFromEmbeddedCollection(
      DatabaseSessionInternal session, final BytesContainer bytes, ImmutableSchema schema) {
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
    int startOffset = bytes.offset;
    return new ResultBinary(db, schema, bytes.bytes, startOffset, valueLength, this);
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

  protected void skipClassName(BytesContainer bytes) {
    final int classNameLen = VarIntSerializer.readAsInteger(bytes);
    bytes.skip(classNameLen);
  }

  @Override
  public BinaryComparator getComparator() {
    return comparator;
  }
}
