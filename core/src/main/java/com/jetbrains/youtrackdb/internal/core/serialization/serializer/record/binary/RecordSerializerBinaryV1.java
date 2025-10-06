package com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary;

import static com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary.HelperClasses.MILLISEC_PER_DAY;
import static com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary.HelperClasses.bytesFromString;
import static com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary.HelperClasses.convertDayToTimezone;
import static com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary.HelperClasses.getGlobalProperty;
import static com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary.HelperClasses.getLinkedType;
import static com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary.HelperClasses.getTypeFromValueEmbedded;
import static com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary.HelperClasses.readBinary;
import static com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary.HelperClasses.readByte;
import static com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary.HelperClasses.readInteger;
import static com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary.HelperClasses.readLinkCollection;
import static com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary.HelperClasses.readLinkMap;
import static com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary.HelperClasses.readLong;
import static com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary.HelperClasses.readOType;
import static com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary.HelperClasses.readOptimizedLink;
import static com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary.HelperClasses.readString;
import static com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary.HelperClasses.stringFromBytesIntern;
import static com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary.HelperClasses.writeBinary;
import static com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary.HelperClasses.writeLinkCollection;
import static com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary.HelperClasses.writeLinkMap;
import static com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary.HelperClasses.writeOType;
import static com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary.HelperClasses.writeOptimizedLink;
import static com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary.HelperClasses.writeString;

