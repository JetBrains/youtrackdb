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

package com.jetbrains.youtrack.db.internal.core.serialization.serializer.result.binary;

import com.jetbrains.youtrack.db.api.common.query.BasicResult;
import com.jetbrains.youtrack.db.api.exception.ValidationException;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.remote.query.RemoteResult;
import com.jetbrains.youtrack.db.internal.common.collection.MultiValue;
import com.jetbrains.youtrack.db.internal.common.serialization.types.DecimalSerializer;
import com.jetbrains.youtrack.db.internal.common.serialization.types.IntegerSerializer;
import com.jetbrains.youtrack.db.internal.common.serialization.types.LongSerializer;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.exception.SerializationException;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.BytesContainer;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.HelperClasses;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.VarIntSerializer;
import com.jetbrains.youtrack.db.internal.core.sql.executor.ResultInternal;
import com.jetbrains.youtrack.db.internal.core.util.DateHelper;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataInput;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataOutput;
import com.jetbrains.youtrack.db.internal.remote.RemoteDatabaseSessionInternal;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.TimeZone;
import javax.annotation.Nullable;

public class ResultSerializerNetwork {

  private static final RecordId NULL_RECORD_ID = new RecordId(-2, RID.COLLECTION_POS_INVALID);
  private static final long MILLISEC_PER_DAY = 86400000;

  public ResultSerializerNetwork() {
  }

  public RemoteResult deserialize(RemoteDatabaseSessionInternal session,
      final BytesContainer bytes) {
    final var resultInternal = new RemoteResultImpl(session);

    String fieldName;
    PropertyTypeInternal type;
    var size = VarIntSerializer.readAsInteger(bytes);
    // fields
    while (size-- > 0) {
      final var len = VarIntSerializer.readAsInteger(bytes);
      // PARSE FIELD NAME
      fieldName = stringFromBytes(bytes.bytes, bytes.offset, len).intern();
      bytes.skip(len);
      type = readOType(bytes);

      if (type == null) {
        resultInternal.setProperty(fieldName, null);
      } else {
        final var value = deserializeValue(session, bytes, type);
        resultInternal.setProperty(fieldName, value);
      }
    }

    var metadataSize = VarIntSerializer.readAsInteger(bytes);
    // metadata
    while (metadataSize-- > 0) {
      final var len = VarIntSerializer.readAsInteger(bytes);
      // PARSE FIELD NAME
      fieldName = stringFromBytes(bytes.bytes, bytes.offset, len).intern();
      bytes.skip(len);
      type = readOType(bytes);

      if (type == null) {
        resultInternal.setMetadata(fieldName, null);
      } else {
        final var value = deserializeValue(session, bytes, type);
        resultInternal.setMetadata(fieldName, value);
      }
    }

    return resultInternal;
  }

  public ResultInternal deserialize(DatabaseSessionEmbedded session,
      final BytesContainer bytes) {
    final var resultInternal = new ResultInternal(session);

    String fieldName;
    PropertyTypeInternal type;
    var size = VarIntSerializer.readAsInteger(bytes);
    // fields
    while (size-- > 0) {
      final var len = VarIntSerializer.readAsInteger(bytes);
      // PARSE FIELD NAME
      fieldName = stringFromBytes(bytes.bytes, bytes.offset, len).intern();
      bytes.skip(len);
      type = readOType(bytes);

      if (type == null) {
        resultInternal.setProperty(fieldName, null);
      } else {
        final var value = deserializeValue(session, bytes, type);
        resultInternal.setProperty(fieldName, value);
      }
    }

    var metadataSize = VarIntSerializer.readAsInteger(bytes);
    // metadata
    while (metadataSize-- > 0) {
      final var len = VarIntSerializer.readAsInteger(bytes);
      // PARSE FIELD NAME
      fieldName = stringFromBytes(bytes.bytes, bytes.offset, len).intern();
      bytes.skip(len);
      type = readOType(bytes);

      if (type == null) {
        resultInternal.setMetadata(fieldName, null);
      } else {
        final var value = deserializeValue(session, bytes, type);
        resultInternal.setMetadata(fieldName, value);
      }
    }

    return resultInternal;
  }

