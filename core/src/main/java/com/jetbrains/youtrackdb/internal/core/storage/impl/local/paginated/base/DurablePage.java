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

package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.common.serialization.types.BinarySerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.ByteSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.IntegerSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.LongSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.ShortSerializer;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.BinarySerializerFactory;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrackdb.internal.core.storage.cache.OptimisticReadFailedException;
import com.jetbrains.youtrackdb.internal.core.storage.cache.PageView;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALChanges;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import javax.annotation.Nullable;

/**
 * Base page class for all durable data structures, that is data structures state of which can be
 * consistently restored after system crash but results of last operations in small interval before
 * crash may be lost.
 *
 * <p>This page has several booked memory areas with following offsets at the beginning:
 *
 * <ol>
 *   <li>from 0 to 7 - Magic number
 *   <li>from 8 to 11 - crc32 of all page content, which is calculated by cache system just before
 *       save
 *   <li>from 12 to 23 - LSN of last operation which was stored for given page
 * </ol>
 *
 * <p>Developer which will extend this class should use all page memory starting from {@link
 * #NEXT_FREE_POSITION} offset. All data structures which use this kind of pages should be derived
 * from {@link
 * StorageComponent} class.
 *
 * @since 16.08.13
 */
public class DurablePage {

  public static final int MAGIC_NUMBER_OFFSET = 0;
  protected static final int CRC32_OFFSET = MAGIC_NUMBER_OFFSET + LongSerializer.LONG_SIZE;

  public static final int WAL_SEGMENT_OFFSET = CRC32_OFFSET + IntegerSerializer.INT_SIZE;
  public static final int WAL_POSITION_OFFSET = WAL_SEGMENT_OFFSET + LongSerializer.LONG_SIZE;

  public static final int MAX_PAGE_SIZE_BYTES =
      GlobalConfiguration.DISK_CACHE_PAGE_SIZE.getValueAsInteger() << 10;

  public static final int NEXT_FREE_POSITION = WAL_POSITION_OFFSET + LongSerializer.LONG_SIZE;

  private final WALChanges changes;
  @Nullable private final CacheEntry cacheEntry;
  private final ByteBuffer buffer;

  // True when this page was created from a PageView (optimistic read).
  // When true, cacheEntry is null and buffer contents are speculative.
  private final boolean speculativeRead;

  // Stored locally for null-safe getPageIndex() in speculative mode
  // (cacheEntry is null, so we cannot delegate to it).
  private final int pageIndex;

  public DurablePage(final CacheEntry cacheEntry) {
    assert cacheEntry != null;
    this.cacheEntry = cacheEntry;
    this.speculativeRead = false;
    this.pageIndex = cacheEntry.getPageIndex();
    var pointer = cacheEntry.getCachePointer();
    this.changes = cacheEntry.getChanges();
    this.buffer = pointer.getBuffer();

    assert buffer == null || buffer.position() == 0;
    assert buffer == null || buffer.isDirect();

    if (cacheEntry.getInitialLSN() == null) {
      final var buffer = pointer.getBuffer();

      if (buffer != null) {
        cacheEntry.setInitialLSN(getLogSequenceNumberFromPage(buffer));
      } else {
        // it is new a page
        cacheEntry.setInitialLSN(new LogSequenceNumber(-1, -1));
      }
    }
  }

  /**
   * Creates a DurablePage from a PageView obtained via an optimistic read. The buffer
   * contents are speculative — they may be stale if the page was evicted or modified.
   * Callers must validate the stamp after reading data.
   *
   * <p>No WAL changes are available in this mode (changes == null). Read methods that
   * encounter potentially corrupted sizes throw {@link OptimisticReadFailedException}
   * via {@link #guardSize(long)} when {@code speculativeRead == true}.
   */
  public DurablePage(final PageView pageView) {
    assert pageView != null;
    this.cacheEntry = null;
    this.changes = null;
    this.buffer = pageView.buffer();
    this.speculativeRead = true;
    this.pageIndex = pageView.pageFrame().getPageIndex();

    assert buffer != null;
    assert buffer.position() == 0;
    assert buffer.isDirect();
  }

