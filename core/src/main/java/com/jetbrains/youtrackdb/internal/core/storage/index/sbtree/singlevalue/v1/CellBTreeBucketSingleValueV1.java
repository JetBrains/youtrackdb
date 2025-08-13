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

package com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.v1;

import com.jetbrains.youtrackdb.api.record.RID;
import com.jetbrains.youtrackdb.internal.common.comparator.DefaultComparator;
import com.jetbrains.youtrackdb.internal.common.serialization.types.BinarySerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.ByteSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.IntegerSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.LongSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.ShortSerializer;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.BinarySerializerFactory;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * @since 8/7/13
 */
public final class CellBTreeBucketSingleValueV1<K> extends DurablePage {

  private static final int RID_SIZE = ShortSerializer.SHORT_SIZE + LongSerializer.LONG_SIZE;

  private static final int FREE_POINTER_OFFSET = NEXT_FREE_POSITION;
  private static final int SIZE_OFFSET = FREE_POINTER_OFFSET + IntegerSerializer.INT_SIZE;
  private static final int IS_LEAF_OFFSET = SIZE_OFFSET + IntegerSerializer.INT_SIZE;
  private static final int LEFT_SIBLING_OFFSET = IS_LEAF_OFFSET + ByteSerializer.BYTE_SIZE;
  private static final int RIGHT_SIBLING_OFFSET = LEFT_SIBLING_OFFSET + LongSerializer.LONG_SIZE;

  private static final int POSITIONS_ARRAY_OFFSET =
      RIGHT_SIBLING_OFFSET + LongSerializer.LONG_SIZE;

  private final Comparator<? super K> comparator = DefaultComparator.INSTANCE;

  public CellBTreeBucketSingleValueV1(final CacheEntry cacheEntry) {
    super(cacheEntry);
  }

