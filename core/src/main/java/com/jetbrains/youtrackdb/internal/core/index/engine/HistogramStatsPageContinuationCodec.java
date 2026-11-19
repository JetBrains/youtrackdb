package com.jetbrains.youtrackdb.internal.core.index.engine;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * Inactive one-or-two-page container for an opaque complete statistics image.
 * Each fragment starts after the durable header and carries the complete-image SHA-256.
 * The digest detects mixed images, not malicious tampering. No live writer uses this codec.
 */
final class HistogramStatsPageContinuationCodec {

  static final int FORMAT_VERSION = 2;
  static final int HEADER_SIZE = 3 * Integer.BYTES + 32;
  static final int PART_CAPACITY = 4096 - DurablePage.NEXT_FREE_POSITION - HEADER_SIZE;

  private HistogramStatsPageContinuationCodec() {
  }

  static byte[][] encode(byte[] image) {
    checkLength(image.length);
    byte[] digest = digest(image);
    int count = image.length > PART_CAPACITY ? 2 : 1;
    byte[][] parts = new byte[count][];
    for (int role = 0; role < count; role++) {
      int start = role * PART_CAPACITY;
      int length = Math.min(PART_CAPACITY, image.length - start);
      parts[role] = ByteBuffer.allocate(HEADER_SIZE + length).order(ByteOrder.nativeOrder())
          .putInt(FORMAT_VERSION).putInt(image.length).putInt(role)
          .put(digest).put(image, start, length).array();
    }
    return parts;
  }

  /**
   * Validates structural metadata and exact extent before callers allocate from stored lengths.
   * Complete-image agreement is checked by assemble, not by accepting an isolated fragment.
   */
  static void validatePart(byte[] part) {
    if (part.length < HEADER_SIZE) {
      throw new IllegalArgumentException("Truncated statistics fragment header");
    }
    var buffer = ByteBuffer.wrap(part).order(ByteOrder.nativeOrder());
    if (buffer.getInt() != FORMAT_VERSION) {
      throw new IllegalArgumentException("Unsupported statistics fragment version");
    }
    int total = buffer.getInt();
    checkLength(total);
    int role = buffer.getInt();
    if (role < 0 || role > 1 || (role == 1 && total <= PART_CAPACITY)) {
      throw new IllegalArgumentException("Invalid statistics fragment role");
    }
    int expected = Math.min(PART_CAPACITY, total - role * PART_CAPACITY);
    if (part.length != HEADER_SIZE + expected) {
      throw new IllegalArgumentException("Invalid statistics fragment extent");
    }
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
    if (!Arrays.equals(digest(image), Arrays.copyOfRange(first, 3 * Integer.BYTES, HEADER_SIZE))) {
      throw new IllegalArgumentException("Statistics image digest mismatch");
    }
    return image;
  }

  private static void checkLength(int length) {
    if (length < 0 || length > 2 * PART_CAPACITY) {
      throw new IllegalArgumentException("Statistics image exceeds two-page budget");
    }
  }

  private static byte[] digest(byte[] image) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(image);
    } catch (NoSuchAlgorithmException e) {
      // SHA-256 is mandatory in every Java implementation.
      throw new AssertionError(e);
    }
  }
}
