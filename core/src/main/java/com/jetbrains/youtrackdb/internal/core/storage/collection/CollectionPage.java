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

package com.jetbrains.youtrackdb.internal.core.storage.collection;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.common.serialization.types.ByteSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.IntegerSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.LongSerializer;
import com.jetbrains.youtrackdb.internal.common.util.RawPairIntegerBoolean;
import com.jetbrains.youtrackdb.internal.core.exception.StorageException;
import com.jetbrains.youtrackdb.internal.core.record.RecordVersionHelper;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import it.unimi.dsi.fastutil.ints.IntSet;
import java.util.Objects;
import javax.annotation.Nullable;

/**
 * A durable page that stores record entries within a paginated collection.
 *
 * @since 19.03.13
 */
public final class CollectionPage extends DurablePage {

  private static final int VERSION_SIZE = RecordVersionHelper.SERIALIZED_SIZE;

  private static final int NEXT_PAGE_OFFSET = NEXT_FREE_POSITION;
  private static final int PREV_PAGE_OFFSET = NEXT_PAGE_OFFSET + LongSerializer.LONG_SIZE;

  private static final int FREELIST_HEADER_OFFSET = PREV_PAGE_OFFSET + LongSerializer.LONG_SIZE;
  private static final int FREE_POSITION_OFFSET =
      FREELIST_HEADER_OFFSET + IntegerSerializer.INT_SIZE;
  private static final int FREE_SPACE_COUNTER_OFFSET =
      FREE_POSITION_OFFSET + IntegerSerializer.INT_SIZE;
  private static final int ENTRIES_COUNT_OFFSET =
      FREE_SPACE_COUNTER_OFFSET + IntegerSerializer.INT_SIZE;
  private static final int PAGE_INDEXES_LENGTH_OFFSET =
      ENTRIES_COUNT_OFFSET + IntegerSerializer.INT_SIZE;
  private static final int PAGE_INDEXES_OFFSET =
      PAGE_INDEXES_LENGTH_OFFSET + IntegerSerializer.INT_SIZE;

  static final int INDEX_ITEM_SIZE = IntegerSerializer.INT_SIZE + VERSION_SIZE;
  private static final int MARKED_AS_DELETED_FLAG = 1 << 16;
  private static final int POSITION_MASK = 0xFFFF;
  public static final int PAGE_SIZE =
      GlobalConfiguration.DISK_CACHE_PAGE_SIZE.getValueAsInteger() << 10;

  static final int MAX_ENTRY_SIZE = PAGE_SIZE - PAGE_INDEXES_OFFSET - INDEX_ITEM_SIZE;

  public static final int MAX_RECORD_SIZE = MAX_ENTRY_SIZE - 3 * IntegerSerializer.INT_SIZE;

  private static final int ENTRY_KIND_HOLE = -1;
  private static final int ENTRY_KIND_UNKNOWN = 0;
  private static final int ENTRY_KIND_DATA = 1;

  public CollectionPage(CacheEntry cacheEntry) {
    super(cacheEntry);
  }

  public void init() {
    setLongValue(NEXT_PAGE_OFFSET, -1);
    setLongValue(PREV_PAGE_OFFSET, -1);

    setFreeListHeader(0);
    setPageIndexesLength(0);
    setIntValue(ENTRIES_COUNT_OFFSET, 0);

    setFreePosition(PAGE_SIZE);
    setFreeSpace(PAGE_SIZE - PAGE_INDEXES_OFFSET);
  }

