package com.jetbrains.youtrackdb.internal.core.storage.cache;

import static com.jetbrains.youtrackdb.internal.core.storage.cache.AbstractWriteCache.checkFileIdCompatibility;
import static com.jetbrains.youtrackdb.internal.core.storage.cache.AbstractWriteCache.composeFileId;
import static com.jetbrains.youtrackdb.internal.core.storage.cache.AbstractWriteCache.extractFileId;
import static com.jetbrains.youtrackdb.internal.core.storage.cache.AbstractWriteCache.extractStorageId;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

/**
 * Tests the four static bit-packing helpers on {@link AbstractWriteCache}:
 * {@code composeFileId}, {@code extractFileId}, {@code extractStorageId}, and
 * {@code checkFileIdCompatibility}.
 *
 * <p>The composed ID is a 64-bit packed value with {@code storageId} in the upper 32 bits
 * and {@code fileId} in the lower 32 bits. The unsigned mask {@code 0xFFFFFFFFL} ensures
 * negative {@code fileId}s round-trip without sign-extension corrupting the upper half.
 *
 * <p>Round-trip correctness is the primary invariant — every {@code (storageId, fileId)} pair
 * tested here is a value the WAL or storage subsystem may legitimately produce. A regression
 * would corrupt the file-id namespace and make stored data unreachable, so the tests cover
 * both signed and unsigned boundary values explicitly.
 */
public class AbstractWriteCacheStaticHelpersTest {

  /**
   * Round-trip with positive (storageId, fileId): compose then extract returns the originals
   * for both halves.
   */
  @Test
  public void testComposeAndExtractPositiveValues() {
    var packed = composeFileId(7, 42);
    assertEquals(42, extractFileId(packed));
    assertEquals(7, extractStorageId(packed));
  }

  /**
   * Round-trip with storageId 0 and fileId 0 — the common "first storage, first file" case
   * which must produce 0L (a recognisable sentinel in stored on-disk metadata).
   */
  @Test
  public void testComposeAndExtractZeroStorageZeroFile() {
    var packed = composeFileId(0, 0);
    assertEquals(0L, packed);
    assertEquals(0, extractFileId(packed));
    assertEquals(0, extractStorageId(packed));
  }

  /**
   * Pins {@code extractFileId}'s round-trip for a negative fileId: the unsigned mask
   * {@code 0xFFFFFFFFL} ensures {@code extractFileId} returns the original {@code int} after
   * narrowing. WOWCache uses negative fileIds as "booked but not yet added" sentinels, so
   * round-tripping the file half is load-bearing.
   *
   * <p><b>Asymmetric storage-id behaviour:</b> {@code composeFileId} ORs in the long-promoted
   * fileId without masking, so a negative fileId sign-extends and overwrites the upper 32
   * bits. {@code extractStorageId(composeFileId(7, -42))} therefore returns {@code -1}, not
   * {@code 7}. This is the <i>current</i> behaviour and is not a bug because WOWCache uses
   * {@code composeFileId} with a non-negative {@code fileId} on the storage-id-carrying call
   * sites — the negative-fileId values only round-trip through {@code extractFileId} (which
   * does have the mask). A regression that adds a mask to {@code composeFileId} would be
   * detectable by changing the assertion below.
   */
  @Test
  public void testComposeAndExtractNegativeFileIdFileHalfRoundTrips() {
    var packed = composeFileId(7, -42);
    assertEquals(
        "Negative fileId must round-trip through extractFileId via the unsigned mask",
        -42, extractFileId(packed));
    // Pin the asymmetry: storageId is corrupted because composeFileId does not mask the
    // long-promoted fileId. This is what the implementation does today.
    assertEquals(
        "Current behaviour: composeFileId does NOT mask the fileId before OR-ing, so a"
            + " negative fileId corrupts the storageId half — extractStorageId returns -1.",
        -1, extractStorageId(packed));
  }

  /**
   * Boundary: {@code Integer.MIN_VALUE} as fileId — the most negative 32-bit value.
   * extractFileId round-trips it via the unsigned mask. extractStorageId returns -1 due to
   * the same composeFileId sign-extension as above.
   */
  @Test
  public void testComposeAndExtractIntegerMinValueFileId() {
    var packed = composeFileId(3, Integer.MIN_VALUE);
    assertEquals(Integer.MIN_VALUE, extractFileId(packed));
    assertEquals(
        "Current behaviour: composeFileId sign-extension corrupts storageId for negative"
            + " fileIds.",
        -1, extractStorageId(packed));
  }

  /**
   * Boundary: {@code Integer.MAX_VALUE} as fileId — positive, so no sign-extension; the
   * storageId half round-trips cleanly.
   */
  @Test
  public void testComposeAndExtractIntegerMaxValueFileId() {
    var packed = composeFileId(3, Integer.MAX_VALUE);
    assertEquals(Integer.MAX_VALUE, extractFileId(packed));
    assertEquals(3, extractStorageId(packed));
  }

  /** Boundary: {@code Integer.MAX_VALUE} as storageId. */
  @Test
  public void testComposeAndExtractIntegerMaxValueStorageId() {
    var packed = composeFileId(Integer.MAX_VALUE, 99);
    assertEquals(99, extractFileId(packed));
    assertEquals(Integer.MAX_VALUE, extractStorageId(packed));
  }

  /**
   * Pinned bit-pattern check: storageId 1, fileId 0 must produce {@code 1L << 32 = 0x1_0000_0000}.
   * A formula refactor that swaps the halves would change this exact value, catching the
   * regression before any round-trip test (which would still pass under a swapped formula).
   */
  @Test
  public void testComposeProducesDocumentedBitPattern() {
    assertEquals(0x1_0000_0000L, composeFileId(1, 0));
  }

  /**
   * checkFileIdCompatibility "rebases" an external fileId onto a different storageId, keeping
   * the lower 32 bits (the actual file id) and replacing the upper 32 bits.
   */
  @Test
  public void testCheckFileIdCompatibilityRebasesStorageId() {
    var original = composeFileId(7, 42);
    // Re-base to storage 9 — extraction must now return (9, 42)
    var rebased = checkFileIdCompatibility(9, original);
    assertEquals(42, extractFileId(rebased));
    assertEquals(9, extractStorageId(rebased));
  }

  /**
   * Re-basing to the same storageId is a no-op and must produce the same packed value.
   */
  @Test
  public void testCheckFileIdCompatibilityIdempotentForSameStorage() {
    var original = composeFileId(7, 42);
    assertEquals(original, checkFileIdCompatibility(7, original));
  }

  /**
   * Falsifiability: a packed ID with storage 7 must not equal a packed ID with storage 8 even
   * when the lower file-id bits match. Pins the contract that the storage-id half actually
   * participates in identity.
   */
  @Test
  public void testComposedIdsWithDifferentStorageAreNotEqual() {
    assertNotEquals(composeFileId(7, 42), composeFileId(8, 42));
  }
}