  public final int getPageIndex() {
    return pageIndex;
  }

  /**
   * Returns the LSN of the page, or null if this is a speculative read (no cacheEntry).
   */
  @Nullable public final LogSequenceNumber getLsn() {
    if (cacheEntry == null) {
      return null;
    }
    final var segment = getLongValue(WAL_SEGMENT_OFFSET);
    final var position = getIntValue(WAL_POSITION_OFFSET);

    return new LogSequenceNumber(segment, position);
  }

  public static LogSequenceNumber getLogSequenceNumberFromPage(final ByteBuffer buffer) {
    final var segment = buffer.getLong(WAL_SEGMENT_OFFSET);
    final var position = buffer.getInt(WAL_POSITION_OFFSET);

    return new LogSequenceNumber(segment, position);
  }

  public static void setLogSequenceNumberForPage(
      final ByteBuffer buffer, final LogSequenceNumber lsn) {
    buffer.putLong(WAL_SEGMENT_OFFSET, lsn.getSegment());
    buffer.putInt(WAL_POSITION_OFFSET, lsn.getPosition());
  }

  /**
   * DO NOT DELETE THIS METHOD IT IS USED IN ENTERPRISE STORAGE
   *
   * <p>Copies content of page into passed in byte array.
   *
   * @param buffer Buffer from which data will be copied
   * @param data   Byte array to which data will be copied
   * @param offset Offset of data inside page
   * @param length Length of data to be copied
   */
  @SuppressWarnings("unused")
  public static void getPageData(
      final ByteBuffer buffer, final byte[] data, final int offset, final int length) {
    buffer.get(0, data, offset, length);
  }

  /**
   * DO NOT DELETE THIS METHOD IT IS USED IN ENTERPRISE STORAGE
   *
   * <p>Get value of LSN from the passed in offset in byte array.
   *
   * @param offset Offset inside of byte array from which LSN value will be read.
   * @param data   Byte array from which LSN value will be read.
   */
  @SuppressWarnings("unused")
  public static LogSequenceNumber getLogSequenceNumber(final int offset, final byte[] data) {
    final var segment =
        LongSerializer.deserializeNative(data, offset + WAL_SEGMENT_OFFSET);
    final var position =
        IntegerSerializer.deserializeNative(data, offset + WAL_POSITION_OFFSET);

    return new LogSequenceNumber(segment, position);
  }

  /**
   * Guards a size value read from the page buffer during a speculative read. If the size
   * is negative or exceeds the buffer capacity, the data is likely stale (page was evicted
   * or modified). Throws {@link OptimisticReadFailedException} to trigger fallback.
   *
   * <p>No-op when {@code speculativeRead == false} (normal CAS-pinned path).
   */
  protected final void guardSize(final long sizeInBytes) {
    if (speculativeRead && (sizeInBytes < 0 || sizeInBytes > buffer.capacity())) {
      throw OptimisticReadFailedException.INSTANCE;
    }
  }

  /**
   * Guards a page offset + access width during a speculative read. If the offset is out of
   * bounds for the given access width, the offset was likely read from stale data.
   * Throws {@link OptimisticReadFailedException} to trigger fallback.
   *
   * <p>No-op when {@code speculativeRead == false} (normal CAS-pinned path).
   */
  private void guardOffset(final int pageOffset, final int accessWidth) {
    if (speculativeRead
        && (pageOffset < 0 || (long) pageOffset + accessWidth > buffer.capacity())) {
      throw OptimisticReadFailedException.INSTANCE;
    }
  }

  /**
   * Asserts that this page is not in speculative read mode. Called by setter methods to
   * catch accidental writes to a shared buffer during optimistic reads.
   */
  private void assertNotSpeculative() {
    assert !speculativeRead : "Write operations are not allowed on speculative-read pages";
  }

