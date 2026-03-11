/*
 *
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.multivalue.v2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.common.directmemory.ByteBufferPool;
import com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator.Intention;
import com.jetbrains.youtrackdb.internal.common.serialization.types.IntegerSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.StringSerializer;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.BinarySerializerFactory;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntryImpl;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CachePointer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for the in-buffer binary search method
 * {@code find(byte[], BinarySerializer, BinarySerializerFactory)} in
 * {@link CellBTreeMultiValueV2Bucket}. This method compares directly in the page buffer
 * without deserializing on-page keys, eliminating object allocation during search.
 *
 * <p>Each test allocates a direct-memory page, populates a bucket with sorted keys,
 * and verifies that the byte[]-based find returns the same results as the
 * deserialization-based find(K, ...) method.
 */
public class CellBTreeMultiValueV2BucketFindTest {

  private BinarySerializerFactory serializerFactory;
  private CachePointer cachePointer;
  private CacheEntry cacheEntry;

  @Before
  public void setUp() {
    serializerFactory = BinarySerializerFactory.create(
        BinarySerializerFactory.currentBinaryFormatVersion());

    var bufferPool = ByteBufferPool.instance(null);
    var pointer = bufferPool.acquireDirect(true, Intention.TEST);
    cachePointer = new CachePointer(pointer, bufferPool, 0, 0);
    cachePointer.incrementReferrer();
    cacheEntry = new CacheEntryImpl(0, 0, cachePointer, false, null);
    cacheEntry.acquireExclusiveLock();
  }

  @After
  public void tearDown() {
    cacheEntry.releaseExclusiveLock();
    cachePointer.decrementReferrer();
  }

  /**
   * Verifies that find(byte[]) returns the correct index for existing integer keys
   * in a leaf bucket, and negative insertion points for missing keys.
   */
  @Test
  public void testLeafBucketWithIntegerKeys() {
    var bucket = new CellBTreeMultiValueV2Bucket<Integer>(cacheEntry);
    bucket.init(true);

    // Insert sorted integer keys: 10, 20, 30, ..., 200
    var keySerializer = IntegerSerializer.INSTANCE;
    var insertedCount = 0;
    for (int i = 1; i <= 20; i++) {
      var key = i * 10;
      var serializedKey = keySerializer.serializeNativeAsWhole(
          serializerFactory, key, (Object[]) null);
      var rid = new RecordId(0, i);
      assertTrue(
          "Entry " + i + " should be inserted successfully",
          bucket.createMainLeafEntry(insertedCount, serializedKey, rid, i));
      insertedCount++;
    }

    // Verify find returns correct index for every inserted key
    for (int i = 0; i < insertedCount; i++) {
      var key = (i + 1) * 10;
      var serializedKey = keySerializer.serializeNativeAsWhole(
          serializerFactory, key, (Object[]) null);

      var indexByBytes = bucket.find(serializedKey, keySerializer, serializerFactory);
      assertEquals("find(byte[]) should return index " + i + " for key " + key,
          i, indexByBytes);
    }

    // Verify negative insertion point for a missing key between existing keys
    // Key 15 is between index 0 (key=10) and index 1 (key=20), insertion point = 1
    var missingKey = keySerializer.serializeNativeAsWhole(
        serializerFactory, 15, (Object[]) null);
    var result = bucket.find(missingKey, keySerializer, serializerFactory);
    assertTrue("find should return negative for missing key", result < 0);
    assertEquals("Insertion point for 15 should be 1", 1, -(result + 1));
  }

  /**
   * Verifies that find(byte[]) works correctly with String keys (StringSerializer)
   * in a leaf bucket.
   */
  @Test
  public void testLeafBucketWithStringKeys() {
    var bucket = new CellBTreeMultiValueV2Bucket<String>(cacheEntry);
    bucket.init(true);

    var keySerializer = StringSerializer.INSTANCE;
    // Sorted string keys
    var keys = new String[] {"alpha", "bravo", "charlie", "delta", "echo",
        "foxtrot", "golf", "hotel", "india", "juliet"};

    var insertedCount = 0;
    for (var key : keys) {
      var serializedKey = keySerializer.serializeNativeAsWhole(
          serializerFactory, key, (Object[]) null);
      var rid = new RecordId(0, insertedCount);
      assertTrue(bucket.createMainLeafEntry(insertedCount, serializedKey, rid, insertedCount));
      insertedCount++;
    }

    // Verify find returns correct index for each key
    for (int i = 0; i < insertedCount; i++) {
      var serializedKey = keySerializer.serializeNativeAsWhole(
          serializerFactory, keys[i], (Object[]) null);
      assertEquals(i, bucket.find(serializedKey, keySerializer, serializerFactory));
    }

    // Missing key "cherry" should fall between "charlie" (2) and "delta" (3)
    var missingKey = keySerializer.serializeNativeAsWhole(
        serializerFactory, "cherry", (Object[]) null);
    var result = bucket.find(missingKey, keySerializer, serializerFactory);
    assertTrue(result < 0);
    assertEquals(3, -(result + 1));
  }

