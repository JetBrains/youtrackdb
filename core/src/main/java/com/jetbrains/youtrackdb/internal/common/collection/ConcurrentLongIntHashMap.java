/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 * Original: Apache BookKeeper ConcurrentLongHashMap
 * Adapted for composite (long fileId, int pageIndex) keys by YouTrackDB.
 */
package com.jetbrains.youtrackdb.internal.common.collection;

import java.util.concurrent.locks.StampedLock;

/**
 * Concurrent hash map with composite {@code (long fileId, int pageIndex)} key and generic value.
 *
 * <p>Forked from Apache BookKeeper's {@code ConcurrentLongHashMap} and adapted for two-field keys.
 * Uses segmented open-addressing with {@link StampedLock} per segment: {@code get()} uses
 * optimistic reads with a single read-lock fallback; mutations use write locks.
 *
 * <p>Null values are disallowed — a null value slot indicates an empty bucket. Both
 * {@code fileId=0} and {@code pageIndex=0} are valid keys.
 *
 * @param <V> the value type
 */
public class ConcurrentLongIntHashMap<V> {

  /** Default number of sections (must be power-of-two). */
  private static final int DEFAULT_SECTION_COUNT = 16;

  /** Default expected number of items. */
  private static final int DEFAULT_EXPECTED_ITEMS = 256;

  private final Section<V>[] sections;
  private final int sectionMask;

  // ---- Nested functional interfaces ----

  /** Mapping function that takes primitive fileId and pageIndex, avoiding boxing. */
  @FunctionalInterface
  public interface LongIntFunction<R> {
    R apply(long fileId, int pageIndex);
  }

  /** Consumer that takes primitive fileId, pageIndex and a value, avoiding boxing. */
  @FunctionalInterface
  public interface LongIntObjConsumer<T> {
    void accept(long fileId, int pageIndex, T value);
  }

  /** Remapping function for {@code compute}. Receives the key and the current value (or null). */
  @FunctionalInterface
  public interface LongIntKeyValueFunction<T> {
    T apply(long fileId, int pageIndex, T currentValue);
  }

  // ---- Constructors ----

  public ConcurrentLongIntHashMap() {
    this(DEFAULT_EXPECTED_ITEMS, DEFAULT_SECTION_COUNT);
  }

  public ConcurrentLongIntHashMap(int expectedItems) {
    this(expectedItems, DEFAULT_SECTION_COUNT);
  }

  @SuppressWarnings("unchecked")
  public ConcurrentLongIntHashMap(int expectedItems, int sectionCount) {
    if (sectionCount <= 0 || (sectionCount & (sectionCount - 1)) != 0) {
      throw new IllegalArgumentException(
          "sectionCount must be a positive power of two: " + sectionCount);
    }
    if (expectedItems < 0) {
      throw new IllegalArgumentException(
          "expectedItems must be non-negative: " + expectedItems);
    }

    this.sectionMask = sectionCount - 1;
    this.sections = new Section[sectionCount];

    int perSectionCapacity = Math.max(2, alignToPowerOfTwo(expectedItems / sectionCount));
    for (int i = 0; i < sectionCount; i++) {
      sections[i] = new Section<>(perSectionCapacity);
    }
  }

  // ---- Public API ----

  /**
   * Returns the value for the given composite key, or {@code null} if absent.
   *
   * <p>Uses an optimistic read on the segment's {@link StampedLock}. If the optimistic read is
   * invalidated by a concurrent write, falls back to a single read-lock acquisition.
   */
  public V get(long fileId, int pageIndex) {
    long hash = hash(fileId, pageIndex);
    int sectionIdx = sectionIndex(hash);
    return sections[sectionIdx].get(fileId, pageIndex, (int) hash);
  }

  /** Returns the total number of entries across all sections. */
  public long size() {
    long total = 0;
    for (Section<V> s : sections) {
      total += s.size;
    }
    return total;
  }

