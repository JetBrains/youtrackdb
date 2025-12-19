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

package com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.multivalue.v2;

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
 * @since 8/7/13
 */
public final class CellBTreeMultiValueV2Bucket<K> extends DurablePage {

  private static final int NEXT_ITEM_POINTER_OFFSET = 0;
  private static final int EMBEDDED_ENTRIES_COUNT_OFFSET =
      NEXT_ITEM_POINTER_OFFSET + IntegerSerializer.INT_SIZE;
  private static final int ENTRIES_COUNT_OFFSET =
      EMBEDDED_ENTRIES_COUNT_OFFSET + ByteSerializer.BYTE_SIZE;
  private static final int M_ID_OFFSET = ENTRIES_COUNT_OFFSET + IntegerSerializer.INT_SIZE;
  private static final int COLLECTION_ID_OFFSET = M_ID_OFFSET + LongSerializer.LONG_SIZE;
  private static final int COLLECTION_POSITION_OFFSET =
      COLLECTION_ID_OFFSET + ShortSerializer.SHORT_SIZE;
  private static final int KEY_OFFSET = COLLECTION_POSITION_OFFSET + LongSerializer.LONG_SIZE;

  private static final int EMBEDDED_ITEMS_THRESHOLD = 64;
  private static final int RID_SIZE = ShortSerializer.SHORT_SIZE + LongSerializer.LONG_SIZE;
  private static final int SINGLE_ELEMENT_LINKED_ITEM_SIZE =
      IntegerSerializer.INT_SIZE + RID_SIZE + ByteSerializer.BYTE_SIZE;

  private static final int FREE_POINTER_OFFSET = NEXT_FREE_POSITION;
  private static final int SIZE_OFFSET = FREE_POINTER_OFFSET + IntegerSerializer.INT_SIZE;
  private static final int IS_LEAF_OFFSET = SIZE_OFFSET + IntegerSerializer.INT_SIZE;
  private static final int LEFT_SIBLING_OFFSET = IS_LEAF_OFFSET + ByteSerializer.BYTE_SIZE;
  private static final int RIGHT_SIBLING_OFFSET = LEFT_SIBLING_OFFSET + LongSerializer.LONG_SIZE;

  private static final int POSITIONS_ARRAY_OFFSET =
      RIGHT_SIBLING_OFFSET + LongSerializer.LONG_SIZE;
  private final Comparator<? super K> comparator = DefaultComparator.INSTANCE;