  /**
   * Verifies that find(byte[]) works on a non-leaf bucket with integer keys.
   * Non-leaf entries store left/right child pointers before the key, so this
   * exercises the different offset calculation branch in the find method.
   */
  @Test
  public void testNonLeafBucketWithIntegerKeys() {
    var bucket = new CellBTreeMultiValueV2Bucket<Integer>(cacheEntry);
    bucket.init(false); // non-leaf

    var keySerializer = IntegerSerializer.INSTANCE;
    var insertedCount = 0;
    for (int i = 1; i <= 20; i++) {
      var key = i * 10;
      var serializedKey = keySerializer.serializeNativeAsWhole(
          serializerFactory, key, (Object[]) null);
      // leftChild = i-1, rightChild = i
      assertTrue(
          bucket.addNonLeafEntry(insertedCount, serializedKey, i - 1, i, false));
      insertedCount++;
    }

    // Verify find returns correct index for every key
    for (int i = 0; i < insertedCount; i++) {
      var key = (i + 1) * 10;
      var serializedKey = keySerializer.serializeNativeAsWhole(
          serializerFactory, key, (Object[]) null);
      assertEquals(i, bucket.find(serializedKey, keySerializer, serializerFactory));
    }

    // Missing key 25 should have insertion point 2 (between 20 at index 1 and 30 at index 2)
    var missingKey = keySerializer.serializeNativeAsWhole(
        serializerFactory, 25, (Object[]) null);
    var result = bucket.find(missingKey, keySerializer, serializerFactory);
    assertTrue(result < 0);
    assertEquals(2, -(result + 1));
  }

  /**
   * Verifies that find(byte[]) returns -1 for an empty leaf bucket.
   * An empty bucket has size 0, so the binary search loop never executes,
   * and the result should be -(0 + 1) = -1.
   */
  @Test
  public void testEmptyLeafBucket() {
    var bucket = new CellBTreeMultiValueV2Bucket<Integer>(cacheEntry);
    bucket.init(true);

    var keySerializer = IntegerSerializer.INSTANCE;
    var serializedKey = keySerializer.serializeNativeAsWhole(
        serializerFactory, 42, (Object[]) null);

    var result = bucket.find(serializedKey, keySerializer, serializerFactory);
    assertEquals("Empty bucket should return -1", -1, result);
  }

  /**
   * Verifies that find(byte[]) works correctly when the bucket contains only
   * a single entry: finds the key and returns correct insertion points for
   * keys less than and greater than the single entry.
   */
  @Test
  public void testSingleEntryLeafBucket() {
    var bucket = new CellBTreeMultiValueV2Bucket<Integer>(cacheEntry);
    bucket.init(true);

    var keySerializer = IntegerSerializer.INSTANCE;
    var serializedKey = keySerializer.serializeNativeAsWhole(
        serializerFactory, 50, (Object[]) null);
    assertTrue(bucket.createMainLeafEntry(0, serializedKey, new RecordId(0, 0), 1));

    // Find the exact key
    assertEquals(0, bucket.find(serializedKey, keySerializer, serializerFactory));

    // Key less than the single entry: insertion point 0
    var lessKey = keySerializer.serializeNativeAsWhole(
        serializerFactory, 10, (Object[]) null);
    var result = bucket.find(lessKey, keySerializer, serializerFactory);
    assertTrue(result < 0);
    assertEquals(0, -(result + 1));

    // Key greater than the single entry: insertion point 1
    var greaterKey = keySerializer.serializeNativeAsWhole(
        serializerFactory, 90, (Object[]) null);
    result = bucket.find(greaterKey, keySerializer, serializerFactory);
    assertTrue(result < 0);
    assertEquals(1, -(result + 1));
  }