  public int appendRecord(
      final long recordVersion,
      final byte[] record,
      final int requestedPosition,
      final IntSet bookedRecordPositions) {
    var freePosition = getFreePosition();
    final var indexesLength = getPageIndexesLength();

    final var lastEntryIndexPosition = computePointerPosition(indexesLength);

    var entrySize = record.length + 3 * IntegerSerializer.INT_SIZE;
    var freeListHeader = getFreeListHeader();

    if (!checkSpace(entrySize)) {
      return -1;
    }

    if (freePosition - entrySize < lastEntryIndexPosition + INDEX_ITEM_SIZE) {
      if (requestedPosition < 0) {
        final var findHole = findHole(entrySize, freePosition);

        if (findHole != null) {
          final var entryPosition = findHole[0];
          final var holeSize = findHole[1];

          final var entry =
              findFirstEmptySlot(
                  recordVersion,
                  entryPosition,
                  indexesLength,
                  entrySize,
                  freeListHeader,
                  true,
                  bookedRecordPositions);

          if (entry != null) {
            assert entry.second; // allocated from free list

            final var entryIndex = entry.first;

            assert holeSize >= entrySize;
            if (holeSize != entrySize) {
              setIntValue(entryPosition + entrySize, -(holeSize - entrySize));
            }

            writeEntry(record, entryPosition, entrySize, entryIndex);

            return entryIndex;
          }
        }
      }

      doDefragmentation();
    }

    freePosition = getFreePosition();
    freePosition -= entrySize;

    int entryIndex;

    if (requestedPosition < 0) {
      final var entry =
          findFirstEmptySlot(
              recordVersion,
              freePosition,
              indexesLength,
              entrySize,
              freeListHeader,
              false,
              bookedRecordPositions);
      Objects.requireNonNull(entry);
      entryIndex = entry.first;
    } else {
      insertIntoRequestedSlot(
          recordVersion, freePosition, entrySize, requestedPosition, freeListHeader);
      entryIndex = requestedPosition;
    }

    writeEntry(record, freePosition, entrySize, entryIndex);

    setFreePosition(freePosition);

    return entryIndex;
  }

  private void writeEntry(byte[] record, int entryPosition, int entrySize, int entryIndex) {
    assert entryPosition + entrySize <= PAGE_SIZE;

    setRecordEntrySize(entryPosition, entrySize);
    setRecordEntryIndex(entryPosition, entryIndex);
    setRecordEntryBytesLength(entryPosition, record.length);
    setRecordEntryBytes(entryPosition, record);

    incrementEntriesCount();
  }

  public void setRecordEntrySize(int recordPosition, int size) {
    setIntValue(recordPosition, size);
  }

  public void setRecordEntryIndex(int recordPosition, int index) {
    setIntValue(recordPosition + IntegerSerializer.INT_SIZE, index);
  }

  public void setRecordEntryBytesLength(int recordPosition, int bytesLength) {
    setIntValue(recordPosition + (IntegerSerializer.INT_SIZE << 1), bytesLength);
  }

  public void setRecordEntryBytes(int recordPosition, byte[] record) {
    setBinaryValue(recordPosition + IntegerSerializer.INT_SIZE * 3, record);
  }

  public int getRecordEntrySize(int recordPosition) {
    return getIntValue(recordPosition);
  }

  public int getRecordEntryBytesLength(int recordPosition) {
    return getIntValue(recordPosition + (IntegerSerializer.INT_SIZE << 1));
  }

  public byte[] getRecordEntryBytes(int recordPosition, int valLen) {
    return getBinaryValue(recordPosition + IntegerSerializer.INT_SIZE * 3, valLen);
  }

  /**
   * Finds the hole position and its size which is equal or bigger than requested size.
   *
   * @return position and size of the hole.
   */
  @Nullable
  private int[] findHole(int requestedSize, int freePosition) {
    var currentPosition = freePosition;

    var holeSize = 0;
    var initialHolePosition = 0;

    while (currentPosition < PAGE_SIZE) {
      final var size = getIntValue(currentPosition);

      if (size < 0) {
        final var currentSize = -size;

        if (initialHolePosition == 0) {
          initialHolePosition = currentPosition;
          holeSize = currentSize;
        } else {
          holeSize += currentSize;
        }

        if (holeSize >= requestedSize) {
          if (holeSize > requestedSize) {
            if (holeSize - requestedSize > Integer.BYTES) {
              return new int[]{initialHolePosition, holeSize};
            } else {
              currentPosition += Math.abs(size);
              continue;
            }
          }

          return new int[]{initialHolePosition, holeSize};
        }
      } else {
        holeSize = 0;
        initialHolePosition = 0;
      }

      currentPosition += Math.abs(size);
    }

    return null;
  }

