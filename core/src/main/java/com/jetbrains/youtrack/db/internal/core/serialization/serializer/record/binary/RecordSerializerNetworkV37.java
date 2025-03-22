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

import com.jetbrains.youtrack.db.api.exception.ValidationException;
import com.jetbrains.youtrack.db.api.record.Blob;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.common.collection.MultiValue;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.common.serialization.types.DecimalSerializer;
import com.jetbrains.youtrack.db.internal.common.serialization.types.IntegerSerializer;
import com.jetbrains.youtrack.db.internal.common.serialization.types.LongSerializer;
import com.jetbrains.youtrack.db.internal.common.serialization.types.UUIDSerializer;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.record.EmbeddedSetImpl;
import com.jetbrains.youtrack.db.internal.core.db.record.LinkList;
import com.jetbrains.youtrack.db.internal.core.db.record.LinkMap;
import com.jetbrains.youtrack.db.internal.core.db.record.LinkSetImpl;
import com.jetbrains.youtrack.db.internal.core.db.record.RecordElement;
import com.jetbrains.youtrack.db.internal.core.db.record.TrackedList;
import com.jetbrains.youtrack.db.internal.core.db.record.TrackedMap;
import com.jetbrains.youtrack.db.internal.core.db.record.ridbag.RidBag;
import com.jetbrains.youtrack.db.internal.core.exception.SerializationException;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaImmutableClass;
import com.jetbrains.youtrack.db.internal.core.record.RecordAbstract;
import com.jetbrains.youtrack.db.internal.core.record.impl.EmbeddedEntityImpl;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityEntry;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.serialization.EntitySerializable;
import com.jetbrains.youtrack.db.internal.core.storage.ridbag.BonsaiCollectionPointer;
import com.jetbrains.youtrack.db.internal.core.storage.ridbag.Change;
import com.jetbrains.youtrack.db.internal.core.storage.ridbag.ChangeSerializationHelper;
import com.jetbrains.youtrack.db.internal.core.storage.ridbag.ridbagbtree.RidBagBucketPointer;
import com.jetbrains.youtrack.db.internal.core.util.DateHelper;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TimeZone;
import java.util.UUID;
import javax.annotation.Nonnull;

public class RecordSerializerNetworkV37 implements RecordSerializerNetwork {

  public static final String NAME = "onet_ser_v37";
  protected static final RecordId NULL_RECORD_ID = new RecordId(-2, RID.CLUSTER_POS_INVALID);
  private static final long MILLISEC_PER_DAY = 86400000;
  public static final RecordSerializerNetworkV37 INSTANCE = new RecordSerializerNetworkV37();

  public RecordSerializerNetworkV37() {
  }

  public void deserializePartial(
      DatabaseSessionInternal db, final EntityImpl entity, final BytesContainer bytes,
      final String[] iFields) {

    ObjectOpenHashSet<String> fields = null;
    if (iFields != null && iFields.length > 0) {
      fields = new ObjectOpenHashSet<>(iFields);
    }

    final var className = readString(bytes);
    if (!className.isEmpty()) {
      entity.setClassNameWithoutPropertiesPostProcessing(className);
    }

    String fieldName;
    PropertyTypeInternal type;
    var size = VarIntSerializer.readAsInteger(bytes);

    var matched = 0;
    while ((size--) > 0) {
      fieldName = readString(bytes);

      type = readOType(bytes);
      Object value;
      if (type == null) {
        value = null;
      } else {
        value = deserializeValue(db, bytes, type, entity);
      }
      if (entity.rawContainsProperty(fieldName)) {
        continue;
      }

      if (fields == null) {
        entity.setDeserializedPropertyInternal(fieldName, value, type);
      } else if (fields.contains(fieldName)) {
        entity.setDeserializedPropertyInternal(fieldName, value, type);
        matched++;

        if (matched == fields.size()) {
          break;
        }
      }
    }
  }

  public void deserialize(DatabaseSessionInternal db, final EntityImpl entity,
      final BytesContainer bytes) {
    final var className = deserializeClassName(bytes);
    if (!className.isEmpty()) {
      entity.setClassNameWithoutPropertiesPostProcessing(className);
    }

    String fieldName;
    PropertyTypeInternal type;
    Object value;
    var size = VarIntSerializer.readAsInteger(bytes);
    while ((size--) > 0) {
      // PARSE FIELD NAME
      fieldName = readString(bytes);
      type = readOType(bytes);
      if (type == null) {
        value = null;
      } else {
        value = deserializeValue(db, bytes, type, entity);
      }
      if (entity.rawContainsProperty(fieldName)) {
        continue;
      }
      entity.setDeserializedPropertyInternal(fieldName, value, type);
    }

    final var rec = (RecordAbstract) entity;
    rec.clearSource();
  }