  /** Returns {@code true} if the map contains no entries. */
  public boolean isEmpty() {
    return size() == 0;
  }

  /**
   * Associates the value with the given composite key. If the key is already present, the previous
   * value is replaced.
   *
   * @return the previous value, or {@code null} if absent
   * @throws IllegalArgumentException if value is null
   */
  public V put(long fileId, int pageIndex, V value) {
    if (value == null) {
      throw new IllegalArgumentException("Null values are not allowed");
    }
    long hash = hash(fileId, pageIndex);
    int sectionIdx = sectionIndex(hash);
    return sections[sectionIdx].put(fileId, pageIndex, value, (int) hash);
  }

  /**
   * Associates the value with the given composite key only if the key is not already present.
   *
   * @return the existing value if present, or {@code null} if the new value was inserted
   * @throws IllegalArgumentException if value is null
   */
  public V putIfAbsent(long fileId, int pageIndex, V value) {
    if (value == null) {
      throw new IllegalArgumentException("Null values are not allowed");
    }
    long hash = hash(fileId, pageIndex);
    int sectionIdx = sectionIndex(hash);
    return sections[sectionIdx].putIfAbsent(fileId, pageIndex, value, (int) hash);
  }

  /**
   * If the key is absent, computes a value using the mapping function and inserts it. If the key is
   * present, returns the existing value without calling the function.
   *
   * @return the existing or newly computed value
   * @throws IllegalArgumentException if the mapping function returns null
   */
  public V computeIfAbsent(long fileId, int pageIndex, LongIntFunction<V> mappingFunction) {
    long hash = hash(fileId, pageIndex);
    int sectionIdx = sectionIndex(hash);
    return sections[sectionIdx].computeIfAbsent(fileId, pageIndex, mappingFunction, (int) hash);
  }

  /** Returns the total capacity (sum of all section capacities). */
  public long capacity() {
    long total = 0;
    for (Section<V> s : sections) {
      total += s.capacity;
    }
    return total;
  }

  // ---- Hashing ----

  /**
   * Mixes both key fields into a 64-bit hash using a Murmur3-style finalizer.
   *
   * <p>The pageIndex is spread into the upper bits of a long before combining with fileId,
   * ensuring that keys differing only in pageIndex still distribute well across sections and
   * buckets.
   */
  static long hash(long fileId, int pageIndex) {
    // Combine: spread pageIndex into long, XOR with fileId
    long h = fileId ^ (((long) pageIndex) * 0x9E3779B97F4A7C15L);
    // Murmur3 64-bit finalizer
    h ^= h >>> 33;
    h *= 0xFF51AFD7ED558CCDL;
    h ^= h >>> 33;
    h *= 0xC4CEB9FE1A85EC53L;
    h ^= h >>> 33;
    return h;
  }

  private int sectionIndex(long hash) {
    return (int) (hash >>> 32) & sectionMask;
  }

  // ---- Utility ----

  static int alignToPowerOfTwo(int n) {
    if (n <= 1) {
      return 1;
    }
    if (n > (1 << 30)) {
      throw new IllegalArgumentException(
          "Cannot align to power of two: " + n + " exceeds maximum array capacity (2^30)");
    }
    return Integer.highestOneBit(n - 1) << 1;
  }

  // ---- Section (inner class) ----

  /**
   * A single segment of the hash map. Holds parallel arrays for keys and values, guarded by a
   * {@link StampedLock}. Uses open addressing with linear probing.
   */
  private static final class Section<V> {
    private static final float FILL_FACTOR = 0.66f;

    private final StampedLock lock = new StampedLock();

    // Parallel arrays — same index across all three stores one logical entry.
    // An empty slot has values[i] == null (fileIds and pageIndices content is undefined).
    private long[] fileIds;
    private int[] pageIndices;
    private V[] values;

    /** Number of logically present entries. */
    private volatile int size;

    /** Number of occupied buckets (entries + tombstones). Drives resize threshold. */
    private int usedBuckets;

