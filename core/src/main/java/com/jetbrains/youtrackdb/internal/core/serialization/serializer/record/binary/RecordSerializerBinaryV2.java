package com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary;

import static com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary.HelperClasses.getGlobalProperty;
import static com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary.HelperClasses.getLinkedType;
import static com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary.HelperClasses.getTypeFromValueEmbedded;
import static com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary.HelperClasses.readString;
import static com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary.HelperClasses.stringFromBytesIntern;
import static com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary.HelperClasses.writeOType;
import static com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary.HelperClasses.writeString;

import com.jetbrains.youtrackdb.internal.common.collection.MultiValue;
import com.jetbrains.youtrackdb.internal.common.hash.MurmurHash3;
import com.jetbrains.youtrackdb.internal.common.serialization.types.IntegerSerializer;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.EntityEmbeddedListImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.EntityEmbeddedMapImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.EntityEmbeddedSetImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.RecordElement;
import com.jetbrains.youtrackdb.internal.core.exception.SerializationException;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.ImmutableSchema;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaProperty;
import com.jetbrains.youtrackdb.internal.core.metadata.security.PropertyEncryption;
import com.jetbrains.youtrackdb.internal.core.record.RecordAbstract;
import com.jetbrains.youtrackdb.internal.core.record.impl.EmbeddedEntityImpl;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityEntry;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.serialization.EntitySerializable;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * V2 entity serializer with hash-accelerated linear scan for faster partial deserialization.
 *
 * <p>Each property entry is prefixed with a 4-byte MurmurHash3 hash of the property name.
 * During partial deserialization, the hash is compared first — on mismatch, the entry is
 * skipped without constructing a String or resolving the property ID.
 *
 * <p>Binary format:
 * <pre>
 * [class name: varint len + UTF-8 bytes]  (0 len = no class, only for embedded mode)
 * [property count: varint]
 * [for each property:]
 *   [name hash: 4 bytes — MurmurHash3 of property name UTF-8 bytes with seed 0]
 *   [name-encoding: varint len + UTF-8 (schema-less) or varint (id+1)*-1 (schema-aware)]
 *   [type byte]
 *   [value-size varint]
 *   [value-bytes]
 * </pre>
 */
public class RecordSerializerBinaryV2 implements EntitySerializer {

  // Maximum property count — reasonable upper bound for a single entity.
  static final int MAX_PROPERTY_COUNT = 2048;

  // V1 serializer for delegating serializeValue/deserializeValue
  private final RecordSerializerBinaryV1 v1Delegate = new RecordSerializerBinaryV1();

  private final BinaryComparatorV0 comparator = new BinaryComparatorV0();

  // ========================================================================================
  // Serialization
  // ========================================================================================

  @Override
  public void serialize(DatabaseSessionEmbedded session, EntityImpl entity,
      BytesContainer bytes) {
    var schema = entity.getImmutableSchema();
    var encryption = entity.propertyEncryption;
    var clazz = entity.getImmutableSchemaClass(session);
    serializeEntity(session, entity, bytes, clazz, schema, encryption);
  }

  /**
   * Serializes an entity with a class name prefix (used for embedded entities).
   */
  public void serializeWithClassName(DatabaseSessionEmbedded session, EntityImpl entity,
      BytesContainer bytes) {
    var schema = entity.getImmutableSchema();
    var clazz = entity.getImmutableSchemaClass(session);
    if (clazz != null && entity.isEmbedded()) {
      writeString(bytes, clazz.getName());
    } else {
      writeEmptyString(bytes);
    }
    var encryption = entity.propertyEncryption;
    serializeEntity(session, entity, bytes, clazz, schema, encryption);
  }