  private static String deserializeClassName(BytesContainer bytes) {
    return readString(bytes);
  }

  public static String deserializeClassName(byte[] record) {
    return readString(new BytesContainer(record));
  }

  public void serialize(DatabaseSessionInternal session, final EntityImpl entity,
      final BytesContainer bytes) {
    entity.checkForBinding();
    serializeClass(session, entity, bytes);
    final var fields = fetchEntries(entity);
    VarIntSerializer.write(bytes, fields.size());
    for (var entry : fields) {
      var docEntry = entry.getValue();
      writeString(bytes, entry.getKey());
      final var value = docEntry.value;
      if (value != null) {
        final var type = getFieldType(docEntry);
        if (type == null) {
          throw new SerializationException(session,
              "Impossible serialize value of type "
                  + value.getClass()
                  + " with the Result binary serializer");
        }
        writeOType(bytes, bytes.alloc(1), type);
        serializeValue(session, bytes, value, type,
            getLinkedType(session, entity, type, entry.getKey()));
      } else {
        writeOType(bytes, bytes.alloc(1), null);
      }
    }
  }

  protected static Collection<Entry<String, EntityEntry>> fetchEntries(EntityImpl entity) {
    return entity.getFilteredEntries();
  }

  public String[] getFieldNames(DatabaseSessionInternal db,
      final BytesContainer bytes) {
    // SKIP CLASS NAME
    final var classNameLen = VarIntSerializer.readAsInteger(bytes);
    bytes.skip(classNameLen);

    final List<String> result = new ArrayList<>();

    var size = VarIntSerializer.readAsInteger(bytes);
    String fieldName;
    PropertyTypeInternal type;
    while ((size--) > 0) {
      fieldName = readString(bytes);
      type = readOType(bytes);
      if (type != null) {
        deserializeValue(db, bytes, type, new EntityImpl(db));
      }
      result.add(fieldName);
    }

    return result.toArray(new String[0]);
  }

  protected static void serializeClass(DatabaseSessionInternal session, final EntityImpl entity,
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

  protected static PropertyTypeInternal readOType(final BytesContainer bytes) {
    return HelperClasses.readType(bytes);
  }

  private static void writeOType(BytesContainer bytes, int pos, PropertyTypeInternal type) {
    if (type == null) {
      bytes.bytes[pos] = (byte) -1;
    } else {
      bytes.bytes[pos] = (byte) type.getId();
    }
  }

  public byte[] serializeValue(DatabaseSessionInternal db, Object value,
      PropertyTypeInternal type) {
    var bytes = new BytesContainer();
    serializeValue(db, bytes, value, type, null);
    return bytes.fitBytes();
  }

  public Object deserializeValue(DatabaseSessionInternal db, byte[] val,
      PropertyTypeInternal type) {
    var bytes = new BytesContainer(val);
    return deserializeValue(db, bytes, type, null);
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
        value = readEmbeddedSet(session, bytes, owner);
        break;
      case EMBEDDEDLIST:
        value = readEmbeddedList(session, bytes, owner);
        break;
      case LINKSET:
        value = readLinkSet(session, bytes, owner);
        break;
      case LINKLIST:
        value = readLinkList(session, bytes, owner);
        break;
      case BINARY:
        value = readBinary(bytes);
        break;
      case LINK:
        value = readOptimizedLink(session, bytes);
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
        var bag = readRidBag(session, bytes);
        bag.setOwner(owner);
        value = bag;
        break;
    }
    return value;
  }

