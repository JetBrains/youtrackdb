package com.jetbrains.youtrackdb.internal.core.index.engine;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import net.jpountz.xxhash.XXHashFactory;

/**
 * Inactive one-or-two-page container for an opaque complete statistics image.
 * Each fragment starts after the durable header. Three native-order ints (version, image length,
 * role) precede a native-order XXH64 long of the complete image, with seed zero. The hash detects
 * accidental damage, not malicious tampering. No live writer uses this codec.
 */
final class HistogramStatsPageContinuationCodec {

  static final int FORMAT_VERSION = 2;
  static final int HEADER_SIZE = 3 * Integer.BYTES + Long.BYTES;
  static final int PART_CAPACITY = 4096 - DurablePage.NEXT_FREE_POSITION - HEADER_SIZE;
  static final int IMAGE_CAPACITY = 2 * PART_CAPACITY;

  private HistogramStatsPageContinuationCodec() {
  }

  static byte[][] encode(byte[] image) {
    checkLength(image.length);
    long digest = digest(image);
    int count = image.length > PART_CAPACITY ? 2 : 1;
    byte[][] parts = new byte[count][];
    for (int role = 0; role < count; role++) {
      int start = role * PART_CAPACITY;
      int length = Math.min(PART_CAPACITY, image.length - start);
      parts[role] = ByteBuffer.allocate(HEADER_SIZE + length).order(ByteOrder.nativeOrder())
          .putInt(FORMAT_VERSION).putInt(image.length).putInt(role)
          .putLong(digest).put(image, start, length).array();
    }
    return parts;
  }

  /**
   * Validates structural metadata and exact extent before callers allocate from stored lengths.
   * Complete-image agreement is checked by assemble, not by accepting an isolated fragment.
   */
  static void validatePart(byte[] part) {
    var buffer = ByteBuffer.wrap(part).order(ByteOrder.nativeOrder());
    if (part.length != partSize(buffer, part.length < HEADER_SIZE ? -1 : buffer.getInt(8))) {
      throw new IllegalArgumentException("Invalid statistics fragment extent");
    }
  }

  static int partSize(ByteBuffer buffer, long pageIndex) {
    int offset = buffer.position();
    if (buffer.remaining() < HEADER_SIZE || buffer.getInt(offset) != FORMAT_VERSION) {
      throw new IllegalArgumentException("Missing or invalid statistics fragment header");
    }
    int total = buffer.getInt(offset + Integer.BYTES);
    checkLength(total);
    int role = buffer.getInt(offset + 2 * Integer.BYTES);
    if (role != pageIndex || role < 0 || role > 1 || (role == 1 && total <= PART_CAPACITY)) {
      throw new IllegalArgumentException("Invalid statistics fragment role or address");
    }
    return HEADER_SIZE + Math.min(PART_CAPACITY, total - role * PART_CAPACITY);
  }

  /**
   * Assembles a complete image, ignoring stale continuation bytes for a one-part image.
   * Neither input is modified or retained.
   */
  static byte[] assemble(byte[] first, byte[] continuation) {
    validatePart(first);
    var header = ByteBuffer.wrap(first).order(ByteOrder.nativeOrder());
    int total = header.getInt(Integer.BYTES);
    if (header.getInt(2 * Integer.BYTES) != 0) {
      throw new IllegalArgumentException("Missing statistics first fragment");
    }
    byte[] image = new byte[total];
    System.arraycopy(first, HEADER_SIZE, image, 0, first.length - HEADER_SIZE);
    if (total > PART_CAPACITY) {
      if (continuation == null) {
        throw new IllegalArgumentException("Missing statistics continuation");
      }
      validatePart(continuation);
      // Version and length are included: equal digests alone do not establish matching metadata.
      byte[] expectedHeader = Arrays.copyOf(first, HEADER_SIZE);
      ByteBuffer.wrap(expectedHeader).order(ByteOrder.nativeOrder()).putInt(2 * Integer.BYTES, 1);
      if (!Arrays.equals(expectedHeader, Arrays.copyOf(continuation, HEADER_SIZE))) {
        throw new IllegalArgumentException("Mismatched statistics fragments");
      }
      System.arraycopy(continuation, HEADER_SIZE, image, PART_CAPACITY,
          continuation.length - HEADER_SIZE);
    }
    if (digest(image) != header.getLong(3 * Integer.BYTES)) {
      throw new IllegalArgumentException("Statistics image digest mismatch");
    }
    return image;
  }

  private static void checkLength(int length) {
    if (length < 0 || length > IMAGE_CAPACITY) {
      throw new IllegalArgumentException("Statistics image exceeds two-page budget");
    }
  }

  private static long digest(byte[] image) {
    return XXHashFactory.fastestInstance().hash64().hash(image, 0, image.length, 0L);
  }
}