    /** Current array length (always a power of two). */
    private int capacity;

    /** Resize when usedBuckets exceeds this. */
    private int resizeThreshold;

    @SuppressWarnings("unchecked")
    Section(int capacity) {
      this.capacity = alignToPowerOfTwo(Math.max(2, capacity));
      this.fileIds = new long[this.capacity];
      this.pageIndices = new int[this.capacity];
      this.values = (V[]) new Object[this.capacity];
      this.size = 0;
      this.usedBuckets = 0;
      this.resizeThreshold = (int) (this.capacity * FILL_FACTOR);
    }

    V get(long fileId, int pageIndex, int hashMix) {
      // Optimistic read — acquire stamp first, then snapshot all mutable fields to locals.
      // If a concurrent resize replaces the arrays between stamp and validate, the stamp
      // validation will fail and we fall back to the read lock.
      long stamp = lock.tryOptimisticRead();

      // Snapshot mutable fields to locals under the optimistic stamp
      int cap = capacity;
      long[] fIds = fileIds;
      int[] pIdxs = pageIndices;
      V[] vals = values;

      int bucketMask = cap - 1;
      int bucket = hashMix & bucketMask;

      // Probe loop using local snapshots only
      V foundValue = null;
      for (int i = 0; i < cap; i++) {
        int idx = (bucket + i) & bucketMask;
        V val = vals[idx];
        if (val == null) {
          // Empty slot — key is not present
          break;
        }
        if (fIds[idx] == fileId && pIdxs[idx] == pageIndex) {
          foundValue = val;
          break;
        }
      }

      if (lock.validate(stamp)) {
        return foundValue;
      }

      // Optimistic read invalidated — fall back to read lock
      stamp = lock.readLock();
      try {
        return getUnderLock(fileId, pageIndex, hashMix);
      } finally {
        lock.unlockRead(stamp);
      }
    }

    /** Probe for a key under a lock (read or write). */
    private V getUnderLock(long fileId, int pageIndex, int hashMix) {
      int bucketMask = capacity - 1;
      int bucket = hashMix & bucketMask;

      for (int i = 0; i < capacity; i++) {
        int idx = (bucket + i) & bucketMask;
        V val = values[idx];
        if (val == null) {
          return null;
        }
        if (fileIds[idx] == fileId && pageIndices[idx] == pageIndex) {
          return val;
        }
      }
      return null;
    }

    V put(long fileId, int pageIndex, V value, int hashMix) {
      long stamp = lock.writeLock();
      try {
        int bucketMask = capacity - 1;
        int bucket = hashMix & bucketMask;

        // Probe for existing key or first empty slot
        int firstEmpty = -1;
        for (int i = 0; i < capacity; i++) {
          int idx = (bucket + i) & bucketMask;
          V val = values[idx];
          if (val == null) {
            if (firstEmpty == -1) {
              firstEmpty = idx;
            }
            break;
          }
          if (fileIds[idx] == fileId && pageIndices[idx] == pageIndex) {
            // Key found — replace value
            V prev = values[idx];
            // Write ordering: value before keys (A2 review decision).
            // On the read path, a reader that sees the new value also sees valid keys.
            // If the reader sees the old value, it's still consistent with the old keys.
            values[idx] = value;
            return prev;
          }
        }

        insertAt(firstEmpty, fileId, pageIndex, value);
        return null;
      } finally {
        lock.unlockWrite(stamp);
      }
    }

    V putIfAbsent(long fileId, int pageIndex, V value, int hashMix) {
      long stamp = lock.writeLock();
      try {
        int bucketMask = capacity - 1;
        int bucket = hashMix & bucketMask;

        int firstEmpty = -1;
        for (int i = 0; i < capacity; i++) {
          int idx = (bucket + i) & bucketMask;
          V val = values[idx];
          if (val == null) {
            if (firstEmpty == -1) {
              firstEmpty = idx;
            }
            break;
          }
          if (fileIds[idx] == fileId && pageIndices[idx] == pageIndex) {
            // Key exists — return current value, don't replace
            return val;
          }
        }

        insertAt(firstEmpty, fileId, pageIndex, value);
        return null;
      } finally {
        lock.unlockWrite(stamp);
      }
    }

