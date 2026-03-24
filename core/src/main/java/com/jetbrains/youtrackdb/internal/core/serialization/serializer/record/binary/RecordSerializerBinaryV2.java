package com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary;

import static com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary.HelperClasses.getGlobalProperty;
import static com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary.HelperClasses.getLinkedType;
import static com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary.HelperClasses.getTypeFromValueEmbedded;
import static com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary.HelperClasses.readString;
import static com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary.HelperClasses.stringFromBytesIntern;
import static com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary.HelperClasses.writeOType;
import static com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary.HelperClasses.writeString;

import com.jetbrains.youtrackdb.internal.common.hash.MurmurHash3;
import com.jetbrains.youtrackdb.internal.common.serialization.types.IntegerSerializer;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.RecordElement;
import com.jetbrains.youtrackdb.internal.core.exception.SerializationException;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.ImmutableSchema;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaImmutableClass;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaProperty;
import com.jetbrains.youtrackdb.internal.core.metadata.security.PropertyEncryption;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityEntry;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * V2 entity serializer using an open-addressing perfect hash map layout for O(1) property lookup.
 *
 * <p>Binary format:
 * <pre>
 * [class name: varint len + UTF-8 bytes]  (0 len = no class, only for embedded mode)
 * [property count: varint]
 * --- if count &lt;= 2: linear mode ---
 * [for each property: name-encoding + type byte + value-size varint + value-bytes]
 * --- if count &gt; 2: hash table mode ---
 * [hash seed: 4 bytes LE]
 * [log2Capacity: 1 byte]
 * [slot array: capacity × 3 bytes]
 *   slot = [hash8: 1 byte][offset: 2 bytes LE]
 *   empty = [0xFF][0xFFFF]
 * [key-value entries packed sequentially]
 *   entry = [name-encoding][type byte][value-size varint][value-bytes]
 * </pre>
 */
public class RecordSerializerBinaryV2 implements EntitySerializer {

  // -- Sentinel for empty hash table slots --
  static final byte EMPTY_HASH8 = (byte) 0xFF;
  static final short EMPTY_OFFSET = (short) 0xFFFF;

  // Fibonacci hashing constant: golden ratio * 2^32, truncated to 32 bits
  private static final int FIBONACCI_CONSTANT = 0x9E3779B9;

  // Slot size in bytes: 1 byte hash8 + 2 bytes offset
  static final int SLOT_SIZE = 3;

  // Maximum seed search attempts per capacity level before doubling
  static final int MAX_SEED_ATTEMPTS = 10_000;

  // Maximum log2 capacity (1024 slots)
  static final int MAX_LOG2_CAPACITY = 10;

  // Minimum log2 capacity (4 slots)
  static final int MIN_LOG2_CAPACITY = 2;

  // Maximum offset value for 2-byte offsets (64 KB minus 1, since 0xFFFF is sentinel)
  static final int MAX_KV_REGION_SIZE = 0xFFFE;

  // Threshold for count <= LINEAR_MODE_THRESHOLD: use linear layout instead of hash table
  static final int LINEAR_MODE_THRESHOLD = 2;

  // Threshold above which we use 4x capacity instead of 2x
  static final int HIGH_CAPACITY_THRESHOLD = 40;

  // V1 serializer for delegating serializeValue/deserializeValue
  private final RecordSerializerBinaryV1 v1Delegate = new RecordSerializerBinaryV1();

  private final BinaryComparatorV0 comparator = new BinaryComparatorV0();

  // ========================================================================================
  // Hash table core utilities (Step 1)
  // ========================================================================================

  /**
   * Computes a slot index via Fibonacci hashing. This is the ONLY index computation formula used
   * everywhere: serialization, deserialization, and comparator.
   */
  static int fibonacciIndex(int hash, int log2Capacity) {
    assert log2Capacity >= MIN_LOG2_CAPACITY && log2Capacity <= MAX_LOG2_CAPACITY
        : "log2Capacity out of range: " + log2Capacity;
    return (hash * FIBONACCI_CONSTANT) >>> (32 - log2Capacity);
  }

