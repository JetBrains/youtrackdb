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
 * V2 entity serializer using a linear probing hash map layout for O(1) property lookup.
 *
 * <p>Binary format:
 * <pre>
 * [class name: varint len + UTF-8 bytes]  (0 len = no class, only for embedded mode)
 * [property count: varint]
 * --- if count &lt;= 12: linear mode ---
 * [for each property: name-encoding + type byte + value-size varint + value-bytes]
 * --- if count &gt; 12: linear probing hash table mode ---
 * [seed: 4 bytes LE]
 * [log2Capacity: 1 byte]
 * [slot array: capacity × 3 bytes]
 *   slot = [hash8: 1 byte][offset: 2 bytes LE]
 *   empty slot = [0xFF][0xFFFF]
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

  // Maximum log2 of slot capacity (2048 slots = ~1280 properties at 0.625 load factor)
  static final int MAX_LOG2_CAPACITY = 11;

  // Maximum offset value for 2-byte offsets (64 KB minus 1, since 0xFFFF is sentinel)
  static final int MAX_KV_REGION_SIZE = 0xFFFE;

  // Threshold for count <= LINEAR_MODE_THRESHOLD: use linear layout instead of hash table.
  // For 3-12 properties, linear scan is cheaper than hash table overhead (seed + slots).
  // Hash table only pays off for 13+ properties where O(n) scanning becomes measurable.
  static final int LINEAR_MODE_THRESHOLD = 12;

  // V1 serializer for delegating serializeValue/deserializeValue
  private final RecordSerializerBinaryV1 v1Delegate = new RecordSerializerBinaryV1();

  private final BinaryComparatorV0 comparator = new BinaryComparatorV0();

  // ========================================================================================
  // Hash table core utilities
  // ========================================================================================

  /**
   * Extracts the high 8 bits of a hash value for the slot hash8 prefix.
   */
  static byte computeHash8(int hash) {
    return (byte) (hash >>> 24);
  }

  // ========================================================================================
  // Linear probing hash table — construction utilities
  // ========================================================================================

  /**
   * Computes log2 of the slot capacity for a given property count at ~0.625 (5/8) load factor.
   * Formula: nextPowerOfTwo(ceil(N * 8 / 5)), minimum 2 slots (log2 = 1).
   *
   * <p>The total slot count is {@code 1 << log2Capacity}.
   */
  static int computeLog2Capacity(int propertyCount) {
    assert propertyCount > 0 : "propertyCount must be positive: " + propertyCount;

    // ceil(N * 8 / 5) = (N * 8 + 4) / 5
    // Use long arithmetic to avoid overflow for large property counts
    int minSlots = (int) (((long) propertyCount * 8 + 4) / 5);
    if (minSlots <= 2) {
      return 1; // Minimum 2 slots (log2 = 1)
    }
    int log2 = 32 - Integer.numberOfLeadingZeros(minSlots - 1);
    return Math.min(log2, MAX_LOG2_CAPACITY);
  }

  /**
   * Builds a linear probing hash table for the given property name bytes. Returns a result
   * containing the seed used, log2 of the capacity, and the slot array bytes.
   *
   * <p>Algorithm: for each property, compute hash via MurmurHash3 with a fixed seed (0), compute
   * the slot index via Fibonacci hashing, and scan forward (wrapping) until an empty slot is
   * found. No eviction chains, no seed retries, no dual hash computation.
   *
   * @param propertyNameBytes array of UTF-8 encoded property names
   * @param log2Capacity log2 of the slot capacity (power-of-two)
   * @return HashTableResult containing seed, log2Capacity, slot array, and slot-to-property map
   */
  static HashTableResult buildHashTable(byte[][] propertyNameBytes, int log2Capacity) {
    assert propertyNameBytes != null : "propertyNameBytes must not be null";
    assert propertyNameBytes.length > 0 : "must have at least one property";
    assert log2Capacity >= 1 && log2Capacity <= MAX_LOG2_CAPACITY
        : "log2Capacity out of range: " + log2Capacity;

    int n = propertyNameBytes.length;
    int capacity = 1 << log2Capacity;
    if (n >= capacity) {
      throw new IllegalStateException(
          "property count " + n + " must be < capacity " + capacity
              + " (load factor violation)");
    }
    // Seed is fixed at 0; field preserved in wire format for future extensibility.
    int seed = 0;

    // Allocate slot array and property index in one pass.
    // slotArray stores [hash8, offsetLo, offsetHi] per slot — initialized to empty sentinels.
    // slotPropertyIndex maps each slot to the property index (-1 if empty).
    byte[] slotArray = new byte[capacity * SLOT_SIZE];
    int[] slotPropertyIndex = new int[capacity];
    // Fill slot array with empty sentinels (0xFF for hash8, 0xFFFF for offset)
    for (int s = 0; s < capacity; s++) {
      int base = s * SLOT_SIZE;
      slotArray[base] = EMPTY_HASH8;
      slotArray[base + 1] = (byte) (EMPTY_OFFSET & 0xFF);
      slotArray[base + 2] = (byte) ((EMPTY_OFFSET >>> 8) & 0xFF);
    }
    Arrays.fill(slotPropertyIndex, -1);

    // Insert each property via linear probing — write hash8 directly into slotArray
    for (int i = 0; i < n; i++) {
      byte[] nameBytes = propertyNameBytes[i];
      int hash = MurmurHash3.hash32WithSeed(nameBytes, 0, nameBytes.length, seed);
      byte hash8 = computeHash8(hash);
      int startSlot = fibonacciSlotIndex(hash, log2Capacity);

      // Linear probe forward until an empty slot is found
      boolean placed = false;
      for (int probe = 0; probe < capacity; probe++) {
        int slot = (startSlot + probe) & (capacity - 1);
        if (slotPropertyIndex[slot] == -1) {
          slotArray[slot * SLOT_SIZE] = hash8;
          slotPropertyIndex[slot] = i;
          placed = true;
          break;
        }
      }
      if (!placed) {
        throw new IllegalStateException(
            "Failed to place property " + i + " in " + capacity
                + " slots — table should have empty slots at load factor 0.625");
      }
    }

    assert slotArray.length == capacity * SLOT_SIZE
        : "slotArray size mismatch: " + slotArray.length + " vs " + (capacity * SLOT_SIZE);
    return new HashTableResult(seed, log2Capacity, slotArray, slotPropertyIndex);
  }

  /**
   * Computes a slot index via Fibonacci hashing. Multiplies the hash by the golden ratio
   * constant and right-shifts to produce an index in [0, capacity).
   */
  static int fibonacciSlotIndex(int hash, int log2Capacity) {
    assert log2Capacity >= 1 : "log2Capacity must be >= 1: " + log2Capacity;
    return (hash * FIBONACCI_CONSTANT) >>> (32 - log2Capacity);
  }

  /**
   * Result of hash table construction, containing the seed, slot layout, and slot-to-property
   * mapping for backpatching offsets during serialization.
   *
   * <p>Note: offset fields in {@code slotArray} are initialized to the empty sentinel (0xFFFF)
   * during construction. Use {@code slotPropertyIndex} to determine slot occupancy. Offsets must
   * be backpatched during serialization with actual KV region offsets.
   */
  static final class HashTableResult {
    final int seed;
    final int log2Capacity;
    final byte[] slotArray; // capacity * SLOT_SIZE bytes
    final int[] slotPropertyIndex; // maps each slot to property index (-1 if empty)

    HashTableResult(int seed, int log2Capacity, byte[] slotArray, int[] slotPropertyIndex) {
      this.seed = seed;
      this.log2Capacity = log2Capacity;
      this.slotArray = slotArray;
      this.slotPropertyIndex = slotPropertyIndex;
    }
  }

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

    if (propertyCount <= LINEAR_MODE_THRESHOLD) {
      serializeLinearMode(session, bytes, fields, props, clazz, schema, encryption, tempBuffer);
    } else {
      serializeHashTableMode(session, bytes, fields, props, clazz, schema, encryption,
          propertyCount, tempBuffer);
    }
  }

  /**
   * Linear mode: write entries sequentially (for <= 12 properties). Each entry is:
   * [name-encoding][type byte][value-size varint][value-bytes]
   */
  private void serializeLinearMode(DatabaseSessionEmbedded session, BytesContainer bytes,
      Set<Entry<String, EntityEntry>> fields, Map<String, SchemaProperty> props,
      SchemaClass oClass, ImmutableSchema schema, PropertyEncryption encryption,
      BytesContainer tempBuffer) {
    for (var field : fields) {
      if (!field.getValue().exists()) {
        continue;
      }
      serializePropertyEntry(session, bytes, field, props, oClass, schema, encryption, tempBuffer);
    }
  }

  /**
   * Linear probing hash table mode: build hash table, write seed + slot array + KV entries.
   * Uses slotPropertyIndex from HashTableResult to backpatch slot offsets after writing
   * KV entries.
   */
  private void serializeHashTableMode(DatabaseSessionEmbedded session, BytesContainer bytes,
      Set<Entry<String, EntityEntry>> fields, Map<String, SchemaProperty> props,
      SchemaClass oClass, ImmutableSchema schema, PropertyEncryption encryption,
      int propertyCount, BytesContainer tempBuffer) {

    // Collect property names as UTF-8 bytes for hash table construction
    byte[][] nameBytes = new byte[propertyCount][];
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

    // Build linear probing hash table
    int log2Capacity = computeLog2Capacity(propertyCount);
    HashTableResult table = buildHashTable(nameBytes, log2Capacity);
    int capacity = 1 << table.log2Capacity;

    // Write seed (4 bytes LE)
    int seedPos = bytes.alloc(IntegerSerializer.INT_SIZE);
    IntegerSerializer.serializeLiteral(table.seed, bytes.bytes, seedPos);

    // Write log2Capacity (1 byte)
    int log2Pos = bytes.alloc(1);
    bytes.bytes[log2Pos] = (byte) table.log2Capacity;

    // Write slot array (capacity * SLOT_SIZE bytes), initially from HashTableResult
    // (hash8 set, offsets = 0xFFFF sentinel to be backpatched)
    int slotArrayPos = bytes.alloc(capacity * SLOT_SIZE);
    System.arraycopy(table.slotArray, 0, bytes.bytes, slotArrayPos, table.slotArray.length);

    // KV region starts here
    int kvRegionBase = bytes.offset;

    // Track which KV entry offset belongs to which property index
    int[] propertyKvOffsets = new int[propertyCount];

    // Write KV entries sequentially
    for (int i = 0; i < propertyCount; i++) {
      int entryOffset = bytes.offset - kvRegionBase;

      if (entryOffset > MAX_KV_REGION_SIZE) {
        throw new SerializationException(session.getDatabaseName(),
            "V2 serialization failed: KV region exceeds 64 KB limit ("
                + entryOffset + " bytes)");
      }

      propertyKvOffsets[i] = entryOffset;
      serializePropertyEntry(session, bytes, orderedFields[i], props, oClass, schema, encryption,
          tempBuffer);
    }

    // Backpatch slot offsets using slotPropertyIndex mapping
    for (int s = 0; s < capacity; s++) {
      int propIdx = table.slotPropertyIndex[s];
      if (propIdx != -1) {
        int slotBytePos = slotArrayPos + s * SLOT_SIZE;
        int entryOffset = propertyKvOffsets[propIdx];
        // hash8 is already written by HashTableResult; write offset (2 bytes LE)
        bytes.bytes[slotBytePos + 1] = (byte) (entryOffset & 0xFF);
        bytes.bytes[slotBytePos + 2] = (byte) ((entryOffset >>> 8) & 0xFF);
      }
    }
  }

  /**
   * Serializes a single property entry: [name-encoding][type byte][value-size varint][value-bytes].
   */
  private void serializePropertyEntry(DatabaseSessionEmbedded session, BytesContainer bytes,
      Entry<String, EntityEntry> field, Map<String, SchemaProperty> props,
      SchemaClass oClass, ImmutableSchema schema, PropertyEncryption encryption,
      BytesContainer tempBuffer) {
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
      deserializeEntry(db, entity, bytes);
    }
  }

  /**
   * Hash table mode full deserialization: skip hash table header and slots, read KV entries linearly.
   */
  private void deserializeHashTableModeFull(DatabaseSessionEmbedded db, EntityImpl entity,
      BytesContainer bytes, int propertyCount) {
    // Skip seed (4 bytes)
    bytes.skip(IntegerSerializer.INT_SIZE);
    // Read and validate log2Capacity
    int log2Capacity = readAndValidateLog2Capacity(bytes);
    int capacity = 1 << log2Capacity;
    // Skip slot array: capacity slots, each SLOT_SIZE bytes
    bytes.skip(capacity * SLOT_SIZE);

    // Read KV entries linearly (full deserialization doesn't need the hash table)
    for (int i = 0; i < propertyCount; i++) {
      deserializeEntry(db, entity, bytes);
    }
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

    if (propertyCount <= LINEAR_MODE_THRESHOLD) {
      deserializePartialLinear(db, entity, bytes, iFields, propertyCount);
    } else {
      deserializePartialHashTable(db, entity, bytes, iFields);
    }
  }

  /**
   * Partial deserialization in linear mode: scan entries sequentially, only deserialize matching
   * fields.
   */
  private void deserializePartialLinear(DatabaseSessionEmbedded db, EntityImpl entity,
      BytesContainer bytes, String[] iFields, int propertyCount) {
    int found = 0;
    for (int i = 0; i < propertyCount && found < iFields.length; i++) {
      var nameAndType = readNameAndType(db, entity, bytes);
      int valueLength = VarIntSerializer.readAsInteger(bytes);

      // Check if this entry matches any requested field
      int matchIndex = -1;
      for (int j = 0; j < iFields.length; j++) {
        if (iFields[j] != null && iFields[j].equals(nameAndType.name)) {
          matchIndex = j;
          break;
        }
      }

      if (matchIndex >= 0) {
        if (valueLength != 0) {
          var value = deserializeValue(db, bytes, nameAndType.type, entity);
          entity.setDeserializedPropertyInternal(
              nameAndType.name, value, nameAndType.type);
        } else {
          entity.setDeserializedPropertyInternal(nameAndType.name, null, null);
        }
        found++;
      } else {
        // Skip value bytes
        bytes.skip(valueLength);
      }
    }
  }

  /**
   * Partial deserialization in linear probing hash table mode: O(1) lookup per field via
   * Fibonacci-indexed linear probe with hash8 fast-reject. Empty slot terminates search.
   */
  private void deserializePartialHashTable(DatabaseSessionEmbedded db, EntityImpl entity,
      BytesContainer bytes, String[] iFields) {
    // Read hash table header
    int seed = IntegerSerializer.deserializeLiteral(bytes.bytes, bytes.offset);
    bytes.skip(IntegerSerializer.INT_SIZE);
    int log2Capacity = readAndValidateLog2Capacity(bytes);
    int capacity = 1 << log2Capacity;
    int slotArrayStart = bytes.offset;
    int kvRegionBase = slotArrayStart + capacity * SLOT_SIZE;

    for (String fieldName : iFields) {
      byte[] nameBytes = fieldName.getBytes(StandardCharsets.UTF_8);
      int hash = MurmurHash3.hash32WithSeed(nameBytes, 0, nameBytes.length, seed);
      byte expectedHash8 = computeHash8(hash);
      int startSlot = fibonacciSlotIndex(hash, log2Capacity);

      // Linear probe forward until found or empty slot (defensive bound: probes < capacity)
      for (int probe = 0; probe < capacity; probe++) {
        int slot = (startSlot + probe) & (capacity - 1);
        int slotPos = slotArrayStart + slot * SLOT_SIZE;
        byte slotHash8 = bytes.bytes[slotPos];
        int slotOffset =
            (bytes.bytes[slotPos + 1] & 0xFF) | ((bytes.bytes[slotPos + 2] & 0xFF) << 8);

        // Empty slot: field not present — terminate search
        if (slotHash8 == EMPTY_HASH8 && slotOffset == (EMPTY_OFFSET & 0xFFFF)) {
          break;
        }

        // Hash8 fast-reject
        if (slotHash8 != expectedHash8) {
          continue;
        }

        // Bounds check
        validateSlotOffset(kvRegionBase, slotOffset, bytes.bytes.length);

        // Navigate to KV entry and verify name match
        bytes.offset = kvRegionBase + slotOffset;
        var nameAndType = readNameAndType(db, entity, bytes);
        if (!fieldName.equals(nameAndType.name)) {
          continue;
        }

        // Found — deserialize value
        int valueLength = VarIntSerializer.readAsInteger(bytes);
        if (valueLength != 0) {
          var value = deserializeValue(db, bytes, nameAndType.type, entity);
          entity.setDeserializedPropertyInternal(fieldName, value, nameAndType.type);
        } else {
          entity.setDeserializedPropertyInternal(fieldName, null, null);
        }
        break;
      }
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

    if (propertyCount <= LINEAR_MODE_THRESHOLD) {
      return deserializeFieldLinear(bytes, iClass, iFieldName, schema, propertyCount);
    } else {
      return deserializeFieldHashTable(bytes, iClass, iFieldName, schema);
    }
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

  /**
   * Field lookup in linear probing hash table mode: Fibonacci-indexed linear probe with hash8
   * fast-reject. Empty slot terminates search (field absent).
   */
  @Nullable private BinaryField deserializeFieldHashTable(BytesContainer bytes,
      SchemaClass iClass, String iFieldName, ImmutableSchema schema) {
    // Read hash table header
    int seed = IntegerSerializer.deserializeLiteral(bytes.bytes, bytes.offset);
    bytes.skip(IntegerSerializer.INT_SIZE);
    int log2Capacity = readAndValidateLog2Capacity(bytes);
    int capacity = 1 << log2Capacity;
    int slotArrayStart = bytes.offset;
    int kvRegionBase = slotArrayStart + capacity * SLOT_SIZE;

    byte[] fieldNameBytes = iFieldName.getBytes(StandardCharsets.UTF_8);
    int hash = MurmurHash3.hash32WithSeed(fieldNameBytes, 0, fieldNameBytes.length, seed);
    byte expectedHash8 = computeHash8(hash);
    int startSlot = fibonacciSlotIndex(hash, log2Capacity);

    // Linear probe forward until found or empty slot (defensive bound: probes < capacity)
    for (int probe = 0; probe < capacity; probe++) {
      int slot = (startSlot + probe) & (capacity - 1);
      int slotPos = slotArrayStart + slot * SLOT_SIZE;
      byte slotHash8 = bytes.bytes[slotPos];
      int slotOffset =
          (bytes.bytes[slotPos + 1] & 0xFF) | ((bytes.bytes[slotPos + 2] & 0xFF) << 8);

      // Empty slot: field not present — terminate search
      if (slotHash8 == EMPTY_HASH8 && slotOffset == (EMPTY_OFFSET & 0xFFFF)) {
        return null;
      }

      // Hash8 fast-reject
      if (slotHash8 != expectedHash8) {
        continue;
      }

      validateSlotOffset(kvRegionBase, slotOffset, bytes.bytes.length);
      bytes.offset = kvRegionBase + slotOffset;

      var nameAndType = readFieldName(bytes, schema);
      if (!iFieldName.equals(nameAndType.name)) {
        continue;
      }

      int valueLength = VarIntSerializer.readAsInteger(bytes);
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

    if (propertyCount <= LINEAR_MODE_THRESHOLD) {
      return getFieldNamesLinear(session, reference, bytes, propertyCount);
    } else {
      return getFieldNamesHashTable(session, reference, bytes, propertyCount);
    }
  }

  /**
   * Field names in linear mode: read names sequentially, skip values.
   */
  private String[] getFieldNamesLinear(DatabaseSessionEmbedded session, EntityImpl reference,
      BytesContainer bytes, int propertyCount) {
    String[] names = new String[propertyCount];
    for (int i = 0; i < propertyCount; i++) {
      var nameAndType = readNameAndType(session, reference, bytes);
      names[i] = nameAndType.name;
      // Skip value
      int valueLength = VarIntSerializer.readAsInteger(bytes);
      bytes.skip(valueLength);
    }
    return names;
  }

  /**
   * Field names in linear probing hash table mode: skip to KV entries, read names, skip values.
   */
  private String[] getFieldNamesHashTable(DatabaseSessionEmbedded session, EntityImpl reference,
      BytesContainer bytes, int propertyCount) {
    // Skip seed (4 bytes)
    bytes.skip(IntegerSerializer.INT_SIZE);
    // Read and validate log2Capacity, skip slot array
    int log2Capacity = readAndValidateLog2Capacity(bytes);
    int capacity = 1 << log2Capacity;
    bytes.skip(capacity * SLOT_SIZE);

    // Read names from KV entries sequentially
    String[] names = new String[propertyCount];
    for (int i = 0; i < propertyCount; i++) {
      var nameAndType = readNameAndType(session, reference, bytes);
      names[i] = nameAndType.name;
      // Skip value
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
    // Upper bound: at 0.625 load factor with MAX_LOG2_CAPACITY=11 (2048 slots),
    // the maximum property count is (2048 * 5 / 8) = 1280. Beyond this, the
    // capacity formula clamps at MAX_LOG2_CAPACITY causing severe load factor
    // degradation (e.g., 2047 properties in 2048 slots = 99.95% load).
    int maxProperties = (1 << MAX_LOG2_CAPACITY) * 5 / 8;
    if (propertyCount > maxProperties) {
      throw new SerializationException(
          "Corrupted record: property count " + propertyCount + " exceeds maximum "
              + maxProperties);
    }
  }

  /**
   * Validates that log2Capacity read from a serialized record is within the supported range.
   * A corrupted byte could produce a value like 30 or 255, causing massive memory allocation or
   * integer overflow in capacity computation.
   */
  private static int readAndValidateLog2Capacity(BytesContainer bytes) {
    int log2Capacity = bytes.bytes[bytes.offset++] & 0xFF;
    if (log2Capacity < 1 || log2Capacity > MAX_LOG2_CAPACITY) {
      throw new SerializationException(
          "Corrupted record: invalid log2Capacity " + log2Capacity
              + " (expected 1-" + MAX_LOG2_CAPACITY + ")");
    }
    return log2Capacity;
  }

  /**
   * Validates that a slot offset is within the bounds of the byte buffer. A corrupted offset
   * would cause an out-of-bounds read during deserialization.
   */
  private static void validateSlotOffset(int kvRegionBase, int slotOffset, int bufferLength) {
    if (kvRegionBase + slotOffset >= bufferLength) {
      throw new SerializationException(
          "Corrupted record: slot offset " + slotOffset + " exceeds buffer bounds (kvBase="
              + kvRegionBase + ", bufLen=" + bufferLength + ")");
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
