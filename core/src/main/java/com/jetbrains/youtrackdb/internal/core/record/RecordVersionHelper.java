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

package com.jetbrains.youtrackdb.internal.core.record;

import com.jetbrains.youtrackdb.internal.core.serialization.BinaryProtocol;

/**
 * Static helper class to manage record version.
 */
public class RecordVersionHelper {

  public static final int SERIALIZED_SIZE = BinaryProtocol.SIZE_LONG;

  protected RecordVersionHelper() {
  }

  public static boolean isValid(final long version) {
    return version > -1;
  }

  public static boolean isTombstone(final long version) {
    return version < 0;
  }

  public static byte[] toStream(final long version) {
    return BinaryProtocol.long2bytes(version);
  }

  public static long fromStream(final byte[] stream) {
    return BinaryProtocol.bytes2long(stream);
  }

  public static long reset() {
    return 0;
  }

  public static long disable() {
    return -1;
  }

  public static int compareTo(final long v1, final long v2) {
    final long myVersion;
    if (isTombstone(v1)) {
      myVersion = -v1;
    } else {
      myVersion = v1;
    }

    final long otherVersion;
    if (isTombstone(v2)) {
      otherVersion = -v2;
    } else {
      otherVersion = v2;
    }

    return Long.compare(myVersion, otherVersion);
  }

  public static String toString(final long version) {
    return String.valueOf(version);
  }

  public static long fromString(final String string) {
    return Long.parseLong(string);
  }
}