    V computeIfAbsent(
        long fileId, int pageIndex, LongIntFunction<V> mappingFunction, int hashMix) {
      long stamp = lock.writeLock();
      try {
        int bucketMask = capacity - 1;
        int bucket = hashMix & bucketMask;

        int firstEmpty = -1;
        for (int i = 0; i < capacity; i++) {
          int idx = (bucket + i) & bucketMask;
          V val = values[idx];
          if (val == null) {
            if (firstEmpty == -1) {
              firstEmpty = idx;
            }
            break;
          }
          if (fileIds[idx] == fileId && pageIndices[idx] == pageIndex) {
            return val;
          }
        }

        // Key absent — compute the value
        V newValue = mappingFunction.apply(fileId, pageIndex);
        if (newValue == null) {
          throw new IllegalArgumentException(
              "computeIfAbsent mapping function must not return null");
        }

        insertAt(firstEmpty, fileId, pageIndex, newValue);
        return newValue;
      } finally {
        lock.unlockWrite(stamp);
      }
    }

    /**
     * Insert a new entry at the given slot index. Handles resize if needed. Must be called under
     * write lock.
     */
    private void insertAt(int slotIdx, long fileId, int pageIndex, V value) {
      // Check if resize is needed before inserting
      if (usedBuckets >= resizeThreshold) {
        rehash(capacity * 2);
        // Recalculate slot after resize — arrays have changed
        slotIdx = findEmptySlot(fileId, pageIndex);
      }

      // Write ordering: value first, then key fields (A2 review decision).
      // A concurrent optimistic reader that sees the value will also see valid keys
      // because the write lock ensures ordering via the stamp validation.
      values[slotIdx] = value;
      fileIds[slotIdx] = fileId;
      pageIndices[slotIdx] = pageIndex;

      usedBuckets++;
      size++;
    }

    /** Find an empty slot for the given key. Must be called under write lock after resize. */
    private int findEmptySlot(long fileId, int pageIndex) {
      int bucketMask = capacity - 1;
      int bucket = (int) hash(fileId, pageIndex) & bucketMask;

      for (int i = 0; i < capacity; i++) {
        int idx = (bucket + i) & bucketMask;
        if (values[idx] == null) {
          return idx;
        }
      }

      // Should never happen — resize ensures there's always room
      throw new IllegalStateException("No empty slot found after resize");
    }

    /** Double the capacity and re-insert all entries. Must be called under write lock. */
    @SuppressWarnings("unchecked")
    private void rehash(int newCapacity) {
      newCapacity = alignToPowerOfTwo(newCapacity);

      long[] oldFileIds = fileIds;
      int[] oldPageIndices = pageIndices;
      V[] oldValues = values;
      int oldCapacity = capacity;

      capacity = newCapacity;
      fileIds = new long[newCapacity];
      pageIndices = new int[newCapacity];
      values = (V[]) new Object[newCapacity];
      resizeThreshold = (int) (newCapacity * FILL_FACTOR);
      usedBuckets = size; // Rehash eliminates tombstones

      int bucketMask = newCapacity - 1;
      for (int i = 0; i < oldCapacity; i++) {
        V val = oldValues[i];
        if (val != null) {
          long fId = oldFileIds[i];
          int pIdx = oldPageIndices[i];
          int bucket = (int) hash(fId, pIdx) & bucketMask;

          // Find empty slot in new arrays
          while (values[bucket] != null) {
            bucket = (bucket + 1) & bucketMask;
          }

          // Write ordering: value first, then keys
          values[bucket] = val;
          fileIds[bucket] = fId;
          pageIndices[bucket] = pIdx;
        }
      }
    }
  }
}