  private void serializeEntity(DatabaseSessionEmbedded session, EntityImpl entity,
      BytesContainer bytes, SchemaClass clazz, ImmutableSchema schema,
      PropertyEncryption encryption) {
    final var props = clazz != null ? clazz.getPropertiesMap() : null;
    final Set<Entry<String, EntityEntry>> fields = entity.getRawEntries();

    // Count existing (non-deleted) properties
    int propertyCount = 0;
    for (var field : fields) {
      if (field.getValue().exists()) {
        propertyCount++;
      }
    }

    // Write property count
    VarIntSerializer.write(bytes, propertyCount);

    if (propertyCount == 0) {
      return;
    }

    // Reusable scratch buffer for measuring serialized value lengths.
    // Each nesting level (embedded entities) creates its own tempBuffer,
    // so nested entities do not interfere with the parent's buffer.
    var tempBuffer = new BytesContainer();

    // Hash-accelerated linear scan: for each property, write 4-byte hash prefix then entry
    for (var field : fields) {
      if (!field.getValue().exists()) {
        continue;
      }
      // Compute hash prefix from the property name
      String fieldName = resolveFieldName(field);
      byte[] nameBytes = fieldName.getBytes(StandardCharsets.UTF_8);
      int hash = MurmurHash3.hash32WithSeed(nameBytes, 0, nameBytes.length, 0);
      int hashPos = bytes.alloc(IntegerSerializer.INT_SIZE);
      IntegerSerializer.serializeLiteral(hash, bytes.bytes, hashPos);
      // Write property entry: [name-encoding][type][value-size][value]
      serializePropertyEntry(session, bytes, field, props, clazz, schema, encryption,
          tempBuffer, nameBytes);
    }
  }

  /**
   * Serializes a single property entry: [name-encoding][type byte][value-size varint][value-bytes].
   *
   * @param preEncodedName pre-computed UTF-8 bytes for the field name (from hash table
   *     construction), or null if not available (linear mode). When non-null and the property
   *     is schema-less, the name bytes are written directly, avoiding a second getBytes(UTF_8).
   */
  private void serializePropertyEntry(DatabaseSessionEmbedded session, BytesContainer bytes,
      Entry<String, EntityEntry> field, Map<String, SchemaProperty> props,
      SchemaClass oClass, ImmutableSchema schema, PropertyEncryption encryption,
      BytesContainer tempBuffer, @Nullable byte[] preEncodedName) {
    var docEntry = field.getValue();

    // Resolve schema property if needed
    if (docEntry.property == null && props != null) {
      var prop = props.get(field.getKey());
      if (prop != null && docEntry.type == PropertyTypeInternal.convertFromPublicType(
          prop.getType())) {
        docEntry.property = prop;
      }
    }

    // Write name-encoding
    if (docEntry.property == null) {
      if (preEncodedName != null) {
        // Reuse pre-computed UTF-8 bytes from hash table construction (avoids double encoding)
        assert java.util.Arrays.equals(preEncodedName,
            field.getKey().getBytes(java.nio.charset.StandardCharsets.UTF_8))
            : "preEncodedName does not match field key UTF-8 bytes for: " + field.getKey();
        VarIntSerializer.write(bytes, preEncodedName.length);
        int start = bytes.alloc(preEncodedName.length);
        System.arraycopy(preEncodedName, 0, bytes.bytes, start, preEncodedName.length);
      } else {
        writeString(bytes, field.getKey());
      }
    } else {
      VarIntSerializer.write(bytes, (docEntry.property.getId() + 1) * -1);
    }

    // Determine type
    final PropertyTypeInternal type;
    final var value = docEntry.value;
    if (value != null) {
      type = getFieldType(docEntry);
      if (type == null) {
        throw new SerializationException(session.getDatabaseName(),
            "Impossible serialize value of type " + value.getClass()
                + " with the EntityImpl binary serializer");
      }
    } else {
      type = null;
    }

    // Write type byte (always, both for schema-less and schema-aware)
    var typeOffset = bytes.alloc(1);
    if (type != null) {
      bytes.bytes[typeOffset] = (byte) type.getId();
    } else {
      bytes.bytes[typeOffset] = (byte) -1;
    }

    // Write value-size varint + value-bytes.
    // Reuses the shared tempBuffer to measure the serialized value length before writing
    // the varint size prefix. reset() keeps the backing array, avoiding per-property allocation.
    if (value != null) {
      tempBuffer.reset();
      serializeValue(session, tempBuffer, value, type,
          getLinkedType(session, oClass, type, field.getKey()), schema, encryption);
      int valueLength = tempBuffer.offset;
      VarIntSerializer.write(bytes, valueLength);
      int dest = bytes.alloc(valueLength);
      System.arraycopy(tempBuffer.bytes, 0, bytes.bytes, dest, valueLength);
    } else {
      VarIntSerializer.write(bytes, 0);
    }
  }