  private void insertIntoRequestedSlot(
      final long recordVersion,
      final int freePosition,
      final int entrySize,
      final int requestedPosition,
      final int freeListHeader) {
    var indexesLength = getPageIndexesLength();
    // 1. requested position is first free slot inside of list of pointers
    if (indexesLength == requestedPosition) {
      setPageIndexesLength(indexesLength + 1);
      setFreeSpace(getFreeSpace() - entrySize - INDEX_ITEM_SIZE);

      var entryIndexPosition = computePointerPosition(requestedPosition);
      setPointerAt(entryIndexPosition, freePosition);

      setVersionAt(entryIndexPosition, recordVersion);
    } else if (indexesLength > requestedPosition) {
      // 2 requested position inside of list of pointers
      final var entryIndexPosition = computePointerPosition(requestedPosition);
      var entryPointer = getPointerAt(entryIndexPosition);
      // 2.1 requested position already occupied by other record, should not really happen
      if ((entryPointer & MARKED_AS_DELETED_FLAG) == 0) {
        throw new StorageException(null,
            "Can not insert record inside of already occupied slot, record position = "
                + requestedPosition);
      }

      // 2.2 requested position is already removed, read free list of removed pointers till we will
      // not find one which we need
      // remove
      var prevFreeListItem = -1;
      var currentFreeListItem = freeListHeader - 1;
      while (true) {
        final var tombstonePointer = getPointerAt(computePointerPosition(currentFreeListItem));
        final var nextEntryPosition = (tombstonePointer & POSITION_MASK);

        if (currentFreeListItem == requestedPosition) {
          if (prevFreeListItem >= 0) {
            setPointer(prevFreeListItem, tombstonePointer);
          } else {
            setFreeListHeader(nextEntryPosition);
          }

          break;
        }

        if (nextEntryPosition > 0) {
          prevFreeListItem = currentFreeListItem;
          currentFreeListItem = nextEntryPosition - 1;
        } else {
          throw new StorageException(null,
              "Record position "
                  + requestedPosition
                  + " marked as deleted but can not be found in the list of deleted records");
        }
      }

      // insert record into acquired slot
      setFreeSpace(getFreeSpace() - entrySize);
      setPointerAt(entryIndexPosition, freePosition);

      setVersionAt(entryIndexPosition, recordVersion);
    } else {
      throw new StorageException(null,
          "Can not insert record out side of list of already inserted records, record position = "
              + requestedPosition);
    }

  }

  @Nullable
  private RawPairIntegerBoolean findFirstEmptySlot(
      long recordVersion,
      int entryPosition,
      int indexesLength,
      int entrySize,
      int freeListHeader,
      boolean useOnlyFreeList,
      IntSet bookedRecordPositions) {
    var allocatedFromFreeList = false;
    int entryIndex;
    if (freeListHeader > 0) {
      // iterate over free list of times to find first not booked position to reuse
      entryIndex = -1;

      var prevFreeListItem = -1;
      var currentFreeListItem = freeListHeader - 1;
      while (true) {
        final var tombstonePointer = getPointer(currentFreeListItem);
        final var nextEntryPosition = (tombstonePointer & POSITION_MASK);

        if (!bookedRecordPositions.contains(currentFreeListItem)) {
          if (prevFreeListItem >= 0) {
            setPointer(prevFreeListItem, tombstonePointer);
          } else {
            setFreeListHeader(nextEntryPosition);
          }

          entryIndex = currentFreeListItem;
          break;
        }

        if (nextEntryPosition > 0) {
          prevFreeListItem = currentFreeListItem;
          currentFreeListItem = nextEntryPosition - 1;
        } else {
          break;
        }
      }

      if (entryIndex >= 0) {
        setFreeSpace(getFreeSpace() - entrySize);

        var entryIndexPosition = computePointerPosition(entryIndex);
        setPointerAt(entryIndexPosition, entryPosition);

        setVersionAt(entryIndexPosition, recordVersion);

        allocatedFromFreeList = true;
      } else {
        if (useOnlyFreeList) {
          return null;
        }

        entryIndex = appendEntry(recordVersion, entryPosition, indexesLength, entrySize);
      }
    } else {
      if (useOnlyFreeList) {
        return null;
      }

      entryIndex = appendEntry(recordVersion, entryPosition, indexesLength, entrySize);
    }

    return new RawPairIntegerBoolean(entryIndex, allocatedFromFreeList);
  }

  private int appendEntry(long recordVersion, int freePosition, int indexesLength, int entrySize) {
    int entryIndex;
    entryIndex = indexesLength;

    setPageIndexesLength(indexesLength + 1);
    setFreeSpace(getFreeSpace() - entrySize - INDEX_ITEM_SIZE);

    var entryIndexPosition = computePointerPosition(entryIndex);
    setPointerAt(entryIndexPosition, freePosition);

    setVersionAt(entryIndexPosition, recordVersion);
    return entryIndex;
  }