  /**
   * Computes the log2 of the hash table capacity for a given property count.
   */
  static int computeLog2Capacity(int propertyCount) {
    assert propertyCount > 0 : "propertyCount must be positive: " + propertyCount;

    int multiplier = propertyCount > HIGH_CAPACITY_THRESHOLD ? 4 : 2;
    int minCapacity = propertyCount * multiplier;

    int log2 = 32 - Integer.numberOfLeadingZeros(minCapacity - 1);
    return Math.max(MIN_LOG2_CAPACITY, Math.min(log2, MAX_LOG2_CAPACITY));
  }

  /**
   * Finds a perfect hash seed such that all property names map to distinct slots.
   */
  static int[] findPerfectHashSeed(byte[][] propertyNameBytes, int log2Capacity) {
    assert propertyNameBytes != null : "propertyNameBytes must not be null";
    assert propertyNameBytes.length > 0 : "must have at least one property";
    assert log2Capacity >= MIN_LOG2_CAPACITY && log2Capacity <= MAX_LOG2_CAPACITY
        : "log2Capacity out of range: " + log2Capacity;

    int n = propertyNameBytes.length;
    int currentLog2 = log2Capacity;

    while (currentLog2 <= MAX_LOG2_CAPACITY) {
      int capacity = 1 << currentLog2;
      boolean[] occupied = new boolean[capacity];

      for (int seed = 0; seed < MAX_SEED_ATTEMPTS; seed++) {
        Arrays.fill(occupied, false);
        boolean collision = false;

        for (int i = 0; i < n; i++) {
          byte[] nameBytes = propertyNameBytes[i];
          int hash = MurmurHash3.hash32WithSeed(nameBytes, 0, nameBytes.length, seed);
          int slot = fibonacciIndex(hash, currentLog2);

          if (occupied[slot]) {
            collision = true;
            break;
          }
          occupied[slot] = true;
        }

        if (!collision) {
          return new int[] {seed, currentLog2};
        }
      }

      currentLog2++;
    }

    throw new IllegalStateException(
        "Failed to find perfect hash seed for " + n + " properties within max capacity "
            + (1 << MAX_LOG2_CAPACITY));
  }

  /**
   * Extracts the high 8 bits of a hash value for the slot hash8 prefix.
   */
  static byte computeHash8(int hash) {
    return (byte) (hash >>> 24);
  }

  // ========================================================================================
  // Serialization (Step 2)
  // ========================================================================================

  @Override
  public void serialize(DatabaseSessionEmbedded session, EntityImpl entity,
      BytesContainer bytes) {
    ImmutableSchema schema = null;
    if (entity != null) {
      schema = entity.getImmutableSchema();
    }
    var encryption = entity.propertyEncryption;
    var clazz = entity.getImmutableSchemaClass(session);
    serializeEntity(session, entity, bytes, clazz, schema, encryption);
  }

  /**
   * Serializes an entity with a class name prefix (used for embedded entities).
   */
  public void serializeWithClassName(DatabaseSessionEmbedded session, EntityImpl entity,
      BytesContainer bytes) {
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

    SchemaImmutableClass oClass = null;
    if (entity != null) {
      oClass = entity.getImmutableSchemaClass(session);
    }

    if (propertyCount <= LINEAR_MODE_THRESHOLD) {
      serializeLinearMode(session, bytes, fields, props, oClass, schema, encryption);
    } else {
      serializeHashTableMode(session, bytes, fields, props, oClass, schema, encryption,
          propertyCount);
    }
  }

