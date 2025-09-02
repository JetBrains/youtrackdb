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

package com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.local.v1;

import static com.jetbrains.youtrackdb.internal.core.storage.collection.CollectionPage.PAGE_SIZE;

import com.jetbrains.youtrackdb.internal.common.comparator.DefaultComparator;
import com.jetbrains.youtrackdb.internal.common.serialization.types.BinarySerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.ByteSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.IntegerSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.LongSerializer;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.BinarySerializerFactory;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * @since 8/7/13
 */
public final class SBTreeBucketV1<K, V> extends DurablePage {

  private static final int FREE_POINTER_OFFSET = NEXT_FREE_POSITION;
  private static final int SIZE_OFFSET = FREE_POINTER_OFFSET + IntegerSerializer.INT_SIZE;
  private static final int IS_LEAF_OFFSET = SIZE_OFFSET + IntegerSerializer.INT_SIZE;
  private static final int LEFT_SIBLING_OFFSET = IS_LEAF_OFFSET + ByteSerializer.BYTE_SIZE;
  private static final int RIGHT_SIBLING_OFFSET = LEFT_SIBLING_OFFSET + LongSerializer.LONG_SIZE;

  private static final int TREE_SIZE_OFFSET = RIGHT_SIBLING_OFFSET + LongSerializer.LONG_SIZE;

  /**
   * KEY_SERIALIZER_OFFSET and VALUE_SERIALIZER_OFFSET are no longer used by sb-tree since 1.7.
   * However we left them in buckets to support backward compatibility.
   */
  private static final int KEY_SERIALIZER_OFFSET = TREE_SIZE_OFFSET + LongSerializer.LONG_SIZE;

  private static final int VALUE_SERIALIZER_OFFSET =
      KEY_SERIALIZER_OFFSET + ByteSerializer.BYTE_SIZE;

  private static final int FREE_VALUES_LIST_OFFSET =
      VALUE_SERIALIZER_OFFSET + ByteSerializer.BYTE_SIZE;

  private static final int POSITIONS_ARRAY_OFFSET =
      FREE_VALUES_LIST_OFFSET + LongSerializer.LONG_SIZE;

  private final Comparator<? super K> comparator = DefaultComparator.INSTANCE;

  public SBTreeBucketV1(final CacheEntry cacheEntry) {
    super(cacheEntry);
  }