  private static String resolveFieldName(Entry<String, EntityEntry> field) {
    var entry = field.getValue();
    if (entry.property != null) {
      return entry.property.getName();
    }
    return field.getKey();
  }

  // ========================================================================================
  // Deserialization
  // ========================================================================================

  @Override
  public void deserialize(DatabaseSessionEmbedded db, EntityImpl entity, BytesContainer bytes) {
    int propertyCount = VarIntSerializer.readAsInteger(bytes);
    validatePropertyCount(propertyCount);

    if (propertyCount == 0) {
      return;
    }

    // Full deserialization: skip hash prefix per entry, then read entry normally
    for (int i = 0; i < propertyCount; i++) {
      bytes.skip(IntegerSerializer.INT_SIZE);
      deserializeEntry(db, entity, bytes);
    }

    entity.clearSource();
  }

  /**
   * Deserializes with a class name prefix (used for embedded entities).
   */
  public void deserializeWithClassName(DatabaseSessionEmbedded db, EntityImpl entity,
      BytesContainer bytes) {
    var className = readString(bytes);
    if (!className.isEmpty()) {
      entity.setClassNameWithoutPropertiesPostProcessing(className);
    }
    deserialize(db, entity, bytes);
  }

  /**
   * Reads a single KV entry: [name-encoding][type byte][value-size varint][value-bytes].
   * The value-size field is used by partial deserialization to skip entries; during
   * full deserialization it serves as a consistency check.
   */
  private void deserializeEntry(DatabaseSessionEmbedded db, EntityImpl entity,
      BytesContainer bytes) {
    var nameAndType = readNameAndType(db, entity, bytes);
    String fieldName = nameAndType.name;
    PropertyTypeInternal type = nameAndType.type;

    int valueLength = VarIntSerializer.readAsInteger(bytes);
    validateValueLength(valueLength, fieldName);

    // Skip properties already present in the entity — they may have been loaded by a prior
    // partial deserialization call and subsequently modified in memory. Overwriting them with
    // the stale serialized data would silently discard in-memory changes. V1 has the same
    // guard in its deserialize loop.
    if (entity.rawContainsProperty(fieldName)) {
      bytes.skip(valueLength);
      return;
    }

    if (valueLength != 0) {
      int before = bytes.offset;
      var value = deserializeValue(db, bytes, type, entity);
      assert bytes.offset - before == valueLength
          : "Value length mismatch for '" + fieldName + "': expected " + valueLength
              + " but consumed " + (bytes.offset - before);
      entity.setDeserializedPropertyInternal(fieldName, value, type);
    } else {
      entity.setDeserializedPropertyInternal(fieldName, null, null);
    }
  }

  // ========================================================================================
  // Partial deserialization, field lookup, field names
  // ========================================================================================

  @Override
  public void deserializePartial(DatabaseSessionEmbedded db, EntityImpl entity,
      BytesContainer bytes, String[] iFields) {
    int propertyCount = VarIntSerializer.readAsInteger(bytes);
    validatePropertyCount(propertyCount);
    if (propertyCount == 0) {
      return;
    }

    // Hash-accelerated partial deserialization: pre-compute hashes for requested fields,
    // then scan entries comparing hash prefix before reading name strings
    int[] fieldHashes = new int[iFields.length];
    for (int fi = 0; fi < iFields.length; fi++) {
      byte[] nameBytes = iFields[fi].getBytes(StandardCharsets.UTF_8);
      fieldHashes[fi] = MurmurHash3.hash32WithSeed(nameBytes, 0, nameBytes.length, 0);
    }

    int found = 0;
    for (int i = 0; i < propertyCount && found < iFields.length; i++) {
      // Read 4-byte hash prefix
      int entryHash = IntegerSerializer.deserializeLiteral(bytes.bytes, bytes.offset);
      bytes.skip(IntegerSerializer.INT_SIZE);

      // Check if this entry's hash matches any requested field
      int matchIndex = -1;
      for (int j = 0; j < iFields.length; j++) {
        if (iFields[j] != null && fieldHashes[j] == entryHash) {
          matchIndex = j;
          break;
        }
      }

      if (matchIndex < 0) {
        // Hash mismatch — skip name, type, and value without constructing strings
        skipNameAndTypeAndValue(bytes);
        continue;
      }

      // Hash match — read name and verify string equality (collision guard)
      var nameAndType = readNameAndType(db, entity, bytes);
      int valueLength = VarIntSerializer.readAsInteger(bytes);

      if (!iFields[matchIndex].equals(nameAndType.name)) {
        // Hash collision — not the field we want, skip value
        bytes.skip(valueLength);
        continue;
      }

      // Field matched — deserialize value
      if (valueLength != 0) {
        var value = deserializeValue(db, bytes, nameAndType.type, entity);
        entity.setDeserializedPropertyInternal(
            nameAndType.name, value, nameAndType.type);
      } else {
        entity.setDeserializedPropertyInternal(nameAndType.name, null, null);
      }
      found++;
    }
  }

