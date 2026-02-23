/*
 *
 *
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *
 *
 */

package com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.v3;

import com.jetbrains.youtrackdb.internal.common.comparator.DefaultComparator;
import com.jetbrains.youtrackdb.internal.common.serialization.types.BinarySerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.ByteSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.IntegerSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.LongSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.ShortSerializer;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.BinarySerializerFactory;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * A durable page bucket for the v3 cell-based B-tree that maps single keys to single RID values.
 *
 * @since 8/7/13
 */
public final class CellBTreeSingleValueBucketV3<K> extends DurablePage {

  private static final int RID_SIZE = ShortSerializer.SHORT_SIZE + LongSerializer.LONG_SIZE;

  private static final int FREE_POINTER_OFFSET = NEXT_FREE_POSITION;
  private static final int SIZE_OFFSET = FREE_POINTER_OFFSET + IntegerSerializer.INT_SIZE;
  private static final int IS_LEAF_OFFSET = SIZE_OFFSET + IntegerSerializer.INT_SIZE;
  private static final int LEFT_SIBLING_OFFSET = IS_LEAF_OFFSET + ByteSerializer.BYTE_SIZE;
  private static final int RIGHT_SIBLING_OFFSET = LEFT_SIBLING_OFFSET + LongSerializer.LONG_SIZE;

  private static final int NEXT_FREE_LIST_PAGE_OFFSET = NEXT_FREE_POSITION;

  private static final int POSITIONS_ARRAY_OFFSET =
      RIGHT_SIBLING_OFFSET + LongSerializer.LONG_SIZE;

  private final Comparator<? super K> comparator = DefaultComparator.INSTANCE;

  public CellBTreeSingleValueBucketV3(final CacheEntry cacheEntry) {
    super(cacheEntry);
  }

  public void switchBucketType() {
    if (!isEmpty()) {
      throw new IllegalStateException(
          "Type of bucket can be changed only bucket if bucket is empty");
    }

    final var isLeaf = isLeaf();
    if (isLeaf) {
      setByteValue(IS_LEAF_OFFSET, (byte) 0);
    } else {
      setByteValue(IS_LEAF_OFFSET, (byte) 1);
    }
  }

  public void init(boolean isLeaf) {
    setFreePointer(MAX_PAGE_SIZE_BYTES);
    setSize(0);

    setByteValue(IS_LEAF_OFFSET, (byte) (isLeaf ? 1 : 0));
    setLongValue(LEFT_SIBLING_OFFSET, -1);
    setLongValue(RIGHT_SIBLING_OFFSET, -1);
  }

  public boolean isEmpty() {
    return size() == 0;
  }

  public int find(final K key, final BinarySerializer<K> keySerializer,
      BinarySerializerFactory serializerFactory) {
    var low = 0;
    var high = size() - 1;

    while (low <= high) {
      final var mid = (low + high) >>> 1;
      final var midVal = getKey(mid, keySerializer, serializerFactory);
      final var cmp = comparator.compare(midVal, key);

      if (cmp < 0) {
        low = mid + 1;
      } else if (cmp > 0) {
        high = mid - 1;
      } else {
        return mid; // key found
      }
    }

    return -(low + 1); // key not found.
  }

  public int removeLeafEntry(final int entryIndex, byte[] key) {
    final var entryPosition = getPointer(entryIndex);

    final int entrySize;
    if (isLeaf()) {
      entrySize = key.length + RID_SIZE;
    } else {
      throw new IllegalStateException("Remove is applies to leaf buckets only");
    }

    var pointers = getPointers();
    var size = pointers.length;
    var startChanging = size;
    if (entryIndex < size - 1) {
      for (var i = entryIndex + 1; i < size; i++) {
        if (pointers[i] < entryPosition) {
          pointers[i] += entrySize;
        }
      }
      setPointersOffset(entryIndex, pointers, entryIndex + 1);
      startChanging = entryIndex;
    }
    for (var i = 0; i < startChanging; i++) {
      if (pointers[i] < entryPosition) {
        setPointer(i, pointers[i] + entrySize);
      }
    }
    size--;
    setSize(size);

    final var freePointer = getFreePointer();
    if (size > 0 && entryPosition > freePointer) {
      moveData(freePointer, freePointer + entrySize, entryPosition - freePointer);
    }

    setFreePointer(freePointer + entrySize);

    return size;
  }