  public void init(boolean isLeaf) {
    setIntValue(FREE_POINTER_OFFSET, MAX_PAGE_SIZE_BYTES);
    setIntValue(SIZE_OFFSET, 0);

    setByteValue(IS_LEAF_OFFSET, (byte) (isLeaf ? 1 : 0));
    setLongValue(LEFT_SIBLING_OFFSET, -1);
    setLongValue(RIGHT_SIBLING_OFFSET, -1);

    setLongValue(TREE_SIZE_OFFSET, 0);
    setLongValue(FREE_VALUES_LIST_OFFSET, -1);
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

  public void setTreeSize(long size) {
    setLongValue(TREE_SIZE_OFFSET, size);
  }

  public long getTreeSize() {
    return getLongValue(TREE_SIZE_OFFSET);
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
      var mid = (low + high) >>> 1;
      var midVal = getKey(mid, keySerializer, serializerFactory);
      var cmp = comparator.compare(midVal, key);

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

  public void removeLeafEntry(final int entryIndex, byte[] oldRawKey, byte[] oldRawValue) {
    if (!isLeaf()) {
      throw new IllegalStateException("This remove method is applied to leaf buckets only.");
    }

    final var entryPosition =
        getIntValue(POSITIONS_ARRAY_OFFSET + entryIndex * IntegerSerializer.INT_SIZE);
    final var entrySize = oldRawKey.length + oldRawValue.length + ByteSerializer.BYTE_SIZE;

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
      throw new IllegalStateException("This remove method is applied to non-leaf buckets only.");
    }

    final var entryPosition =
        getIntValue(POSITIONS_ARRAY_OFFSET + entryIndex * IntegerSerializer.INT_SIZE);
    final var entrySize = key.length + 2 * LongSerializer.LONG_SIZE;

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
        setLongValue(prevEntryPosition + LongSerializer.LONG_SIZE, prevChild);
      }

      if (entryIndex < size) {
        final var nextEntryPosition =
            getIntValue(POSITIONS_ARRAY_OFFSET + entryIndex * IntegerSerializer.INT_SIZE);
        setLongValue(nextEntryPosition, prevChild);
      }
    }
  }

  public int size() {
    return getIntValue(SIZE_OFFSET);
  }

  public SBTreeEntry<K, V> getEntry(
      final int entryIndex,
      final BinarySerializer<K> keySerializer,
      final BinarySerializer<V> valueSerializer, BinarySerializerFactory serializerFactory) {
    var entryPosition =
        getIntValue(entryIndex * IntegerSerializer.INT_SIZE + POSITIONS_ARRAY_OFFSET);

    if (isLeaf()) {
      var key = deserializeFromDirectMemory(keySerializer, serializerFactory, entryPosition);
      entryPosition += getObjectSizeInDirectMemory(keySerializer, serializerFactory, entryPosition);

      assert getByteValue(entryPosition) == 0;
      final var value =
          deserializeFromDirectMemory(valueSerializer, serializerFactory,
              entryPosition + ByteSerializer.BYTE_SIZE);

      return new SBTreeEntry<>(-1, -1, key, new SBTreeValue<>(false, -1, value));
    } else {
      var leftChild = getLongValue(entryPosition);
      entryPosition += LongSerializer.LONG_SIZE;

      var rightChild = getLongValue(entryPosition);
      entryPosition += LongSerializer.LONG_SIZE;

      var key = deserializeFromDirectMemory(keySerializer, serializerFactory, entryPosition);
      return new SBTreeEntry<>(leftChild, rightChild, key, null);
    }
  }

  public byte[] getRawEntry(
      final int entryIndex,
      final BinarySerializer<K> keySerializer,
      final BinarySerializer<V> valueSerializer, BinarySerializerFactory serializerFactory) {
    var entryPosition =
        getIntValue(entryIndex * IntegerSerializer.INT_SIZE + POSITIONS_ARRAY_OFFSET);
    final var startEntryPosition = entryPosition;

    if (isLeaf()) {
      final int keySize;
      keySize = getObjectSizeInDirectMemory(keySerializer, serializerFactory, entryPosition);

      entryPosition += keySize;

      assert getByteValue(entryPosition) == 0;

      final var valueSize =
          getObjectSizeInDirectMemory(valueSerializer, serializerFactory,
              entryPosition + ByteSerializer.BYTE_SIZE);

      return getBinaryValue(startEntryPosition, keySize + valueSize + ByteSerializer.BYTE_SIZE);
    } else {
      entryPosition += 2 * LongSerializer.LONG_SIZE;
      final var keySize = getObjectSizeInDirectMemory(keySerializer, serializerFactory,
          entryPosition);

      return getBinaryValue(startEntryPosition, keySize + 2 * LongSerializer.LONG_SIZE);
    }
  }

  /**
   * Obtains the value stored under the given entry index in this bucket.
   *
   * @param entryIndex        the value entry index.
   * @param serializerFactory
   * @return the obtained value.
   */
  public SBTreeValue<V> getValue(
      final int entryIndex,
      final boolean isEncrypted,
      final BinarySerializer<K> keySerializer,
      final BinarySerializer<V> valueSerializer, BinarySerializerFactory serializerFactory) {
    assert isLeaf();

    var entryPosition =
        getIntValue(entryIndex * IntegerSerializer.INT_SIZE + POSITIONS_ARRAY_OFFSET);

    // skip key
    if (!isEncrypted) {
      entryPosition += getObjectSizeInDirectMemory(keySerializer, serializerFactory, entryPosition);
    } else {
      final var encryptedSize = getIntValue(entryPosition);
      entryPosition += IntegerSerializer.INT_SIZE + encryptedSize;
    }

    assert getByteValue(entryPosition) == 0;
    final var value =
        deserializeFromDirectMemory(valueSerializer, serializerFactory,
            entryPosition + ByteSerializer.BYTE_SIZE);

    return new SBTreeValue<>(false, -1, value);
  }

  byte[] getRawValue(
      final int entryIndex,
      final BinarySerializer<K> keySerializer,
      final BinarySerializer<V> valueSerializer, BinarySerializerFactory serializerFactory) {
    assert isLeaf();

    var entryPosition =
        getIntValue(entryIndex * IntegerSerializer.INT_SIZE + POSITIONS_ARRAY_OFFSET);
    entryPosition += getObjectSizeInDirectMemory(keySerializer, serializerFactory, entryPosition);

    assert getByteValue(entryPosition) == 0;

    final var valueSize =
        getObjectSizeInDirectMemory(valueSerializer, serializerFactory,
            entryPosition + ByteSerializer.BYTE_SIZE);
    return getBinaryValue(entryPosition + ByteSerializer.BYTE_SIZE, valueSize);
  }

  byte[] getRawKey(
      final int entryIndex, final boolean isEncrypted, final BinarySerializer<K> keySerializer,
      BinarySerializerFactory serializerFactory) {
    assert isLeaf();

    var entryPosition =
        getIntValue(entryIndex * IntegerSerializer.INT_SIZE + POSITIONS_ARRAY_OFFSET);

    byte[] rawKey;
    if (!isEncrypted) {
      final var keySize = getObjectSizeInDirectMemory(keySerializer, serializerFactory,
          entryPosition);
      rawKey = getBinaryValue(entryPosition, keySize);
    } else {
      final var encryptedSize = getIntValue(entryPosition);
      rawKey = getBinaryValue(entryPosition, IntegerSerializer.INT_SIZE + encryptedSize);
    }

    return rawKey;
  }

  public K getKey(
      final int index, final BinarySerializer<K> keySerializer,
      BinarySerializerFactory serializerFactory) {
    var entryPosition = getIntValue(index * IntegerSerializer.INT_SIZE + POSITIONS_ARRAY_OFFSET);

    if (!isLeaf()) {
      entryPosition += 2 * LongSerializer.LONG_SIZE;
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

    setIntValue(SIZE_OFFSET, rawEntries.size() + currentSize);
  }

  public void shrink(
      final int newSize,
      final BinarySerializer<K> keySerializer,
      final BinarySerializer<V> valueSerializer, BinarySerializerFactory serializerFactory) {
    final List<byte[]> rawEntries = new ArrayList<>(newSize);

    for (var i = 0; i < newSize; i++) {
      rawEntries.add(getRawEntry(i, keySerializer, valueSerializer, serializerFactory));
    }

    final var oldSize = getIntValue(SIZE_OFFSET);
    final List<byte[]> removedEntries;
    if (newSize == oldSize) {
      removedEntries = Collections.emptyList();
    } else {
      removedEntries = new ArrayList<>(oldSize - newSize);

      for (var i = newSize; i < oldSize; i++) {
        removedEntries.add(getRawEntry(i, keySerializer, valueSerializer, serializerFactory));
      }
    }

    setIntValue(FREE_POINTER_OFFSET, PAGE_SIZE);

    var index = 0;
    for (final var entry : rawEntries) {
      appendRawEntry(index, entry);
      index++;
    }

    setIntValue(SIZE_OFFSET, newSize);
  }

  public boolean addLeafEntry(
      final int index, final byte[] serializedKey, final byte[] serializedValue) {
    assert isLeaf();

    final var entrySize = serializedKey.length + serializedValue.length + ByteSerializer.BYTE_SIZE;
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
    setByteValue(freePointer + serializedKey.length, (byte) 0);
    setBinaryValue(freePointer + serializedKey.length + ByteSerializer.BYTE_SIZE, serializedValue);

    return true;
  }

  public boolean addNonLeafEntry(
      final int index,
      final byte[] key,
      final long leftChild,
      final long rightChild,
      final boolean updateNeighbours) {
    assert !isLeaf();

    final var entrySize = key.length + 2 * LongSerializer.LONG_SIZE;
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

    freePointer += setLongValue(freePointer, leftChild);
    freePointer += setLongValue(freePointer, rightChild);

    setBinaryValue(freePointer, key);

    size++;

    if (updateNeighbours && size > 1) {
      if (index < size - 1) {
        final var nextEntryPosition =
            getIntValue(POSITIONS_ARRAY_OFFSET + (index + 1) * IntegerSerializer.INT_SIZE);
        setLongValue(nextEntryPosition, rightChild);
      }

      if (index > 0) {
        final var prevEntryPosition =
            getIntValue(POSITIONS_ARRAY_OFFSET + (index - 1) * IntegerSerializer.INT_SIZE);
        setLongValue(prevEntryPosition + LongSerializer.LONG_SIZE, leftChild);
      }
    }

    return true;
  }

  private void appendRawEntry(final int index, final byte[] rawEntry) {
    var freePointer = getIntValue(FREE_POINTER_OFFSET);
    freePointer -= rawEntry.length;

    setIntValue(FREE_POINTER_OFFSET, freePointer);
    setIntValue(POSITIONS_ARRAY_OFFSET + index * IntegerSerializer.INT_SIZE, freePointer);

    setBinaryValue(freePointer, rawEntry);
  }

  public void updateValue(final int index, final byte[] value, final int keySize) {
    var entryPosition =
        getIntValue(index * IntegerSerializer.INT_SIZE + POSITIONS_ARRAY_OFFSET) + keySize;
    assert getByteValue(entryPosition) == 0;

    entryPosition += ByteSerializer.BYTE_SIZE;

    setBinaryValue(entryPosition, value);
  }

  public void setLeftSibling(long pageIndex) {
    setLongValue(LEFT_SIBLING_OFFSET, pageIndex);
  }

  public long getLeftSibling() {
    return getLongValue(LEFT_SIBLING_OFFSET);
  }

  public void setRightSibling(long pageIndex) {
    setLongValue(RIGHT_SIBLING_OFFSET, pageIndex);
  }

  public long getRightSibling() {
    return getLongValue(RIGHT_SIBLING_OFFSET);
  }

  public static final class SBTreeEntry<K, V> implements Comparable<SBTreeEntry<K, V>> {

    private final Comparator<? super K> comparator = DefaultComparator.INSTANCE;

    public final long leftChild;
    public final long rightChild;
    public final K key;
    public final SBTreeValue<V> value;

    public SBTreeEntry(long leftChild, long rightChild, K key, SBTreeValue<V> value) {
      this.leftChild = leftChild;
      this.rightChild = rightChild;
      this.key = key;
      this.value = value;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }

      final var that = (SBTreeEntry<?, ?>) o;

      if (leftChild != that.leftChild) {
        return false;
      }
      if (rightChild != that.rightChild) {
        return false;
      }
      if (!key.equals(that.key)) {
        return false;
      }
      if (value != null) {
        return value.equals(that.value);
      } else {
        return that.value == null;
      }
    }

    @Override
    public int hashCode() {
      var result = (int) (leftChild ^ (leftChild >>> 32));
      result = 31 * result + (int) (rightChild ^ (rightChild >>> 32));
      result = 31 * result + key.hashCode();
      result = 31 * result + (value != null ? value.hashCode() : 0);
      return result;
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
    public int compareTo(SBTreeEntry<K, V> other) {
      return comparator.compare(key, other.key);
    }
  }
}
