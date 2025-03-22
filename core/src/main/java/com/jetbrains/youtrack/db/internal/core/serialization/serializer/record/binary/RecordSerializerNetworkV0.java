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

import com.jetbrains.youtrack.db.api.exception.DatabaseException;
import com.jetbrains.youtrack.db.api.exception.ValidationException;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.schema.GlobalProperty;
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
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.ImmutableSchema;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaImmutableClass;
import com.jetbrains.youtrack.db.internal.core.metadata.security.PropertyEncryption;
import com.jetbrains.youtrack.db.internal.core.record.RecordAbstract;
import com.jetbrains.youtrack.db.internal.core.record.impl.EmbeddedEntityImpl;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityEntry;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.serialization.EntitySerializable;
import com.jetbrains.youtrack.db.internal.core.util.DateHelper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TimeZone;

public class RecordSerializerNetworkV0 implements EntitySerializer {

  private static final String CHARSET_UTF_8 = "UTF-8";
  private static final RecordId NULL_RECORD_ID = new RecordId(-2, RID.CLUSTER_POS_INVALID);
  private static final long MILLISEC_PER_DAY = 86400000;

  public RecordSerializerNetworkV0() {
  }

  @Override
  public void deserializePartial(
      DatabaseSessionInternal session, final EntityImpl entity, final BytesContainer bytes,
      final String[] iFields) {
    final var className = readString(bytes);
    if (className.length() != 0) {
      entity.setClassNameWithoutPropertiesPostProcessing(className);
    }

    // TRANSFORMS FIELDS FOM STRINGS TO BYTE[]
    final var fields = new byte[iFields.length][];
    for (var i = 0; i < iFields.length; ++i) {
      fields[i] = iFields[i].getBytes();
    }

    String fieldName = null;
    int valuePos;
    PropertyTypeInternal type;
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
          if (iFields[i].length() == len) {
            var matchField = true;
            for (var j = 0; j < len; ++j) {
              if (bytes.bytes[bytes.offset + j] != fields[i][j]) {
                matchField = false;
                break;
              }
            }
            if (matchField) {
              fieldName = iFields[i];
              unmarshalledFields++;
              bytes.skip(len);
              match = true;
              break;
            }
          }
        }