  private int getSize() {
    return getIntValue(SIZE_OFFSET);
  }

  public int removeNonLeafEntry(
      final int entryIndex,
      boolean removeLeftChildPointer,
      final BinarySerializer<K> keySerializer, BinarySerializerFactory serializerFactory) {
    if (isLeaf()) {
      throw new IllegalStateException("Remove is applied to non-leaf buckets only");
    }

    final var entryPosition = getPointer(entryIndex);
    final var keySize =
        getObjectSizeInDirectMemory(keySerializer, serializerFactory,
            entryPosition + 2 * IntegerSerializer.INT_SIZE);
    final var key = getBinaryValue(entryPosition + 2 * IntegerSerializer.INT_SIZE, keySize);

    return removeNonLeafEntry(entryIndex, key, removeLeftChildPointer);
  }

  public int removeNonLeafEntry(
      final int entryIndex, final byte[] key, boolean removeLeftChildPointer) {
    if (isLeaf()) {
      throw new IllegalStateException("Remove is applied to non-leaf buckets only");
    }

    final var entryPosition = getPointer(entryIndex);
    final var entrySize = key.length + 2 * IntegerSerializer.INT_SIZE;

    final var leftChild = getIntValue(entryPosition);
    final var rightChild = getIntValue(entryPosition + IntegerSerializer.INT_SIZE);

    var pointers = getPointers();
    var size = pointers.length;
    var startChanging = size;
    if (entryIndex < size - 1) {
      for (var i = entryIndex + 1; i < size; i++) {
        if (pointers[i] < entryPosition) {
          pointers[i] += entrySize;
        }
      }
      setPointersOffset(entryIndex, pointers, entryIndex + 1);
      startChanging = entryIndex;
    }
    for (var i = 0; i < startChanging; i++) {
      if (pointers[i] < entryPosition) {
        setPointer(i, pointers[i] + entrySize);
      }
    }
    size--;
    setSize(size);

    final var freePointer = getFreePointer();
    if (size > 0 && entryPosition > freePointer) {
      moveData(freePointer, freePointer + entrySize, entryPosition - freePointer);
    }

    setFreePointer(freePointer + entrySize);

    if (size > 0) {
      final var childPointer = removeLeftChildPointer ? rightChild : leftChild;

      if (entryIndex > 0) {
        final var prevEntryPosition = getPointer(entryIndex - 1);
        setIntValue(prevEntryPosition + IntegerSerializer.INT_SIZE, childPointer);
      }
      if (entryIndex < size) {
        final var nextEntryPosition = getPointer(entryIndex);
        setIntValue(nextEntryPosition, childPointer);
      }
    }

    return size;
  }

  public int[] getPointers() {
    var size = getSize();
    return getIntArray(POSITIONS_ARRAY_OFFSET, size);
  }

  public void setPointersOffset(int position, int[] pointers, int pointersOffset) {
    setIntArray(
        POSITIONS_ARRAY_OFFSET + position * IntegerSerializer.INT_SIZE, pointers, pointersOffset);
  }

  public int size() {
    return getSize();
  }