  public byte[] replaceRecord(int entryIndex, byte[] record, final int recordVersion) {
    var entryIndexPosition = computePointerPosition(entryIndex);

    if (recordVersion != -1) {
      setVersionAt(entryIndexPosition, recordVersion);
    }

    var entryPosition = getPointerValuePositionAt(entryIndexPosition);

    var recordSize = getRecordEntrySize(entryPosition) - 3 * IntegerSerializer.INT_SIZE;
    if (recordSize != record.length) {
      throw new IllegalStateException(
          "Length of passed in and stored records are different. Stored record length = "
              + recordSize
              + ", passed record length = "
              + record.length);
    }

    final var oldRecord = getRecordEntryBytes(entryPosition, recordSize);

    setRecordEntryBytesLength(entryPosition, record.length);
    setRecordEntryBytes(entryPosition, record);

    return oldRecord;
  }

  public long getRecordVersion(int position) {
    var indexesLength = getPageIndexesLength();
    if (position >= indexesLength) {
      return -1;
    }

    final var entryIndexPosition = computePointerPosition(position);
    final var entryPointer = getPointerAt(entryIndexPosition);

    if ((entryPointer & MARKED_AS_DELETED_FLAG) != 0) {
      return -1;
    }
    return getVersionAt(entryIndexPosition);
  }

  public boolean setRecordVersion(int position, int version) {
    var indexesLength = getPageIndexesLength();
    if (position >= indexesLength) {
      return false;
    }

    final var entryIndexPosition = computePointerPosition(position);
    final var entryPointer = getPointerAt(entryIndexPosition);

    if ((entryPointer & MARKED_AS_DELETED_FLAG) != 0) {
      return false;
    }

    setVersionAt(entryIndexPosition, version);
    return true;
  }

  public boolean isEmpty() {
    return getFreeSpace() == PAGE_SIZE - PAGE_INDEXES_OFFSET;
  }

  private boolean checkSpace(int entrySize) {
    return getFreeSpace() - entrySize >= 0;
  }

  @Nullable
  public byte[] deleteRecord(int position, boolean preserveFreeListPointer) {
    var indexesLength = getPageIndexesLength();
    if (position >= indexesLength) {
      return null;
    }

    var entryIndexPosition = computePointerPosition(position);
    final var entryPointer = getPointerAt(entryIndexPosition);

    if ((entryPointer & MARKED_AS_DELETED_FLAG) != 0) {
      return null;
    }

    var entryPosition = entryPointer & POSITION_MASK;
    if (preserveFreeListPointer) {
      var freeListHeader = getFreeListHeader();
      if (freeListHeader <= 0) {
        setPointerAt(entryIndexPosition, MARKED_AS_DELETED_FLAG);
      } else {
        setPointerAt(entryIndexPosition, freeListHeader | MARKED_AS_DELETED_FLAG);
      }

      setFreeListHeader(position + 1);
    } else {
      if (position != indexesLength - 1) {
        throw new IllegalStateException(
            "Only last position can be removed without keeping it in free list");
      }

      setPageIndexesLength(indexesLength - 1);
      setFreeSpace(getFreeSpace() + INDEX_ITEM_SIZE);
    }

    final var entrySize = getRecordEntrySize(entryPosition);
    assert entrySize + entryPosition <= PAGE_SIZE;
    assert entrySize > 0;

    final var recordSize = getRecordEntryBytesLength(entryPosition);

    setRecordEntrySize(entryPosition, -entrySize);
    setFreeSpace(getFreeSpace() + entrySize);

    decrementEntriesCount();

    return getRecordEntryBytes(entryPosition, recordSize);
  }

  public boolean isDeleted(final int position) {
    final var indexesLength = getPageIndexesLength();
    if (position >= indexesLength) {
      return true;
    }

    var entryPointer = getPointer(position);

    return (entryPointer & MARKED_AS_DELETED_FLAG) != 0;
  }

  public int getRecordSize(final int position) {
    final var indexesLength = getPageIndexesLength();
    if (position >= indexesLength) {
      return -1;
    }

    final var entryPointer = getPointer(position);
    if ((entryPointer & MARKED_AS_DELETED_FLAG) != 0) {
      return -1;
    }

    final var entryPosition = entryPointer & POSITION_MASK;
    assert getRecordEntrySize(entryPosition) + entryPosition <= PAGE_SIZE;
    assert getRecordEntryBytesLength(entryPosition) <= PAGE_SIZE;

    return getRecordEntryBytesLength(entryPosition);
  }

