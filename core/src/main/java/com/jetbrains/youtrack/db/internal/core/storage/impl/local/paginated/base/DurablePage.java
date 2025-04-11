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

package com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.base;

import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.internal.common.serialization.types.BinarySerializer;
import com.jetbrains.youtrack.db.internal.common.serialization.types.ByteSerializer;
import com.jetbrains.youtrack.db.internal.common.serialization.types.IntegerSerializer;
import com.jetbrains.youtrack.db.internal.common.serialization.types.LongSerializer;
import com.jetbrains.youtrack.db.internal.common.serialization.types.ShortSerializer;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.binary.BinarySerializerFactory;
import com.jetbrains.youtrack.db.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.wal.WALChanges;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

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
 * DurableComponent} class.
 *
 * @since 16.08.13
 */
public class DurablePage {

  public static final int MAGIC_NUMBER_OFFSET = 0;
  protected static final int CRC32_OFFSET = MAGIC_NUMBER_OFFSET + LongSerializer.LONG_SIZE;

  public static final int WAL_SEGMENT_OFFSET = CRC32_OFFSET + IntegerSerializer.INT_SIZE;
  public static final int WAL_POSITION_OFFSET = WAL_SEGMENT_OFFSET + LongSerializer.LONG_SIZE;

  public static final int MAX_PAGE_SIZE_BYTES =
      GlobalConfiguration.DISK_CACHE_PAGE_SIZE.getValueAsInteger() * 1024;

  public static final int NEXT_FREE_POSITION = WAL_POSITION_OFFSET + LongSerializer.LONG_SIZE;

  private final WALChanges changes;
  private final CacheEntry cacheEntry;
  private final ByteBuffer buffer;

  public DurablePage(final CacheEntry cacheEntry) {
    assert cacheEntry != null;
    this.cacheEntry = cacheEntry;
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

  public final int getPageIndex() {
    return cacheEntry.getPageIndex();
  }

  public final LogSequenceNumber getLsn() {
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
        LongSerializer.INSTANCE.deserializeNative(data, offset + WAL_SEGMENT_OFFSET);
    final var position =
        IntegerSerializer.deserializeNative(data, offset + WAL_POSITION_OFFSET);

    return new LogSequenceNumber(segment, position);
  }

  protected final int getIntValue(final int pageOffset) {
    if (changes == null) {

      assert buffer != null;

      assert buffer.order() == ByteOrder.nativeOrder();
      return buffer.getInt(pageOffset);
    }

    return changes.getIntValue(buffer, pageOffset);
  }

  protected final int[] getIntArray(final int pageOffset, int size) {
    var values = new int[size];
    var bytes = getBinaryValue(pageOffset, size * IntegerSerializer.INT_SIZE);
    for (var i = 0; i < size; i++) {
      values[i] =
          IntegerSerializer.deserializeNative(bytes, i * IntegerSerializer.INT_SIZE);
    }
    return values;
  }

  protected final void setIntArray(final int pageOffset, int[] values, int offset) {
    var bytes = new byte[(values.length - offset) * IntegerSerializer.INT_SIZE];
    for (var i = offset; i < values.length; i++) {
      IntegerSerializer.serializeNative(
          values[i], bytes, (i - offset) * IntegerSerializer.INT_SIZE);
    }
    setBinaryValue(pageOffset, bytes);
  }

  protected final short getShortValue(final int pageOffset) {
    if (changes == null) {
      assert buffer != null;

      assert buffer.order() == ByteOrder.nativeOrder();
      return buffer.getShort(pageOffset);
    }

    return changes.getShortValue(buffer, pageOffset);
  }

  protected final long getLongValue(final int pageOffset) {
    if (changes == null) {
      assert buffer != null;

      assert buffer.order() == ByteOrder.nativeOrder();
      return buffer.getLong(pageOffset);
    }

    return changes.getLongValue(buffer, pageOffset);
  }

  protected final byte[] getBinaryValue(final int pageOffset, final int valLen) {
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

      return binarySerializer.getObjectSizeInByteBuffer(serializerFactory, offset, buffer);
    }

    return binarySerializer.getObjectSizeInByteBuffer(buffer, changes, offset);
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
    if (changes == null) {

      assert buffer != null;

      assert buffer.order() == ByteOrder.nativeOrder();
      return buffer.get(pageOffset);
    }
    return changes.getByteValue(buffer, pageOffset);
  }

  @SuppressWarnings("SameReturnValue")
  protected final int setIntValue(final int pageOffset, final int value) {
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
    final var buffer = cacheEntry.getCachePointer().getBuffer();
    assert buffer != null;

    changes.applyChanges(buffer);
  }

  public final void setLsn(final LogSequenceNumber lsn) {
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