  public void serialize(DatabaseSessionInternal session, final BasicResult result,
      final BytesContainer bytes) {
    var propertyNames = result.getPropertyNames();

    VarIntSerializer.write(bytes, propertyNames.size());
    for (var property : propertyNames) {
      writeString(bytes, property);
      var propertyValue = result.getProperty(property);
      if (propertyValue != null) {
        if (propertyValue instanceof Result) {
          if (((Result) propertyValue).isEntity()) {
            var elem = ((Result) propertyValue).asEntity();
            if (elem.isEmbedded()) {
              writeOType(bytes, bytes.alloc(1), PropertyTypeInternal.EMBEDDED);
              serializeValue(session, bytes, propertyValue, PropertyTypeInternal.EMBEDDED);
            } else {
              writeOType(bytes, bytes.alloc(1), PropertyTypeInternal.LINK);
              serializeValue(session, bytes, session.refreshRid(elem.getIdentity()),
                  PropertyTypeInternal.LINK);
            }
          } else {
            writeOType(bytes, bytes.alloc(1), PropertyTypeInternal.EMBEDDED);
            serializeValue(session, bytes, propertyValue, PropertyTypeInternal.EMBEDDED);
          }
        } else {
          final var type = PropertyTypeInternal.getTypeByValue(propertyValue);
          if (type == null) {
            throw new SerializationException(session,
                "Impossible serialize value of type "
                    + propertyValue.getClass()
                    + " with the Result binary serializer");
          }
          writeOType(bytes, bytes.alloc(1), type);
          serializeValue(session, bytes, propertyValue, type);
        }
      } else {
        writeOType(bytes, bytes.alloc(1), null);
      }
    }

    if (result instanceof ResultInternal resultInternal) {
      var metadataKeys = resultInternal.getMetadataKeys();
      VarIntSerializer.write(bytes, metadataKeys.size());

      for (var field : metadataKeys) {
        writeString(bytes, field);
        final var value = resultInternal.getMetadata(field);
        if (value != null) {
          if (value instanceof Result) {
            writeOType(bytes, bytes.alloc(1), PropertyTypeInternal.EMBEDDED);
            serializeValue(session, bytes, value, PropertyTypeInternal.EMBEDDED);
          } else {
            final var type = PropertyTypeInternal.getTypeByValue(value);
            if (type == null) {
              throw new SerializationException(session,
                  "Impossible serialize value of type "
                      + value.getClass()
                      + " with the Result binary serializer");
            }
            writeOType(bytes, bytes.alloc(1), type);
            serializeValue(session, bytes, value, type);
          }
        } else {
          writeOType(bytes, bytes.alloc(1), null);
        }
      }
    } else {
      VarIntSerializer.write(bytes, 0);
    }
  }

  @Nullable
  protected static PropertyTypeInternal readOType(final BytesContainer bytes) {
    var val = readByte(bytes);
    if (val == -1) {
      return null;
    }
    return PropertyTypeInternal.getById(val);
  }

  private static void writeOType(BytesContainer bytes, int pos, PropertyTypeInternal type) {
    if (type == null) {
      bytes.bytes[pos] = (byte) -1;
    } else {
      bytes.bytes[pos] = (byte) type.getId();
    }
  }

