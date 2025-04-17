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

package com.jetbrains.youtrack.db.internal.core.record;

import com.jetbrains.youtrack.db.internal.core.serialization.BinaryProtocol;

/**
 * Static helper class to manage record version.
 */
public class RecordVersionHelper {

  public static final int SERIALIZED_SIZE = BinaryProtocol.SIZE_INT;

  protected RecordVersionHelper() {
  }

  public static int increment(final int version) {
    if (isTombstone(version)) {
      throw new IllegalStateException("Record was deleted and cannot be updated.");
    }

    return version + 1;
  }

  public static int decrement(final int version) {
    if (isTombstone(version)) {
      throw new IllegalStateException("Record was deleted and cannot be updated.");
    }

    return version - 1;
  }

  public static boolean isValid(final int version) {
    return version > -1;
  }

  public static boolean isTombstone(final int version) {
    return version < 0;
  }

  public static byte[] toStream(final int version) {
    return BinaryProtocol.int2bytes(version);
  }

  public static int fromStream(final byte[] stream) {
    return BinaryProtocol.bytes2int(stream);
  }

  public static int reset() {
    return 0;
  }

  public static int disable() {
    return -1;
  }

  public static int compareTo(final int v1, final int v2) {
    final int myVersion;
    if (isTombstone(v1)) {
      myVersion = -v1;
    } else {
      myVersion = v1;
    }

    final int otherVersion;
    if (isTombstone(v2)) {
      otherVersion = -v2;
    } else {
      otherVersion = v2;
    }

    if (myVersion == otherVersion) {
      return 0;
    }

    if (myVersion < otherVersion) {
      return -1;
    }

    return 1;
  }

  public static String toString(final int version) {
    return String.valueOf(version);
  }

  public static int fromString(final String string) {
    return Integer.parseInt(string);
  }
}
