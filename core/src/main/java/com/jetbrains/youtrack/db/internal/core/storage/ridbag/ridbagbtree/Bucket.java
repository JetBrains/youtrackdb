package com.jetbrains.youtrack.db.internal.core.storage.ridbag.ridbagbtree;

import com.jetbrains.youtrack.db.internal.common.serialization.types.ByteSerializer;
import com.jetbrains.youtrack.db.internal.common.serialization.types.IntegerSerializer;
import com.jetbrains.youtrack.db.internal.common.serialization.types.LongSerializer;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.binary.BinarySerializerFactory;
import com.jetbrains.youtrack.db.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.base.DurablePage;
import java.util.ArrayList;
import java.util.List;

final class Bucket extends DurablePage {

  private static final int FREE_POINTER_OFFSET = NEXT_FREE_POSITION;
  private static final int SIZE_OFFSET = FREE_POINTER_OFFSET + IntegerSerializer.INT_SIZE;
  private static final int IS_LEAF_OFFSET = SIZE_OFFSET + IntegerSerializer.INT_SIZE;
  private static final int LEFT_SIBLING_OFFSET = IS_LEAF_OFFSET + ByteSerializer.BYTE_SIZE;
  private static final int RIGHT_SIBLING_OFFSET = LEFT_SIBLING_OFFSET + LongSerializer.LONG_SIZE;

  private static final int POSITIONS_ARRAY_OFFSET =
      RIGHT_SIBLING_OFFSET + LongSerializer.LONG_SIZE;

  public Bucket(final CacheEntry cacheEntry) {
    super(cacheEntry);
  }