  protected final int getIntValue(final int pageOffset) {
    guardOffset(pageOffset, IntegerSerializer.INT_SIZE);
    if (changes == null) {

      assert buffer != null;

      assert buffer.order() == ByteOrder.nativeOrder();
      return buffer.getInt(pageOffset);
    }

    return changes.getIntValue(buffer, pageOffset);
  }

  protected final int[] getIntArray(final int pageOffset, final int size) {
    guardSize((long) size * IntegerSerializer.INT_SIZE);
    var values = new int[size];
    var bytes = getBinaryValue(pageOffset, size * IntegerSerializer.INT_SIZE);
    for (var i = 0; i < size; i++) {
      values[i] =
          IntegerSerializer.deserializeNative(bytes, i * IntegerSerializer.INT_SIZE);
    }
    return values;
  }

  protected final void setIntArray(final int pageOffset, final int[] values, final int offset) {
    assertNotSpeculative();

    var bytes = new byte[(values.length - offset) * IntegerSerializer.INT_SIZE];
    for (var i = offset; i < values.length; i++) {
      IntegerSerializer.serializeNative(
          values[i], bytes, (i - offset) * IntegerSerializer.INT_SIZE);
    }
    setBinaryValue(pageOffset, bytes);
  }

  protected final short getShortValue(final int pageOffset) {
    guardOffset(pageOffset, ShortSerializer.SHORT_SIZE);
    if (changes == null) {
      assert buffer != null;

      assert buffer.order() == ByteOrder.nativeOrder();
      return buffer.getShort(pageOffset);
    }

    return changes.getShortValue(buffer, pageOffset);
  }

  protected final long getLongValue(final int pageOffset) {
    guardOffset(pageOffset, LongSerializer.LONG_SIZE);
    if (changes == null) {
      assert buffer != null;

      assert buffer.order() == ByteOrder.nativeOrder();
      return buffer.getLong(pageOffset);
    }

    return changes.getLongValue(buffer, pageOffset);
  }

  protected final byte[] getBinaryValue(final int pageOffset, final int valLen) {
    guardSize(valLen);
    if (changes == null) {
      assert buffer != null;
      assert buffer.order() == ByteOrder.nativeOrder();
      final var result = new byte[valLen];

      buffer.get(pageOffset, result);

      return result;
    }

    return changes.getBinaryValue(buffer, pageOffset, valLen);
  }

  protected final int getObjectSizeInDirectMemory(
      final BinarySerializer<?> binarySerializer, BinarySerializerFactory serializerFactory,
      final int offset) {
    if (changes == null) {
      assert buffer != null;
      assert buffer.order() == ByteOrder.nativeOrder();

      final var size =
          binarySerializer.getObjectSizeInByteBuffer(serializerFactory, offset, buffer);
      guardSize(size);
      return size;
    }

    return binarySerializer.getObjectSizeInByteBuffer(buffer, changes, offset);
  }

  /**
   * Compares a key stored on this page against a pre-serialized search key without deserializing
   * the on-page key. On the hot path (no WAL changes), delegates to the serializer's in-buffer
   * comparison. Falls back to deserialization when WAL changes are present.
   */
  protected final <T> int compareKeyInDirectMemory(
      final BinarySerializer<T> serializer, BinarySerializerFactory serializerFactory,
      final int pageOffset, final byte[] searchKey, final int searchKeyOffset) {
    if (changes == null) {
      assert buffer != null;
      assert buffer.order() == ByteOrder.nativeOrder();

      return serializer.compareInByteBuffer(
          serializerFactory, pageOffset, buffer, searchKey, searchKeyOffset);
    }
    // WAL changes present: delegate to serializer's WAL-aware comparison
    return serializer.compareInByteBufferWithWALChanges(
        serializerFactory, buffer, changes, pageOffset, searchKey, searchKeyOffset);
  }

  protected final <T> T deserializeFromDirectMemory(
      final BinarySerializer<T> binarySerializer, BinarySerializerFactory serializerFactory,
      final int offset) {
    if (changes == null) {
      assert buffer != null;
      assert buffer.order() == ByteOrder.nativeOrder();

      return binarySerializer.deserializeFromByteBufferObject(serializerFactory, offset, buffer);
    }
    return binarySerializer.deserializeFromByteBufferObject(serializerFactory, buffer, changes,
        offset);
  }