  private static void writeRidBag(DatabaseSessionInternal session, BytesContainer bytes,
      RidBag bag) {
    final var bTreeCollectionManager = session.getBTreeCollectionManager();
    UUID uuid = null;
    if (bTreeCollectionManager != null) {
      uuid = bTreeCollectionManager.listenForChanges(bag, session);
    }
    if (uuid == null) {
      uuid = new UUID(-1, -1);
    }
    var uuidPos = bytes.alloc(UUIDSerializer.UUID_SIZE);
    UUIDSerializer.staticSerialize(uuid, bytes.bytes, uuidPos);

    if (bag.isToSerializeEmbedded()) {
      var pos = bytes.alloc(1);
      bytes.bytes[pos] = 1;
      VarIntSerializer.write(bytes, bag.size());
      for (Identifiable itemValue : bag) {
        if (itemValue == null) {
          writeNullLink(bytes);
        } else {
          writeOptimizedLink(session, bytes, itemValue);
        }
      }
    } else {
      var pos = bytes.alloc(1);
      bytes.bytes[pos] = 2;
      var pointer = bag.getPointer();
      if (pointer == null || pointer == BonsaiCollectionPointer.INVALID) {
        throw new IllegalStateException("RidBag with invalid pointer was found");
      }

      VarIntSerializer.write(bytes, pointer.getFileId());
      VarIntSerializer.write(bytes, pointer.getRootPointer().getPageIndex());
      VarIntSerializer.write(bytes, pointer.getRootPointer().getPageOffset());
      VarIntSerializer.write(bytes, -1);
      var changes = bag.getChanges();
      if (changes != null) {
        VarIntSerializer.write(bytes, changes.size());
        for (var change : changes.entrySet()) {
          writeOptimizedLink(session, bytes, change.getKey());
          var posAll = bytes.alloc(1);
          bytes.bytes[posAll] = change.getValue().getType();
          VarIntSerializer.write(bytes, change.getValue().getValue());
        }
      } else {
        VarIntSerializer.write(bytes, 0);
      }
    }
  }

  protected RidBag readRidBag(DatabaseSessionInternal db, BytesContainer bytes) {
    var uuid = UUIDSerializer.staticDeserialize(bytes.bytes, bytes.offset);
    bytes.skip(UUIDSerializer.UUID_SIZE);
    if (uuid.getMostSignificantBits() == -1 && uuid.getLeastSignificantBits() == -1) {
      uuid = null;
    }
    var b = bytes.bytes[bytes.offset];
    bytes.skip(1);
    if (b == 1) {
      var bag = new RidBag(db, uuid);
      // enable tracking due to timeline issue, which must not be NULL (i.e. tracker.isEnabled()).
      bag.enableTracking(null);
      var size = VarIntSerializer.readAsInteger(bytes);
      for (var i = 0; i < size; i++) {
        Identifiable id = readOptimizedLink(db, bytes);
        if (id.equals(NULL_RECORD_ID)) {
          bag.add(null);
        } else {
          bag.add(id.getIdentity());
        }
      }
      bag.disableTracking(null);
      bag.transactionClear();
      return bag;
    } else {
      var fileId = VarIntSerializer.readAsLong(bytes);
      var pageIndex = VarIntSerializer.readAsLong(bytes);
      var pageOffset = VarIntSerializer.readAsInteger(bytes);
      //bag size
      VarIntSerializer.readAsInteger(bytes);

      Map<RID, Change> changes = new HashMap<>();
      var size = VarIntSerializer.readAsInteger(bytes);
      while (size-- > 0) {
        var link = readOptimizedLink(db, bytes);
        var type = bytes.bytes[bytes.offset];
        bytes.skip(1);
        var change = VarIntSerializer.readAsInteger(bytes);
        changes.put(link, ChangeSerializationHelper.createChangeInstance(type, change));
      }

      BonsaiCollectionPointer pointer = null;
      if (fileId != -1) {
        pointer =
            new BonsaiCollectionPointer(fileId, new RidBagBucketPointer(pageIndex, pageOffset));
      }
      return new RidBag(db, pointer, changes, uuid);
    }
  }

