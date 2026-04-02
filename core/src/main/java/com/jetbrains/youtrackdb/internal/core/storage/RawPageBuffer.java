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
package com.jetbrains.youtrackdb.internal.core.storage;

import com.jetbrains.youtrackdb.internal.common.directmemory.PageFrame;
import java.nio.ByteBuffer;

/**
 * Zero-copy storage read result that holds a reference to a PageFrame instead of
 * copying record bytes into a byte[]. Used on the single-page optimistic read path.
 *
 * <p>{@code contentOffset} is an absolute byte offset within the PageFrame's native
 * memory buffer pointing to the start of the record content (after the entry header
 * and metadata header). {@code contentLength} is the number of content bytes.
 *
 * <p>The caller must validate the PageFrame's stamp after reading to ensure the data
 * was not modified concurrently. If the stamp is invalid, the caller falls back to
 * the byte[]-based {@link RawBuffer} path.
 *
 * @param pageFrame the PageFrame containing the record data
 * @param stamp the optimistic read stamp obtained when the page was read
 * @param contentOffset absolute byte offset within the page buffer to record content
 * @param contentLength number of bytes of record content
 * @param recordVersion record version at the time of reading
 * @param recordType record type byte
 */
public record RawPageBuffer(
    PageFrame pageFrame,
    long stamp,
    int contentOffset,
    int contentLength,
    long recordVersion,
    byte recordType)
    implements StorageReadResult {

  public RawPageBuffer {
    if (pageFrame == null) {
      throw new IllegalArgumentException("PageFrame must not be null");
    }
    if (contentOffset < 0) {
      throw new IllegalArgumentException(
          "contentOffset must be non-negative: " + contentOffset);
    }
    if (contentLength < 0) {
      throw new IllegalArgumentException(
          "contentLength must be non-negative: " + contentLength);
    }
    int end = Math.addExact(contentOffset, contentLength);
    if (end > pageFrame.getBuffer().capacity()) {
      throw new IllegalArgumentException(
          "content region ["
              + contentOffset
              + ", "
              + end
              + ") exceeds page buffer capacity "
              + pageFrame.getBuffer().capacity());
    }
  }

  /**
   * Returns an independent ByteBuffer view covering exactly the record content bytes.
   * The returned buffer has position 0 and limit equal to {@code contentLength}.
   *
   * <p>The returned buffer shares native memory with the PageFrame — the caller must
   * validate the stamp after reading from this buffer.
   */
  public ByteBuffer sliceContent() {
    return pageFrame.getBuffer().slice(contentOffset, contentLength);
  }
}