  public CellBTreeSingleValueEntryV3<K> getEntry(
      final int entryIndex, final BinarySerializer<K> keySerializer,
      BinarySerializerFactory serializerFactory) {
    var entryPosition = getPointer(entryIndex);

    if (isLeaf()) {
      final K key;

      key = deserializeFromDirectMemory(keySerializer, serializerFactory, entryPosition);

      entryPosition += getObjectSizeInDirectMemory(keySerializer, serializerFactory, entryPosition);

      final int collectionId = getShortValue(entryPosition);
      final var collectionPosition = getLongValue(entryPosition + ShortSerializer.SHORT_SIZE);

      return new CellBTreeSingleValueEntryV3<>(
          -1, -1, key, new RecordId(collectionId, collectionPosition));
    } else {
      final var leftChild = getIntValue(entryPosition);
      entryPosition += IntegerSerializer.INT_SIZE;

      final var rightChild = getIntValue(entryPosition);
      entryPosition += IntegerSerializer.INT_SIZE;

      final var key = deserializeFromDirectMemory(keySerializer, serializerFactory, entryPosition);

      return new CellBTreeSingleValueEntryV3<>(leftChild, rightChild, key, null);
    }
  }

  public int getLeft(final int entryIndex) {
    assert !isLeaf();

    final var entryPosition = getPointer(entryIndex);

    return getIntValue(entryPosition);
  }

  public int getRight(final int entryIndex) {
    assert !isLeaf();

    final var entryPosition = getPointer(entryIndex);

    return getIntValue(entryPosition + IntegerSerializer.INT_SIZE);
  }

  public byte[] getRawEntry(final int entryIndex, final BinarySerializer<K> keySerializer,
      BinarySerializerFactory serializerFactory) {
    var entryPosition = getPointer(entryIndex);
    final var startEntryPosition = entryPosition;

    if (isLeaf()) {
      final var keySize = getObjectSizeInDirectMemory(keySerializer, serializerFactory,
          entryPosition);

      return getBinaryValue(startEntryPosition, keySize + RID_SIZE);
    } else {
      entryPosition += 2 * IntegerSerializer.INT_SIZE;

      final var keySize = getObjectSizeInDirectMemory(keySerializer, serializerFactory,
          entryPosition);

      return getBinaryValue(startEntryPosition, keySize + 2 * IntegerSerializer.INT_SIZE);
    }
  }

  /**
   * Obtains the value stored under the given entry index in this bucket.
   *
   * @param entryIndex        the value entry index.
   * @param serializerFactory
   * @return the obtained value.
   */
  public RID getValue(final int entryIndex, final BinarySerializer<K> keySerializer,
      BinarySerializerFactory serializerFactory) {
    assert isLeaf();

    var entryPosition = getPointer(entryIndex);

    // skip key
    entryPosition += getObjectSizeInDirectMemory(keySerializer, serializerFactory, entryPosition);

    final int collectionId = getShortValue(entryPosition);
    final var collectionPosition = getLongValue(entryPosition + ShortSerializer.SHORT_SIZE);

    return new RecordId(collectionId, collectionPosition);
  }

  byte[] getRawValue(final int entryIndex, final BinarySerializer<K> keySerializer,
      BinarySerializerFactory serializerFactory) {
    assert isLeaf();
    assert entryIndex < getSize();

    var entryPosition = getPointer(entryIndex);

    // skip key
    entryPosition += getObjectSizeInDirectMemory(keySerializer, serializerFactory, entryPosition);

    return getBinaryValue(entryPosition, RID_SIZE);
  }

  public K getKey(final int index, final BinarySerializer<K> keySerializer,
      BinarySerializerFactory serializerFactory) {
    assert index < getSize();
    var entryPosition = getPointer(index);

    if (!isLeaf()) {
      entryPosition += 2 * IntegerSerializer.INT_SIZE;
    }

    return deserializeFromDirectMemory(keySerializer, serializerFactory, entryPosition);
  }

  private int getPointer(final int index) {
    return getIntValue(index * IntegerSerializer.INT_SIZE + POSITIONS_ARRAY_OFFSET);
  }

  public byte[] getRawKey(final int index, final BinarySerializer<K> keySerializer,
      BinarySerializerFactory serializerFactory) {
    var entryPosition = getPointer(index);

    if (!isLeaf()) {
      entryPosition += 2 * IntegerSerializer.INT_SIZE;
    }

    final var keyLen = getObjectSizeInDirectMemory(keySerializer, serializerFactory, entryPosition);
    return getBinaryValue(entryPosition, keyLen);
  }

