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

/**
 * Sealed result of a storage read operation. Two variants:
 *
 * <ul>
 *   <li>{@link RawBuffer} — byte[] copy (traditional path, multi-page records,
 *       pinned fallback)
 *   <li>{@link RawPageBuffer} — zero-copy PageFrame reference (single-page
 *       optimistic read path)
 * </ul>
 */
public sealed interface StorageReadResult permits RawBuffer, RawPageBuffer {

  /** Record version at the time of reading. */
  long recordVersion();

  /** Record type byte. */
  byte recordType();

  /**
   * Returns this result as a {@link RawBuffer}. If this is already a {@code RawBuffer},
   * returns it directly. If this is a {@code RawPageBuffer}, extracts the content bytes
   * into a new byte[] and wraps them in a {@code RawBuffer}.
   *
   * <p>Use this for callers that need a byte[] (e.g., storage configuration reads)
   * and cannot use the zero-copy PageFrame path.
   */
  default RawBuffer toRawBuffer() {
    return switch (this) {
      case RawBuffer rb -> rb;
      case RawPageBuffer pb -> {
        var slice = pb.sliceContent();
        var bytes = new byte[slice.remaining()];
        slice.get(bytes);
        yield new RawBuffer(bytes, pb.recordVersion(), pb.recordType());
      }
    };
  }
}