  @Override
  @Nullable public BinaryField deserializeField(DatabaseSessionEmbedded db, BytesContainer bytes,
      SchemaClass iClass, String iFieldName, boolean embedded, ImmutableSchema schema,
      PropertyEncryption encryption) {
    if (embedded) {
      // Skip class name bytes
      int classNameLen = VarIntSerializer.readAsInteger(bytes);
      bytes.skip(classNameLen);
    }

    int propertyCount = VarIntSerializer.readAsInteger(bytes);
    validatePropertyCount(propertyCount);
    if (propertyCount == 0) {
      return null;
    }

    // Hash-accelerated field lookup: pre-compute hash, scan with hash-first rejection
    byte[] fieldNameBytes = iFieldName.getBytes(StandardCharsets.UTF_8);
    int targetHash = MurmurHash3.hash32WithSeed(fieldNameBytes, 0, fieldNameBytes.length, 0);

    for (int i = 0; i < propertyCount; i++) {
      int entryHash = IntegerSerializer.deserializeLiteral(bytes.bytes, bytes.offset);
      bytes.skip(IntegerSerializer.INT_SIZE);

      if (entryHash != targetHash) {
        skipNameAndTypeAndValue(bytes);
        continue;
      }

      // Hash match — read name and verify
      var nameAndType = readFieldName(bytes, schema);
      int valueLength = VarIntSerializer.readAsInteger(bytes);

      if (!iFieldName.equals(nameAndType.name)) {
        bytes.skip(valueLength);
        continue;
      }

      if (valueLength == 0 || !getComparator().isBinaryComparable(nameAndType.type)) {
        return null;
      }

      var classProp = iClass != null ? iClass.getProperty(iFieldName) : null;
      return new BinaryField(
          iFieldName, nameAndType.type, bytes,
          classProp != null ? classProp.getCollate() : null);
    }
    return null;
  }

  /**
   * Field lookup in linear mode: scan entries sequentially.
   */
  @Nullable private BinaryField deserializeFieldLinear(BytesContainer bytes, SchemaClass iClass,
      String iFieldName, ImmutableSchema schema, int propertyCount) {
    for (int i = 0; i < propertyCount; i++) {
      var nameAndType = readFieldName(bytes, schema);
      int valueLength = VarIntSerializer.readAsInteger(bytes);

      if (iFieldName.equals(nameAndType.name)) {
        if (valueLength == 0 || !getComparator().isBinaryComparable(nameAndType.type)) {
          return null;
        }
        // bytes is now positioned at the start of the value data
        var classProp = iClass != null ? iClass.getProperty(iFieldName) : null;
        return new BinaryField(
            iFieldName, nameAndType.type, bytes,
            classProp != null ? classProp.getCollate() : null);
      }

      // Skip value bytes
      bytes.skip(valueLength);
    }
    return null;
  }