  protected final byte getByteValue(final int pageOffset) {
    guardOffset(pageOffset, ByteSerializer.BYTE_SIZE);
    if (changes == null) {

      assert buffer != null;

      assert buffer.order() == ByteOrder.nativeOrder();
      return buffer.get(pageOffset);
    }
    return changes.getByteValue(buffer, pageOffset);
  }

  @SuppressWarnings("SameReturnValue")
  protected final int setIntValue(final int pageOffset, final int value) {
    assertNotSpeculative();
    if (changes != null) {
      changes.setIntValue(buffer, value, pageOffset);
    } else {
      assert buffer != null;
      assert buffer.order() == ByteOrder.nativeOrder();
      buffer.putInt(pageOffset, value);
    }

    return IntegerSerializer.INT_SIZE;
  }

  protected final int setShortValue(final int pageOffset, final short value) {
    assertNotSpeculative();
    if (changes != null) {
      changes.setIntValue(buffer, value, pageOffset);
    } else {
      assert buffer != null;
      assert buffer.order() == ByteOrder.nativeOrder();
      buffer.putShort(pageOffset, value);
    }

    return ShortSerializer.SHORT_SIZE;
  }

  @SuppressWarnings("SameReturnValue")
  protected final int setByteValue(final int pageOffset, final byte value) {
    assertNotSpeculative();
    if (changes != null) {
      changes.setByteValue(buffer, value, pageOffset);
    } else {
      assert buffer != null;
      assert buffer.order() == ByteOrder.nativeOrder();
      buffer.put(pageOffset, value);
    }

    return ByteSerializer.BYTE_SIZE;
  }

  @SuppressWarnings("SameReturnValue")
  protected final int setLongValue(final int pageOffset, final long value) {
    assertNotSpeculative();
    if (changes != null) {
      changes.setLongValue(buffer, value, pageOffset);
    } else {
      assert buffer != null;
      assert buffer.order() == ByteOrder.nativeOrder();
      buffer.putLong(pageOffset, value);
    }

    return LongSerializer.LONG_SIZE;
  }

  protected final int setBinaryValue(final int pageOffset, final byte[] value) {
    assertNotSpeculative();
    if (value.length == 0) {
      return 0;
    }

    if (changes != null) {
      changes.setBinaryValue(buffer, value, pageOffset);
    } else {
      assert buffer != null;
      assert buffer.order() == ByteOrder.nativeOrder();
      buffer.put(pageOffset, value);
    }

    return value.length;
  }

  protected final void moveData(final int from, final int to, final int len) {
    assertNotSpeculative();
    if (len == 0) {
      return;
    }

    if (changes != null) {
      changes.moveData(buffer, from, to, len);
    } else {
      assert buffer != null;
      assert buffer.order() == ByteOrder.nativeOrder();

      buffer.put(to, buffer, from, len);
    }
  }

  public final WALChanges getChanges() {
    return changes;
  }

  public final CacheEntry getCacheEntry() {
    return cacheEntry;
  }

  public final void restoreChanges(final WALChanges changes) {
    assertNotSpeculative();
    final var buffer = cacheEntry.getCachePointer().getBuffer();
    assert buffer != null;

    changes.applyChanges(buffer);
  }

  public final void setLsn(final LogSequenceNumber lsn) {
    assertNotSpeculative();
    assert buffer != null;

    assert buffer.order() == ByteOrder.nativeOrder();

    setLogSequenceNumberForPage(buffer, lsn);
  }

  @Override
  public String toString() {
    if (cacheEntry != null) {
      return getClass().getSimpleName()
          + "{"
          + "fileId="
          + cacheEntry.getFileId()
          + ", pageIndex="
          + cacheEntry.getPageIndex()
          + '}';
    } else {
      return super.toString();
    }
  }
}