import com.jetbrains.youtrackdb.api.exception.BaseException;
import com.jetbrains.youtrackdb.api.exception.DatabaseException;
import com.jetbrains.youtrackdb.api.exception.ValidationException;
import com.jetbrains.youtrackdb.api.record.Identifiable;
import com.jetbrains.youtrackdb.api.record.RID;
import com.jetbrains.youtrackdb.internal.common.collection.MultiValue;
import com.jetbrains.youtrackdb.internal.common.serialization.types.ByteSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.DecimalSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.IntegerSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.LongSerializer;
import com.jetbrains.youtrackdb.internal.common.util.RawPair;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.db.record.EntityEmbeddedListImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.EntityEmbeddedMapImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.EntityEmbeddedSetImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.EntityLinkListImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.EntityLinkSetImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.RecordElement;
import com.jetbrains.youtrackdb.internal.core.db.record.ridbag.LinkBag;
import com.jetbrains.youtrackdb.internal.core.exception.SerializationException;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.GlobalProperty;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.ImmutableSchema;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.ImmutableSchemaClass;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.ImmutableSchemaProperty;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaSnapshot;
import com.jetbrains.youtrackdb.internal.core.metadata.security.PropertyEncryption;
import com.jetbrains.youtrackdb.internal.core.record.RecordAbstract;
import com.jetbrains.youtrackdb.internal.core.record.impl.EmbeddedEntityImpl;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityEntry;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.serialization.EntitySerializable;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary.HelperClasses.MapRecordInfo;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary.HelperClasses.Tuple;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.AbsoluteChange;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.AbstractLinkBag;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.BTreeBasedLinkBag;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.Change;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.EmbeddedLinkBag;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.LinkBagPointer;
import com.jetbrains.youtrackdb.internal.core.util.DateHelper;
import java.io.IOException;
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
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

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

  @Override
  public void deserializePartial(DatabaseSessionEmbedded db, EntityImpl entity,
      BytesContainer bytes, String[] iFields) {
    // TRANSFORMS FIELDS FOM STRINGS TO BYTE[]
    final var fields = new byte[iFields.length][];
    for (var i = 0; i < iFields.length; ++i) {
      fields[i] = bytesFromString(iFields[i]);
    }

    String fieldName;
    PropertyTypeInternal type;
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
        type = PropertyTypeInternal.convertFromPublicType(prop.getType());

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

  @Override
  @Nullable
  public BinaryField deserializeField(
      DatabaseSessionInternal session, final BytesContainer bytes,
      final ImmutableSchemaClass iClass,
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
        final PropertyTypeInternal type;
        type = PropertyTypeInternal.convertFromPublicType(prop.getType());

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

  @Override
  public void deserialize(DatabaseSessionEmbedded session, final EntityImpl entity,
      final BytesContainer bytes) {
    var headerLength = VarIntSerializer.readAsInteger(bytes);
    var headerStart = bytes.offset;
    var valuesStart = headerStart + headerLength;
    var last = 0;
    String fieldName;
    PropertyTypeInternal type;
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
        type = PropertyTypeInternal.convertFromPublicType(prop.getType());
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

    final var rec = (RecordAbstract) entity;
    rec.clearSource();

    if (last > bytes.offset) {
      bytes.offset = last;
    }
  }

  public void deserializeWithClassName(DatabaseSessionEmbedded db, final EntityImpl entity,
      final BytesContainer bytes) {

    final var className = readString(bytes);
    if (!className.isEmpty()) {
      entity.setClassNameWithoutPropertiesPostProcessing(className);
    }

    deserialize(db, entity, bytes);
  }

  @Override
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
      final Map<String, ? extends ImmutableSchemaProperty> props,
      ImmutableSchema schema,
      PropertyEncryption encryption) {
    ImmutableSchemaClass result = null;
    if (entity != null) {
      result = entity.getImmutableSchemaClass();
    }
    var cls = result;
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

      final PropertyTypeInternal type;
      if (value != null) {
        type = EntitySerializerDelta.getFieldType(field.getValue());
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
            getLinkedType(cls, type, field.getKey()),
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

  private void serializeEntity(
      DatabaseSessionInternal session, final EntityImpl entity,
      final BytesContainer bytes,
      final ImmutableSchemaClass clazz,
      ImmutableSchema schema,
      PropertyEncryption encryption) {
    // allocate space for header length

    final var props = clazz != null ? clazz.getPropertiesMap() : null;
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
      schema = session.getMetadata().getFastImmutableSchema();
    }
    ImmutableSchemaClass result = null;
    if (entity != null) {
      result = entity.getImmutableSchemaClass();
    }
    var clazz = result;
    if (clazz != null && entity.isEmbedded()) {
      writeString(bytes, clazz.getName());
    } else {
      writeEmptyString(bytes);
    }
    var encryption = entity.propertyEncryption;
    serializeEntity(session, entity, bytes, clazz, schema, encryption);
  }

  @Override
  public void serialize(DatabaseSessionInternal session, final EntityImpl entity,
      final BytesContainer bytes) {
    ImmutableSchema schema = null;
    if (entity != null) {
      schema = session.getMetadata().getFastImmutableSchema();
    }
    var encryption = entity.propertyEncryption;
    var clazz = entity.getImmutableSchemaClass();
    serializeEntity(session, entity, bytes, clazz, schema, encryption);
  }

  @Nullable
  protected <RET> RET deserializeFieldTypedLoopAndReturn(
      DatabaseSessionEmbedded session, BytesContainer bytes,
      String iFieldName,
      final SchemaSnapshot schema,
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
          var value = deserializeValue(session, bytes, type, null, false
          );
          //noinspection unchecked
          return (RET) value;
        }
        cumulativeLength += fieldLength;
      } else {
        // LOAD GLOBAL PROPERTY BY ID
        final var id = (len * -1) - 1;
        final var prop = schema.getGlobalPropertyById(id);
        final var fieldLength = VarIntSerializer.readAsInteger(bytes);
        var type = PropertyTypeInternal.convertFromPublicType(prop.getType());

        if (iFieldName.equals(prop.getName())) {

          if (fieldLength == 0) {
            return null;
          }

          bytes.offset = cumulativeLength;

          var value = deserializeValue(session, bytes, type, null,
              false
          );
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
  private static Tuple<Integer, PropertyTypeInternal> getFieldSizeAndTypeFromCurrentPosition(
      BytesContainer bytes) {
    var fieldSize = VarIntSerializer.readAsInteger(bytes);
    var type = readOType(bytes, false);
    return new Tuple<>(fieldSize, type);
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
      var type = PropertyTypeInternal.STRING;
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

  protected Object readEmbeddedMap(DatabaseSessionEmbedded db, final BytesContainer bytes,
      final RecordElement owner) {
    var size = VarIntSerializer.readAsInteger(bytes);
    final var result = new EntityEmbeddedMapImpl<Object>(owner);
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
      DatabaseSessionEmbedded session, final BytesContainer bytes) {
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
        deserializeValue(session, bytes, valueType, null,
            true);
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

  protected static int writeLinkSet(DatabaseSessionInternal session, BytesContainer bytes,
      EntityLinkSetImpl linkSet) {
    var positionOffset = bytes.offset;
    linkSet.checkAndConvert(session.getTransactionInternal());

    byte configByte = 0;

    if (linkSet.isEmbedded()) {
      configByte |= 1;
    }

    // alloc will move offset and do skip
    HelperClasses.writeByte(bytes, configByte);

    var delegate = (AbstractLinkBag) linkSet.getDelegate();
    VarIntSerializer.write(bytes, delegate.size());

    if (linkSet.isEmbedded()) {
      writeEmbeddedLinkBagDelegate(bytes, delegate);
    } else {
      var btreeLinkBag = (BTreeBasedLinkBag) delegate;
      writeBTreeBasedLinkBag(session, bytes, btreeLinkBag);
    }

    return positionOffset;
  }

  protected static int writeLinkBag(DatabaseSessionInternal session, BytesContainer bytes,
      LinkBag ridbag) {
    var positionOffset = bytes.offset;
    ridbag.checkAndConvert();
    byte configByte = 0;

    if (ridbag.isEmbedded()) {
      configByte |= 1;
    }

    // alloc will move offset and do skip
    HelperClasses.writeByte(bytes, configByte);

    var delegate = (AbstractLinkBag) ridbag.getDelegate();
    VarIntSerializer.write(bytes, delegate.size());

    if (ridbag.isEmbedded()) {
      writeEmbeddedLinkBagDelegate(bytes, delegate);
    } else {
      var btreeLinkBag = (BTreeBasedLinkBag) delegate;
      writeBTreeBasedLinkBag(session, bytes, btreeLinkBag);
    }

    return positionOffset;
  }

  private static void writeBTreeBasedLinkBag(DatabaseSessionInternal session, BytesContainer bytes,
      BTreeBasedLinkBag btreeLinkBag) {
    var pointer = btreeLinkBag.getCollectionPointer();

    var currentTx = session.getActiveTransaction();
    final var context = currentTx.getRecordSerializationContext();

    if (pointer == null) {
      final var collectionId = btreeLinkBag.getOwnerEntity().getIdentity().getCollectionId();
      assert collectionId > -1;
      try {
        final var storage = (AbstractStorage) session.getStorage();
        final var atomicOperation =
            storage.getAtomicOperationsManager().getCurrentOperation();
        assert atomicOperation != null;
        pointer = session
            .getBTreeCollectionManager()
            .createBTree(collectionId, atomicOperation, session);

        btreeLinkBag.setCollectionPointer(pointer);
      } catch (IOException e) {
        throw BaseException.wrapException(
            new DatabaseException(session.getDatabaseName(), "Error during creation of linkbag"),
            e,
            session.getDatabaseName());
      }
    }

    VarIntSerializer.write(bytes, pointer.fileId());
    VarIntSerializer.write(bytes, pointer.linkBagId());

    btreeLinkBag.handleContextBTree(context, pointer);
  }

  private static void writeEmbeddedLinkBagDelegate(BytesContainer bytes, AbstractLinkBag delegate) {
    delegate.getChanges().forEach(ridChangeRawPair -> {
      var recId = ridChangeRawPair.first();
      assert recId.isPersistent();

      var change = ridChangeRawPair.second().getValue();
      assert change >= 0;
      if (change > 0) {
        HelperClasses.writeByte(bytes, (byte) 1);
        HelperClasses.writeLinkOptimized(bytes, recId);
        VarIntSerializer.write(bytes, change);
      }
    });
    HelperClasses.writeByte(bytes, (byte) 0);
  }

  protected static EntityLinkSetImpl readLinkSet(DatabaseSessionEmbedded session,
      BytesContainer bytes) {
    var configByte = bytes.bytes[bytes.offset++];
    var isEmbedded = (configByte & 1) != 0;

    EntityLinkSetImpl ridbag;
    var linkBagSize = VarIntSerializer.readAsInteger(bytes);
    if (isEmbedded) {
      var embeddedBagDelegate = readEmbeddedLinkBag(session, bytes, linkBagSize, 1);
      ridbag = new EntityLinkSetImpl(session, embeddedBagDelegate);
    } else {
      var btreeLinkBag = readBTreeBasedLinkBag(session, bytes, linkBagSize, 1);
      ridbag = new EntityLinkSetImpl(session, btreeLinkBag);
    }

    return ridbag;
  }

  protected static LinkBag readLinkBag(DatabaseSessionInternal session, BytesContainer bytes) {
    var configByte = bytes.bytes[bytes.offset++];
    var isEmbedded = (configByte & 1) != 0;

    LinkBag ridbag;
    var linkBagSize = VarIntSerializer.readAsInteger(bytes);
    if (isEmbedded) {
      var embeddedBagDelegate = readEmbeddedLinkBag(session, bytes, linkBagSize, Integer.MAX_VALUE);
      ridbag = new LinkBag(session, embeddedBagDelegate);
    } else {
      var btreeLinkBag = readBTreeBasedLinkBag(session, bytes, linkBagSize, Integer.MAX_VALUE);
      ridbag = new LinkBag(session, btreeLinkBag);
    }

    return ridbag;
  }

  @Nonnull
  private static BTreeBasedLinkBag readBTreeBasedLinkBag(DatabaseSessionInternal session,
      BytesContainer bytes,
      int linkBagSize, int counterMaxValue) {
    var fileId = VarIntSerializer.readAsLong(bytes);
    var linkBagId = VarIntSerializer.readAsLong(bytes);
    assert fileId > -1;

    var pointer = new LinkBagPointer(fileId, linkBagId);

    return new BTreeBasedLinkBag(session, pointer, linkBagSize, counterMaxValue);
  }

  @Nonnull
  private static EmbeddedLinkBag readEmbeddedLinkBag(DatabaseSessionInternal session,
      BytesContainer bytes,
      int linkBagSize, int counterMaxValue) {
    var changes = new ArrayList<RawPair<RID, Change>>();
    var continueFlag = readByte(bytes);

    while (continueFlag > 0) {
      var rid = HelperClasses.readLinkOptimizedEmbedded(session, bytes);
      var counter = VarIntSerializer.readAsInteger(bytes);
      changes.add(new RawPair<>(rid, new AbsoluteChange(counter)));
      continueFlag = readByte(bytes);
    }

    return new EmbeddedLinkBag(changes, session, linkBagSize, counterMaxValue);
  }

  @Override
  public Object deserializeValue(
      DatabaseSessionEmbedded db, final BytesContainer bytes, final PropertyTypeInternal type,
      final RecordElement owner) {
    var entity = owner;
    while (!(entity instanceof EntityImpl) && entity != null) {
      entity = entity.getOwner();
    }

    return deserializeValue(db, bytes, type, owner, false);
  }

  protected Object deserializeValue(
      DatabaseSessionEmbedded session, final BytesContainer bytes,
      final PropertyTypeInternal type,
      final RecordElement owner,
      boolean justRunThrough) {
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
        value = deserializeEmbeddedAsDocument(session, bytes, owner);
        break;
      case EMBEDDEDSET:
        value = readEmbeddedSet(session, bytes, owner);
        break;
      case EMBEDDEDLIST:
        value = readEmbeddedList(session, bytes, owner);
        break;
      case LINKSET:
        value = readLinkSet(session, bytes);
        break;
      case LINKLIST:
        EntityLinkListImpl collectionList = null;
        if (!justRunThrough) {
          collectionList = new EntityLinkListImpl(owner);
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
        value = readEmbeddedMap(session, bytes, owner);
        break;
      case DECIMAL:
        value = DecimalSerializer.staticDeserialize(bytes.bytes, bytes.offset);
        bytes.skip(DecimalSerializer.staticGetObjectSize(bytes.bytes, bytes.offset));
        break;
      case LINKBAG:
        var bag = readLinkBag(session, bytes);
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
      final PropertyTypeInternal type,
      final PropertyTypeInternal linkedType,
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
        pointer = writeLinkSet(session, bytes, (EntityLinkSetImpl) value);
        break;
      case LINKLIST:
        var ridCollection = (Collection<Identifiable>) value;
        pointer = writeLinkCollection(session, bytes, ridCollection);
        break;
      case LINK:
        if (!(value instanceof Identifiable identifiable)) {
          throw new ValidationException(session.getDatabaseName(),
              "Value '" + value + "' is not a Identifiable");
        }

        if (!identifiable.getIdentity().isPersistent()) {
          throw new IllegalStateException(
              "Non-persistent link " + identifiable + " cannot be serialized");
        }
        pointer = writeOptimizedLink(session, bytes, identifiable);
        break;
      case LINKMAP:
        pointer = writeLinkMap(session, bytes, (Map<Object, Identifiable>) value);
        break;
      case EMBEDDEDMAP:
        pointer = writeEmbeddedMap(session, bytes, (Map<Object, Object>) value, schema, encryption);
        break;
      case LINKBAG:
        pointer = writeLinkBag(session, bytes, (LinkBag) value);
        break;
    }
    return pointer;
  }

  protected int writeEmbeddedCollection(
      DatabaseSessionInternal session, final BytesContainer bytes,
      final Collection<?> value,
      final PropertyTypeInternal linkedType,
      ImmutableSchema schema,
      PropertyEncryption encryption) {
    final var pos = VarIntSerializer.write(bytes, value.size());
    writeOType(bytes, bytes.alloc(1),
        linkedType != null ? linkedType : PropertyTypeInternal.EMBEDDED);
    for (var itemValue : value) {
      if (itemValue == null) {
        writeOType(bytes, bytes.alloc(1), null);
        continue;
      }
      PropertyTypeInternal type;
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

  protected Object deserializeEmbeddedAsDocument(
      DatabaseSessionEmbedded db, final BytesContainer bytes, final RecordElement owner) {
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
      final var rec = (RecordAbstract) entity;
      rec.unsetDirty();
    }

    return value;
  }

  @Nullable
  protected EntityEmbeddedSetImpl<?> readEmbeddedSet(DatabaseSessionEmbedded db,
      final BytesContainer bytes,
      final RecordElement owner) {

    final var items = VarIntSerializer.readAsInteger(bytes);
    var type = readOType(bytes, false);

    if (type != null) {
      var found = new EntityEmbeddedSetImpl<>(owner);
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

  @Nullable
  protected EntityEmbeddedListImpl<?> readEmbeddedList(DatabaseSessionEmbedded db,
      final BytesContainer bytes,
      final RecordElement owner) {

    final var items = VarIntSerializer.readAsInteger(bytes);
    var type = readOType(bytes, false);

    if (type != null) {
      var found = new EntityEmbeddedListImpl<>(owner);
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
    final var classNameLen = VarIntSerializer.readAsInteger(bytes);
    bytes.skip(classNameLen);
  }

  @Override
  public BinaryComparator getComparator() {
    return comparator;
  }
}