  public CellBTreeMultiValueV2Bucket(final CacheEntry cacheEntry) {
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

  boolean isEmpty() {
    return size() == 0;
  }

  int find(final K key, final BinarySerializer<K> keySerializer,
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

  private void removeMainLeafEntry(
      final int entryIndex, final int entryPosition, final int keySize) {
    int nextItem;
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
    final var entrySize =
        LongSerializer.LONG_SIZE
            + 2 * IntegerSerializer.INT_SIZE
            + ByteSerializer.BYTE_SIZE
            + RID_SIZE
            + keySize;

    var moved = false;
    if (size > 0 && entryPosition > freePointer) {
      moveData(freePointer, freePointer + entrySize, entryPosition - freePointer);
      moved = true;
    }

    setIntValue(FREE_POINTER_OFFSET, freePointer + entrySize);

    if (moved) {
      var currentPositionOffset = POSITIONS_ARRAY_OFFSET;

      for (var i = 0; i < size; i++) {
        final var currentEntryPosition = getIntValue(currentPositionOffset);
        final int updatedEntryPosition;

        if (currentEntryPosition < entryPosition) {
          updatedEntryPosition = currentEntryPosition + entrySize;
          setIntValue(currentPositionOffset, updatedEntryPosition);
        } else {
          updatedEntryPosition = currentEntryPosition;
        }

        nextItem = getIntValue(updatedEntryPosition);
        if (nextItem > 0 && nextItem < entryPosition) {
          // update reference to the first item of linked list
          setIntValue(updatedEntryPosition, nextItem + entrySize);

          updateAllLinkedListReferences(nextItem, entryPosition, entrySize);
        }

        currentPositionOffset += IntegerSerializer.INT_SIZE;
      }
    }
  }

  public int removeLeafEntry(final int entryIndex, final RID value) {
    assert isLeaf();

    final var entryPosition =
        getIntValue(POSITIONS_ARRAY_OFFSET + entryIndex * IntegerSerializer.INT_SIZE);

    var position = entryPosition;
    var nextItem = getIntValue(position);
    position += IntegerSerializer.INT_SIZE;

    final var embeddedEntriesCountPosition = position;
    final int embeddedEntriesCount = getByteValue(position);
    position += ByteSerializer.BYTE_SIZE;

    final var entriesCountPosition = position;
    final var entriesCount = getIntValue(entriesCountPosition);
    position += IntegerSerializer.INT_SIZE;
    position += LongSerializer.LONG_SIZE; // mId

    // only single element in list
    if (nextItem == -1) {
      final var collectionIdPosition = position;
      final int collectionId = getShortValue(collectionIdPosition);
      position += ShortSerializer.SHORT_SIZE;

      final var collectionPosition = getLongValue(position);

      if (collectionId != value.getCollectionId()) {
        return -1;
      }

      if (collectionPosition == value.getCollectionPosition()) {
        setShortValue(collectionIdPosition, (short) -1);

        assert embeddedEntriesCount == 1;

        setByteValue(embeddedEntriesCountPosition, (byte) (0));
        setIntValue(entriesCountPosition, entriesCount - 1);

        return entriesCount - 1;
      }
    } else {
      int collectionId = getShortValue(position);
      position += ShortSerializer.SHORT_SIZE;

      var collectionPosition = getLongValue(position);
      if (collectionId == value.getCollectionId() && collectionPosition == value.getCollectionPosition()) {
        final var nextNextItem = getIntValue(nextItem);
        final var nextItemSize = 0xFF & getByteValue(nextItem + IntegerSerializer.INT_SIZE);

        final var nextValue =
            getBinaryValue(
                nextItem + IntegerSerializer.INT_SIZE + ByteSerializer.BYTE_SIZE, RID_SIZE);

        assert nextItemSize > 0;
        final var freePointer = getIntValue(FREE_POINTER_OFFSET);
        if (nextItemSize == 1) {
          setIntValue(entryPosition, nextNextItem);
          setIntValue(
              FREE_POINTER_OFFSET,
              freePointer + IntegerSerializer.INT_SIZE + RID_SIZE + ByteSerializer.BYTE_SIZE);
        } else {
          setByteValue(nextItem + IntegerSerializer.INT_SIZE, (byte) (nextItemSize - 1));
          setIntValue(FREE_POINTER_OFFSET, freePointer + RID_SIZE);
        }

        setBinaryValue(
            entryPosition
                + 2 * IntegerSerializer.INT_SIZE
                + ByteSerializer.BYTE_SIZE
                + LongSerializer.LONG_SIZE,
            nextValue);

        if (nextItem > freePointer || nextItemSize > 1) {
          if (nextItemSize == 1) {
            moveData(
                freePointer, freePointer + SINGLE_ELEMENT_LINKED_ITEM_SIZE, nextItem - freePointer);
          } else {
            moveData(
                freePointer,
                freePointer + RID_SIZE,
                nextItem + IntegerSerializer.INT_SIZE + ByteSerializer.BYTE_SIZE - freePointer);
          }

          final var diff =
              nextItemSize > 1
                  ? RID_SIZE
                  : IntegerSerializer.INT_SIZE + ByteSerializer.BYTE_SIZE + RID_SIZE;

          final var size = getIntValue(SIZE_OFFSET);
          var currentPositionOffset = POSITIONS_ARRAY_OFFSET;

          for (var i = 0; i < size; i++) {
            final var currentEntryPosition = getIntValue(currentPositionOffset);
            final int updatedEntryPosition;

            if (currentEntryPosition < nextItem) {
              updatedEntryPosition = currentEntryPosition + diff;
              setIntValue(currentPositionOffset, updatedEntryPosition);
            } else {
              updatedEntryPosition = currentEntryPosition;
            }

            final var currentNextItem = getIntValue(updatedEntryPosition);
            if (currentNextItem > 0 && currentNextItem < nextItem + diff) {
              // update reference to the first item of linked list
              setIntValue(updatedEntryPosition, currentNextItem + diff);

              updateAllLinkedListReferences(currentNextItem, nextItem + diff, diff);
            }

            currentPositionOffset += IntegerSerializer.INT_SIZE;
          }
        }

        setByteValue(embeddedEntriesCountPosition, (byte) (embeddedEntriesCount - 1));
        setIntValue(entriesCountPosition, entriesCount - 1);

        return entriesCount - 1;
      } else {
        var prevItem = entryPosition;

        while (nextItem > 0) {
          final var nextNextItem = getIntValue(nextItem);
          final var nextItemSize = 0xFF & getByteValue(nextItem + IntegerSerializer.INT_SIZE);

          if (nextItemSize == 1) {
            collectionId =
                getShortValue(nextItem + IntegerSerializer.INT_SIZE + ByteSerializer.BYTE_SIZE);
            collectionPosition =
                getLongValue(
                    nextItem
                        + IntegerSerializer.INT_SIZE
                        + ByteSerializer.BYTE_SIZE
                        + ShortSerializer.SHORT_SIZE);

            if (collectionId == value.getCollectionId()
                && collectionPosition == value.getCollectionPosition()) {
              setIntValue(prevItem, nextNextItem);

              final var freePointer = getIntValue(FREE_POINTER_OFFSET);
              setIntValue(FREE_POINTER_OFFSET, freePointer + SINGLE_ELEMENT_LINKED_ITEM_SIZE);

              if (nextItem > freePointer) {
                moveData(
                    freePointer,
                    freePointer + SINGLE_ELEMENT_LINKED_ITEM_SIZE,
                    nextItem - freePointer);

                final var size = getIntValue(SIZE_OFFSET);
                var currentPositionOffset = POSITIONS_ARRAY_OFFSET;

                for (var i = 0; i < size; i++) {
                  final var currentEntryPosition = getIntValue(currentPositionOffset);
                  final int updatedEntryPosition;

                  if (currentEntryPosition < nextItem) {
                    updatedEntryPosition = currentEntryPosition + SINGLE_ELEMENT_LINKED_ITEM_SIZE;
                    setIntValue(currentPositionOffset, updatedEntryPosition);
                  } else {
                    updatedEntryPosition = currentEntryPosition;
                  }

                  final var currentNextItem = getIntValue(updatedEntryPosition);
                  if (currentNextItem > 0 && currentNextItem < nextItem) {
                    // update reference to the first item of linked list
                    setIntValue(
                        updatedEntryPosition, currentNextItem + SINGLE_ELEMENT_LINKED_ITEM_SIZE);

                    updateAllLinkedListReferences(
                        currentNextItem, nextItem, SINGLE_ELEMENT_LINKED_ITEM_SIZE);
                  }

                  currentPositionOffset += IntegerSerializer.INT_SIZE;
                }
              }

              setByteValue(embeddedEntriesCountPosition, (byte) (embeddedEntriesCount - 1));
              setIntValue(entriesCountPosition, entriesCount - 1);

              return entriesCount - 1;
            }
          } else {
            for (var i = 0; i < nextItemSize; i++) {
              collectionId =
                  getShortValue(
                      nextItem
                          + IntegerSerializer.INT_SIZE
                          + ByteSerializer.BYTE_SIZE
                          + i * RID_SIZE);
              collectionPosition =
                  getLongValue(
                      nextItem
                          + IntegerSerializer.INT_SIZE
                          + ShortSerializer.SHORT_SIZE
                          + ByteSerializer.BYTE_SIZE
                          + i * RID_SIZE);

              if (collectionId == value.getCollectionId()
                  && collectionPosition == value.getCollectionPosition()) {
                final var freePointer = getIntValue(FREE_POINTER_OFFSET);
                setIntValue(FREE_POINTER_OFFSET, freePointer + RID_SIZE);

                setByteValue(nextItem + IntegerSerializer.INT_SIZE, (byte) (nextItemSize - 1));

                moveData(
                    freePointer,
                    freePointer + RID_SIZE,
                    nextItem
                        + IntegerSerializer.INT_SIZE
                        + ByteSerializer.BYTE_SIZE
                        + i * RID_SIZE
                        - freePointer);

                final var size = getIntValue(SIZE_OFFSET);
                var currentPositionOffset = POSITIONS_ARRAY_OFFSET;

                for (var n = 0; n < size; n++) {
                  final var currentEntryPosition = getIntValue(currentPositionOffset);
                  final int updatedEntryPosition;

                  if (currentEntryPosition < nextItem) {
                    updatedEntryPosition = currentEntryPosition + RID_SIZE;
                    setIntValue(currentPositionOffset, updatedEntryPosition);
                  } else {
                    updatedEntryPosition = currentEntryPosition;
                  }

                  final var currentNextItem = getIntValue(updatedEntryPosition);
                  if (currentNextItem > 0 && currentNextItem < nextItem + RID_SIZE) {
                    // update reference to the first item of linked list
                    setIntValue(updatedEntryPosition, currentNextItem + RID_SIZE);

                    updateAllLinkedListReferences(currentNextItem, nextItem + RID_SIZE, RID_SIZE);
                  }

                  currentPositionOffset += IntegerSerializer.INT_SIZE;
                }

                setByteValue(embeddedEntriesCountPosition, (byte) (embeddedEntriesCount - 1));
                setIntValue(entriesCountPosition, entriesCount - 1);

                return entriesCount - 1;
              }
            }
          }

          prevItem = nextItem;
          nextItem = nextNextItem;
        }
      }
    }

    return -1;
  }

  boolean hasExternalEntries(final int entryIndex) {
    final var entryPosition =
        getIntValue(POSITIONS_ARRAY_OFFSET + entryIndex * IntegerSerializer.INT_SIZE);
    final int embeddedEntriesCount = getByteValue(entryPosition + IntegerSerializer.INT_SIZE);
    final var entriesCount =
        getIntValue(entryPosition + IntegerSerializer.INT_SIZE + ByteSerializer.BYTE_SIZE);

    assert entriesCount >= embeddedEntriesCount;
    return entriesCount > embeddedEntriesCount;
  }

  long getMid(final int entryIndex) {
    final var entryPosition =
        getIntValue(POSITIONS_ARRAY_OFFSET + entryIndex * IntegerSerializer.INT_SIZE);
    return getLongValue(
        entryPosition + 2 * IntegerSerializer.INT_SIZE + ByteSerializer.BYTE_SIZE);
  }

  public boolean decrementEntriesCount(final int entryIndex) {
    final var entryPosition =
        getIntValue(POSITIONS_ARRAY_OFFSET + entryIndex * IntegerSerializer.INT_SIZE);
    final var entriesCount =
        getIntValue(entryPosition + IntegerSerializer.INT_SIZE + ByteSerializer.BYTE_SIZE);

    setIntValue(
        entryPosition + IntegerSerializer.INT_SIZE + ByteSerializer.BYTE_SIZE, entriesCount - 1);

    return entriesCount == 1;
  }

  public void removeMainLeafEntry(final int entryIndex, final int keySize) {
    final var entryPosition =
        getIntValue(POSITIONS_ARRAY_OFFSET + entryIndex * IntegerSerializer.INT_SIZE);
    removeMainLeafEntry(entryIndex, entryPosition, keySize);
  }

  public void incrementEntriesCount(final int entryIndex) {
    final var entryPosition =
        getIntValue(POSITIONS_ARRAY_OFFSET + entryIndex * IntegerSerializer.INT_SIZE);
    final var entriesCount =
        getIntValue(entryPosition + IntegerSerializer.INT_SIZE + ByteSerializer.BYTE_SIZE);
    setIntValue(
        entryPosition + IntegerSerializer.INT_SIZE + ByteSerializer.BYTE_SIZE, entriesCount + 1);
  }

  private void updateAllLinkedListReferences(
      final int firstItem, final int boundary, final int diffSize) {
    var currentItem = firstItem + diffSize;

    while (true) {
      final var nextItem = getIntValue(currentItem);

      if (nextItem > 0 && nextItem < boundary) {
        setIntValue(currentItem, nextItem + diffSize);
        currentItem = nextItem + diffSize;
      } else {
        return;
      }
    }
  }

  public int size() {
    return getIntValue(SIZE_OFFSET);
  }

  public LeafEntry getLeafEntry(
      final int entryIndex, final BinarySerializer<K> keySerializer,
      BinarySerializerFactory serializerFactory) {
    assert isLeaf();

    var entryPosition =
        getIntValue(entryIndex * IntegerSerializer.INT_SIZE + POSITIONS_ARRAY_OFFSET);

    var nextItem = getIntValue(entryPosition);
    entryPosition += IntegerSerializer.INT_SIZE;

    final int embeddedEntriesCount = getByteValue(entryPosition);
    entryPosition += ByteSerializer.BYTE_SIZE;

    final var entriesCount = getIntValue(entryPosition);
    entryPosition += IntegerSerializer.INT_SIZE;

    final var mId = getLongValue(entryPosition);
    entryPosition += LongSerializer.LONG_SIZE;

    final List<RID> values = new ArrayList<>(entriesCount);

    int collectionId = getShortValue(entryPosition);
    entryPosition += ShortSerializer.SHORT_SIZE;

    if (collectionId >= 0) {
      final var collectionPosition = getLongValue(entryPosition);
      entryPosition += LongSerializer.LONG_SIZE;

      values.add(new RecordId(collectionId, collectionPosition));
    } else {
      entryPosition += LongSerializer.LONG_SIZE;
    }

    final var keySize = getObjectSizeInDirectMemory(keySerializer, serializerFactory,
        entryPosition);
    final var key = getBinaryValue(entryPosition, keySize);

    while (nextItem > 0) {
      final var nextNextItem = getIntValue(nextItem);
      final var nextItemSize = 0xFF & getByteValue(nextItem + IntegerSerializer.INT_SIZE);

      for (var i = 0; i < nextItemSize; i++) {
        collectionId =
            getShortValue(
                nextItem + IntegerSerializer.INT_SIZE + ByteSerializer.BYTE_SIZE + i * RID_SIZE);
        final var collectionPosition =
            getLongValue(
                nextItem
                    + ShortSerializer.SHORT_SIZE
                    + IntegerSerializer.INT_SIZE
                    + ByteSerializer.BYTE_SIZE
                    + i * RID_SIZE);

        values.add(new RecordId(collectionId, collectionPosition));
      }

      nextItem = nextNextItem;
    }

    assert values.size() == embeddedEntriesCount;

    return new LeafEntry(key, mId, values, entriesCount);
  }

  public NonLeafEntry getNonLeafEntry(
      final int entryIndex, final BinarySerializer<K> keySerializer,
      BinarySerializerFactory serializerFactory) {
    assert !isLeaf();

    var entryPosition =
        getIntValue(entryIndex * IntegerSerializer.INT_SIZE + POSITIONS_ARRAY_OFFSET);

    final var leftChild = getIntValue(entryPosition);
    entryPosition += IntegerSerializer.INT_SIZE;

    final var rightChild = getIntValue(entryPosition);
    entryPosition += IntegerSerializer.INT_SIZE;

    final byte[] key;

    final var keySize = getObjectSizeInDirectMemory(keySerializer, serializerFactory,
        entryPosition);
    key = getBinaryValue(entryPosition, keySize);

    return new NonLeafEntry(key, leftChild, rightChild);
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

  public K getKey(
      final int index, final BinarySerializer<K> keySerializer,
      BinarySerializerFactory serializerFactory) {
    var entryPosition = getIntValue(index * IntegerSerializer.INT_SIZE + POSITIONS_ARRAY_OFFSET);

    if (!isLeaf()) {
      entryPosition += 2 * IntegerSerializer.INT_SIZE;
    } else {
      entryPosition +=
          2 * IntegerSerializer.INT_SIZE
              + ByteSerializer.BYTE_SIZE
              + LongSerializer.LONG_SIZE
              + RID_SIZE;
    }

    return deserializeFromDirectMemory(keySerializer, serializerFactory, entryPosition);
  }

  byte[] getRawKey(
      final int index, final BinarySerializer<K> keySerializer,
      BinarySerializerFactory serializerFactory) {
    var entryPosition = getIntValue(index * IntegerSerializer.INT_SIZE + POSITIONS_ARRAY_OFFSET);

    if (!isLeaf()) {
      entryPosition += 2 * IntegerSerializer.INT_SIZE;
    } else {
      entryPosition +=
          2 * IntegerSerializer.INT_SIZE
              + ByteSerializer.BYTE_SIZE
              + LongSerializer.LONG_SIZE
              + RID_SIZE;
    }

    final var keySize = getObjectSizeInDirectMemory(keySerializer, serializerFactory,
        entryPosition);
    return getBinaryValue(entryPosition, keySize);
  }

  public boolean isLeaf() {
    return getByteValue(IS_LEAF_OFFSET) > 0;
  }

  public void addAll(
      final List<? extends Entry> entries) {
    final var currentSize = size();

    final var isLeaf = isLeaf();
    if (!isLeaf) {
      for (var i = 0; i < entries.size(); i++) {
        final var entry = (NonLeafEntry) entries.get(i);
        doAddNonLeafEntry(i + currentSize, entry.key, entry.leftChild, entry.rightChild, false);
      }
    } else {
      for (var i = 0; i < entries.size(); i++) {
        final var entry = (LeafEntry) entries.get(i);
        final var key = entry.key;
        final var values = entry.values;

        if (!values.isEmpty()) {
          doCreateMainLeafEntry(i + currentSize, key, values.get(0), entry.mId);
        } else {
          doCreateMainLeafEntry(i + currentSize, key, null, entry.mId);
        }

        if (values.size() > 1) {
          appendNewLeafEntries(
              i + currentSize, values.subList(1, values.size()), entry.entriesCount);
        }
      }
    }

    setIntValue(SIZE_OFFSET, currentSize + entries.size());
  }

  public void shrink(
      final int newSize, final BinarySerializer<K> keySerializer,
      BinarySerializerFactory serializerFactory) {
    final var isLeaf = isLeaf();
    final var currentSize = size();
    if (isLeaf) {
      final List<LeafEntry> entriesToAdd = new ArrayList<>(newSize);

      for (var i = 0; i < newSize; i++) {
        entriesToAdd.add(getLeafEntry(i, keySerializer, serializerFactory));
      }

      final List<LeafEntry> entriesToRemove = new ArrayList<>(currentSize - newSize);
      for (var i = newSize; i < currentSize; i++) {
        entriesToRemove.add(getLeafEntry(i, keySerializer, serializerFactory));
      }

      setIntValue(FREE_POINTER_OFFSET, MAX_PAGE_SIZE_BYTES);
      setIntValue(SIZE_OFFSET, 0);

      var index = 0;
      for (final var entry : entriesToAdd) {
        final var key = entry.key;
        final var values = entry.values;

        if (!values.isEmpty()) {
          doCreateMainLeafEntry(index, key, values.get(0), entry.mId);
        } else {
          doCreateMainLeafEntry(index, key, null, entry.mId);
        }

        if (values.size() > 1) {
          appendNewLeafEntries(index, values.subList(1, values.size()), entry.entriesCount);
        }

        index++;
      }

    } else {
      final List<NonLeafEntry> entries = new ArrayList<>(newSize);

      for (var i = 0; i < newSize; i++) {
        entries.add(getNonLeafEntry(i, keySerializer, serializerFactory));
      }

      final List<NonLeafEntry> entriesToRemove = new ArrayList<>(currentSize - newSize);
      for (var i = newSize; i < currentSize; i++) {
        entriesToRemove.add(getNonLeafEntry(i, keySerializer, serializerFactory));
      }

      setIntValue(FREE_POINTER_OFFSET, MAX_PAGE_SIZE_BYTES);
      setIntValue(SIZE_OFFSET, 0);

      var index = 0;
      for (final var entry : entries) {
        doAddNonLeafEntry(index, entry.key, entry.leftChild, entry.rightChild, false);
        index++;
      }
    }
  }

  public boolean createMainLeafEntry(
      final int index, final byte[] serializedKey, final RID value, final long mId) {
    return !doCreateMainLeafEntry(index, serializedKey, value, mId);
  }

  private boolean doCreateMainLeafEntry(int index, byte[] serializedKey, RID value, long mId) {
    assert isLeaf();

    final var entrySize =
        IntegerSerializer.INT_SIZE
            + ByteSerializer.BYTE_SIZE
            + IntegerSerializer.INT_SIZE
            + LongSerializer.LONG_SIZE
            + RID_SIZE
            + serializedKey
            .length; // next item pointer + embedded entries count- entries count + mid + rid +
    // key

    final var size = getIntValue(SIZE_OFFSET);

    var freePointer = getIntValue(FREE_POINTER_OFFSET);
    if (freePointer - entrySize
        < (size + 1) * IntegerSerializer.INT_SIZE + POSITIONS_ARRAY_OFFSET) {
      return true;
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

    freePointer += setIntValue(freePointer, -1); // next item pointer

    if (value != null) {
      freePointer += setByteValue(freePointer, (byte) 1); // embedded entries count
      freePointer += setIntValue(freePointer, 1); // entries count
    } else {
      freePointer += setByteValue(freePointer, (byte) 0); // embedded entries count
      freePointer += setIntValue(freePointer, 0); // entries count
    }

    freePointer += setLongValue(freePointer, mId); // mId

    if (value != null) {
      freePointer += setShortValue(freePointer, (short) value.getCollectionId()); // rid
      freePointer += setLongValue(freePointer, value.getCollectionPosition());
    } else {
      freePointer += setShortValue(freePointer, (short) -1); // rid
      freePointer += setLongValue(freePointer, -1);
    }

    setBinaryValue(freePointer, serializedKey); // key
    return false;
  }

  public long appendNewLeafEntry(final int index, final RID value) {
    assert isLeaf();

    final var entryPosition =
        getIntValue(index * IntegerSerializer.INT_SIZE + POSITIONS_ARRAY_OFFSET);
    final var nextItem = getIntValue(entryPosition);
    final int embeddedEntriesCount = getByteValue(entryPosition + IntegerSerializer.INT_SIZE);
    final var entriesCount =
        getIntValue(entryPosition + ByteSerializer.BYTE_SIZE + IntegerSerializer.INT_SIZE);
    final var mId =
        getLongValue(entryPosition + ByteSerializer.BYTE_SIZE + 2 * IntegerSerializer.INT_SIZE);

    if (embeddedEntriesCount < EMBEDDED_ITEMS_THRESHOLD) {
      if (embeddedEntriesCount > 0) {
        final var itemSize =
            IntegerSerializer.INT_SIZE
                + RID_SIZE
                + ByteSerializer.BYTE_SIZE; // next item pointer + RID + size
        var freePointer = getIntValue(FREE_POINTER_OFFSET);

        final var size = getIntValue(SIZE_OFFSET);

        if (freePointer - itemSize < size * IntegerSerializer.INT_SIZE + POSITIONS_ARRAY_OFFSET) {
          return -2;
        }

        freePointer -= itemSize;
        setIntValue(entryPosition, freePointer); // update list header

        freePointer += setIntValue(freePointer, nextItem); // next item pointer
        freePointer += setByteValue(freePointer, (byte) 1); // size
        freePointer += setShortValue(freePointer, (short) value.getCollectionId()); // rid
        freePointer += setLongValue(freePointer, value.getCollectionPosition());

        freePointer -= itemSize;
        setIntValue(FREE_POINTER_OFFSET, freePointer);
      } else {
        setShortValue(entryPosition + COLLECTION_ID_OFFSET, (short) value.getCollectionId());
        setLongValue(entryPosition + COLLECTION_POSITION_OFFSET, value.getCollectionPosition());
      }

      setByteValue(entryPosition + IntegerSerializer.INT_SIZE, (byte) (embeddedEntriesCount + 1));
    } else {
      return mId;
    }

    setIntValue(
        entryPosition + ByteSerializer.BYTE_SIZE + IntegerSerializer.INT_SIZE, entriesCount + 1);

    return -1;
  }

  private void appendNewLeafEntries(
      final int index, final List<RID> values, final int entriesCount) {
    assert isLeaf();

    final var entryPosition =
        getIntValue(index * IntegerSerializer.INT_SIZE + POSITIONS_ARRAY_OFFSET);
    final int embeddedEntriesCount = getByteValue(entryPosition + IntegerSerializer.INT_SIZE);

    if (values.size() > EMBEDDED_ITEMS_THRESHOLD - embeddedEntriesCount) {
      throw new IllegalStateException(
          "Can not insert "
              + values.size()
              + " embedded entries, limit is "
              + (EMBEDDED_ITEMS_THRESHOLD - embeddedEntriesCount));
    }

    var startIndex = 0;
    if (embeddedEntriesCount == 0) {
      final var rid = values.get(0);

      setShortValue(
          entryPosition + 2 * IntegerSerializer.INT_SIZE + ByteSerializer.BYTE_SIZE,
          (short) rid.getCollectionId()); // rid
      setLongValue(
          entryPosition
              + 2 * IntegerSerializer.INT_SIZE
              + ByteSerializer.BYTE_SIZE
              + ShortSerializer.SHORT_SIZE,
          rid.getCollectionPosition());
      startIndex = 1;
    }

    if (values.size() > startIndex) {
      final var itemSize =
          IntegerSerializer.INT_SIZE
              + RID_SIZE * values.size()
              + ByteSerializer.BYTE_SIZE; // next item pointer + RIDs + size

      var freePointer = getIntValue(FREE_POINTER_OFFSET);

      freePointer -= itemSize;
      setIntValue(FREE_POINTER_OFFSET, freePointer);

      final var nextItem = getIntValue(entryPosition);

      setIntValue(entryPosition, freePointer); // update list header

      freePointer += setIntValue(freePointer, nextItem); // next item pointer
      freePointer += setByteValue(freePointer, (byte) values.size());

      for (var i = startIndex; i < values.size(); i++) {
        final var rid = values.get(i);

        freePointer += setShortValue(freePointer, (short) rid.getCollectionId());
        freePointer += setLongValue(freePointer, rid.getCollectionPosition());
      }
    }

    setByteValue(
        entryPosition + IntegerSerializer.INT_SIZE, (byte) (embeddedEntriesCount + values.size()));
    setIntValue(
        entryPosition + ByteSerializer.BYTE_SIZE + IntegerSerializer.INT_SIZE, entriesCount);
  }

  public boolean addNonLeafEntry(
      final int index,
      final byte[] serializedKey,
      final int leftChild,
      final int rightChild,
      final boolean updateNeighbors) {
    final var prevChild =
        doAddNonLeafEntry(index, serializedKey, leftChild, rightChild, updateNeighbors);
    return prevChild >= -1;
  }

  private int doAddNonLeafEntry(
      int index, byte[] serializedKey, int leftChild, int rightChild, boolean updateNeighbors) {
    assert !isLeaf();

    final var entrySize = serializedKey.length + 2 * IntegerSerializer.INT_SIZE;

    var size = size();
    var freePointer = getIntValue(FREE_POINTER_OFFSET);
    if (freePointer - entrySize
        < (size + 1) * IntegerSerializer.INT_SIZE + POSITIONS_ARRAY_OFFSET) {
      return -2;
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

    setBinaryValue(freePointer, serializedKey);

    size++;

    var prevChild = -1;
    if (updateNeighbors && size > 1) {
      if (index < size - 1) {
        final var nextEntryPosition =
            getIntValue(POSITIONS_ARRAY_OFFSET + (index + 1) * IntegerSerializer.INT_SIZE);
        prevChild = getIntValue(nextEntryPosition);
        setIntValue(nextEntryPosition, rightChild);
      }

      if (index > 0) {
        final var prevEntryPosition =
            getIntValue(POSITIONS_ARRAY_OFFSET + (index - 1) * IntegerSerializer.INT_SIZE);
        prevChild = getIntValue(prevEntryPosition + IntegerSerializer.INT_SIZE);
        setIntValue(prevEntryPosition + IntegerSerializer.INT_SIZE, leftChild);
      }
    }

    return prevChild;
  }

  public void removeNonLeafEntry(final int entryIndex, final byte[] key, final int prevChild) {
    if (isLeaf()) {
      throw new IllegalStateException("Remove is applied to non-leaf buckets only");
    }

    final var entryPosition =
        getIntValue(POSITIONS_ARRAY_OFFSET + entryIndex * IntegerSerializer.INT_SIZE);
    final var entrySize = key.length + 2 * IntegerSerializer.INT_SIZE;
    var size = getIntValue(SIZE_OFFSET);

    final var leftChild = getIntValue(entryPosition);
    final var rightChild = getIntValue(entryPosition + IntegerSerializer.INT_SIZE);

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

  protected static class Entry {

    public final byte[] key;

    Entry(final byte[] key) {
      this.key = key;
    }
  }

  public static final class LeafEntry extends Entry {

    public final long mId;
    public final List<RID> values;
    public final int entriesCount;

    public LeafEntry(final byte[] key, final long mId, final List<RID> values, int entriesCount) {
      super(key);
      this.mId = mId;
      this.values = values;
      this.entriesCount = entriesCount;
    }
  }

  public static final class NonLeafEntry extends Entry {

    public final int leftChild;
    public final int rightChild;

    public NonLeafEntry(final byte[] key, final int leftChild, final int rightChild) {
      super(key);

      this.leftChild = leftChild;
      this.rightChild = rightChild;
    }
  }
}