  public void init(boolean isLeaf) {
    setFreePointer(MAX_PAGE_SIZE_BYTES);
    setSize(0);

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

  public int size() {
    return getSize();
  }

  public boolean isLeaf() {
    return getByteValue(IS_LEAF_OFFSET) > 0;
  }

  public int find(final EdgeKey key, BinarySerializerFactory serializerFactory) {
    var low = 0;
    var high = size() - 1;

    while (low <= high) {
      final var mid = (low + high) >>> 1;
      final var midKey = getKey(mid, serializerFactory);
      final var cmp = midKey.compareTo(key);

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

  public EdgeKey getKey(final int index, BinarySerializerFactory serializerFactory) {
    var entryPosition = getPointer(index);

    if (!isLeaf()) {
      entryPosition += 2 * IntegerSerializer.INT_SIZE;
    }

    return deserializeFromDirectMemory(EdgeKeySerializer.INSTANCE, serializerFactory,
        entryPosition);
  }

  private byte[] getRawKey(final int index, BinarySerializerFactory serializerFactory) {
    var entryPosition = getPointer(index);

    if (!isLeaf()) {
      entryPosition += 2 * IntegerSerializer.INT_SIZE;
    }

    final var keySize = getObjectSizeInDirectMemory(EdgeKeySerializer.INSTANCE, serializerFactory,
        entryPosition);
    return getBinaryValue(entryPosition, keySize);
  }

  public void removeLeafEntry(final int entryIndex, final int keySize, final int valueSize) {
    final var entryPosition = getPointer(entryIndex);

    final int entrySize;
    if (isLeaf()) {
      entrySize = keySize + valueSize;
    } else {
      throw new IllegalStateException("Remove is applies to leaf buckets only");
    }

    final var freePointer = getFreePointer();
    assert freePointer <= DurablePage.MAX_PAGE_SIZE_BYTES;
    assert freePointer + entrySize <= DurablePage.MAX_PAGE_SIZE_BYTES;

    var size = getSize();
    var pointers = getPointers();
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

    if (size > 0 && entryPosition > freePointer) {
      moveData(freePointer, freePointer + entrySize, entryPosition - freePointer);
    }

    setFreePointer(freePointer + entrySize);
  }

  public void removeNonLeafEntry(final int entryIndex, final byte[] key, final int prevChild) {
    if (isLeaf()) {
      throw new IllegalStateException("Remove is applied to non-leaf buckets only");
    }

    final var entryPosition = getPointer(entryIndex);
    final var entrySize = key.length + 2 * IntegerSerializer.INT_SIZE;
    var size = getSize();

    final var leftChild = getIntValue(entryPosition);
    final var rightChild = getIntValue(entryPosition + IntegerSerializer.INT_SIZE);

    var pointers = getPointers();
    var endSize = size - 1;
    if (entryIndex < size - 1) {
      for (var i = entryIndex + 1; i < size; i++) {
        if (pointers[i] < entryPosition) {
          pointers[i] += entrySize;
        }
      }
      setPointersOffset(entryIndex, pointers, entryIndex + 1);
      endSize = entryIndex;
    }

    size--;
    setSize(size);

    for (var i = 0; i < endSize; i++) {
      if (pointers[i] < entryPosition) {
        setPointer(i, pointers[i] + entrySize);
      }
    }

    final var freePointer = getFreePointer();
    assert freePointer <= DurablePage.MAX_PAGE_SIZE_BYTES;

    if (size > 0 && entryPosition > freePointer) {
      moveData(freePointer, freePointer + entrySize, entryPosition - freePointer);
    }

    assert freePointer + entrySize <= DurablePage.MAX_PAGE_SIZE_BYTES;
    setFreePointer(freePointer + entrySize);

    if (prevChild >= 0) {
      if (entryIndex > 0) {
        final var prevEntryPosition = getPointer(entryIndex - 1);
        setIntValue(prevEntryPosition + IntegerSerializer.INT_SIZE, prevChild);
      }

      if (entryIndex < size) {
        final var nextEntryPosition = getPointer(entryIndex);
        setIntValue(nextEntryPosition, prevChild);
      }
    }
  }

  public TreeEntry getEntry(final int entryIndex, BinarySerializerFactory serializerFactory) {
    var entryPosition = getPointer(entryIndex);

    if (isLeaf()) {
      final EdgeKey key;

      key = deserializeFromDirectMemory(EdgeKeySerializer.INSTANCE, serializerFactory,
          entryPosition);

      entryPosition += getObjectSizeInDirectMemory(EdgeKeySerializer.INSTANCE, serializerFactory,
          entryPosition);

      final int value = deserializeFromDirectMemory(IntSerializer.INSTANCE, serializerFactory,
          entryPosition);

      return new TreeEntry(-1, -1, key, value);
    } else {
      final var leftChild = getIntValue(entryPosition);
      entryPosition += IntegerSerializer.INT_SIZE;

      final var rightChild = getIntValue(entryPosition);
      entryPosition += IntegerSerializer.INT_SIZE;

      final var key = deserializeFromDirectMemory(EdgeKeySerializer.INSTANCE, serializerFactory,
          entryPosition);

      return new TreeEntry(leftChild, rightChild, key, -1);
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

  public int getValue(final int entryIndex, BinarySerializerFactory serializerFactory) {
    assert isLeaf();

    var entryPosition = getPointer(entryIndex);

    // skip key
    entryPosition += getObjectSizeInDirectMemory(EdgeKeySerializer.INSTANCE, serializerFactory,
        entryPosition);
    return deserializeFromDirectMemory(IntSerializer.INSTANCE, serializerFactory, entryPosition);
  }

  byte[] getRawValue(final int entryIndex, BinarySerializerFactory serializerFactory) {
    assert isLeaf();

    var entryPosition = getPointer(entryIndex);

    // skip key
    entryPosition += getObjectSizeInDirectMemory(EdgeKeySerializer.INSTANCE, serializerFactory,
        entryPosition);

    final var intSize = getObjectSizeInDirectMemory(IntSerializer.INSTANCE, serializerFactory,
        entryPosition);
    return getBinaryValue(entryPosition, intSize);
  }

  public void addAll(final List<byte[]> rawEntries) {
    final var currentSize = size();
    for (var i = 0; i < rawEntries.size(); i++) {
      appendRawEntry(i + currentSize, rawEntries.get(i));
    }

    setSize(rawEntries.size() + currentSize);
  }

  private void appendRawEntry(final int index, final byte[] rawEntry) {
    var freePointer = getFreePointer();
    assert freePointer <= DurablePage.MAX_PAGE_SIZE_BYTES;

    freePointer -= rawEntry.length;

    assert freePointer <= DurablePage.MAX_PAGE_SIZE_BYTES;

    setFreePointer(freePointer);
    setPointer(index, freePointer);

    setBinaryValue(freePointer, rawEntry);
  }

  public void shrink(final int newSize, BinarySerializerFactory serializerFactory) {
    final List<byte[]> rawEntries = new ArrayList<>(newSize);

    for (var i = 0; i < newSize; i++) {
      rawEntries.add(getRawEntry(i, serializerFactory));
    }

    setFreePointer(MAX_PAGE_SIZE_BYTES);

    for (var i = 0; i < newSize; i++) {
      appendRawEntry(i, rawEntries.get(i));
    }

    setSize(newSize);
  }

  public byte[] getRawEntry(final int entryIndex, BinarySerializerFactory serializerFactory) {
    var entryPosition = getPointer(entryIndex);
    final var startEntryPosition = entryPosition;

    if (isLeaf()) {
      final var keySize = getObjectSizeInDirectMemory(EdgeKeySerializer.INSTANCE, serializerFactory,
          entryPosition);
      final var valueSize =
          getObjectSizeInDirectMemory(IntSerializer.INSTANCE, serializerFactory,
              startEntryPosition + keySize);
      return getBinaryValue(startEntryPosition, keySize + valueSize);
    } else {
      entryPosition += 2 * IntegerSerializer.INT_SIZE;

      final var keySize = getObjectSizeInDirectMemory(EdgeKeySerializer.INSTANCE, serializerFactory,
          entryPosition);

      return getBinaryValue(startEntryPosition, keySize + 2 * IntegerSerializer.INT_SIZE);
    }
  }

  public boolean addLeafEntry(
      final int index, final byte[] serializedKey, final byte[] serializedValue) {
    final var entrySize = serializedKey.length + serializedValue.length;

    assert isLeaf();
    final var size = getSize();

    var freePointer = getFreePointer();
    assert freePointer <= DurablePage.MAX_PAGE_SIZE_BYTES;

    if (doesOverflow(entrySize, 1)) {
      return false;
    }

    if (index <= size - 1) {
      shiftPointers(index, index + 1, size - index);
    }

    freePointer -= entrySize;

    assert freePointer <= DurablePage.MAX_PAGE_SIZE_BYTES;

    setFreePointer(freePointer);
    setPointer(index, freePointer);
    setSize(size + 1);

    setBinaryValue(freePointer, serializedKey);
    setBinaryValue(freePointer + serializedKey.length, serializedValue);

    return true;
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
    var freePointer = getFreePointer();
    assert freePointer <= DurablePage.MAX_PAGE_SIZE_BYTES;

    if (doesOverflow(entrySize, 1)) {
      return false;
    }

    if (index <= size - 1) {
      shiftPointers(index, index + 1, size - index);
    }

    freePointer -= entrySize;

    assert freePointer <= DurablePage.MAX_PAGE_SIZE_BYTES;

    setFreePointer(freePointer);
    setPointer(index, freePointer);
    setSize(size + 1);

    freePointer += setIntValue(freePointer, leftChild);
    freePointer += setIntValue(freePointer, rightChild);

    setBinaryValue(freePointer, key);

    size++;

    if (updateNeighbors && size > 1) {
      if (index < size - 1) {
        final var nextEntryPosition = getPointer(index + 1);
        setIntValue(nextEntryPosition, rightChild);
      }

      if (index > 0) {
        final var prevEntryPosition = getPointer(index - 1);
        setIntValue(prevEntryPosition + IntegerSerializer.INT_SIZE, leftChild);
      }
    }

    return true;
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

  public void updateValue(final int index, final byte[] value, final int keySize,
      BinarySerializerFactory serializerFactory) {
    final var entryPosition = getPointer(index) + keySize;

    final var valueSize = getObjectSizeInDirectMemory(IntSerializer.INSTANCE, serializerFactory,
        entryPosition);
    if (valueSize == value.length) {
      setBinaryValue(entryPosition, value);
    } else {
      final var rawKey = getRawKey(index, serializerFactory);

      removeLeafEntry(index, keySize, valueSize);
      addLeafEntry(index, rawKey, value);
    }
  }

  public void setFreePointer(int value) {
    setIntValue(FREE_POINTER_OFFSET, value);
  }

  public int getFreePointer() {
    return getIntValue(FREE_POINTER_OFFSET);
  }

  public int getSize() {
    return getIntValue(SIZE_OFFSET);
  }

  public void setSize(int value) {
    setIntValue(SIZE_OFFSET, value);
  }

  public int getPointer(final int index) {
    return getIntValue(index * IntegerSerializer.INT_SIZE + POSITIONS_ARRAY_OFFSET);
  }

  public int[] getPointers() {
    var size = getSize();
    return getIntArray(POSITIONS_ARRAY_OFFSET, size);
  }

  public void setPointer(final int index, int value) {
    setIntValue(index * IntegerSerializer.INT_SIZE + POSITIONS_ARRAY_OFFSET, value);
  }

  public void setPointersOffset(int position, int[] pointers, int pointersOffset) {
    setIntArray(
        POSITIONS_ARRAY_OFFSET + position * IntegerSerializer.INT_SIZE, pointers, pointersOffset);
  }

  public void shiftPointers(int from, int to, int size) {
    moveData(
        POSITIONS_ARRAY_OFFSET + from * IntegerSerializer.INT_SIZE,
        POSITIONS_ARRAY_OFFSET + to * IntegerSerializer.INT_SIZE,
        size * IntegerSerializer.INT_SIZE);
  }

  private boolean doesOverflow(int requiredDataSpace, int requirePointerSpace) {
    var size = getSize();
    var freePointer = getFreePointer();
    return freePointer - requiredDataSpace
        < (size + requirePointerSpace) * IntegerSerializer.INT_SIZE + POSITIONS_ARRAY_OFFSET;
  }
}
