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
    private final StampedLock lock = new StampedLock();

    // Parallel arrays — same index across all three stores one logical entry.
    // An empty slot has values[i] == null (fileIds and pageIndices content is undefined).
    private long[] fileIds;
    private int[] pageIndices;
    private V[] values;

    /** Number of logically present entries. */
    private volatile int size;

    /** Current array length (always a power of two). */
    private int capacity;

    @SuppressWarnings("unchecked")
    Section(int capacity) {
      this.capacity = alignToPowerOfTwo(Math.max(2, capacity));
      this.fileIds = new long[this.capacity];
      this.pageIndices = new int[this.capacity];
      this.values = (V[]) new Object[this.capacity];
      this.size = 0;
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
  }
}