  public boolean isLeaf() {
    return getByteValue(IS_LEAF_OFFSET) > 0;
  }

  public void addAll(final List<byte[]> rawEntries, final BinarySerializer<K> keySerializer) {
    final var currentSize = size();
    for (var i = 0; i < rawEntries.size(); i++) {
      appendRawEntry(i + currentSize, rawEntries.get(i));
    }

    setSize(rawEntries.size() + currentSize);
  }

  public void shrink(final int newSize, final BinarySerializer<K> keySerializer,
      BinarySerializerFactory serializerFactory) {
    final var currentSize = size();
    final List<byte[]> rawEntries = new ArrayList<>(newSize);
    final List<byte[]> removedEntries = new ArrayList<>(currentSize - newSize);

    for (var i = 0; i < newSize; i++) {
      rawEntries.add(getRawEntry(i, keySerializer, serializerFactory));
    }

    for (var i = newSize; i < currentSize; i++) {
      removedEntries.add(getRawEntry(i, keySerializer, serializerFactory));
    }

    setFreePointer(MAX_PAGE_SIZE_BYTES);

    for (var i = 0; i < newSize; i++) {
      appendRawEntry(i, rawEntries.get(i));
    }

    setSize(newSize);
  }

  private void setSize(final int newSize) {
    setIntValue(SIZE_OFFSET, newSize);
  }

  public boolean addLeafEntry(
      final int index, final byte[] serializedKey, final byte[] serializedValue) {
    final var entrySize = serializedKey.length + serializedValue.length;

    assert isLeaf();
    final var size = getSize();

    var freePointer = getFreePointer();
    if (doesOverflow(entrySize, 1)) {
      return false;
    }

    if (index <= size - 1) {
      shiftPointers(index, index + 1, size - index);
    }

    freePointer -= entrySize;

    setFreePointer(freePointer);
    setPointer(index, freePointer);
    setSize(size + 1);

    setBinaryValue(freePointer, serializedKey);
    setBinaryValue(freePointer + serializedKey.length, serializedValue);

    return true;
  }

  private void shiftPointers(final int index, final int indexTo, final int count) {
    moveData(
        POSITIONS_ARRAY_OFFSET + index * IntegerSerializer.INT_SIZE,
        POSITIONS_ARRAY_OFFSET + indexTo * IntegerSerializer.INT_SIZE,
        count * IntegerSerializer.INT_SIZE);
  }

  private int setPointer(final int index, int pointer) {
    return setIntValue(POSITIONS_ARRAY_OFFSET + index * IntegerSerializer.INT_SIZE, pointer);
  }

  private int setFreePointer(int freePointer) {
    return setIntValue(FREE_POINTER_OFFSET, freePointer);
  }

  private int getFreePointer() {
    return getIntValue(FREE_POINTER_OFFSET);
  }

  private void appendRawEntry(final int index, final byte[] rawEntry) {
    var freePointer = getFreePointer();
    freePointer -= rawEntry.length;

    setFreePointer(freePointer);
    setPointer(index, freePointer);

    setBinaryValue(freePointer, rawEntry);
  }

  public boolean addNonLeafEntry(
      final int index, final int leftChildIndex, final int newRightChildIndex, final byte[] key) {
    assert !isLeaf();

    final var keySize = key.length;

    final var entrySize = keySize + 2 * IntegerSerializer.INT_SIZE;

    var size = size();
    var freePointer = getFreePointer();
    if (doesOverflow(entrySize, 1)) {
      return false;
    }

    if (index <= size - 1) {
      shiftPointers(index, index + 1, size - index);
    }

    freePointer -= entrySize;

    setFreePointer(freePointer);
    setPointer(index, freePointer);
    setSize(size + 1);

    freePointer += setIntValue(freePointer, leftChildIndex);
    freePointer += setIntValue(freePointer, newRightChildIndex);

    setBinaryValue(freePointer, key);

    size++;

    if (size > 1) {
      if (index < size - 1) {
        final var nextEntryPosition = getPointer(index + 1);
        setIntValue(nextEntryPosition, newRightChildIndex);
      }
    }

    return true;
  }