  private static byte[] readBinary(BytesContainer bytes) {
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
      Identifiable value = readOptimizedLink(db, bytes);
      if (value.equals(NULL_RECORD_ID)) {
        result.putInternal(key.toString(), null);
      } else {
        result.putInternal(key.toString(), value);
      }
    }
    return result;
  }

  private Object readEmbeddedMap(DatabaseSessionInternal db, final BytesContainer bytes,
      final RecordElement owner) {
    var size = VarIntSerializer.readAsInteger(bytes);
    final var result = new TrackedMap<>(owner);
    while ((size--) > 0) {
      var key = readString(bytes);
      var valType = readOType(bytes);
      Object value = null;
      if (valType != null) {
        value = deserializeValue(db, bytes, valType, result);
      }
      result.putInternal(key, value);
    }
    return result;
  }

  private static Collection<Identifiable> readLinkList(
      DatabaseSessionInternal db, BytesContainer bytes, RecordElement owner) {
    var found = new LinkList(owner);
    final var items = VarIntSerializer.readAsInteger(bytes);
    for (var i = 0; i < items; i++) {
      Identifiable id = readOptimizedLink(db, bytes);
      if (id.equals(NULL_RECORD_ID)) {
        found.addInternal(null);
      } else {
        found.addInternal(id);
      }
    }
    return found;
  }

  private static Collection<Identifiable> readLinkSet(DatabaseSessionInternal db,
      BytesContainer bytes,
      RecordElement owner) {
    var found = new LinkSetImpl(owner);
    final var items = VarIntSerializer.readAsInteger(bytes);
    for (var i = 0; i < items; i++) {
      Identifiable id = readOptimizedLink(db, bytes);
      if (id.equals(NULL_RECORD_ID)) {
        found.addInternal(null);
      } else {
        found.addInternal(id);
      }
    }
    return found;
  }

  protected static RID readOptimizedLink(DatabaseSessionInternal db,
      final BytesContainer bytes) {
    var rid =
        new RecordId(VarIntSerializer.readAsInteger(bytes), VarIntSerializer.readAsLong(bytes));
    if (rid.isTemporary()) {
      return db.refreshRid(rid);
    }

    return rid;
  }

  private Collection<?> readEmbeddedList(DatabaseSessionInternal db, final BytesContainer bytes,
      final RecordElement owner) {
    var found = new TrackedList<>(owner);
    final var items = VarIntSerializer.readAsInteger(bytes);
    for (var i = 0; i < items; i++) {
      var itemType = readOType(bytes);
      if (itemType == null) {
        found.addInternal(null);
      } else {
        found.addInternal(deserializeValue(db, bytes, itemType, found));
      }
    }
    return found;
  }

  private Collection<?> readEmbeddedSet(DatabaseSessionInternal db, final BytesContainer bytes,
      final RecordElement owner) {
    var found = new EmbeddedSetImpl<>(owner);
    final var items = VarIntSerializer.readAsInteger(bytes);
    for (var i = 0; i < items; i++) {
      var itemType = readOType(bytes);
      if (itemType == null) {
        found.addInternal(null);
      } else {
        found.addInternal(deserializeValue(db, bytes, itemType, found));
      }
    }
    return found;
  }

  private static PropertyTypeInternal getLinkedType(DatabaseSessionInternal session,
      EntityImpl entity,
      PropertyTypeInternal type, String key) {
    return RecordSerializerBinaryV0.getLinkedType(session, entity, type, key);
  }

  @SuppressWarnings("unchecked")
  public void serializeValue(
      DatabaseSessionInternal session, final BytesContainer bytes, Object value,
      final PropertyTypeInternal type,
      final PropertyTypeInternal linkedType) {
    int pointer;
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
          writeEmbeddedCollection(session, bytes, Arrays.asList(MultiValue.array(value)),
              linkedType);
        } else {
          writeEmbeddedCollection(session, bytes, (Collection<?>) value, linkedType);
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
        writeRidBag(session, bytes, (RidBag) value);
        break;
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
    VarIntSerializer.write(bytes, map.size());
    for (var entry : map.entrySet()) {
      writeString(bytes, entry.getKey().toString());
      final var value = entry.getValue();
      if (value != null) {
        final var type = getTypeFromValueEmbedded(value);
        if (type == null) {
          throw new SerializationException(session,
              "Impossible serialize value of type "
                  + value.getClass()
                  + " with the Result binary serializer");
        }
        writeOType(bytes, bytes.alloc(1), type);
        serializeValue(session, bytes, value, type, null);
      } else {
        writeOType(bytes, bytes.alloc(1), null);
      }
    }
  }

  private static void writeNullLink(final BytesContainer bytes) {
    VarIntSerializer.write(bytes, NULL_RECORD_ID.getIdentity().getClusterId());
    VarIntSerializer.write(bytes, NULL_RECORD_ID.getIdentity().getClusterPosition());
  }

  protected static void writeOptimizedLink(DatabaseSessionInternal session,
      final BytesContainer bytes,
      Identifiable link) {
    var rid = link.getIdentity();
    if (!rid.isPersistent()) {
      rid = session.refreshRid(rid);
    }

    VarIntSerializer.write(bytes, rid.getClusterId());
    VarIntSerializer.write(bytes, rid.getClusterPosition());
  }

  private static void writeLinkCollection(
      DatabaseSessionInternal db, final BytesContainer bytes,
      final Collection<Identifiable> value) {
    VarIntSerializer.write(bytes, value.size());

    for (var itemValue : value) {
      // TODO: handle the null links
      if (itemValue == null) {
        writeNullLink(bytes);
      } else {
        writeOptimizedLink(db, bytes, itemValue);
      }
    }
  }

  private void writeEmbeddedCollection(
      DatabaseSessionInternal session, final BytesContainer bytes, final Collection<?> value,
      final PropertyTypeInternal linkedType) {
    VarIntSerializer.write(bytes, value.size());
    // TODO manage embedded type from schema and auto-determined.
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
        serializeValue(session, bytes, itemValue, type, null);
      } else {
        throw new SerializationException(session,
            "Impossible serialize value of type "
                + value.getClass()
                + " with the EntityImpl binary serializer");
      }
    }
  }

  private static PropertyTypeInternal getFieldType(final EntityEntry entry) {
    return EntitySerializerDelta.getFieldType(entry);
  }

  private static PropertyTypeInternal getTypeFromValueEmbedded(final Object fieldValue) {
    return HelperClasses.getTypeFromValueEmbedded(fieldValue);
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

  private static void writeEmptyString(final BytesContainer bytes) {
    VarIntSerializer.write(bytes, 0);
  }

  private static void writeString(final BytesContainer bytes, final String toWrite) {
    final var nameBytes = bytesFromString(toWrite);
    final var pointer = VarIntSerializer.write(bytes, nameBytes.length);
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

  public void fromStream(DatabaseSessionInternal db, byte[] iSource,
      @Nonnull RecordAbstract record) {
    if (record instanceof Blob) {
      record.unsetDirty();
      record.fill(record.getIdentity(), record.getVersion(), iSource, true);
    }

    fromStream(db, iSource, record, null);
  }

  @Override
  public void fromStream(@Nonnull DatabaseSessionInternal db, @Nonnull byte[] iSource,
      @Nonnull RecordAbstract record,
      String[] iFields) {
    if (iSource.length == 0) {
      return;
    }

    if (record instanceof Blob) {
      final var rec = record;
      rec.unsetDirty();
      record.fromStream(iSource);
    }

    var oldSerializer = record.recordSerializer;
    try {
      record.recordSerializer = this;
      var container = new BytesContainer(iSource);
      try {
        if (iFields != null && iFields.length > 0) {
          deserializePartial(db, (EntityImpl) record, container, iFields);
        } else {
          deserialize(db, (EntityImpl) record, container);
        }
      } catch (RuntimeException e) {
        LogManager.instance()
            .warn(
                this,
                "Error deserializing record with id %s send this data for debugging: %s ",
                record.getIdentity().toString(),
                Base64.getEncoder().encodeToString(iSource));
        throw e;
      }
    } finally {
      record.recordSerializer = oldSerializer;
    }
  }

  @Override
  public byte[] toStream(@Nonnull DatabaseSessionInternal session,
      @Nonnull RecordAbstract iSource) {
    if (iSource instanceof Blob) {
      return iSource.toStream();
    } else {

      final var container = new BytesContainer();
      var entity = (EntityImpl) iSource;
      // SERIALIZE RECORD
      serialize(session, entity, container);
      return container.fitBytes();
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

  @Override
  public boolean getSupportBinaryEvaluate() {
    return false;
  }

  @Override
  public String getName() {
    return NAME;
  }

  @Override
  public String[] getFieldNames(@Nonnull DatabaseSessionInternal session, EntityImpl reference,
      @Nonnull byte[] iSource) {
    if (iSource.length == 0) {
      return new String[0];
    }

    final var container = new BytesContainer(iSource);

    try {
      return getFieldNames(session, container);
    } catch (RuntimeException e) {
      LogManager.instance()
          .warn(
              this,
              "Error deserializing record to get field-names, send this data for debugging: %s ",
              Base64.getEncoder().encodeToString(iSource));
      throw e;
    }
  }
}