  /**
   * Linear mode: write entries sequentially (for <= 2 properties). Each entry is:
   * [name-encoding][type byte][value-size varint][value-bytes]
   */
  private void serializeLinearMode(DatabaseSessionEmbedded session, BytesContainer bytes,
      Set<Entry<String, EntityEntry>> fields, Map<String, SchemaProperty> props,
      SchemaClass oClass, ImmutableSchema schema, PropertyEncryption encryption) {
    for (var field : fields) {
      if (!field.getValue().exists()) {
        continue;
      }
      serializePropertyEntry(session, bytes, field, props, oClass, schema, encryption);
    }
  }

  /**
   * Hash table mode: build perfect hash table, write seed + slots + KV entries.
   */
  private void serializeHashTableMode(DatabaseSessionEmbedded session, BytesContainer bytes,
      Set<Entry<String, EntityEntry>> fields, Map<String, SchemaProperty> props,
      SchemaClass oClass, ImmutableSchema schema, PropertyEncryption encryption,
      int propertyCount) {

    // Collect property names as UTF-8 bytes for seed search
    byte[][] nameBytes = new byte[propertyCount][];
    // Parallel arrays to track field entries in order
    @SuppressWarnings("unchecked")
    Entry<String, EntityEntry>[] orderedFields = new Entry[propertyCount];
    int idx = 0;
    for (var field : fields) {
      if (!field.getValue().exists()) {
        continue;
      }
      String fieldName = resolveFieldName(field);
      nameBytes[idx] = fieldName.getBytes(StandardCharsets.UTF_8);
      orderedFields[idx] = field;
      idx++;
    }

    // Find perfect hash seed
    int log2Capacity = computeLog2Capacity(propertyCount);
    int[] seedResult = findPerfectHashSeed(nameBytes, log2Capacity);
    int seed = seedResult[0];
    int finalLog2 = seedResult[1];
    int capacity = 1 << finalLog2;

    // Write seed (4 bytes LE)
    int seedPos = bytes.alloc(IntegerSerializer.INT_SIZE);
    IntegerSerializer.serializeLiteral(seed, bytes.bytes, seedPos);

    // Write log2Capacity (1 byte)
    int log2Pos = bytes.alloc(1);
    bytes.bytes[log2Pos] = (byte) finalLog2;

    // Reserve slot array (capacity * SLOT_SIZE bytes), filled with empty sentinel
    int slotArrayPos = bytes.alloc(capacity * SLOT_SIZE);
    for (int i = 0; i < capacity; i++) {
      int slotOffset = slotArrayPos + i * SLOT_SIZE;
      bytes.bytes[slotOffset] = EMPTY_HASH8;
      bytes.bytes[slotOffset + 1] = (byte) (EMPTY_OFFSET & 0xFF);
      bytes.bytes[slotOffset + 2] = (byte) ((EMPTY_OFFSET >>> 8) & 0xFF);
    }

    // KV region starts here
    int kvRegionBase = bytes.offset;

    // Write KV entries and backpatch slots
    for (int i = 0; i < propertyCount; i++) {
      int entryOffset = bytes.offset - kvRegionBase;

      if (entryOffset > MAX_KV_REGION_SIZE) {
        throw new SerializationException(session.getDatabaseName(),
            "V2 serialization failed: KV region exceeds 64 KB limit ("
                + entryOffset + " bytes)");
      }

      // Write the KV entry
      serializePropertyEntry(session, bytes, orderedFields[i], props, oClass, schema, encryption);

      // Backpatch the hash table slot
      int hash = MurmurHash3.hash32WithSeed(nameBytes[i], 0, nameBytes[i].length, seed);
      int slot = fibonacciIndex(hash, finalLog2);
      byte hash8 = computeHash8(hash);
      int slotOffset = slotArrayPos + slot * SLOT_SIZE;
      bytes.bytes[slotOffset] = hash8;
      bytes.bytes[slotOffset + 1] = (byte) (entryOffset & 0xFF);
      bytes.bytes[slotOffset + 2] = (byte) ((entryOffset >>> 8) & 0xFF);
    }
  }

