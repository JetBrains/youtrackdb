package com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary;

import static com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary.HelperClasses.MILLISEC_PER_DAY;
import static com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary.HelperClasses.NULL_RECORD_ID;
import static com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary.HelperClasses.convertDayToTimezone;
import static com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary.HelperClasses.getLinkedType;
import static com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary.HelperClasses.getTypeFromValueEmbedded;
import static com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary.HelperClasses.readBinary;
import static com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary.HelperClasses.readByte;
import static com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary.HelperClasses.readInteger;
import static com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary.HelperClasses.readLong;
import static com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary.HelperClasses.readOType;
import static com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary.HelperClasses.readString;
import static com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary.HelperClasses.writeBinary;
import static com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary.HelperClasses.writeNullLink;
import static com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary.HelperClasses.writeOType;
import static com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary.HelperClasses.writeString;

import com.jetbrains.youtrackdb.internal.common.collection.MultiValue;
import com.jetbrains.youtrackdb.internal.common.serialization.types.DecimalSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.IntegerSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.LongSerializer;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.EntityEmbeddedListImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.EntityEmbeddedMapImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.EntityEmbeddedSetImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.EntityLinkListImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.EntityLinkMapIml;
import com.jetbrains.youtrackdb.internal.core.db.record.EntityLinkSetImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.RecordElement;
import com.jetbrains.youtrackdb.internal.core.db.record.TrackedMultiValue;
import com.jetbrains.youtrackdb.internal.core.db.record.record.DBRecord;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.db.record.ridbag.LinkBag;
import com.jetbrains.youtrackdb.internal.core.exception.SerializationException;
import com.jetbrains.youtrackdb.internal.core.exception.ValidationException;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaImmutableClass;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.record.impl.EmbeddedEntityImpl;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityEntry;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.serialization.EntitySerializable;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.BTreeBasedLinkBag;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.LinkBagPointer;
import com.jetbrains.youtrackdb.internal.core.util.DateHelper;
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

  public static byte[] serialize(DatabaseSessionEmbedded session, EntityImpl entity) {
    var bytes = new BytesContainer();
    serialize(session, entity, bytes);
    return bytes.fitBytes();
  }

  public static byte[] serializeDelta(DatabaseSessionEmbedded session, EntityImpl entity) {
    var bytes = new BytesContainer();
    serializeDelta(session, bytes, entity);
    return bytes.fitBytes();
  }

  protected static void serializeClass(DatabaseSessionEmbedded session, final EntityImpl entity,
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

  private static void serialize(DatabaseSessionEmbedded session, final EntityImpl entity,
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

  public void deserialize(DatabaseSessionEmbedded session, byte[] content, EntityImpl toFill) {
    var bytesContainer = new BytesContainer(content);
    deserialize(session, toFill, bytesContainer);
  }

  private void deserialize(DatabaseSessionEmbedded session, final EntityImpl entity,
      final BytesContainer bytes) {
    final var className = readString(bytes);
    if (!className.isEmpty()) {
      entity.setClassNameWithoutPropertiesPostProcessing(className);
    }

    String fieldName;
    PropertyTypeInternal type;
    Object value;
    var size = VarIntSerializer.readAsInteger(bytes);
    while (size-- > 0) {
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

  public void deserializeDelta(DatabaseSessionEmbedded session, byte[] content,
      EntityImpl toFill) {
    var bytesContainer = new BytesContainer(content);
    deserializeDelta(session, bytesContainer, toFill);
  }

  public void deserializeDelta(DatabaseSessionEmbedded session, BytesContainer bytes,
      EntityImpl toFill) {
    final var className = readString(bytes);
    if (!className.isEmpty() && toFill != null) {
      toFill.setClassNameWithoutPropertiesPostProcessing(className);
    }
    var count = VarIntSerializer.readAsLong(bytes);
    while (count-- > 0) {
      switch (deserializeByte(bytes)) {
        case CREATED, REPLACED -> deserializeFullEntry(session, bytes, toFill);
        case CHANGED -> deserializeDeltaEntry(session, bytes, toFill);
        case REMOVED -> {
          var property = readString(bytes);
          if (toFill != null) {
            toFill.removePropertyInternal(property);
          }
        }
        default -> {
        }
      }
    }
  }

  private void deserializeDeltaEntry(DatabaseSessionEmbedded session, BytesContainer bytes,
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

  private void deserializeDeltaValue(DatabaseSessionEmbedded session, BytesContainer bytes,
      PropertyTypeInternal type, Object toUpdate) {
    switch (type) {
      case EMBEDDEDLIST ->
          //noinspection unchecked
          deserializeDeltaEmbeddedList(session, bytes, (EntityEmbeddedListImpl<Object>) toUpdate);
      case EMBEDDEDSET ->
          //noinspection unchecked
          deserializeDeltaEmbeddedSet(session, bytes, (EntityEmbeddedSetImpl<Object>) toUpdate);
      case EMBEDDEDMAP ->
          //noinspection unchecked
          deserializeDeltaEmbeddedMap(session, bytes, (EntityEmbeddedMapImpl<Object>) toUpdate);
      case EMBEDDED -> {
        var transaction = session.getActiveTransaction();
        deserializeDelta(session, bytes, transaction.load(((DBRecord) toUpdate)));
      }
      case LINKLIST ->
          deserializeDeltaLinkList(session, bytes, (EntityLinkListImpl) toUpdate);
      case LINKSET ->
          deserializeDeltaLinkSet(session, bytes, (EntityLinkSetImpl) toUpdate);
      case LINKMAP ->
          deserializeDeltaLinkMap(session, bytes, (EntityLinkMapIml) toUpdate);
      case LINKBAG ->
          deserializeDeltaLinkBag(session, bytes, (LinkBag) toUpdate);
      default -> throw new SerializationException(session.getDatabaseName(),
          "delta not supported for type:" + type);
    }
  }

  private static void deserializeDeltaLinkMap(DatabaseSessionEmbedded session, BytesContainer bytes,
      EntityLinkMapIml toUpdate) {
    var rootChanges = VarIntSerializer.readAsLong(bytes);
    while (rootChanges-- > 0) {
      var change = deserializeByte(bytes);
      switch (change) {
        case CREATED -> {
          var key = readString(bytes);
          var link = readOptimizedLink(session, bytes);
          if (toUpdate != null) {
            toUpdate.put(key, link);
          }
        }
        case REPLACED -> {
          var key = readString(bytes);
          var link = readOptimizedLink(session, bytes);
          if (toUpdate != null) {
            toUpdate.put(key, link);
          }
        }
        case REMOVED -> {
          var key = readString(bytes);
          if (toUpdate != null) {
            toUpdate.remove(key);
          }
        }
        default -> {
        }
      }
    }
  }

  protected static void deserializeDeltaLinkBag(DatabaseSessionEmbedded session,
      BytesContainer bytes,
      LinkBag toUpdate) {
    var rootChanges = VarIntSerializer.readAsLong(bytes);
    while (rootChanges-- > 0) {
      var change = deserializeByte(bytes);
      switch (change) {
        case CREATED -> {
          var link = readOptimizedLink(session, bytes);
          if (toUpdate != null) {
            toUpdate.add(link);
          }
        }
        case REPLACED -> {
        }
        case REMOVED -> {
          var link = readOptimizedLink(session, bytes);
          if (toUpdate != null) {
            toUpdate.remove(link);
          }
        }
        default -> {
        }
      }
    }
  }

  private static void deserializeDeltaLinkList(DatabaseSessionEmbedded session,
      BytesContainer bytes,
      EntityLinkListImpl toUpdate) {
    var rootChanges = VarIntSerializer.readAsLong(bytes);
    while (rootChanges-- > 0) {
      var change = deserializeByte(bytes);
      switch (change) {
        case CREATED -> {
          var link = readOptimizedLink(session, bytes);
          if (toUpdate != null) {
            toUpdate.add(link);
          }
        }
        case REPLACED -> {
          var position = VarIntSerializer.readAsLong(bytes);
          var link = readOptimizedLink(session, bytes);
          if (toUpdate != null) {
            toUpdate.set((int) position, link);
          }
        }
        case REMOVED -> {
          var link = readOptimizedLink(session, bytes);
          if (toUpdate != null) {
            toUpdate.remove(link);
          }
        }
        default -> {
        }
      }
    }
  }

  private static void deserializeDeltaLinkSet(DatabaseSessionEmbedded session, BytesContainer bytes,
      EntityLinkSetImpl toUpdate) {
    var rootChanges = VarIntSerializer.readAsLong(bytes);
    while (rootChanges-- > 0) {
      var change = deserializeByte(bytes);
      switch (change) {
        case CREATED -> {
          var link = readOptimizedLink(session, bytes);
          if (toUpdate != null) {
            toUpdate.add(link);
          }
        }
        case REPLACED -> {
        }
        case REMOVED -> {
          var link = readOptimizedLink(session, bytes);
          if (toUpdate != null) {
            toUpdate.remove(link);
          }
        }
        default -> {
        }
      }
    }
  }

  private void deserializeDeltaEmbeddedMap(DatabaseSessionEmbedded session, BytesContainer bytes,
      EntityEmbeddedMapImpl<Object> toUpdate) {
    var rootChanges = VarIntSerializer.readAsLong(bytes);
    while (rootChanges-- > 0) {
      var change = deserializeByte(bytes);
      switch (change) {
        case CREATED -> {
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
        }
        case REPLACED -> {
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
        }
        case REMOVED -> {
          var key = readString(bytes);
          if (toUpdate != null) {
            toUpdate.remove(key);
          }
        }
        default -> {
        }
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

  private void deserializeDeltaEmbeddedSet(DatabaseSessionEmbedded session, BytesContainer bytes,
      EntityEmbeddedSetImpl<Object> toUpdate) {
    var rootChanges = VarIntSerializer.readAsLong(bytes);
    while (rootChanges-- > 0) {
      var change = deserializeByte(bytes);
      switch (change) {
        case CREATED -> {
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
        }
        case REPLACED, REMOVED -> {
          assert change != REPLACED : "this can't ever happen";
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
        }
        default -> {
        }
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

  private void deserializeDeltaEmbeddedList(DatabaseSessionEmbedded session, BytesContainer bytes,
      EntityEmbeddedListImpl<Object> toUpdate) {
    var rootChanges = VarIntSerializer.readAsLong(bytes);
    while (rootChanges-- > 0) {
      var change = deserializeByte(bytes);
      switch (change) {
        case CREATED -> {
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
        }
        case REPLACED -> {
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
        }
        case REMOVED -> {
          var pos = VarIntSerializer.readAsLong(bytes);
          if (toUpdate != null) {
            toUpdate.remove((int) pos);
          }
        }
        default -> {
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

  private void deserializeFullEntry(DatabaseSessionEmbedded session, BytesContainer bytes,
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

  public static void serializeDelta(DatabaseSessionEmbedded session, BytesContainer bytes,
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
      DatabaseSessionEmbedded session, BytesContainer bytes, String name,
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
      DatabaseSessionEmbedded session, BytesContainer bytes, Object value,
      PropertyTypeInternal type) {
    switch (type) {
      case EMBEDDEDLIST ->
          serializeDeltaEmbeddedList(session, bytes, (EntityEmbeddedListImpl<?>) value);
      case EMBEDDEDSET ->
          //noinspection unchecked
          serializeDeltaEmbeddedSet(session, bytes, (EntityEmbeddedSetImpl<Object>) value);
      case EMBEDDEDMAP ->
          //noinspection unchecked
          serializeDeltaEmbeddedMap(session, bytes, (EntityEmbeddedMapImpl<Object>) value);
      case EMBEDDED -> serializeDelta(session, bytes, (EntityImpl) value);
      case LINKLIST ->
          serializeDeltaLinkList(session, bytes, (EntityLinkListImpl) value);
      case LINKSET ->
          serializeDeltaLinkSet(session, bytes, (EntityLinkSetImpl) value);
      case LINKMAP ->
          serializeDeltaLinkMap(session, bytes, (EntityLinkMapIml) value);
      case LINKBAG ->
          serializeDeltaLinkBag(session, bytes, (LinkBag) value);
      default -> throw new SerializationException(session.getDatabaseName(),
          "delta not supported for type:" + type);
    }
  }

  protected static void serializeDeltaLinkBag(DatabaseSessionEmbedded session, BytesContainer bytes,
      LinkBag value) {
    final var timeline =
        value.getTransactionTimeLine();
    assert timeline != null : "Collection timeline required for serialization of link types";
    VarIntSerializer.write(bytes, timeline.getMultiValueChangeEvents().size());
    for (var event :
        timeline.getMultiValueChangeEvents()) {
      switch (event.getChangeType()) {
        case ADD -> {
          serializeByte(bytes, CREATED);
          writeOptimizedLink(session, bytes, event.getValue());
        }
        case UPDATE -> throw new UnsupportedOperationException(
            "update do not happen in sets, it will be like and add");
        case REMOVE -> {
          serializeByte(bytes, REMOVED);
          writeOptimizedLink(session, bytes, event.getOldValue());
        }
      }
    }
  }

  private static void serializeDeltaLinkSet(
      DatabaseSessionEmbedded session, BytesContainer bytes,
      TrackedMultiValue<Identifiable, Identifiable> value) {
    var timeline =
        value.getTransactionTimeLine();
    assert timeline != null : "Collection timeline required for link* types serialization";
    VarIntSerializer.write(bytes, timeline.getMultiValueChangeEvents().size());
    for (var event :
        timeline.getMultiValueChangeEvents()) {
      switch (event.getChangeType()) {
        case ADD -> {
          serializeByte(bytes, CREATED);
          writeOptimizedLink(session, bytes, event.getValue());
        }
        case UPDATE -> throw new UnsupportedOperationException(
            "update do not happen in sets, it will be like and add");
        case REMOVE -> {
          serializeByte(bytes, REMOVED);
          writeOptimizedLink(session, bytes, event.getOldValue());
        }
      }
    }
  }

  private static void serializeDeltaLinkList(DatabaseSessionEmbedded session, BytesContainer bytes,
      EntityLinkListImpl value) {
    var timeline = value.getTransactionTimeLine();
    assert timeline != null : "Collection timeline required for link* types serialization";
    VarIntSerializer.write(bytes, timeline.getMultiValueChangeEvents().size());
    for (var event :
        timeline.getMultiValueChangeEvents()) {
      switch (event.getChangeType()) {
        case ADD -> {
          serializeByte(bytes, CREATED);
          writeOptimizedLink(session, bytes, event.getValue());
        }
        case UPDATE -> {
          serializeByte(bytes, REPLACED);
          VarIntSerializer.write(bytes, event.getKey().longValue());
          writeOptimizedLink(session, bytes, event.getValue());
        }
        case REMOVE -> {
          serializeByte(bytes, REMOVED);
          writeOptimizedLink(session, bytes, event.getOldValue());
        }
      }
    }
  }

  private static void serializeDeltaLinkMap(DatabaseSessionEmbedded session, BytesContainer bytes,
      EntityLinkMapIml value) {
    var timeline = value.getTransactionTimeLine();
    assert timeline != null : "Collection timeline required for link* types serialization";
    VarIntSerializer.write(bytes, timeline.getMultiValueChangeEvents().size());
    for (var event :
        timeline.getMultiValueChangeEvents()) {
      switch (event.getChangeType()) {
        case ADD -> {
          serializeByte(bytes, CREATED);
          writeString(bytes, event.getKey());
          writeOptimizedLink(session, bytes, event.getValue());
        }
        case UPDATE -> {
          serializeByte(bytes, REPLACED);
          writeString(bytes, event.getKey());
          writeOptimizedLink(session, bytes, event.getValue());
        }
        case REMOVE -> {
          serializeByte(bytes, REMOVED);
          writeString(bytes, event.getKey());
        }
      }
    }
  }

  private static void serializeDeltaEmbeddedMap(DatabaseSessionEmbedded session,
      BytesContainer bytes,
      EntityEmbeddedMapImpl<?> value) {
    var timeline = value.getTransactionTimeLine();
    if (timeline != null) {
      VarIntSerializer.write(bytes, timeline.getMultiValueChangeEvents().size());
      for (var event : timeline.getMultiValueChangeEvents()) {
        switch (event.getChangeType()) {
          case ADD -> {
            serializeByte(bytes, CREATED);
            writeString(bytes, event.getKey());
            if (event.getValue() != null) {
              var type = PropertyTypeInternal.getTypeByValue(event.getValue());
              writeNullableType(bytes, type);
              serializeValue(session, bytes, event.getValue(), type, null);
            } else {
              writeNullableType(bytes, null);
            }
          }
          case UPDATE -> {
            serializeByte(bytes, REPLACED);
            writeString(bytes, event.getKey());
            if (event.getValue() != null) {
              var type = PropertyTypeInternal.getTypeByValue(event.getValue());
              writeNullableType(bytes, type);
              serializeValue(session, bytes, event.getValue(), type, null);
            } else {
              writeNullableType(bytes, null);
            }
          }
          case REMOVE -> {
            serializeByte(bytes, REMOVED);
            writeString(bytes, event.getKey());
          }
        }
      }
    } else {
      VarIntSerializer.write(bytes, 0);
    }
    var count =
        value.values().stream()
            .filter(
                (v) -> (v instanceof TrackedMultiValue<?, ?> trackedMultiValue
                    && trackedMultiValue.isTransactionModified())
                    || (v instanceof EntityImpl entity
                    && entity.isEmbedded()
                    && entity.isDirty()))
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
      } else if (singleValue instanceof EntityImpl entity
          && entity.isEmbedded()
          && entity.isDirty()) {
        serializeByte(bytes, CHANGED);
        writeString(bytes, singleEntry.getKey());
        var type = PropertyTypeInternal.getTypeByValue(singleValue);
        writeNullableType(bytes, type);
        serializeDeltaValue(session, bytes, singleValue, type);
      }
    }
  }

  private static void serializeDeltaEmbeddedList(DatabaseSessionEmbedded session,
      BytesContainer bytes,
      EntityEmbeddedListImpl<?> value) {
    var timeline = value.getTransactionTimeLine();
    if (timeline != null) {
      VarIntSerializer.write(bytes, timeline.getMultiValueChangeEvents().size());
      for (var event : timeline.getMultiValueChangeEvents()) {
        switch (event.getChangeType()) {
          case ADD -> {
            serializeByte(bytes, CREATED);
            if (event.getValue() != null) {
              var type = PropertyTypeInternal.getTypeByValue(event.getValue());
              writeNullableType(bytes, type);
              serializeValue(session, bytes, event.getValue(), type, null);
            } else {
              writeNullableType(bytes, null);
            }
          }
          case UPDATE -> {
            serializeByte(bytes, REPLACED);
            VarIntSerializer.write(bytes, event.getKey().longValue());
            if (event.getValue() != null) {
              var type = PropertyTypeInternal.getTypeByValue(event.getValue());
              writeNullableType(bytes, type);
              serializeValue(session, bytes, event.getValue(), type, null);
            } else {
              writeNullableType(bytes, null);
            }
          }
          case REMOVE -> {
            serializeByte(bytes, REMOVED);
            VarIntSerializer.write(bytes, event.getKey().longValue());
          }
        }
      }
    } else {
      VarIntSerializer.write(bytes, 0);
    }
    var count =
        value.stream()
            .filter(
                (v) -> (v instanceof TrackedMultiValue<?, ?> trackedMultiValue
                    && trackedMultiValue.isTransactionModified())
                    || (v instanceof EntityImpl entity
                    && entity.isEmbedded()
                    && entity.isDirty()))
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
      } else if (singleValue instanceof EntityImpl entity
          && entity.isEmbedded()
          && entity.isDirty()) {
        serializeByte(bytes, CHANGED);
        VarIntSerializer.write(bytes, i);
        var type = PropertyTypeInternal.getTypeByValue(singleValue);
        writeNullableType(bytes, type);
        serializeDeltaValue(session, bytes, singleValue, type);
      }
    }
  }

  private static void serializeDeltaEmbeddedSet(DatabaseSessionEmbedded session,
      BytesContainer bytes,
      EntityEmbeddedSetImpl<?> value) {
    var timeline = value.getTransactionTimeLine();
    if (timeline != null) {
      VarIntSerializer.write(bytes, timeline.getMultiValueChangeEvents().size());
      for (var event : timeline.getMultiValueChangeEvents()) {
        switch (event.getChangeType()) {
          case ADD -> {
            serializeByte(bytes, CREATED);
            if (event.getValue() != null) {
              var type = PropertyTypeInternal.getTypeByValue(event.getValue());
              writeNullableType(bytes, type);
              serializeValue(session, bytes, event.getValue(), type, null);
            } else {
              writeNullableType(bytes, null);
            }
          }
          case UPDATE -> throw new UnsupportedOperationException(
              "update do not happen in sets, it will be like and add");
          case REMOVE -> {
            serializeByte(bytes, REMOVED);
            if (event.getOldValue() != null) {
              var type = PropertyTypeInternal.getTypeByValue(event.getOldValue());
              writeNullableType(bytes, type);
              serializeValue(session, bytes, event.getOldValue(), type, null);
            } else {
              writeNullableType(bytes, null);
            }
          }
        }
      }
    } else {
      VarIntSerializer.write(bytes, 0);
    }
    var count =
        value.stream()
            .filter(
                (v) -> (v instanceof TrackedMultiValue<?, ?> trackedMultiValue
                    && trackedMultiValue.isTransactionModified())
                    || (v instanceof EntityImpl entity
                    && entity.isEmbedded()
                    && entity.isDirty()))
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
      } else if (singleValue instanceof EntityImpl entity
          && entity.isEmbedded()
          && entity.isDirty()) {
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
      @Nonnull DatabaseSessionEmbedded session, BytesContainer bytes, SchemaClass oClass,
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
      DatabaseSessionEmbedded session, final BytesContainer bytes, Object value,
      final PropertyTypeInternal type,
      final PropertyTypeInternal linkedType) {
    switch (type) {
      case INTEGER, LONG, SHORT ->
          VarIntSerializer.write(bytes, ((Number) value).longValue());
      case STRING -> writeString(bytes, value.toString());
      case DOUBLE -> {
        var dg = Double.doubleToLongBits((Double) value);
        var pointer = bytes.alloc(LongSerializer.LONG_SIZE);
        LongSerializer.serializeLiteral(dg, bytes.bytes, pointer);
      }
      case FLOAT -> {
        var fg = Float.floatToIntBits((Float) value);
        var pointer = bytes.alloc(IntegerSerializer.INT_SIZE);
        IntegerSerializer.serializeLiteral(fg, bytes.bytes, pointer);
      }
      case BYTE -> {
        var pointer = bytes.alloc(1);
        bytes.bytes[pointer] = (Byte) value;
      }
      case BOOLEAN -> {
        var pointer = bytes.alloc(1);
        bytes.bytes[pointer] = ((Boolean) value) ? (byte) 1 : (byte) 0;
      }
      case DATETIME -> {
        if (value instanceof Long longVal) {
          VarIntSerializer.write(bytes, longVal);
        } else {
          VarIntSerializer.write(bytes, ((Date) value).getTime());
        }
      }
      case DATE -> {
        long dateValue;
        if (value instanceof Long longVal) {
          dateValue = longVal;
        } else {
          dateValue = ((Date) value).getTime();
        }
        dateValue =
            convertDayToTimezone(
                DateHelper.getDatabaseTimeZone(session), TimeZone.getTimeZone("GMT"), dateValue);
        VarIntSerializer.write(bytes, dateValue / MILLISEC_PER_DAY);
      }
      case EMBEDDED -> {
        if (value instanceof EntitySerializable entitySerializable) {
          var cur = entitySerializable.toEntity(session);
          cur.setProperty(EntitySerializable.CLASS_NAME, value.getClass().getName());
          serialize(session, cur, bytes);
        } else {
          var transaction = session.getActiveTransaction();
          serialize(session, transaction.load(((DBRecord) value)), bytes);
        }
      }
      case EMBEDDEDSET, EMBEDDEDLIST -> {
        if (value.getClass().isArray()) {
          writeEmbeddedCollection(session, bytes, Arrays.asList(MultiValue.array(value)),
              linkedType);
        } else {
          writeEmbeddedCollection(session, bytes, (Collection<?>) value, linkedType);
        }
      }
      case DECIMAL -> {
        var decimalValue = (BigDecimal) value;
        var pointer = bytes.alloc(
            DecimalSerializer.INSTANCE.getObjectSize(session.getSerializerFactory(), decimalValue));
        DecimalSerializer.INSTANCE.serialize(decimalValue, session.getSerializerFactory(),
            bytes.bytes, pointer);
      }
      case BINARY -> writeBinary(bytes, (byte[]) value);
      case LINKSET -> writeLinkSet(session, bytes, (EntityLinkSetImpl) value);
      case LINKLIST -> {
        @SuppressWarnings("unchecked")
        var ridCollection = (Collection<Identifiable>) value;
        writeLinkCollection(session, bytes, ridCollection);
      }
      case LINK -> {
        if (!(value instanceof Identifiable identifiable)) {
          throw new ValidationException(session.getDatabaseName(),
              "Value '" + value + "' is not a Identifiable");
        }

        writeOptimizedLink(session, bytes, identifiable);
      }
      case LINKMAP ->
          //noinspection unchecked
          writeLinkMap(session, bytes, (Map<Object, Identifiable>) value);
      case EMBEDDEDMAP ->
          //noinspection unchecked
          writeEmbeddedMap(session, bytes, (Map<Object, Object>) value);
      case LINKBAG -> writeLinkBag(session, bytes, (LinkBag) value);
      default -> {
      }
    }
  }

  private static void writeLinkCollection(
      DatabaseSessionEmbedded session, final BytesContainer bytes,
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

  private static void writeLinkMap(DatabaseSessionEmbedded session, final BytesContainer bytes,
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
      DatabaseSessionEmbedded session, final BytesContainer bytes, final Collection<?> value,
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

  private static void writeEmbeddedMap(DatabaseSessionEmbedded session, BytesContainer bytes,
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

  public Object deserializeValue(DatabaseSessionEmbedded session, BytesContainer bytes,
      PropertyTypeInternal type,
      RecordElement owner) {
    Object value = null;
    switch (type) {
      case INTEGER -> value = VarIntSerializer.readAsInteger(bytes);
      case LONG -> value = VarIntSerializer.readAsLong(bytes);
      case SHORT -> value = VarIntSerializer.readAsShort(bytes);
      case STRING -> value = readString(bytes);
      case DOUBLE -> value = Double.longBitsToDouble(readLong(bytes));
      case FLOAT -> value = Float.intBitsToFloat(readInteger(bytes));
      case BYTE -> value = readByte(bytes);
      case BOOLEAN -> value = readByte(bytes) == 1;
      case DATETIME -> value = new Date(VarIntSerializer.readAsLong(bytes));
      case DATE -> {
        var savedTime = VarIntSerializer.readAsLong(bytes) * MILLISEC_PER_DAY;
        savedTime =
            convertDayToTimezone(
                TimeZone.getTimeZone("GMT"), DateHelper.getDatabaseTimeZone(session), savedTime);
        value = new Date(savedTime);
      }
      case EMBEDDED -> {
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
      }
      case EMBEDDEDSET -> value = readEmbeddedSet(session, bytes, owner);
      case EMBEDDEDLIST -> value = readEmbeddedList(session, bytes, owner);
      case LINKSET -> value = readLinkSet(session, bytes);
      case LINKLIST -> value = readLinkList(session, bytes, owner);
      case BINARY -> value = readBinary(bytes);
      case LINK -> value = readOptimizedLink(session, bytes);
      case LINKMAP -> value = readLinkMap(session, bytes, owner);
      case EMBEDDEDMAP -> value = readEmbeddedMap(session, bytes, owner);
      case DECIMAL -> {
        value = DecimalSerializer.INSTANCE.deserialize(session.getSerializerFactory(), bytes.bytes,
            bytes.offset);
        bytes.skip(
            DecimalSerializer.INSTANCE.getObjectSize(session.getSerializerFactory(), bytes.bytes,
                bytes.offset));
      }
      case LINKBAG -> {
        var bag = readLinkBag(session, bytes);
        bag.setOwner(owner);
        value = bag;
      }
      default -> {
      }
    }
    return value;
  }

  private EntityEmbeddedListImpl<?> readEmbeddedList(DatabaseSessionEmbedded session,
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

  private EntityEmbeddedSetImpl<?> readEmbeddedSet(DatabaseSessionEmbedded session,
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

  private static Collection<Identifiable> readLinkList(DatabaseSessionEmbedded session,
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

  private Map<String, Identifiable> readLinkMap(
      DatabaseSessionEmbedded session, final BytesContainer bytes, final RecordElement owner) {
    var size = VarIntSerializer.readAsInteger(bytes);
    var result = new EntityLinkMapIml(owner);
    while (size-- > 0) {
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

  private Object readEmbeddedMap(DatabaseSessionEmbedded session, final BytesContainer bytes,
      final RecordElement owner) {
    var size = VarIntSerializer.readAsInteger(bytes);
    final var result = new EntityEmbeddedMapImpl<>(owner);
    while (size-- > 0) {
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

  private static EntityLinkSetImpl readLinkSet(DatabaseSessionEmbedded session,
      BytesContainer bytes) {
    var b = bytes.bytes[bytes.offset];
    bytes.skip(1);
    if (b == 1) {
      var bag = new EntityLinkSetImpl(session);
      // enable tracking due to timeline issue, which must not be NULL (i.e. tracker.isEnabled()).
      bag.enableTracking(null);

      var size = VarIntSerializer.readAsInteger(bytes);
      for (var i = 0; i < size; i++) {
        Identifiable id = readOptimizedLink(session, bytes);
        bag.add(id.getIdentity());
      }

      bag.disableTracking(null);
      bag.transactionClear();
      return bag;
    } else {
      var linkBagSize = VarIntSerializer.readAsInteger(bytes);
      var fileId = VarIntSerializer.readAsLong(bytes);
      var linkBagId = VarIntSerializer.readAsLong(bytes);

      var pointer = new LinkBagPointer(fileId, linkBagId);
      if (!pointer.isValid()) {
        throw new IllegalStateException("LinkSet with invalid pointer was found");
      }
      return new EntityLinkSetImpl(session,
          new BTreeBasedLinkBag(session, pointer, linkBagSize, 1));
    }
  }

  private static void writeLinkSet(DatabaseSessionEmbedded session, BytesContainer bytes,
      EntityLinkSetImpl linkSet) {
    if (linkSet.isToSerializeEmbedded()) {
      var pos = bytes.alloc(1);
      bytes.bytes[pos] = 1;
      VarIntSerializer.write(bytes, linkSet.size());

      for (var itemValue : linkSet) {
        writeOptimizedLink(session, bytes, itemValue);
      }
    } else {
      var pos = bytes.alloc(1);
      bytes.bytes[pos] = 2;

      var delegate = (BTreeBasedLinkBag) linkSet.getDelegate();
      var pointer = delegate.getCollectionPointer();

      if (pointer == null || !pointer.isValid()) {
        throw new IllegalStateException("LinkSet with invalid pointer was found");
      }

      VarIntSerializer.write(bytes, delegate.size());
      VarIntSerializer.write(bytes, pointer.fileId());
      VarIntSerializer.write(bytes, pointer.linkBagId());
    }
  }


  private static LinkBag readLinkBag(DatabaseSessionEmbedded session, BytesContainer bytes) {
    var b = bytes.bytes[bytes.offset];
    bytes.skip(1);
    if (b == 1) {
      var bag = new LinkBag(session);
      // enable tracking due to timeline issue, which must not be NULL (i.e. tracker.isEnabled()).
      bag.enableTracking(null);

      var size = VarIntSerializer.readAsInteger(bytes);
      for (var i = 0; i < size; i++) {
        Identifiable id = readOptimizedLink(session, bytes);
        bag.add(id.getIdentity());
      }

      bag.disableTracking(null);
      bag.transactionClear();
      return bag;
    } else {
      var linkBagSize = VarIntSerializer.readAsInteger(bytes);
      var fileId = VarIntSerializer.readAsLong(bytes);
      var linkBagId = VarIntSerializer.readAsLong(bytes);

      var pointer = new LinkBagPointer(fileId, linkBagId);
      if (!pointer.isValid()) {
        throw new IllegalStateException("LinkBag with invalid pointer was found");
      }
      return new LinkBag(session,
          new BTreeBasedLinkBag(session, pointer, linkBagSize, Integer.MAX_VALUE));
    }
  }

  private static void writeLinkBag(DatabaseSessionEmbedded session, BytesContainer bytes,
      LinkBag bag) {
    if (bag.isToSerializeEmbedded()) {
      var pos = bytes.alloc(1);
      bytes.bytes[pos] = 1;
      VarIntSerializer.write(bytes, bag.size());

      for (var itemValue : bag) {
        writeOptimizedLink(session, bytes, itemValue.primaryRid());
      }
    } else {
      var pos = bytes.alloc(1);
      bytes.bytes[pos] = 2;

      var delegate = (BTreeBasedLinkBag) bag.getDelegate();
      var pointer = delegate.getCollectionPointer();

      if (pointer == null || !pointer.isValid()) {
        throw new IllegalStateException("RidBag with invalid pointer was found");
      }

      VarIntSerializer.write(bytes, delegate.size());
      VarIntSerializer.write(bytes, pointer.fileId());
      VarIntSerializer.write(bytes, pointer.linkBagId());
    }
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
  public static RID readOptimizedLink(DatabaseSessionEmbedded session, final BytesContainer bytes) {
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

  public static void writeOptimizedLink(DatabaseSessionEmbedded session, final BytesContainer bytes,
      Identifiable link) {
    if (link == null) {
      VarIntSerializer.write(bytes, -2);
      VarIntSerializer.write(bytes, -2);
    } else {
      var rid = link.getIdentity();
      if (!rid.isPersistent()) {
        rid = session.refreshRid(rid);
      }

      VarIntSerializer.write(bytes, rid.getCollectionId());
      VarIntSerializer.write(bytes, rid.getCollectionPosition());
    }
  }
}