  /**
   * Verifies that searching for a key smaller than all entries in the bucket
   * returns a negative value with insertion point 0.
   */
  @Test
  public void testKeyLessThanAll() {
    var bucket = new CellBTreeMultiValueV2Bucket<Integer>(cacheEntry);
    bucket.init(true);

    var keySerializer = IntegerSerializer.INSTANCE;
    for (int i = 0; i < 10; i++) {
      var key = (i + 1) * 100; // 100, 200, ..., 1000
      var serializedKey = keySerializer.serializeNativeAsWhole(
          serializerFactory, key, (Object[]) null);
      assertTrue(bucket.createMainLeafEntry(i, serializedKey, new RecordId(0, i), i));
    }

    // Search for key 1, which is less than all entries (min is 100)
    var smallKey = keySerializer.serializeNativeAsWhole(
        serializerFactory, 1, (Object[]) null);
    var result = bucket.find(smallKey, keySerializer, serializerFactory);
    assertTrue(result < 0);
    assertEquals("Insertion point should be 0 for key less than all",
        0, -(result + 1));
  }

  /**
   * Verifies that searching for a key larger than all entries in the bucket
   * returns a negative value with insertion point equal to the bucket size.
   */
  @Test
  public void testKeyGreaterThanAll() {
    var bucket = new CellBTreeMultiValueV2Bucket<Integer>(cacheEntry);
    bucket.init(true);

    var keySerializer = IntegerSerializer.INSTANCE;
    for (int i = 0; i < 10; i++) {
      var key = (i + 1) * 100; // 100, 200, ..., 1000
      var serializedKey = keySerializer.serializeNativeAsWhole(
          serializerFactory, key, (Object[]) null);
      assertTrue(bucket.createMainLeafEntry(i, serializedKey, new RecordId(0, i), i));
    }

    // Search for key 9999, which is greater than all entries (max is 1000)
    var largeKey = keySerializer.serializeNativeAsWhole(
        serializerFactory, 9999, (Object[]) null);
    var result = bucket.find(largeKey, keySerializer, serializerFactory);
    assertTrue(result < 0);
    assertEquals("Insertion point should be 10 for key greater than all",
        10, -(result + 1));
  }

  /**
   * Verifies consistency between find(byte[], ...) and find(K, ...) for all
   * inserted keys in a leaf bucket with integer keys. Both methods must return
   * the same index for every key.
   */
  @Test
  public void testConsistencyBetweenFindMethodsLeafInteger() {
    var bucket = new CellBTreeMultiValueV2Bucket<Integer>(cacheEntry);
    bucket.init(true);

    var keySerializer = IntegerSerializer.INSTANCE;
    var insertedCount = 0;
    for (int i = 0; i < 50; i++) {
      var key = i * 7 + 3; // 3, 10, 17, 24, ...
      var serializedKey = keySerializer.serializeNativeAsWhole(
          serializerFactory, key, (Object[]) null);
      var rid = new RecordId(0, i);
      if (!bucket.createMainLeafEntry(insertedCount, serializedKey, rid, i)) {
        break; // page full
      }
      insertedCount++;
    }

    assertTrue("Should have inserted at least some keys", insertedCount > 0);

    // Verify both find methods agree on every inserted key
    for (int i = 0; i < insertedCount; i++) {
      var key = i * 7 + 3;
      var serializedKey = keySerializer.serializeNativeAsWhole(
          serializerFactory, key, (Object[]) null);

      var indexByDeserializing = bucket.find(key, keySerializer, serializerFactory);
      var indexByBytes = bucket.find(serializedKey, keySerializer, serializerFactory);

      assertEquals(
          "find(K) and find(byte[]) must agree for key " + key,
          indexByDeserializing, indexByBytes);
    }

    // Also verify consistency for missing keys
    for (int missing = -10; missing < 400; missing += 13) {
      var serializedKey = keySerializer.serializeNativeAsWhole(
          serializerFactory, missing, (Object[]) null);

      var indexByDeserializing = bucket.find(missing, keySerializer, serializerFactory);
      var indexByBytes = bucket.find(serializedKey, keySerializer, serializerFactory);

      assertEquals(
          "find(K) and find(byte[]) must agree for missing key " + missing,
          indexByDeserializing, indexByBytes);
    }
  }