  @Override
  public String[] getFieldNames(DatabaseSessionEmbedded session, EntityImpl reference,
      BytesContainer bytes, boolean embedded) {
    if (embedded) {
      int classNameLen = VarIntSerializer.readAsInteger(bytes);
      bytes.skip(classNameLen);
    }

    int propertyCount = VarIntSerializer.readAsInteger(bytes);
    validatePropertyCount(propertyCount);
    if (propertyCount == 0) {
      return new String[0];
    }

    // Read field names: skip hash prefix per entry, read name, skip value
    String[] names = new String[propertyCount];
    for (int i = 0; i < propertyCount; i++) {
      bytes.skip(IntegerSerializer.INT_SIZE);
      var nameAndType = readNameAndType(session, reference, bytes);
      names[i] = nameAndType.name;
      int valueLength = VarIntSerializer.readAsInteger(bytes);
      bytes.skip(valueLength);
    }
    return names;
  }

  // ========================================================================================
  // Value serialization/deserialization
  // ========================================================================================

  @Override
  @SuppressWarnings("unchecked")
  public int serializeValue(DatabaseSessionEmbedded db, BytesContainer bytes, Object value,
      PropertyTypeInternal type, PropertyTypeInternal linkedType, ImmutableSchema schema,
      PropertyEncryption encryption) {
    // Types that can recursively contain EMBEDDED entities must use V2's serialization
    // so that nested entities are written in V2 format, not V1.
    switch (type) {
      case EMBEDDED -> {
        var pointer = bytes.offset;
        if (value instanceof EntitySerializable entitySerializable) {
          var cur = entitySerializable.toEntity(db);
          cur.setProperty(
              EntitySerializable.CLASS_NAME,
              value.getClass().getName());
          serializeWithClassName(db, cur, bytes);
        } else {
          serializeWithClassName(db, (EntityImpl) value, bytes);
        }
        return pointer;
      }
      case EMBEDDEDSET, EMBEDDEDLIST -> {
        if (value.getClass().isArray()) {
          return writeEmbeddedCollection(db, bytes,
              Arrays.asList(
                  MultiValue.array(value)),
              linkedType, schema, encryption);
        } else {
          return writeEmbeddedCollection(db, bytes, (Collection<?>) value,
              linkedType, schema, encryption);
        }
      }
      case EMBEDDEDMAP -> {
        return writeEmbeddedMap(db, bytes, (Map<Object, Object>) value,
            schema, encryption);
      }
      default -> {
        // All other types delegate to V1 (value encoding is identical, no recursion)
        return v1Delegate.serializeValue(db, bytes, value, type, linkedType, schema, encryption);
      }
    }
  }

  @Override
  public Object deserializeValue(DatabaseSessionEmbedded db, BytesContainer bytes,
      PropertyTypeInternal type, RecordElement owner) {
    // Types that can recursively contain EMBEDDED entities must use V2's deserialization
    switch (type) {
      case EMBEDDED -> {
        return deserializeEmbeddedAsDocument(db, bytes, owner);
      }
      case EMBEDDEDSET -> {
        return readEmbeddedSet(db, bytes, owner);
      }
      case EMBEDDEDLIST -> {
        return readEmbeddedList(db, bytes, owner);
      }
      case EMBEDDEDMAP -> {
        return readEmbeddedMap(db, bytes, owner);
      }
      default -> {
        // All other types delegate to V1 (value encoding is identical, no recursion)
        return v1Delegate.deserializeValue(db, bytes, type, owner);
      }
    }
  }

