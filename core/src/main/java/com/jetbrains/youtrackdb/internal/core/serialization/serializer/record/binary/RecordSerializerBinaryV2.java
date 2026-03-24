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
 * V2 entity serializer using a bucketized cuckoo hash map layout for O(1) property lookup.
 *
 * <p>Binary format:
 * <pre>
 * [class name: varint len + UTF-8 bytes]  (0 len = no class, only for embedded mode)
 * [property count: varint]
 * --- if count &lt;= 12: linear mode ---
 * [for each property: name-encoding + type byte + value-size varint + value-bytes]
 * --- if count &gt; 12: cuckoo hash table mode ---
 * [seed: 4 bytes LE]
 * [log2NumBuckets: 1 byte]
 * [bucket array: numBuckets × 4 slots × 3 bytes]
 *   bucket = [slot0][slot1][slot2][slot3]
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

  // Maximum log2 of bucket count (1024 buckets × 4 slots = 4096 total slots)
  static final int MAX_LOG2_CAPACITY = 10;

  // Maximum offset value for 2-byte offsets (64 KB minus 1, since 0xFFFF is sentinel)
  static final int MAX_KV_REGION_SIZE = 0xFFFE;

  // Threshold for count <= LINEAR_MODE_THRESHOLD: use linear layout instead of hash table.
  // For 3-12 properties, linear scan is cheaper than hash table overhead (seed + buckets).
  // Hash table only pays off for 13+ properties where O(n) scanning becomes measurable.
  static final int LINEAR_MODE_THRESHOLD = 12;

  // -- Bucketized cuckoo hashing constants --

  // Number of slots per bucket in the cuckoo hash table
  static final int BUCKET_SIZE = 4;

  // XOR constant for deriving h2 seed from h1 seed (MurmurHash3 finalization constant)
  static final int CUCKOO_XOR_CONSTANT = 0x85ebca6b;

  // Maximum evictions in a single cuckoo displacement chain before declaring failure
  static final int MAX_EVICTIONS = 500;

  // Maximum seed retries before capacity doubling
  static final int MAX_SEED_RETRIES = 10;

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
  // Bucketized cuckoo hashing — construction utilities
  // ========================================================================================

  /**
   * Derives the h2 seed from the h1 seed by XOR with a MurmurHash3 finalization constant. This
   * ensures h1 and h2 produce independent hash values for cuckoo's two-bucket scheme.
   */
  static int computeH2Seed(int seed) {
    return seed ^ CUCKOO_XOR_CONSTANT;
  }

  /**
   * Computes log2 of the number of buckets for a given property count at ~85% target load factor.
   * Formula: nextPowerOfTwo(ceil(N / (BUCKET_SIZE * 0.85))), minimum 1 bucket (log2 = 0).
   *
   * <p>The total slot count is {@code (1 << log2NumBuckets) * BUCKET_SIZE}.
   */
  static int computeLog2NumBuckets(int propertyCount) {
    assert propertyCount > 0 : "propertyCount must be positive: " + propertyCount;

    // ceil(N / (4 * 0.85)) = ceil(N / 3.4) = ceil(N * 10 / 34) = (N * 10 + 33) / 34
    // Use long arithmetic to avoid overflow for very large property counts
    int minBuckets = (int) (((long) propertyCount * 10 + 33) / 34);
    if (minBuckets <= 1) {
      return 0;
    }
    int log2 = 32 - Integer.numberOfLeadingZeros(minBuckets - 1);
    return Math.min(log2, MAX_LOG2_CAPACITY);
  }

  /**
   * Builds a cuckoo hash table for the given property name bytes. Returns a result containing the
   * seed used, log2 of the bucket count, and the bucket array bytes.
   *
   * <p>Algorithm: greedy placement with displacement chains. For each property, try bucket1
   * (from h1), then bucket2 (from h2). If both are full, evict a slot from bucket1 (round-robin
   * by eviction count) and re-place it. Repeat for up to {@link #MAX_EVICTIONS} evictions. If the
   * chain exceeds the limit, increment the seed and retry (up to {@link #MAX_SEED_RETRIES}). If
   * all seeds fail, double the bucket count and retry.
   *
   * @param propertyNameBytes array of UTF-8 encoded property names
   * @param log2NumBuckets initial log2 of bucket count
   * @return CuckooTableResult containing seed, final log2NumBuckets, and bucket array
   * @throws IllegalStateException if construction fails even at maximum capacity
   */
  static CuckooTableResult buildCuckooTable(byte[][] propertyNameBytes, int log2NumBuckets) {
    assert propertyNameBytes != null : "propertyNameBytes must not be null";
    assert propertyNameBytes.length > 0 : "must have at least one property";
    assert log2NumBuckets >= 0 && log2NumBuckets <= MAX_LOG2_CAPACITY
        : "log2NumBuckets out of range: " + log2NumBuckets;

    int n = propertyNameBytes.length;
    int currentLog2 = log2NumBuckets;

    while (currentLog2 <= MAX_LOG2_CAPACITY) {
      int numBuckets = 1 << currentLog2;
      int totalSlots = numBuckets * BUCKET_SIZE;

      // Allocate working arrays once per capacity level, reuse across seed retries
      byte[] slotHash8 = new byte[totalSlots];
      int[] slotPropertyIndex = new int[totalSlots];
      int[] propBucket1 = new int[n];
      int[] propBucket2 = new int[n];
      int[] propH1 = new int[n];

      for (int seed = 0; seed < MAX_SEED_RETRIES; seed++) {
        int h2Seed = computeH2Seed(seed);

        // Reset slot tracking arrays for this seed attempt
        Arrays.fill(slotHash8, EMPTY_HASH8);
        Arrays.fill(slotPropertyIndex, -1);

        // Precompute bucket1 and bucket2 for displacement chain lookups
        for (int i = 0; i < n; i++) {
          byte[] nameBytes = propertyNameBytes[i];
          int h1 = MurmurHash3.hash32WithSeed(nameBytes, 0, nameBytes.length, seed);
          int h2 = MurmurHash3.hash32WithSeed(nameBytes, 0, nameBytes.length, h2Seed);
          propH1[i] = h1;
          propBucket1[i] = fibonacciBucketIndex(h1, currentLog2);
          propBucket2[i] = fibonacciBucketIndex(h2, currentLog2);
        }

        boolean success = true;
        for (int i = 0; i < n; i++) {
          if (!cuckooInsert(i, propBucket1, propBucket2, propH1, slotHash8,
              slotPropertyIndex)) {
            success = false;
            break;
          }
        }

        if (success) {
          // Build the byte array in a single pass: for each slot, write hash8 + offset sentinel.
          // Occupied slots get actual hash8; empty slots get EMPTY_HASH8. All offsets are
          // initialized to EMPTY_OFFSET and will be backpatched during serializeHashTableMode.
          byte[] bucketArray = new byte[totalSlots * SLOT_SIZE];
          for (int s = 0; s < totalSlots; s++) {
            int byteOffset = s * SLOT_SIZE;
            if (slotPropertyIndex[s] != -1) {
              bucketArray[byteOffset] = slotHash8[s];
            } else {
              bucketArray[byteOffset] = EMPTY_HASH8;
            }
            bucketArray[byteOffset + 1] = (byte) (EMPTY_OFFSET & 0xFF);
            bucketArray[byteOffset + 2] = (byte) ((EMPTY_OFFSET >>> 8) & 0xFF);
          }
          assert bucketArray.length == totalSlots * SLOT_SIZE
              : "bucketArray size mismatch: " + bucketArray.length
                  + " vs " + (totalSlots * SLOT_SIZE);
          return new CuckooTableResult(seed, currentLog2, bucketArray, slotPropertyIndex);
        }
      }
      // All seeds exhausted at this bucket count — double capacity
      currentLog2++;
    }

    throw new IllegalStateException(
        "Failed to build cuckoo table for " + n + " properties at max capacity "
            + ((1 << MAX_LOG2_CAPACITY) * BUCKET_SIZE) + " slots");
  }

  /**
   * Computes a bucket index via Fibonacci hashing. Multiplies the hash by the golden ratio
   * constant and right-shifts to produce an index in [0, numBuckets).
   */
  static int fibonacciBucketIndex(int hash, int log2NumBuckets) {
    if (log2NumBuckets == 0) {
      return 0; // Single bucket — all items go to bucket 0
    }
    return (hash * FIBONACCI_CONSTANT) >>> (32 - log2NumBuckets);
  }

  /**
   * Attempts to insert property at {@code propIdx} into the cuckoo table. Uses greedy placement
   * with displacement chains: try bucket1, then bucket2, then evict a slot from bucket1
   * (round-robin by eviction count to avoid ping-pong cycles) and re-place the evicted item.
   *
   * @return true if insertion succeeded, false if displacement chain exceeded MAX_EVICTIONS
   */
  private static boolean cuckooInsert(int propIdx, int[] propBucket1, int[] propBucket2,
      int[] propH1, byte[] slotHash8, int[] slotPropertyIndex) {

    int currentPropIdx = propIdx;
    for (int evictions = 0; evictions <= MAX_EVICTIONS; evictions++) {
      int bucket1 = propBucket1[currentPropIdx];
      int bucket1Start = bucket1 * BUCKET_SIZE;

      // Try bucket1
      for (int s = 0; s < BUCKET_SIZE; s++) {
        int slotIdx = bucket1Start + s;
        if (slotHash8[slotIdx] == EMPTY_HASH8 && slotPropertyIndex[slotIdx] == -1) {
          slotHash8[slotIdx] = computeHash8(propH1[currentPropIdx]);
          slotPropertyIndex[slotIdx] = currentPropIdx;
          return true;
        }
      }

      int bucket2 = propBucket2[currentPropIdx];
      int bucket2Start = bucket2 * BUCKET_SIZE;

      // Try bucket2
      for (int s = 0; s < BUCKET_SIZE; s++) {
        int slotIdx = bucket2Start + s;
        if (slotHash8[slotIdx] == EMPTY_HASH8 && slotPropertyIndex[slotIdx] == -1) {
          // Item in bucket2 uses h1's hash8 (the primary hash prefix, always stored regardless
          // of which bucket the item lands in)
          slotHash8[slotIdx] = computeHash8(propH1[currentPropIdx]);
          slotPropertyIndex[slotIdx] = currentPropIdx;
          return true;
        }
      }

      // Both buckets full — evict from bucket1 using round-robin slot selection to avoid
      // ping-pong cycles that would occur with always-evict-first-slot strategy
      int evictSlotIdx = bucket1Start + (evictions % BUCKET_SIZE);
      int evictedPropIdx = slotPropertyIndex[evictSlotIdx];

      // Place current item in the evicted slot
      slotHash8[evictSlotIdx] = computeHash8(propH1[currentPropIdx]);
      slotPropertyIndex[evictSlotIdx] = currentPropIdx;

      // The evicted item needs to be re-placed in its alternate bucket
      currentPropIdx = evictedPropIdx;
    }

    // Displacement chain exceeded limit
    return false;
  }

  /**
   * Result of cuckoo table construction, containing the seed, bucket layout, and slot-to-property
   * mapping for backpatching offsets during serialization.
   *
   * <p>Note: offset fields in {@code bucketArray} are initialized to the empty sentinel (0xFFFF)
   * during construction. Use {@code slotPropertyIndex} to determine slot occupancy. Offsets must
   * be backpatched during serialization with actual KV region offsets.
   */
  static final class CuckooTableResult {
    final int seed;
    final int log2NumBuckets;
    final byte[] bucketArray; // numBuckets * BUCKET_SIZE * SLOT_SIZE bytes
    final int[] slotPropertyIndex; // maps each slot to property index (-1 if empty)

    CuckooTableResult(int seed, int log2NumBuckets, byte[] bucketArray, int[] slotPropertyIndex) {
      this.seed = seed;
      this.log2NumBuckets = log2NumBuckets;
      this.bucketArray = bucketArray;
      this.slotPropertyIndex = slotPropertyIndex;
    }
  }

  // ========================================================================================
  // Serialization (Step 2)
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

    if (propertyCount <= LINEAR_MODE_THRESHOLD) {
      serializeLinearMode(session, bytes, fields, props, clazz, schema, encryption);
    } else {
      serializeHashTableMode(session, bytes, fields, props, clazz, schema, encryption,
          propertyCount);
    }
  }

  /**
   * Linear mode: write entries sequentially (for <= 12 properties). Each entry is:
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
   * Cuckoo hash table mode: build cuckoo table, write seed + bucket array + KV entries.
   * Uses slotPropertyIndex from CuckooTableResult to backpatch slot offsets after writing
   * KV entries.
   */
  private void serializeHashTableMode(DatabaseSessionEmbedded session, BytesContainer bytes,
      Set<Entry<String, EntityEntry>> fields, Map<String, SchemaProperty> props,
      SchemaClass oClass, ImmutableSchema schema, PropertyEncryption encryption,
      int propertyCount) {

    // Collect property names as UTF-8 bytes for cuckoo table construction
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

    // Build cuckoo hash table
    int log2NumBuckets = computeLog2NumBuckets(propertyCount);
    CuckooTableResult cuckoo = buildCuckooTable(nameBytes, log2NumBuckets);
    int finalLog2 = cuckoo.log2NumBuckets;
    int numBuckets = 1 << finalLog2;
    int totalSlots = numBuckets * BUCKET_SIZE;

    // Write seed (4 bytes LE)
    int seedPos = bytes.alloc(IntegerSerializer.INT_SIZE);
    IntegerSerializer.serializeLiteral(cuckoo.seed, bytes.bytes, seedPos);

    // Write log2NumBuckets (1 byte)
    int log2Pos = bytes.alloc(1);
    bytes.bytes[log2Pos] = (byte) finalLog2;

    // Write bucket array (numBuckets * BUCKET_SIZE * SLOT_SIZE bytes), initially from
    // CuckooTableResult (hash8 set, offsets = 0xFFFF sentinel to be backpatched)
    int slotArrayPos = bytes.alloc(totalSlots * SLOT_SIZE);
    System.arraycopy(cuckoo.bucketArray, 0, bytes.bytes, slotArrayPos, cuckoo.bucketArray.length);

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
      serializePropertyEntry(session, bytes, orderedFields[i], props, oClass, schema, encryption);
    }

    // Backpatch slot offsets using slotPropertyIndex mapping
    for (int s = 0; s < totalSlots; s++) {
      int propIdx = cuckoo.slotPropertyIndex[s];
      if (propIdx != -1) {
        int slotBytePos = slotArrayPos + s * SLOT_SIZE;
        int entryOffset = propertyKvOffsets[propIdx];
        // hash8 is already written by CuckooTableResult; write offset (2 bytes LE)
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

    // Write value-size varint + value-bytes.
    // Uses a temporary buffer to measure the serialized value length before writing the
    // varint size prefix. A reserve-and-backpatch approach could avoid this allocation
    // but would require adding writeAt/signedSize methods to VarIntSerializer.
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
   * Cuckoo hash table mode full deserialization: skip hash table, read KV entries linearly.
   */
  private void deserializeHashTableModeFull(DatabaseSessionEmbedded db, EntityImpl entity,
      BytesContainer bytes, int propertyCount) {
    // Skip seed (4 bytes)
    bytes.skip(IntegerSerializer.INT_SIZE);
    // Read and validate log2NumBuckets
    int log2NumBuckets = readAndValidateLog2NumBuckets(bytes);
    int numBuckets = 1 << log2NumBuckets;
    // Skip bucket array: numBuckets * BUCKET_SIZE slots, each SLOT_SIZE bytes
    bytes.skip(numBuckets * BUCKET_SIZE * SLOT_SIZE);

    // Read KV entries linearly (full deserialization doesn't need the hash table)
    for (int i = 0; i < propertyCount; i++) {
      deserializeEntry(db, entity, bytes);
    }
  }

  /**
   * Reads a single KV entry: [name-encoding][type byte][value-size varint][value-bytes].
   * The value-size field is used by partial deserialization (Step 3) to skip entries; during
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
  // Partial deserialization, field lookup, field names (Step 3)
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
   * Partial deserialization in cuckoo hash table mode: O(1) lookup per field via 2-bucket scan.
   * For each field, compute h1 and scan bucket1 (4 slots with hash8 fast-reject). If not found,
   * compute h2 and scan bucket2.
   */
  private void deserializePartialHashTable(DatabaseSessionEmbedded db, EntityImpl entity,
      BytesContainer bytes, String[] iFields) {
    // Read hash table header
    int seed = IntegerSerializer.deserializeLiteral(bytes.bytes, bytes.offset);
    bytes.skip(IntegerSerializer.INT_SIZE);
    int log2NumBuckets = readAndValidateLog2NumBuckets(bytes);
    int numBuckets = 1 << log2NumBuckets;
    int slotArrayStart = bytes.offset;
    int kvRegionBase = slotArrayStart + numBuckets * BUCKET_SIZE * SLOT_SIZE;

    int h2Seed = computeH2Seed(seed);

    for (String fieldName : iFields) {
      byte[] nameBytes = fieldName.getBytes(StandardCharsets.UTF_8);
      int h1 = MurmurHash3.hash32WithSeed(nameBytes, 0, nameBytes.length, seed);
      byte expectedHash8 = computeHash8(h1);

      // Scan bucket1 (from h1)
      int bucket1 = fibonacciBucketIndex(h1, log2NumBuckets);
      if (scanBucketForPartialDeserialize(db, entity, bytes, fieldName, expectedHash8,
          slotArrayStart, kvRegionBase, bucket1)) {
        continue; // Found in bucket1
      }

      // Scan bucket2 (from h2) — only computed on bucket1 miss
      int h2 = MurmurHash3.hash32WithSeed(nameBytes, 0, nameBytes.length, h2Seed);
      int bucket2 = fibonacciBucketIndex(h2, log2NumBuckets);
      scanBucketForPartialDeserialize(db, entity, bytes, fieldName, expectedHash8,
          slotArrayStart, kvRegionBase, bucket2);
    }
  }

  /**
   * Scans a single bucket (4 slots) looking for the given field. Returns true if found and
   * deserialized, false if not found in this bucket.
   */
  private boolean scanBucketForPartialDeserialize(DatabaseSessionEmbedded db, EntityImpl entity,
      BytesContainer bytes, String fieldName, byte expectedHash8,
      int slotArrayStart, int kvRegionBase, int bucketIndex) {
    int bucketStart = slotArrayStart + bucketIndex * BUCKET_SIZE * SLOT_SIZE;

    for (int s = 0; s < BUCKET_SIZE; s++) {
      int slotPos = bucketStart + s * SLOT_SIZE;
      byte slotHash8 = bytes.bytes[slotPos];
      int slotOffset =
          (bytes.bytes[slotPos + 1] & 0xFF) | ((bytes.bytes[slotPos + 2] & 0xFF) << 8);

      // Skip empty slots
      if (slotHash8 == EMPTY_HASH8 && slotOffset == (EMPTY_OFFSET & 0xFFFF)) {
        continue;
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
      return true;
    }
    return false;
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
   * Field lookup in cuckoo hash table mode: scan up to 2 buckets via h1/h2.
   */
  @Nullable private BinaryField deserializeFieldHashTable(BytesContainer bytes,
      SchemaClass iClass, String iFieldName, ImmutableSchema schema) {
    // Read hash table header
    int seed = IntegerSerializer.deserializeLiteral(bytes.bytes, bytes.offset);
    bytes.skip(IntegerSerializer.INT_SIZE);
    int log2NumBuckets = readAndValidateLog2NumBuckets(bytes);
    int numBuckets = 1 << log2NumBuckets;
    int slotArrayStart = bytes.offset;
    int kvRegionBase = slotArrayStart + numBuckets * BUCKET_SIZE * SLOT_SIZE;

    byte[] fieldNameBytes = iFieldName.getBytes(StandardCharsets.UTF_8);
    int h1 = MurmurHash3.hash32WithSeed(fieldNameBytes, 0, fieldNameBytes.length, seed);
    byte expectedHash8 = computeHash8(h1);

    // Scan bucket1 (from h1)
    int bucket1 = fibonacciBucketIndex(h1, log2NumBuckets);
    BinaryField result = scanBucketForFieldDeserialize(bytes, iClass, iFieldName, schema,
        expectedHash8, slotArrayStart, kvRegionBase, bucket1);
    if (result != null) {
      return result;
    }

    // Scan bucket2 (from h2)
    int h2Seed = computeH2Seed(seed);
    int h2 = MurmurHash3.hash32WithSeed(fieldNameBytes, 0, fieldNameBytes.length, h2Seed);
    int bucket2 = fibonacciBucketIndex(h2, log2NumBuckets);
    return scanBucketForFieldDeserialize(bytes, iClass, iFieldName, schema,
        expectedHash8, slotArrayStart, kvRegionBase, bucket2);
  }

  /**
   * Scans a single bucket (4 slots) looking for the given field for binary field extraction.
   * Returns a BinaryField if found, null otherwise.
   */
  @Nullable private BinaryField scanBucketForFieldDeserialize(BytesContainer bytes,
      SchemaClass iClass, String iFieldName, ImmutableSchema schema, byte expectedHash8,
      int slotArrayStart, int kvRegionBase, int bucketIndex) {
    int bucketStart = slotArrayStart + bucketIndex * BUCKET_SIZE * SLOT_SIZE;

    for (int s = 0; s < BUCKET_SIZE; s++) {
      int slotPos = bucketStart + s * SLOT_SIZE;
      byte slotHash8 = bytes.bytes[slotPos];
      int slotOffset =
          (bytes.bytes[slotPos + 1] & 0xFF) | ((bytes.bytes[slotPos + 2] & 0xFF) << 8);

      if (slotHash8 == EMPTY_HASH8 && slotOffset == (EMPTY_OFFSET & 0xFFFF)) {
        continue;
      }

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
   * Field names in cuckoo hash table mode: skip to KV entries, read names, skip values.
   */
  private String[] getFieldNamesHashTable(DatabaseSessionEmbedded session, EntityImpl reference,
      BytesContainer bytes, int propertyCount) {
    // Skip seed (4 bytes)
    bytes.skip(IntegerSerializer.INT_SIZE);
    // Read and validate log2NumBuckets, skip bucket array
    int log2NumBuckets = readAndValidateLog2NumBuckets(bytes);
    int numBuckets = 1 << log2NumBuckets;
    bytes.skip(numBuckets * BUCKET_SIZE * SLOT_SIZE);

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
    // Upper bound: MAX_LOG2_CAPACITY=10 → max 1024 buckets. With 64 KB KV region, this is
    // a generous sanity limit for property count.
    if (propertyCount > (1 << MAX_LOG2_CAPACITY)) {
      throw new SerializationException(
          "Corrupted record: property count " + propertyCount + " exceeds maximum "
              + (1 << MAX_LOG2_CAPACITY));
    }
  }

  /**
   * Validates that log2Capacity read from a serialized record is within the supported range.
   * A corrupted byte could produce a value like 30 or 255, causing massive memory allocation or
   * integer overflow in capacity computation.
   */
  private static int readAndValidateLog2NumBuckets(BytesContainer bytes) {
    int log2NumBuckets = bytes.bytes[bytes.offset++] & 0xFF;
    if (log2NumBuckets > MAX_LOG2_CAPACITY) {
      throw new SerializationException(
          "Corrupted record: invalid log2NumBuckets " + log2NumBuckets
              + " (expected 0-" + MAX_LOG2_CAPACITY + ")");
    }
    return log2NumBuckets;
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
