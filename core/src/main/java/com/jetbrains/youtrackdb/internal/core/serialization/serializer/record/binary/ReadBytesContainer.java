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

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.exception.BaseException;
import com.jetbrains.youtrackdb.internal.core.exception.SerializationException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Read-only, ByteBuffer-backed container for the record deserialization path. Replaces {@link
 * BytesContainer} on the read side — provides position-tracked read methods without any
 * mutable alloc/resize semantics.
 *
 * <p>Supports both heap ByteBuffers (for byte[] wrap fallback) and direct ByteBuffers (for
 * PageFrame zero-copy reads).
 */
public final class ReadBytesContainer {

  private final ByteBuffer buffer;

  /**
   * Wraps an existing ByteBuffer. The container reads from the buffer's current position to its
   * limit. The buffer's position is advanced as bytes are consumed.
   */
  public ReadBytesContainer(ByteBuffer buffer) {
    this.buffer = buffer;
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
    return buffer.get(buffer.position() + relativeOffset);
  }

  /** Bulk read into a destination array. */
  public void getBytes(byte[] dst, int dstOffset, int length) {
    buffer.get(dst, dstOffset, length);
  }

  /** Reads {@code length} bytes and creates a UTF-8 String. */
  public String getStringBytes(int length) {
    if (buffer.hasArray()) {
      var result =
          new String(
              buffer.array(),
              buffer.arrayOffset() + buffer.position(),
              length,
              StandardCharsets.UTF_8);
      buffer.position(buffer.position() + length);
      return result;
    }
    var bytes = new byte[length];
    buffer.get(bytes);
    return new String(bytes, 0, length, StandardCharsets.UTF_8);
  }

  /**
   * Reads {@code length} bytes and returns an interned String via the session's string cache. Falls
   * back to {@code new String(...).intern()} if no cache is available.
   */
  public String getInternedString(DatabaseSessionEmbedded session, int length) {
    // StringCache requires byte[] — extract from the buffer
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

    try {
      var context = session.getSharedContext();
      if (context != null) {
        var cache = context.getStringCache();
        if (cache != null) {
          return cache.getString(bytes, bytesOffset, length);
        }
      }
      return new String(bytes, bytesOffset, length, StandardCharsets.UTF_8).intern();
    } catch (UnsupportedEncodingException e) {
      throw BaseException.wrapException(
          new SerializationException(
              session.getDatabaseName(), "Error on string decoding"),
          e,
          session.getDatabaseName());
    }
  }

  /** Returns the number of bytes remaining between the current position and the limit. */
  public int remaining() {
    return buffer.remaining();
  }

  /** Returns the current read position. */
  public int offset() {
    return buffer.position();
  }

  /** Advances the position by {@code n} bytes without reading. */
  public void skip(int n) {
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
