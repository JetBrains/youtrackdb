package com.jetbrains.youtrackdb.internal.core.storage.memory;

import static org.junit.Assert.assertEquals;

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
   */
  @Test
  public void freshFileBothSurfacesReportZero() {
    final var viaLegacy = cache.getFilledUpTo(fileId);
    final var viaHelper = cache.physicalSizeForBackupSnapshot(fileId);

    assertEquals("fresh in-memory file must report 0 pages via getFilledUpTo", 0L, viaLegacy);
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
        "physicalSizeForBackupSnapshot must agree across multiple extends",
        viaLegacy,
        viaHelper);
  }
}