  public Object deserializeValue(RemoteDatabaseSessionInternal session, BytesContainer bytes,
      PropertyTypeInternal type) {
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
        value = readString(bytes);
        break;
      case DOUBLE:
        value = Double.longBitsToDouble(readLong(bytes));
        break;
      case FLOAT:
        value = Float.intBitsToFloat(readInteger(bytes));
        break;
      case BYTE:
        value = readByte(bytes);
        break;
      case BOOLEAN:
        value = readByte(bytes) == 1;
        break;
      case DATETIME:
        value = new Date(VarIntSerializer.readAsLong(bytes));
        break;
      case DATE:
        var savedTime = VarIntSerializer.readAsLong(bytes) * MILLISEC_PER_DAY;
        savedTime =
            convertDayToTimezone(
                TimeZone.getTimeZone("GMT"), DateHelper.getDatabaseTimeZone(session), savedTime);
        value = new Date(savedTime);
        break;
      case EMBEDDED:
        value = deserialize(session, bytes);
        break;
      case EMBEDDEDSET:
        value = readEmbeddedCollection(session, bytes, new LinkedHashSet<>());
        break;
      case EMBEDDEDLIST:
        value = readEmbeddedCollection(session, bytes, new ArrayList<>());
        break;
      case LINKSET:
        value = readLinkCollection(bytes, new LinkedHashSet<>());
        break;
      case LINKLIST:
        value = readLinkCollection(bytes, new ArrayList<>());
        break;
      case BINARY:
        value = readBinary(bytes);
        break;
      case LINK:
        value = readOptimizedLink(bytes);
        break;
      case LINKMAP:
        value = readLinkMap(session, bytes);
        break;
      case EMBEDDEDMAP:
        value = readEmbeddedMap(session, bytes);
        break;
      case DECIMAL:
        value = DecimalSerializer.staticDeserialize(bytes.bytes, bytes.offset);
        bytes.skip(DecimalSerializer.staticGetObjectSize(bytes.bytes, bytes.offset));
        break;
      case LINKBAG:
        throw new UnsupportedOperationException("LINKBAG should never appear in a projection");
    }
    return value;
  }

  public Object deserializeValue(DatabaseSessionEmbedded session, BytesContainer bytes,
      PropertyTypeInternal type) {
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
        value = readString(bytes);
        break;
      case DOUBLE:
        value = Double.longBitsToDouble(readLong(bytes));
        break;
      case FLOAT:
        value = Float.intBitsToFloat(readInteger(bytes));
        break;
      case BYTE:
        value = readByte(bytes);
        break;
      case BOOLEAN:
        value = readByte(bytes) == 1;
        break;
      case DATETIME:
        value = new Date(VarIntSerializer.readAsLong(bytes));
        break;
      case DATE:
        var savedTime = VarIntSerializer.readAsLong(bytes) * MILLISEC_PER_DAY;
        savedTime =
            convertDayToTimezone(
                TimeZone.getTimeZone("GMT"), DateHelper.getDatabaseTimeZone(session), savedTime);
        value = new Date(savedTime);
        break;
      case EMBEDDED:
        value = deserialize(session, bytes);
        break;
      case EMBEDDEDSET:
        value = readEmbeddedCollection(session, bytes, new LinkedHashSet<>());
        break;
      case EMBEDDEDLIST:
        value = readEmbeddedCollection(session, bytes, new ArrayList<>());
        break;
      case LINKSET:
        value = readLinkCollection(bytes, new LinkedHashSet<>());
        break;
      case LINKLIST:
        value = readLinkCollection(bytes, new ArrayList<>());
        break;
      case BINARY:
        value = readBinary(bytes);
        break;
      case LINK:
        value = readOptimizedLink(bytes);
        break;
      case LINKMAP:
        value = readLinkMap(session, bytes);
        break;
      case EMBEDDEDMAP:
        value = readEmbeddedMap(session, bytes);
        break;
      case DECIMAL:
        value = DecimalSerializer.staticDeserialize(bytes.bytes, bytes.offset);
        bytes.skip(DecimalSerializer.staticGetObjectSize(bytes.bytes, bytes.offset));
        break;
      case LINKBAG:
        throw new UnsupportedOperationException("LINKBAG should never appear in a projection");
    }
    return value;
  }

  private static byte[] readBinary(BytesContainer bytes) {
    var n = VarIntSerializer.readAsInteger(bytes);
    var newValue = new byte[n];
    System.arraycopy(bytes.bytes, bytes.offset, newValue, 0, newValue.length);
    bytes.skip(n);
    return newValue;
  }

  private Map<Object, Identifiable> readLinkMap(RemoteDatabaseSessionInternal session,
      final BytesContainer bytes) {
    var size = VarIntSerializer.readAsInteger(bytes);
    Map<Object, Identifiable> result = new HashMap<>();
    while ((size--) > 0) {
      var keyType = readOType(bytes);
      var key = deserializeValue(session, bytes, keyType);
      var value = readOptimizedLink(bytes);
      if (value.equals(NULL_RECORD_ID)) {
        result.put(key, null);
      } else {
        result.put(key, value);
      }
    }
    return result;
  }

  private Map<Object, Identifiable> readLinkMap(DatabaseSessionEmbedded session,
      final BytesContainer bytes) {
    var size = VarIntSerializer.readAsInteger(bytes);
    Map<Object, Identifiable> result = new HashMap<>();
    while ((size--) > 0) {
      var keyType = readOType(bytes);
      var key = deserializeValue(session, bytes, keyType);
      var value = readOptimizedLink(bytes);
      if (value.equals(NULL_RECORD_ID)) {
        result.put(key, null);
      } else {
        result.put(key, value);
      }
    }
    return result;
  }


  private Map<String, Object> readEmbeddedMap(RemoteDatabaseSessionInternal session,
      final BytesContainer bytes) {
    var size = VarIntSerializer.readAsInteger(bytes);
    var map = new HashMap<String, Object>();
    String fieldName;
    PropertyTypeInternal type;
    while ((size--) > 0) {
      final var len = VarIntSerializer.readAsInteger(bytes);
      // PARSE FIELD NAME
      fieldName = stringFromBytes(bytes.bytes, bytes.offset, len).intern();
      bytes.skip(len);
      type = readOType(bytes);

      if (type == null) {
        map.put(fieldName, null);
      } else {
        final var value = deserializeValue(session, bytes, type);
        map.put(fieldName, value);
      }
    }
    return map;
  }

  private Map<String, Object> readEmbeddedMap(DatabaseSessionEmbedded session,
      final BytesContainer bytes) {
    var size = VarIntSerializer.readAsInteger(bytes);
    var map = new HashMap<String, Object>();
    String fieldName;
    PropertyTypeInternal type;
    while ((size--) > 0) {
      final var len = VarIntSerializer.readAsInteger(bytes);
      // PARSE FIELD NAME
      fieldName = stringFromBytes(bytes.bytes, bytes.offset, len).intern();
      bytes.skip(len);
      type = readOType(bytes);

      if (type == null) {
        map.put(fieldName, null);
      } else {
        final var value = deserializeValue(session, bytes, type);
        map.put(fieldName, value);
      }
    }

    return map;
  }

  private static Collection<Identifiable> readLinkCollection(
      BytesContainer bytes, Collection<Identifiable> found) {
    final var items = VarIntSerializer.readAsInteger(bytes);
    for (var i = 0; i < items; i++) {
      var id = readOptimizedLink(bytes);
      if (id.equals(NULL_RECORD_ID)) {
        found.add(null);
      } else {
        found.add(id);
      }
    }
    return found;
  }

  private static RID readOptimizedLink(final BytesContainer bytes) {
    return new RecordId(
        VarIntSerializer.readAsInteger(bytes), VarIntSerializer.readAsLong(bytes));
  }

  private Collection<?> readEmbeddedCollection(
      RemoteDatabaseSessionInternal db, final BytesContainer bytes,
      final Collection<Object> found) {
    final var items = VarIntSerializer.readAsInteger(bytes);
    for (var i = 0; i < items; i++) {
      var itemType = readOType(bytes);
      if (itemType == null) {
        found.add(null);
      } else {
        found.add(deserializeValue(db, bytes, itemType));
      }
    }
    return found;
  }

  private Collection<?> readEmbeddedCollection(
      DatabaseSessionEmbedded db, final BytesContainer bytes,
      final Collection<Object> found) {
    final var items = VarIntSerializer.readAsInteger(bytes);
    for (var i = 0; i < items; i++) {
      var itemType = readOType(bytes);
      if (itemType == null) {
        found.add(null);
      } else {
        found.add(deserializeValue(db, bytes, itemType));
      }
    }
    return found;
  }

  @SuppressWarnings("unchecked")
  public void serializeValue(
      DatabaseSessionInternal session, final BytesContainer bytes, Object value,
      final PropertyTypeInternal type) {

    final int pointer;
    switch (type) {
      case INTEGER:
      case LONG:
      case SHORT:
        VarIntSerializer.write(bytes, ((Number) value).longValue());
        break;
      case STRING:
        writeString(bytes, value.toString());
        break;
      case DOUBLE:
        var dg = Double.doubleToLongBits((Double) value);
        pointer = bytes.alloc(LongSerializer.LONG_SIZE);
        LongSerializer.serializeLiteral(dg, bytes.bytes, pointer);
        break;
      case FLOAT:
        var fg = Float.floatToIntBits((Float) value);
        pointer = bytes.alloc(IntegerSerializer.INT_SIZE);
        IntegerSerializer.serializeLiteral(fg, bytes.bytes, pointer);
        break;
      case BYTE:
        pointer = bytes.alloc(1);
        bytes.bytes[pointer] = (Byte) value;
        break;
      case BOOLEAN:
        pointer = bytes.alloc(1);
        bytes.bytes[pointer] = ((Boolean) value) ? (byte) 1 : (byte) 0;
        break;
      case DATETIME:
        if (value instanceof Long) {
          VarIntSerializer.write(bytes, (Long) value);
        } else {
          VarIntSerializer.write(bytes, ((Date) value).getTime());
        }
        break;
      case DATE:
        long dateValue;
        if (value instanceof Long) {
          dateValue = (Long) value;
        } else {
          dateValue = ((Date) value).getTime();
        }
        dateValue =
            convertDayToTimezone(
                DateHelper.getDatabaseTimeZone(session), TimeZone.getTimeZone("GMT"), dateValue);
        VarIntSerializer.write(bytes, dateValue / MILLISEC_PER_DAY);
        break;
      case EMBEDDED:
        if (!(value instanceof Result)) {
          throw new UnsupportedOperationException();
        }
        serialize(session, (Result) value, bytes);
        break;
      case EMBEDDEDSET:
      case EMBEDDEDLIST:
        if (value.getClass().isArray()) {
          writeEmbeddedCollection(session, bytes, Arrays.asList(MultiValue.array(value)));
        } else {
          writeEmbeddedCollection(session, bytes, (Collection<?>) value);
        }
        break;
      case DECIMAL:
        var decimalValue = (BigDecimal) value;
        pointer = bytes.alloc(DecimalSerializer.staticGetObjectSize(decimalValue));
        DecimalSerializer.staticSerialize(decimalValue, bytes.bytes, pointer);
        break;
      case BINARY:
        writeBinary(bytes, (byte[]) (value));
        break;
      case LINKSET:
      case LINKLIST:
        var ridCollection = (Collection<Identifiable>) value;
        writeLinkCollection(session, bytes, ridCollection);
        break;
      case LINK:
        if (value instanceof Result && ((Result) value).isEntity()) {
          value = ((Result) value).asEntity();
        }
        if (!(value instanceof Identifiable)) {
          throw new ValidationException(session, "Value '" + value + "' is not a Identifiable");
        }
        writeOptimizedLink(session, bytes, (Identifiable) value);
        break;
      case LINKMAP:
        writeLinkMap(session, bytes, (Map<Object, Identifiable>) value);
        break;
      case EMBEDDEDMAP:
        writeEmbeddedMap(session, bytes, (Map<Object, Object>) value);
        break;
      case LINKBAG:
        throw new UnsupportedOperationException("LINKBAG should never appear in a projection");
    }
  }

  private static void writeBinary(final BytesContainer bytes, final byte[] valueBytes) {
    HelperClasses.writeBinary(bytes, valueBytes);
  }

  private static void writeLinkMap(DatabaseSessionInternal db, final BytesContainer bytes,
      final Map<Object, Identifiable> map) {
    VarIntSerializer.write(bytes, map.size());
    for (var entry : map.entrySet()) {
      // TODO:check skip of complex types
      // FIXME: changed to support only string key on map
      final var type = PropertyTypeInternal.STRING;
      writeOType(bytes, bytes.alloc(1), type);
      writeString(bytes, entry.getKey().toString());
      if (entry.getValue() == null) {
        writeNullLink(bytes);
      } else {
        writeOptimizedLink(db, bytes, entry.getValue());
      }
    }
  }

  private void writeEmbeddedMap(DatabaseSessionInternal session, BytesContainer bytes,
      Map<Object, Object> map) {
    var fieldNames = map.keySet();
    VarIntSerializer.write(bytes, map.size());
    for (var f : fieldNames) {
      if (!(f instanceof String field)) {
        throw new SerializationException(session,
            "Invalid key type for map: " + f + " (only Strings supported)");
      }
      writeString(bytes, field);
      final var value = map.get(field);
      if (value != null) {
        if (value instanceof Result) {
          writeOType(bytes, bytes.alloc(1), PropertyTypeInternal.EMBEDDED);
          serializeValue(session, bytes, value, PropertyTypeInternal.EMBEDDED);
        } else {
          final var type = PropertyTypeInternal.getTypeByValue(value);
          if (type == null) {
            throw new SerializationException(session,
                "Impossible serialize value of type "
                    + value.getClass()
                    + " with the Result binary serializer");
          }
          writeOType(bytes, bytes.alloc(1), type);
          serializeValue(session, bytes, value, type);
        }
      } else {
        writeOType(bytes, bytes.alloc(1), null);
      }
    }
  }

  private static void writeNullLink(final BytesContainer bytes) {
    VarIntSerializer.write(bytes, NULL_RECORD_ID.getIdentity().getCollectionId());
    VarIntSerializer.write(bytes, NULL_RECORD_ID.getIdentity().getCollectionPosition());
  }

  private static void writeOptimizedLink(DatabaseSessionInternal session,
      final BytesContainer bytes,
      Identifiable link) {
    var rid = link.getIdentity();
    if (!rid.isPersistent()) {
      rid = session.refreshRid(rid);
    }

    VarIntSerializer.write(bytes, rid.getCollectionId());
    VarIntSerializer.write(bytes, rid.getCollectionPosition());
  }

  private static void writeLinkCollection(
      DatabaseSessionInternal db, final BytesContainer bytes,
      final Collection<Identifiable> value) {
    VarIntSerializer.write(bytes, value.size());
    for (var itemValue : value) {
      if (itemValue == null) {
        writeNullLink(bytes);
      } else {
        writeOptimizedLink(db, bytes, itemValue);
      }
    }
  }

  private void writeEmbeddedCollection(DatabaseSessionInternal session, final BytesContainer bytes,
      final Collection<?> value) {
    VarIntSerializer.write(bytes, value.size());

    for (var itemValue : value) {
      // TODO:manage in a better way null entry
      if (itemValue == null) {
        writeOType(bytes, bytes.alloc(1), null);
        continue;
      }
      var type = getTypeFromValueEmbedded(itemValue);
      if (type != null) {
        writeOType(bytes, bytes.alloc(1), type);
        serializeValue(session, bytes, itemValue, type);
      } else {
        throw new SerializationException(session,
            "Impossible serialize value of type "
                + value.getClass()
                + " with the EntityImpl binary serializer");
      }
    }
  }

  private static PropertyTypeInternal getTypeFromValueEmbedded(final Object fieldValue) {
    if (fieldValue instanceof Result && ((Result) fieldValue).isEntity()) {
      return PropertyTypeInternal.LINK;
    }
    return fieldValue instanceof Result ? PropertyTypeInternal.EMBEDDED
        : PropertyTypeInternal.getTypeByValue(fieldValue);
  }

  protected static String readString(final BytesContainer bytes) {
    final var len = VarIntSerializer.readAsInteger(bytes);
    final var res = stringFromBytes(bytes.bytes, bytes.offset, len);
    bytes.skip(len);
    return res;
  }

  protected static int readInteger(final BytesContainer container) {
    return HelperClasses.readInteger(container);
  }

  private static byte readByte(final BytesContainer container) {
    return container.bytes[container.offset++];
  }

  private static long readLong(final BytesContainer container) {
    return HelperClasses.readLong(container);
  }

  private static void writeString(final BytesContainer bytes, final String toWrite) {
    final var nameBytes = bytesFromString(toWrite);
    VarIntSerializer.write(bytes, nameBytes.length);
    final var start = bytes.alloc(nameBytes.length);
    System.arraycopy(nameBytes, 0, bytes.bytes, start, nameBytes.length);
  }

  private static byte[] bytesFromString(final String toWrite) {
    return toWrite.getBytes(StandardCharsets.UTF_8);
  }

  protected static String stringFromBytes(final byte[] bytes, final int offset, final int len) {
    return new String(bytes, offset, len, StandardCharsets.UTF_8);
  }

  private static long convertDayToTimezone(TimeZone from, TimeZone to, long time) {
    return HelperClasses.convertDayToTimezone(from, to, time);
  }

  public void toStream(DatabaseSessionEmbedded session, BasicResult item,
      ChannelDataOutput channel)
      throws IOException {
    final var bytes = new BytesContainer();
    this.serialize(session, item, bytes);
    channel.writeBytes(bytes.fitBytes());
  }

  public RemoteResult fromStream(RemoteDatabaseSessionInternal session, ChannelDataInput channel)
      throws IOException {
    var bytes = new BytesContainer();
    bytes.bytes = channel.readBytes();
    return this.deserialize(session, bytes);
  }

  public Result fromStream(DatabaseSessionEmbedded session, ChannelDataInput channel)
      throws IOException {
    var bytes = new BytesContainer();
    bytes.bytes = channel.readBytes();
    return this.deserialize(session, bytes);
  }
}
