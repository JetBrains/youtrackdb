package com.jetbrains.youtrackdb.internal.core.index;

import com.jetbrains.youtrackdb.internal.common.comparator.ByteArrayComparator;
import com.jetbrains.youtrackdb.internal.common.comparator.ComparatorFactory;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Shared prefix and lower-bound rules for single-field index keys. */
final class IndexKeyShortener {

  private IndexKeyShortener() {
  }

  static int prefixLength(PropertyTypeInternal[] types, boolean unique, int limit) {
    if (limit <= 0 || types == null || types.length != 1) {
      return -1;
    }
    int valueHeader;
    if (types[0] == PropertyTypeInternal.STRING) {
      valueHeader = Short.BYTES;
    } else if (types[0] == PropertyTypeInternal.BINARY) {
      valueHeader = Integer.BYTES;
    } else {
      return -1;
    }
    // Composite length + element count, user type tag, value header, version tag + long.
    // A non-unique key also has a RID tag, short collection id and byte position length.
    return limit - (2 * Integer.BYTES + 1 + valueHeader + 1 + Long.BYTES
        + (unique ? 0 : 1 + Short.BYTES + 1));
  }

  static Object shorten(Object key, int prefixLength) {
    if (prefixLength < 0) {
      return key;
    }
    if (key instanceof byte[] bytes) {
      return bytes.length <= prefixLength ? key : Arrays.copyOf(bytes, prefixLength);
    }
    if (key instanceof String string) {
      int end = 0;
      int bytes = 0;
      while (end < string.length()) {
        int codePoint = string.codePointAt(end);
        // Java's UTF-8 encoder replaces an unpaired surrogate with one byte.
        int width = codePoint <= 0x7f ? 1 : codePoint <= 0x7ff ? 2
            : codePoint >= 0xd800 && codePoint <= 0xdfff ? 1
                : codePoint <= 0xffff ? 3 : 4;
        if (bytes + width > prefixLength) {
          break;
        }
        bytes += width;
        end += Character.charCount(codePoint);
      }
      return end == string.length() ? key : string.substring(0, end);
    }
    return key;
  }

  static Object lowerBoundKey(Object prepared, int prefixLength) {
    if (prefixLength < 0) {
      return prepared;
    }
    if (prepared instanceof String) {
      // Stored prefixes can stop three bytes before P when a four-byte point does not fit.
      return shorten(prepared, Math.max(0, prefixLength - 3));
    }
    if (prepared instanceof byte[] bytes && bytes.length >= prefixLength
        && ComparatorFactory.INSTANCE.getComparator(byte[].class) == ByteArrayComparator.INSTANCE) {
      // Length-first order loses the original length after shortening. Every P-byte prefix
      // can represent a qualifying longer value, so start at the smallest P-byte array.
      return new byte[prefixLength];
    }
    return prepared;
  }

  static boolean inclusiveLowerBound(Object key, int prefixLength, boolean inclusive) {
    if (prefixLength < 0) {
      return inclusive;
    }
    // A four-byte code point may leave three bytes unused in a stored prefix.
    return inclusive
        || (key instanceof String string
            && string.getBytes(StandardCharsets.UTF_8).length >= prefixLength - 3)
        || (key instanceof byte[] bytes && bytes.length >= prefixLength);
  }
}
