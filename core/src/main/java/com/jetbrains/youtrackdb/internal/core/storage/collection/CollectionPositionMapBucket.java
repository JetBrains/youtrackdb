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

import com.jetbrains.youtrackdb.internal.common.serialization.types.ByteSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.IntegerSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.LongSerializer;
import com.jetbrains.youtrackdb.internal.core.exception.StorageException;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import javax.annotation.Nullable;

/**
 * @since 10/7/13
 */
public final class CollectionPositionMapBucket extends DurablePage {

  private static final int NEXT_PAGE_OFFSET = NEXT_FREE_POSITION;
  private static final int SIZE_OFFSET = NEXT_PAGE_OFFSET + LongSerializer.LONG_SIZE;
  private static final int POSITIONS_OFFSET = SIZE_OFFSET + IntegerSerializer.INT_SIZE;

  // NEVER USED ON DISK
  public static final byte NOT_EXISTENT = 0;
  public static final byte REMOVED = 1;
  public static final byte FILLED = 2;
  public static final byte ALLOCATED = 4;

  private static final int ENTRY_SIZE =
      ByteSerializer.BYTE_SIZE + LongSerializer.LONG_SIZE + IntegerSerializer.INT_SIZE
          + LongSerializer.LONG_SIZE;

  public static final int MAX_ENTRIES = (MAX_PAGE_SIZE_BYTES - POSITIONS_OFFSET) / ENTRY_SIZE;

  public CollectionPositionMapBucket(CacheEntry cacheEntry) {
    super(cacheEntry);
  }

  public void init() {
    setIntValue(SIZE_OFFSET, 0);
  }

  public int add(long pageIndex, int recordPosition, long recordVersion) {
    return add(pageIndex, recordPosition, recordVersion, FILLED);
  }

  public int add(long pageIndex, int recordPosition, long recordVersion, byte status) {
    var size = getIntValue(SIZE_OFFSET);

    var position = entryPosition(size);

    position += setByteValue(position, status);
    position += setLongValue(position, pageIndex);
    position += setIntValue(position, recordPosition);
    setLongValue(position, recordVersion);

    setIntValue(SIZE_OFFSET, size + 1);

    return size;
  }

  public int allocate() {
    var size = getIntValue(SIZE_OFFSET);

    var position = entryPosition(size);

    position += setByteValue(position, ALLOCATED);
    position += setLongValue(position, -1);
    position += setIntValue(position, -1);
    setLongValue(position, 0);

    setIntValue(SIZE_OFFSET, size + 1);

    return size;
  }

  @Nullable
  public PositionEntry get(int index) {
    var size = getIntValue(SIZE_OFFSET);

    if (index >= size) {
      return null;
    }

    var position = entryPosition(index);
    if (getByteValue(position) != FILLED) {
      return null;
    }

    return readEntry(position);
  }

  public void set(final int index, final PositionEntry entry) {
    final var size = getIntValue(SIZE_OFFSET);

    if (index >= size) {
      throw new StorageException(null, "Provided index " + index + " is out of range");
    }

    final var position = entryPosition(index);

    var flag = getByteValue(position);
    if (flag == ALLOCATED) {
      flag = FILLED;
    } else if (flag != FILLED) {
      throw new StorageException(null, "Provided index " + index + " points to removed entry");
    }

    updateEntry(index, entry.pageIndex, entry.recordPosition, entry.recordVersion, flag);
  }

  public void updateEntry(
      final int index, final long pageIndex, final int recordPosition,
      final long recordVersion, final byte status) {
    final var position = entryPosition(index);
    setByteValue(position, status);
    setLongValue(position + ByteSerializer.BYTE_SIZE, pageIndex);
    setIntValue(position + ByteSerializer.BYTE_SIZE + LongSerializer.LONG_SIZE, recordPosition);
    setLongValue(
        position + ByteSerializer.BYTE_SIZE + LongSerializer.LONG_SIZE
            + IntegerSerializer.INT_SIZE,
        recordVersion);
  }

  private static int entryPosition(int index) {
    return index * ENTRY_SIZE + POSITIONS_OFFSET;
  }

  public boolean isFull() {
    return getIntValue(SIZE_OFFSET) == MAX_ENTRIES;
  }

  public int getSize() {
    return getIntValue(SIZE_OFFSET);
  }

  public void remove(int index) {
    var size = getIntValue(SIZE_OFFSET);

    if (index >= size) {
      return;
    }

    var position = entryPosition(index);

    if (getByteValue(position) != FILLED) {
      return;
    }

    updateStatus(index, REMOVED);
  }

  public void updateStatus(int index, byte status) {
    var position = entryPosition(index);
    setByteValue(position, status);
  }

  private PositionEntry readEntry(int position) {
    position += ByteSerializer.BYTE_SIZE;
    final var pageIndex = getLongValue(position);
    position += LongSerializer.LONG_SIZE;
    final var pagePosition = getIntValue(position);
    position += IntegerSerializer.INT_SIZE;
    final var recordVersion = getLongValue(position);
    return new PositionEntry(pageIndex, pagePosition, recordVersion);
  }

  public boolean exists(final int index) {
    var size = getIntValue(SIZE_OFFSET);
    if (index >= size) {
      return false;
    }

    final var position = entryPosition(index);
    return getByteValue(position) == FILLED;
  }

  public byte getStatus(final int index) {
    var size = getIntValue(SIZE_OFFSET);
    if (index >= size) {
      return NOT_EXISTENT;
    }

    final var position = entryPosition(index);
    return getByteValue(position);
  }

  public long getVersion(int index) {
    var size = getIntValue(SIZE_OFFSET);

    if (index >= size) {
      return -1;
    }

    var position = entryPosition(index);
    return getLongValue(
        position + ByteSerializer.BYTE_SIZE + LongSerializer.LONG_SIZE
            + IntegerSerializer.INT_SIZE);
  }

  public void updateVersion(int index, long recordVersion) {
    var size = getIntValue(SIZE_OFFSET);

    if (index >= size) {
      throw new StorageException(
          null, "Provided index " + index + " is out of range");
    }

    var position = entryPosition(index);
    setLongValue(
        position + ByteSerializer.BYTE_SIZE + LongSerializer.LONG_SIZE
            + IntegerSerializer.INT_SIZE,
        recordVersion);
  }

  public static final class PositionEntry {

    private final long pageIndex;
    private final int recordPosition;
    private final long recordVersion;

    public PositionEntry(
        final long pageIndex, final int recordPosition, final long recordVersion) {
      this.pageIndex = pageIndex;
      this.recordPosition = recordPosition;
      this.recordVersion = recordVersion;
    }

    public long getPageIndex() {
      return pageIndex;
    }

    public int getRecordPosition() {
      return recordPosition;
    }

    public long getRecordVersion() {
      return recordVersion;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      var that = (PositionEntry) o;
      return pageIndex == that.pageIndex && recordPosition == that.recordPosition
          && recordVersion == that.recordVersion;
    }

    @Override
    public int hashCode() {
      var result = Long.hashCode(pageIndex);
      result = 31 * result + recordPosition;
      result = 31 * result + Long.hashCode(recordVersion);
      return result;
    }

    @Override
    public String toString() {
      return "PositionEntry{"
          + "pageIndex="
          + pageIndex
          + ", recordPosition="
          + recordPosition
          + ", recordVersion="
          + recordVersion
          + '}';
    }
  }
}
