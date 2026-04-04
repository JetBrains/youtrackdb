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

package com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary;

import com.jetbrains.youtrackdb.internal.core.db.StringCache;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import javax.annotation.Nullable;

/**
 * Read-only, ByteBuffer-backed container for the record deserialization path. Replaces {@link
 * BytesContainer} on the read side — provides position-tracked read methods without any mutable
 * alloc/resize semantics.
 *
 * <p>Supports both heap ByteBuffers (for byte[] wrap fallback) and direct ByteBuffers (for
 * PageFrame zero-copy reads).
 */
public final class ReadBytesContainer {

  private ByteBuffer buffer;

  /**
   * Wraps an existing ByteBuffer. The container reads from the buffer's current position to its
   * limit. The buffer's position is advanced as bytes are consumed.
   */
  public ReadBytesContainer(ByteBuffer buffer) {
    this.buffer = buffer;
  }

  /**
   * Creates an empty container that must be initialized via {@link #reset(ByteBuffer, int, int)}
   * before use. Useful for pre-allocating a reusable container to avoid per-call allocation.
   */
  public ReadBytesContainer() {
    this.buffer = null;
  }

  /**
   * Resets this container to read from the given buffer starting at {@code offset} for
   * {@code length} bytes. Uses {@link ByteBuffer#duplicate()} to avoid the heavier
   * {@link ByteBuffer#slice(int, int)} allocation, then sets position and limit directly.
   */
  public void reset(ByteBuffer source, int offset, int length) {
    this.buffer = source.duplicate();
    this.buffer.position(offset);
    this.buffer.limit(offset + length);
  }

  /**
   * Convenience constructor wrapping a byte array in a heap ByteBuffer. The entire array is
   * readable.
   */
  public ReadBytesContainer(byte[] source) {
    this.buffer = ByteBuffer.wrap(source);
  }

  /**
   * Convenience constructor wrapping a byte array with an initial offset. Reads start at {@code
   * offset} and extend to the end of the array.
   */
  public ReadBytesContainer(byte[] source, int offset) {
    this.buffer = ByteBuffer.wrap(source, offset, source.length - offset);
  }

  /** Reads one byte and advances the position. */
  public byte getByte() {
    return buffer.get();
  }

  /**
   * Reads a byte at {@code position() + relativeOffset} without advancing the position. Useful for
   * field name matching where bytes are compared without consumption.
   */
  public byte peekByte(int relativeOffset) {
    assert relativeOffset >= 0 : "peekByte relativeOffset must be non-negative";
    return buffer.get(buffer.position() + relativeOffset);
  }

  /** Bulk read into a destination array. */
  public void getBytes(byte[] dst, int dstOffset, int length) {
    buffer.get(dst, dstOffset, length);
  }

  /**
   * Reads {@code length} bytes and creates a UTF-8 String. Guards against OOM by checking remaining
   * bytes before allocating. Throws {@link BufferUnderflowException} if length is negative or
   * exceeds remaining bytes.
   */
  public String getStringBytes(int length) {
    if (length < 0 || length > buffer.remaining()) {
      throw new BufferUnderflowException();
    }
    if (buffer.hasArray()) {
      var pos = buffer.position();
      var result =
          new String(
              buffer.array(), buffer.arrayOffset() + pos, length, StandardCharsets.UTF_8);
      buffer.position(pos + length);
      return result;
    }
    var bytes = new byte[length];
    buffer.get(bytes);
    return new String(bytes, 0, length, StandardCharsets.UTF_8);
  }

  /**
   * Reads {@code length} bytes and returns an interned String via the provided string cache. Falls
   * back to a plain String if no cache is available. Guards against OOM by checking remaining bytes
   * before allocating. Throws {@link BufferUnderflowException} if length is negative or exceeds
   * remaining bytes.
   */
  public String getInternedString(@Nullable StringCache cache, int length) {
    if (length < 0 || length > buffer.remaining()) {
      throw new BufferUnderflowException();
    }

    byte[] bytes;
    int bytesOffset;
    if (buffer.hasArray()) {
      bytes = buffer.array();
      bytesOffset = buffer.arrayOffset() + buffer.position();
      buffer.position(buffer.position() + length);
    } else {
      bytes = new byte[length];
      buffer.get(bytes);
      bytesOffset = 0;
    }

    if (cache != null) {
      return cache.getString(bytes, bytesOffset, length);
    }
    // Match the intern() behavior of HelperClasses.stringFromBytesIntern for the rare
    // case when no StringCache is available (e.g., during early initialization).
    return new String(bytes, bytesOffset, length, StandardCharsets.UTF_8).intern();
  }

  /** Reads a big-endian int (4 bytes) and advances the position. No intermediate allocation. */
  public int getInt() {
    return buffer.getInt();
  }

  /** Reads a big-endian long (8 bytes) and advances the position. No intermediate allocation. */
  public long getLong() {
    return buffer.getLong();
  }

  /** Returns the number of bytes remaining between the current position and the limit. */
  public int remaining() {
    return buffer.remaining();
  }

  /** Returns the current read position. */
  public int offset() {
    return buffer.position();
  }

  /**
   * Advances the position by {@code n} bytes without reading. Throws {@link
   * IllegalArgumentException} if {@code n} is negative or would advance past the limit.
   */
  public void skip(int n) {
    if (n < 0) {
      throw new IllegalArgumentException("skip amount must be non-negative: " + n);
    }
    buffer.position(buffer.position() + n);
  }

  /** Sets the absolute read position. */
  public void setOffset(int position) {
    buffer.position(position);
  }

  /**
   * Creates a sub-container sharing the same backing buffer, starting at the current position and
   * spanning {@code length} bytes. The parent's position is advanced past the sliced region.
   */
  public ReadBytesContainer slice(int length) {
    var sliced = buffer.slice(buffer.position(), length);
    buffer.position(buffer.position() + length);
    return new ReadBytesContainer(sliced);
  }
}