  /**
   * Serializes a single property entry: [name-encoding][type byte][value-size varint][value-bytes].
   */
  private void serializePropertyEntry(DatabaseSessionEmbedded session, BytesContainer bytes,
      Entry<String, EntityEntry> field, Map<String, SchemaProperty> props,
      SchemaClass oClass, ImmutableSchema schema, PropertyEncryption encryption) {
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
      writeString(bytes, field.getKey());
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

    // Write value-size varint + value-bytes
    if (value != null) {
      var valuesBuffer = new BytesContainer();
      serializeValue(session, valuesBuffer, value, type,
          getLinkedType(session, oClass, type, field.getKey()), schema, encryption);
      int valueLength = valuesBuffer.offset;
      VarIntSerializer.write(bytes, valueLength);
      int dest = bytes.alloc(valueLength);
      System.arraycopy(valuesBuffer.bytes, 0, bytes.bytes, dest, valueLength);
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
  // Deserialization (Step 2)
  // ========================================================================================

  @Override
  public void deserialize(DatabaseSessionEmbedded db, EntityImpl entity, BytesContainer bytes) {
    int propertyCount = VarIntSerializer.readAsInteger(bytes);

    if (propertyCount == 0) {
      return;
    }

    if (propertyCount <= LINEAR_MODE_THRESHOLD) {
      deserializeLinearMode(db, entity, bytes, propertyCount);
    } else {
      deserializeHashTableModeFull(db, entity, bytes, propertyCount);
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
   * Linear mode deserialization: read entries sequentially.
   */
  private void deserializeLinearMode(DatabaseSessionEmbedded db, EntityImpl entity,
      BytesContainer bytes, int propertyCount) {
    for (int i = 0; i < propertyCount; i++) {
      var nameAndType = readNameAndType(db, entity, bytes);
      String fieldName = nameAndType.name;
      PropertyTypeInternal type = nameAndType.type;

      int valueLength = VarIntSerializer.readAsInteger(bytes);
      if (valueLength != 0) {
        var value = deserializeValue(db, bytes, type, entity);
        entity.setDeserializedPropertyInternal(fieldName, value, type);
      } else {
        entity.setDeserializedPropertyInternal(fieldName, null, null);
      }
    }
  }

  /**
   * Hash table mode full deserialization: skip hash table, read KV entries linearly.
   */
  private void deserializeHashTableModeFull(DatabaseSessionEmbedded db, EntityImpl entity,
      BytesContainer bytes, int propertyCount) {
    // Skip seed (4 bytes)
    bytes.skip(IntegerSerializer.INT_SIZE);
    // Read log2Capacity
    int log2Capacity = bytes.bytes[bytes.offset++] & 0xFF;
    int capacity = 1 << log2Capacity;
    // Skip slot array
    bytes.skip(capacity * SLOT_SIZE);

    // Read KV entries linearly (full deserialization doesn't need the hash table)
    for (int i = 0; i < propertyCount; i++) {
      var nameAndType = readNameAndType(db, entity, bytes);
      String fieldName = nameAndType.name;
      PropertyTypeInternal type = nameAndType.type;

      int valueLength = VarIntSerializer.readAsInteger(bytes);
      if (valueLength != 0) {
        var value = deserializeValue(db, bytes, type, entity);
        entity.setDeserializedPropertyInternal(fieldName, value, type);
      } else {
        entity.setDeserializedPropertyInternal(fieldName, null, null);
      }
    }
  }

  // ========================================================================================
  // Partial deserialization, field lookup, field names (Step 3 stubs)
  // ========================================================================================

  @Override
  public void deserializePartial(DatabaseSessionEmbedded db, EntityImpl entity,
      BytesContainer bytes, String[] iFields) {
    throw new UnsupportedOperationException("V2 deserializePartial not yet implemented");
  }

  @Override
  @Nullable public BinaryField deserializeField(DatabaseSessionEmbedded db, BytesContainer bytes,
      SchemaClass iClass, String iFieldName, boolean embedded, ImmutableSchema schema,
      PropertyEncryption encryption) {
    throw new UnsupportedOperationException("V2 deserializeField not yet implemented");
  }

  @Override
  public String[] getFieldNames(DatabaseSessionEmbedded session, EntityImpl reference,
      BytesContainer iBytes, boolean embedded) {
    throw new UnsupportedOperationException("V2 getFieldNames not yet implemented");
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
        if (value instanceof com.jetbrains.youtrackdb.internal.core.serialization.EntitySerializable entitySerializable) {
          var cur = entitySerializable.toEntity(db);
          cur.setProperty(
              com.jetbrains.youtrackdb.internal.core.serialization.EntitySerializable.CLASS_NAME,
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
              java.util.Arrays.asList(
                  com.jetbrains.youtrackdb.internal.common.collection.MultiValue.array(value)),
              linkedType, schema, encryption);
        } else {
          return writeEmbeddedCollection(db, bytes, (java.util.Collection<?>) value,
              linkedType, schema, encryption);
        }
      }
      case EMBEDDEDMAP -> {
        return writeEmbeddedMap(db, bytes, (java.util.Map<Object, Object>) value,
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
    Object value = new com.jetbrains.youtrackdb.internal.core.record.impl.EmbeddedEntityImpl(db);
    deserializeWithClassName(db, (EntityImpl) value, bytes);
    if (((EntityImpl) value).hasProperty(
        com.jetbrains.youtrackdb.internal.core.serialization.EntitySerializable.CLASS_NAME)) {
      String className = ((EntityImpl) value).getProperty(
          com.jetbrains.youtrackdb.internal.core.serialization.EntitySerializable.CLASS_NAME);
      try {
        var clazz = Class.forName(className);
        var newValue =
            (com.jetbrains.youtrackdb.internal.core.serialization.EntitySerializable) clazz
                .newInstance();
        newValue.fromDocument((EntityImpl) value);
        value = newValue;
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    } else {
      var entity = (EntityImpl) value;
      entity.setOwner(owner);
      var rec = (com.jetbrains.youtrackdb.internal.core.record.RecordAbstract) entity;
      rec.unsetDirty();
    }
    return value;
  }

  // ========================================================================================
  // Collection/map serialization/deserialization (V2-aware, calls this.serializeValue)
  // ========================================================================================

  private int writeEmbeddedCollection(DatabaseSessionEmbedded session, BytesContainer bytes,
      java.util.Collection<?> value, PropertyTypeInternal linkedType, ImmutableSchema schema,
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
      java.util.Map<Object, Object> map, ImmutableSchema schema,
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

  @Nullable private com.jetbrains.youtrackdb.internal.core.db.record.EntityEmbeddedSetImpl<?>
      readEmbeddedSet(DatabaseSessionEmbedded db, BytesContainer bytes, RecordElement owner) {
    final var items = VarIntSerializer.readAsInteger(bytes);
    var type = HelperClasses.readOType(bytes, false);
    if (type != null) {
      var found =
          new com.jetbrains.youtrackdb.internal.core.db.record.EntityEmbeddedSetImpl<>(owner);
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

  @Nullable private com.jetbrains.youtrackdb.internal.core.db.record.EntityEmbeddedListImpl<?>
      readEmbeddedList(DatabaseSessionEmbedded db, BytesContainer bytes, RecordElement owner) {
    final var items = VarIntSerializer.readAsInteger(bytes);
    var type = HelperClasses.readOType(bytes, false);
    if (type != null) {
      var found =
          new com.jetbrains.youtrackdb.internal.core.db.record.EntityEmbeddedListImpl<>(owner);
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
        new com.jetbrains.youtrackdb.internal.core.db.record.EntityEmbeddedMapImpl<Object>(owner);
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
   * Helper record for returning field name and type from readNameAndType.
   */
  private record NameAndType(String name, PropertyTypeInternal type) {
  }
}