  /**
   * Verifies consistency between find(byte[], ...) and find(K, ...) for all
   * inserted keys in a non-leaf bucket with integer keys. Exercises the
   * non-leaf offset calculation path for both find methods.
   */
  @Test
  public void testConsistencyBetweenFindMethodsNonLeafInteger() {
    var bucket = new CellBTreeMultiValueV2Bucket<Integer>(cacheEntry);
    bucket.init(false);

    var keySerializer = IntegerSerializer.INSTANCE;
    var insertedCount = 0;
    for (int i = 0; i < 50; i++) {
      var key = i * 7 + 3;
      var serializedKey = keySerializer.serializeNativeAsWhole(
          serializerFactory, key, (Object[]) null);
      if (!bucket.addNonLeafEntry(insertedCount, serializedKey, i, i + 1, false)) {
        break;
      }
      insertedCount++;
    }

    assertTrue("Should have inserted at least some keys", insertedCount > 0);

    for (int i = 0; i < insertedCount; i++) {
      var key = i * 7 + 3;
      var serializedKey = keySerializer.serializeNativeAsWhole(
          serializerFactory, key, (Object[]) null);

      var indexByDeserializing = bucket.find(key, keySerializer, serializerFactory);
      var indexByBytes = bucket.find(serializedKey, keySerializer, serializerFactory);

      assertEquals(
          "find(K) and find(byte[]) must agree for non-leaf key " + key,
          indexByDeserializing, indexByBytes);
    }

    // Also verify consistency for missing keys
    for (int missing = -10; missing < 400; missing += 13) {
      var serializedKey = keySerializer.serializeNativeAsWhole(
          serializerFactory, missing, (Object[]) null);

      var indexByDeserializing = bucket.find(missing, keySerializer, serializerFactory);
      var indexByBytes = bucket.find(serializedKey, keySerializer, serializerFactory);

      assertEquals(
          "find(K) and find(byte[]) must agree for missing non-leaf key " + missing,
          indexByDeserializing, indexByBytes);
    }
  }

  /**
   * Verifies consistency between find(byte[], ...) and find(K, ...) for
   * String keys in a leaf bucket, ensuring the in-buffer comparison works
   * correctly for variable-length key types.
   */
  @Test
  public void testConsistencyBetweenFindMethodsLeafString() {
    var bucket = new CellBTreeMultiValueV2Bucket<String>(cacheEntry);
    bucket.init(true);

    var keySerializer = StringSerializer.INSTANCE;
    // Generate sorted string keys
    var keys = new String[] {
        "apple", "banana", "cherry", "date", "elderberry",
        "fig", "grape", "honeydew", "kiwi", "lemon",
        "mango", "nectarine", "orange", "papaya", "quince"
    };

    var insertedCount = 0;
    for (var key : keys) {
      var serializedKey = keySerializer.serializeNativeAsWhole(
          serializerFactory, key, (Object[]) null);
      var rid = new RecordId(0, insertedCount);
      if (!bucket.createMainLeafEntry(
          insertedCount, serializedKey, rid, insertedCount)) {
        break;
      }
      insertedCount++;
    }

    assertTrue("Should have inserted at least some keys", insertedCount > 0);

    // Verify both find methods agree on every inserted key
    for (int i = 0; i < insertedCount; i++) {
      var serializedKey = keySerializer.serializeNativeAsWhole(
          serializerFactory, keys[i], (Object[]) null);

      var indexByDeserializing =
          bucket.find(keys[i], keySerializer, serializerFactory);
      var indexByBytes =
          bucket.find(serializedKey, keySerializer, serializerFactory);

      assertEquals(
          "find(K) and find(byte[]) must agree for key '" + keys[i] + "'",
          indexByDeserializing, indexByBytes);
    }

    // Verify for missing keys
    var missingKeys = new String[] {"aardvark", "coconut", "zebra"};
    for (var missing : missingKeys) {
      var serializedKey = keySerializer.serializeNativeAsWhole(
          serializerFactory, missing, (Object[]) null);

      var indexByDeserializing =
          bucket.find(missing, keySerializer, serializerFactory);
      var indexByBytes =
          bucket.find(serializedKey, keySerializer, serializerFactory);

      assertEquals(
          "find(K) and find(byte[]) must agree for missing key '" + missing + "'",
          indexByDeserializing, indexByBytes);
    }
  }
}