  @SuppressWarnings("BooleanMethodIsAlwaysInverted")
  public boolean updateKey(
      final int entryIndex, final byte[] key, final BinarySerializer<K> keySerializer,
      BinarySerializerFactory serializerFactory) {
    if (isLeaf()) {
      throw new IllegalStateException("Update key is applied to non-leaf buckets only");
    }

    final var entryPosition = getPointer(entryIndex);
    final var keySize =
        getObjectSizeInDirectMemory(keySerializer, serializerFactory,
            entryPosition + 2 * IntegerSerializer.INT_SIZE);
    assert entryPosition + keySize < MAX_PAGE_SIZE_BYTES;
    if (key.length == keySize) {
      setBinaryValue(entryPosition + 2 * IntegerSerializer.INT_SIZE, key);
      return true;
    }

    var size = getSize();
    var freePointer = getFreePointer();

    if (doesOverflow(key.length - keySize, 0)) {
      return false;
    }

    final var entrySize = keySize + 2 * IntegerSerializer.INT_SIZE;

    final var leftChildIndex = getIntValue(entryPosition);
    final var rightChildIndex = getIntValue(entryPosition + IntegerSerializer.INT_SIZE);

    if (size > 0 && entryPosition > freePointer) {
      moveData(freePointer, freePointer + entrySize, entryPosition - freePointer);
      updatePointers(size, entryPosition, entrySize, entryIndex);
    }

    freePointer = freePointer - key.length + keySize;

    setFreePointer(freePointer);
    setPointer(entryIndex, freePointer);

    freePointer += setIntValue(freePointer, leftChildIndex);
    freePointer += setIntValue(freePointer, rightChildIndex);

    setBinaryValue(freePointer, key);

    return true;
  }

  public void updateValue(final int index, final byte[] value, int keyLenght) {
    var entryPosition = getPointer(index);
    if (!isLeaf()) {
      entryPosition += 2 * IntegerSerializer.INT_SIZE;
    }
    setBinaryValue(entryPosition + keyLenght, value);
  }

  public void setLeftSibling(final long pageIndex) {
    setLongValue(LEFT_SIBLING_OFFSET, pageIndex);
  }

  public long getLeftSibling() {
    return getLongValue(LEFT_SIBLING_OFFSET);
  }

  public void setRightSibling(final long pageIndex) {
    setLongValue(RIGHT_SIBLING_OFFSET, pageIndex);
  }

  public int getNextFreeListPage() {
    return getIntValue(NEXT_FREE_LIST_PAGE_OFFSET);
  }

  public void setNextFreeListPage(int nextFreeListPage) {
    setIntValue(NEXT_FREE_LIST_PAGE_OFFSET, nextFreeListPage);
  }

  public long getRightSibling() {
    return getLongValue(RIGHT_SIBLING_OFFSET);
  }

  private void updatePointers(int size, int basePosition, int shiftSize, int toIgnore) {
    var pointers = getIntArray(POSITIONS_ARRAY_OFFSET, size);
    for (var i = 0; i < size; i++) {
      if (toIgnore == i) {
        continue;
      }
      if (pointers[i] < basePosition) {
        setPointer(i, pointers[i] + shiftSize);
      }
    }
  }

  private boolean doesOverflow(int requiredDataSpace, int requirePointerSpace) {
    var size = getSize();
    var freePointer = getFreePointer();
    return freePointer - requiredDataSpace
        < (size + requirePointerSpace) * IntegerSerializer.INT_SIZE + POSITIONS_ARRAY_OFFSET;
  }
}
