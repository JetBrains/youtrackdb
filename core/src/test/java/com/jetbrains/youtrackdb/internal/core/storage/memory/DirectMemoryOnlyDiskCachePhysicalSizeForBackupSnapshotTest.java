package com.jetbrains.youtrackdb.internal.core.storage.memory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import com.jetbrains.youtrackdb.internal.core.exception.StorageException;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CachePointer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Contract pin for
 * {@link DirectMemoryOnlyDiskCache#physicalSizeForBackupSnapshot(long)} on the in-memory
 * engine. The helper is a named alias for
 * {@link DirectMemoryOnlyDiskCache#getFilledUpTo(long)} mirroring the disk engine's
 * {@code WOWCachePhysicalSizeForBackupSnapshotTest}. The contract under test is the same:
 * for any observable file state the helper returns the same value as {@code getFilledUpTo}.
 * The in-memory engine has no lock or null-file guard to verify; the parity is what we
 * care about, since the {@link com.jetbrains.youtrackdb.internal.core.storage.cache.WriteCache}
 * audit-grep contract treats both engines uniformly.
 */
public class DirectMemoryOnlyDiskCachePhysicalSizeForBackupSnapshotTest {

  private static final int PAGE_SIZE = 1024;
  private static final int STORAGE_ID = 17;
  private static final String STORAGE_NAME =
      "directMemoryPhysicalSizeForBackupSnapshotTestStorage";
  private static final String FILE_NAME = "physicalSizeForBackupSnapshot.dat";

  private DirectMemoryOnlyDiskCache cache;
  private long fileId;

  @Before
  public void setUp() {
    cache = new DirectMemoryOnlyDiskCache(PAGE_SIZE, STORAGE_ID, STORAGE_NAME);
    fileId = cache.addFile(FILE_NAME);
  }

  @After
  public void tearDown() {
    if (cache != null) {
      cache.delete();
      cache = null;
    }
  }

  /**
   * Fresh, never-extended file: both surfaces must report zero pages. A divergence
   * would point at a helper that short-circuited around {@code MemoryFile.size()} (the
   * only correct source on the in-memory engine).
   *
   * <p>Pin both the literal expected value AND the parity to {@code getFilledUpTo}: the
   * literal pin keeps the helper independently falsifiable, the parity pin guards against
   * a future divergence between the two surfaces.
   */
  @Test
  public void freshFileBothSurfacesReportZero() {
    final var viaLegacy = cache.getFilledUpTo(fileId);
    final var viaHelper = cache.physicalSizeForBackupSnapshot(fileId);

    assertEquals("fresh in-memory file must report 0 pages via getFilledUpTo", 0L, viaLegacy);
    assertEquals(
        "physicalSizeForBackupSnapshot must observe 0 pages on a fresh file",
        0L,
        viaHelper);
    assertEquals(
        "physicalSizeForBackupSnapshot must agree on a fresh file",
        viaLegacy,
        viaHelper);
  }

  /**
   * After one extend: both surfaces must report a single-page file. Releasing the
   * returned pointer keeps the in-memory frame pool tidy across tests in the same
   * suite.
   */
  @Test
  public void afterOneExtendBothSurfacesReportOnePage() {
    final CachePointer pointer = cache.loadOrAdd(fileId, 0L, false);
    try {
      final var viaLegacy = cache.getFilledUpTo(fileId);
      final var viaHelper = cache.physicalSizeForBackupSnapshot(fileId);

      assertEquals(
          "single extend must advance the in-memory high-watermark to 1",
          1L,
          viaLegacy);
      assertEquals(
          "physicalSizeForBackupSnapshot must observe 1 page after a single extend",
          1L,
          viaHelper);
      assertEquals(
          "physicalSizeForBackupSnapshot must observe the same one-page extend",
          viaLegacy,
          viaHelper);
    } finally {
      pointer.decrementReadersReferrer();
    }
  }

  /**
   * After multiple extends: pin parity across a wider range so a regression that
   * truncated the helper's return (e.g. integer overflow / cast bug) would surface here
   * rather than only at the {@code 0} / {@code 1} corners.
   */
  @Test
  public void afterMultipleExtendsBothSurfacesAgree() {
    for (int i = 0; i < 5; i++) {
      cache.loadOrAdd(fileId, i, false).decrementReadersReferrer();
    }

    final var viaLegacy = cache.getFilledUpTo(fileId);
    final var viaHelper = cache.physicalSizeForBackupSnapshot(fileId);

    assertEquals("five extends must advance getFilledUpTo to 5 pages", 5L, viaLegacy);
    assertEquals(
        "physicalSizeForBackupSnapshot must observe 5 pages after five extends",
        5L,
        viaHelper);
    assertEquals(
        "physicalSizeForBackupSnapshot must agree across multiple extends",
        viaLegacy,
        viaHelper);
  }

  /**
   * Post-truncate: {@code DirectMemoryOnlyDiskCache.truncateFile} resets the in-memory
   * file via {@code file.clear()} while keeping the file live. Both surfaces must
   * observe the reset immediately. A future implementer that short-circuited the helper
   * (e.g. cached the pre-truncate high-watermark) would surface here.
   */
  @Test
  public void postTruncateBothSurfacesReportZero() {
    for (int i = 0; i < 3; i++) {
      cache.loadOrAdd(fileId, i, false).decrementReadersReferrer();
    }
    cache.truncateFile(fileId);

    final var viaLegacy = cache.getFilledUpTo(fileId);
    final var viaHelper = cache.physicalSizeForBackupSnapshot(fileId);

    assertEquals("post-truncate file must report 0 pages via getFilledUpTo", 0L, viaLegacy);
    assertEquals(
        "physicalSizeForBackupSnapshot must observe the truncate immediately",
        0L,
        viaHelper);
    assertEquals(
        "physicalSizeForBackupSnapshot must agree with getFilledUpTo post-truncate",
        viaLegacy,
        viaHelper);
  }

  /**
   * Deleted-file engine asymmetry: the in-memory engine's {@code getFilledUpTo} throws
   * {@code StorageException} on a missing/deleted file (the disk engine returns 0
   * instead — see {@code WOWCachePhysicalSizeForBackupSnapshotTest#deletedFileBothSurfacesReportZero}).
   * The Javadoc on {@code DirectMemoryOnlyDiskCache.physicalSizeForBackupSnapshot} claims
   * "same semantics as getFilledUpTo"; this test pins that claim by asserting the helper
   * propagates the same exception. If the implementation evolves to align with the disk
   * engine (or vice versa), this test must be updated alongside.
   */
  @Test
  public void deletedFileBehaviorIsDocumented() {
    cache.loadOrAdd(fileId, 0L, false).decrementReadersReferrer();
    cache.deleteFile(fileId);

    assertThrows(
        "In-memory engine asymmetry: getFilledUpTo throws on a deleted file",
        StorageException.class,
        () -> cache.getFilledUpTo(fileId));
    assertThrows(
        "physicalSizeForBackupSnapshot must propagate the same exception on a deleted file",
        StorageException.class,
        () -> cache.physicalSizeForBackupSnapshot(fileId));
  }
}