  public void init(boolean isLeaf) {
    setIntValue(FREE_POINTER_OFFSET, MAX_PAGE_SIZE_BYTES);
    setIntValue(SIZE_OFFSET, 0);

    setByteValue(IS_LEAF_OFFSET, (byte) (isLeaf ? 1 : 0));
    setLongValue(LEFT_SIBLING_OFFSET, -1);
    setLongValue(RIGHT_SIBLING_OFFSET, -1);
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

  public boolean isEmpty() {
    return size() == 0;
  }

  public int find(
      final K key, final BinarySerializer<K> keySerializer,
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

  public void removeLeafEntry(final int entryIndex, byte[] key, byte[] value) {
    final var entryPosition =
        getIntValue(POSITIONS_ARRAY_OFFSET + entryIndex * IntegerSerializer.INT_SIZE);

    final int entrySize;
    if (isLeaf()) {
      entrySize = key.length + RID_SIZE;
    } else {
      throw new IllegalStateException("Remove is applies to leaf buckets only");
    }

    var size = getIntValue(SIZE_OFFSET);
    if (entryIndex < size - 1) {
      moveData(
          POSITIONS_ARRAY_OFFSET + (entryIndex + 1) * IntegerSerializer.INT_SIZE,
          POSITIONS_ARRAY_OFFSET + entryIndex * IntegerSerializer.INT_SIZE,
          (size - entryIndex - 1) * IntegerSerializer.INT_SIZE);
    }

    size--;
    setIntValue(SIZE_OFFSET, size);

    final var freePointer = getIntValue(FREE_POINTER_OFFSET);
    if (size > 0 && entryPosition > freePointer) {
      moveData(freePointer, freePointer + entrySize, entryPosition - freePointer);
    }

    setIntValue(FREE_POINTER_OFFSET, freePointer + entrySize);

    var currentPositionOffset = POSITIONS_ARRAY_OFFSET;

    for (var i = 0; i < size; i++) {
      final var currentEntryPosition = getIntValue(currentPositionOffset);
      if (currentEntryPosition < entryPosition) {
        setIntValue(currentPositionOffset, currentEntryPosition + entrySize);
      }
      currentPositionOffset += IntegerSerializer.INT_SIZE;
    }
  }

  public void removeNonLeafEntry(final int entryIndex, final byte[] key, final int prevChild) {
    if (isLeaf()) {
      throw new IllegalStateException("Remove is applied to non-leaf buckets only");
    }

    final var entryPosition =
        getIntValue(POSITIONS_ARRAY_OFFSET + entryIndex * IntegerSerializer.INT_SIZE);
    final var entrySize = key.length + 2 * IntegerSerializer.INT_SIZE;
    var size = getIntValue(SIZE_OFFSET);

    if (entryIndex < size - 1) {
      moveData(
          POSITIONS_ARRAY_OFFSET + (entryIndex + 1) * IntegerSerializer.INT_SIZE,
          POSITIONS_ARRAY_OFFSET + entryIndex * IntegerSerializer.INT_SIZE,
          (size - entryIndex - 1) * IntegerSerializer.INT_SIZE);
    }

    size--;
    setIntValue(SIZE_OFFSET, size);

    final var freePointer = getIntValue(FREE_POINTER_OFFSET);
    if (size > 0 && entryPosition > freePointer) {
      moveData(freePointer, freePointer + entrySize, entryPosition - freePointer);
    }

    setIntValue(FREE_POINTER_OFFSET, freePointer + entrySize);

    var currentPositionOffset = POSITIONS_ARRAY_OFFSET;

    for (var i = 0; i < size; i++) {
      final var currentEntryPosition = getIntValue(currentPositionOffset);
      if (currentEntryPosition < entryPosition) {
        setIntValue(currentPositionOffset, currentEntryPosition + entrySize);
      }
      currentPositionOffset += IntegerSerializer.INT_SIZE;
    }

    if (prevChild >= 0) {
      if (entryIndex > 0) {
        final var prevEntryPosition =
            getIntValue(POSITIONS_ARRAY_OFFSET + (entryIndex - 1) * IntegerSerializer.INT_SIZE);
        setIntValue(prevEntryPosition + IntegerSerializer.INT_SIZE, prevChild);
      }

      if (entryIndex < size) {
        final var nextEntryPosition =
            getIntValue(POSITIONS_ARRAY_OFFSET + entryIndex * IntegerSerializer.INT_SIZE);
        setIntValue(nextEntryPosition, prevChild);
      }
    }
  }

  public int size() {
    return getIntValue(SIZE_OFFSET);
  }

  public SBTreeEntry<K> getEntry(
      final int entryIndex, BinarySerializer<K> keySerializer,
      BinarySerializerFactory serializerFactory) {
    var entryPosition =
        getIntValue(entryIndex * IntegerSerializer.INT_SIZE + POSITIONS_ARRAY_OFFSET);

    if (isLeaf()) {
      final K key;

      key = deserializeFromDirectMemory(keySerializer, serializerFactory, entryPosition);

      entryPosition += getObjectSizeInDirectMemory(keySerializer, serializerFactory, entryPosition);

      final int collectionId = getShortValue(entryPosition);
      final var collectionPosition = getLongValue(entryPosition + ShortSerializer.SHORT_SIZE);

      return new SBTreeEntry<>(-1, -1, key, new RecordId(collectionId, collectionPosition));
    } else {
      final var leftChild = getIntValue(entryPosition);
      entryPosition += IntegerSerializer.INT_SIZE;

      final var rightChild = getIntValue(entryPosition);
      entryPosition += IntegerSerializer.INT_SIZE;

      final K key;
      key = deserializeFromDirectMemory(keySerializer, serializerFactory, entryPosition);

      return new SBTreeEntry<>(leftChild, rightChild, key, null);
    }
  }

  int getLeft(final int entryIndex) {
    assert !isLeaf();

    final var entryPosition =
        getIntValue(entryIndex * IntegerSerializer.INT_SIZE + POSITIONS_ARRAY_OFFSET);

    return getIntValue(entryPosition);
  }

  int getRight(final int entryIndex) {
    assert !isLeaf();

    final var entryPosition =
        getIntValue(entryIndex * IntegerSerializer.INT_SIZE + POSITIONS_ARRAY_OFFSET);

    return getIntValue(entryPosition + IntegerSerializer.INT_SIZE);
  }

  public byte[] getRawEntry(
      final int entryIndex, BinarySerializer<K> keySerializer,
      BinarySerializerFactory serializerFactory) {
    var entryPosition =
        getIntValue(entryIndex * IntegerSerializer.INT_SIZE + POSITIONS_ARRAY_OFFSET);
    final var startEntryPosition = entryPosition;

    if (isLeaf()) {
      final int keySize;
      keySize = getObjectSizeInDirectMemory(keySerializer, serializerFactory, entryPosition);

      return getBinaryValue(startEntryPosition, keySize + RID_SIZE);
    } else {
      entryPosition += 2 * IntegerSerializer.INT_SIZE;

      final int keySize;
      keySize = getObjectSizeInDirectMemory(keySerializer, serializerFactory, entryPosition);

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
  public RID getValue(
      final int entryIndex, BinarySerializer<K> keySerializer,
      BinarySerializerFactory serializerFactory) {
    assert isLeaf();

    var entryPosition =
        getIntValue(entryIndex * IntegerSerializer.INT_SIZE + POSITIONS_ARRAY_OFFSET);
    entryPosition += getObjectSizeInDirectMemory(keySerializer, serializerFactory, entryPosition);

    final int collectionId = getShortValue(entryPosition);
    final var collectionPosition = getLongValue(entryPosition + ShortSerializer.SHORT_SIZE);

    return new RecordId(collectionId, collectionPosition);
  }

  public RID getValue(final int entryIndex, final int keyLen) {
    assert isLeaf();

    var entryPosition =
        getIntValue(entryIndex * IntegerSerializer.INT_SIZE + POSITIONS_ARRAY_OFFSET);
    entryPosition += keyLen;

    final int collectionId = getShortValue(entryPosition);
    final var collectionPosition = getLongValue(entryPosition + ShortSerializer.SHORT_SIZE);

    return new RecordId(collectionId, collectionPosition);
  }

  byte[] getRawValue(
      final int entryIndex, BinarySerializer<K> keySerializer,
      BinarySerializerFactory serializerFactory) {
    assert isLeaf();

    var entryPosition =
        getIntValue(entryIndex * IntegerSerializer.INT_SIZE + POSITIONS_ARRAY_OFFSET);

    entryPosition += getObjectSizeInDirectMemory(keySerializer, serializerFactory, entryPosition);

    return getBinaryValue(entryPosition, RID_SIZE);
  }

  public K getKey(final int index, BinarySerializer<K> keySerializer,
      BinarySerializerFactory serializerFactory) {
    var entryPosition = getIntValue(index * IntegerSerializer.INT_SIZE + POSITIONS_ARRAY_OFFSET);

    if (!isLeaf()) {
      entryPosition += 2 * IntegerSerializer.INT_SIZE;
    }

    return deserializeFromDirectMemory(keySerializer, serializerFactory, entryPosition);
  }

  public boolean isLeaf() {
    return getByteValue(IS_LEAF_OFFSET) > 0;
  }

  public void addAll(
      final List<byte[]> rawEntries) {
    final var currentSize = size();
    for (var i = 0; i < rawEntries.size(); i++) {
      appendRawEntry(i + currentSize, rawEntries.get(i));
    }

    setIntValue(SIZE_OFFSET, currentSize + rawEntries.size());
  }

  public void shrink(
      final int newSize, final BinarySerializer<K> keySerializer,
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

    setIntValue(FREE_POINTER_OFFSET, MAX_PAGE_SIZE_BYTES);

    for (var i = 0; i < newSize; i++) {
      appendRawEntry(i, rawEntries.get(i));
    }

    setIntValue(SIZE_OFFSET, newSize);
  }

  public boolean addLeafEntry(
      final int index, final byte[] serializedKey, final byte[] serializedValue) {
    final var entrySize = serializedKey.length + serializedValue.length;

    assert isLeaf();
    final var size = getIntValue(SIZE_OFFSET);

    var freePointer = getIntValue(FREE_POINTER_OFFSET);
    if (freePointer - entrySize
        < (size + 1) * IntegerSerializer.INT_SIZE + POSITIONS_ARRAY_OFFSET) {
      return false;
    }

    if (index <= size - 1) {
      moveData(
          POSITIONS_ARRAY_OFFSET + index * IntegerSerializer.INT_SIZE,
          POSITIONS_ARRAY_OFFSET + (index + 1) * IntegerSerializer.INT_SIZE,
          (size - index) * IntegerSerializer.INT_SIZE);
    }

    freePointer -= entrySize;

    setIntValue(FREE_POINTER_OFFSET, freePointer);
    setIntValue(POSITIONS_ARRAY_OFFSET + index * IntegerSerializer.INT_SIZE, freePointer);
    setIntValue(SIZE_OFFSET, size + 1);

    setBinaryValue(freePointer, serializedKey);
    setBinaryValue(freePointer + serializedKey.length, serializedValue);
    return true;
  }

  private void appendRawEntry(final int index, final byte[] rawEntry) {
    var freePointer = getIntValue(FREE_POINTER_OFFSET);
    freePointer -= rawEntry.length;

    setIntValue(FREE_POINTER_OFFSET, freePointer);
    setIntValue(POSITIONS_ARRAY_OFFSET + index * IntegerSerializer.INT_SIZE, freePointer);

    setBinaryValue(freePointer, rawEntry);
  }

  public boolean addNonLeafEntry(
      final int index,
      final int leftChild,
      final int rightChild,
      final byte[] key,
      final boolean updateNeighbors) {
    assert !isLeaf();

    final var keySize = key.length;

    final var entrySize = keySize + 2 * IntegerSerializer.INT_SIZE;

    var size = size();
    var freePointer = getIntValue(FREE_POINTER_OFFSET);
    if (freePointer - entrySize
        < (size + 1) * IntegerSerializer.INT_SIZE + POSITIONS_ARRAY_OFFSET) {
      return false;
    }

    if (index <= size - 1) {
      moveData(
          POSITIONS_ARRAY_OFFSET + index * IntegerSerializer.INT_SIZE,
          POSITIONS_ARRAY_OFFSET + (index + 1) * IntegerSerializer.INT_SIZE,
          (size - index) * IntegerSerializer.INT_SIZE);
    }

    freePointer -= entrySize;

    setIntValue(FREE_POINTER_OFFSET, freePointer);
    setIntValue(POSITIONS_ARRAY_OFFSET + index * IntegerSerializer.INT_SIZE, freePointer);
    setIntValue(SIZE_OFFSET, size + 1);

    freePointer += setIntValue(freePointer, leftChild);
    freePointer += setIntValue(freePointer, rightChild);

    setBinaryValue(freePointer, key);

    size++;

    if (updateNeighbors && size > 1) {
      if (index < size - 1) {
        final var nextEntryPosition =
            getIntValue(POSITIONS_ARRAY_OFFSET + (index + 1) * IntegerSerializer.INT_SIZE);
        setIntValue(nextEntryPosition, rightChild);
      }

      if (index > 0) {
        final var prevEntryPosition =
            getIntValue(POSITIONS_ARRAY_OFFSET + (index - 1) * IntegerSerializer.INT_SIZE);
        setIntValue(prevEntryPosition + IntegerSerializer.INT_SIZE, leftChild);
      }
    }

    return true;
  }

  public void updateValue(final int index, final byte[] value, final int keySize) {
    final var entryPosition =
        getIntValue(index * IntegerSerializer.INT_SIZE + POSITIONS_ARRAY_OFFSET) + keySize;

    setBinaryValue(entryPosition, value);
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

  public long getRightSibling() {
    return getLongValue(RIGHT_SIBLING_OFFSET);
  }

  public static final class SBTreeEntry<K> implements Comparable<SBTreeEntry<K>> {

    private final Comparator<? super K> comparator = DefaultComparator.INSTANCE;

    private final int leftChild;
    private final int rightChild;
    public final K key;
    public final RID value;

    public SBTreeEntry(final int leftChild, final int rightChild, final K key, final RID value) {
      this.leftChild = leftChild;
      this.rightChild = rightChild;
      this.key = key;
      this.value = value;
    }

    @Override
    public boolean equals(final Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      final var that = (SBTreeEntry<?>) o;
      return leftChild == that.leftChild
          && rightChild == that.rightChild
          && Objects.equals(key, that.key)
          && Objects.equals(value, that.value);
    }

    @Override
    public int hashCode() {
      return Objects.hash(leftChild, rightChild, key, value);
    }

    @Override
    public String toString() {
      return "CellBTreeEntry{"
          + "leftChild="
          + leftChild
          + ", rightChild="
          + rightChild
          + ", key="
          + key
          + ", value="
          + value
          + '}';
    }

    @Override
    public int compareTo(final SBTreeEntry<K> other) {
      return comparator.compare(key, other.key);
    }
  }
}