        if (!match) {
          // SKIP IT
          bytes.skip(len + IntegerSerializer.INT_SIZE + 1);
          continue;
        }
        valuePos = readInteger(bytes);
        type = readOType(bytes);
      } else {
        throw new DatabaseException(session, "property id not supported in network serialization");
      }

      if (valuePos != 0) {
        var headerCursor = bytes.offset;
        bytes.offset = valuePos;
        final var value = deserializeValue(session, bytes, type, entity);
        bytes.offset = headerCursor;
        entity.setDeserializedPropertyInternal(fieldName, value, type);
      } else {
        entity.setDeserializedPropertyInternal(fieldName, null, null);
      }

      if (unmarshalledFields == iFields.length)
      // ALL REQUESTED FIELDS UNMARSHALLED: EXIT
      {
        break;
      }
    }
  }

  @Override
  public void deserialize(DatabaseSessionInternal session, final EntityImpl entity,
      final BytesContainer bytes) {
    final var className = readString(bytes);
    if (className.length() != 0) {
      entity.setClassNameWithoutPropertiesPostProcessing(className);
    }

    var last = 0;
    String fieldName;
    int valuePos;
    PropertyTypeInternal type;
    while (true) {
      final var len = VarIntSerializer.readAsInteger(bytes);
      if (len == 0) {
        // SCAN COMPLETED
        break;
      } else if (len > 0) {
        // PARSE FIELD NAME
        fieldName = stringFromBytes(bytes.bytes, bytes.offset, len).intern();
        bytes.skip(len);
        valuePos = readInteger(bytes);
        type = readOType(bytes);
      } else {
        throw new DatabaseException(session, "property id not supported in network serialization");
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

    final var rec = (RecordAbstract) entity;
    rec.clearSource();

    if (last > bytes.offset) {
      bytes.offset = last;
    }
  }

  @SuppressWarnings("unchecked")
  @Override
  public void serialize(DatabaseSessionInternal session, final EntityImpl entity,
      final BytesContainer bytes) {
    entity.checkForBinding();
    ImmutableSchema schema = null;
    if (entity != null) {
      schema = entity.getImmutableSchema();
    }
    var encryption = entity.propertyEncryption;

    serializeClass(session, entity, bytes);

    final var fields = entity.getFilteredEntries();

    final var pos = new int[fields.size()];

    var i = 0;

    final Entry<String, EntityEntry>[] values = new Entry[fields.size()];
    for (var entry : fields) {
      var docEntry = entry.getValue();
      if (!docEntry.exists()) {
        continue;
      }
      writeString(bytes, entry.getKey());
      pos[i] = bytes.alloc(IntegerSerializer.INT_SIZE + 1);
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
          throw new SerializationException(session,
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
        writeOType(bytes, (pos[i] + IntegerSerializer.INT_SIZE), type);
      }
    }
  }

  @Override
  public String[] getFieldNames(DatabaseSessionInternal session, EntityImpl reference,
      final BytesContainer bytes,
      boolean embedded) {
    // SKIP CLASS NAME
    final var classNameLen = VarIntSerializer.readAsInteger(bytes);
    bytes.skip(classNameLen);

    final List<String> result = new ArrayList<String>();

    String fieldName;
    while (true) {
      GlobalProperty prop = null;
      final var len = VarIntSerializer.readAsInteger(bytes);
      if (len == 0) {
        // SCAN COMPLETED
        break;
      } else if (len > 0) {
        // PARSE FIELD NAME
        fieldName = stringFromBytes(bytes.bytes, bytes.offset, len).intern();
        result.add(fieldName);

        // SKIP THE REST
        bytes.skip(len + IntegerSerializer.INT_SIZE + 1);
      } else {
        // LOAD GLOBAL PROPERTY BY ID
        final var id = (len * -1) - 1;
        prop = reference.getGlobalPropertyById(id);
        result.add(prop.getName());

        // SKIP THE REST
        bytes.skip(IntegerSerializer.INT_SIZE);
      }
    }

    return result.toArray(new String[result.size()]);
  }

  protected void serializeClass(DatabaseSessionInternal session, final EntityImpl entity,
      final BytesContainer bytes) {
    SchemaImmutableClass result = null;
    if (entity != null) {
      result = entity.getImmutableSchemaClass(session);
    }
    final SchemaClass clazz = result;
    String name = null;
    if (clazz != null) {
      name = clazz.getName();
    }
    if (name == null) {
      name = entity.getSchemaClassName();
    }

    if (name != null) {
      writeString(bytes, name);
    } else {
      writeEmptyString(bytes);
    }
  }

  protected PropertyTypeInternal readOType(final BytesContainer bytes) {
    var res = readByte(bytes);
    if (res == -1) {
      return null;
    }

    return PropertyTypeInternal.getById(res);
  }

  private void writeOType(BytesContainer bytes, int pos, PropertyTypeInternal type) {
    if (type == null) {
      bytes.bytes[pos] = (byte) -1;
    } else {
      bytes.bytes[pos] = (byte) type.getId();
    }
  }

  public Object deserializeValue(DatabaseSessionInternal session, BytesContainer bytes,
      PropertyTypeInternal type,
      RecordElement owner) {
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
        value = new EmbeddedEntityImpl(session);
        deserialize(session, (EntityImpl) value, bytes);
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

        break;
      case EMBEDDEDSET:
        var set = new TrackedSet<Object>(owner);
        value = readEmbeddedCollection(session, bytes, set, set);
        break;
      case EMBEDDEDLIST:
        var list = new TrackedList<Object>(owner);
        value = readEmbeddedCollection(session, bytes, list, list);
        break;
      case LINKSET:
        value = readLinkCollection(bytes, new LinkSet(owner));
        break;
      case LINKLIST:
        value = readLinkCollection(bytes, new LinkList(owner));
        break;
      case BINARY:
        value = readBinary(bytes);
        break;
      case LINK:
        value = readOptimizedLink(bytes);
        break;
      case LINKMAP:
        value = readLinkMap(session, bytes, owner);
        break;
      case EMBEDDEDMAP:
        value = readEmbeddedMap(session, bytes, owner);
        break;
      case DECIMAL:
        value = DecimalSerializer.staticDeserialize(bytes.bytes, bytes.offset);
        bytes.skip(DecimalSerializer.staticGetObjectSize(bytes.bytes, bytes.offset));
        break;
      case LINKBAG:
        var bag = new RidBag(session);
        bag.fromStream(session, bytes);
        bag.setOwner(owner);
        value = bag;
        break;
    }
    return value;
  }

  private byte[] readBinary(BytesContainer bytes) {
    var n = VarIntSerializer.readAsInteger(bytes);
    var newValue = new byte[n];
    System.arraycopy(bytes.bytes, bytes.offset, newValue, 0, newValue.length);
    bytes.skip(n);
    return newValue;
  }

  private Map<String, Identifiable> readLinkMap(
      DatabaseSessionInternal db, final BytesContainer bytes, final RecordElement owner) {
    var size = VarIntSerializer.readAsInteger(bytes);
    var result = new LinkMap(owner);
    while ((size--) > 0) {
      var keyType = readOType(bytes);
      var key = deserializeValue(db, bytes, keyType, result);
      var value = readOptimizedLink(bytes);
      if (value.equals(NULL_RECORD_ID)) {
        result.put(key.toString(), null);
      } else {
        result.put(key.toString(), value);
      }
    }
    return result;
  }

  private Object readEmbeddedMap(DatabaseSessionInternal db, final BytesContainer bytes,
      final RecordElement owner) {
    var size = VarIntSerializer.readAsInteger(bytes);
    final var result = new TrackedMap<Object>(owner);
    var last = 0;
    while ((size--) > 0) {
      var keyType = readOType(bytes);
      var key = deserializeValue(db, bytes, keyType, result);
      final var valuePos = readInteger(bytes);
      final var type = readOType(bytes);
      if (valuePos != 0) {
        var headerCursor = bytes.offset;
        bytes.offset = valuePos;
        var value = deserializeValue(db, bytes, type, result);
        if (bytes.offset > last) {
          last = bytes.offset;
        }
        bytes.offset = headerCursor;
        result.put(key.toString(), value);
      } else {
        result.put(key.toString(), null);
      }
    }
    if (last > bytes.offset) {
      bytes.offset = last;
    }
    return result;
  }

  private Collection<Identifiable> readLinkCollection(
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

  private RecordId readOptimizedLink(final BytesContainer bytes) {
    return new RecordId(
        VarIntSerializer.readAsInteger(bytes), VarIntSerializer.readAsLong(bytes));
  }

  private Collection<?> readEmbeddedCollection(
      DatabaseSessionInternal db, final BytesContainer bytes, final Collection<Object> found,
      final RecordElement owner) {
    final var items = VarIntSerializer.readAsInteger(bytes);
    var type = readOType(bytes);

    if (type != null) {
      for (var i = 0; i < items; i++) {
        var itemType = readOType(bytes);
        if (itemType == null) {
          found.add(null);
        } else {
          found.add(deserializeValue(db, bytes, itemType, owner));
        }
      }
      return found;
    }
    // TODO: manage case where type is known
    return null;
  }

  private PropertyTypeInternal getLinkedType(DatabaseSessionInternal session, EntityImpl entity,
      PropertyTypeInternal type, String key) {
    if (type != PropertyTypeInternal.EMBEDDEDLIST && type != PropertyTypeInternal.EMBEDDEDSET
        && type != PropertyTypeInternal.EMBEDDEDMAP) {
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
        return PropertyTypeInternal.convertFromPublicType(prop.getLinkedType());
      }
    }
    return null;
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
          pointer = VarIntSerializer.write(bytes, (Long) value);
        } else {
          pointer = VarIntSerializer.write(bytes, ((Date) value).getTime());
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
        pointer = VarIntSerializer.write(bytes, dateValue / MILLISEC_PER_DAY);
        break;
      case EMBEDDED:
        pointer = bytes.offset;
        if (value instanceof EntitySerializable) {
          var cur = ((EntitySerializable) value).toEntity(session);
          cur.setProperty(EntitySerializable.CLASS_NAME, value.getClass().getName());
          serialize(session, cur, bytes);
        } else {
          serialize(session, (EntityImpl) value, bytes);
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
          throw new ValidationException(session, "Value '" + value + "' is not a Identifiable");
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
        pointer = ((RidBag) value).toStream(session, bytes);
        break;
    }
    return pointer;
  }

  private static int writeBinary(final BytesContainer bytes, final byte[] valueBytes) {
    return HelperClasses.writeBinary(bytes, valueBytes);
  }

  private int writeLinkMap(DatabaseSessionInternal db, final BytesContainer bytes,
      final Map<Object, Identifiable> map) {
    final var fullPos = VarIntSerializer.write(bytes, map.size());
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
    return fullPos;
  }

  @SuppressWarnings("unchecked")
  private int writeEmbeddedMap(
      DatabaseSessionInternal session, BytesContainer bytes,
      Map<Object, Object> map,
      ImmutableSchema schema,
      PropertyEncryption encryption) {
    final var pos = new int[map.size()];
    var i = 0;
    Entry<Object, Object>[] values = new Entry[map.size()];
    final var fullPos = VarIntSerializer.write(bytes, map.size());
    for (var entry : map.entrySet()) {
      // TODO:check skip of complex types
      // FIXME: changed to support only string key on map
      var type = PropertyTypeInternal.STRING;
      writeOType(bytes, bytes.alloc(1), type);
      writeString(bytes, entry.getKey().toString());
      pos[i] = bytes.alloc(IntegerSerializer.INT_SIZE + 1);
      values[i] = entry;
      i++;
    }

    for (i = 0; i < values.length; i++) {
      var pointer = 0;
      final var value = values[i].getValue();
      if (value != null) {
        final var type = getTypeFromValueEmbedded(value);
        if (type == null) {
          throw new SerializationException(session,
              "Impossible serialize value of type "
                  + value.getClass()
                  + " with the EntityImpl binary serializer");
        }
        pointer = serializeValue(session, bytes, value, type, null, schema, encryption);
        IntegerSerializer.serializeLiteral(pointer, bytes.bytes, pos[i]);
        writeOType(bytes, (pos[i] + IntegerSerializer.INT_SIZE), type);
      }
    }
    return fullPos;
  }

  private int writeNullLink(final BytesContainer bytes) {
    final var pos = VarIntSerializer.write(bytes, NULL_RECORD_ID.getIdentity().getClusterId());
    VarIntSerializer.write(bytes, NULL_RECORD_ID.getIdentity().getClusterPosition());
    return pos;
  }

  private static int writeOptimizedLink(DatabaseSessionInternal session, final BytesContainer bytes,
      Identifiable link) {
    var rid = link.getIdentity();
    if (!rid.isPersistent()) {
      rid = session.refreshRid(rid);
    }

    final var pos = VarIntSerializer.write(bytes, rid.getClusterId());
    VarIntSerializer.write(bytes, rid.getClusterPosition());
    return pos;
  }

  private int writeLinkCollection(
      DatabaseSessionInternal db, final BytesContainer bytes,
      final Collection<Identifiable> value) {
    final var pos = VarIntSerializer.write(bytes, value.size());
    for (var itemValue : value) {
      // TODO: handle the null links
      if (itemValue == null) {
        writeNullLink(bytes);
      } else {
        writeOptimizedLink(db, bytes, itemValue);
      }
    }

    return pos;
  }

  private int writeEmbeddedCollection(
      DatabaseSessionInternal session, final BytesContainer bytes,
      final Collection<?> value,
      final PropertyTypeInternal linkedType,
      ImmutableSchema schema,
      PropertyEncryption encryption) {
    final var pos = VarIntSerializer.write(bytes, value.size());
    // TODO manage embedded type from schema and auto-determined.
    writeOType(bytes, bytes.alloc(1),
        linkedType != null ? linkedType : PropertyTypeInternal.EMBEDDED);
    for (var itemValue : value) {
      // TODO:manage in a better way null entry
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

  private PropertyTypeInternal getFieldType(DatabaseSessionInternal session,
      final EntityEntry entry) {
    var type = entry.type;
    if (type == null) {
      final var prop = entry.property;
      if (prop != null) {
        type = PropertyTypeInternal.convertFromPublicType(prop.getType());
      }
    }
    if (type == null) {
      type = PropertyTypeInternal.getTypeByValue(entry.value);
    }
    return type;
  }

  private PropertyTypeInternal getTypeFromValueEmbedded(final Object fieldValue) {
    var type = PropertyTypeInternal.getTypeByValue(fieldValue);
    if (type == PropertyTypeInternal.LINK
        && fieldValue instanceof EntityImpl
        && !((EntityImpl) fieldValue).getIdentity().isValidPosition()) {
      type = PropertyTypeInternal.EMBEDDED;
    }
    return type;
  }

  protected String readString(final BytesContainer bytes) {
    final var len = VarIntSerializer.readAsInteger(bytes);
    final var res = stringFromBytes(bytes.bytes, bytes.offset, len);
    bytes.skip(len);
    return res;
  }

  protected static int readInteger(final BytesContainer container) {
    final var value =
        IntegerSerializer.deserializeLiteral(container.bytes, container.offset);
    container.offset += IntegerSerializer.INT_SIZE;
    return value;
  }

  private byte readByte(final BytesContainer container) {
    return container.bytes[container.offset++];
  }

  private static long readLong(final BytesContainer container) {
    final var value =
        LongSerializer.deserializeLiteral(container.bytes, container.offset);
    container.offset += LongSerializer.LONG_SIZE;
    return value;
  }

  private int writeEmptyString(final BytesContainer bytes) {
    return VarIntSerializer.write(bytes, 0);
  }

  private int writeString(final BytesContainer bytes, final String toWrite) {
    final var nameBytes = bytesFromString(toWrite);
    final var pointer = VarIntSerializer.write(bytes, nameBytes.length);
    final var start = bytes.alloc(nameBytes.length);
    System.arraycopy(nameBytes, 0, bytes.bytes, start, nameBytes.length);
    return pointer;
  }

  private byte[] bytesFromString(final String toWrite) {
    return toWrite.getBytes(StandardCharsets.UTF_8);
  }

  protected String stringFromBytes(final byte[] bytes, final int offset, final int len) {
    return new String(bytes, offset, len, StandardCharsets.UTF_8);
  }

  @Override
  public BinaryField deserializeField(
      DatabaseSessionInternal db, final BytesContainer bytes,
      final SchemaClass iClass,
      final String iFieldName,
      boolean embedded,
      ImmutableSchema schema,
      PropertyEncryption encryption) {
    // TODO: check if integrate the binary disc binary comparator here
    throw new UnsupportedOperationException("network serializer doesn't support comparators");
  }

  @Override
  public BinaryComparator getComparator() {
    // TODO: check if integrate the binary disc binary comparator here
    throw new UnsupportedOperationException("network serializer doesn't support comparators");
  }

  private long convertDayToTimezone(TimeZone from, TimeZone to, long time) {
    var fromCalendar = Calendar.getInstance(from);
    fromCalendar.setTimeInMillis(time);
    var toCalendar = Calendar.getInstance(to);
    toCalendar.setTimeInMillis(0);
    toCalendar.set(Calendar.ERA, fromCalendar.get(Calendar.ERA));
    toCalendar.set(Calendar.YEAR, fromCalendar.get(Calendar.YEAR));
    toCalendar.set(Calendar.MONTH, fromCalendar.get(Calendar.MONTH));
    toCalendar.set(Calendar.DAY_OF_MONTH, fromCalendar.get(Calendar.DAY_OF_MONTH));
    toCalendar.set(Calendar.HOUR_OF_DAY, 0);
    toCalendar.set(Calendar.MINUTE, 0);
    toCalendar.set(Calendar.SECOND, 0);
    toCalendar.set(Calendar.MILLISECOND, 0);
    return toCalendar.getTimeInMillis();
  }

  @Override
  public boolean isSerializingClassNameByDefault() {
    return true;
  }

  @Override
  public <RET> RET deserializeFieldTyped(
      DatabaseSessionInternal session, BytesContainer record,
      String iFieldName,
      boolean isEmbedded,
      ImmutableSchema schema,
      PropertyEncryption encryption) {
    throw new UnsupportedOperationException(
        "Not supported yet."); // To change body of generated methods, choose Tools | Templates.
  }

  @Override
  public void deserializeDebug(
      DatabaseSessionInternal db, BytesContainer bytes,
      RecordSerializationDebug debugInfo,
      ImmutableSchema schema) {
    throw new UnsupportedOperationException(
        "Not supported yet."); // To change body of generated methods, choose Tools | Templates.
  }
}
