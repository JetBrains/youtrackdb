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

package com.jetbrains.youtrack.db.internal.common.comparator;

import java.lang.reflect.Field;
import java.nio.ByteOrder;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Comparator;
import sun.misc.Unsafe;

/**
 * Comparator for fast byte arrays comparison using {@link Unsafe} class. Bytes are compared like
 * unsigned not like signed bytes.
 *
 * @since 08.07.12
 */
@SuppressWarnings("restriction")
public class UnsafeByteArrayComparator implements Comparator<byte[]> {

  public static final UnsafeByteArrayComparator INSTANCE = new UnsafeByteArrayComparator();

  private static final Unsafe unsafe;

  private static final int BYTE_ARRAY_OFFSET;
  private static final boolean littleEndian =
      ByteOrder.nativeOrder().equals(ByteOrder.LITTLE_ENDIAN);

  private static final int LONG_SIZE = Long.SIZE / Byte.SIZE;

  static {
    unsafe =
        (Unsafe)
            AccessController.doPrivileged(
                (PrivilegedAction<Object>)
                    () -> {
                      try {
                        var f = Unsafe.class.getDeclaredField("theUnsafe");
                        f.setAccessible(true);
                        return f.get(null);
                      } catch (NoSuchFieldException | IllegalAccessException e) {
                        throw new Error(e);
                      }
                    });

    BYTE_ARRAY_OFFSET = unsafe.arrayBaseOffset(byte[].class);

    final var byteArrayScale = unsafe.arrayIndexScale(byte[].class);

    if (byteArrayScale != 1) {
      throw new Error();
    }
  }

  public int compare(byte[] arrayOne, byte[] arrayTwo) {
    final var commonLen = Math.min(arrayOne.length, arrayTwo.length);
    final var WORDS = commonLen / LONG_SIZE;

    for (var i = 0; i < WORDS * LONG_SIZE; i += LONG_SIZE) {
      final long index = i + BYTE_ARRAY_OFFSET;

      final var wOne = unsafe.getLong(arrayOne, index);
      final var wTwo = unsafe.getLong(arrayTwo, index);

      if (wOne == wTwo) {
        continue;
      }

      if (littleEndian) {
        return lessThanUnsigned(Long.reverseBytes(wOne), Long.reverseBytes(wTwo)) ? -1 : 1;
      }

      return lessThanUnsigned(wOne, wTwo) ? -1 : 1;
    }

    for (var i = WORDS * LONG_SIZE; i < commonLen; i++) {
      var diff = compareUnsignedByte(arrayOne[i], arrayTwo[i]);
      if (diff != 0) {
        return diff;
      }
    }

    return Integer.compare(arrayOne.length, arrayTwo.length);
  }

  private static boolean lessThanUnsigned(long longOne, long longTwo) {
    return (longOne + Long.MIN_VALUE) < (longTwo + Long.MIN_VALUE);
  }

  private static int compareUnsignedByte(byte byteOne, byte byteTwo) {
    final var valOne = byteOne & 0xFF;
    final var valTwo = byteTwo & 0xFF;
    return valOne - valTwo;
  }
}