  int findFirstDeletedRecord(final int position) {
    final var indexesLength = getPageIndexesLength();
    for (var i = position; i < indexesLength; i++) {
      var entryPointer = getPointer(i);
      if ((entryPointer & MARKED_AS_DELETED_FLAG) != 0) {
        return i;
      }
    }

    return -1;
  }

  int findFirstRecord(final int position) {
    final var indexesLength = getPageIndexesLength();
    for (var i = position; i < indexesLength; i++) {
      final var entryPointer = getPointer(i);
      if ((entryPointer & MARKED_AS_DELETED_FLAG) == 0) {
        return i;
      }
    }

    return -1;
  }

  int findLastRecord(final int position) {
    final var indexesLength = getPageIndexesLength();

    final var endIndex = Math.min(indexesLength - 1, position);
    for (var i = endIndex; i >= 0; i--) {
      final var entryPointer = getPointer(i);
      if ((entryPointer & MARKED_AS_DELETED_FLAG) == 0) {
        return i;
      }
    }

    return -1;
  }

  public int getFreeSpace() {
    return getIntValue(FREE_SPACE_COUNTER_OFFSET);
  }

  public int getMaxRecordSize() {
    final var maxEntrySize = getFreeSpace() - INDEX_ITEM_SIZE;

    final var result = maxEntrySize - 3 * IntegerSerializer.INT_SIZE;
    return Math.max(result, 0);
  }

  public int getRecordsCount() {
    return getIntValue(ENTRIES_COUNT_OFFSET);
  }

  public long getNextPage() {
    return getLongValue(NEXT_PAGE_OFFSET);
  }

  public void setNextPage(final long nextPage) {
    setLongValue(NEXT_PAGE_OFFSET, nextPage);
  }

  public long getPrevPage() {
    return getLongValue(PREV_PAGE_OFFSET);
  }

  public void setPrevPage(final long prevPage) {
    setLongValue(PREV_PAGE_OFFSET, prevPage);
  }

  @Nullable
  public byte[] getRecordBinaryValue(final int recordPosition, final int offset, final int size) {
    assert isPositionInsideInterval(recordPosition);

    final var entryPointer = getPointer(recordPosition);
    if ((entryPointer & MARKED_AS_DELETED_FLAG) != 0) {
      return null;
    }

    final var entryPosition = entryPointer & POSITION_MASK;

    if (offset >= 0) {
      assert insideRecordBounds(entryPosition, offset, size);

      return getBinaryValue(entryPosition + offset + 3 * IntegerSerializer.INT_SIZE, size);
    } else {
      final var recordSize = getRecordEntryBytesLength(entryPosition);
      assert insideRecordBounds(entryPosition, recordSize + offset, LongSerializer.LONG_SIZE);

      return getBinaryValue(
          entryPosition + 3 * IntegerSerializer.INT_SIZE + recordSize + offset, size);
    }
  }

  public byte getRecordByteValue(final int recordPosition, final int offset) {
    assert isPositionInsideInterval(recordPosition);

    final var entryPosition = getPointerValuePosition(recordPosition);

    if (offset >= 0) {
      assert insideRecordBounds(entryPosition, offset, ByteSerializer.BYTE_SIZE);
      return getByteValue(entryPosition + offset + 3 * IntegerSerializer.INT_SIZE);
    } else {
      final var recordSize = getRecordEntryBytesLength(entryPosition);
      assert insideRecordBounds(entryPosition, recordSize + offset, ByteSerializer.BYTE_SIZE);
      return getByteValue(entryPosition + 3 * IntegerSerializer.INT_SIZE + recordSize + offset);
    }
  }

  private boolean insideRecordBounds(
      final int entryPosition, final int offset, final int contentSize) {
    final var recordSize = getRecordEntryBytesLength(entryPosition);
    return offset >= 0 && offset + contentSize <= recordSize;
  }

  private void incrementEntriesCount() {
    setIntValue(ENTRIES_COUNT_OFFSET, getRecordsCount() + 1);
  }

  private void decrementEntriesCount() {
    setIntValue(ENTRIES_COUNT_OFFSET, getRecordsCount() - 1);
  }

  private boolean isPositionInsideInterval(final int recordPosition) {
    final var indexesLength = getPageIndexesLength();
    return recordPosition < indexesLength;
  }

