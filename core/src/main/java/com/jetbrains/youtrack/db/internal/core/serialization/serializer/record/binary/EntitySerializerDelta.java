package com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary;

import static com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.HelperClasses.MILLISEC_PER_DAY;
import static com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.HelperClasses.NULL_RECORD_ID;
import static com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.HelperClasses.convertDayToTimezone;
import static com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.HelperClasses.getLinkedType;
import static com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.HelperClasses.getTypeFromValueEmbedded;
import static com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.HelperClasses.readBinary;
import static com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.HelperClasses.readByte;
import static com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.HelperClasses.readInteger;
import static com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.HelperClasses.readLong;
import static com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.HelperClasses.readOType;
import static com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.HelperClasses.readString;
import static com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.HelperClasses.writeBinary;
import static com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.HelperClasses.writeNullLink;
import static com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.HelperClasses.writeOType;
import static com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.HelperClasses.writeString;

import com.jetbrains.youtrack.db.api.exception.ValidationException;
import com.jetbrains.youtrack.db.api.record.DBRecord;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.common.collection.MultiValue;
import com.jetbrains.youtrack.db.internal.common.serialization.types.DecimalSerializer;
import com.jetbrains.youtrack.db.internal.common.serialization.types.IntegerSerializer;
import com.jetbrains.youtrack.db.internal.common.serialization.types.LongSerializer;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.record.EntityEmbeddedListImpl;
import com.jetbrains.youtrack.db.internal.core.db.record.EntityEmbeddedMapImpl;
import com.jetbrains.youtrack.db.internal.core.db.record.EntityEmbeddedSetImpl;
import com.jetbrains.youtrack.db.internal.core.db.record.EntityLinkListImpl;
import com.jetbrains.youtrack.db.internal.core.db.record.EntityLinkMapIml;
import com.jetbrains.youtrack.db.internal.core.db.record.EntityLinkSetImpl;
import com.jetbrains.youtrack.db.internal.core.db.record.RecordElement;
import com.jetbrains.youtrack.db.internal.core.db.record.TrackedMultiValue;
import com.jetbrains.youtrack.db.internal.core.db.record.ridbag.RidBag;
import com.jetbrains.youtrack.db.internal.core.exception.SerializationException;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaImmutableClass;
import com.jetbrains.youtrack.db.internal.core.record.impl.EmbeddedEntityImpl;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityEntry;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.serialization.EntitySerializable;
import com.jetbrains.youtrack.db.internal.core.util.DateHelper;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.Map;
import java.util.TimeZone;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class EntitySerializerDelta {

  protected static final byte CREATED = 1;
  protected static final byte REPLACED = 2;
  protected static final byte CHANGED = 3;
  protected static final byte REMOVED = 4;
  public static final byte DELTA_RECORD_TYPE = 10;

  private static final EntitySerializerDelta INSTANCE = new EntitySerializerDelta();

  public static EntitySerializerDelta instance() {
    return INSTANCE;
  }

  protected EntitySerializerDelta() {
  }

  public static byte[] serialize(DatabaseSessionInternal session, EntityImpl entity) {
    var bytes = new BytesContainer();
    serialize(session, entity, bytes);
    return bytes.fitBytes();
  }

  public static byte[] serializeDelta(DatabaseSessionInternal session, EntityImpl entity) {
    var bytes = new BytesContainer();
    serializeDelta(session, bytes, entity);
    return bytes.fitBytes();
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

  private static void writeEmptyString(final BytesContainer bytes) {
    VarIntSerializer.write(bytes, 0);
  }

  private static void serialize(DatabaseSessionInternal session, final EntityImpl entity,
      final BytesContainer bytes) {
    serializeClass(session, entity, bytes);
    SchemaImmutableClass result = null;
    if (entity != null) {
      result = entity.getImmutableSchemaClass(session);
    }
    SchemaClass oClass = result;
    final var fields = entity.getRawEntries();
    VarIntSerializer.write(bytes, entity.getPropertiesCount());
    for (var entry : fields) {
      var entityEntry = entry.getValue();
      if (!entityEntry.exists()) {
        continue;
      }
      writeString(bytes, entry.getKey());
      final var value = entry.getValue().value;
      if (value != null) {
        final var type = getFieldType(entry.getValue());
        if (type == null) {
          throw new SerializationException(session.getDatabaseName(),
              "Impossible serialize value of type "
                  + value.getClass()
                  + " with the Result binary serializer");
        }
        writeNullableType(bytes, type);
        serializeValue(session, bytes, value, type,
            getLinkedType(session, oClass, type, entry.getKey()));
      } else {
        writeNullableType(bytes, null);
      }
    }
  }

  public void deserialize(DatabaseSessionInternal session, byte[] content, EntityImpl toFill) {
    var bytesContainer = new BytesContainer(content);
    deserialize(session, toFill, bytesContainer);
  }

  private void deserialize(DatabaseSessionInternal session, final EntityImpl entity,
      final BytesContainer bytes) {
    final var className = readString(bytes);
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
      type = readNullableType(bytes);
      if (type == null) {
        value = null;
      } else {
        value = deserializeValue(session, bytes, type, entity);
      }
      entity.setDeserializedPropertyInternal(fieldName, value, type);
    }
  }

  public void deserializeDelta(DatabaseSessionInternal session, byte[] content,
      EntityImpl toFill) {
    var bytesContainer = new BytesContainer(content);
    deserializeDelta(session, bytesContainer, toFill);
  }

  public void deserializeDelta(DatabaseSessionInternal session, BytesContainer bytes,
      EntityImpl toFill) {
    final var className = readString(bytes);
    if (!className.isEmpty() && toFill != null) {
      toFill.setClassNameWithoutPropertiesPostProcessing(className);
    }
    var count = VarIntSerializer.readAsLong(bytes);
    while (count-- > 0) {
      switch (deserializeByte(bytes)) {
        case CREATED, REPLACED:
          deserializeFullEntry(session, bytes, toFill);
          break;
        case CHANGED:
          deserializeDeltaEntry(session, bytes, toFill);
          break;
        case REMOVED:
          var property = readString(bytes);
          if (toFill != null) {
            toFill.removePropertyInternal(property);
          }
          break;
      }
    }
  }

  private void deserializeDeltaEntry(DatabaseSessionInternal session, BytesContainer bytes,
      EntityImpl toFill) {
    var name = readString(bytes);
    var type = readNullableType(bytes);
    Object toUpdate;
    if (toFill != null) {
      toUpdate = toFill.getPropertyInternal(name);
    } else {
      toUpdate = null;
    }
    deserializeDeltaValue(session, bytes, type, toUpdate);
  }

  private void deserializeDeltaValue(DatabaseSessionInternal session, BytesContainer bytes,
      PropertyTypeInternal type, Object toUpdate) {
    switch (type) {
      case EMBEDDEDLIST:
        //noinspection unchecked
        deserializeDeltaEmbeddedList(session, bytes, (EntityEmbeddedListImpl<Object>) toUpdate);
        break;
      case EMBEDDEDSET:
        //noinspection unchecked
        deserializeDeltaEmbeddedSet(session, bytes, (EntityEmbeddedSetImpl<Object>) toUpdate);
        break;
      case EMBEDDEDMAP:
        //noinspection unchecked
        deserializeDeltaEmbeddedMap(session, bytes, (EntityEmbeddedMapImpl<Object>) toUpdate);
        break;
      case EMBEDDED:
        var transaction = session.getActiveTransaction();
        deserializeDelta(session, bytes, transaction.load(((DBRecord) toUpdate)));
        break;
      case LINKLIST:
        deserializeDeltaLinkList(session, bytes, (EntityLinkListImpl) toUpdate);
        break;
      case LINKSET:
        deserializeDeltaLinkSet(session, bytes, (EntityLinkSetImpl) toUpdate);
        break;
      case LINKMAP:
        deserializeDeltaLinkMap(session, bytes, (EntityLinkMapIml) toUpdate);
        break;
      case LINKBAG:
        deserializeDeltaLinkBag(session, bytes, (RidBag) toUpdate);
        break;
      default:
        throw new SerializationException(session.getDatabaseName(),
            "delta not supported for type:" + type);
    }
  }

  private static void deserializeDeltaLinkMap(DatabaseSessionInternal session, BytesContainer bytes,
      EntityLinkMapIml toUpdate) {
    var rootChanges = VarIntSerializer.readAsLong(bytes);
    while (rootChanges-- > 0) {
      var change = deserializeByte(bytes);
      switch (change) {
        case CREATED: {
          var key = readString(bytes);
          var link = readOptimizedLink(session, bytes);
          if (toUpdate != null) {
            toUpdate.put(key, link);
          }
          break;
        }
        case REPLACED: {
          var key = readString(bytes);
          var link = readOptimizedLink(session, bytes);
          if (toUpdate != null) {
            toUpdate.put(key, link);
          }
          break;
        }
        case REMOVED: {
          var key = readString(bytes);
          if (toUpdate != null) {
            toUpdate.remove(key);
          }
          break;
        }
      }
    }
  }

  protected static void deserializeDeltaLinkBag(DatabaseSessionInternal session,
      BytesContainer bytes,
      RidBag toUpdate) {
    var rootChanges = VarIntSerializer.readAsLong(bytes);
    while (rootChanges-- > 0) {
      var change = deserializeByte(bytes);
      switch (change) {
        case CREATED: {
          var link = readOptimizedLink(session, bytes);
          if (toUpdate != null) {
            toUpdate.add(link);
          }
          break;
        }
        case REPLACED: {
          break;
        }
        case REMOVED: {
          var link = readOptimizedLink(session, bytes);
          if (toUpdate != null) {
            toUpdate.remove(link);
          }
          break;
        }
      }
    }
  }

  private static void deserializeDeltaLinkList(DatabaseSessionInternal session,
      BytesContainer bytes,
      EntityLinkListImpl toUpdate) {
    var rootChanges = VarIntSerializer.readAsLong(bytes);
    while (rootChanges-- > 0) {
      var change = deserializeByte(bytes);
      switch (change) {
        case CREATED: {
          var link = readOptimizedLink(session, bytes);
          if (toUpdate != null) {
            toUpdate.add(link);
          }
          break;
        }
        case REPLACED: {
          var position = VarIntSerializer.readAsLong(bytes);
          var link = readOptimizedLink(session, bytes);
          if (toUpdate != null) {
            toUpdate.set((int) position, link);
          }
          break;
        }
        case REMOVED: {
          var link = readOptimizedLink(session, bytes);
          if (toUpdate != null) {
            toUpdate.remove(link);
          }
          break;
        }
      }
    }
  }

  private static void deserializeDeltaLinkSet(DatabaseSessionInternal session, BytesContainer bytes,
      EntityLinkSetImpl toUpdate) {
    var rootChanges = VarIntSerializer.readAsLong(bytes);
    while (rootChanges-- > 0) {
      var change = deserializeByte(bytes);
      switch (change) {
        case CREATED: {
          var link = readOptimizedLink(session, bytes);
          if (toUpdate != null) {
            toUpdate.add(link);
          }
          break;
        }
        case REPLACED: {
          break;
        }
        case REMOVED: {
          var link = readOptimizedLink(session, bytes);
          if (toUpdate != null) {
            toUpdate.remove(link);
          }
          break;
        }
      }
    }
  }

  private void deserializeDeltaEmbeddedMap(DatabaseSessionInternal session, BytesContainer bytes,
      EntityEmbeddedMapImpl<Object> toUpdate) {
    var rootChanges = VarIntSerializer.readAsLong(bytes);
    while (rootChanges-- > 0) {
      var change = deserializeByte(bytes);
      switch (change) {
        case CREATED: {
          var key = readString(bytes);
          var type = readNullableType(bytes);
          Object value;
          if (type != null) {
            value = deserializeValue(session, bytes, type, toUpdate);
          } else {
            value = null;
          }
          if (toUpdate != null) {
            toUpdate.put(key, value);
          }
          break;
        }
        case REPLACED: {
          var key = readString(bytes);
          var type = readNullableType(bytes);
          Object value;
          if (type != null) {
            value = deserializeValue(session, bytes, type, toUpdate);
          } else {
            value = null;
          }
          if (toUpdate != null) {
            toUpdate.put(key, value);
          }
          break;
        }
        case REMOVED:
          var key = readString(bytes);
          if (toUpdate != null) {
            toUpdate.remove(key);
          }
          break;
      }
    }
    var nestedChanges = VarIntSerializer.readAsLong(bytes);
    while (nestedChanges-- > 0) {
      var other = deserializeByte(bytes);
      assert other == CHANGED;
      var key = readString(bytes);
      Object nested;
      if (toUpdate != null) {
        nested = toUpdate.get(key);
      } else {
        nested = null;
      }
      var type = readNullableType(bytes);
      deserializeDeltaValue(session, bytes, type, nested);
    }
  }

  private void deserializeDeltaEmbeddedSet(DatabaseSessionInternal session, BytesContainer bytes,
      EntityEmbeddedSetImpl<Object> toUpdate) {
    var rootChanges = VarIntSerializer.readAsLong(bytes);
    while (rootChanges-- > 0) {
      var change = deserializeByte(bytes);
      switch (change) {
        case CREATED: {
          var type = readNullableType(bytes);
          Object value;
          if (type != null) {
            value = deserializeValue(session, bytes, type, toUpdate);
          } else {
            value = null;
          }
          if (toUpdate != null) {
            toUpdate.add(value);
          }
          break;
        }
        case REPLACED:
          assert false : "this can't ever happen";
        case REMOVED:
          var type = readNullableType(bytes);
          Object value;
          if (type != null) {
            value = deserializeValue(session, bytes, type, toUpdate);
          } else {
            value = null;
          }
          if (toUpdate != null) {
            toUpdate.remove(value);
          }
          break;
      }
    }
    var nestedChanges = VarIntSerializer.readAsLong(bytes);
    while (nestedChanges-- > 0) {
      var other = deserializeByte(bytes);
      assert other == CHANGED;
      var position = VarIntSerializer.readAsLong(bytes);
      var type = readNullableType(bytes);
      Object nested;
      if (toUpdate != null) {
        var iter = toUpdate.iterator();
        for (var i = 0; i < position; i++) {
          iter.next();
        }
        nested = iter.next();
      } else {
        nested = null;
      }

      deserializeDeltaValue(session, bytes, type, nested);
    }
  }

  private void deserializeDeltaEmbeddedList(DatabaseSessionInternal session, BytesContainer bytes,
      EntityEmbeddedListImpl<Object> toUpdate) {
    var rootChanges = VarIntSerializer.readAsLong(bytes);
    while (rootChanges-- > 0) {
      var change = deserializeByte(bytes);
      switch (change) {
        case CREATED: {
          var type = readNullableType(bytes);
          Object value;
          if (type != null) {
            value = deserializeValue(session, bytes, type, toUpdate);
          } else {
            value = null;
          }
          if (toUpdate != null) {
            toUpdate.add(value);
          }
          break;
        }
        case REPLACED: {
          var pos = VarIntSerializer.readAsLong(bytes);
          var type = readNullableType(bytes);
          Object value;
          if (type != null) {
            value = deserializeValue(session, bytes, type, toUpdate);
          } else {
            value = null;
          }
          if (toUpdate != null) {
            toUpdate.set((int) pos, value);
          }
          break;
        }
        case REMOVED: {
          var pos = VarIntSerializer.readAsLong(bytes);
          if (toUpdate != null) {
            toUpdate.remove((int) pos);
          }
          break;
        }
      }
    }
    var nestedChanges = VarIntSerializer.readAsLong(bytes);
    while (nestedChanges-- > 0) {
      var other = deserializeByte(bytes);
      assert other == CHANGED;
      var position = VarIntSerializer.readAsLong(bytes);
      Object nested;
      if (toUpdate != null) {
        nested = toUpdate.get((int) position);
      } else {
        nested = null;
      }
      var type = readNullableType(bytes);
      deserializeDeltaValue(session, bytes, type, nested);
    }
  }

  private void deserializeFullEntry(DatabaseSessionInternal session, BytesContainer bytes,
      EntityImpl toFill) {
    var name = readString(bytes);
    var type = readNullableType(bytes);
    Object value;
    if (type != null) {
      value = deserializeValue(session, bytes, type, toFill);
    } else {
      value = null;
    }
    if (toFill != null) {
      toFill.compareAndSetPropertyInternal(name, value, type);
    }
  }

  public static void serializeDelta(DatabaseSessionInternal session, BytesContainer bytes,
      EntityImpl entity) {
    serializeClass(session, entity, bytes);
    SchemaImmutableClass result = null;
    if (entity != null) {
      result = entity.getImmutableSchemaClass(session);
    }
    SchemaClass oClass = result;
    var count =
        entity.getRawEntries().stream()
            .filter(
                (e) -> {
                  var entry = e.getValue();
                  return entry.isTxCreated()
                      || entry.isTxChanged()
                      || entry.isTxTrackedModified()
                      || !entry.isTxExists();
                })
            .count();
    var entries = entity.getRawEntries();

    VarIntSerializer.write(bytes, count);
    for (final var entry : entries) {
      final var docEntry = entry.getValue();
      if (!docEntry.isTxExists()) {
        serializeByte(bytes, REMOVED);
        writeString(bytes, entry.getKey());
      } else if (docEntry.isTxCreated()) {
        serializeByte(bytes, CREATED);
        serializeFullEntry(session, bytes, oClass, entry.getKey(), docEntry);
      } else if (docEntry.isTxChanged()) {
        serializeByte(bytes, REPLACED);
        serializeFullEntry(session, bytes, oClass, entry.getKey(), docEntry);
      } else if (docEntry.isTxTrackedModified()) {
        serializeByte(bytes, CHANGED);
        // timeline must not be NULL here. Else check that tracker is enabled
        serializeDeltaEntry(session, bytes, entry.getKey(), docEntry);
      }
    }
  }

  private static void serializeDeltaEntry(
      DatabaseSessionInternal session, BytesContainer bytes, String name,
      EntityEntry entry) {
    final var value = entry.value;
    assert value != null;
    final var type = getFieldType(entry);
    if (type == null) {
      throw new SerializationException(session.getDatabaseName(),
          "Impossible serialize value of type " + value.getClass() + " with the delta serializer");
    }
    writeString(bytes, name);
    writeNullableType(bytes, type);
    serializeDeltaValue(session, bytes, value, type);
  }

  private static void serializeDeltaValue(
      DatabaseSessionInternal session, BytesContainer bytes, Object value,
      PropertyTypeInternal type) {
    switch (type) {
      case EMBEDDEDLIST:
        serializeDeltaEmbeddedList(session, bytes, (EntityEmbeddedListImpl<?>) value);
        break;
      case EMBEDDEDSET:
        //noinspection unchecked
        serializeDeltaEmbeddedSet(session, bytes, (EntityEmbeddedSetImpl<Object>) value);
        break;
      case EMBEDDEDMAP:
        //noinspection unchecked
        serializeDeltaEmbeddedMap(session, bytes, (EntityEmbeddedMapImpl<Object>) value);
        break;
      case EMBEDDED:
        serializeDelta(session, bytes, (EntityImpl) value);
        break;
      case LINKLIST:
        serializeDeltaLinkList(session, bytes, (EntityLinkListImpl) value);
        break;
      case LINKSET:
        serializeDeltaLinkSet(session, bytes, (EntityLinkSetImpl) value);
        break;
      case LINKMAP:
        serializeDeltaLinkMap(session, bytes, (EntityLinkMapIml) value);
        break;
      case LINKBAG:
        serializeDeltaLinkBag(session, bytes, (RidBag) value);
        break;
      default:
        throw new SerializationException(session.getDatabaseName(),
            "delta not supported for type:" + type);
    }
  }

  protected static void serializeDeltaLinkBag(DatabaseSessionInternal session, BytesContainer bytes,
      RidBag value) {
    final var timeline =
        value.getTransactionTimeLine();
    assert timeline != null : "Collection timeline required for serialization of link types";
    VarIntSerializer.write(bytes, timeline.getMultiValueChangeEvents().size());
    for (var event :
        timeline.getMultiValueChangeEvents()) {
      switch (event.getChangeType()) {
        case ADD:
          serializeByte(bytes, CREATED);
          writeOptimizedLink(session, bytes, event.getValue());
          break;
        case UPDATE:
          throw new UnsupportedOperationException(
              "update do not happen in sets, it will be like and add");
        case REMOVE:
          serializeByte(bytes, REMOVED);
          writeOptimizedLink(session, bytes, event.getOldValue());
          break;
      }
    }
  }

  private static void serializeDeltaLinkSet(
      DatabaseSessionInternal session, BytesContainer bytes,
      TrackedMultiValue<Identifiable, Identifiable> value) {
    var timeline =
        value.getTransactionTimeLine();
    assert timeline != null : "Collection timeline required for link* types serialization";
    VarIntSerializer.write(bytes, timeline.getMultiValueChangeEvents().size());
    for (var event :
        timeline.getMultiValueChangeEvents()) {
      switch (event.getChangeType()) {
        case ADD:
          serializeByte(bytes, CREATED);
          writeOptimizedLink(session, bytes, event.getValue());
          break;
        case UPDATE:
          throw new UnsupportedOperationException(
              "update do not happen in sets, it will be like and add");
        case REMOVE:
          serializeByte(bytes, REMOVED);
          writeOptimizedLink(session, bytes, event.getOldValue());
          break;
      }
    }
  }

  private static void serializeDeltaLinkList(DatabaseSessionInternal session, BytesContainer bytes,
      EntityLinkListImpl value) {
    var timeline = value.getTransactionTimeLine();
    assert timeline != null : "Collection timeline required for link* types serialization";
    VarIntSerializer.write(bytes, timeline.getMultiValueChangeEvents().size());
    for (var event :
        timeline.getMultiValueChangeEvents()) {
      switch (event.getChangeType()) {
        case ADD:
          serializeByte(bytes, CREATED);
          writeOptimizedLink(session, bytes, event.getValue());
          break;
        case UPDATE:
          serializeByte(bytes, REPLACED);
          VarIntSerializer.write(bytes, event.getKey().longValue());
          writeOptimizedLink(session, bytes, event.getValue());
          break;
        case REMOVE:
          serializeByte(bytes, REMOVED);
          writeOptimizedLink(session, bytes, event.getOldValue());
          break;
      }
    }
  }

  private static void serializeDeltaLinkMap(DatabaseSessionInternal session, BytesContainer bytes,
      EntityLinkMapIml value) {
    var timeline = value.getTransactionTimeLine();
    assert timeline != null : "Collection timeline required for link* types serialization";
    VarIntSerializer.write(bytes, timeline.getMultiValueChangeEvents().size());
    for (var event :
        timeline.getMultiValueChangeEvents()) {
      switch (event.getChangeType()) {
        case ADD:
          serializeByte(bytes, CREATED);
          writeString(bytes, event.getKey());
          writeOptimizedLink(session, bytes, event.getValue());
          break;
        case UPDATE:
          serializeByte(bytes, REPLACED);
          writeString(bytes, event.getKey());
          writeOptimizedLink(session, bytes, event.getValue());
          break;
        case REMOVE:
          serializeByte(bytes, REMOVED);
          writeString(bytes, event.getKey());
          break;
      }
    }
  }

  private static void serializeDeltaEmbeddedMap(DatabaseSessionInternal session,
      BytesContainer bytes,
      EntityEmbeddedMapImpl<?> value) {
    var timeline = value.getTransactionTimeLine();
    if (timeline != null) {
      VarIntSerializer.write(bytes, timeline.getMultiValueChangeEvents().size());
      for (var event : timeline.getMultiValueChangeEvents()) {
        switch (event.getChangeType()) {
          case ADD: {
            serializeByte(bytes, CREATED);
            writeString(bytes, event.getKey());
            if (event.getValue() != null) {
              var type = PropertyTypeInternal.getTypeByValue(event.getValue());
              writeNullableType(bytes, type);
              serializeValue(session, bytes, event.getValue(), type, null);
            } else {
              writeNullableType(bytes, null);
            }
            break;
          }
          case UPDATE: {
            serializeByte(bytes, REPLACED);
            writeString(bytes, event.getKey());
            if (event.getValue() != null) {
              var type = PropertyTypeInternal.getTypeByValue(event.getValue());
              writeNullableType(bytes, type);
              serializeValue(session, bytes, event.getValue(), type, null);
            } else {
              writeNullableType(bytes, null);
            }
            break;
          }
          case REMOVE:
            serializeByte(bytes, REMOVED);
            writeString(bytes, event.getKey());
            break;
        }
      }
    } else {
      VarIntSerializer.write(bytes, 0);
    }
    var count =
        value.values().stream()
            .filter(
                (v) -> v instanceof TrackedMultiValue<?, ?> trackedMultiValue &&
                    trackedMultiValue.isTransactionModified()
                    || v instanceof EntityImpl
                    && ((EntityImpl) v).isEmbedded()
                    && ((EntityImpl) v).isDirty())
            .count();
    VarIntSerializer.write(bytes, count);
    for (var singleEntry : value.entrySet()) {
      var singleValue = singleEntry.getValue();
      if (singleValue instanceof TrackedMultiValue<?, ?> trackedMultiValue
          && trackedMultiValue.isTransactionModified()) {
        serializeByte(bytes, CHANGED);
        writeString(bytes, singleEntry.getKey());
        var type = PropertyTypeInternal.getTypeByValue(singleValue);
        writeNullableType(bytes, type);
        serializeDeltaValue(session, bytes, singleValue, type);
      } else if (singleValue instanceof EntityImpl
          && ((EntityImpl) singleValue).isEmbedded()
          && ((EntityImpl) singleValue).isDirty()) {
        serializeByte(bytes, CHANGED);
        writeString(bytes, singleEntry.getKey());
        var type = PropertyTypeInternal.getTypeByValue(singleValue);
        writeNullableType(bytes, type);
        serializeDeltaValue(session, bytes, singleValue, type);
      }
    }
  }

  private static void serializeDeltaEmbeddedList(DatabaseSessionInternal session,
      BytesContainer bytes,
      EntityEmbeddedListImpl<?> value) {
    var timeline = value.getTransactionTimeLine();
    if (timeline != null) {
      VarIntSerializer.write(bytes, timeline.getMultiValueChangeEvents().size());
      for (var event : timeline.getMultiValueChangeEvents()) {
        switch (event.getChangeType()) {
          case ADD: {
            serializeByte(bytes, CREATED);
            if (event.getValue() != null) {
              var type = PropertyTypeInternal.getTypeByValue(event.getValue());
              writeNullableType(bytes, type);
              serializeValue(session, bytes, event.getValue(), type, null);
            } else {
              writeNullableType(bytes, null);
            }
            break;
          }
          case UPDATE: {
            serializeByte(bytes, REPLACED);
            VarIntSerializer.write(bytes, event.getKey().longValue());
            if (event.getValue() != null) {
              var type = PropertyTypeInternal.getTypeByValue(event.getValue());
              writeNullableType(bytes, type);
              serializeValue(session, bytes, event.getValue(), type, null);
            } else {
              writeNullableType(bytes, null);
            }
            break;
          }
          case REMOVE: {
            serializeByte(bytes, REMOVED);
            VarIntSerializer.write(bytes, event.getKey().longValue());
            break;
          }
        }
      }
    } else {
      VarIntSerializer.write(bytes, 0);
    }
    var count =
        value.stream()
            .filter(
                (v) -> v instanceof TrackedMultiValue<?, ?> trackedMultiValue
                    && trackedMultiValue.isTransactionModified()
                    || v instanceof EntityImpl
                    && ((EntityImpl) v).isEmbedded()
                    && ((EntityImpl) v).isDirty())
            .count();
    VarIntSerializer.write(bytes, count);
    for (var i = 0; i < value.size(); i++) {
      var singleValue = value.get(i);
      if (singleValue instanceof TrackedMultiValue<?, ?> trackedMultiValue
          && trackedMultiValue.isTransactionModified()) {
        serializeByte(bytes, CHANGED);
        VarIntSerializer.write(bytes, i);
        var type = PropertyTypeInternal.getTypeByValue(singleValue);
        writeNullableType(bytes, type);
        serializeDeltaValue(session, bytes, singleValue, type);
      } else if (singleValue instanceof EntityImpl
          && ((EntityImpl) singleValue).isEmbedded()
          && ((EntityImpl) singleValue).isDirty()) {
        serializeByte(bytes, CHANGED);
        VarIntSerializer.write(bytes, i);
        var type = PropertyTypeInternal.getTypeByValue(singleValue);
        writeNullableType(bytes, type);
        serializeDeltaValue(session, bytes, singleValue, type);
      }
    }
  }

  private static void serializeDeltaEmbeddedSet(DatabaseSessionInternal session,
      BytesContainer bytes,
      EntityEmbeddedSetImpl<?> value) {
    var timeline = value.getTransactionTimeLine();
    if (timeline != null) {
      VarIntSerializer.write(bytes, timeline.getMultiValueChangeEvents().size());
      for (var event : timeline.getMultiValueChangeEvents()) {
        switch (event.getChangeType()) {
          case ADD: {
            serializeByte(bytes, CREATED);
            if (event.getValue() != null) {
              var type = PropertyTypeInternal.getTypeByValue(event.getValue());
              writeNullableType(bytes, type);
              serializeValue(session, bytes, event.getValue(), type, null);
            } else {
              writeNullableType(bytes, null);
            }
            break;
          }
          case UPDATE:
            throw new UnsupportedOperationException(
                "update do not happen in sets, it will be like and add");
          case REMOVE: {
            serializeByte(bytes, REMOVED);
            if (event.getOldValue() != null) {
              var type = PropertyTypeInternal.getTypeByValue(event.getOldValue());
              writeNullableType(bytes, type);
              serializeValue(session, bytes, event.getOldValue(), type, null);
            } else {
              writeNullableType(bytes, null);
            }
            break;
          }
        }
      }
    } else {
      VarIntSerializer.write(bytes, 0);
    }
    var count =
        value.stream()
            .filter(
                (v) -> v instanceof TrackedMultiValue<?, ?> trackedMultiValue
                    && trackedMultiValue.isTransactionModified()
                    || v instanceof EntityImpl
                    && ((EntityImpl) v).isEmbedded()
                    && ((EntityImpl) v).isDirty())
            .count();
    VarIntSerializer.write(bytes, count);
    var i = 0;
    for (var singleValue : value) {
      if (singleValue instanceof TrackedMultiValue<?, ?> trackedMultiValue
          && trackedMultiValue.isTransactionModified()) {
        serializeByte(bytes, CHANGED);
        VarIntSerializer.write(bytes, i);
        var type = PropertyTypeInternal.getTypeByValue(singleValue);
        writeNullableType(bytes, type);
        serializeDeltaValue(session, bytes, singleValue, type);
      } else if (singleValue instanceof EntityImpl
          && ((EntityImpl) singleValue).isEmbedded()
          && ((EntityImpl) singleValue).isDirty()) {
        serializeByte(bytes, CHANGED);
        VarIntSerializer.write(bytes, i);
        var type = PropertyTypeInternal.getTypeByValue(singleValue);
        writeNullableType(bytes, type);
        serializeDeltaValue(session, bytes, singleValue, type);
      }
      i++;
    }
  }

  protected static PropertyTypeInternal getFieldType(final EntityEntry entry) {
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

  private static void serializeFullEntry(
      @Nonnull DatabaseSessionInternal session, BytesContainer bytes, SchemaClass oClass,
      String name,
      EntityEntry entry) {
    final var value = entry.value;
    if (value != null) {
      final var type = getFieldType(entry);
      if (type == null) {
        throw new SerializationException(session.getDatabaseName(),
            "Impossible serialize value of type "
                + value.getClass()
                + " with the delta serializer");
      }
      writeString(bytes, name);
      writeNullableType(bytes, type);
      serializeValue(session, bytes, value, type, getLinkedType(session, oClass, type, name));
    } else {
      writeString(bytes, name);
      writeNullableType(bytes, null);
    }
  }

  protected static byte deserializeByte(BytesContainer bytes) {
    var pos = bytes.offset;
    bytes.skip(1);
    return bytes.bytes[pos];
  }

  protected static void serializeByte(BytesContainer bytes, byte value) {
    var pointer = bytes.alloc(1);
    bytes.bytes[pointer] = value;
  }

  public static void serializeValue(
      DatabaseSessionInternal session, final BytesContainer bytes, Object value,
      final PropertyTypeInternal type,
      final PropertyTypeInternal linkedType) {
    var pointer = 0;
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
          var transaction = session.getActiveTransaction();
          serialize(session, transaction.load(((DBRecord) value)), bytes);
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
        pointer = bytes.alloc(
            DecimalSerializer.INSTANCE.getObjectSize(session.getSerializerFactory(), decimalValue));
        DecimalSerializer.INSTANCE.serialize(decimalValue, session.getSerializerFactory(),
            bytes.bytes, pointer);
        break;
      case BINARY:
        writeBinary(bytes, (byte[]) (value));
        break;
      case LINKSET:
      case LINKLIST:
        @SuppressWarnings("unchecked")
        var ridCollection = (Collection<Identifiable>) value;
        writeLinkCollection(session, bytes, ridCollection);
        break;
      case LINK:
        if (!(value instanceof Identifiable)) {
          throw new ValidationException(session.getDatabaseName(),
              "Value '" + value + "' is not a Identifiable");
        }

        writeOptimizedLink(session, bytes, (Identifiable) value);
        break;
      case LINKMAP:
        //noinspection unchecked
        writeLinkMap(session, bytes, (Map<Object, Identifiable>) value);
        break;
      case EMBEDDEDMAP:
        //noinspection unchecked
        writeEmbeddedMap(session, bytes, (Map<Object, Object>) value);
        break;
      case LINKBAG:
        writeLinkBag(session, bytes, (RidBag) value);
        break;
    }
  }

  private static void writeLinkCollection(
      DatabaseSessionInternal session, final BytesContainer bytes,
      final Collection<Identifiable> value) {
    VarIntSerializer.write(bytes, value.size());

    for (var itemValue : value) {
      // TODO: handle the null links
      if (itemValue == null) {
        writeNullLink(bytes);
      } else {
        writeOptimizedLink(session, bytes, itemValue);
      }
    }

  }

  private static void writeLinkMap(DatabaseSessionInternal session, final BytesContainer bytes,
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
        writeOptimizedLink(session, bytes, entry.getValue());
      }
    }
  }

  private static void writeEmbeddedCollection(
      DatabaseSessionInternal session, final BytesContainer bytes, final Collection<?> value,
      final PropertyTypeInternal linkedType) {
    VarIntSerializer.write(bytes, value.size());
    // TODO manage embedded type from schema and auto-determined.
    for (var itemValue : value) {
      // TODO:manage in a better way null entry
      if (itemValue == null) {
        writeNullableType(bytes, null);
        continue;
      }
      PropertyTypeInternal type;
      if (linkedType == null) {
        type = getTypeFromValueEmbedded(itemValue);
      } else {
        type = linkedType;
      }
      if (type != null) {
        writeNullableType(bytes, type);
        serializeValue(session, bytes, itemValue, type, null);
      } else {
        throw new SerializationException(session.getDatabaseName(),
            "Impossible serialize value of type "
                + value.getClass()
                + " with the EntityImpl binary serializer");
      }
    }
  }

  private static void writeEmbeddedMap(DatabaseSessionInternal session, BytesContainer bytes,
      Map<Object, Object> map) {
    VarIntSerializer.write(bytes, map.size());
    for (var entry : map.entrySet()) {
      writeString(bytes, entry.getKey().toString());
      final var value = entry.getValue();
      if (value != null) {
        final var type = getTypeFromValueEmbedded(value);
        if (type == null) {
          throw new SerializationException(session.getDatabaseName(),
              "Impossible serialize value of type "
                  + value.getClass()
                  + " with the Result binary serializer");
        }
        writeNullableType(bytes, type);
        serializeValue(session, bytes, value, type, null);
      } else {
        writeNullableType(bytes, null);
      }
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
        value = DecimalSerializer.INSTANCE.deserialize(session.getSerializerFactory(), bytes.bytes,
            bytes.offset);
        bytes.skip(
            DecimalSerializer.INSTANCE.getObjectSize(session.getSerializerFactory(), bytes.bytes,
                bytes.offset));
        break;
      case LINKBAG:
        var bag = readLinkBag(session, bytes);
        bag.setOwner(owner);
        value = bag;
        break;
    }
    return value;
  }

  private EntityEmbeddedListImpl<?> readEmbeddedList(DatabaseSessionInternal session,
      final BytesContainer bytes, final RecordElement owner) {
    var found = new EntityEmbeddedListImpl<>(owner);
    final var items = VarIntSerializer.readAsInteger(bytes);
    for (var i = 0; i < items; i++) {
      var itemType = readNullableType(bytes);
      if (itemType == null) {
        found.addInternal(null);
      } else {
        found.addInternal(deserializeValue(session, bytes, itemType, found));
      }
    }
    return found;
  }

  private EntityEmbeddedSetImpl<?> readEmbeddedSet(DatabaseSessionInternal session,
      final BytesContainer bytes, final RecordElement owner) {
    var found = new EntityEmbeddedSetImpl<>(owner);
    final var items = VarIntSerializer.readAsInteger(bytes);
    for (var i = 0; i < items; i++) {
      var itemType = readNullableType(bytes);
      if (itemType == null) {
        found.addInternal(null);
      } else {
        found.addInternal(deserializeValue(session, bytes, itemType, found));
      }
    }
    return found;
  }

  private static Collection<Identifiable> readLinkList(DatabaseSessionInternal session,
      BytesContainer bytes,
      RecordElement owner) {
    var found = new EntityLinkListImpl(owner);
    final var items = VarIntSerializer.readAsInteger(bytes);
    for (var i = 0; i < items; i++) {
      Identifiable id = readOptimizedLink(session, bytes);
      if (id.equals(NULL_RECORD_ID)) {
        found.addInternal(null);
      } else {
        found.addInternal(id);
      }
    }
    return found;
  }

  private static Collection<Identifiable> readLinkSet(DatabaseSessionInternal session,
      BytesContainer bytes,
      RecordElement owner) {
    var found = new EntityLinkSetImpl(owner);
    final var items = VarIntSerializer.readAsInteger(bytes);
    for (var i = 0; i < items; i++) {
      Identifiable id = readOptimizedLink(session, bytes);
      if (id.equals(NULL_RECORD_ID)) {
        found.addInternal(null);
      } else {
        found.addInternal(id);
      }
    }
    return found;
  }

  private Map<String, Identifiable> readLinkMap(
      DatabaseSessionInternal session, final BytesContainer bytes, final RecordElement owner) {
    var size = VarIntSerializer.readAsInteger(bytes);
    var result = new EntityLinkMapIml(owner);
    while ((size--) > 0) {
      var keyType = readOType(bytes, false);
      var key = deserializeValue(session, bytes, keyType, result);
      Identifiable value = readOptimizedLink(session, bytes);
      if (value.equals(NULL_RECORD_ID)) {
        result.putInternal(key.toString(), null);
      } else {
        result.putInternal(key.toString(), value);
      }
    }

    return result;
  }

  private Object readEmbeddedMap(DatabaseSessionInternal session, final BytesContainer bytes,
      final RecordElement owner) {
    var size = VarIntSerializer.readAsInteger(bytes);
    final var result = new EntityEmbeddedMapImpl<>(owner);
    while ((size--) > 0) {
      var key = readString(bytes);
      var valType = readNullableType(bytes);
      Object value = null;
      if (valType != null) {
        value = deserializeValue(session, bytes, valType, result);
      }
      result.putInternal(key, value);
    }
    return result;
  }

  private static RidBag readLinkBag(DatabaseSessionInternal session, BytesContainer bytes) {
    return RecordSerializerNetworkV37.INSTANCE.readLinkBag(session, bytes);
  }

  private static void writeLinkBag(DatabaseSessionInternal session, BytesContainer bytes,
      RidBag bag) {
    RecordSerializerNetworkV37.INSTANCE.writeLinkBag(session, bytes, bag);
  }

  public static void writeNullableType(BytesContainer bytes, PropertyTypeInternal type) {
    var pos = bytes.alloc(1);
    if (type == null) {
      bytes.bytes[pos] = -1;
    } else {
      bytes.bytes[pos] = (byte) type.getId();
    }
  }

  public static PropertyTypeInternal readNullableType(BytesContainer bytes) {
    return HelperClasses.readType(bytes);
  }

  @Nullable
  public static RID readOptimizedLink(DatabaseSessionInternal session, final BytesContainer bytes) {
    var collectionId = VarIntSerializer.readAsInteger(bytes);
    var collectionPos = VarIntSerializer.readAsLong(bytes);

    if (collectionId == -2 && collectionPos == -2) {
      return null;
    } else {
      RID rid = new RecordId(collectionId, collectionPos);

      if (!rid.isPersistent()) {
        rid = session.refreshRid(rid);
      }

      return rid;
    }
  }

  public static void writeOptimizedLink(DatabaseSessionInternal session, final BytesContainer bytes,
      Identifiable link) {
    if (link == null) {
      VarIntSerializer.write(bytes, -2);
      VarIntSerializer.write(bytes, -2);
    } else {
      RecordSerializerNetworkV37.writeOptimizedLink(session, bytes, link);
    }
  }
}