  private Object deserializeEmbeddedAsDocument(DatabaseSessionEmbedded db, BytesContainer bytes,
      RecordElement owner) {
    Object value = new EmbeddedEntityImpl(db);
    deserializeWithClassName(db, (EntityImpl) value, bytes);
    if (((EntityImpl) value).hasProperty(
        EntitySerializable.CLASS_NAME)) {
      String className = ((EntityImpl) value).getProperty(
          EntitySerializable.CLASS_NAME);
      try {
        // Inherited from V1: Class.forName + newInstance is a known security debt.
        // The cast to EntitySerializable provides minimal type safety.
        var clazz = Class.forName(className);
        @SuppressWarnings("deprecation")
        var newValue =
            (EntitySerializable) clazz
                .newInstance();
        newValue.fromDocument((EntityImpl) value);
        value = newValue;
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    } else {
      var entity = (EntityImpl) value;
      entity.setOwner(owner);
      var rec = (RecordAbstract) entity;
      rec.unsetDirty();
    }
    return value;
  }

  // ========================================================================================
  // Collection/map serialization/deserialization (V2-aware, calls this.serializeValue)
  // ========================================================================================

  private int writeEmbeddedCollection(DatabaseSessionEmbedded session, BytesContainer bytes,
      Collection<?> value, PropertyTypeInternal linkedType, ImmutableSchema schema,
      PropertyEncryption encryption) {
    final var pos = VarIntSerializer.write(bytes, value.size());
    writeOType(bytes, bytes.alloc(1),
        linkedType != null ? linkedType : PropertyTypeInternal.EMBEDDED);
    for (var itemValue : value) {
      if (itemValue == null) {
        writeOType(bytes, bytes.alloc(1), null);
        continue;
      }
      PropertyTypeInternal itemType;
      if (linkedType == null) {
        itemType = getTypeFromValueEmbedded(itemValue);
      } else {
        itemType = linkedType;
      }
      if (itemType != null) {
        writeOType(bytes, bytes.alloc(1), itemType);
        serializeValue(session, bytes, itemValue, itemType, null, schema, encryption);
      } else {
        throw new SerializationException(session.getDatabaseName(),
            "Impossible serialize value of type " + value.getClass()
                + " with the EntityImpl binary serializer");
      }
    }
    return pos;
  }

  private int writeEmbeddedMap(DatabaseSessionEmbedded session, BytesContainer bytes,
      Map<Object, Object> map, ImmutableSchema schema,
      PropertyEncryption encryption) {
    final var fullPos = VarIntSerializer.write(bytes, map.size());
    for (var entry : map.entrySet()) {
      var keyType = PropertyTypeInternal.STRING;
      writeOType(bytes, bytes.alloc(1), keyType);
      var key = entry.getKey();
      if (key == null) {
        throw new SerializationException(session.getDatabaseName(),
            "Maps with null keys are not supported");
      }
      writeString(bytes, entry.getKey().toString());
      final var val = entry.getValue();
      if (val != null) {
        var valType = getTypeFromValueEmbedded(val);
        if (valType == null) {
          throw new SerializationException(session.getDatabaseName(),
              "Impossible serialize value of type " + val.getClass()
                  + " with the EntityImpl binary serializer");
        }
        writeOType(bytes, bytes.alloc(1), valType);
        serializeValue(session, bytes, val, valType, null, schema, encryption);
      } else {
        var pointer = bytes.alloc(1);
        bytes.bytes[pointer] = -1;
      }
    }
    return fullPos;
  }

  @Nullable private EntityEmbeddedSetImpl<?>
      readEmbeddedSet(DatabaseSessionEmbedded db, BytesContainer bytes, RecordElement owner) {
    final var items = VarIntSerializer.readAsInteger(bytes);
    var type = HelperClasses.readOType(bytes, false);
    if (type != null) {
      var found =
          new EntityEmbeddedSetImpl<>(owner);
      for (var i = 0; i < items; i++) {
        var itemType = HelperClasses.readOType(bytes, false);
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

  @Nullable private EntityEmbeddedListImpl<?>
      readEmbeddedList(DatabaseSessionEmbedded db, BytesContainer bytes, RecordElement owner) {
    final var items = VarIntSerializer.readAsInteger(bytes);
    var type = HelperClasses.readOType(bytes, false);
    if (type != null) {
      var found =
          new EntityEmbeddedListImpl<>(owner);
      for (var i = 0; i < items; i++) {
        var itemType = HelperClasses.readOType(bytes, false);
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

  private Object readEmbeddedMap(DatabaseSessionEmbedded db, BytesContainer bytes,
      RecordElement owner) {
    var size = VarIntSerializer.readAsInteger(bytes);
    final var result =
        new EntityEmbeddedMapImpl<Object>(owner);
    for (var i = 0; i < size; i++) {
      var keyType = HelperClasses.readOType(bytes, false);
      var key = deserializeValue(db, bytes, keyType, result);
      final var valType = HelperClasses.readType(bytes);
      if (valType != null) {
        var val = deserializeValue(db, bytes, valType, result);
        result.putInternal(key.toString(), val);
      } else {
        result.putInternal(key.toString(), null);
      }
    }
    return result;
  }

  @Override
  public BinaryComparator getComparator() {
    return comparator;
  }

  // ========================================================================================
  // Helper methods
  // ========================================================================================

  /**
   * Reads a property name and type from the current position in the byte stream.
   *
   * <p>V2 format: [name-encoding][type byte]. Name-encoding is either a varint length + UTF-8
   * bytes (schema-less) or a negative varint for a global property ID (schema-aware). The type byte
   * always follows the name (unlike V1 where schema-aware properties omit the type byte).
   */
  private static NameAndType readNameAndType(DatabaseSessionEmbedded db, EntityImpl entity,
      BytesContainer bytes) {
    int len = VarIntSerializer.readAsInteger(bytes);
    String fieldName;
    if (len > 0) {
      // Schema-less: inline field name
      fieldName = stringFromBytesIntern(db, bytes.bytes, bytes.offset, len);
      bytes.skip(len);
    } else {
      // Schema-aware: global property ID
      var prop = getGlobalProperty(entity, len);
      fieldName = prop.getName();
    }

    // Type byte (always present in V2)
    PropertyTypeInternal type = HelperClasses.readOType(bytes, false);
    return new NameAndType(fieldName, type);
  }

  /**
   * Reads a property name and type without requiring a session or entity. Used by deserializeField
   * methods that receive an ImmutableSchema instead. Schema-aware properties are resolved via the
   * schema parameter.
   */
  private static NameAndType readFieldName(BytesContainer bytes, ImmutableSchema schema) {
    int len = VarIntSerializer.readAsInteger(bytes);
    String fieldName;
    if (len > 0) {
      fieldName = new String(bytes.bytes, bytes.offset, len, StandardCharsets.UTF_8);
      bytes.skip(len);
    } else {
      var id = (len * -1) - 1;
      var prop = schema.getGlobalPropertyById(id);
      fieldName = prop.getName();
    }
    PropertyTypeInternal type = HelperClasses.readOType(bytes, false);
    return new NameAndType(fieldName, type);
  }

  /**
   * Skips over a property entry's name-encoding, type byte, and value bytes without constructing
   * any objects. Used by hash-accelerated deserialization to skip non-matching entries.
   */
  private static void skipNameAndTypeAndValue(BytesContainer bytes) {
    int len = VarIntSerializer.readAsInteger(bytes);
    if (len > 0) {
      bytes.skip(len); // schema-less: skip UTF-8 name bytes
    }
    // schema-aware (len <= 0): varint itself was the entire encoding
    bytes.skip(1); // type byte
    int valueLength = VarIntSerializer.readAsInteger(bytes);
    bytes.skip(valueLength);
  }

  private static void writeEmptyString(BytesContainer bytes) {
    VarIntSerializer.write(bytes, 0);
  }

  private static PropertyTypeInternal getFieldType(EntityEntry entry) {
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

  /**
   * Validates that propertyCount from a serialized record is non-negative and within a reasonable
   * bound. A corrupted varint could produce negative or extremely large values, causing OOM or
   * silent data loss during deserialization.
   */
  private static void validatePropertyCount(int propertyCount) {
    if (propertyCount < 0) {
      throw new SerializationException(
          "Corrupted record: negative property count " + propertyCount);
    }
    if (propertyCount > MAX_PROPERTY_COUNT) {
      throw new SerializationException(
          "Corrupted record: property count " + propertyCount + " exceeds maximum "
              + MAX_PROPERTY_COUNT);
    }
  }

  /**
   * Validates that a value length is non-negative. A corrupted varint could produce a negative
   * value, causing the buffer offset to move backwards.
   */
  private static void validateValueLength(int valueLength, String fieldName) {
    if (valueLength < 0) {
      throw new SerializationException(
          "Corrupted record: negative value length " + valueLength + " for field '"
              + fieldName + "'");
    }
  }

  /**
   * Helper record for returning field name and type from readNameAndType.
   */
  private record NameAndType(String name, PropertyTypeInternal type) {
  }
}
