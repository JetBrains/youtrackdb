package com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary;

import com.jetbrains.youtrackdb.internal.common.hash.MurmurHash3;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.RecordElement;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.ImmutableSchema;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.metadata.security.PropertyEncryption;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.Arrays;
import javax.annotation.Nullable;

/**
 * V2 entity serializer using an open-addressing perfect hash map layout for O(1) property lookup.
 *
 * <p>Binary format:
 * <pre>
 * [class name: varint len + UTF-8 bytes]  (0 len = no class, only for embedded mode)
 * [property count: varint]
 * --- if count &lt;= 2: linear mode (same layout as V1 per-entry) ---
 * [for each property: name-encoding + type + value-size + value-bytes]
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

  // Maximum offset value for 2-byte offsets (64 KB)
  static final int MAX_KV_REGION_SIZE = 0xFFFF;

  // Threshold above which we use 4x capacity instead of 2x
  static final int HIGH_CAPACITY_THRESHOLD = 40;

  private final BinaryComparatorV0 comparator = new BinaryComparatorV0();

  /**
   * Computes a slot index via Fibonacci hashing. This is the ONLY index computation formula used
   * everywhere: serialization, deserialization, and comparator.
   *
   * @param hash         the 32-bit hash value
   * @param log2Capacity log2 of the table capacity (e.g. 4 means 16 slots)
   * @return slot index in [0, capacity)
   */
  static int fibonacciIndex(int hash, int log2Capacity) {
    assert log2Capacity >= MIN_LOG2_CAPACITY && log2Capacity <= MAX_LOG2_CAPACITY
        : "log2Capacity out of range: " + log2Capacity;
    // Unsigned multiply + shift: (hash * FIBONACCI_CONSTANT) >>> (32 - log2Capacity)
    // Java int multiplication gives the low 32 bits, which is correct for Fibonacci hashing.
    return (hash * FIBONACCI_CONSTANT) >>> (32 - log2Capacity);
  }

  /**
   * Computes the log2 of the hash table capacity for a given property count.
   *
   * <p>Capacity = next power of two &gt;= 2*N (or 4*N for N &gt; 40). Min capacity 4 (log2=2),
   * max capacity 1024 (log2=10).
   *
   * @param propertyCount number of properties (must be &gt; 0)
   * @return log2 of capacity
   */
  static int computeLog2Capacity(int propertyCount) {
    assert propertyCount > 0 : "propertyCount must be positive: " + propertyCount;

    int multiplier = propertyCount > HIGH_CAPACITY_THRESHOLD ? 4 : 2;
    int minCapacity = propertyCount * multiplier;

    // Next power of two >= minCapacity
    int log2 = 32 - Integer.numberOfLeadingZeros(minCapacity - 1);
    // Clamp to [MIN_LOG2_CAPACITY, MAX_LOG2_CAPACITY]
    return Math.max(MIN_LOG2_CAPACITY, Math.min(log2, MAX_LOG2_CAPACITY));
  }

  /**
   * Finds a perfect hash seed such that all property names map to distinct slots via Fibonacci
   * hashing.
   *
   * @param propertyNameBytes array of UTF-8 encoded property names
   * @param log2Capacity      initial log2 capacity
   * @return a two-element array: [seed, finalLog2Capacity]
   * @throws IllegalStateException if no seed found within MAX_LOG2_CAPACITY
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

      // Exhausted attempts at this capacity, double it
      currentLog2++;
    }

    throw new IllegalStateException(
        "Failed to find perfect hash seed for " + n + " properties within max capacity "
            + (1 << MAX_LOG2_CAPACITY));
  }

  /**
   * Extracts the high 8 bits of a hash value for the slot hash8 prefix.
   *
   * <p>No sentinel collision is possible because the empty sentinel uses offset 0xFFFF, which is
   * a reserved value never assigned to real entries (valid offsets are 0 through 0xFFFE).
   *
   * @param hash the 32-bit hash value
   * @return hash8 byte
   */
  static byte computeHash8(int hash) {
    return (byte) (hash >>> 24);
  }

  @Override
  public void serialize(DatabaseSessionEmbedded session, EntityImpl entity,
      BytesContainer bytes) {
    throw new UnsupportedOperationException("V2 serialize not yet implemented");
  }

  @Override
  public int serializeValue(DatabaseSessionEmbedded db, BytesContainer bytes, Object value,
      PropertyTypeInternal type, PropertyTypeInternal linkedType, ImmutableSchema schema,
      PropertyEncryption encryption) {
    throw new UnsupportedOperationException("V2 serializeValue not yet implemented");
  }

  @Override
  public void deserialize(DatabaseSessionEmbedded db, EntityImpl entity, BytesContainer bytes) {
    throw new UnsupportedOperationException("V2 deserialize not yet implemented");
  }

  @Override
  public void deserializePartial(DatabaseSessionEmbedded db, EntityImpl entity,
      BytesContainer bytes, String[] iFields) {
    throw new UnsupportedOperationException("V2 deserializePartial not yet implemented");
  }

  @Override
  public Object deserializeValue(DatabaseSessionEmbedded db, BytesContainer bytes,
      PropertyTypeInternal type, RecordElement owner) {
    throw new UnsupportedOperationException("V2 deserializeValue not yet implemented");
  }

  @Override
  @Nullable public BinaryField deserializeField(DatabaseSessionEmbedded db, BytesContainer bytes,
      SchemaClass iClass, String iFieldName, boolean embedded, ImmutableSchema schema,
      PropertyEncryption encryption) {
    throw new UnsupportedOperationException("V2 deserializeField not yet implemented");
  }

  @Override
  public BinaryComparator getComparator() {
    return comparator;
  }

  @Override
  public String[] getFieldNames(DatabaseSessionEmbedded session, EntityImpl reference,
      BytesContainer iBytes, boolean embedded) {
    throw new UnsupportedOperationException("V2 getFieldNames not yet implemented");
  }
}
