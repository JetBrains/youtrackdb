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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.StampedLock;
import java.util.function.Consumer;

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
   * <p><b>Warning:</b> The mapping function is called while holding the section's write lock. It
   * must be fast, non-blocking, and must not call back into this map (StampedLock is not reentrant).
   *
   * @return the existing or newly computed value
   * @throws IllegalArgumentException if the mapping function returns null
   */
  public V computeIfAbsent(long fileId, int pageIndex, LongIntFunction<V> mappingFunction) {
    long hash = hash(fileId, pageIndex);
    int sectionIdx = sectionIndex(hash);
    return sections[sectionIdx].computeIfAbsent(fileId, pageIndex, mappingFunction, (int) hash);
  }

  /**
   * Computes a new mapping for the given key. The remapping function receives the caller-supplied
   * fileId and pageIndex, plus the current value (or {@code null} if absent).
   *
   * <p><b>Semantics:</b>
   *
   * <ul>
   *   <li>Key absent, function returns null → no-op (key stays absent)
   *   <li>Key absent, function returns non-null → insert
   *   <li>Key present, function returns null → removal
   *   <li>Key present, function returns non-null → replace
   * </ul>
   *
   * <p><b>Warning:</b> The remapping function is called while holding the section's write lock. It
   * must be fast, non-blocking, and must not call back into this map (StampedLock is not reentrant).
   *
   * @return the new value associated with the key, or {@code null} if removed/absent
   */
  public V compute(long fileId, int pageIndex, LongIntKeyValueFunction<V> remappingFunction) {
    long hash = hash(fileId, pageIndex);
    int sectionIdx = sectionIndex(hash);
    return sections[sectionIdx].compute(fileId, pageIndex, remappingFunction, (int) hash);
  }

  /**
   * Removes the entry for the given composite key.
   *
   * @return the removed value, or {@code null} if absent
   */
  public V remove(long fileId, int pageIndex) {
    long hash = hash(fileId, pageIndex);
    int sectionIdx = sectionIndex(hash);
    return sections[sectionIdx].remove(fileId, pageIndex, (int) hash);
  }

  /**
   * Removes the entry for the given composite key only if it is currently mapped to the specified
   * value. Uses reference equality ({@code ==}), not {@code equals()}.
   *
   * @return {@code true} if the entry was removed
   */
  public boolean remove(long fileId, int pageIndex, V expected) {
    long hash = hash(fileId, pageIndex);
    int sectionIdx = sectionIndex(hash);
    return sections[sectionIdx].remove(fileId, pageIndex, expected, (int) hash);
  }

  /**
   * Removes all entries with the given fileId across all sections.
   *
   * <p>Each section is swept linearly under its write lock: matching entries are collected into a
   * list and the section's arrays are compacted (same-capacity rehash if needed). The returned list
   * is safe to iterate outside any lock — callers can perform post-removal processing (e.g.,
   * freeze, eviction callbacks) without holding segment locks.
   *
   * <p>Cross-section removal is not collectively atomic, matching the per-entry atomicity of
   * individual {@code remove()} calls.
   *
   * @return a list of removed values (may be empty, never null)
   */
  public List<V> removeByFileId(long fileId) {
    var result = new ArrayList<V>();
    for (Section<V> section : sections) {
      section.removeByFileId(fileId, result);
    }
    return result;
  }

  /** Removes all entries from all sections. */
  public void clear() {
    for (Section<V> s : sections) {
      s.clear();
    }
  }

  /** Iterates all entries, passing (fileId, pageIndex, value) to the consumer. */
  public void forEach(LongIntObjConsumer<V> consumer) {
    for (Section<V> s : sections) {
      s.forEach(consumer);
    }
  }

  /** Iterates all values, passing each to the consumer. */
  public void forEachValue(Consumer<V> consumer) {
    for (Section<V> s : sections) {
      s.forEachValue(consumer);
    }
  }

  /**
   * Shrinks the internal capacity to fit the current number of entries (respecting the fill
   * factor). Each section independently shrinks to the smallest power-of-two capacity that can hold
   * its current entries. Available but not called automatically — the cache has a stable working set
   * size.
   */
  public void shrink() {
    for (Section<V> s : sections) {
      s.shrink();
    }
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
   *
   * <p><b>Write ordering convention:</b> when inserting a new entry, the value is written before the
   * key fields (fileId, pageIndex). A concurrent optimistic reader that sees a non-null value at a
   * slot will find valid key fields because the write lock ensures ordering via stamp validation.
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

    /** Number of occupied buckets. Drives resize threshold. */
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

    /**
     * Probe for a key under write lock. Returns the index where the key was found (non-negative),
     * or a negative value encoding the first empty slot: {@code ~firstEmptySlot}. Callers decode
     * the empty slot index with {@code ~returnValue}.
     */
    private int probeForKey(long fileId, int pageIndex, int hashMix) {
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
          return idx;
        }
      }

      assert firstEmpty >= 0 : "No empty slot found; resize threshold should prevent full table";
      return ~firstEmpty;
    }

    V put(long fileId, int pageIndex, V value, int hashMix) {
      long stamp = lock.writeLock();
      try {
        int probeResult = probeForKey(fileId, pageIndex, hashMix);
        if (probeResult >= 0) {
          // Key found — replace value (write ordering: value before keys, see Section Javadoc)
          V prev = values[probeResult];
          values[probeResult] = value;
          return prev;
        }

        insertAt(~probeResult, fileId, pageIndex, value, hashMix);
        return null;
      } finally {
        lock.unlockWrite(stamp);
      }
    }

    V putIfAbsent(long fileId, int pageIndex, V value, int hashMix) {
      long stamp = lock.writeLock();
      try {
        int probeResult = probeForKey(fileId, pageIndex, hashMix);
        if (probeResult >= 0) {
          return values[probeResult];
        }

        insertAt(~probeResult, fileId, pageIndex, value, hashMix);
        return null;
      } finally {
        lock.unlockWrite(stamp);
      }
    }

    V computeIfAbsent(
        long fileId, int pageIndex, LongIntFunction<V> mappingFunction, int hashMix) {
      long stamp = lock.writeLock();
      try {
        int probeResult = probeForKey(fileId, pageIndex, hashMix);
        if (probeResult >= 0) {
          return values[probeResult];
        }

        V newValue = mappingFunction.apply(fileId, pageIndex);
        if (newValue == null) {
          throw new IllegalArgumentException(
              "computeIfAbsent mapping function must not return null");
        }

        insertAt(~probeResult, fileId, pageIndex, newValue, hashMix);
        return newValue;
      } finally {
        lock.unlockWrite(stamp);
      }
    }

    V compute(
        long fileId, int pageIndex, LongIntKeyValueFunction<V> remappingFunction, int hashMix) {
      long stamp = lock.writeLock();
      try {
        int probeResult = probeForKey(fileId, pageIndex, hashMix);

        if (probeResult >= 0) {
          // Key present — call function with current value
          V newValue = remappingFunction.apply(fileId, pageIndex, values[probeResult]);
          if (newValue != null) {
            values[probeResult] = newValue;
            return newValue;
          }
          // Function returned null on present key → removal
          removeAt(probeResult);
          return null;
        }

        // Key absent — call function with null
        V newValue = remappingFunction.apply(fileId, pageIndex, null);
        if (newValue == null) {
          // Function returned null on absent key → no-op
          return null;
        }
        insertAt(~probeResult, fileId, pageIndex, newValue, hashMix);
        return newValue;
      } finally {
        lock.unlockWrite(stamp);
      }
    }

    V remove(long fileId, int pageIndex, int hashMix) {
      long stamp = lock.writeLock();
      try {
        int probeResult = probeForKey(fileId, pageIndex, hashMix);
        if (probeResult < 0) {
          return null;
        }
        V prev = values[probeResult];
        removeAt(probeResult);
        return prev;
      } finally {
        lock.unlockWrite(stamp);
      }
    }

    boolean remove(long fileId, int pageIndex, V expected, int hashMix) {
      long stamp = lock.writeLock();
      try {
        int probeResult = probeForKey(fileId, pageIndex, hashMix);
        if (probeResult < 0) {
          return false;
        }
        // Reference equality, not equals() (T7 review decision)
        if (values[probeResult] != expected) {
          return false;
        }
        removeAt(probeResult);
        return true;
      } finally {
        lock.unlockWrite(stamp);
      }
    }

    /**
     * Remove all entries with the given fileId. Sweeps the entire section linearly under write
     * lock, collecting removed values into the provided list. After the sweep, always performs a
     * same-capacity rehash to compact the section and restore probe chain integrity.
     */
    void removeByFileId(long fileId, List<V> removedEntries) {
      long stamp = lock.writeLock();
      try {
        int removed = 0;
        for (int i = 0; i < capacity; i++) {
          V val = values[i];
          if (val != null && fileIds[i] == fileId) {
            removedEntries.add(val);
            values[i] = null;
            removed++;
          }
        }

        if (removed == 0) {
          return;
        }

        size -= removed;
        usedBuckets -= removed;

        // Since we used simple nullification (not backward-sweep per entry), there may be
        // gaps in probe chains. A same-capacity rehash restores probe chain integrity.
        rehashSameCapacity();
      } finally {
        lock.unlockWrite(stamp);
      }
    }

    /** Same-capacity rehash to compact gaps from bulk removal. Must be called under write lock. */
    private void rehashSameCapacity() {
      rehashTo(capacity);
    }

    /**
     * Remove the entry at the given slot and perform backward-sweep compaction. After nullifying
     * the slot, any entries that were displaced past this position during insertion are shifted
     * backward to fill the gap. This prevents tombstones entirely — {@code usedBuckets} decreases
     * with {@code size}. Must be called under write lock.
     */
    private void removeAt(int slotIdx) {
      values[slotIdx] = null;
      size--;

      // Backward-sweep cleanup: move entries that were displaced past the removed slot
      // back toward their ideal bucket position.
      int bucketMask = capacity - 1;
      int nextIdx = (slotIdx + 1) & bucketMask;
      while (values[nextIdx] != null) {
        int idealBucket = (int) hash(fileIds[nextIdx], pageIndices[nextIdx]) & bucketMask;

        // Check if the entry at nextIdx belongs in the gap we created.
        // It needs to be moved if its ideal bucket is at or before the empty slot
        // (accounting for wrap-around).
        if (isBetween(idealBucket, slotIdx, nextIdx, bucketMask)) {
          // Move the entry to the empty slot
          fileIds[slotIdx] = fileIds[nextIdx];
          pageIndices[slotIdx] = pageIndices[nextIdx];
          values[slotIdx] = values[nextIdx];
          values[nextIdx] = null;
          slotIdx = nextIdx;
        }
        nextIdx = (nextIdx + 1) & bucketMask;
      }

      // No tombstones created — usedBuckets decreases with size
      usedBuckets--;
    }

    /**
     * Returns true if the entry at {@code currentIdx} was displaced past the gap at {@code
     * emptySlot} during its original insertion, meaning it should be shifted backward to fill the
     * gap. Equivalently: the circular distance from {@code idealBucket} to {@code currentIdx} is
     * &gt;= the distance from {@code emptySlot} to {@code currentIdx}.
     */
    private static boolean isBetween(int idealBucket, int emptySlot, int currentIdx, int mask) {
      return ((currentIdx - idealBucket) & mask) >= ((currentIdx - emptySlot) & mask);
    }

    /**
     * Insert a new entry at the given slot index. Handles resize if needed. Must be called under
     * write lock.
     */
    private void insertAt(int slotIdx, long fileId, int pageIndex, V value, int hashMix) {
      if (usedBuckets >= resizeThreshold) {
        rehash();
        // Recalculate slot after resize — arrays have changed
        slotIdx = findEmptySlot(hashMix);
      }

      // Value first, then key fields (see Section Javadoc for write ordering rationale)
      values[slotIdx] = value;
      fileIds[slotIdx] = fileId;
      pageIndices[slotIdx] = pageIndex;

      usedBuckets++;
      size++;
    }

    /** Find an empty slot starting from the hash bucket. Must be called under write lock. */
    private int findEmptySlot(int hashMix) {
      int bucketMask = capacity - 1;
      int bucket = hashMix & bucketMask;

      for (int i = 0; i < capacity; i++) {
        int idx = (bucket + i) & bucketMask;
        if (values[idx] == null) {
          return idx;
        }
      }

      throw new IllegalStateException("No empty slot found after resize");
    }

    /** Double the capacity and re-insert all entries. Must be called under write lock. */
    private void rehash() {
      if (capacity >= (1 << 30)) {
        throw new IllegalStateException("Maximum section capacity reached (2^30)");
      }
      rehashTo(capacity * 2);
    }

    /**
     * Re-insert all live entries into fresh arrays of the given capacity. Updates capacity,
     * resizeThreshold, and usedBuckets. Must be called under write lock.
     */
    @SuppressWarnings("unchecked")
    private void rehashTo(int newCapacity) {
      long[] oldFileIds = fileIds;
      int[] oldPageIndices = pageIndices;
      V[] oldValues = values;
      int oldCapacity = capacity;

      capacity = newCapacity;
      fileIds = new long[newCapacity];
      pageIndices = new int[newCapacity];
      values = (V[]) new Object[newCapacity];
      resizeThreshold = (int) (newCapacity * FILL_FACTOR);
      usedBuckets = size;

      int bucketMask = newCapacity - 1;
      for (int i = 0; i < oldCapacity; i++) {
        V val = oldValues[i];
        if (val != null) {
          long fId = oldFileIds[i];
          int pIdx = oldPageIndices[i];
          int bucket = (int) hash(fId, pIdx) & bucketMask;

          while (values[bucket] != null) {
            bucket = (bucket + 1) & bucketMask;
          }

          values[bucket] = val;
          fileIds[bucket] = fId;
          pageIndices[bucket] = pIdx;
        }
      }
    }

    @SuppressWarnings("unchecked")
    void clear() {
      long stamp = lock.writeLock();
      try {
        fileIds = new long[capacity];
        pageIndices = new int[capacity];
        values = (V[]) new Object[capacity];
        size = 0;
        usedBuckets = 0;
      } finally {
        lock.unlockWrite(stamp);
      }
    }

    void forEach(LongIntObjConsumer<V> consumer) {
      long stamp = lock.readLock();
      try {
        for (int i = 0; i < capacity; i++) {
          V val = values[i];
          if (val != null) {
            consumer.accept(fileIds[i], pageIndices[i], val);
          }
        }
      } finally {
        lock.unlockRead(stamp);
      }
    }

    void forEachValue(Consumer<V> consumer) {
      long stamp = lock.readLock();
      try {
        for (int i = 0; i < capacity; i++) {
          V val = values[i];
          if (val != null) {
            consumer.accept(val);
          }
        }
      } finally {
        lock.unlockRead(stamp);
      }
    }

    /**
     * Shrink capacity to fit current entries. Acquires the write lock internally. The minimum
     * capacity is 2 (matching the constructor floor).
     */
    void shrink() {
      long stamp = lock.writeLock();
      try {
        // When size == 0, yields newCapacity = 2 (minimum)
        int newCapacity = alignToPowerOfTwo((int) Math.ceil(size / FILL_FACTOR));
        newCapacity = Math.max(2, newCapacity);
        if (newCapacity >= capacity) {
          return;
        }
        rehashTo(newCapacity);
      } finally {
        lock.unlockWrite(stamp);
      }
    }
  }
}