  private void doDefragmentation() {
    final var recordsCount = getRecordsCount();
    final var freePosition = getFreePosition();

    // 1. Build the entries "map" and merge consecutive holes.

    final var maxEntries =
        recordsCount /* live records */ + recordsCount + 1 /* max holes after merging */;
    final var positions = new int[maxEntries];
    final var sizes = new int[maxEntries];

    var count = 0;
    var currentPosition = freePosition;
    var lastEntryKind = ENTRY_KIND_UNKNOWN;
    while (currentPosition < PAGE_SIZE) {
      final var size = getIntValue(currentPosition);
      final var entryKind = Integer.signum(size);
      assert entryKind != ENTRY_KIND_UNKNOWN;

      if (entryKind == ENTRY_KIND_HOLE && lastEntryKind == ENTRY_KIND_HOLE) {
        sizes[count - 1] += size;
      } else {
        positions[count] = currentPosition;
        sizes[count] = size;

        ++count;

        lastEntryKind = entryKind;
      }

      currentPosition += entryKind == ENTRY_KIND_HOLE ? -size : size;
    }

    // 2. Iterate entries in reverse, update data offsets, merge consecutive data segments and move
    // them in a single operation.

    var shift = 0;
    var lastDataPosition = 0;
    var mergedDataSize = 0;
    for (var i = count - 1; i >= 0; --i) {
      final var position = positions[i];
      final var size = sizes[i];

      final var entryKind = Integer.signum(size);
      assert entryKind != ENTRY_KIND_UNKNOWN;

      if (entryKind == ENTRY_KIND_DATA && shift > 0) {
        final var positionIndex = getIntValue(position + IntegerSerializer.INT_SIZE);
        setPointer(positionIndex, position + shift);

        lastDataPosition = position;
        mergedDataSize += size; // accumulate consecutive data segments size
      }

      if (mergedDataSize > 0
          && (entryKind == ENTRY_KIND_HOLE
          || i == 0)) { // move consecutive merged data segments in one go
        moveData(lastDataPosition, lastDataPosition + shift, mergedDataSize);
        mergedDataSize = 0;
      }

      if (entryKind == ENTRY_KIND_HOLE) {
        shift -= size;
      }
    }

    // 3. Update free position.

    setFreePosition(freePosition + shift);
  }

  public int getFreePosition() {
    return getIntValue(FREE_POSITION_OFFSET);
  }

  public void setFreePosition(int freePosition) {
    setIntValue(FREE_POSITION_OFFSET, freePosition);
  }

  public void setFreeSpace(int freePosition) {
    setIntValue(FREE_SPACE_COUNTER_OFFSET, freePosition);
  }

  public int getPageIndexesLength() {
    return getIntValue(PAGE_INDEXES_LENGTH_OFFSET);
  }

  public void setPageIndexesLength(int freePosition) {
    setIntValue(PAGE_INDEXES_LENGTH_OFFSET, freePosition);
  }

  public void setPointer(int position, int value) {
    setPointerAt(computePointerPosition(position), value);
  }

  public void setPointerAt(int entryIndexPosition, int pointer) {
    setIntValue(entryIndexPosition, pointer);
  }

  public int getPointerAt(int entryIndexPosition) {
    return getIntValue(entryIndexPosition);
  }

  public int getPointerValuePositionAt(int entryIndexPosition) {
    var valuePosition = getPointerAt(entryIndexPosition);
    return valuePosition & POSITION_MASK;
  }

  public int getPointer(int position) {
    return getPointerAt(computePointerPosition(position));
  }

  public int getPointerValuePosition(int position) {
    return getPointerValuePositionAt(computePointerPosition(position));
  }

  public void setVersionAt(int entryIndexPosition, long version) {
    setLongValue(entryIndexPosition + IntegerSerializer.INT_SIZE, version);
  }

  public long getVersionAt(int entryIndexPosition) {
    return getLongValue(entryIndexPosition + IntegerSerializer.INT_SIZE);
  }

  public static int computePointerPosition(int position) {
    return PAGE_INDEXES_OFFSET + INDEX_ITEM_SIZE * position;
  }

  public int getFreeListHeader() {
    return getIntValue(FREELIST_HEADER_OFFSET);
  }

  public void setFreeListHeader(int freePosition) {
    setIntValue(FREELIST_HEADER_OFFSET, freePosition);
  }
}
