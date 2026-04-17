package com.jetbrains.youtrackdb.internal.core.storage.collection.v2;

import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.CacheEntryChanges;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;

/**
 * A single page of a {@link CollectionDirtyPageBitSet}, storing a flat array of bits. Each bit
 * corresponds to one data page in the collection's {@code .cdt} file and indicates whether that
 * page contains at least one stale record's start chunk.
 *
 * <h2>Page Layout</h2>
 *
 * <pre>{@code
 *  Byte offset (relative to NEXT_FREE_POSITION):
 *  +-----------------------------------------------+
 *  | bits[0..USABLE_BYTES-1]                       |
 *  | Each byte packs 8 bits (LSB = lowest index).  |
 *  +-----------------------------------------------+
 *
 *  For the default 8 KB page size (MAX_PAGE_SIZE_BYTES = 8192, NEXT_FREE_POSITION = 28):
 *    - Usable bytes = 8164
 *    - Bits per page = 8164 * 8 = 65,312
 * }</pre>
 *
 * @see CollectionDirtyPageBitSet
 */
final class DirtyPageBitSetPage extends DurablePage {

  /** Number of usable bytes for bit storage (page size minus the durable page header). */
  static final int USABLE_BYTES = MAX_PAGE_SIZE_BYTES - NEXT_FREE_POSITION;

  /** Number of bits that can be stored on a single page. */
  static final int BITS_PER_PAGE = USABLE_BYTES * Byte.SIZE;

  DirtyPageBitSetPage(CacheEntry cacheEntry) {
    super(cacheEntry);
  }

  /** Zeroes out the entire usable area, clearing all bits. */
  void init() {
    setBinaryValue(NEXT_FREE_POSITION, new byte[USABLE_BYTES]);

    var cacheEntry = getCacheEntry();
    if (cacheEntry instanceof CacheEntryChanges cec) {
      cec.registerPageOperation(
          new DirtyPageBitSetPageInitOp(
              cacheEntry.getPageIndex(), cacheEntry.getFileId(),
              0, cec.getInitialLSN()));
    }
  }

  /**
   * Sets the bit at the given index within this page.
   *
   * @param bitIndex index of the bit to set (0-based, must be in [0, BITS_PER_PAGE))
   */
  void setBit(int bitIndex) {
    assert bitIndex >= 0 && bitIndex < BITS_PER_PAGE;

    var byteOffset = NEXT_FREE_POSITION + bitIndex / Byte.SIZE;
    var currentByte = getByteValue(byteOffset);
    var mask = (byte) (1 << (bitIndex % Byte.SIZE));
    setByteValue(byteOffset, (byte) (currentByte | mask));

    var cacheEntry = getCacheEntry();
    if (cacheEntry instanceof CacheEntryChanges cec) {
      cec.registerPageOperation(
          new DirtyPageBitSetPageSetBitOp(
              cacheEntry.getPageIndex(), cacheEntry.getFileId(),
              0, cec.getInitialLSN(), bitIndex));
    }
  }

  /**
   * Clears the bit at the given index within this page.
   *
   * @param bitIndex index of the bit to clear (0-based, must be in [0, BITS_PER_PAGE))
   */
  void clearBit(int bitIndex) {
    assert bitIndex >= 0 && bitIndex < BITS_PER_PAGE;

    var byteOffset = NEXT_FREE_POSITION + bitIndex / Byte.SIZE;
    var currentByte = getByteValue(byteOffset);
    var mask = (byte) (1 << (bitIndex % Byte.SIZE));
    setByteValue(byteOffset, (byte) (currentByte & ~mask));

    var cacheEntry = getCacheEntry();
    if (cacheEntry instanceof CacheEntryChanges cec) {
      cec.registerPageOperation(
          new DirtyPageBitSetPageClearBitOp(
              cacheEntry.getPageIndex(), cacheEntry.getFileId(),
              0, cec.getInitialLSN(), bitIndex));
    }
  }

  /**
   * Returns {@code true} if the bit at the given index is set.
   *
   * @param bitIndex index of the bit to test (0-based, must be in [0, BITS_PER_PAGE))
   */
  boolean isBitSet(int bitIndex) {
    assert bitIndex >= 0 && bitIndex < BITS_PER_PAGE;

    var byteOffset = NEXT_FREE_POSITION + bitIndex / Byte.SIZE;
    var currentByte = getByteValue(byteOffset);
    var mask = (byte) (1 << (bitIndex % Byte.SIZE));
    return (currentByte & mask) != 0;
  }

  /**
   * Returns the index of the first set bit at or after {@code fromBitIndex} within this page,
   * or {@code -1} if no set bit exists in the range [{@code fromBitIndex}, {@link #BITS_PER_PAGE}).
   *
   * @param fromBitIndex the starting bit index (inclusive, 0-based)
   */
  int nextSetBit(int fromBitIndex) {
    assert fromBitIndex >= 0;

    if (fromBitIndex >= BITS_PER_PAGE) {
      return -1;
    }

    // Start scanning from the byte containing fromBitIndex.
    var byteIndex = fromBitIndex / Byte.SIZE;
    var bitInByte = fromBitIndex % Byte.SIZE;

    // Check the first (partial) byte — mask off bits below fromBitIndex.
    var currentByte = (getByteValue(NEXT_FREE_POSITION + byteIndex) & 0xFF) >>> bitInByte;
    if (currentByte != 0) {
      return byteIndex * Byte.SIZE + bitInByte + Integer.numberOfTrailingZeros(currentByte);
    }

    // Scan remaining whole bytes.
    byteIndex++;
    var maxByteIndex = USABLE_BYTES;
    while (byteIndex < maxByteIndex) {
      currentByte = getByteValue(NEXT_FREE_POSITION + byteIndex) & 0xFF;
      if (currentByte != 0) {
        return byteIndex * Byte.SIZE + Integer.numberOfTrailingZeros(currentByte);
      }
      byteIndex++;
    }

    return -1;
  }
}
